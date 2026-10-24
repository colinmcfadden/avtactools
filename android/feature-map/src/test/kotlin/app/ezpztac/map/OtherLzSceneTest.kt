package app.ezpztac.map

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramAnalysis
import app.ezpztac.model.DiagramGraphics
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.DiagramView
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The open LZ/PZs not being worked on, as the map draws them faded: what of each is drawn, which one a tap was on, and their names. */
class OtherLzSceneTest {
    private val uh60 = AircraftProfile.FALLBACK

    private fun ring(vararg points: Pair<Double, Double>) = JsonArray(points.map { (a, b) -> JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(b))) })
    private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) as JsonElement })

    private val box = ring(34.781 to -84.083, 34.781 to -84.081, 34.783 to -84.081, 34.783 to -84.083)

    private fun lz(
        id: String,
        name: String,
        at: LatLon = LatLon(34.782, -84.082),
        analysis: DiagramAnalysis = DiagramAnalysis(detectedLZ = box),
        graphics: DiagramGraphics = DiagramGraphics(),
        view: DiagramView = DiagramView(),
    ) = Diagram(
        id = id, name = name, createdAt = "t", updatedAt = "t", status = DiagramStatus.ANALYZED, target = DiagramTarget(at.lat, at.lon, "16S GD 66993 52949"),
        analysis = analysis, graphics = graphics, view = view,
    )

    private val crowded = DiagramGraphics(
        helicopters = listOf(
            obj("id" to 1, "lat" to 34.782, "lon" to -84.0821, "rotation" to 90, "profileId" to "uh60l"),
            obj("id" to 2, "lat" to 34.782, "lon" to -84.0820, "rotation" to 90, "profileId" to "uh60l"),        // too close to the first: red on the open one
        ),
        pzMarkers = listOf(obj("id" to "pz-1", "lat" to 34.7815, "lon" to -84.0825, "tipLat" to 34.7815, "tipLon" to -84.0830)),
        goArounds = listOf(obj("id" to "ga-1", "lat" to 34.7825, "lon" to -84.0815, "rotation" to 45, "direction" to "right")),
        doghouses = listOf(obj("id" to "dh1", "lat" to 34.784, "lon" to -84.082, "rotation" to 270)),
        units = listOf(obj("id" to "u1", "lat" to 34.7805, "lon" to -84.0822, "sidc" to "SFGPUCI----D")),
    )

    private fun features(scene: OtherLzScene) = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.map { it.jsonObject }
    private fun role(f: JsonObject) = f.getValue("properties").jsonObject.getValue("role").jsonPrimitive.content
    private fun prop(f: JsonObject, key: String) = f.getValue("properties").jsonObject.getValue(key).jsonPrimitive.content

    @Test
    fun `none open but the one worked on draws nothing`() {
        assertTrue(OtherLzScene.of(emptyList()).isEmpty)
        assertEquals(emptyList<JsonObject>(), features(OtherLzScene.EMPTY))
    }

    @Test
    fun `each is drawn with its target, boundary and where things stand, each feature saying whose it is`() {
        val scene = OtherLzScene.of(listOf(lz("a", "LZ HAWK", graphics = crowded), lz("b", "LZ DOVE", at = LatLon(34.80, -84.10))), listOf(uh60), uh60)
        val all = features(scene)
        val hawk = all.filter { prop(it, "lz") == "a" }
        assertEquals(listOf("boundary", "pz", "pz-anchor", "pz-tip", "ga", "heli", "heli", "target"), hawk.map(::role))
        assertEquals(listOf("boundary", "target"), all.filter { prop(it, "lz") == "b" }.map(::role))
        // Never red: how close aircraft stand is said for the one being worked on.
        val placed = scene.lzs.first().graphics.aircraft
        assertTrue(placed.all { it.violating })                                                   // as the open one measures them
        assertEquals(placed.map { LzScene.aircraftImage(it.iconKey, violating = false) }, hawk.filter { role(it) == "heli" }.map { prop(it, "icon") })
        assertEquals("ga-right", prop(hawk.single { role(it) == "ga" }, "icon"))
        assertEquals("16S GD 66993 52949", scene.lzs.first().grid)
        // The target's coordinates longitude first, as GeoJSON has them.
        val target = hawk.single { role(it) == "target" }.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(listOf(-84.082, 34.782), target.map { it.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `none of their separations, doghouses, units or slope map is drawn`() {
        val other = OtherLzScene.of(listOf(lz("a", "LZ HAWK", graphics = crowded)), listOf(uh60), uh60).lzs.single()
        assertTrue(other.graphics.separations.isEmpty())
        assertTrue(other.graphics.doghouses.isEmpty())
        assertTrue(other.graphics.units.isEmpty())
        assertEquals(2, other.graphics.aircraft.size)
        assertFalse(features(OtherLzScene(listOf(other))).any { role(it) in setOf("sep", "ring", "halo", "corner") })
    }

    @Test
    fun `its own view hides its analysis boundary, and a boundary drawn by hand is drawn whatever it says`() {
        val hidden = DiagramView(showLZOutline = false)
        assertTrue(OtherLzScene.of(listOf(lz("a", "LZ HAWK", view = hidden))).lzs.single().boundary.isEmpty())
        val drawn = OtherLzScene.of(listOf(lz("a", "LZ HAWK", analysis = DiagramAnalysis(customLZ = box), view = hidden)))
        assertEquals(listOf("drawn", "target"), features(drawn).map(::role))
    }

    // -- A tap ---------------------------------------------------------------------------------------------------------------

    private fun view(center: LatLon = LatLon(34.782, -84.082)) = MapProjection(CameraState(center, 18.0), 400.0, 800.0, 2.0)

    @Test
    fun `a tap on its target, its name, an aircraft or an arrow is a tap on it, and the nearest one wins`() {
        val scene = OtherLzScene.of(listOf(lz("a", "LZ HAWK", graphics = crowded), lz("b", "LZ DOVE", at = LatLon(34.7823, -84.082))), listOf(uh60), uh60)
        val v = view()
        assertEquals("a", OtherLzHitTest.pick(scene, v, LatLon(34.782, -84.082), 48.0))
        assertEquals("b", OtherLzHitTest.pick(scene, v, LatLon(34.7823, -84.082), 48.0))
        // On the name, to the right of the target, well past a finger's reach of it.
        val dove = v.toScreen(LatLon(34.7823, -84.082))
        assertEquals("b", OtherLzHitTest.pick(scene, v, v.toLatLon(dove.x + 60.0 * 2, dove.y), 48.0))
        assertNull("past the end of its name", OtherLzHitTest.pick(scene, v, v.toLatLon(dove.x + 400.0 * 2, dove.y), 48.0))
        // An aircraft anywhere on its rotor (7 m from its mast: past a finger's reach, inside the rotor's 8.2), a PZ arrow along its length, a go-around.
        assertEquals("a", OtherLzHitTest.pick(scene, v, LatLon(34.782 + 7 / 111_320.0, -84.0821), 48.0))
        assertEquals("a", OtherLzHitTest.pick(scene, v, LatLon(34.7815, -84.08275), 48.0))
        assertEquals("a", OtherLzHitTest.pick(scene, v, LatLon(34.7825, -84.0815), 48.0))
        // Inside its boundary but on nothing of it: not a tap on it (a phone's tap there hides the frame).
        assertNull(OtherLzHitTest.pick(scene, v, LatLon(34.7812, -84.0812), 48.0))
    }

    @Test
    fun `its name is laid at its target, faded, and one with no target or name has none`() {
        val v = view()
        val scene = OtherLzScene(
            listOf(
                OtherLzScene.of(listOf(lz("a", "LZ HAWK"))).lzs.single(),
                OtherLz("b", "", LatLon(34.782, -84.082), null, emptyList(), emptyList(), GraphicsScene.EMPTY),
                OtherLz("c", "LZ CROW", null, null, emptyList(), emptyList(), GraphicsScene.EMPTY),
            ),
        )
        val labels = PinLabels.others(scene, v)
        assertEquals(listOf("LZ HAWK"), labels.map { it.text })
        assertTrue(labels.single().dim)
        assertEquals("Also open: LZ HAWK", labels.single().description)
        assertEquals(v.toScreen(LatLon(34.782, -84.082)), labels.single().at)
    }
}
