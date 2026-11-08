package ai.quranlab.tajweedformer.npu

import ai.quranlab.tajweedformer.J
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.JsonException
import ai.quranlab.tajweedformer.RecognizerException
import ai.quranlab.tajweedformer.TajweedHead
import java.io.File

/** One integer tensor of the NPU model: `q = clamp(rint(v / scale) + zeroPoint)`. */
data class NpuTensor(val name: String, val shape: List<Int>, val dtype: String, val scale: Double, val zeroPoint: Int) {
    val size: Int get() = shape.fold(1) { a, b -> a * b }
}

/** A state carried from output [output] of one chunk to input [input] of the next. */
data class NpuState(val input: String, val output: String, val requantRatio: Double)

/**
 * One part of a chained build (`parts` in io.json): the LiteRT model [file] (a name next to io.json), run as the
 * [index]-th, with its inputs and outputs by name in the model's own order and shapes. A part input is a graph
 * input or an output of an earlier part with the same element count; the bytes pass through unchanged (the board
 * runner relabels them to the part's shape). Intermediate tensors carry the scale of no graph tensor: their
 * [NpuTensor.scale] is NaN (the host never dequantises them).
 */
data class NpuPart(val index: Int, val file: String, val inputs: Map<String, NpuTensor>, val outputs: Map<String, NpuTensor>)

/** What the host does to a head output: dequantise, then [op]. */
data class NpuHeadSpec(val output: String, val head: TajweedHead, val op: HostOp)

enum class HostOp { LOG_SOFTMAX, SOFTMAX, NONE }

/**
 * The host contract of an NPU build (`npu/<soc>/<ctx>/<name>.io.json`, written by the NPU compile tools):
 * the stream geometry, every input and output with its quantisation, the state pairs with their
 * requantisation ratios, and which output is which head with the host op that finishes it.
 *
 * Rules (io.json's own text): states start at zero; chunk c reads fbank frames
 * `[c * shift, c * shift + window)`, quantised with x's scale; every `new_<state>` output becomes
 * the next chunk's `<state>` input, copied when `requant_ratio == 1`, else
 * `clamp(rint(q * requant_ratio))` in double; the keep / fill inputs come from the mask table of
 * chunk set `min(c, mask_chunk_sets - 1)`; `zf_cv_<hex>` inputs are the constant
 * `float.fromhex(<hex>)` quantised; head outputs are logits, finished on the host by log-softmax
 * (CTC heads) or softmax (attributes, gem); boundary and conf stay logits.
 *
 * A CHAINED build (the Exynos 2500: [parts] not empty) is one graph cut into several models. The contract above
 * still describes the whole graph (inputs, outputs, states, heads, masks), and [parts] says how to run it: each
 * part in order over one name-keyed pool of int16 tensors (the whole graph's inputs, then every part's outputs),
 * exactly as the board runner (`run_model --graph=P0,...,Pn` stream mode) did when the build was verified; see
 * [NpuChainRunner]. Its host constants (`zf_cm*`, `zf_ck*`, `zf_zero_*`) are int16 codes folded into io.json by
 * the publisher ([constantCodes], key `constant_codes`, encoding `int16le-base64`).
 */
