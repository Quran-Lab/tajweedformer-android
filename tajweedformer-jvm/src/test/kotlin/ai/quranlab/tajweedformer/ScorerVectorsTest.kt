package ai.quranlab.tajweedformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The engine's V4 scorer, through this SDK's JNI binding, against the reference scorer's golden vectors
 * (the reference scorer's vectors): every clip's saved heads (concatenated over chunks)
 * scored as its expected row under each preset's remote config (MANIFEST.json "presets"), compared
 * with the saved reference verdicts: the scored path, every site's verdict and per-class best
 * alternative, enabled and flagged state and per-head and fused LLRs (within 1e-3); madd, qalqalah
 * and gemination items and flags. The comparison is the engine's own (tajweed/tests/run_vectors.py
 * `compare`), ported. The typed records the binding builds are checked against the same result's JSON.
 *
 * Needs (skipped when absent): `-Dtajweedformer.library=` a JNI library with zf_tajweed
 * (scripts/build_scorer_host.sh), and `TJ_SCORER_TABLE` = the folder scripts/make_scorer_table.py
 * writes (it names the vectors folder). The vectors (about 10 MB) are not in this repository.
 */
class ScorerVectorsTest {
    private val table: File? = (System.getenv("TJ_SCORER_TABLE") ?: System.getProperty("TJ_SCORER_TABLE"))
        ?.let(::File)?.takeIf { File(it, "clips.json").isFile }

    /** A .npy array inside an .npz: float32, little endian, C order. */
    class Npy(val shape: IntArray, val data: FloatArray)

