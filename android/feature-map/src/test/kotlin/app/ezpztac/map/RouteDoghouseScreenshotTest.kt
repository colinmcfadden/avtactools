package app.ezpztac.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteDoghouses
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A route's leg doghouses beside its lines (drawn here with a canvas, as MapLibre would), for someone to look at (`-Pezpz.screenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RouteDoghouseScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = LatLon(34.6, -84.4)
    private fun named(id: String, lat: Double, lon: Double, name: String) = RoutePoint(id = id, lat = lat, lon = lon, kind = "amps", ptType = "turn", name = name)

    private val route = SketchRoute(
        id = "r1", name = "ROUTE 1", color = "#ef4444",
        points = listOf(
            named("SP", 34.585, -84.42, ".SP"),
            named("CP1", 34.6, -84.405, ".CP1"),
            RoutePoint(id = "s1", lat = 34.612, lon = -84.402, kind = "shaping"),
            named("RP", 34.615, -84.39, ".RP"),
            named("TGT", 34.6, -84.38, ".TGT"),
        ),
    )

    private fun shot(name: String, zoom: Double, bearing: Double = 0.0) {
        val camera = CameraState(here, zoom, bearingDegrees = bearing)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF55703F))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density.toDouble())
                        route.points.zipWithNext().forEach { (a, b) ->
                            val p = view.toScreen(LatLon(a.lat, a.lon))
                            val q = view.toScreen(LatLon(b.lat, b.lon))
                            drawLine(Color(0xFFEF4444), Offset(p.x.toFloat(), p.y.toFloat()), Offset(q.x.toFloat(), q.y.toFloat()), strokeWidth = 4 * density)
                        }
                    }
                    LegDoghouses({ camera }, RouteDoghouses.of(listOf(route)))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/route-doghouses-$name.png")
    }

    @Test fun close() = shot("close", zoom = 14.0)
    @Test fun far() = shot("far", zoom = 11.5)
    @Test fun turned() = shot("turned", zoom = 14.0, bearing = 60.0)
}
