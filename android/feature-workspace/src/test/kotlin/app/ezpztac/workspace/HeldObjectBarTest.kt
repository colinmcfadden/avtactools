package app.ezpztac.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
import app.ezpztac.planning.PlanDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a tap on the map holds, and the bar over the map that says so: its name, how to turn it, and a way to the rest of what can be done to it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HeldObjectBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = HeldObjectActions(rotateBy = { log += "turn $it" }, options = { log += "options" }, done = { log += "done" })

    private fun show(held: HeldObjectUi) = compose.setContent { EzpzTheme(ThemeMode.Dark) { HeldObjectBar(held, actions) } }

    private fun present(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // -- What is held ----------------------------------------------------------------------------------------------------

    private val inspector = InspectorUi(
        ref = GraphicRef("helicopters", "1"), kind = GraphicKind.HELICOPTER, title = "Helicopter 1", grid = "16S GD 66993 52949", latLon = "34.78, -84.08", rotation = 270.0,
        reachFt = null, direction = null,
    )
    private val threat = HeldThreatUi("t1", "SA-6", "SA-6 Gainful", "16S GD 66000 52000", "34.77, -84.07", "", "", emptyList())

    private fun route(point: PlanPointUi? = null, shaping: ShapingPointUi? = null) = RouteDetailUi(
        routeId = "r1", name = "ROUTE 1", aircraft = "UH-60L", plan = PlanDraft.of(app.ezpztac.model.RoutePlan()), points = listOfNotNull(point), shapingPoints = 0,
        heldShaping = shaping, totals = null, warnings = emptyList(), hasElevations = false,
    )

    private fun point(held: Boolean, name: String = ".LZ") = PlanPointUi(
        id = "p1", name = name, ptType = "target", first = false, values = app.ezpztac.planning.PointDraft.of(app.ezpztac.model.RoutePlan(), "p1"), clock = "--:--:--", hasClock = false,
        facts = "", elapsed = "", held = held, grid = "16S GD 66900 52900",
    )

    @Test
    fun `nothing held, no bar`() {
        assertNull(heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState()))
    }

    @Test
    fun `a held graphic is named, placed and turns`() {
        val held = heldObjectOf(GraphicsUiState(inspector = inspector), ThreatsUiState(), RoutesUiState())!!
        assertEquals(HeldObjectUi("Helicopter 1", "16S GD 66993 52949", 270.0), held)
    }

    @Test
    fun `a graphic that does not turn has no heading`() {
        val sector = inspector.copy(kind = GraphicKind.SECTOR_OF_FIRE, title = "Sector of fire", rotation = null)
        assertNull(heldObjectOf(GraphicsUiState(inspector = sector), ThreatsUiState(), RoutesUiState())!!.heading)
    }

    @Test
    fun `a held threat is named and placed, and does not turn`() {
        assertEquals(HeldObjectUi("SA-6", "16S GD 66000 52000", null), heldObjectOf(GraphicsUiState(), ThreatsUiState(held = threat), RoutesUiState()))
    }

    @Test
    fun `a held point of a route says which route it is on`() {
        val state = RoutesUiState(detail = route(point = point(held = true)))
        assertEquals(HeldObjectUi(".LZ", "ROUTE 1 · 16S GD 66900 52900", null), heldObjectOf(GraphicsUiState(), ThreatsUiState(), state))
        assertNull(heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(detail = route(point = point(held = false)))))      // the route is held, no point of it
    }

    @Test
    fun `a held shaping point, and a point with no name`() {
        val shaping = RoutesUiState(detail = route(shaping = ShapingPointUi("s1", held = true, grid = "16S GD 1 2")))
        assertEquals(HeldObjectUi("Shaping point", "ROUTE 1 · 16S GD 1 2", null), heldObjectOf(GraphicsUiState(), ThreatsUiState(), shaping))
        val unnamed = RoutesUiState(detail = route(point = point(held = true, name = "")))
        assertEquals("Route point", heldObjectOf(GraphicsUiState(), ThreatsUiState(), unnamed)!!.title)
    }

    @Test
    fun `a graphic is held over a threat, and a threat over a point, as a tap takes them`() {
        val both = heldObjectOf(GraphicsUiState(inspector = inspector), ThreatsUiState(held = threat), RoutesUiState(detail = route(point = point(true))))!!
        assertEquals("Helicopter 1", both.title)
        val threatAndPoint = heldObjectOf(GraphicsUiState(), ThreatsUiState(held = threat), RoutesUiState(detail = route(point = point(true))))!!
        assertEquals("SA-6", threatAndPoint.title)
    }

    // -- The bar ---------------------------------------------------------------------------------------------------------

    @Test
    fun `an aircraft's bar turns it by a degree or fifteen, either way, and says how to move it`() {
        show(HeldObjectUi("Helicopter 1", "16S GD 66993 52949", 270.0))
        compose.onNodeWithText("Helicopter 1").assertIsDisplayed()
        compose.onNodeWithText("Heading 270°").assertIsDisplayed()
        assertTrue(present("Press and hold to move it."))
        compose.onNodeWithContentDescription("Turn left 15 degrees").performClick()
        compose.onNodeWithContentDescription("Turn left 1 degrees").performClick()
        compose.onNodeWithContentDescription("Turn right 1 degrees").performClick()
        compose.onNodeWithContentDescription("Turn right 15 degrees").performClick()
        assertEquals(listOf("turn -15.0", "turn -1.0", "turn 1.0", "turn 15.0"), log)
    }

    @Test
    fun `something that does not turn has no turn buttons`() {
        show(HeldObjectUi("SA-6", "16S GD 66000 52000", null))
        assertTrue(compose.onAllNodesWithText("Heading", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescriptionSubstring("Turn").isEmpty())
    }

    @Test
    fun `more options opens the sheet and done puts it down`() {
        show(HeldObjectUi("Helicopter 1", null, 10.0))
        compose.onNodeWithText("More options").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("options", "done"), log)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionSubstring(text: String) =
        onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes()
}