class NpuContract(
    val soc: String,
    val context: String,
    val windowFrames: Int,
    val shiftFrames: Int,
    val chunkMs: Int,
    val leftFrames: Int,
    val maskChunkSets: Int,
    val inputs: Map<String, NpuTensor>,
    val outputs: Map<String, NpuTensor>,
    val states: List<NpuState>,
    val heads: List<NpuHeadSpec>,
    /** The keep / fill inputs (from the mask table). */
    val maskInputs: List<String>,
    /** zf_cv_* constant inputs and their float value. */
    val constants: Map<String, Double>,
    /** A chained build's parts in run order; empty for a one-model build. */
    val parts: List<NpuPart> = emptyList(),
    /** Constant inputs given as int16 codes (io.json `constant_codes`), fed as they are. */
    val constantCodes: Map<String, ShortArray> = emptyMap(),
) {
    /**
     * Part [k] as a contract of its own (its inputs and outputs only; no states, heads or masks), for the runner
     * factory that loads one model: each part's runner sees its own model's tensor names and sizes.
     */
    fun partContract(k: Int): NpuContract {
        val p = parts[k]
        return NpuContract(soc, context, windowFrames, shiftFrames, chunkMs, leftFrames, maskChunkSets,
            p.inputs, p.outputs, emptyList(), emptyList(), emptyList(), emptyMap())
    }

    val x: NpuTensor get() = inputs.getValue("x")

    companion object {
        @JvmStatic
        fun read(file: File): NpuContract = parse(file.readText(Charsets.UTF_8))

        /** Parses io.json; throws [RecognizerException.BackendUnavailable] for anything this host
         *  loop does not implement (so the NPU is not used). */
        @JvmStatic
        fun parse(text: String): NpuContract = try {
            val m = J.obj(Json.parse(text), "io.json")
            val g = J.obj(m["geometry"], "geometry")
            fun tensors(key: String) = J.list(m, key).map { v ->
                val o = J.obj(v, key)
                val dtype = J.str(o, "dtype", "$key.dtype")
                if (dtype != "int16") throw JsonException("$key ${o["name"]} is $dtype; the host loop takes int16")
                NpuTensor(J.str(o, "name", "$key.name"), J.list(o, "shape", "$key.shape").map { (it as Double).toInt() }, dtype,
                    J.num(o, "scale", "$key.scale"), J.int(o, "zero_point", "$key.zero_point"))
            }.associateBy { it.name }
            val inputs = tensors("inputs")
            val outputs = tensors("outputs")
            val states = J.list(m, "states").map { v ->
                val o = J.obj(v, "states")
                NpuState(J.str(o, "input", "states.input"), J.str(o, "output", "states.output"), J.num(o, "requant_ratio", "states.requant_ratio"))
            }
            val headsObj = J.obj(m["heads"], "heads")
            val heads = headsObj.map { (out, v) ->
                val o = J.obj(v, "heads.$out")
                val export = J.str(o, "export_output", "heads.$out.export_output")
                val head = TajweedHead.fromKey(export) ?: throw JsonException("head $export is not a V4 head")
                val opText = J.str(o, "host_op", "heads.$out.host_op")
                val op = when {
                    opText == "log_softmax" -> HostOp.LOG_SOFTMAX
                    opText == "softmax" -> HostOp.SOFTMAX
                    opText.startsWith("none") -> HostOp.NONE
                    else -> throw JsonException("unknown host op $opText")
                }
                NpuHeadSpec(out, head, op)
            }
            val other = (m["other_inputs"] as? List<*>)?.map { it as String } ?: emptyList()
            val stateInputs = states.map { it.input }.toSet()
            val masks = inputs.keys.filter { it.startsWith("zf_keep") || it.startsWith("zf_fill") }
            val codes = constantCodes(m, inputs)
            val constants = HashMap<String, Double>()
            for (name in inputs.keys) {
                if (name == "x" || name in stateInputs || name in masks || name in codes) continue
                if (name.startsWith("zf_cv_")) {
                    constants[name] = java.lang.Double.parseDouble(hexFloat(name.removePrefix("zf_cv_")))
                } else {
                    throw JsonException("input $name is neither x, a state, a mask, a zf_cv_ constant nor in constant_codes")
                }
            }
            for (name in other) if (name !in inputs) throw JsonException("other input $name is not an input")
            for (s in states) {
                if (s.input !in inputs || s.output !in outputs) throw JsonException("state ${s.input} / ${s.output} is not an input / output")
            }
            for (h in heads) if (h.output !in outputs) throw JsonException("head output ${h.output} is not an output")
            val parts = parts(m, inputs, outputs)
            NpuContract(
                soc = J.str(m, "soc"), context = J.str(m, "context"),
                windowFrames = J.int(g, "window_frames"), shiftFrames = J.int(g, "shift_frames"), chunkMs = J.int(g, "chunk_ms"),
                leftFrames = J.int(g, "left_frames"), maskChunkSets = J.int(g, "mask_chunk_sets"),
                inputs = inputs, outputs = outputs, states = states, heads = heads, maskInputs = masks, constants = constants,
                parts = parts, constantCodes = codes,
            )
        } catch (e: JsonException) {
            throw RecognizerException.BackendUnavailable("io.json: ${e.message}")
        } catch (e: ClassCastException) {
            throw RecognizerException.BackendUnavailable("io.json: ${e.message}")
        } catch (e: NumberFormatException) {
            throw RecognizerException.BackendUnavailable("io.json: ${e.message}")
        } catch (e: IllegalArgumentException) { // bad base64
            throw RecognizerException.BackendUnavailable("io.json: ${e.message}")
        }

        private const val CODES_ENCODING = "int16le-base64"

        /** io.json `constant_codes` ({"encoding": "int16le-base64", "inputs": {name: base64}}): every name an
         *  input, every value exactly that input's element count of int16 little endian codes. */
        private fun constantCodes(m: Map<String, Any?>, inputs: Map<String, NpuTensor>): Map<String, ShortArray> {
            val c = J.objOrNull(m, "constant_codes") ?: return emptyMap()
            val enc = J.str(c, "encoding", "constant_codes.encoding")
            if (enc != CODES_ENCODING) throw JsonException("constant_codes encoding $enc")
            return J.obj(c["inputs"], "constant_codes.inputs").mapValues { (name, v) ->
                val t = inputs[name] ?: throw JsonException("constant $name is not an input")
                val b = java.util.Base64.getDecoder().decode(v as? String ?: throw JsonException("constant $name is not base64 text"))
                if (b.size != 2 * t.size) throw JsonException("constant $name has ${b.size} bytes, ${t.shape} needs ${2 * t.size}")
                ShortArray(t.size) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
            }
        }

        /**
         * io.json `parts` (absent: a one-model build), checked so that a chain can only run as the board ran it:
         * indices 0..n-1 in order, distinct plain file names, int16 tensors, every part input a graph input or an
         * EARLIER part's output with the same element count (the board runner fed zeros for a name it could not
         * resolve; here that is refused), no part output re-using a graph input's name, every graph output made
         * by a part with its element count.
         */
        private fun parts(m: Map<String, Any?>, inputs: Map<String, NpuTensor>, outputs: Map<String, NpuTensor>): List<NpuPart> {
            val list = m["parts"] as? List<*> ?: return emptyList()
            if (list.isEmpty()) throw JsonException("parts is empty")
            val avail = HashMap<String, Int>()
            for ((n, t) in inputs) avail[n] = t.size
            val made = HashSet<String>()
            val files = HashSet<String>()
            val parts = list.mapIndexed { k, v ->
                val o = J.obj(v, "parts[$k]")
                if (J.int(o, "index") != k) throw JsonException("parts[$k] has index ${o["index"]}")
                val file = J.str(o, "file", "parts[$k].file")
                if (file.isEmpty() || file.startsWith(".") || file.any { it == '/' || it == '\\' || it == ':' } || !files.add(file)) {
                    throw JsonException("parts[$k].file $file")
                }
                fun tensors(key: String): List<NpuTensor> {
                    val ts = J.list(o, key, "parts[$k].$key").map { e ->
                        val t = J.obj(e, "parts[$k].$key")
                        val name = J.str(t, "name", "parts[$k].$key.name")
                        val dtype = (t["dtype"] as? String) ?: "int16"
                        if (dtype != "int16") throw JsonException("part $k tensor $name is $dtype")
                        val shape = J.list(t, "shape", "parts[$k].$key.shape").map { (it as Double).toInt() }
                        val g = inputs[name] ?: outputs[name]
                        NpuTensor(name, shape, "int16", g?.scale ?: Double.NaN, g?.zeroPoint ?: 0)
                    }
                    if (ts.map { it.name }.toSet().size != ts.size) throw JsonException("part $k lists a tensor twice")
                    return ts
                }
                val ins = tensors("inputs")
                for (t in ins) {
                    val n = avail[t.name] ?: throw JsonException("part $k input ${t.name} is neither a graph input nor an earlier part's output")
                    if (n != t.size) throw JsonException("part $k input ${t.name} ${t.shape} does not match its source's $n values")
                }
                val outs = tensors("outputs")
                for (t in outs) {
                    if (t.name in inputs) throw JsonException("part $k output ${t.name} re-uses a graph input's name")
                    avail[t.name] = t.size
                    made.add(t.name)
                }
                NpuPart(k, file, ins.associateBy { it.name }, outs.associateBy { it.name })
            }
            for ((n, t) in outputs) {
                if (n !in made || avail[n] != t.size) throw JsonException("graph output $n is not made by any part with its size")
            }
            return parts
        }

        /** Python's float.hex text ("0x1.8p+1", or the name's form with "m" for "-") as Java parses it. */
        internal fun hexFloat(s: String): String {
            var t = s
            if (t.startsWith("m")) t = "-" + t.substring(1)
            if (!t.contains("0x")) t = t.replaceFirst(Regex("^(-?)"), "$10x")
            if (!t.contains('p')) t += "p0"
            return t
        }
    }
}

