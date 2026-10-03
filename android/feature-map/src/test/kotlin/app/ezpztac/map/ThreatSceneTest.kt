package app.ezpztac.map

import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatSceneTest {
    private fun entry(
        id: String,
        visible: Boolean = true,
        lat: Double = 34.75,
        lon: Double = -84.05,
        rings: Boolean = true,
    ): ThreatEntry = ThreatEntry(
        id,
        Threat("SA-8", "SHGPEWRR------", lat, lon, "", "SOF", radars = Radars.defaultPair().map { it.copy(showRangeRings = rings) }),
        visible,
    )

    @Test
    fun `visible threats become symbols and their two range rings`() {
        val scene = ThreatScene.of(listOf(entry("a")), selectedId = "a")
        assertEquals(1, scene.pins.size)
        assertEquals("SA-8", scene.pins.single().name)
        assertTrue(scene.pins.single().selected)
        assertEquals(listOf(Radars.DETECTION, Radars.ENGAGEMENT), scene.rings.map { it.type })

        val features = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray
        assertEquals(2, features.size)
        val properties = features.map { it.jsonObject.getValue("properties").jsonObject }
        assertEquals(listOf("ring", "ring"), properties.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(listOf(true, false), properties.map { it.getValue("dashed").jsonPrimitive.boolean })
        val points = features.first().jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(97, points.size)
        assertEquals(points.first(), points.last())
    }

    @Test
    fun `hidden or invalid threats are absent and disabled rings stay absent`() {
        val scene = ThreatScene.of(listOf(entry("hidden", visible = false), entry("bad", lat = Double.NaN), entry("plain", rings = false)))
        assertEquals(listOf("plain"), scene.pins.map { it.id })
        assertTrue(scene.rings.isEmpty())
        assertFalse(scene.isEmpty)
        assertTrue(ThreatScene.of(listOf(entry("hidden", visible = false))).isEmpty)
    }

    @Test
    fun `nonpositive and unreadable ranges are not drawn`() {
        val threat = entry("odd").let { entry ->
            entry.copy(threat = entry.threat.copy(radars = listOf(
                Radars.default(Radars.DETECTION).copy(rangeNmi = 0.0),
                Radars.default(Radars.ENGAGEMENT).copy(rangeNmi = Double.POSITIVE_INFINITY),
            )))
        }
        assertTrue(ThreatScene.of(listOf(threat)).rings.isEmpty())
    }
}
