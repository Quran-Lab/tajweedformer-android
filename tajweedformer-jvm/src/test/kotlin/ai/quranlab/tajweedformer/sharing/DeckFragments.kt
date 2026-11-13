package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import java.security.MessageDigest

/**
 * The sentence that deck 2026-11-12.1 added to `sheet.who` and `what.detail` (SPEC 11.8, contract 1.15.0), known here
 * only by its length and SHA-256 in each language (`sharing_vectors/deck_2026-11-12.1_added.json`, made by the release
 * tooling's `tools/deck_fragment_hashes.py` from the contract's archived deck): this repository does not hold its
 * text. A text holds it when some window of that many characters has that hash.
 */
internal object DeckFragments {
    class Fragment(val length: Int, val sha256: String)

    /** Per language: the sentence that deck 2026-11-12.1 added. */
    val addedBy2026_11_12_1: Map<String, Fragment> by lazy {
        val b = DeckFragments::class.java.getResourceAsStream("/sharing_vectors/deck_2026-11-12.1_added.json")!!.use { it.readBytes() }
        parse(String(b, Charsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): Map<String, Fragment> {
        val o = Json.parse(json) as Map<String, Any?>
        check(o["format"] == "quranlab.deck-fragment/1" && o["added_by"] == "2026-11-12.1")
        return (o["languages"] as Map<String, Map<String, Any?>>).mapValues { (_, v) -> Fragment((v["length"] as Double).toInt(), v["sha256"] as String) }
    }

    /** Whether [text] holds the fragment [f]: a window of its length whose UTF-8 bytes have its SHA-256. */
    fun holds(text: String, f: Fragment): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0..text.length - f.length) {
            val h = md.digest(text.substring(i, i + f.length).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if (h == f.sha256) return true
        }
        return false
    }
}