/**
 * The keep / fill inputs of every chunk set, already quantised (`npu/<soc>/<ctx>/<name>.masks.json`).
 * They depend only on the chunk index, so the publisher tabulates them once from the build's
 * `_masks.onnx` and io.json (tajweedformer-coreml `publish/npu_masks.py`) and no mask graph runs on
 * the phone.
 *
 * Schema `quranlab.npu-masks/1` (npu_masks.py):
 *
 * ```json
 * {"format": "quranlab.npu-masks/1", "soc": "tensor_g3", "context": "c16_l64", "mask_chunk_sets": 5,
 *  "rule": "...", "source": {...},
 *  "inputs": {"zf_keep0": {"shape": [...], "scale": s, "zero_point": z,
 *                          "sets": [[int16 codes of chunk set 0, row-major], ...]}, ...}}
 * ```
 *
 * Chunk c feeds set `min(c, mask_chunk_sets - 1)` of every input. Codes are
 * `clip(rint(v / scale) + zero_point)` of `keep = mask_graph(zf_frame_mask = (shift / 2 * c <=
 * arange(left)))` and `fill = -30 * (1 - keep)`, as the device runner was fed. The table is checked
 * against io.json (every keep / fill input, its shape, scale and zero point, the set count).
 */
class NpuMasks(val sets: List<Map<String, ShortArray>>) {
    /** The inputs of chunk index [chunk]. */
    fun forChunk(chunk: Int): Map<String, ShortArray> = sets[minOf(chunk, sets.size - 1)]

