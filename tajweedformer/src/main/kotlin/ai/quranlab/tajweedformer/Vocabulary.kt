package ai.quranlab.tajweedformer

import java.io.File

/** The model's tokens (tokens.txt: `<symbol> <id>` per line). V4's tj3 set has 38, blank 0. */
class Vocabulary(private val symbols: Array<String>, val blank: Int) {
    val size: Int get() = symbols.size

    /** The symbol of [id], or `<id>` for an id outside the set. */
    fun symbol(id: Int): String = symbols.getOrElse(id) { "<$id>" }

    /** Every symbol by id. */
    val names: List<String> get() = symbols.toList()

    companion object {
        /** Reads tokens.txt; the blank is [blank] when given, else the `<blank>` entry, else the last id. */
        @JvmStatic
        @JvmOverloads
        fun read(file: File, blank: Int? = null): Vocabulary {
            val pairs = file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val t = line.trimEnd()
                val cut = t.lastIndexOf(' ')
                if (cut <= 0) null else t.substring(0, cut) to t.substring(cut + 1).toInt()
            }
            if (pairs.isEmpty()) throw RecognizerException.ModelMissing("${file.path} has no tokens")
            val n = pairs.maxOf { it.second } + 1
            val symbols = Array(n) { "<$it>" }
            for ((s, id) in pairs) symbols[id] = s
            return Vocabulary(symbols, blank ?: pairs.firstOrNull { it.first == "<blank>" }?.second ?: (n - 1))
        }
    }
}