    private fun readNpz(file: File): Map<String, Npy> {
        val out = HashMap<String, Npy>()
        ZipInputStream(file.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name.removeSuffix(".npy")] = parseNpy(z.readBytes())
            }
        }
        return out
    }

    private fun parseNpy(b: ByteArray): Npy {
        require(b[0] == 0x93.toByte() && String(b, 1, 5, Charsets.US_ASCII) == "NUMPY") { "not a .npy" }
        val major = b[6].toInt()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val (hlen, start) = if (major == 1) (bb.getShort(8).toInt() and 0xffff) to 10 else bb.getInt(8) to 12
        val header = String(b, start, hlen, Charsets.US_ASCII)
        require(header.contains("'descr': '<f4'")) { "not float32: $header" }
        require(header.contains("'fortran_order': False")) { "Fortran order: $header" }
        val shape = Regex("'shape': \\(([^)]*)\\)").find(header)!!.groupValues[1]
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        val n = shape.fold(1) { a, x -> a * x }
        val fb = ByteBuffer.wrap(b, start + hlen, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Npy(shape, FloatArray(n).also { fb.get(it) })
    }

    /** The take's heads: (n_chunks, frames, C) concatenated over chunks, as the engine's run_vectors.py does. */
    private fun heads(npz: Map<String, Npy>): TajweedHeads {
        val lp = npz.getValue("log_probs")
        val n40 = lp.shape[0] * lp.shape[1]
        val m = HashMap<TajweedHead, FloatArray>()
        for ((k, v) in npz) {
            val h = TajweedHead.fromKey(k) ?: continue
            m[h] = v.data
        }
        return TajweedHeads(n40, m)
    }

    // ------------------------------------------------------------------ the engine's compare(), ported

    private fun numEq(a: Any?, b: Any?, tol: Double): Boolean {
        if (a == null || b == null) return a == null && b == null
        val x = (a as Number).toDouble()
        val y = (b as Number).toDouble()
        if (x.isNaN() || y.isNaN()) return x.isNaN() && y.isNaN()
        return Math.abs(x - y) <= tol
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        else -> true
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(c: Map<String, Any?>, ref: Map<String, Any?>): List<String> {
        val bad = ArrayList<String>()
        if (c["path"] != ref["path"]) {
            bad.add("path differs: C ${c["path"]} | ref ${ref["path"]}")
            return bad
        }
        if (c["path_edit_cost"] != ref["path_edit_cost"]) bad.add("path_edit_cost C ${c["path_edit_cost"]} ref ${ref["path_edit_cost"]}")
        val pfc = c["path_forms"] as List<Map<String, Any?>>
        val pfr = ref["path_forms"] as List<Map<String, Any?>>
        for ((wc, wr) in pfc.zip(pfr)) {
            for (k in listOf("pick", "L", "R", "mode", "wajh", "cross")) {
                if (wc[k] != wr[k]) bad.add("path_forms word ${wr["word"]}: $k C ${wc[k]} ref ${wr[k]}")
            }
        }
        val sc = c["sites"] as List<Map<String, Any?>>
        val sr = ref["sites"] as List<Map<String, Any?>>
        if (sc.map { it["i"] } != sr.map { it["i"] }) {
            bad.add("site windows differ")
            return bad
        }
        for ((a, b) in sc.zip(sr)) {
            val tag = "site i=${b["i"]}"
            if (a["expected"] != b["expected"]) bad.add("$tag: expected C ${a["expected"]} ref ${b["expected"]}")
            if (truthy(a["flagged"]) != truthy(b["flagged"]) || a["class"] != b["class"]) {
                bad.add("$tag: verdict C ${a["flagged"]}/${a["class"]} ref ${b["flagged"]}/${b["class"]}")
            }
            val pa = a["per_class"] as Map<String, Map<String, Any?>>
            val pb = b["per_class"] as Map<String, Map<String, Any?>>
            if (pa.keys != pb.keys) {
                bad.add("$tag: classes C ${pa.keys.sorted()} ref ${pb.keys.sorted()}")
                continue
            }
            for ((cls, pr) in pb) {
                val pc = pa.getValue(cls)
                if (pc["alt"] != pr["alt"] || pc["alt_window"] != pr["alt_window"]) bad.add("$tag $cls: alt C ${pc["alt"]} ref ${pr["alt"]}")
                if (truthy(pc["enabled"]) != truthy(pr["enabled"]) || truthy(pc["flagged"]) != truthy(pr["flagged"])) {
                    bad.add("$tag $cls: enabled/flagged C ${pc["enabled"]}/${pc["flagged"]} ref ${pr["enabled"]}/${pr["flagged"]} (fused ${pr["fused"]} thr ${pr["threshold"]})")
                }
                for (f in listOf("llr_log_probs", "llr_inter1", "fused")) {
                    if (!numEq(pc[f], pr[f], 1e-3)) bad.add("$tag $cls: $f C ${pc[f]} ref ${pr[f]}")
                }
                if (!numEq(pc["threshold"], pr["threshold"], 1e-12)) bad.add("$tag $cls: threshold C ${pc["threshold"]} ref ${pr["threshold"]}")
            }
        }
        for ((part, pname) in listOf("madd" to "ratio", "qalqalah" to "p_burst", "gemination" to "p_held")) {
            if (c.containsKey(part) != ref.containsKey(part)) {
                bad.add("$part: present C ${c.containsKey(part)} ref ${ref.containsKey(part)}")
                continue
            }
            if (!ref.containsKey(part)) continue
            val pc = c[part] as Map<String, Any?>
            val pr = ref[part] as Map<String, Any?>
            val ic = pc["items"] as List<Map<String, Any?>>
            val ir = pr["items"] as List<Map<String, Any?>>
            if (ic.map { it["i"] } != ir.map { it["i"] }) {
                bad.add("$part: items C ${ic.map { it["i"] }} ref ${ir.map { it["i"] }}")
                continue
            }
            if (part == "madd" && !numEq(pc["unit_frames"], pr["unit_frames"], 1e-9)) bad.add("madd unit C ${pc["unit_frames"]} ref ${pr["unit_frames"]}")
            for ((a, b) in ic.zip(ir)) {
                val keys = listOf("flagged") + if (part == "madd") listOf("cls", "count", "aared_at_stop", "scoring") else emptyList()
                for (k in keys) if (a[k] != b[k]) bad.add("$part i=${b["i"]}: $k C ${a[k]} ref ${b[k]}")
                if ((a["reported"] ?: true) != (b["reported"] ?: true)) bad.add("$part i=${b["i"]}: reported C ${a["reported"]} ref ${b["reported"]}")
                if (!numEq(a[pname], b[pname], 1e-5)) bad.add("$part i=${b["i"]}: $pname C ${a[pname]} ref ${b[pname]}")
            }
        }
        return bad
    }

    /** The binding's typed records against the same result's JSON (the marshalling, not the scorer). */
    @Suppress("UNCHECKED_CAST")
    private fun typedAgreesWithJson(r: TajweedResult, j: Map<String, Any?>): List<String> {
        val bad = ArrayList<String>()
        val sites = j["sites"] as List<Map<String, Any?>>
        val perClass = sites.flatMap { s -> (s["per_class"] as Map<String, Map<String, Any?>>).map { (k, v) -> k to v } }
        if (r.sites.size != perClass.size) bad.add("typed sites ${r.sites.size} vs JSON per-class entries ${perClass.size}")
        val flagged = sites.count { truthy(it["flagged"]) }
        if (r.sites.count { it.flagged } != flagged) bad.add("typed flagged ${r.sites.count { it.flagged }} vs JSON $flagged")
        val typed = r.sites.map { it.siteClass.key to it.llr }.sortedWith(compareBy({ it.first }, { it.second }))
        val json = perClass.map { it.first to (it.second["fused"] as Double) }.sortedWith(compareBy({ it.first }, { it.second }))
        for ((a, b) in typed.zip(json)) if (a.first != b.first || Math.abs(a.second - b.second) > 1e-6) {
            bad.add("typed ${a.first} ${a.second} vs JSON ${b.first} ${b.second}")
            break
        }
        for ((part, list) in listOf("qalqalah" to r.qalqalah, "gemination" to r.gemination)) {
            val items = (j[part] as? Map<String, Any?>)?.get("items") as? List<*> ?: emptyList<Any?>()
            if (list.size != items.size) bad.add("typed $part ${list.size} vs JSON ${items.size}")
        }
        // the Kotlin settings resolution (TajweedSettings) against the engine's config, on the release calibration
        for ((cls, v) in perClass) {
            val k = r.settings.sites[SiteClass.fromKey(cls)!!] ?: continue
            if (!numEq(k.threshold, v["threshold"], 1e-12) || k.enabled != truthy(v["enabled"])) {
                bad.add("settings $cls: Kotlin ${k.threshold}/${k.enabled} engine ${v["threshold"]}/${v["enabled"]}")
                break
            }
        }
        val summary = j["summary"] as? Map<String, Any?>
        if (summary != null) {
            val s = r.summary
            val want = mapOf("n_sites" to s.sites, "n_flagged" to s.sitesFlagged, "n_madd" to s.madd, "n_madd_flagged" to s.maddFlagged,
                "n_qalqalah" to s.qalqalah, "n_qalqalah_flagged" to s.qalqalahFlagged, "n_gemination" to s.gemination,
                "n_gemination_flagged" to s.geminationFlagged)
            for ((k, v) in want) if (summary.containsKey(k) && (summary[k] as Double).toInt() != v) bad.add("summary $k typed $v JSON ${summary[k]}")
        }
        return bad
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun everyVectorMatchesTheReferenceScorer() {
        Assume.assumeTrue("TJ_SCORER_TABLE is not set (scripts/make_scorer_table.py)", table != null)
        Assume.assumeTrue("the JNI library has no zf_tajweed (scripts/build_scorer_host.sh, -Dtajweedformer.library=)",
            ai.quranlab.tajweedformer.engine.TajweedJni.available)
        val index = Json.parse(File(table, "clips.json").readText()) as Map<String, Any?>
        val vectors = File(index["vectors"] as String)
        val presets = index["presets"] as Map<String, Any?>
        val clips = index["clips"] as List<Map<String, Any?>>
        val cal = Calibration.read(File(table, "calibration.json"))
        val scorers = HashMap<String, TajweedScorer>()
        val tally = sortedMapOf<String, IntArray>()
        val failures = ArrayList<String>()
        var items = 0
        // the comparison must see a difference: each clip's balanced result against its very_sensitive reference
        var crossDiffers = 0
        try {
            for (clip in clips) {
                val ctx = clip["context"] as String
                val ayah = (clip["ayah"] as Double).toInt()
                val npzFile = File(vectors, clip["heads"] as String)
                (clip["heads_sha256"] as String?)?.let { want ->
                    val have = MessageDigest.getInstance("SHA-256").digest(npzFile.readBytes()).joinToString("") { "%02x".format(it) }
                    assertEquals("${clip["name"]} heads", want, have)
                }
                val heads = heads(readNpz(npzFile))
                val scorer = scorers.getOrPut(ctx) { TajweedScorer.openUnverified(table!!, ctx, cal) }
                // the expected row went in as surah 1, ayah k
                val row = Json.parse(File(vectors, (clip["heads"] as String).removeSuffix(".npz") + ".row.json").readText()) as Map<String, Any?>
                val rowTokens = (row["tj3"] as List<*>).size
                assertTrue("${clip["name"]}: no expected tokens", scorer.expectedTokens(1, ayah).isNotEmpty() || rowTokens == 0)
                val got = HashMap<String, Map<String, Any?>>()
                val refs = HashMap<String, Map<String, Any?>>()
                for ((preset, file) in clip["verdicts"] as Map<String, Any?>) {
                    val remote = Json.write(presets.getValue(preset))
                    val result = scorer.score(heads, TajweedConfig(remoteJson = remote, debugBuild = false), 1, ayah)
                    val gotJson = Json.parse(result.json!!) as Map<String, Any?>
                    val ref = Json.parse(File(vectors, file as String).readText()) as Map<String, Any?>
                    val bad = compare(gotJson, ref) + typedAgreesWithJson(result, gotJson)
                    got[preset] = gotJson
                    refs[preset] = ref
                    items += (ref["sites"] as List<*>).size
                    for (k in listOf("context $ctx", "preset $preset")) {
                        val t = tally.getOrPut(k) { IntArray(2) }
                        t[1]++
                        if (bad.isEmpty()) t[0]++
                    }
                    if (bad.isNotEmpty()) failures.add("$ctx/${clip["name"]} $preset: " + bad.take(4).joinToString("; "))
                }
                if (got.containsKey("balanced") && refs.containsKey("very_sensitive") &&
                    compare(got.getValue("balanced"), refs.getValue("very_sensitive")).isNotEmpty()
                ) crossDiffers++
            }
        } finally {
            scorers.values.forEach { it.close() }
        }
        println("scorer vectors: ${clips.size} clips, $items reference sites; balanced vs very_sensitive differs on $crossDiffers clips")
        assertTrue("the comparison never saw a difference between presets", crossDiffers > 0)
        for ((k, t) in tally) println("$k: ${t[0]}/${t[1]}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(clips.size * 2, tally.filterKeys { it.startsWith("preset") }.values.sumOf { it[1] })
    }
}
