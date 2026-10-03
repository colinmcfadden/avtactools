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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
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
        )
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
}
