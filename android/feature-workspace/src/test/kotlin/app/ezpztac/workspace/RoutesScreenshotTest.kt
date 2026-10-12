package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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

/** The Routes panel, its foot, the route's plan and the naming sheet, beside the design's AP-Routes, AP-RoutePlan and AP-SaveRoutes. `-Pezpz.screenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class RoutesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, scroll: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(if (scroll) Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp) else Modifier) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/routes-$name.png")
    }

    private val one = RouteRowUi("r1", "ROUTE 1", "#E5533D", visible = true, pointCount = 4, routePointCount = 4, summary = "4.2 nm · 2:30 · 40 lb", selected = false)
    private val egress = RouteRowUi("r2", "EGRESS", "#0A84FF", visible = false, pointCount = 3, routePointCount = 1, summary = null, selected = false)

    private fun open(vararg routes: RouteRowUi, name: String = "SKETCHED ROUTES", madeUp: Boolean = true, sync: SyncStatus = SyncStatus.PENDING, mission: Boolean = false) = OpenSetUi(
        "s1", name, routes.toList(), canUndo = true, canRedo = false, sync, null, isMission = mission, madeUpName = madeUp, chips = routeSetChips(sync, null, mission),
        saveContents = listOf("1 route, 4 points, 4.2 nm", "Speeds, altitudes, winds, fuel flow and the start time"),
    )

    private val goat = RouteSetRow("s2", "GOAT SUCKER", 2, SyncStatus.SYNCED, null, false, isMission = true, chips = routeSetChips(SyncStatus.SYNCED, null, true))

    private fun homeState() = RoutesUiState(open = open(one), alsoOpen = listOf(goat), openCount = 2, sets = listOf(goat, RouteSetRow("s1", "SKETCHED ROUTES", 1, SyncStatus.PENDING, null, true)))

    /** The panel with its foot pinned under it, as the sheet draws them. */
    @Composable
    private fun PanelWithFoot(state: RoutesUiState, threats: Int = 1) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) { RoutesHomeContent(state, RoutesActions(), threatCount = threats) }
            RoutesFooter(state, RoutesActions())
        }
    }

    @Test fun home() = shot("home", scroll = false) { PanelWithFoot(homeState()) }
    @Test fun homeNight() = shot("home-night", ThemeMode.Night, scroll = false) { PanelWithFoot(homeState()) }
    @Test fun mission() = shot("mission", scroll = false) { PanelWithFoot(RoutesUiState(open = open(one, egress, name = "GOAT SUCKER", madeUp = false, sync = SyncStatus.SYNCED, mission = true))) }
    @Test fun empty() = shot("empty", scroll = false) { PanelWithFoot(RoutesUiState(), threats = 0) }
    @Test fun drawing() = shot("drawing", scroll = false) { PanelWithFoot(RoutesUiState(open = open(one), drawing = RouteDrawingUi(1, false))) }
    @Test fun library() = shot("library") { RoutesLibraryContent(RoutesUiState(sets = listOf(goat, RouteSetRow("s9", "SKETCHED ROUTES (from this device)", 1, SyncStatus.CONFLICT, "s1", false, pointCount = 4, chips = routeSetChips(SyncStatus.CONFLICT, "s1", false)))), RoutesActions()) }
    @Test fun save() = shot("save", scroll = false) { SaveRoutesContent(open(one), listOf("NIGHT INFIL"), onSave = {}, onCancel = {}) }
    @Test fun saveNight() = shot("save-night", ThemeMode.Night, scroll = false) { SaveRoutesContent(open(one), emptyList(), onSave = {}, onCancel = {}) }

    private val values = PointDraft("50", "agl", "100", "ground", "0", "0")

    private fun point(id: String, name: String, type: String, first: Boolean = false, held: Boolean = false, facts: String, elapsed: String) =
        PlanPointUi(id, name, type, first, values, "--:--:--", false, facts, elapsed, held, "16S GC 28864 55349")

    private fun plan(held: String = "p2") = RoutesUiState(
        open = open(one.copy(selected = true)), planOpen = true,
        detail = RouteDetailUi(
            "r1", "ROUTE 1", "UH-60L Black Hawk", PlanDraft(), listOf(
                point("p1", ".TGT", "target", first = true, held = held == "p1", facts = "START", elapsed = "0:00"),
                point("p2", ".SP", "ip", held = held == "p2", facts = "0.8 nm · 096°T · 100 kt", elapsed = "0:30"),
                point("p3", ".RP", "ip", held = held == "p3", facts = "2.8 nm · 251°T · 100 kt", elapsed = "2:10"),
                point("p4", ".TGT", "target", held = held == "p4", facts = "0.6 nm · 327°T · 100 kt", elapsed = "2:30"),
            ),
            0, null, "4.2 nm · 2:30 · 40 lb", emptyList(), hasElevations = false, color = "#E5533D",
        ),
    )

    @Test fun routePlan() = shot("plan", scroll = false) { RoutePlanPage(plan(), RoutesActions(), "16S GC 28936 55287", 1) }
    @Test fun routePlanNight() = shot("plan-night", ThemeMode.Night, scroll = false) { RoutePlanPage(plan(), RoutesActions(), "16S GC 28936 55287", 1) }
    @Test fun routePlanFirst() = shot("plan-first", scroll = false) { RoutePlanPage(plan(held = "p1"), RoutesActions(), "16S GC 28936 55287", 1) }

    @Test
    fun toolbar() = shot("toolbar", scroll = false) {
        Box(Modifier.padding(top = 200.dp)) { RouteToolbar(RouteDrawingUi(3, true), null, RoutesActions()) }
    }
}
