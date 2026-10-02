package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapLogicTest {
    private val token = "pk.test-token"

    // -- Base styles --------------------------------------------------------------------------------

    @Test
    fun `the three base maps have the ids a saved diagram uses`() {
        assertEquals(listOf("satellite", "topo", "vfr-sectional"), MapStyles.available(token).map { it.id })
    }

    @Test
    fun `satellite is drawn from Mapbox's 512 point retina tiles with the token in the url`() {
        val satellite = MapStyles.byId("satellite", token)
        assertEquals("https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/512/{z}/{x}/{y}@2x?access_token=$token", satellite.tiles)
        assertEquals(512, satellite.tileSize)
        assertTrue(satellite.needsMapboxToken)
        assertEquals("https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/512/{z}/{x}/{y}@2x?access_token=$token", MapStyles.byId("topo", token).tiles)
    }

    @Test
    fun `the FAA chart only has levels 8 to 12, and ArcGIS puts y before x`() {
        val vfr = MapStyles.byId("vfr-sectional", token)
        assertEquals(8, vfr.minZoom)
        assertEquals(12, vfr.maxZoom)
        assertTrue(vfr.tiles.endsWith("/tile/{z}/{y}/{x}"))
        assertFalse(vfr.needsMapboxToken)
    }

    @Test
    fun `a stale or unknown id falls back to the first map that can be drawn`() {
        assertEquals("satellite", MapStyles.byId("some-removed-style", token).id)
        assertEquals("satellite", MapStyles.byId(null, token).id)
        assertEquals("satellite", MapStyles.DEFAULT_ID)
    }

    @Test
    fun `with no token only the FAA chart is offered, and asking for satellite gets it rather than a blank map`() {
        assertEquals(listOf("vfr-sectional"), MapStyles.available(null).map { it.id })
        assertEquals("vfr-sectional", MapStyles.byId("satellite", null).id)
    }

    @Test
    fun `a token that is not a public one is not used`() {
        assertEquals(listOf("vfr-sectional"), MapStyles.available("sk.a-secret-token").map { it.id })
        assertEquals(listOf("vfr-sectional"), MapStyles.available("").map { it.id })
    }

    @Test
    fun `a style document is a raster source with the right zoom range over a navy background`() {
        val doc = Fixtures.json.parseToJsonElement(MapStyles.styleJson(MapStyles.byId("vfr-sectional", token))).jsonObject
        assertEquals(8, doc.getValue("version").jsonPrimitive.content.toInt())
        val source = doc.getValue("sources").jsonObject.getValue("base").jsonObject
        assertEquals("raster", source.getValue("type").jsonPrimitive.content)
        assertEquals("8", source.getValue("minzoom").jsonPrimitive.content)
        assertEquals("12", source.getValue("maxzoom").jsonPrimitive.content)
        assertEquals("256", source.getValue("tileSize").jsonPrimitive.content)
        val layers = doc.getValue("layers") as JsonArray
        assertEquals(listOf("background", "base"), layers.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals("#092137", layers[0].jsonObject.getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content)
    }

    // -- The readout under the crosshair --------------------------------------------------------------

    @Test
    fun `the readout gives the grid PyGeodesy gives, for every named place in the fixtures`() {
        val named = Fixtures.load("mgrs/forward.json").getValue("named").jsonArray
        assertTrue(named.size >= 3)
        for (entry in named) {
            val o = entry.jsonObject
            val readout = Readout.of(LatLon(o.getValue("lat").jsonPrimitive.content.toDouble(), o.getValue("lon").jsonPrimitive.content.toDouble()))
            assertEquals(o.getValue("name").jsonPrimitive.content, o.getValue("mgrs").jsonPrimitive.content, readout.mgrs)
        }
    }

    @Test
    fun `the readout says the degrees too, and says no grid where there is none`() {
        val readout = Readout.of(LatLon(34.783817, -84.08219))
        assertEquals("34.78382, -84.08219", readout.latLon)
        assertNull(Readout.of(LatLon(89.9, 10.0)).mgrs)                                      // the polar caps are not UTM
    }

    @Test
    fun `scale shrinks with latitude, and halves with each zoom level`() {
        assertEquals(78_271.516964, metersPerPixel(0.0, 0.0), 1e-6)
        assertEquals(metersPerPixel(0.0, 10.0) / 2, metersPerPixel(0.0, 11.0), 1e-9)
        assertEquals(metersPerPixel(0.0, 10.0) * 0.5, metersPerPixel(60.0, 10.0), 1e-9)       // cos 60 degrees is one half
        assertTrue(metersPerPixel(34.78, 18.0) in 0.2..0.3)                                  // about a quarter of a metre per point at LZ zoom
    }

    @Test
    fun `two cameras are the same view when nothing visible moved`() {
        val a = CameraState(LatLon(34.0, -84.0), 15.0)
        assertTrue(a.sameView(a.copy(center = LatLon(34.00000001, -84.0))))
        assertFalse(a.sameView(a.copy(zoom = 15.5)))
        assertFalse(a.sameView(a.copy(center = LatLon(34.001, -84.0))))
    }

    // -- Search ---------------------------------------------------------------------------------------------

    @Test
    fun `a grid, in any spacing and case, is found where the grid is`() {
        for (typed in listOf("16S GD 66993 52949", "16sgd6699352949", "  16S  GD  66993  52949 ")) {
            val place = Search.resolve(typed) as SearchResult.Place
            assertTrue(typed, abs(place.at.lat - 34.783817) < 0.0001 && abs(place.at.lon - -84.08219) < 0.0001)
            assertEquals("MGRS grid", place.description)
        }
    }

    @Test
    fun `a less exact grid is the middle of its square`() {
        val place = Search.resolve("16S GD 66 52") as SearchResult.Place
        assertNotNull(place.at)
        assertTrue(abs(place.at.lat - 34.78) < 0.05)
    }

    @Test
    fun `coordinates in the formats crews paste are understood, and the person is told which`() {
        val decimal = Search.resolve("34.545678, -84.123456") as SearchResult.Place
        assertEquals(34.545678, decimal.at.lat, 1e-9)
        assertEquals(-84.123456, decimal.at.lon, 1e-9)
        assertEquals("Decimal degrees", decimal.description)
        val ddm = Search.resolve("34°32.740'N 084°07.407'W") as SearchResult.Place
        assertEquals(34.5456667, ddm.at.lat, 1e-6)
        assertEquals("Degrees/decimal minutes", ddm.description)
    }

    @Test
    fun `what cannot be read says so, and nothing is moved`() {
        assertTrue(Search.resolve("") is SearchResult.NotUnderstood)
        assertTrue(Search.resolve("   ") is SearchResult.NotUnderstood)
        assertTrue(Search.resolve("somewhere nice") is SearchResult.NotUnderstood)
        assertTrue(Search.resolve("99.0, 200.0") is SearchResult.NotUnderstood)
        assertTrue(Search.resolve("16S ZZ 123") is SearchResult.NotUnderstood)
    }
}
