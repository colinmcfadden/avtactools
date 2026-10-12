package app.ezpztac.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.model.LocalPointMatch
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The route's plan as a person meets it (docs/native-design: RoutePlan): the bar, the set, the whole route, a card for each point, and the total. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutePlanPageTest {
    @get:Rule
    val compose = createComposeRule()

    private class Applied(val typed: Any, val before: Any, val first: Boolean?, val key: Any?)

    private class Recorder {
        val log = mutableListOf<String>()
        val plans = mutableListOf<Applied>()
        val points = mutableListOf<Applied>()
        var problem: String? = null
        var textProblem: String? = null
        var elevation: Double? = 1730.0
        val actions = RoutesActions(
            save = { log += "save" }, closePlan = { log += "back" }, renameRoute = { id, name -> log += "renameRoute:$id:$name" }, deleteRoute = { log += "deleteRoute:$it" },
            undo = { log += "undo" }, redo = { log += "redo" }, dismissError = { log += "dismiss" },
            applyPlan = { route, typed, before, key -> plans += Applied(typed, before, null, key); log += "applyPlan:$route"; problem },
            applyPoint = { route, point, typed, before, first, key -> points += Applied(typed, before, first, key); log += "applyPoint:$route:$point"; problem },
            selectPoint = { log += "selectPoint:$it" }, renamePoint = { r, p, n -> log += "renamePoint:$r:$p:$n" }, setPointType = { r, p, t -> log += "type:$r:$p:$t" },
            makeShaping = { r, p -> log += "shaping:$r:$p" }, makeNamed = { r, p -> log += "named:$r:$p" },
            nudgePoint = { r, p, n, e -> log += "nudge:$r:$p:${n.toLong()}:${e.toLong()}" }, pointToCrosshair = { r, p -> log += "crosshair:$r:$p" },
            pointToText = { r, p, t -> log += "pointTo:$r:$p:$t"; textProblem }, addShapingPoint = { log += "addShaping:$it" },
            fetchWinds = { log += "winds" }, fetchElevations = { log += "elevations" }, dismissNote = { log += "dismissNote" },
            exportRoute = { id, threats -> log += "exportRoute:$id:$threats" }, shareRoute = { id, format -> log += "share:$id:${format.extension}" },
            localPointNamed = { typed -> if (typed.startsWith("BLUE")) LocalPointMatch(typed.uppercase(), LatLon(34.9, -84.9), elevation) else null },
        )
    }

    private val values = PointDraft("50", "agl", "100", "ground", "0", "0")

    private fun point(id: String, name: String, type: String?, first: Boolean = false, held: Boolean = false, hasClock: Boolean = false, clock: String = "--:--:--") =
        PlanPointUi(
            id, name, type, first, values, clock, hasClock, if (first) "START" else "2.8 nm · 251°T · 100 kt", if (first) "0:00" else "2:10", held, "16S GC 28864 55349",
        )

    private val route = RouteRowUi("r1", "ROUTE 1", "#E5533D", visible = true, pointCount = 4, routePointCount = 4, summary = "4.2 nm · 2:30 · 40 lb", selected = true)

    private fun detail(
        vararg points: PlanPointUi, mission: Boolean = false, shaping: Int = 0, heldShaping: ShapingPointUi? = null, warnings: List<String> = emptyList(),
        elevations: Boolean = false,
    ) = RouteDetailUi(
        "r1", "ROUTE 1", "UH-60L Black Hawk", PlanDraft(date = "2026-10-12"), points.toList(), shaping, heldShaping, "4.2 nm · 2:30 · 40 lb", warnings,
        hasElevations = elevations, isMission = mission, color = "#E5533D",
    )

    private fun open(madeUp: Boolean = true, mission: Boolean = false, canUndo: Boolean = false) = OpenSetUi(
        "s1", if (madeUp) "SKETCHED ROUTES" else "NIGHT INFIL", listOf(route), canUndo, canRedo = false, SyncStatus.PENDING, null, isMission = mission,
        madeUpName = madeUp, chips = routeSetChips(SyncStatus.PENDING, null, mission),
    )

    private val log4 = arrayOf(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip", held = true), point("p3", ".RP", "ip"), point("p4", ".TGT", "target", hasClock = true, clock = "12:30:00"))

    private fun page(detail: RouteDetailUi, open: OpenSetUi = open(), configure: RoutesUiState.() -> RoutesUiState = { this }, crosshairGrid: String? = "16S GC 28936 55287", threats: Int = 0, recorder: Recorder.() -> Unit = {}): Recorder {
        val r = Recorder().apply(recorder)
        val state = RoutesUiState(open = open, detail = detail, planOpen = true).configure()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutePlanPage(state, r.actions, crosshairGrid, threats) } }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    // -- The bars ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the bar names the route with its totals, and Back goes back to the panel`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("ROUTE 1").assertIsDisplayed()
        compose.onAllNodesWithText("4.2 nm · 2:30 · 40 lb")[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Routes").performClick()
        assertEquals(listOf("back"), r.log)
    }

    @Test
    fun `the route is shared as a GPX or a flight plan from the bar`() {
        val r = page(detail(*log4))
        compose.onNodeWithContentDescription("Share ROUTE 1 as GPX or FPL").performClick()
        compose.onNodeWithText("Garmin FPL").performClick()
        assertEquals(listOf("share:r1:fpl"), r.log)
    }

    @Test
    fun `the bar's menu undoes, exports this route with the threats as chosen, renames and deletes`() {
        val r = page(detail(*log4), open = open(canUndo = true), configure = { copy(includeThreats = true) }, threats = 2)
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Redo").assertIsNotEnabled()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Export this route as .msnx").performClick()
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Route name").performTextReplacement("ingress")
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("undo", "exportRoute:r1:true", "renameRoute:r1:ingress"), r.log)
    }

    @Test
    fun `deleting the route is asked about first, and takes the page with it`() {
        val r = page(detail(*log4))
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"ROUTE 1\"? You can undo this until the set is closed.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("back", "deleteRoute:r1"), r.log)
    }

    @Test
    fun `a mission's route is not renamed or deleted here`() {
        page(detail(*log4, mission = true), open = open(madeUp = false, mission = true))
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        assertEquals(0, count("Rename"))
        assertEquals(0, count("Delete"))
    }

    @Test
    fun `the set strip names the set and its state, and Save names a set still called by its made-up name`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("SKETCHED ROUTES").assertIsDisplayed()
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
        compose.onNodeWithText("Personal · saves to your Library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save SKETCHED ROUTES").performClick()
        assertEquals(listOf("save"), r.log)
    }

    @Test
    fun `a named set says it is saved`() {
        page(detail(*log4), open = open(madeUp = false))
        compose.onNodeWithText("NIGHT INFIL").assertIsDisplayed()
        compose.onNodeWithText("Saved").assertIsNotEnabled()
    }

    // -- The whole route --------------------------------------------------------------------------------------------------------------

    @Test
    fun `the whole route has the date, temperature and fuel flow, and fetches winds`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Whole route").assertIsDisplayed()
        compose.onNodeWithText("10/12/2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose the date from a calendar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fetch winds aloft for ROUTE 1").performClick()
        assertEquals(listOf("winds"), r.log)
    }

    @Test
    fun `while winds are fetched the button says so and takes no taps`() {
        page(detail(*log4), configure = { copy(fetching = PlanningKind.WINDS) })
        compose.onNodeWithContentDescription("Fetch winds aloft for ROUTE 1").assertIsNotEnabled()
        compose.onNodeWithText("Fetching winds…").assertIsDisplayed()
    }

    @Test
    fun `a temperature typed is applied as it is typed, alone, under one key for the field`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Temp °C").performTextReplacement("-4")
        val applied = r.plans.last()
        assertEquals(PlanDraft(date = "2026-10-12").copy(tempC = "-4"), applied.typed)
        assertEquals(PlanDraft(date = "2026-10-12"), applied.before)
        assertTrue(applied.key.toString(), applied.key.toString().startsWith("temp:r1#"))
    }

    @Test
    fun `what the planner refuses is said at the field`() {
        page(detail(*log4)) { problem = "Fuel flow must be between 0 and 20000." }
        compose.onNodeWithText("Fuel lb/hr").performTextReplacement("99999")
        compose.onNodeWithText("Fuel flow must be between 0 and 20000.").assertIsDisplayed()
    }

    @Test
    fun `a date is applied once it is a whole date, as the plan keeps it`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("10/12/2026").performTextReplacement("10/1")
        compose.waitForIdle()                                                                   // what is typed is read a frame later
        assertEquals(emptyList<Applied>(), r.plans)                                              // not yet a date
        compose.onNodeWithText("10/1").performTextReplacement("10/13/2026")
        compose.waitForIdle()
        assertEquals("2026-10-13", (r.plans.single().typed as PlanDraft).date)
    }

    @Test
    fun `what a wind fetch found is shown under the whole route, and can be dismissed`() {
        val r = page(detail(*log4), configure = { copy(note = PlanningNote("Winds for 4 points · 4 METAR", failed = false, PlanningKind.WINDS)) })
        compose.onNodeWithText("Winds for 4 points · 4 METAR").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismissNote"), r.log)
    }

    // -- The points ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a shut card says what the point is, its clock, its leg and what is flown to it, and a tap opens it`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("4 points").assertIsDisplayed()
        compose.onNodeWithText("Start alt 50 AGL").assertIsDisplayed()
        compose.onNodeWithText("START").assertIsDisplayed()
        compose.onNodeWithText(".RP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12:30:00 TOT").performScrollTo().assertIsDisplayed()           // the time on target, said in words too
        compose.onAllNodesWithText("50 AGL · 100 GS · Wind 0°T 0 kt")[0].assertIsDisplayed()
        compose.onNodeWithText(".RP").performClick()
        assertEquals(listOf("selectPoint:p3"), r.log)
    }

    @Test
    fun `the open card has the name, the clock, and the altitude, speed and wind flown to it`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Point name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Set clock time at .SP, not set").assertIsDisplayed()
        compose.onNodeWithText("Alt to").assertIsDisplayed()
        compose.onNodeWithText("Speed to").assertIsDisplayed()
        compose.onNodeWithText("Wind °T").assertIsDisplayed()
        compose.onNodeWithText("Wind kt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse .SP").performClick()
        assertEquals(listOf("selectPoint:p2"), r.log)
    }

    @Test
    fun `the first point has only its start altitude`() {
        page(detail(point("p1", ".TGT", "target", first = true, held = true), point("p2", ".SP", "ip")))
        compose.onNodeWithText("Start alt").assertIsDisplayed()
        assertEquals(0, count("Speed to"))
        assertEquals(0, count("Wind °T"))
    }

    @Test
    fun `an altitude typed goes as the point's own, with what was shown and the field's key`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Alt to").performTextReplacement("300")
        val applied = r.points.last()
        assertEquals(values.copy(altitudeValue = "300"), applied.typed)
        assertEquals(values, applied.before)
        assertEquals(false, applied.first)
        assertTrue(applied.key.toString().startsWith("alt:r1:p2#"))
        assertEquals("applyPoint:r1:p2", r.log.last())
    }

    @Test
    fun `the references are chosen with a tap, each its own step`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("MSL").performClick()
        assertEquals(values.copy(altitudeRef = "msl"), r.points.last().typed)
        assertNull(r.points.last().key)
        compose.onNodeWithText("IAS").performClick()
        assertEquals(values.copy(speedType = "indicated"), r.points.last().typed)
        compose.onNodeWithText("AGL").assertIsSelected()                                         // what is shown is the point's, until it changes
    }

    @Test
    fun `a point refused shows the words at its field`() {
        page(detail(*log4)) { problem = "Wind speed is not a number." }
        compose.onNodeWithText("Wind kt").performTextReplacement("gale")
        compose.onNodeWithText("Wind speed is not a number.").assertIsDisplayed()
    }

    @Test
    fun `the clock is set with its own small form, and cleared there`() {
        val r = page(detail(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip", held = true)))
        compose.onNodeWithContentDescription("Set clock time at .SP, not set").performClick()
        compose.onNodeWithText("Clock time at .SP (HH:MM:SS)").performTextInput("12:30")
        compose.onNodeWithText("Set").performClick()
        assertEquals(values.copy(clock = "12:30"), r.points.single().typed)
        assertEquals(0, count("Clock time at .SP (HH:MM:SS)"))                                    // closed once it is set
    }

    @Test
    fun `a clock refused keeps its form open with the words`() {
        page(detail(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip", held = true))) { problem = "The time must be written hours:minutes, like 09:15 or 09:15:30." }
        compose.onNodeWithContentDescription("Set clock time at .SP, not set").performClick()
        compose.onNodeWithText("Clock time at .SP (HH:MM:SS)").performTextInput("25:00")
        compose.onNodeWithText("Set").performClick()
        compose.onNodeWithText("The time must be written hours:minutes, like 09:15 or 09:15:30.").assertIsDisplayed()
    }

    @Test
    fun `a name is saved on Save name, and a local point's name says it moves the point first`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Point name").performTextReplacement("BLUE 7")
        compose.onNodeWithText("Matches a local point: saving puts this point on it, at 1,730 ft.").assertIsDisplayed()
        compose.onNodeWithText("Save name").performClick()
        assertEquals("renamePoint:r1:p2:BLUE 7", r.log.last())
    }

    @Test
    fun `the point's kind, moving it and making it only shape the line are under its leg`() {
        val r = page(detail(*log4), crosshairGrid = "16S GC 28936 55287")
        compose.onNodeWithContentDescription("Kind of point for .SP: IP").performScrollTo().performClick()
        compose.onNodeWithText("Target").performClick()
        compose.onNodeWithText("Shape only").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Show controls to move this point").performScrollTo().performClick()
        compose.onAllNodesWithText("Crosshair: 16S GC 28936 55287")[0].performScrollTo().assertIsDisplayed()  // the map is under the page: the grid says where
        compose.onNodeWithContentDescription("Put this point at the crosshair, 16S GC 28936 55287").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move north 50 feet").performScrollTo().performClick()
        assertEquals(listOf("type:r1:p2:target", "shaping:r1:p2", "crosshair:r1:p2", "nudge:r1:p2:50:0"), r.log)
    }

    @Test
    fun `a grid typed for the point is sent, and what is wrong with it is said at the field`() {
        val r = page(detail(*log4)) { textProblem = "That is not a grid or a coordinate." }
        compose.onNodeWithContentDescription("Show controls to move this point").performScrollTo().performClick()
        compose.onNodeWithText("Move to a grid").performScrollTo().performTextInput("nowhere")
        compose.onNodeWithText("Go").performClick()
        assertEquals("pointTo:r1:p2:nowhere", r.log.last())
        compose.onNodeWithText("That is not a grid or a coordinate.").assertIsDisplayed()
    }

    @Test
    fun `a mission's point keeps its kind, as the file has it`() {
        page(detail(*log4, mission = true), open = open(madeUp = false, mission = true))
        assertEquals(0, count("Shape only"))
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Kind of point", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `a held point that only shapes the line can be moved, and made a route point`() {
        val r = page(detail(*log4.map { it.copy(held = false) }.toTypedArray(), shaping = 1, heldShaping = ShapingPointUi("s9", held = true, grid = "16S GC 1 2")))
        compose.onNodeWithText("Shaping point").assertIsDisplayed()
        compose.onNodeWithText("1 shaping point bends the line between named points.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Make it a route point").performScrollTo().performClick()
        assertEquals(listOf("named:r1:s9"), r.log)
    }

    @Test
    fun `a shaping point is added at the crosshair, which the button names`() {
        val r = page(detail(*log4))
        compose.onNodeWithContentDescription("Add a point that bends the line at the crosshair, 16S GC 28936 55287").performScrollTo().performClick()
        assertEquals(listOf("addShaping:r1"), r.log)
    }

    // -- The total ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the total is shown with Fetch elevations, and the note says what the values mean`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fetch elevations").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("UH-60L Black Hawk · speeds, altitudes and winds are “to” each point and export to AMPS.").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("elevations"), r.log)
    }

    @Test
    fun `with elevations fetched the button offers to refresh them, and while one is fetched it waits`() {
        var state by mutableStateOf(RoutesUiState(open = open(), detail = detail(*log4, elevations = true), planOpen = true))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutePlanPage(state, Recorder().actions, null, 0) } }
        compose.onNodeWithText("Refresh elevations").performScrollTo().assertIsDisplayed()
        state = state.copy(fetching = PlanningKind.ELEVATIONS)
        compose.onNodeWithText("Fetching…").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `what the planner says is wrong is shown`() {
        page(detail(*log4, warnings = listOf("Set an airspeed above zero.")))
        compose.onNodeWithText("Set an airspeed above zero.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with no plan open there is no page`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutePlanPage(RoutesUiState(), Recorder().actions, null, 0) } }
        assertEquals(0, count("Whole route"))
    }

    // -- What the review found ---------------------------------------------------------------------------------------------------

    @Test
    fun `the page is announced by its name, as a page of its own`() {
        page(detail(*log4))
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Plan of ROUTE 1")).assertExists()
    }

    @Test
    fun `what is typed in one visit to a field is one key, and the next visit another`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Temp °C").performTextInput("1")
        compose.onNodeWithText("Temp °C").performTextInput("2")
        val first = r.plans.map { it.key }
        assertEquals(2, first.size)
        assertEquals(first[0], first[1])                                                      // one visit: one step to undo
        compose.onNodeWithText("Fuel lb/hr").performClick()                                      // the field is left
        compose.onNodeWithText("Temp °C").performClick()                                         // and visited again
        compose.onNodeWithText("Temp °C").performTextInput("3")
        assertTrue(r.plans.last().key != first[0])
        assertTrue(r.plans.last().key.toString().startsWith("temp:r1#"))
    }

    @Test
    fun `Done on a name that was not changed moves nothing`() {
        val r = page(detail(*log4))
        compose.onNodeWithText("Point name").performImeAction()
        assertEquals(emptyList<String>(), r.log.filter { it.startsWith("renamePoint") })
        compose.onNodeWithText("Point name").performTextReplacement(".SP2")
        compose.onNodeWithText("Point name").performImeAction()
        assertEquals("renamePoint:r1:p2:.SP2", r.log.last())
    }

    @Test
    fun `a refusal or a delete asked about from the bar is in sight however far the page is scrolled`() {
        page(detail(*log4), configure = { copy(error = "A route needs at least two named points.") })
        compose.onNodeWithText("Total").performScrollTo()                                        // down the page
        compose.onNodeWithText("A route needs at least two named points.").assertIsDisplayed()
    }

    @Test
    fun `the references are a group of choices, each said with its words`() {
        page(detail(*log4))
        compose.onNodeWithContentDescription("MSL, above mean sea level").assertIsDisplayed()
        compose.onNodeWithContentDescription("TAS, true airspeed").assertIsDisplayed()
    }

}
