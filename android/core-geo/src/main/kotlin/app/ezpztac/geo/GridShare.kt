package app.ezpztac.geo

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * A grid sent to someone (`docs/GRID_SHARING.md` §1): the same few lines whatever carries them (the system's share sheet, a QR code, a
 * copy), and a link that opens EZ/PZ at the grid.
 *
 *     LZ HAWK
 *     16S GC 28864 55349
 *     34.54568° N, 84.12346° W
 *     https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK
 *
 * The web's `utils/gridShare.js` function by function, held to `contracts/fixtures/coords/share.json`: JavaScript's whitespace, its
 * `encodeURIComponent`, and `URLSearchParams`' reading of a fragment are ported here, since Java's are not the same.
 */
public object GridShare {
    public const val LINK_BASE: String = "https://ezpztac.app/"
    public const val LABEL_MAX: Int = 60

    // Control characters and line breaks: a label is one line of the person's own words.
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029]")
    // What draws nothing but can turn or hide what is around it (bidirectional marks and overrides, the zero-width space, the word
    // joiner), so a label cannot draw the grid beside it right to left. The joiners an emoji or a script needs (U+200C, U+200D) stay.
    private val INVISIBLE = Regex("[\\u061c\\u200b\\u200e\\u200f\\u202a-\\u202e\\u2060-\\u2064\\u2066-\\u2069]")
    // JavaScript's \s.
    private val JS_SPACES = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
    private val JS_SPACE = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]")

    /**
     * A label as it is shared: one line, spaces collapsed, nothing that turns or hides text, at most 60 code points (a surrogate pair is one,
     * never split; an emoji joined from several may lose its tail at the cut), or "" for none.
     */
    public fun cleanLabel(label: String?): String {
        val text = CoordinateParser.jsTrim(withoutLoneSurrogates((label ?: "").replace(CONTROL, " ").replace(INVISIBLE, "")).replace(JS_SPACES, " "))
        return CoordinateParser.jsTrim(firstCodePoints(text, LABEL_MAX))
    }

    /** The link that opens EZ/PZ at [mgrs] (spaces taken out), with its label when there is one. */
    public fun gridLink(mgrs: String, label: String? = null): String {
        val grid = mgrs.replace(JS_SPACE, "").uppercase()
        val name = cleanLabel(label)
        return "$LINK_BASE#grid=${encodeUriComponent(grid)}" + if (name.isNotEmpty()) "&name=${encodeUriComponent(name)}" else ""
    }

    /** The lines a shared grid is, joined by line breaks: the label (when there is one), the grid, the lat/long, the link. */
    public fun shareText(mgrs: String, lat: Double, lon: Double, label: String? = null): String {
        val name = cleanLabel(label)
        return listOf(name, mgrs, PlaceSearch.formatLatLong(lat, lon), gridLink(mgrs, name)).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** What a link asks for: its grid, read as the search reads it, and its label. */
    public data class Link(val choice: PlaceChoice, val name: String)

    /**
     * What a link's fragment (`#grid=…&name=…`) asks for, or null when it has no grid or its grid does not read as one place. A link names a
     * grid, never a lat/long to guess a side for.
     */
    public fun readGridLink(hash: String?): Link? {
        val fragment = (hash ?: "").removePrefix("#")
        if (fragment.isEmpty()) return null
        val params = formParams(fragment)
        val grid = params.firstOrNull { it.first == "grid" }?.second
        if (grid.isNullOrEmpty() || grid.length > 40) return null
        val choice = PlaceSearch.read(grid).choices.singleOrNull()?.takeIf { it.isGrid } ?: return null
        return Link(choice, cleanLabel(params.firstOrNull { it.first == "name" }?.second))
    }

    // -- JavaScript, as the web runs it --------------------------------------------------------------------

    private fun withoutLoneSurrogates(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { append(c).append(text[i + 1]); i += 2 }
                c.isSurrogate() -> i++
                else -> { append(c); i++ }
            }
        }
    }

    // Array.from(text).slice(0, n).join(""): characters, not UTF-16 units.
    private fun firstCodePoints(text: String, n: Int): String {
        val count = text.codePointCount(0, text.length)
        return if (count <= n) text else text.substring(0, text.offsetByCodePoints(0, n))
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** JavaScript's `encodeURIComponent`, for text with no lone surrogate: its unreserved set kept, everything else as UTF-8 %XX. */
    internal fun encodeUriComponent(text: String): String = buildString {
        for (byte in text.toByteArray(StandardCharsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            if (b < 0x80 && UNRESERVED.indexOf(b.toChar()) >= 0) append(b.toChar())
            else append('%').append("0123456789ABCDEF"[b shr 4]).append("0123456789ABCDEF"[b and 0xF])
        }
    }

    /** `new URLSearchParams(text)`: pieces split on "&", an empty one skipped, each name and value form-decoded (WHATWG). */
    private fun formParams(text: String): List<Pair<String, String>> = text.split('&').filter { it.isNotEmpty() }.map { piece ->
        val i = piece.indexOf('=')
        if (i < 0) formDecode(piece) to "" else formDecode(piece.substring(0, i)) to formDecode(piece.substring(i + 1))
    }

    // "+" is a space; "%" and two hex digits a byte, any other "%" itself; the bytes then read as UTF-8 as WHATWG reads it (utf8Decode).
    private fun formDecode(text: String): String {
        val bytes = text.replace('+', ' ').toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte() && i + 2 < bytes.size && hex(bytes[i + 1]) >= 0 && hex(bytes[i + 2]) >= 0) {
                out.write(hex(bytes[i + 1]) * 16 + hex(bytes[i + 2]))
                i += 3
            } else {
                out.write(b.toInt())
                i++
            }
        }
        return utf8Decode(out.toByteArray())
    }

    /**
     * The Encoding Standard's UTF-8 decoder: one U+FFFD for each maximal part of a sequence that cannot be completed. Java's decoder is not
     * held to it (an encoded surrogate, `ED A0 80`, is one U+FFFD there and three here), nor is Android's libcore's.
     */
    internal fun utf8Decode(bytes: ByteArray): String = buildString {
        var needed = 0
        var seen = 0
        var codePoint = 0
        var lower = 0x80
        var upper = 0xBF
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (needed == 0) {
                when (b) {
                    in 0x00..0x7F -> append(b.toChar())
                    in 0xC2..0xDF -> { needed = 1; codePoint = b and 0x1F }
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) lower = 0xA0
                        if (b == 0xED) upper = 0x9F
                        needed = 2; codePoint = b and 0xF
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) lower = 0x90
                        if (b == 0xF4) upper = 0x8F
                        needed = 3; codePoint = b and 0x7
                    }
                    else -> append('\uFFFD')
                }
                i++
                continue
            }
            if (b !in lower..upper) {
                // This byte starts again: the sequence it broke is one U+FFFD.
                needed = 0; seen = 0; codePoint = 0; lower = 0x80; upper = 0xBF
                append('\uFFFD')
                continue
            }
            lower = 0x80; upper = 0xBF
            codePoint = (codePoint shl 6) or (b and 0x3F)
            seen++
            i++
            if (seen == needed) {
                appendCodePoint(codePoint)
                needed = 0; seen = 0; codePoint = 0
            }
        }
        if (needed != 0) append('\uFFFD')
    }

    private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
