package app.ezpztac.map

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGraphics
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class GraphicsSceneTest {
    private val uh60 = AircraftProfile.FALLBACK
    private val chinook = AircraftProfile(id = 7, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29, rotorTipClearanceM = 75.0)
    private val profiles = listOf(uh60, chinook)

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to prim(v) })
    private fun prim(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun helo(id: Any, lat: Double, lon: Double, rotation: Double = 0.0, profile: String? = "uh60l") =
        obj("id" to id, "lat" to lat, "lon" to lon, "rotation" to rotation, "type" to "helo", "profileId" to profile)

    private fun diagram(graphics: DiagramGraphics = DiagramGraphics()) = Diagram(
        id = "d", createdAt = "t", updatedAt = "t", status = DiagramStatus.ANALYZED, target = DiagramTarget(34.5, -84.1), graphics = graphics,
    )

    private fun sceneOf(graphics: DiagramGraphics, selected: GraphicRef? = null) = GraphicsScene.of(diagram(graphics), profiles, uh60, selected)

    // -- Aircraft --------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing open has no graphics`() {
        assertTrue(GraphicsScene.of(null, profiles, uh60).isEmpty)
        assertTrue(sceneOf(DiagramGraphics()).isEmpty)
    }

    @Test
    fun `an aircraft is drawn where it is, turned as it is, the size of its own rotor`() {
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(helo(1700000000000L, 34.5, -84.1, rotation = 270.0, profile = "ch47f"))))
        val a = scene.aircraft.single()
        assertEquals(GraphicRef("helicopters", "1700000000000"), a.ref)
        assertEquals(LatLon(34.5, -84.1), a.at)
        assertEquals(270.0, a.rotationDeg, 0.0)
        assertEquals(18.29, a.diameterM, 0.0)
        assertEquals("ch47", a.iconKey)
        assertEquals("CH-47F", a.designation)
        assertFalse(a.violating)
    }

    @Test
    fun `an aircraft with no profile is the active one, and one that names none that is known is too`() {
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1, profile = null), helo(2, 34.51, -84.1, profile = "no-such-aircraft"))))
        assertEquals(listOf("UH-60L", "UH-60L"), scene.aircraft.map { it.designation })
    }

    @Test
    fun `a rotation that is missing is zero, and an aircraft with no position is not drawn`() {
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(obj("id" to 1, "lat" to 34.5, "lon" to -84.1), obj("id" to 2), JsonPrimitive("junk"))))
        assertEquals(listOf(0.0), scene.aircraft.map { it.rotationDeg })
    }

    @Test
    fun `an icon name this version does not know is drawn as the generic aircraft`() {
        val odd = AircraftProfile(slug = "odd", designation = "ODD", iconKey = "flying-saucer")
        val scene = GraphicsScene.of(diagram(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1, profile = "odd")))), listOf(odd), uh60)
        assertEquals("generic", scene.aircraft.single().iconKey)
    }

    // -- Separation ------------------------------------------------------------------------------------------------------

    @Test
    fun `two aircraft far apart have a grey line between their rotor tips and neither is red`() {
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1), helo(2, 34.5, -84.099))))
        val line = scene.separations.single()
        assertFalse(line.violating)
        assertEquals("247 ft", line.label)
        assertTrue(scene.aircraft.none { it.violating })
        assertTrue(line.from.lon < line.to.lon)                                              // from one tip to the other, not centre to centre
        assertTrue(line.from.lon > -84.1 && line.to.lon < -84.099)
    }

    @Test
    fun `two that are too close are both red, with the gap said in feet`() {
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1), helo(2, 34.5, -84.0995), helo(3, 34.51, -84.1))))
        assertEquals(3, scene.separations.size)                                              // one line for every pair
        assertEquals(listOf(true, true, false), scene.aircraft.map { it.violating })
        assertEquals("97 ft", scene.separations.first().label)
        assertTrue(scene.separations.first().violating)
    }

    @Test
    fun `overlapping rotors say zero feet, never a negative`() {
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1), helo(2, 34.50005, -84.1))))
        assertEquals("0 ft", scene.separations.single().label)
        assertTrue(scene.separations.single().violating)
    }

    @Test
    fun `a pair is judged by the stricter of the two aircraft's clearances`() {
        // A Chinook and a Black Hawk 93 ft apart at the tips: inside the Chinook's 246 ft, outside what a Black Hawk pair would need to be fine.
        val scene = sceneOf(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1, profile = "uh60l"), helo(2, 34.5, -84.0995, profile = "ch47f"))))
        assertTrue(scene.separations.single().violating)
    }

    // -- Selection -------------------------------------------------------------------------------------------------------

    @Test
    fun `the selected graphic is marked, and the halo sits on it`() {
        val graphics = DiagramGraphics(
            helicopters = listOf(helo(1, 34.5, -84.1), helo(2, 34.51, -84.1)),
            pzMarkers = listOf(obj("id" to "pz-1", "lat" to 34.52, "lon" to -84.1, "tipLat" to 34.52, "tipLon" to -84.102)),
        )
        val onHelo = sceneOf(graphics, GraphicRef("helicopters", "2"))
        assertEquals(listOf(false, true), onHelo.aircraft.map { it.selected })
        assertEquals(LatLon(34.51, -84.1), onHelo.selectedAt)
        val onPz = sceneOf(graphics, GraphicRef("pzMarkers", "pz-1"))
        assertEquals(LatLon(34.52, -84.1), onPz.selectedAt)
        assertTrue(onPz.pzMarkers.single().selected)
        assertNull(sceneOf(graphics).selectedAt)
        assertNull(sceneOf(graphics, GraphicRef("helicopters", "no-such")).selectedAt)
    }

    // -- The other graphics ----------------------------------------------------------------------------------------------

    @Test
    fun `a PZ marker runs from its anchor to its tip, and one with no tip points 0·001 degrees west as the web draws it`() {
        val scene = sceneOf(
            DiagramGraphics(
                pzMarkers = listOf(
                    obj("id" to "pz-1", "lat" to 34.5, "lon" to -84.1, "tipLat" to 34.5, "tipLon" to -84.102),
                    obj("id" to "pz-2", "lat" to 34.51, "lon" to -84.1),
                    obj("id" to "pz-3"),
                ),
            ),
        )
        assertEquals(LatLon(34.5, -84.102), scene.pzMarkers[0].tip)
        assertEquals(LatLon(34.51, -84.101), scene.pzMarkers[1].tip)
        assertEquals(2, scene.pzMarkers.size)                                               // no position, no marker
    }

    @Test
    fun `a sector of fire is a polygon of its points, and fewer than three is none`() {
        fun point(lat: Double, lng: Double) = obj("lat" to lat, "lng" to lng)
        val scene = sceneOf(
            DiagramGraphics(
                sectorsOfFire = listOf(
                    obj("id" to "sec-1", "points" to JsonArray(listOf(point(34.501, -84.1), point(34.499, -84.099), point(34.499, -84.101)))),
                    obj("id" to "sec-2", "points" to JsonArray(listOf(point(1.0, 2.0), point(3.0, 4.0)))),
                    obj("id" to "sec-3"),
                ),
            ),
        )
        assertEquals(1, scene.sectors.size)
        assertEquals(LatLon(34.501, -84.1), scene.sectors.single().ring.first())
    }

    @Test
    fun `a go-around keeps its direction and its turn`() {
        val scene = sceneOf(DiagramGraphics(goArounds = listOf(obj("id" to "ga-1", "lat" to 34.499, "lon" to -84.1, "direction" to "right", "rotation" to 30))))
        val ga = scene.goArounds.single()
        assertEquals("right", ga.direction)
        assertEquals(30.0, ga.rotationDeg, 0.0)
    }

    // -- What goes to the map -----------------------------------------------------------------------------------------------

    private fun features(scene: LzScene) = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.map { it.jsonObject }
    private fun role(f: JsonObject) = f.getValue("properties").jsonObject.getValue("role").jsonPrimitive.content
    private fun prop(f: JsonObject, key: String) = f.getValue("properties").jsonObject.getValue(key)

    @Test
    fun `every kind of graphic reaches the map under its own role`() {
        val graphics = DiagramGraphics(
            helicopters = listOf(helo(1, 34.5, -84.1), helo(2, 34.5, -84.0995)),
            pzMarkers = listOf(obj("id" to "pz-1", "lat" to 34.52, "lon" to -84.1, "tipLat" to 34.52, "tipLon" to -84.102)),
            sectorsOfFire = listOf(obj("id" to "s", "points" to JsonArray(listOf(obj("lat" to 1, "lng" to 2), obj("lat" to 3, "lng" to 4), obj("lat" to 5, "lng" to 6))))),
            goArounds = listOf(obj("id" to "ga-1", "lat" to 34.499, "lon" to -84.1, "direction" to "left", "rotation" to 0)),
        )
        val scene = LzScene.of(diagram(graphics), null, profiles, uh60, GraphicRef("helicopters", "1"))
        val roles = features(scene).map(::role)
        for (expected in listOf("ring", "heli", "sep", "pz", "pz-anchor", "pz-tip", "sector", "ga", "halo", "target")) assertTrue("$expected in $roles", expected in roles)
        assertEquals(2, roles.count { it == "heli" })
        assertEquals(1, roles.count { it == "ring" })                                        // only for the aircraft that is held
        assertEquals(1, roles.count { it == "sep" })
    }

    @Test
    fun `a violating aircraft uses the red picture, and the size factor is the rotor over the ground's width at zoom zero`() {
        val scene = LzScene.of(diagram(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1), helo(2, 34.5, -84.0995)))), null, profiles, uh60)
        val helos = features(scene).filter { role(it) == "heli" }
        assertEquals("ac-uh60-violation", prop(helos[0], "icon").jsonPrimitive.content)
        val k0 = prop(helos[0], "k0").jsonPrimitive.doubleOrNull!!
        assertEquals(16.357 / (78_271.516964 * cos(Math.toRadians(34.5))) / LzScene.AIRCRAFT_ICON_PX, k0, 1e-12)
        val calm = LzScene.of(diagram(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1)))), null, profiles, uh60)
        assertEquals("ac-uh60-normal", prop(features(calm).first { role(it) == "heli" }, "icon").jsonPrimitive.content)
    }

    @Test
    fun `GeoJSON is longitude then latitude for graphics too, and the keep-out ring is the rotor radius plus the clearance`() {
        val scene = LzScene.of(diagram(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1)))), null, profiles, uh60, GraphicRef("helicopters", "1"))
        val features = features(scene)
        val heli = features.first { role(it) == "heli" }
        assertEquals(listOf(-84.1, 34.5), heli.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.map { it.jsonPrimitive.content.toDouble() })
        val ring = features.first { role(it) == "ring" }.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.single().jsonArray
        val north = ring[0].jsonArray.map { it.jsonPrimitive.content.toDouble() }                // the first point of the ring is due north
        assertEquals(-84.1, north[0], 1e-9)
        assertEquals(16.357 / 2 + 60.0, Math.toRadians(north[1] - 34.5) * 6_378_137.0, 0.01)
        assertTrue(features.indexOfFirst { role(it) == "ring" } < features.indexOfFirst { role(it) == "heli" })
    }

    @Test
    fun `an aircraft that is not held has no ring`() {
        val scene = LzScene.of(diagram(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1)))), null, profiles, uh60)
        assertTrue(features(scene).none { role(it) == "ring" })
    }

    @Test
    fun `a circle is the right size on the ground and closes`() {
        val centre = LatLon(34.5, -84.1)
        val ring = GraphicsScene.circle(centre, 8.0)
        assertEquals(49, ring.size)
        assertEquals(ring.first(), ring.last())
        for (p in ring) {
            val dy = Math.toRadians(p.lat - centre.lat) * 6_378_137.0
            val dx = Math.toRadians(p.lon - centre.lon) * 6_378_137.0 * cos(Math.toRadians(centre.lat))
            assertEquals(8.0, Math.hypot(dx, dy), 0.01)
        }
    }

    @Test
    fun `bearings are compass bearings`() {
        val o = LatLon(34.5, -84.1)
        assertEquals(0.0, GraphicsScene.bearing(o, LatLon(34.6, -84.1)), 1e-6)
        assertEquals(90.0, GraphicsScene.bearing(o, LatLon(34.5, -84.0)), 0.1)
        assertEquals(180.0, GraphicsScene.bearing(o, LatLon(34.4, -84.1)), 1e-6)
        assertEquals(270.0, GraphicsScene.bearing(o, LatLon(34.5, -84.2)), 0.1)
        // North-west is not 315 degrees here: a degree of longitude is shorter than a degree of latitude at 34.5 north.
        assertEquals(320.6, GraphicsScene.bearing(o, LatLon(34.6, -84.2)), 0.2)
    }

    @Test
    fun `the scene with graphics is not empty, and without them it is as it was`() {
        assertFalse(LzScene.of(diagram(DiagramGraphics(helicopters = listOf(helo(1, 34.5, -84.1)))), null, profiles, uh60).isEmpty)
        assertNotNull(LzScene.of(diagram(), null, profiles, uh60).target)
    }
}
