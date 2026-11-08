/*
 * Romanizer for UpZe KMP
 */
package com.ravi.musicspot.lyrics

expect fun platformTransliterate(text: String, isHan: Boolean): String?
expect fun isPlatformTransliterateAvailable(): Boolean

object Romanizer {
    val available: Boolean get() = isPlatformTransliterateAvailable()

    fun lines(texts: List<String>): List<String?> {
        if (!available) return texts.map { null }
        val japanese = texts.any { t -> t.any(::isKana) }
        return texts.map { romanize(it, japanese) }
    }

    fun romanize(text: String, japanese: Boolean = text.any(::isKana)): String? {
        if (!available || text.none(::needsIt)) return null
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val kind = kindOf(text[i])
            var j = i
            while (j < text.length && kindOf(text[j]) == kind) j++
            val run = text.substring(i, j)
            out.append(
                when (kind) {
                    HANGUL -> Hangul.romanize(run)
                    HAN -> if (japanese) run else " " + (platformTransliterate(run, true) ?: run) + " "
                    else -> platformTransliterate(run, false) ?: run
                }
            )
            i = j
        }
        return plain(out.toString()).replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotEmpty() && it != text.trim() }
    }

    private const val HANGUL = 1
    private const val HAN = 2
    private const val OTHER = 3

    private fun needsIt(c: Char) = c.code >= 0x0370 && (kindOf(c) != OTHER || c.code !in 0x1E00..0x1EFF)
    private fun isKana(c: Char) = c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'
    private fun kindOf(c: Char) = when {
        c in '\uAC00'..'\uD7A3' -> HANGUL
        c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> HAN
        else -> OTHER
    }
    private fun plain(s: String): String = s.replace('ʰ', 'h').replace(Regex("[ʾʿ~]"), "")
}

internal object Hangul {
    private val INITIAL = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIAL = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FINAL = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "p", "t", "t", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    private val LINKED = arrayOf("", "g", "kk", "ks", "n", "nj", "n", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "r", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "")

    private const val SILENT = 11
    private const val RIEUL = 5
    private const val FINAL_RIEUL = 8

    fun romanize(run: String): String {
        val codes = run.map { it.code - 0xAC00 }
        val out = StringBuilder()
        codes.forEachIndexed { i, c ->
            val initial = c / (21 * 28)
            val medial = c % (21 * 28) / 28
            val final = c % 28
            val prevFinal = if (i > 0) codes[i - 1] % 28 else 0
            out.append(
                when {
                    initial == SILENT -> ""
                    initial == RIEUL && prevFinal == FINAL_RIEUL -> "l"
                    else -> INITIAL[initial]
                },
            )
            out.append(MEDIAL[medial])
            val nextInitial = codes.getOrNull(i + 1)?.let { it / (21 * 28) }
            out.append(if (final != 0 && nextInitial == SILENT) LINKED[final] else FINAL[final])
        }
        return out.toString()
    }
}
