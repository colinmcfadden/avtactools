package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.Backstops
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.SetLines
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The part of the backstops, FLOTs and phase lines Compose draws over the map (a backstop's arrow and reading, a FLOT's scallops and label, a phase line's names at
 * each end, the handles of what is held), over a stand-in for imagery, for someone to look at: `-Pezpz.screenshots` writes it to build/screenshots. The dashed
 * line and the phase line itself are MapLibre's, which cannot be drawn here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h500dp-xxhdpi")
class MeasuresScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = LatLon(34.78, -84.08)
    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)

    @Test
    fun `a held backstop, a FLOT and a phase line`() {
        val backstop = JsonObject(mapOf("id" to JsonPrimitive("bs-1"), "points" to SetLines.plain(listOf(at(120.0, -60.0), at(130.0, 0.0), at(120.0, 60.0)))))
        val flot = JsonObject(mapOf("id" to JsonPrimitive("flot-1"), "points" to SetLines.plain(listOf(at(-60.0, -120.0), at(-80.0, 0.0), at(-60.0, 120.0)))))
        val pl = JsonObject(mapOf("id" to JsonPrimitive("pl-1"), "name" to JsonPrimitive("RED"), "points" to SetLines.plain(listOf(at(-150.0, -100.0), at(-140.0, 100.0)))))
        val set = RouteSet(id = "m-1", name = SetLines.MEASURES_NAME, routes = emptyList(), extras = JsonObject(mapOf(SetLines.FLOTS to JsonArray(listOf(flot)), SetLines.PHASE_LINES to JsonArray(listOf(pl)))))
        val scene = MeasuresScene.of(null, listOf(set)).copy(
            target = here,
            backstops = listOf(BackstopDrawn("bs-1", Backstops.points(backstop), Backstops.arrow(backstop, here), Backstops.arrow(backstop, here)?.let { Backstops.reading(it).text }, held = true, movable = true)),
        )
        compose.setContent {
            Box(Modifier.size(411.dp, 500.dp).background(Color(0xFF55703F))) { MeasuresLabels(CameraState(here, 16.0, 0.0), scene) }
        }
        compose.onRoot().captureRoboImage("build/screenshots/measures-map.png")
    }
}
