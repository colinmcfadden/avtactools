package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Every case of `contracts/fixtures/documents/search.json`, which the web's `documentSearch.js` wrote, held strictly: each hit's every
 * field, the count and whether more were found than listed, written as the web's object is (so a field that differs, or one too many,
 * fails), and every fold. Then what no case of the fixture reaches, each the web's answer in Node for the same input.
 */
class DocumentSearchFixtureTest {
    private val fixture = Fixtures.load("documents/search.json")

    private fun asJson(result: DocumentHits): JsonObject = JsonObject(
        mapOf(
            "hits" to JsonArray(
                result.hits.map { hit ->
                    JsonObject(
                        mapOf(
                            "page" to JsonPrimitive(hit.page),
                            "start" to JsonPrimitive(hit.start),
                            "end" to JsonPrimitive(hit.end),
                            "before" to JsonPrimitive(hit.before),
                            "match" to JsonPrimitive(hit.match),
                            "after" to JsonPrimitive(hit.after),
                            "cutBefore" to JsonPrimitive(hit.cutBefore),
                            "cutAfter" to JsonPrimitive(hit.cutAfter),
                        ),
                    )
                },
            ),
            "count" to JsonPrimitive(result.count),
            "more" to JsonPrimitive(result.more),
        ),
    )

    @TestFactory
    fun `every case finds what the web finds`(): List<DynamicTest> {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 24)
        return cases.map { case ->
            DynamicTest.dynamicTest(case.getValue("name").jsonPrimitive.content) {
                val options = case["options"] as? JsonObject
                val result = DocumentSearch.findInDocument(
                    DocumentSearch.pagesOf(case.getValue("pages")),
                    case.getValue("query").jsonPrimitive.content,
                    context = options?.get("context")?.jsonPrimitive?.int ?: DocumentSearch.CONTEXT,
                    limit = options?.get("limit")?.jsonPrimitive?.int ?: DocumentSearch.LIMIT,
                )
                // Parsed again, so a number is compared as the fixture's literal is, never as a Kotlin type.
                assertEquals(case.getValue("expected"), Json.parseToJsonElement(asJson(result).toString()))
            }
        }
    }

    @TestFactory
    fun `every text folds as the web folds it`(): List<DynamicTest> = fixture.getValue("folds").jsonArray.map { it.jsonObject }.map { fold ->
        val text = fold.getValue("text").jsonPrimitive.content
        DynamicTest.dynamicTest(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))) { assertEquals(fold.getValue("folded").jsonPrimitive.content, DocumentSearch.foldForSearch(text)) }
    }

    @Test
    fun `white space is Unicode's, not Java's`() {
        // A no-break space, a narrow one, a line separator and a next line are white space; U+001C to U+001F, which Java's
        // isWhitespace calls white space, and a byte order mark, are not.
        assertEquals("a b c d e", DocumentSearch.foldForSearch("a b c d\u0085e"))
        assertEquals("a\u001Cb\u001Fc﻿d", DocumentSearch.foldForSearch("a\u001Cb\u001Fc﻿d"))
        assertEquals(1, DocumentSearch.findInDocument(listOf("LZ HAWK"), "lz hawk").count)
        assertEquals(0, DocumentSearch.findInDocument(listOf("LZ\u001FHAWK"), "lz hawk").count)
    }

    @Test
    fun `the query is trimmed as JavaScript trims, a byte order mark included`() {
        val found = DocumentSearch.findInDocument(listOf("LZ HAWK"), "﻿ hawk ﻿")
        assertEquals(1, found.count)
        assertEquals("HAWK", found.hits.single().match)
        // A next line is a space once folded, so it is trimmed too, though JavaScript's trim alone would not take it.
        assertEquals(1, DocumentSearch.findInDocument(listOf("LZ HAWK"), "\u0085hawk").count)
        assertEquals(DocumentHits.NONE, DocumentSearch.findInDocument(listOf("LZ HAWK"), "﻿"))
    }

    @Test
    fun `a mark that only spaces is a mark, and an enclosing one too`() {
        // U+0903 (a Devanagari visarga) is a spacing mark, U+20DD an enclosing circle: both are \p{M}, so both go.
        assertEquals("ab", DocumentSearch.foldForSearch("aःb⃝"))
    }

    @Test
    fun `a page that is not text, or pages that are not a list, find nothing`() {
        assertEquals(DocumentHits.NONE, DocumentSearch.findInDocument(null, "hawk"))
        assertEquals(null, DocumentSearch.pagesOf(Json.parseToJsonElement("""{"0": "LZ HAWK"}""")))
        assertEquals(null, DocumentSearch.pagesOf(null))
        assertEquals(listOf(null, null, "7"), DocumentSearch.pagesOf(Json.parseToJsonElement("""[null, 7, "7"]""")))
    }

    @Test
    fun `a limit of none lists nothing and says there is more`() {
        val found = DocumentSearch.findInDocument(listOf("aa aa"), "aa", limit = 0)
        assertEquals(DocumentHits(emptyList(), 2, true), found)
    }

    @Test
    fun `a hit next to an emoji takes it whole into its context`() {
        val hit = DocumentSearch.findInDocument(listOf("🚁HAWK🚁"), "hawk", context = 1).hits.single()
        assertEquals(DocumentHit(1, 1, 5, "🚁", "HAWK", "🚁", cutBefore = false, cutAfter = false), hit)
    }

    @Test
    fun `a negative context is refused`() {
        assertThrows<IllegalArgumentException> { DocumentSearch.findInDocument(listOf("LZ HAWK"), "hawk", context = -1) }
    }
}
