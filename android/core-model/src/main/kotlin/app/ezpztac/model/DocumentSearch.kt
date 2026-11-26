package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * One place a query was found: on [page] (from 1), from code point [start] of the page's own text to just before [end], with the
 * text itself ([match]) and up to the context's code points either side ([before], [after]). [cutBefore] and [cutAfter] say the page
 * goes on past them, so a list can show an ellipsis.
 */
public data class DocumentHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val before: String,
    val match: String,
    val after: String,
    val cutBefore: Boolean,
    val cutAfter: Boolean,
)

/** What a find came to: the hits listed, in page order; [count], every hit, listed or not; and [more], that some were not listed. */
public data class DocumentHits(val hits: List<DocumentHit>, val count: Int, val more: Boolean) {
    public companion object {
        public val NONE: DocumentHits = DocumentHits(emptyList(), 0, false)
    }
}

/**
 * Find in a document (docs/DOCUMENTS.md §8.2): where a query occurs in a document's page text, the text the server read once at upload
 * (`{version, pages}`), so every client finds the same hits the same way, offline too. The web's `documentSearch.js` function for
 * function, held to every case of `contracts/fixtures/documents/search.json`.
 *
 * The rule. The query and the text are each folded one code point at a time: Unicode NFKD, its combining marks (`\p{M}`: Mn, Mc and
 * Me) taken out, then each code point lower-cased alone (so no context, such as a final sigma, changes a letter), and a run of white
 * space (`\p{White_Space}`) one space. A hit is where the folded query occurs in the folded text, scanning from the start, the next
 * search starting after the hit (hits never overlap). Each hit is given in the page's own text, by code point, never by UTF-16 unit,
 * as JavaScript's `[...text]` counts: an emoji is one. A query that folds to nothing, or to a space alone, finds nothing.
 *
 * Where the JVM is not JavaScript, and why this agrees anyway:
 * - **White space is Unicode's, not Java's `\s` or `isWhitespace`**: `\p{White_Space}` is exactly the space, line and paragraph
 *   separators (Zs, Zl, Zp) and U+0009 to U+000D and U+0085, which is what [isSpace] asks (no-break spaces in, U+001C to U+001F and the
 *   byte order mark out).
 * - **Lower case is JavaScript's `toLowerCase` of one code point**: Kotlin's [lowercase] of a one-code-point string, with the root
 *   locale's full mapping, as JavaScript's is. Alone, a capital sigma has nothing before it and is never final; the one full mapping
 *   that differs from the simple one (U+0130 to `i` and a dot) cannot meet it, since NFKD has already made U+0130 an `I` and a dot.
 * - **The query is trimmed as JavaScript trims** ([JsNumber.trimJs]): after folding every white space is a space, but a byte order mark
 *   at either end is still there, and JavaScript's `trim` takes it.
 */
public object DocumentSearch {
    /** How many code points either side of a hit are given, unless asked otherwise. */
    public const val CONTEXT: Int = 40

    /** How many hits are listed, unless asked otherwise; the rest are counted. */
    public const val LIMIT: Int = 1000

    private const val SPACE = ' '.code

    /** [text] folded as the rule says (a null is the empty text, as the web's `String(text ?? "")`). */
    public fun foldForSearch(text: String?): String {
        val folded = foldWithOrigins(text.orEmpty())
        return String(folded.chars, 0, folded.size)
    }

