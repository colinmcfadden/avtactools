package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.sync.SyncStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The routes tab drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2000dp-xxhdpi")
class RoutesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.padding(vertical = 12.dp)) { Column(Modifier.padding(horizontal = 12.dp)) { content() } } } }
        }
        compose.onRoot().captureRoboImage("build/screenshots/routes-$name.png")
    }

    private val one = RouteRowUi("r1", "ROUTE 1", "#FF453A", visible = true, pointCount = 5, routePointCount = 4, summary = "12.3 nm · 8:15", selected = false)
    private val two = RouteRowUi("r2", "INGRESS", "#0A84FF", visible = false, pointCount = 2, routePointCount = 2, summary = "3.1 nm · 1:52", selected = true)
    private val three = RouteRowUi("r3", "EGRESS", "#32D74B", visible = true, pointCount = 7, routePointCount = 3, summary = null, selected = false)
    private val night = RouteSetRow("s2", "NIGHT RUN", 3, SyncStatus.SYNCED, null, isOpen = false)

    private fun open(vararg routes: RouteRowUi, conflictOf: String? = null) = OpenSetUi("s1", "MISSION 1", routes.toList(), canUndo = true, canRedo = false, SyncStatus.PENDING, conflictOf)

    @Test fun empty() = shot("empty") { RoutesContent(RoutesUiState(), RoutesActions()) }

    @Test fun openSet() = shot("open") {
        RoutesContent(RoutesUiState(sets = listOf(RouteSetRow("s1", "MISSION 1", 3, SyncStatus.PENDING, null, true), night), open = open(one, two, three)), RoutesActions())
    }

    @Test fun drawing() = shot("drawing", ThemeMode.Light) {
        RoutesContent(RoutesUiState(open = open(one), drawing = RouteDrawingUi(3, true)), RoutesActions())
    }

    @Test fun conflict() = shot("conflict", ThemeMode.Night) {
        RoutesContent(RoutesUiState(sets = listOf(night.copy(sync = SyncStatus.CONFLICT, conflictOf = "s9")), open = null), RoutesActions())
    }

    @Test fun toolbar() = shot("toolbar") { RouteToolbar(RouteDrawingUi(2, true), null, RoutesActions()) }

    private fun values() = PointDraft("50", "agl", "100", "ground", "0", "0")

    private fun point(id: String, name: String, type: String?, first: Boolean = false, held: Boolean = false, hasClock: Boolean = false, clock: String = "--:--:--", facts: String = "3.1 nm · 045°T · 98 kt · 1320' MSL", elapsed: String = "1:52") =
        PlanPointUi(id, name, type, first, values(), clock, hasClock, if (first) "START" else facts, if (first) "0:00" else elapsed, held)

    private val log = listOf(
        point("p1", ".TGT", "target", first = true, clock = "12:21:40"),
        point("p2", ".SP", "ip", clock = "12:24:10", elapsed = "2:30"),
        point("p3", ".RP", "ip", held = true, clock = "12:27:55", facts = "4.4 nm · 078°T · 101 kt · 1480' MSL", elapsed = "6:15"),
        point("p4", ".TGT", "target", hasClock = true, clock = "12:30:00", facts = "2.2 nm · 112°T · 99 kt · 1390' MSL", elapsed = "8:20"),
    )

    private fun detail(warnings: List<String> = emptyList()) = RouteDetailUi(
        "r1", "ROUTE 1", "UH-60L Black Hawk", PlanDraft(date = "2026-10-03", airspeedType = "indicated", altitudeRef = "msl", windDir = "270", windSpeed = "15"), log,
        shapingPoints = 2, heldShaping = null, totals = "Total 12.3 nm · 8:20 · 640 lb", warnings = warnings, hasElevations = true,
    )

    @Test fun plan() = shot("plan") { RoutesContent(RoutesUiState(open = open(one), detail = detail(listOf("Route needs at least two route points."))), RoutesActions()) }

    @Test fun planLight() = shot("plan-light", ThemeMode.Light) { RoutesContent(RoutesUiState(open = open(one), detail = detail()), RoutesActions()) }
}
