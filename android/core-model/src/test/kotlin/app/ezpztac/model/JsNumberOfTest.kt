package app.ezpztac.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `JsNumber.of` is JavaScript's `Number()` (each value against what Node answers), and the diagram normaliser reads a target with it,
 * and trims a grid as JavaScript trims, as the web's normalizeLzDiagram does (contracts/fixtures/packs/shared.json's `lzShape` holds the
 * whole of it through core-missionpacks).
 */
class JsNumberOfTest {
    private fun of(json: String) = JsNumber.of(Json.parseToJsonElement(json))

    @Test
    fun `text, lists and the rest are read as JavaScript reads them`() {
        assertEquals(34.0, of("\"0x22\""))
        assertEquals(90.0, of("\"0b1011010\""))
        assertEquals(90.0, of("\"0o132\""))
        assertEquals(0.0, of("\"\""))
        assertEquals(0.0, of("\"  \""))
        assertEquals(42.0, of("\"\\u00a042\\ufeff\""))
        assertEquals(1.0, of("true"))
        assertEquals(0.0, of("null"))
        assertEquals(0.0, of("[]"))
        assertEquals(34.783817, of("[34.783817]"))
        assertEquals(-84.08219, of("[\"-84.08219\"]"))
        assertEquals(Double.POSITIVE_INFINITY, of("\"Infinity\""))
        listOf("\"-0x22\"", "\"1_000\"", "\"\\u001c1\"", "\"\\u200b1\"", "[1, 2]", "{}", "\"north\"").forEach { assertTrue(of(it).isNaN(), it) }
        assertTrue(JsNumber.of(null).isNaN())
    }

    @Test
    fun `a target is read with JavaScript's Number`() {
        val hex = DiagramNormalizer.normalizeTarget(Json.parseToJsonElement("""{"lat": "0x22", "lon": "-84.08219"}"""))
        assertEquals(34.0, hex?.lat)
        val lists = DiagramNormalizer.normalizeTarget(Json.parseToJsonElement("""[[34.783817], ["-84.08219"]]"""))
        assertEquals(DiagramTarget(34.783817, -84.08219, ""), lists)
        assertEquals(DiagramTarget(1.0, 0.0, ""), DiagramNormalizer.normalizeTarget(Json.parseToJsonElement("""{"lat": true, "lon": []}""")))
        assertNull(DiagramNormalizer.normalizeTarget(Json.parseToJsonElement("""{"lat": "north", "lon": 1}""")))
    }

    @Test
    fun `a grid is blank or not as JavaScript's trim says`() {
        val target = Json.parseToJsonElement("""{"lat": 34.5, "lon": -84.1, "mgrs": "own"}""")
        // A byte order mark alone is blank to JavaScript, so the target's own grid is taken; U+001C alone is not, so it is kept.
        assertEquals("own", DiagramNormalizer.normalizeTarget(target, JsonPrimitive("\uFEFF"))?.mgrs)
        assertEquals("\u001C", DiagramNormalizer.normalizeTarget(target, JsonPrimitive("\u001C"))?.mgrs)
        assertEquals(" 16S ", DiagramNormalizer.normalizeTarget(target, JsonPrimitive(" 16S "))?.mgrs)
    }

    @Test
    fun `JavaScript's trim takes every one of its spaces, and nothing else`() {
        val spaces = "\t\n\u000B\u000C\r          　﻿"
        assertEquals("16S", JsNumber.trimJs(spaces + "16S" + spaces))
        // Either side of the run U+2000 to U+200A, the separators Kotlin's trim takes, and NEL: none is a space to JavaScript.
        listOf("῿", "​", "\u001C", "\u001F", "\u0085").forEach { assertEquals(it, JsNumber.trimJs(it), "U+" + it[0].code.toString(16)) }
    }
}
