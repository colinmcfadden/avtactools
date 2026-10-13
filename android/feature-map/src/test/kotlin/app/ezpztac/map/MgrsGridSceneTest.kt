package app.ezpztac.map

import app.ezpztac.geo.GridBox
import app.ezpztac.geo.GridLabel
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** How the map's grid is fitted to what shows: the bounds and scale the grid is asked for, its labels on the screen, and its lines for MapLibre. */
class MgrsGridSceneTest {
    private val density = 2.625
    private val width = 412 * density
    private val height = 800 * density

    @Test
    fun `facing north, the grid's view is the map from its top to the sheet, a grid point being a screen point`() {
        val camera = CameraState(LatLon(34.62, -84.18), 14.0, paddingTopPx = 64 * density, paddingBottomPx = 300 * density)
        val view = MgrsGridScene.viewOf(camera, width, height, density)!!
        assertFalse(view.turned)
        val projection = MapProjection(camera, width, height, density)
        val topLeft = projection.toLatLon(0.0, 0.0)
        val bottomRight = projection.toLatLon(width, height - 300 * density)
        assertEquals(topLeft.lat, view.bounds.north, 1e-9)
        assertEquals(topLeft.lon, view.bounds.west, 1e-9)
        assertEquals(bottomRight.lat, view.bounds.south, 1e-9)
        assertEquals(bottomRight.lon, view.bounds.east, 1e-9)
        // The grid's frame is as wide in its units as the map is in points: its 70-point gap is 70 points on the screen.
        val middle = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp((mercator(view.bounds.north) + mercator(view.bounds.south)) / 2)) - PI / 2)
        val pxPerDeg = 6378137 * cos(Math.toRadians(middle)) / view.metersPerPixel * PI / 180
        assertEquals(412.0, (view.bounds.east - view.bounds.west) * pxPerDeg, 1e-6)
        assertEquals(MgrsGridScene.PAD, view.options.pad, 0.0)
    }

    private fun mercator(lat: Double) = kotlin.math.ln(kotlin.math.tan(PI / 4 + Math.toRadians(lat) / 2))

    @Test
    fun `across the antimeridian the bounds run past 180, as the grid expects`() {
        val camera = CameraState(LatLon(-17.0, 179.99), 9.0)
        val view = MgrsGridScene.viewOf(camera, width, height, density, coveredBottomPx = 0.0)!!
        assertTrue(view.bounds.east > 180)
        assertTrue(view.bounds.west < view.bounds.east)
        val grid = MgrsGridScene.gridFor(view)
        assertTrue(grid.lines.any { line -> line.points.any { it.lon > 180 } })
    }

    @Test
    fun `turned, the grid covers the box round the view and draws no values along its edges, which are not the screen's`() {
        val north = CameraState(LatLon(34.62, -84.18), 14.0)
        val turned = north.copy(bearingDegrees = 30.0)
        val flat = MgrsGridScene.viewOf(north, width, height, density, coveredBottomPx = 0.0)!!
        val view = MgrsGridScene.viewOf(turned, width, height, density, coveredBottomPx = 0.0, avoidPx = listOf(GridBox(0.0, 0.0, 100.0, 100.0)))!!
        assertTrue(view.turned)
        assertTrue(view.bounds.east - view.bounds.west > flat.bounds.east - flat.bounds.west)
        assertTrue(view.options.avoid.isEmpty())
        val projection = MapProjection(turned, width, height, density)
        listOf(0.0 to 0.0, width to 0.0, 0.0 to height, width to height).forEach { (x, y) ->
            val corner = projection.toLatLon(x, y)
            assertTrue(corner.lat in view.bounds.south - 1e-9..view.bounds.north + 1e-9)
            assertTrue(corner.lon in view.bounds.west - 1e-9..view.bounds.east + 1e-9)
        }
        assertTrue(MgrsGridScene.gridFor(flat).labels.any { it.edge != null })
        assertTrue(MgrsGridScene.gridFor(view).labels.none { it.edge != null })
    }

    @Test
    fun `turned, the squares and zones are labelled on the screen, clear of what floats over the map`() {
        for (bearing in listOf(30.0, 45.0, -120.0)) {
            for (zoom in listOf(12.0, 7.5, 4.0)) {
                val camera = CameraState(LatLon(34.62, -84.18), zoom, bearingDegrees = bearing)
                val button = GridBox(width - 64 * density, 140 * density, width - 16 * density, 400 * density)
                val view = MgrsGridScene.viewOf(camera, width, height, density, coveredBottomPx = 0.0, avoidPx = listOf(button))!!
                val labels = MgrsGridScene.gridFor(view).labels
                assertTrue("labelled at $bearing, zoom $zoom", labels.isNotEmpty())
                val projection = MapProjection(camera, width, height, density)
                labels.forEach { label ->
                    val at = projection.toScreen(LatLon(label.lat, label.lon))
                    assertTrue("${label.text} at $at, $bearing degrees", at.x in 0.0..width && at.y in 0.0..height)
                    assertFalse("${label.text} under the button", at.x in button.x0..button.x1 && at.y in button.y0..button.y1)
                }
            }
        }
    }

    @Test
    fun `what floats over the map is kept off in points, with the web's margin round it`() {
        val camera = CameraState(LatLon(34.62, -84.18), 14.0)
        val view = MgrsGridScene.viewOf(camera, width, height, density, avoidPx = listOf(GridBox(16 * density, 8 * density, 396 * density, 64 * density)))!!
        assertEquals(listOf(GridBox(12.0, 4.0, 400.0, 68.0)), view.options.avoid.map { GridBox(round(it.x0), round(it.y0), round(it.x1), round(it.y1)) })
        // Covered along the top, the eastings go to the bottom edge instead.
        val labels = MgrsGridScene.gridFor(view).labels
        assertTrue(labels.any { it.edge == "bottom" })
        assertTrue(labels.none { it.edge == "top" })
    }

    private fun round(value: Double) = Math.round(value * 1e6) / 1e6

    @Test
    fun `a map with no size has no grid`() {
        val camera = CameraState(LatLon(34.62, -84.18), 14.0, paddingBottomPx = 900 * density)
        assertNull(MgrsGridScene.viewOf(camera, 0.0, height, density))
        assertNull(MgrsGridScene.viewOf(camera, width, height, density))                  // the sheet covers it all
    }

    @Test
    fun `each line is a feature carrying its kind, in longitude and latitude`() {
        val camera = CameraState(LatLon(34.62, -84.18), 12.0)
        val grid = MgrsGridScene.gridFor(MgrsGridScene.viewOf(camera, width, height, density)!!)
        val features = Json.parseToJsonElement(MgrsGridScene.geoJson(grid)).jsonObject["features"]!!.jsonArray
        assertEquals(grid.lines.size, features.size)
        val first = features.first().jsonObject
        assertEquals(grid.lines.first().kind, first["properties"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val point = first["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.first().jsonArray
        assertEquals(grid.lines.first().points.first().lon, point[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(grid.lines.first().points.first().lat, point[1].jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `a line's value sits just inside its edge, and a zone or square centred on its point`() {
        fun label(edge: String?) = GridLabel(if (edge == null) "square" else "easting", edge, "66", 16, null, 0.0, 0.0, GridBox(0.0, 0.0, 0.0, 0.0))
        val at = ScreenPoint(100.0, 200.0)
        assertEquals(ScreenPoint(80.0, 210.0), MgrsGridScene.pillTopLeft(label("top"), at, 40.0, 20.0, 10.0))
        assertEquals(ScreenPoint(80.0, 170.0), MgrsGridScene.pillTopLeft(label("bottom"), at, 40.0, 20.0, 10.0))
        assertEquals(ScreenPoint(110.0, 190.0), MgrsGridScene.pillTopLeft(label("left"), at, 40.0, 20.0, 10.0))
        assertEquals(ScreenPoint(50.0, 190.0), MgrsGridScene.pillTopLeft(label("right"), at, 40.0, 20.0, 10.0))
        assertEquals(ScreenPoint(80.0, 190.0), MgrsGridScene.pillTopLeft(label(null), at, 40.0, 20.0, 10.0))
    }
}
