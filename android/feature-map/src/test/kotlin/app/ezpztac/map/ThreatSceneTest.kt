package app.ezpztac.map

import app.ezpztac.model.LatLon
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

    // -- The rings themselves ---------------------------------------------------------------------------------------------------

    private fun haversineNmi(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.pow(Math.sin(dLat / 2), 2.0) + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.pow(Math.sin(dLon / 2), 2.0)
        return 2 * 3440.065 * Math.asin(Math.sqrt(h))
    }

    @Test
    fun `a ring is a closed loop whose every point is the range from the threat, anywhere on the earth`() {
        for (center in listOf(LatLon(34.75, -84.05), LatLon(0.0, 0.0), LatLon(64.5, 10.0), LatLon(-33.9, 151.2))) {
            val loop = ThreatScene.circle(center, 25.0)
            assertEquals(ThreatScene.RING_SEGMENTS + 1, loop.size)
            assertEquals(loop.first().lat, loop.last().lat, 1e-9)
            assertEquals(loop.first().lon, loop.last().lon, 1e-9)
            for (p in loop) assertEquals("at $center", 25.0, haversineNmi(center, p), 25.0 * 0.0005)
        }
    }

    @Test
    fun `a ring starts due north and goes round clockwise`() {
        val center = LatLon(34.75, -84.05)
        val loop = ThreatScene.circle(center, 5.0)
        assertTrue(loop[0].lat > center.lat)
        assertEquals(center.lon, loop[0].lon, 1e-9)
        assertTrue(loop[ThreatScene.RING_SEGMENTS / 4].lon > center.lon)                              // a quarter of the way round is east
        assertTrue(loop[ThreatScene.RING_SEGMENTS * 3 / 4].lon < center.lon)
    }

    @Test
    fun `a ring across the antimeridian stays one continuous line, with longitudes past 180 and no jump across the map`() {
        val loop = ThreatScene.circle(LatLon(10.0, 179.9), 30.0)
        for (i in 1 until loop.size) assertTrue("jump at $i", Math.abs(loop[i].lon - loop[i - 1].lon) < 1.0)
        assertTrue(loop.any { it.lon > 180.0 })
    }
}
