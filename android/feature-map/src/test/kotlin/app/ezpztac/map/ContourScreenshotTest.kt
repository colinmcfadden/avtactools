package app.ezpztac.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * The slope map's contour labels over its lines, for someone to look at (`-Pezpz.screenshots` writes build/screenshots/slope-contours*.png).
 * MapLibre cannot draw here, so the lines are drawn with a canvas as DiagramOverlay styles them: a halo, then the line, the ring outside faded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h800dp-xxhdpi")
class ContourScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = LatLon(34.689, -84.4917)
    private val camera = CameraState(here, 17.0)

    /** A hill: rings round [here] every 15 m, 10 ft apart, the outer two in the ring outside the LZ, every fifth major. */
    private val hill: SlopeImage = run {
        val metresPerDegLat = 111_320.0
        val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(here.lat))
        val pieces = (1..7).map { i ->
            val radius = 15.0 * i
            val points = (0..48).map { k ->
                val angle = 2 * Math.PI * k / 48
                LatLon(here.lat + radius * cos(angle) / metresPerDegLat, here.lon + radius * sin(angle) / metresPerDegLon)
            }
            val elevation = 1440 - 10 * i
            // Labels alternate sides, as the server spreads them 40 m apart.
            val label = if (i <= 5) points[if (i % 2 == 0) 12 else 36] else null
            ContourPiece(elevation, major = elevation % 50 == 0, inside = i <= 5, points = points, label = label)
        }
        SlopeImage(34.6, -84.6, 34.8, -84.4, "data:image/png;base64,AA==", contours = pieces)
    }

    private fun shot(name: String, mode: ThemeMode) {
        compose.setContent {
            EzpzTheme(mode) {
                Box(Modifier.fillMaxSize().background(Color(0xFF4F6B3A))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density.toDouble())
                        for (part in listOf("halo", "line")) for (piece in hill.contours) {
                            val alpha = if (piece.inside) 1f else 0.4f
                            val width = when (part) {
                                "halo" -> if (piece.major) 4.5f else 3f
                                else -> if (piece.major) 2f else 1f
                            } * density
                            val color = if (part == "halo") Color.White.copy(alpha = 0.55f * alpha) else Color(0xFF121212).copy(alpha = 0.9f * alpha)
                            piece.points.zipWithNext().forEach { (a, b) ->
                                val p = view.toScreen(a)
                                val q = view.toScreen(b)
                                drawLine(color, Offset(p.x.toFloat(), p.y.toFloat()), Offset(q.x.toFloat(), q.y.toFloat()), width)
                            }
                        }
                    }
                    ContourLabels({ camera }, hill)
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun contours() {
        shot("slope-contours", ThemeMode.Dark)
        // The labels are a picture: a screen reader is told nothing of them (the slope tile says what matters in words).
        assertEquals(0, compose.onAllNodesWithText("1430").fetchSemanticsNodes().size)
        // Each pill is centred on its label's place (a local named `size` once moved every pill by half the screen).
        val root = compose.onRoot().fetchSemanticsNode().size
        val view = MapProjection(camera, root.width.toDouble(), root.height.toDouble(), compose.density.density.toDouble())
        hill.contours.filter { it.label != null }.forEach { piece ->
            val bounds = compose.onAllNodesWithText(piece.elevationFt.toString(), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
            val at = view.toScreen(piece.label!!)
            assertEquals("${piece.elevationFt} across", at.x, bounds.center.x.toDouble(), 3.0)
            assertEquals("${piece.elevationFt} down", at.y, bounds.center.y.toDouble(), 3.0)
        }
    }

    @Test
    @Config(fontScale = 2.0f)
    fun contoursAtLargeTextNight() = shot("slope-contours-large-night", ThemeMode.Night)
}
