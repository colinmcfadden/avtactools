package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.model.SetPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

/** What the map draws for the sets of local points that are shown. */
class PointSceneTest {
    private fun point(id: String, name: String, lat: Double = 34.5, lon: Double = -84.2) = SetPoint(id, name, "", "", "", null, lat, lon)

    private val blue = DrawnPointSet("s1", "#0A84FF", listOf(point("a", "BLUE 1"), point("b", "FARP", 34.6, -84.1)))
    private val red = DrawnPointSet("s2", "#FF453A", listOf(point("a", "RED 1", 35.0, -85.0)))

    private fun features(scene: PointScene) = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.map { it.jsonObject }

    @Test
    fun `no sets, an empty scene`() {
        assertTrue(PointScene.of(emptyList()).isEmpty)
        assertTrue(PointScene.EMPTY.isEmpty)
        assertEquals(0, features(PointScene.EMPTY).size)
    }

    @Test
    fun `every point of every shown set is a pin in its set's colour, in order`() {
        val scene = PointScene.of(listOf(blue, red))
        assertEquals(listOf("BLUE 1", "FARP", "RED 1"), scene.pins.map { it.name })
        assertEquals(listOf("#0A84FF", "#0A84FF", "#FF453A"), scene.pins.map { it.color })
        assertEquals(listOf("s1", "s1", "s2"), scene.pins.map { it.setId })
        assertEquals(LatLon(34.6, -84.1), scene.pins[1].at)
        assertFalse(scene.isEmpty)
    }

    @Test
    fun `a point id is only unique in its set, so the held point is named by both`() {
        val scene = PointScene.of(listOf(blue, red), selectedSetId = "s2", selectedPointId = "a")
        assertEquals(listOf(false, false, true), scene.pins.map { it.selected })              // "a" is in both sets; only the one in s2 is held
        assertEquals(listOf(false, false, false), PointScene.of(listOf(blue, red), selectedSetId = "s2", selectedPointId = "nope").pins.map { it.selected })
        assertEquals(listOf(false, false, false), PointScene.of(listOf(blue, red), selectedSetId = null, selectedPointId = "a").pins.map { it.selected })
    }

    @Test
    fun `a point with no position on the map is not drawn`() {
        val odd = DrawnPointSet("s3", "#32D74B", listOf(point("x", "NaN", Double.NaN, -84.0), point("y", "INF", 1.0, Double.POSITIVE_INFINITY), point("z", "OK")))
        assertEquals(listOf("OK"), PointScene.of(listOf(odd)).pins.map { it.name })
    }

    @Test
    fun `the geojson has each point at longitude then latitude, with its colour, name and whether it is held`() {
        val scene = PointScene.of(listOf(blue), selectedSetId = "s1", selectedPointId = "b")
        val list = features(scene)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("Feature", first.getValue("type").jsonPrimitive.content)
        val coordinates = first.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(-84.2, coordinates[0].jsonPrimitive.double, 0.0)
        assertEquals(34.5, coordinates[1].jsonPrimitive.double, 0.0)
        assertEquals("Point", first.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
        val properties = first.getValue("properties").jsonObject
        assertEquals("#0A84FF", properties.getValue("color").jsonPrimitive.content)
        assertEquals("BLUE 1", properties.getValue("name").jsonPrimitive.content)
        assertFalse(properties.getValue("selected").jsonPrimitive.boolean)
        assertTrue(list[1].getValue("properties").jsonObject.getValue("selected").jsonPrimitive.boolean)
    }

    @Test
    fun `a name with quotes and unicode survives into the geojson as it was`() {
        val tricky = DrawnPointSet("s", "#fff", listOf(point("q", "A \"B\" \\ ÀÉÎ ⛰")))
        val name = features(PointScene.of(listOf(tricky))).single().getValue("properties").jsonObject.getValue("name").jsonPrimitive.content
        assertEquals("A \"B\" \\ ÀÉÎ ⛰", name)
    }

    @Test
    fun `an empty set draws nothing`() {
        assertTrue(PointScene.of(listOf(DrawnPointSet("e", "#fff", emptyList()))).isEmpty)
    }
}
