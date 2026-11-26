package app.ezpztac.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteDoghouses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A doghouse on each route leg, where it goes on the screen, and drawn over the map without taking its touches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RouteDoghouseLabelsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = LatLon(34.6, -84.4)

    /** A route east from [here], 2 km long, between two named points. */
    private val east = SketchRoute(
        id = "r1", name = "ROUTE 1", color = "#ff0000",
        points = listOf(
            RoutePoint(id = "A", lat = here.lat, lon = here.lon - 0.011, kind = "amps", ptType = "turn", name = ".A"),
            RoutePoint(id = "B", lat = here.lat, lon = here.lon + 0.011, kind = "amps", ptType = "turn", name = ".CP1"),
        ),
    )

    private fun view(zoom: Double, bearing: Double = 0.0, density: Double = 1.0) =
        CameraState(here, zoom, bearingDegrees = bearing).let { it to MapProjection(it, 1000.0, 2000.0, density) }

    @Test
    fun `a leg's doghouse stands on the right of the way flown, turned to its course, at its size`() {
        val doghouse = RouteDoghouses.of(listOf(east)).single()
        val (camera, view) = view(zoom = 15.0)
        val placed = RouteDoghouseLabels.placed(listOf(doghouse), view, camera).single()
        val point = view.toScreen(LatLon(doghouse.lat, doghouse.lon))
        assertEquals(90.0, placed.rotationDeg, 0.1)                                          // flown east
        assertEquals(1.0, placed.scale, 0.0)                                                 // 2 km is long on the screen at zoom 15
        assertEquals(point.x, placed.middle.x, 0.5)                                         // to the right of a leg flown east is south: down the screen
        assertEquals(point.y + 44.0, placed.middle.y, 0.5)
    }

    @Test
    fun `on a map turned to the east it stands to the right of the screen, and on a dense screen as far in dp`() {
        val doghouse = RouteDoghouses.of(listOf(east)).single()
        val (camera, view) = view(zoom = 15.0, bearing = 90.0, density = 3.0)
        val placed = RouteDoghouseLabels.placed(listOf(doghouse), view, camera).single()
        val point = view.toScreen(LatLon(doghouse.lat, doghouse.lon))
        assertEquals(0.0, placed.rotationDeg, 0.1)                                           // east is up: the box upright
        assertEquals(point.x + 44.0 * 3, placed.middle.x, 0.5)
        assertEquals(point.y, placed.middle.y, 0.5)
    }

    @Test
    fun `zoomed out it is drawn smaller, its offset with it, down to its floor, and far off the screen it is not drawn`() {
        val doghouse = RouteDoghouses.of(listOf(east)).single()
        val (camera, view) = view(zoom = 9.0)
        val placed = RouteDoghouseLabels.placed(listOf(doghouse), view, camera).single()
        assertEquals(RouteDoghouses.MIN_SCALE, placed.scale, 0.0)
        assertEquals(view.toScreen(LatLon(doghouse.lat, doghouse.lon)).y + 44.0 * RouteDoghouses.MIN_SCALE, placed.middle.y, 0.5)
        val (farCamera, farView) = CameraState(LatLon(here.lat + 0.2, here.lon), 15.0).let { it to MapProjection(it, 1000.0, 2000.0, 1.0) }
        assertTrue(RouteDoghouseLabels.placed(listOf(doghouse), farView, farCamera).isEmpty())
    }

    @Test
    fun `it says what the leg's plan says, and a tap on it is the map's`() {
        var taps = 0
        val doghouses = RouteDoghouses.of(listOf(east))
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().clickable { taps++ })                                  // stands in for the map, under the boxes
                LegDoghouses({ CameraState(here, 15.0) }, doghouses)
            }
        }
        // Hidden from a screen reader (the route's plan says the same in words), so looked for in the unmerged tree.
        compose.onNodeWithText("CP1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("090°", useUnmergedTree = true).assertExists()
        compose.onRoot().performTouchInput { click(center.copy(y = center.y + 44 * 3f)) }  // on the box, 44 dp under the leg's middle
        assertEquals(1, taps)
    }
}
