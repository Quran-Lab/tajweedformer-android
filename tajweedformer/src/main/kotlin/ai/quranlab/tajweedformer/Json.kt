package ai.quranlab.tajweedformer

/**
 * A small JSON reader and writer, so the SDK reads calibration.json, manifest.json and the portal's
 * `tajweed` block the same way on Android and on the desktop JVM, with no dependency.
 *
 * Values: `Map<String, Any?>` (insertion ordered; a repeated key keeps its last value, as Python's
 * json does), `List<Any?>`, `String`, `Double` for every number, `Boolean`, and `null`. As Python's
 * json module, it also reads the literals `NaN`, `Infinity` and `-Infinity`, so a portal block that
 * carries one is read and its field dropped by validation instead of losing the whole document.
 */
object Json {
    /** Parses [text]; throws [JsonException] when it is not one JSON value. */
    @JvmStatic
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw p.fail("trailing characters")
        return v
    }

    /** Compact JSON for [value] (the types [parse] gives; Int, Long and Float are written as numbers). */
    @JvmStatic
    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Double -> sb.append(number(v))
            is Float -> sb.append(number(v.toDouble()))
            is Number -> sb.append(v.toLong())
            is String -> string(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun number(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "-Infinity"
        d == Math.rint(d) && Math.abs(d) < 1e15 -> d.toLong().toString() + ".0"
        else -> d.toString()
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(why: String) = JsonException("$why at offset $i")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 256) throw fail("nested too deeply")
            if (i >= s.length) throw fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                'N' -> lit("NaN", Double.NaN)
                'I' -> lit("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' && s.startsWith("-Infinity", i)) lit("-Infinity", Double.NEGATIVE_INFINITY) else num()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw fail("unexpected character")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw fail("expected a key")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw fail("expected ':'")
                i++
                ws()
                m.remove(k)
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> { i--; throw fail("expected ',' or '}'") }
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return a
            }
            while (true) {
                ws()
                a.add(value(depth + 1))
                ws()
                if (i >= s.length) throw fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return a
                    else -> { i--; throw fail("expected ',' or ']'") }
                }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw fail("unterminated string")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw fail("bad \\u escape")
                                val h = s.substring(i, i + 4).toIntOrNull(16) ?: throw fail("bad \\u escape")
                                sb.append(h.toChar())
                                i += 4
                            }
                            else -> throw fail("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw fail("control character in a string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) throw fail("unexpected character")
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isDigit()) throw fail("bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) throw fail("bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDouble()
        }
    }
}

/** Text that is not JSON. */
class JsonException(message: String) : IllegalArgumentException(message)

/** Typed reads of parsed JSON, for the SDK's own files. Each throws [JsonException] naming the key. */
internal object J {
    fun obj(v: Any?, what: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw JsonException("$what is not an object")
    }

    fun objOrNull(m: Map<String, Any?>, k: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return m[k] as? Map<String, Any?>
    }

    fun str(m: Map<String, Any?>, k: String, what: String = k): String =
        m[k] as? String ?: throw JsonException("$what is missing or not a string")

    fun num(m: Map<String, Any?>, k: String, what: String = k): Double =
        m[k] as? Double ?: throw JsonException("$what is missing or not a number")

    fun int(m: Map<String, Any?>, k: String, what: String = k): Int {
        val d = num(m, k, what)
        if (d != Math.rint(d) || Math.abs(d) > Int.MAX_VALUE) throw JsonException("$what is not an integer")
        return d.toInt()
    }

    fun long(m: Map<String, Any?>, k: String, what: String = k): Long {
        val d = num(m, k, what)
        if (d != Math.rint(d) || d < 0 || d > 9.007199254740992E15) throw JsonException("$what is not a size")
        return d.toLong()
    }

    fun list(m: Map<String, Any?>, k: String, what: String = k): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return m[k] as? List<Any?> ?: throw JsonException("$what is missing or not an array")
    }
}
