package app.ezpztac.map

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramAnalysis
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LzSceneTest {
    private fun ring(vararg points: Pair<Double, Double>) = JsonArray(points.map { (a, b) -> JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(b))) })

    private fun diagram(target: DiagramTarget? = DiagramTarget(34.78, -84.08, "16S GD 1 2"), analysis: DiagramAnalysis = DiagramAnalysis()) = Diagram(
        id = "d", createdAt = "t", updatedAt = "t", status = DiagramStatus.TARGETED, target = target, analysis = analysis,
    )

    private fun features(scene: LzScene) = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.map { it.jsonObject }
    private fun role(f: JsonObject) = f.getValue("properties").jsonObject.getValue("role").jsonPrimitive.content
    private fun geometry(f: JsonObject) = f.getValue("geometry").jsonObject

    @Test
    fun `nothing open draws nothing`() {
        assertTrue(LzScene.of(null).isEmpty)
        assertEquals(emptyList<JsonObject>(), features(LzScene.EMPTY))
        assertEquals("FeatureCollection", Json.parseToJsonElement(LzScene.EMPTY.geoJson()).jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a targeted diagram draws its target, longitude first`() {
        val scene = LzScene.of(diagram())
        assertEquals(LatLon(34.78, -84.08), scene.target)
        val target = features(scene).single()
        assertEquals("target", role(target))
        assertEquals("Point", geometry(target).getValue("type").jsonPrimitive.content)
        assertEquals(listOf(-84.08, 34.78), geometry(target).getValue("coordinates").jsonArray.map { it.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `a draft with no target has no target to draw`() {
        assertNull(LzScene.of(diagram(target = null)).target)
    }

    @Test
    fun `the analysis boundary is a closed polygon in GeoJSON order`() {
        val analysis = DiagramAnalysis(detectedLZ = ring(34.71 to -84.09, 34.71 to -84.01, 34.79 to -84.01))
        val scene = LzScene.of(diagram(analysis = analysis))
        assertEquals(3, scene.boundary.size)                                               // not closed in the scene...
        val polygon = features(scene).single { role(it) == "boundary" }
        val coordinates = geometry(polygon).getValue("coordinates").jsonArray.single().jsonArray
        assertEquals(4, coordinates.size)                                                  // ...but closed on the way out
        assertEquals(coordinates.first(), coordinates.last())
        assertEquals(listOf(-84.09, 34.71), coordinates.first().jsonArray.map { it.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `two points are not a boundary`() {
        val scene = LzScene.of(diagram(analysis = DiagramAnalysis(detectedLZ = ring(34.7 to -84.1, 34.8 to -84.0))))
        assertEquals(emptyList<LatLon>(), scene.boundary)
        assertEquals(listOf("target"), features(scene).map(::role))
    }

    @Test
    fun `a boundary being drawn is a line until it has three points`() {
        val line = LzScene.of(diagram(analysis = DiagramAnalysis(customLZ = ring(34.7 to -84.1, 34.8 to -84.0))))
        val drawn = features(line).single { role(it) == "drawn" }
        assertEquals("LineString", geometry(drawn).getValue("type").jsonPrimitive.content)

        val polygon = LzScene.of(diagram(analysis = DiagramAnalysis(customLZ = ring(34.7 to -84.1, 34.8 to -84.0, 34.8 to -84.1))))
        assertEquals("Polygon", geometry(features(polygon).single { role(it) == "drawn" }).getValue("type").jsonPrimitive.content)

        assertEquals(emptyList<LatLon>(), LzScene.of(diagram(analysis = DiagramAnalysis(customLZ = ring(34.7 to -84.1)))).drawn)
    }

    @Test
    fun `a point that is not a pair of numbers is left out, and a boundary of nothing but rubbish is none`() {
        val rubbish = JsonArray(listOf(JsonPrimitive("x"), JsonArray(listOf(JsonPrimitive(1))), JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), JsonNull))
        assertEquals(emptyList<LatLon>(), LzScene.of(diagram(analysis = DiagramAnalysis(detectedLZ = rubbish))).boundary)
        val mixed = JsonArray(ring(34.7 to -84.1, 34.7 to -84.0, 34.8 to -84.0) + JsonPrimitive("junk"))
        assertEquals(3, LzScene.of(diagram(analysis = DiagramAnalysis(detectedLZ = mixed))).boundary.size)
        assertEquals(emptyList<LatLon>(), LzScene.of(diagram(analysis = DiagramAnalysis(detectedLZ = JsonPrimitive("nope")))).boundary)
    }

    @Test
    fun `the slope raster is carried as it is and is not part of the vector layer`() {
        val slope = SlopeImage(34.7, -84.1, 34.8, -84.0, "data:image/png;base64,AA==")
        val scene = LzScene.of(diagram(), slope)
        assertEquals(slope, scene.slope)
        assertTrue(!scene.geoJson().contains("base64"))
    }
}
