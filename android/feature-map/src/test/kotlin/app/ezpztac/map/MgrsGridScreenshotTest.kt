package app.ezpztac.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.geo.GridResult
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The grid's labels over a stand-in for the map. MapLibre draws the lines on a GPU, which a test does not have, so the same lines are drawn
 * here with a canvas from the same projection: what is looked at is whether each value sits where its line meets the edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h800dp-xxhdpi")
class MgrsGridScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, camera: CameraState, chrome: MapChromeBoxes? = null) {
        var grid = GridResult.EMPTY
        var handed = GridResult.EMPTY
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A5A40))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val view = MgrsGridScene.viewOf(camera, size.width.toDouble(), size.height.toDouble(), density.toDouble()) ?: return@Canvas
                        grid = MgrsGridScene.gridFor(view)
                        val projection = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density.toDouble())
                        grid.lines.forEach { line ->
                            val width = when (line.kind) { "zone" -> 2.5f; "major" -> 1.75f; else -> 1f } * density
                            val dash = if (line.kind == "zone") PathEffect.dashPathEffect(floatArrayOf(12f * density, 6f * density)) else null
                            line.points.zipWithNext().forEach { (a, b) ->
                                val p = projection.toScreen(LatLon(a.lat, a.lon))
                                val q = projection.toScreen(LatLon(b.lat, b.lon))
                                drawLine(Color.White.copy(alpha = 0.9f), Offset(p.x.toFloat(), p.y.toFloat()), Offset(q.x.toFloat(), q.y.toFloat()), width, pathEffect = dash)
                            }
                        }
                    }
                    MgrsGridLabels({ camera }, shown = true, chrome = chrome, onGrid = { g, _ -> handed = g })
                }
            }
        }
        // The grid is worked out once the camera has been still a moment, off the main thread.
        compose.mainClock.advanceTimeBy(1_000)
        Thread.sleep(1_000)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
        // Worked out once the camera settled, and handed to the map (whose lines the canvas above stands in for, when a picture is taken).
        assertTrue("the map is handed the grid", handed.lines.isNotEmpty())
        if (grid.lines.isNotEmpty()) assertTrue("the same lines: ${handed.lines.size} for ${grid.lines.size}", handed.lines.size == grid.lines.size)
    }

    @Test
    fun oneKilometre() = shot("mgrs-grid-1km", CameraState(LatLon(34.62, -84.18), 13.0))

    @Test
    fun hundredMetres() = shot("mgrs-grid-100m", CameraState(LatLon(34.62, -84.18), 16.5))

    @Test
    fun zones() = shot("mgrs-grid-zones", CameraState(LatLon(36.0, -84.0), 4.0))

    @Test
    fun squares() = shot("mgrs-grid-squares", CameraState(LatLon(34.62, -84.18), 7.5))
}
