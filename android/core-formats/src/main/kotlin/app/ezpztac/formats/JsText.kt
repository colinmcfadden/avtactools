package app.ezpztac.formats

/*
 * What JavaScript does with text and numbers that the web's `.LPS` and `.ths` readers lean
 * on. A file's text is trimmed the way `String.prototype.trim` does it, which removes more
 * than Kotlin's `trim()` (the no-break space and the byte order mark among them), and a
 * number is read the way `Number(...)` reads it.
 */
internal object JsText {
    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    fun trim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isJsSpace(text[start])) start++
        while (end > start && isJsSpace(text[end - 1])) end--
        return text.substring(start, end)
    }

    private val DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /**
     * `Number(value)` for a value read from a SQLite row: a number is itself, text is parsed
     * whole (so `"12"` is 12, `"12 ft"` is NaN and blank text is 0). NULL and blobs are NaN:
     * the caller decides what to do about a value that is not there.
     */
    fun number(value: Any?): Double = when (value) {
        is Long -> value.toDouble()
        is Double -> value
        is String -> {
            val text = trim(value)
            when {
                text.isEmpty() -> 0.0
                DECIMAL.matches(text) -> text.toDouble()
                else -> Double.NaN
            }
        }
        else -> Double.NaN
    }

    /**
     * A value used where the web writes `(value || fallback).trim()`. Text is itself; a number is shown as
     * JavaScript shows it, except that zero is falsy there and so counts as missing; NULL and blobs are missing.
     * (On the web a non-zero number there would be an error, since a number has no `trim`; a person's file
     * should open instead.)
     */
    fun text(value: Any?): String? = when (value) {
        is String -> value
        is Long -> if (value == 0L) null else value.toString()
        is Double -> when {
            value == 0.0 || value.isNaN() -> null
            value == Math.rint(value) && Math.abs(value) < 1e21 -> value.toLong().toString()
            else -> value.toString()
        }
        else -> null
    }
}
