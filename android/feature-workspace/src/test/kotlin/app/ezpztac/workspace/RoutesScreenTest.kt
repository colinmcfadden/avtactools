package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The routes tab as a person meets it: the sets, the routes of the open one, and the toolbar over the map while drawing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val actions = RoutesActions(
            startCreating = { log += "startCreating" }, cancelCreating = { log += "cancelCreating" }, createSet = { log += "create:$it" },
            openSet = { log += "open:$it" }, closeSet = { log += "close" }, renameSet = { id, name -> log += "renameSet:$id:$name" },
            deleteSet = { log += "deleteSet:$it" }, resolve = { id, how -> log += "resolve:$id:$how" }, dismissError = { log += "dismiss" },
            selectRoute = { log += "select:$it" }, toggleVisible = { log += "toggle:$it" }, renameRoute = { id, name -> log += "renameRoute:$id:$name" },
            deleteRoute = { log += "deleteRoute:$it" }, undo = { log += "undo" }, redo = { log += "redo" }, startDrawing = { log += "draw" },
            addAtCrosshair = { log += "add" }, undoPoint = { log += "undoPoint" }, finishDrawing = { log += "finish" }, cancelDrawing = { log += "cancelDrawing" },
            applyPlan = { route, draft -> applied += route to draft; log += "applyPlan:$route"; problem },
            applyPoint = { route, point, typed, before, first -> points += Applied(route, point, typed, before, first); log += "applyPoint:$route:$point"; problem },
            selectPoint = { log += "selectPoint:$it" }, renamePoint = { r, p, n -> log += "renamePoint:$r:$p:$n" },
            setPointType = { r, p, t -> log += "type:$r:$p:$t" }, makeShaping = { r, p -> log += "shaping:$r:$p" }, makeNamed = { r, p -> log += "named:$r:$p" },
            fetchWinds = { log += "winds" }, fetchElevations = { log += "elevations" }, dismissNote = { log += "dismissNote" },
        )
        var problem: String? = null
        val applied = mutableListOf<Pair<String, PlanDraft>>()
        class Applied(val route: String, val point: String, val typed: PointDraft, val before: PointDraft, val first: Boolean)
        val points = mutableListOf<Applied>()
    }

    private fun content(state: RoutesUiState): Recorder {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { RoutesContent(state, r.actions) } } }
        return r
    }

    private fun toolbar(drawing: RouteDrawingUi, error: String? = null): Recorder {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { RouteToolbar(drawing, error, r.actions) } } }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val one = RouteRowUi("r1", "ROUTE 1", "#FF453A", visible = true, pointCount = 5, routePointCount = 4, summary = "12.3 nm · 8:15", selected = false)
    private val two = RouteRowUi("r2", "INGRESS", "#0A84FF", visible = false, pointCount = 2, routePointCount = 2, summary = null, selected = true)
    private val three = RouteRowUi("r3", "EGRESS", "#32D74B", visible = true, pointCount = 7, routePointCount = 3, summary = null, selected = false)

    private fun open(vararg routes: RouteRowUi, name: String = "MISSION 1", conflictOf: String? = null, canUndo: Boolean = false, canRedo: Boolean = false) =
        OpenSetUi("s1", name, routes.toList(), canUndo, canRedo, SyncStatus.SYNCED, conflictOf)

    private val setRow = RouteSetRow("s2", "NIGHT RUN", 3, SyncStatus.SYNCED, null, isOpen = false)

    // -- Empty -----------------------------------------------------------------------------------------------

    @Test
    fun `with nothing saved it says how to begin, and New set starts the form`() {
        val r = content(RoutesUiState())
        compose.onNodeWithText("Routes").assertIsDisplayed()
        compose.onNodeWithText("No routes yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("New set").performClick()
        assertEquals(listOf("startCreating"), r.log)
    }

    @Test
    fun `the form creates a set with the name typed, and can be cancelled`() {
        val r = content(RoutesUiState(creating = true))
        assertEquals(0, count("New set"))                                                          // the button is gone while the form is open
        assertEquals(0, count("No routes yet", substring = true))
        compose.onNodeWithText("Name (optional)").performTextInput("night")
        compose.onNodeWithText("Create set").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("create:night", "cancelCreating"), r.log)
    }

    @Test
    fun `an error is shown and can be dismissed`() {
        val r = content(RoutesUiState(error = "The set could not be opened."))
        compose.onNodeWithText("The set could not be opened.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }

    // -- The open set ----------------------------------------------------------------------------------------

    @Test
    fun `the open set is a card with its routes, each with what it is`() {
        content(RoutesUiState(sets = listOf(RouteSetRow("s1", "MISSION 1", 2, SyncStatus.SYNCED, null, true)), open = open(one, two)))
        compose.onNodeWithText("MISSION 1").assertIsDisplayed()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("ROUTE 1").assertIsDisplayed()
        compose.onNodeWithText("5 points · 4 route points").assertIsDisplayed()
        compose.onNodeWithText("12.3 nm · 8:15").assertIsDisplayed()
        compose.onNodeWithText("2 points · 2 route points").assertIsDisplayed()
        assertEquals(0, count("7 points", substring = true))
        assertEquals(0, count("Other sets"))
    }

    @Test
    fun `a route with no summary yet shows only what it is made of`() {
        content(RoutesUiState(open = open(three)))
        compose.onNodeWithText("7 points · 3 route points").assertIsDisplayed()
        assertEquals(0, count(" nm", substring = true))
    }

    @Test
    fun `a set that is open is not told that there are no routes, even before the list has it`() {
        content(RoutesUiState(sets = emptyList(), open = open(one)))
        assertEquals(0, count("No routes yet", substring = true))
    }

    @Test
    fun `a route of one named point says so in the singular`() {
        content(RoutesUiState(open = open(one.copy(pointCount = 1, routePointCount = 1, summary = null))))
        compose.onNodeWithText("1 point · 1 route point").assertIsDisplayed()
    }

    @Test
    fun `an open set with no routes says so and offers to draw`() {
        val r = content(RoutesUiState(open = open()))
        compose.onNodeWithText("This set has no routes yet.").assertIsDisplayed()
        compose.onNodeWithText("Draw a route").performClick()
        assertEquals(listOf("draw"), r.log)
    }

    @Test
    fun `while drawing the card says so instead of offering to draw again`() {
        content(RoutesUiState(open = open(one), drawing = RouteDrawingUi(3, true)))
        assertEquals(0, count("Draw a route"))
        compose.onNodeWithText("Drawing a route: 3 points", substring = true).assertIsDisplayed()
        assertEquals(0, count("This set has no routes yet."))
    }

    @Test
    fun `tapping a route holds it, and Hide and Show change its visibility`() {
        val r = content(RoutesUiState(open = open(one, two)))
        compose.onNodeWithText("ROUTE 1").performClick()
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("Show").performClick()
        assertEquals(listOf("select:r1", "toggle:r1", "toggle:r2"), r.log)
    }

    @Test
    fun `only the held route offers rename and delete, and a delete is asked about first`() {
        val r = content(RoutesUiState(open = open(one, two)))
        assertEquals(1, count("Rename"))
        compose.onNodeWithText("Delete").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Delete \"INGRESS\"?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"INGRESS\"?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).performClick()
        assertEquals(listOf("deleteRoute:r2"), r.log)
    }

    @Test
    fun `renaming a route takes the typed name`() {
        val r = content(RoutesUiState(open = open(one, two)))
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Route name").performTextInput("X")
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("renameRoute:r2:XINGRESS"), r.log)                                      // the field starts with the route's own name, and typing goes in at the front
    }

    @Test
    fun `undo and redo are there when there is something to undo or redo`() {
        content(RoutesUiState(open = open(one)))
        compose.onNodeWithText("Undo").assertIsNotEnabled()
        compose.onNodeWithText("Redo").assertIsNotEnabled()
    }

    @Test
    fun `undo and redo call through when enabled`() {
        val r = content(RoutesUiState(open = open(one, canUndo = true, canRedo = true)))
        compose.onNodeWithText("Undo").assertIsEnabled().performClick()
        compose.onNodeWithText("Redo").assertIsEnabled().performClick()
        assertEquals(listOf("undo", "redo"), r.log)
    }

    @Test
    fun `the open set can be closed, and its rename and delete are asked about`() {
        val r = content(RoutesUiState(open = open(one)))
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Delete set").performClick()
        compose.onNodeWithText("Delete \"MISSION 1\" and its routes?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete set").performClick()
        assertEquals(listOf("close", "deleteSet:s1"), r.log)
    }

    @Test
    fun `a conflict copy that is open offers the three choices`() {
        val r = content(RoutesUiState(open = open(one, conflictOf = "s0")))
        compose.onNodeWithText("This is your version", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep both").performClick()
        assertEquals(listOf("resolve:s1:${SyncEngine.Resolution.KEEP_BOTH}"), r.log)
    }

    // -- The others ------------------------------------------------------------------------------------------

    @Test
    fun `the other sets are listed, and tapping one opens it`() {
        val r = content(RoutesUiState(sets = listOf(RouteSetRow("s1", "MISSION 1", 2, SyncStatus.SYNCED, null, true), setRow), open = open(one)))
        compose.onNodeWithText("Other sets").assertIsDisplayed()
        compose.onNodeWithText("NIGHT RUN").performClick()
        assertEquals(listOf("open:s2"), r.log)
        compose.onNodeWithText("3 routes").assertIsDisplayed()
    }

    @Test
    fun `with no set open every set is in the list`() {
        content(RoutesUiState(sets = listOf(setRow.copy(routeCount = 1))))
        assertEquals(0, count("Other sets"))
        compose.onNodeWithText("1 route").assertIsDisplayed()
    }

    @Test
    fun `a conflict copy in the list offers its choices, and a set waiting to sync says so`() {
        val r = content(RoutesUiState(sets = listOf(setRow.copy(sync = SyncStatus.CONFLICT, conflictOf = "s9"))))
        compose.onNodeWithText("Conflict copy").assertIsDisplayed()
        compose.onNodeWithText("Keep theirs (discard mine)").performClick()
        assertEquals(listOf("resolve:s2:${SyncEngine.Resolution.KEEP_THEIRS}"), r.log)
    }

    @Test
    fun `a set waiting to sync says so`() {
        content(RoutesUiState(sets = listOf(setRow.copy(sync = SyncStatus.PENDING))))
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
    }

    // -- Over the map ------------------------------------------------------------------------------------------

    @Test
    fun `the toolbar says how many points, and what each button does`() {
        val r = toolbar(RouteDrawingUi(1, false))
        compose.onNodeWithText("Route: 1 point").assertIsDisplayed()
        compose.onNodeWithText("Tap the map to add a point. A route needs 2.").assertIsDisplayed()
        compose.onNodeWithText("Finish").assertIsNotEnabled()
        compose.onNodeWithText("Add here").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("add", "undoPoint", "cancelDrawing"), r.log)
    }

    @Test
    fun `the toolbar can finish once there are two points`() {
        val r = toolbar(RouteDrawingUi(2, true))
        compose.onNodeWithText("Tap the map for another point, or Finish.").assertIsDisplayed()
        compose.onNodeWithText("Finish").assertIsEnabled().performClick()
        assertEquals(listOf("finish"), r.log)
    }

    @Test
    fun `with no points there is nothing to undo, and an error shows on the toolbar`() {
        val r = toolbar(RouteDrawingUi(0, false), error = "Move the map to where the point should go first.")
        compose.onNodeWithText("Undo").assertIsNotEnabled()
        compose.onNodeWithText("Move the map to where the point should go first.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }

    @Test
    fun `the toolbar takes no room when nothing is being drawn`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { RouteToolbarWhileDrawing(RoutesUiState(), Recorder().actions) } } }
        assertEquals(0, count("Finish"))
    }

    @Test
    fun `the toolbar is there while a route is being drawn`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { RouteToolbarWhileDrawing(RoutesUiState(drawing = RouteDrawingUi(2, true)), Recorder().actions) } } }
        compose.onNodeWithText("Finish").assertIsDisplayed()
    }

    // -- The held route's plan ---------------------------------------------------------------------------------

    private fun values() = PointDraft("50", "agl", "100", "ground", "0", "0")

    private fun point(id: String, name: String, type: String?, first: Boolean = false, held: Boolean = false, hasClock: Boolean = false, clock: String = "--:--:--", facts: String = "3.1 nm · 045°T · 98 kt · 1320' MSL") =
        PlanPointUi(id, name, type, first, values(), clock, hasClock, if (first) "START" else facts, if (first) "0:00" else "1:52", held)

    private fun detail(vararg points: PlanPointUi, shaping: Int = 0, heldShaping: ShapingPointUi? = null, warnings: List<String> = emptyList(), totals: String? = "Total 12.3 nm · 8:15 · 640 lb") =
        RouteDetailUi("r1", "ROUTE 1", "UH-60L Black Hawk", PlanDraft(date = "2026-10-03"), points.toList(), shaping, heldShaping, totals, warnings, hasElevations = false)

    private fun withDetail(detail: RouteDetailUi): Recorder = content(RoutesUiState(open = open(one), detail = detail))

    private val log = arrayOf(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip"), point("p3", ".TGT", "target", hasClock = true, clock = "12:30:00"))

    @Test
    fun `the held route shows its plan, its aircraft and its totals`() {
        withDetail(detail(*log))
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("UH-60L Black Hawk").assertIsDisplayed()
        compose.onNodeWithText("Total 12.3 nm · 8:15 · 640 lb").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with no route held there is no plan`() {
        content(RoutesUiState(open = open(one)))
        assertEquals(0, count("Apply plan"))
        assertEquals(0, count("Nav log"))
    }

    @Test
    fun `the plan form starts as the plan, and Apply is there only once something has changed`() {
        val r = withDetail(detail(*log))
        compose.onNodeWithText("Apply plan").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Date (YYYY-MM-DD)").performScrollTo()
        compose.onNodeWithText("2026-10-03").assertIsDisplayed()
        compose.onNodeWithText("Wind (kt)").performScrollTo().performTextInput("5")
        compose.onNodeWithText("Apply plan").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("applyPlan:r1"), r.log)
        assertEquals("50", r.applied.single().second.windSpeed)                                     // "0" with the 5 typed at the front
    }

    @Test
    fun `a plan the form refuses shows the words beside the fields, and typing again clears them`() {
        val r = Recorder().also { it.problem = "Wind speed is not a number." }
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { RoutesContent(RoutesUiState(open = open(one), detail = detail(*log)), r.actions) } } }
        compose.onNodeWithText("Wind (kt)").performScrollTo().performTextInput("x")
        compose.onNodeWithText("Apply plan").performScrollTo().performClick()
        compose.onNodeWithText("Wind speed is not a number.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wind (kt)").performScrollTo().performTextInput("1")
        assertEquals(0, count("Wind speed is not a number."))
    }

    @Test
    fun `the nav log has a row for each named point with its leg, its time and the time since the start`() {
        withDetail(detail(*log))
        compose.onNodeWithText("Nav log").performScrollTo().assertIsDisplayed()
        assertEquals(2, count("▲ .TGT"))                                                              // the first point and the last
        assertEquals(1, count("■ .SP"))
        compose.onNodeWithText("START").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12:30:00 TOT").performScrollTo().assertIsDisplayed()                  // the TOT says so in words
        assertEquals(2, count("3.1 nm · 045°T · 98 kt · 1320' MSL"))
        assertEquals(1, count("0:00"))
        assertEquals(2, count("1:52"))
    }

    @Test
    fun `an unnamed point is called what it is`() {
        withDetail(detail(point("p1", "", "turn", first = true), point("p2", "", "turn")))
        compose.onNodeWithText("● .SP").performScrollTo().assertIsDisplayed()                         // the first point is the start
        compose.onNodeWithText("● (unnamed)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping a row holds the point`() {
        val r = withDetail(detail(*log))
        compose.onNodeWithText("● .SP", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("■ .SP").performScrollTo().performClick()
        assertEquals(listOf("selectPoint:p2"), r.log)
    }

    @Test
    fun `only the held point has its form, and the first point has no speed or wind`() {
        withDetail(detail(point("p1", ".TGT", "target", first = true, held = true), point("p2", ".SP", "ip"), point("p3", ".TGT", "target")))
        compose.onNodeWithText("Start altitude (ft)").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("Speed to"))
        assertEquals(0, count("Altitude to (ft)"))
        assertEquals(1, count("Time to be here (HH:MM:SS)"))
    }

    @Test
    fun `a later point has the legs speed and wind as well`() {
        withDetail(detail(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip", held = true)))
        compose.onNodeWithText("Altitude to (ft)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Speed to").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Wind from (°T)")[1].performScrollTo().assertIsDisplayed()          // the plan has one, and so has the point
    }

    @Test
    fun `applying a point passes what was typed, what was shown and whether it is the first`() {
        val r = withDetail(detail(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip", held = true)))
        compose.onNodeWithText("Apply point").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Time to be here (HH:MM:SS)").performScrollTo().performTextInput("12:30")
        compose.onNodeWithText("Apply point").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("applyPoint:r1:p2"), r.log)
        val applied = r.points.single()
        assertEquals("12:30", applied.typed.clock)
        assertEquals("", applied.before.clock)
        assertEquals(false, applied.first)
    }

    @Test
    fun `a point the form refuses shows the words in its row`() {
        val r = Recorder().also { it.problem = "The time must be written hours:minutes, like 09:15 or 09:15:30." }
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { RoutesContent(RoutesUiState(open = open(one), detail = detail(point("p1", ".TGT", "target", first = true, held = true), point("p2", ".SP", "ip"))), r.actions) } }
        }
        compose.onNodeWithText("Time to be here (HH:MM:SS)").performScrollTo().performTextInput("noon")
        compose.onNodeWithText("Apply point").performScrollTo().performClick()
        compose.onNodeWithText("The time must be written hours:minutes, like 09:15 or 09:15:30.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the held point can be renamed, and can be made a different type or only a shaping point`() {
        val r = withDetail(detail(point("p1", ".TGT", "target", first = true), point("p2", ".SP", "ip", held = true)))
        compose.onNodeWithText("Name").performScrollTo().performTextInput("X")
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Type: IP").performScrollTo().performClick()
        compose.onNodeWithText("Target").performClick()
        compose.onNodeWithText("Make it only shape the line").performScrollTo().performClick()
        assertEquals(listOf("renamePoint:r1:p2:X.SP", "type:r1:p2:target", "shaping:r1:p2"), r.log)
    }

    @Test
    fun `a held point that only shapes the line is offered as a route point`() {
        val r = withDetail(detail(*log, shaping = 1, heldShaping = ShapingPointUi("s1", held = true)))
        compose.onNodeWithText("Shaping point").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Make it a route point").performScrollTo().performClick()
        assertEquals(listOf("named:r1:s1"), r.log)
        compose.onNodeWithText("1 shaping point bends the line between named points.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `several shaping points are counted`() {
        withDetail(detail(*log, shaping = 3))
        compose.onNodeWithText("3 shaping points bend the line between named points.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `what the planner says is wrong is shown, and with no totals nothing is totalled`() {
        withDetail(detail(warnings = listOf("Route needs at least two route points."), totals = null))
        compose.onNodeWithText("Route needs at least two route points.").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("Total ", substring = true))
        assertEquals(0, count("Nav log"))
    }

    // -- Winds and elevations -------------------------------------------------------------------------------------

    private fun fetching(kind: PlanningKind? = null, note: PlanningNote? = null, hasElevations: Boolean = false): Recorder {
        val r = Recorder()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    RoutesContent(RoutesUiState(open = open(one), detail = detail(*log).copy(hasElevations = hasElevations), fetching = kind, note = note), r.actions)
                }
            }
        }
        return r
    }

    @Test
    fun `winds and elevations can be fetched, and the elevation button says whether they are already there`() {
        val r = fetching()
        compose.onNodeWithText("Fetch winds").performScrollTo().performClick()
        compose.onNodeWithText("Fetch elevations").performScrollTo().performClick()
        assertEquals(listOf("winds", "elevations"), r.log)
        assertEquals(0, count("Refresh elevations"))
    }

    @Test
    fun `with elevations already fetched the button offers to refresh them`() {
        fetching(hasElevations = true)
        compose.onNodeWithText("Refresh elevations").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("Fetch elevations"))
    }

    @Test
    fun `while winds are being fetched the button says so and the other cannot be pressed`() {
        val r = fetching(PlanningKind.WINDS)
        compose.onNodeWithText("Fetching winds…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fetch elevations").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Fetching winds…").performClick()
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `while elevations are being fetched the other button cannot be pressed`() {
        fetching(PlanningKind.ELEVATIONS)
        compose.onNodeWithText("Fetching…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fetch winds").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `what a fetch found is shown, and can be dismissed`() {
        val r = fetching(note = PlanningNote("3 points · 2 METAR, 1 TAF", failed = false))
        compose.onNodeWithText("3 points · 2 METAR, 1 TAF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertEquals(listOf("dismissNote"), r.log)
    }

    @Test
    fun `a fetch that failed is shown as an error`() {
        fetching(note = PlanningNote("There is no connection to the server, so the winds could not be fetched.", failed = true))
        compose.onNodeWithText("There is no connection to the server, so the winds could not be fetched.").performScrollTo().assertIsDisplayed()
    }
}
