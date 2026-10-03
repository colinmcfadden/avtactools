package app.ezpztac.model

import java.math.BigDecimal

/** What a form does with a number a person typed, and with one it is putting back in a field. Shared by the forms so they agree on what a number is. */
internal object TypedNumber {
    private val NUMBER = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)""")

    /** A typed number: digits with an optional point and sign, and nothing else ("12 kt", "1e3" and "1,5" are not numbers here). */
    fun parse(text: String): Double? = text.trim().takeIf { NUMBER.matches(it) }?.toDouble()

    /** A number as it is typed back into a field: no trailing ".0", no exponent, no locale. */
    fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** At most [max] characters, counted as the file formats count them (code points), so an emoji at the end is never split. */
    fun cut(text: String, max: Int): String =
        if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
}
