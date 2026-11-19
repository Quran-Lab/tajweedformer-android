package ai.quranlab.tajweedformer

import java.io.File
import java.security.MessageDigest

/** The model's tokens (tokens.txt: `<symbol> <id>` per line). V4's tj3 set has 38, blank 0. */
internal class Vocabulary(private val symbols: Array<String>, val blank: Int) {
    val size: Int get() = symbols.size

    /** The symbol of [id], or `<id>` for an id outside the set. */
    fun symbol(id: Int): String = symbols.getOrElse(id) { "<$id>" }

    /** Every symbol by id. */
    val names: List<String> get() = symbols.toList()

    companion object {
        /** Reads tokens.txt; the blank is [blank] when given, else the `<blank>` entry, else the last id. */
        @JvmStatic
        @JvmOverloads
        fun read(file: File, blank: Int? = null): Vocabulary = parse(file.readBytes(), file.path, blank)

        private fun parse(bytes: ByteArray, what: String, blank: Int?): Vocabulary {
            val pairs = String(bytes, Charsets.UTF_8).split('\n').mapNotNull { line ->
                val t = line.trimEnd()
                val cut = t.lastIndexOf(' ')
                if (cut <= 0) null else t.substring(0, cut) to t.substring(cut + 1).toInt()
            }
            if (pairs.isEmpty()) throw RecognizerException.ModelMissing("$what has no tokens")
            val n = pairs.maxOf { it.second } + 1
            val symbols = Array(n) { "<$it>" }
            for ((s, id) in pairs) symbols[id] = s
            return Vocabulary(symbols, blank ?: pairs.firstOrNull { it.first == "<blank>" }?.second ?: (n - 1))
        }

        /**
         * The vocabularies this SDK carries, by the SHA-256 of their tokens.txt (SDK 4.0: an encrypted build's tokens
         * file is a container only the Core reads, and the Core has no call that gives the token names; the build's
         * signed `tokens_sha256` picks the vocabulary here). V4's tj3 set: `v4_tokens.txt`, 66fe6eb8...2aa4.
         */
        private val KNOWN = listOf("v4_tokens.txt")

        /** Tests: vocabularies of test builds (the contract's vectors use made-up tokens files), by tokens_sha256. */
        internal val testVocabularies = java.util.concurrent.ConcurrentHashMap<String, Vocabulary>()

        @JvmStatic
        fun known(tokensSha256: String): Vocabulary? {
            testVocabularies[tokensSha256.lowercase()]?.let { return it }
            for (name in KNOWN) {
                val b = Vocabulary::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/$name")?.use { it.readBytes() } ?: continue
                val sha = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (sha == tokensSha256.lowercase()) return parse(b, name, 0)
            }
            return null
        }
    }
}
