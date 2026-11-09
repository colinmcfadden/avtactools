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

    private val live = VfrLive(
        build = "2026-10-29.1", tiles = "https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp", coverage = "/charts/vfr/2026-10-29.1/coverage.json",
        tileSize = 512, minZoom = 5, maxZoom = 14, effective = "2026-10-29T09:01Z", expires = "2026-12-24T09:01Z",
        bounds = ChartBox(-125.0, 24.0, -66.0, 50.0), fallback = listOf(ChartBox(-158.9, 18.6, -154.7, 22.6), ChartBox(165.9, 12.9, 180.0, 21.9)),
    )

    private fun styleDoc(style: MapBaseStyle) = Fixtures.json.parseToJsonElement(MapStyles.styleJson(style)).jsonObject

    private fun boundsOf(doc: JsonObject, source: String) =
        doc.getValue("sources").jsonObject.getValue(source).jsonObject["bounds"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }

    @Test
    fun `the VFR map is the live build, its zooms and size the config's, within its bounds`() {
        val doc = styleDoc(MapStyles.byId(MapStyles.VFR_ID, token, live))
        val source = doc.getValue("sources").jsonObject.getValue("base").jsonObject
        assertEquals(live.tiles, source.getValue("tiles").jsonArray.single().jsonPrimitive.content)
        assertEquals("512", source.getValue("tileSize").jsonPrimitive.content)
        assertEquals("5", source.getValue("minzoom").jsonPrimitive.content)
        assertEquals("14", source.getValue("maxzoom").jsonPrimitive.content)
        assertEquals(listOf(-125.0, 24.0, -66.0, 50.0), boundsOf(doc, "base"))
    }

    @Test
    fun `on a dense screen the charts are laid over half the points, one level finer, and every other tile is as it was`() {
        fun source(style: MapBaseStyle, density: Double, name: String) =
            Fixtures.json.parseToJsonElement(MapStyles.styleJson(style, density)).jsonObject.getValue("sources").jsonObject.getValue(name).jsonObject
        val vfr = MapStyles.byId(MapStyles.VFR_ID, token, live)
        val dense = source(vfr, 3.0, "base")
        assertEquals("256", dense.getValue("tileSize").jsonPrimitive.content)
        assertEquals("5", dense.getValue("minzoom").jsonPrimitive.content)
        assertEquals("14", dense.getValue("maxzoom").jsonPrimitive.content)
        assertEquals("512", source(vfr, 1.0, "base").getValue("tileSize").jsonPrimitive.content)
        assertEquals("256", source(vfr, 3.0, "fallback-0").getValue("tileSize").jsonPrimitive.content)
        assertEquals("512", source(MapStyles.byId("satellite", token), 3.0, "base").getValue("tileSize").jsonPrimitive.content)
        assertEquals("256", source(MapStyles.byId(MapStyles.VFR_ID, token, null), 3.0, "base").getValue("tileSize").jsonPrimitive.content)
    }

    @Test
    fun `a screen draws a level finer only from the density where that level is the nearer of the two, and never two`() {
        assertEquals(listOf(0, 0, 1, 1, 1, 1), listOf(1.0, 1.33, 1.5, 2.0, 3.0, 4.0).map(::levelsFiner))
    }

    @Test
    fun `each region with no chart of ours is ArcGIS within its own bounds, under the chart layer`() {
        val doc = styleDoc(MapStyles.byId(MapStyles.VFR_ID, token, live))
        assertEquals(listOf(-158.9, 18.6, -154.7, 22.6), boundsOf(doc, "fallback-0"))
        assertEquals(listOf(165.9, 12.9, 180.0, 21.9), boundsOf(doc, "fallback-1"))
        val under = doc.getValue("sources").jsonObject.getValue("fallback-1").jsonObject
        assertTrue(under.getValue("tiles").jsonArray.single().jsonPrimitive.content.startsWith("https://tiles.arcgis.com/"))
        assertEquals("256", under.getValue("tileSize").jsonPrimitive.content)
        // On top is `base`, which the MGRS grid and everything else is put above.
        assertEquals(listOf("background", "fallback-0", "fallback-1", "base"), (doc.getValue("layers") as JsonArray).map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun `with no build at all the VFR map is ArcGIS everywhere, and has no bounds`() {
        val doc = styleDoc(MapStyles.byId(MapStyles.VFR_ID, token, null))
        assertTrue(doc.getValue("sources").jsonObject.getValue("base").jsonObject.getValue("tiles").jsonArray.single().jsonPrimitive.content.startsWith("https://tiles.arcgis.com/"))
        assertNull(boundsOf(doc, "base"))
        assertEquals(setOf("base"), doc.getValue("sources").jsonObject.keys)
    }

    @Test
    fun `a build changes the VFR map alone, and keeps its id and the name a diagram saves`() {
        val with = MapStyles.available(token, live)
        assertEquals(listOf("satellite", "topo", MapStyles.VFR_ID), with.map { it.id })
        assertEquals(MapStyles.available(token).take(2), with.take(2))
        assertEquals("VFR", with.last().label)
    }

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
}
