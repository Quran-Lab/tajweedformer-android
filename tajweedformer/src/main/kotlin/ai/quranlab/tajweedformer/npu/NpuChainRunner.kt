package ai.quranlab.tajweedformer.npu

/**
 * A chained NPU build ([NpuContract.parts], the Exynos 2500's 9 LiteRT models) as ONE [NpuRunner]: it takes the
 * whole graph's inputs and returns the whole graph's outputs, so [NpuStreamer] (x windows, mask sets, constants,
 * state feedback, heads) is the same code for one model and for a chain.
 *
 * One chunk, exactly as the board runner that verified the build (LiteRT `run_model --graph=P0,...,Pn` in stream
 * mode): a pool of int16 tensors keyed by name starts with the whole graph's inputs (x, masks, constants, states);
 * part k reads each of its inputs from the pool by name (the bytes as they are, whatever the part's own shape;
 * io.json was checked to give the same element count) and writes each of its outputs into the pool by name; after
 * the last part the graph outputs (heads and `new_<state>`) are taken from the pool. The streamer then turns each
 * `new_<state>` into the next chunk's `<state>` (copied, or requantised by its ratio), as the board runner did after
 * its chain.
 *
 * The one difference: where the board runner fed zeros for a name it could not resolve, this throws (which the
 * engine's watchdog treats as an NPU failure and moves the take to the CPU). [NpuContract.parse] already refuses a
 * chain with an unresolved input, so that only happens when a part's runner returns too little.
 *
 * [runners] are the parts' runners in order; this runner owns them and closes them all.
 */
internal class NpuChainRunner(private val contract: NpuContract, private val runners: List<NpuRunner>) : NpuRunner {
    init {
        require(contract.parts.isNotEmpty()) { "not a chained build" }
        require(runners.size == contract.parts.size) { "${runners.size} runners for ${contract.parts.size} parts" }
    }

    override fun run(inputs: Map<String, ShortArray>): Map<String, ShortArray> {
        val pool = HashMap<String, ShortArray>(inputs.size * 2)
        pool.putAll(inputs)
        for ((k, part) in contract.parts.withIndex()) {
            val feed = LinkedHashMap<String, ShortArray>(part.inputs.size * 2)
            for ((name, t) in part.inputs) {
                val v = pool[name] ?: throw IllegalStateException("part $k (${part.file}): no tensor $name")
                if (v.size != t.size) throw IllegalStateException("part $k (${part.file}): $name has ${v.size} values, ${t.size} expected")
                feed[name] = v
            }
            val out = runners[k].run(feed)
            for ((name, t) in part.outputs) {
                val v = out[name] ?: throw IllegalStateException("part $k (${part.file}) gave no $name")
                if (v.size != t.size) throw IllegalStateException("part $k (${part.file}): $name has ${v.size} values, ${t.size} expected")
                pool[name] = v
            }
        }
        val result = HashMap<String, ShortArray>(contract.outputs.size * 2)
        for (name in contract.outputs.keys) result[name] = pool[name] ?: throw IllegalStateException("the chain made no $name")
        return result
    }

    override fun close() {
        var first: Exception? = null
        for (r in runners) {
            try { r.close() } catch (e: Exception) { if (first == null) first = e }
        }
        first?.let { throw it }
    }

    companion object {
        /**
         * Opens every part of [contract] with [open] (the part index and that part's own contract, see
         * [NpuContract.partContract]) and chains them. If opening part k fails, the parts opened before it are
         * closed and the error is thrown.
         */
        @JvmStatic
        fun open(contract: NpuContract, open: (Int, NpuContract) -> NpuRunner): NpuChainRunner {
            val opened = ArrayList<NpuRunner>(contract.parts.size)
            try {
                for (k in contract.parts.indices) opened.add(open(k, contract.partContract(k)))
            } catch (e: Throwable) {
                for (r in opened) try { r.close() } catch (_: Exception) {}
                throw e
            }
            return NpuChainRunner(contract, opened)
        }
    }
}
