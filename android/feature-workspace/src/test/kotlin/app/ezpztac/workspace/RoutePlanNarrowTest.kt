package app.ezpztac.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

/** The route's plan on a 360 dp phone, the narrowest common width: the design's rows (drawn at 412) are stacked so nothing is cut off. `-Pezpz.screenshots` draws it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class RoutePlanNarrowTest {
    @get:Rule
    val compose = createComposeRule()

    private val values = PointDraft("50", "agl", "100", "ground", "0", "0")

    private fun point(id: String, name: String, first: Boolean = false, held: Boolean = false) =
        PlanPointUi(id, name, "ip", first, values, "--:--:--", false, if (first) "START" else "2.8 nm · 251°T · 100 kt", "2:10", held, "16S GC 28864 55349")

    private fun show() {
        val route = RouteRowUi("r1", "ROUTE 1", "#E5533D", visible = true, pointCount = 2, routePointCount = 2, summary = "4.2 nm · 2:30 · 40 lb", selected = true)
        val state = RoutesUiState(
            open = OpenSetUi("s1", "SKETCHED ROUTES", listOf(route), false, false, SyncStatus.PENDING, null, madeUpName = true, chips = routeSetChips(SyncStatus.PENDING, null, false)),
            detail = RouteDetailUi(
                "r1", "ROUTE 1", "UH-60L Black Hawk", PlanDraft(date = "2026-10-12"), listOf(point("p1", ".SP", first = true), point("p2", ".RP", held = true)),
                0, null, "4.2 nm · 2:30 · 40 lb", emptyList(), hasElevations = false, color = "#E5533D",
            ),
            planOpen = true,
        )
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutePlanPage(state, RoutesActions(), "16S GC 28936 55287", 0) } }
    }

    @Test
    fun `the references keep room for their words, and the wind has a row of its own`() {
        show()
        // Each part of the speed reference at least as wide as its tick and its words.
        compose.onNodeWithContentDescription("TAS, true airspeed").assertWidthIsAtLeast(40.dp)
        compose.onNodeWithContentDescription("AGL, above ground level").assertWidthIsAtLeast(56.dp)
        compose.onNodeWithText("Wind kt").assertIsDisplayed()
    }

    @Test
    fun `the date has the page's width, so the whole of it shows`() {
        show()
        compose.onNodeWithText("10/12/2026").assertWidthIsAtLeast(200.dp)
    }

    @Test
    fun picture() {
        show()
        compose.onRoot().captureRoboImage("build/screenshots/routes-plan-360.png")
    }
}
