package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The planning-graphics panel drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
class GraphicsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: GraphicsUiState, mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { GraphicsContent(state, "16S GD 66993 52949", GraphicsActions()) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/graphics-$name.png")
    }

    private val heli = GraphicRef("helicopters", "1")
    private val heli2 = GraphicRef("helicopters", "2")
    private val pz = GraphicRef("pzMarkers", "pz-3")
    private val sector = GraphicRef("sectorsOfFire", "sec-4")
    private val goAround = GraphicRef("goArounds", "ga-5")

    private fun row(ref: GraphicRef, kind: GraphicKind, title: String, grid: String, detail: String?, selected: Boolean = false, warning: Boolean = false) =
        GraphicRowUi(ref, kind, title, grid, detail, warning, selected)

    private val rows = listOf(
        row(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "16S GD 66993 52949", "heading 90°"),
        row(heli2, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "16S GD 67021 52949", "heading 90°"),
        row(pz, GraphicKind.PZ_MARKER, "PZ marker", "16S GD 66940 52900", "reach 328 ft"),
        row(sector, GraphicKind.SECTOR_OF_FIRE, "Sector of fire", "16S GD 66990 52990", null),
        row(goAround, GraphicKind.GO_AROUND, "Go-around", "16S GD 66993 52949", "left · heading 0°"),
    )

    private fun state(rows: List<GraphicRowUi> = this.rows, inspector: InspectorUi? = null, alerts: List<String> = emptyList(), error: String? = null) =
        GraphicsUiState(canEdit = true, rows = rows, inspector = inspector, alerts = alerts, undoDepth = 3, redoDepth = 1, error = error)

    private fun held(ref: GraphicRef, kind: GraphicKind, title: String, grid: String, rotation: Double? = null, reachFt: Long? = null, direction: String? = null) =
        InspectorUi(ref, kind, title, grid, "34.78382, -84.08219", rotation, reachFt, direction)

    @Test fun notAnalysed() = shot("not-analysed", GraphicsUiState())
    @Test fun empty() = shot("empty", state(rows = emptyList()))
    @Test fun list() = shot("list", state())

    @Test fun tooClose() = shot(
        "too-close",
        state(
            rows = listOf(rows[0].copy(warning = true), rows[1].copy(warning = true)) + rows.drop(2),
            alerts = listOf("Separation Alert (Chalk 1–Chalk 2): Rotor edges are only 12 ft apart (Min: 100 ft / 30 m)."),
        ),
    )

    @Test fun aircraftHeld() = shot(
        "aircraft",
        state(
            rows = listOf(rows[0].copy(selected = true)) + rows.drop(1),
            inspector = held(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "16S GD 66993 52949", rotation = 90.0),
        ),
    )

    @Test fun pzHeld() = shot(
        "pz",
        state(
            rows = rows.map { it.copy(selected = it.ref == pz) },
            inspector = held(pz, GraphicKind.PZ_MARKER, "PZ marker", "16S GD 66940 52900", rotation = 270.0, reachFt = 328),
        ),
    )

    @Test fun goAroundHeldNight() = shot(
        "go-around-night",
        state(
            rows = rows.map { it.copy(selected = it.ref == goAround) },
            inspector = held(goAround, GraphicKind.GO_AROUND, "Go-around", "16S GD 66993 52949", rotation = 0.0, direction = "left"),
        ),
        mode = ThemeMode.Night,
    )

    @Test fun sectorHeldLight() = shot(
        "sector-light",
        state(rows = rows.map { it.copy(selected = it.ref == sector) }, inspector = held(sector, GraphicKind.SECTOR_OF_FIRE, "Sector of fire", "16S GD 66990 52990")),
        mode = ThemeMode.Light,
    )

    @Test fun error() = shot("error", state(error = "Move the map to where it should go first."))
}