    companion object {
        const val FORMAT = "quranlab.npu-masks/1"

        @JvmStatic
        fun read(file: File, contract: NpuContract): NpuMasks = parse(file.readText(Charsets.UTF_8), contract)

        @JvmStatic
        fun parse(text: String, contract: NpuContract): NpuMasks = try {
            val m = J.obj(Json.parse(text), "masks.json")
            if (J.str(m, "format") != FORMAT) throw JsonException("format ${m["format"]}")
            if (J.str(m, "soc") != contract.soc || J.str(m, "context") != contract.context) throw JsonException("another build's table")
            val n = J.int(m, "mask_chunk_sets")
            if (n != contract.maskChunkSets) throw JsonException("$n sets, io.json says ${contract.maskChunkSets}")
            val inputs = J.obj(m["inputs"], "inputs")
            val perInput = contract.maskInputs.associateWith { name ->
                val o = J.obj(inputs[name], "inputs.$name")
                val t = contract.inputs.getValue(name)
                val shape = J.list(o, "shape", "$name.shape").map { (it as Double).toInt() }
                if (shape != t.shape || J.num(o, "scale", "$name.scale") != t.scale || J.int(o, "zero_point", "$name.zero_point") != t.zeroPoint) {
                    throw JsonException("$name's shape or quantisation differs from io.json")
                }
                val sets = J.list(o, "sets", "$name.sets")
                if (sets.size != n) throw JsonException("$name has ${sets.size} sets")
                sets.map { v ->
                    val codes = v as? List<*> ?: throw JsonException("$name.sets")
                    if (codes.size != t.size) throw JsonException("$name has ${codes.size} codes, ${t.size} expected")
                    ShortArray(codes.size) { i ->
                        val x = (codes[i] as Double)
                        if (x < -32768 || x > 32767 || x != Math.rint(x)) throw JsonException("$name code $x")
                        x.toInt().toShort()
                    }
                }
            }
            NpuMasks((0 until n).map { k -> perInput.mapValues { it.value[k] } })
        } catch (e: JsonException) {
            throw RecognizerException.BackendUnavailable("masks.json: ${e.message}")
        } catch (e: ClassCastException) {
            throw RecognizerException.BackendUnavailable("masks.json: ${e.message}")
        }
    }
}
