package app.ezpztac.geo

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Held to contracts/fixtures/coords/share.json, which records what the web's utils/gridShare.js makes of a shared grid. */
class GridShareFixtureTest {
    private val fixture = Fixtures.load("coords/share.json")

    private fun cases(key: String) = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String): String? = this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun `writes a shared grid's lines as the web does`() {
        val all = cases("shareText")
        assertTrue(all.size >= 8)
        all.forEach { c ->
            val place = c["place"]!!.jsonObject
            val text = GridShare.shareText(place.str("mgrs")!!, place["lat"]!!.jsonPrimitive.double, place["lon"]!!.jsonPrimitive.double, place.str("label"))
            assertEquals(c.str("expected"), text, place.toString())
        }
    }

    @Test
    fun `makes the link and cleans a label as the web does`() {
        cases("gridLink").forEach { c -> assertEquals(c.str("expected"), GridShare.gridLink(c.str("mgrs")!!, c.str("label")), c.toString()) }
        cases("cleanLabel").forEach { c -> assertEquals(c.str("expected"), GridShare.cleanLabel(c.str("label")), c.toString()) }
    }

    @Test
    fun `reads a link's fragment as the web does`() {
        val all = cases("readGridLink")
        assertTrue(all.size >= 15)
        all.forEach { c ->
            val read = GridShare.readGridLink(c.str("hash"))
            val expected = c["expected"]!!
            if (expected is JsonNull) {
                assertNull(read, c.str("hash"))
            } else {
                val e = expected.jsonObject
                assertEquals(e.str("mgrs"), read!!.choice.mgrs, c.str("hash"))
                assertEquals(e["lat"]!!.jsonPrimitive.double, read.choice.lat, 1e-9)
                assertEquals(e["lon"]!!.jsonPrimitive.double, read.choice.lon, 1e-9)
                assertEquals(e.str("name"), read.name, c.str("hash"))
            }
        }
    }

    @Test
    fun `decodes malformed UTF-8 as WHATWG does, one replacement for each maximal part`() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals("\uFFFD\uFFFD\uFFFDx", GridShare.utf8Decode(bytes(0xED, 0xA0, 0x80, 0x78)))
        assertEquals("\uFFFDx", GridShare.utf8Decode(bytes(0xF0, 0x9F, 0x98, 0x78)))
        assertEquals("\uFFFD", GridShare.utf8Decode(bytes(0xE0, 0xA4)))
        assertEquals("é\uD83D\uDE81", GridShare.utf8Decode("é\uD83D\uDE81".toByteArray()))
    }

    @Test
    fun `encodes as JavaScript's encodeURIComponent does`() {
        assertEquals("a%20b-_.!~*'()%26%2F%23%3F%3D%2B%25", GridShare.encodeUriComponent("a b-_.!~*'()&/#?=+%"))
        assertEquals("%C3%A9%F0%9F%9A%81", GridShare.encodeUriComponent("é🚁"))
    }
}