    /**
     * Every hit of [query] in [pages] (each page's text; a page that is not text, null, is passed over, and no pages at all finds
     * nothing), page by page. [count][DocumentHits.count] is every hit; at most [limit] are listed, and
     * [more][DocumentHits.more] says some were not. [context] must not be negative: the web's slices would then mean something nobody
     * asked for.
     */
    public fun findInDocument(pages: List<String?>?, query: String?, context: Int = CONTEXT, limit: Int = LIMIT): DocumentHits {
        require(context >= 0) { "A context is a number of code points: $context" }
        val wanted = codePoints(JsNumber.trimJs(foldForSearch(query)))
        if (wanted.isEmpty() || pages == null) return DocumentHits.NONE
        val hits = ArrayList<DocumentHit>()
        var count = 0
        var more = false
        pages.forEachIndexed { pageIndex, pageText ->
            val text = pageText.orEmpty()
            if (text.isEmpty()) return@forEachIndexed
            val folded = foldWithOrigins(text)
            // The page's own code points are needed only for a hit that is listed.
            var points: IntArray? = null
            var at = 0
            while (at + wanted.size <= folded.size) {
                if (!folded.sameAt(at, wanted)) {
                    at += 1
                    continue
                }
                count += 1
                if (hits.size < limit) {
                    val own = points ?: codePoints(text).also { points = it }
                    val start = folded.from[at]
                    val end = folded.from[at + wanted.size - 1] + 1
                    val beforeStart = maxOf(0, start - context)
                    val afterEnd = minOf(own.size, end + context)
                    hits += DocumentHit(
                        page = pageIndex + 1,
                        start = start,
                        end = end,
                        before = String(own, beforeStart, start - beforeStart),
                        match = String(own, start, end - start),
                        after = String(own, end, afterEnd - end),
                        cutBefore = beforeStart > 0,
                        cutAfter = afterEnd < own.size,
                    )
                } else {
                    more = true
                }
                at += wanted.size
            }
        }
        return DocumentHits(hits, count, more)
    }

    /**
     * The pages of a document's text file (its `pages`) as [findInDocument] takes them: a JSON string is a page's text and anything
     * else none (the web's `typeof pageText === "string"`), and what is not a list is no pages at all (`Array.isArray`).
     */
    public fun pagesOf(pages: JsonElement?): List<String?>? =
        (pages as? JsonArray)?.map { page -> (page as? JsonPrimitive)?.takeIf { it.isString }?.content }

    // -- The fold ----------------------------------------------------------------------------------------------------------

    /** The folded code points of a text ([chars], [size] of them), each with the index, in the text's code points, it came from ([from]). */
    private class Folded(capacity: Int) {
        var chars = IntArray(capacity)
        var from = IntArray(capacity)
        var size = 0

        fun last(): Int = if (size == 0) -1 else chars[size - 1]

        fun add(char: Int, origin: Int) {
            if (size == chars.size) {
                val grown = maxOf(16, size * 2)
                chars = chars.copyOf(grown)
                from = from.copyOf(grown)
            }
            chars[size] = char
            from[size] = origin
            size += 1
        }

        fun sameAt(at: Int, query: IntArray): Boolean {
            for (i in query.indices) if (chars[at + i] != query[i]) return false
            return true
        }
    }

    /** [text] folded, with where each folded code point came from. A run of white space is one space, taken from the run's first. */
    private fun foldWithOrigins(text: String): Folded {
        val folded = Folded(text.length)
        var index = 0
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            offset += Character.charCount(cp)
            foldCodePoint(cp) { char -> if (char != SPACE || folded.last() != SPACE) folded.add(char, index) }
            index += 1
        }
        return folded
    }

    /** One code point folded: none or more code points (a mark is none, a ligature several), white space as a space. */
    private inline fun foldCodePoint(cp: Int, emit: (Int) -> Unit) {
        if (isSpace(cp)) {
            emit(SPACE)
            return
        }
        // Only for speed: NFKD leaves ASCII as it is, it has no marks, and lower-casing changes only its capitals.
        if (cp < 0x80) {
            emit(if (cp in 'A'.code..'Z'.code) cp + ('a' - 'A') else cp)
            return
        }
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
        var at = 0
        while (at < decomposed.length) {
            val part = decomposed.codePointAt(at)
            at += Character.charCount(part)
            if (isMark(part)) continue
            val lower = String(Character.toChars(part)).lowercase()
            var i = 0
            while (i < lower.length) {
                val char = lower.codePointAt(i)
                i += Character.charCount(char)
                if (!isMark(char)) emit(if (isSpace(char)) SPACE else char)
            }
        }
    }

    /** `\p{M}`: a non-spacing, spacing or enclosing mark. */
    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** `\p{White_Space}`, which is not Java's `\s` nor [Character.isWhitespace]. */
    private fun isSpace(cp: Int): Boolean = cp in 0x09..0x0D || cp == 0x85 || when (Character.getType(cp)) {
        Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }

    /** A text's code points, as JavaScript's `[...text]` iterates them (a lone surrogate is one). */
    private fun codePoints(text: String): IntArray = text.codePoints().toArray()
}
