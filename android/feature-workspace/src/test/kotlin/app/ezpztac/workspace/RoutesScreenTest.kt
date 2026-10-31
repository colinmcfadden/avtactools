package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
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

/** The Routes panel as a person meets it (docs/native-design: Routes, SaveRoutes): the sets open now, their routes, every set, the foot, and the toolbar while drawing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val actions = RoutesActions(
            importMission = { log += "import" }, showPage = { log += "page:$it" }, browse = { log += "browse" }, openSet = { log += "open:$it" }, close = { log += "close:$it" },
            renameSet = { id, name -> log += "renameSet:$id:$name" }, deleteSet = { log += "deleteSet:$it" }, save = { log += "save" },
            resolve = { id, how -> log += "resolve:$id:$how" }, dismissError = { log += "dismiss" }, openPlan = { log += "plan:$it" },
            toggleVisible = { log += "toggle:$it" }, renameRoute = { id, name -> log += "renameRoute:$id:$name" }, deleteRoute = { log += "deleteRoute:$it" },
            undo = { log += "undo" }, redo = { log += "redo" }, startSketch = { log += "sketch" }, addAtCrosshair = { log += "add" }, undoPoint = { log += "undoPoint" },
            finishDrawing = { log += "finish" }, cancelDrawing = { log += "cancelDrawing" }, exportSet = { log += "exportSet:$it" },
            exportRoute = { id, threats -> log += "exportRoute:$id:$threats" }, shareRoute = { id, format -> log += "share:$id:${format.extension}" },
            dismissExportWarning = { log += "dismissWarning" }, includeThreats = { log += "threats:$it" },
        )
    }

    private fun home(state: RoutesUiState, threats: Int = 0): Recorder {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RoutesHomeContent(state, r.actions, threatCount = threats) } } }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val one = RouteRowUi("r1", "ROUTE 1", "#E5533D", visible = true, pointCount = 4, routePointCount = 4, summary = "4.2 nm · 2:30 · 40 lb", selected = false)
    private val hidden = RouteRowUi("r2", "EGRESS", "#0A84FF", visible = false, pointCount = 3, routePointCount = 1, summary = null, selected = false)

    private fun open(
        vararg routes: RouteRowUi, name: String = "SKETCHED ROUTES", madeUp: Boolean = true, sync: SyncStatus? = SyncStatus.PENDING, mission: Boolean = false,
        conflictOf: String? = null, canUndo: Boolean = false, canRedo: Boolean = false,
    ) = OpenSetUi(
        "s1", name, routes.toList(), canUndo, canRedo, sync, conflictOf, isMission = mission, madeUpName = madeUp, chips = routeSetChips(sync, conflictOf, mission),
        saveContents = listOf("1 route, 4 points, 4.2 nm", "Speeds, altitudes, winds, fuel flow and the start time"),
    )

    private fun row(uuid: String, name: String, open: Boolean = false, mission: Boolean = false, sync: SyncStatus = SyncStatus.SYNCED, conflictOf: String? = null) =
        RouteSetRow(uuid, name, 2, sync, conflictOf, open, mission, pointCount = if (mission) null else 9, chips = routeSetChips(sync, conflictOf, mission))

    // -- The set on the map ---------------------------------------------------------------------------------------------

    @Test
    fun `the set on the map is a card with its state, what it holds, and its routes`() {
        home(RoutesUiState(open = open(one), sets = listOf(row("s1", "SKETCHED ROUTES", open = true))))
        compose.onNodeWithText("SKETCHED ROUTES").assertIsDisplayed()
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
        compose.onNodeWithText("1 route · 4 points").assertIsDisplayed()
        compose.onNodeWithText("Saves to your Library").assertIsDisplayed()
        compose.onNodeWithText("ROUTE 1").assertIsDisplayed()
        compose.onNodeWithText("4.2 nm · 2:30 · 40 lb").assertIsDisplayed()
    }

    @Test
    fun `Save names a set of sketched routes with a route, and is off with none`() {
        val r = home(RoutesUiState(open = open(one)))
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf("save"), r.log)
    }

    @Test
    fun `Save is off for a set with nothing in it yet, and a named set or a mission is Saved`() {
        home(RoutesUiState(open = open()))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("No routes in this set yet. Sketch a route to start one.").assertIsDisplayed()
    }

    @Test
    fun `a named set says it is saved`() {
        home(RoutesUiState(open = open(one, name = "NIGHT INFIL", madeUp = false, sync = SyncStatus.SYNCED)))
        compose.onNodeWithText("Saved").assertIsNotEnabled()
        compose.onNodeWithText("Synced").assertIsDisplayed()
    }

    @Test
    fun `an imported mission says so, is saved, and its routes are not renamed or removed here`() {
        home(RoutesUiState(open = open(one, name = "GOAT SUCKER", madeUp = false, mission = true, sync = SyncStatus.SYNCED)))
        compose.onNodeWithText("Imported").assertIsDisplayed()
        compose.onNodeWithText("Saved").assertIsNotEnabled()
        compose.onNodeWithText("An AMPS mission", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        assertEquals(0, count("Rename"))
        assertEquals(0, count("Delete"))
        compose.onNodeWithText("Export this route as .msnx").assertIsDisplayed()
    }

    @Test
    fun `Export takes the threats only when there are some and the box is ticked`() {
        val r = home(RoutesUiState(open = open(one), includeThreats = true), threats = 2)
        compose.onNodeWithText("Include threats (2) as a .ths file").assertIsDisplayed()
        compose.onNodeWithText("Built on this device; never saved to your account").assertIsDisplayed()
        compose.onNodeWithText("Export .msnx").performClick()
        assertEquals(listOf("exportSet:true"), r.log)
    }

    @Test
    fun `the threats' box says what it is set to and changes it`() {
        val r = home(RoutesUiState(open = open(one), includeThreats = true), threats = 1)
        compose.onNode(hasText("Include threats (1) as a .ths file", substring = true) and isToggleable()).assertIsOn().performClick()
        assertEquals(listOf("threats:false"), r.log)
    }

    @Test
    fun `with the box cleared, or no threats at all, the mission goes alone`() {
        val r = home(RoutesUiState(open = open(one), includeThreats = false), threats = 3)
        compose.onNode(hasText("Include threats (3) as a .ths file", substring = true) and isToggleable()).assertIsOff()
        compose.onNodeWithText("Export .msnx").performClick()
        assertEquals(listOf("exportSet:false"), r.log)
    }

    @Test
    fun `with no threats the box is not offered`() {
        val r = home(RoutesUiState(open = open(one), includeThreats = true), threats = 0)
        assertEquals(0, count("Include threats", substring = true))
        compose.onNodeWithText("Export .msnx").performClick()
        assertEquals(listOf("exportSet:false"), r.log)
    }

    @Test
    fun `while a mission is being built the button says so and takes no taps`() {
        home(RoutesUiState(open = open(one), exporting = true))
        compose.onNodeWithText("Building…").assertIsNotEnabled()
    }

    @Test
    fun `an empty set has nothing to export`() {
        home(RoutesUiState(open = open()))
        compose.onNodeWithText("Export .msnx").assertIsNotEnabled()
    }

    @Test
    fun `a warning about the file just made is shown until it is dismissed`() {
        val r = home(RoutesUiState(open = open(one), exportWarning = "AMPS will open it as a UH-60L."))
        compose.onNodeWithText("AMPS will open it as a UH-60L.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismissWarning"), r.log)
    }

    @Test
    fun `an error is shown and can be dismissed`() {
        val r = home(RoutesUiState(open = open(one), error = "The set could not be opened."))
        compose.onNodeWithText("The set could not be opened.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }

    // -- Its routes -------------------------------------------------------------------------------------------------------

    @Test
    fun `a route's row opens its plan, and its buttons share, hide and do the rest`() {
        val r = home(RoutesUiState(open = open(one, hidden)))
        compose.onNodeWithText("ROUTE 1").performClick()
        compose.onNodeWithContentDescription("Hide ROUTE 1").performClick()
        compose.onNodeWithContentDescription("Show EGRESS").performClick()               // a hidden route offers to show it
        compose.onNodeWithContentDescription("Share ROUTE 1 as GPX or FPL").performClick()
        compose.onNodeWithText("GPX").performClick()
        compose.onNodeWithContentDescription("Share ROUTE 1 as GPX or FPL").performClick()
        compose.onNodeWithText("Garmin FPL").performClick()
        assertEquals(listOf("plan:r1", "toggle:r1", "toggle:r2", "share:r1:gpx", "share:r1:fpl"), r.log)
    }

    @Test
    fun `a route with no legs yet says how many points it has`() {
        home(RoutesUiState(open = open(hidden)))
        compose.onNodeWithText("3 points").assertIsDisplayed()
    }

    @Test
    fun `a route is renamed from its menu, in capitals`() {
        val r = home(RoutesUiState(open = open(one)))
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Route name").performTextClearance()
        compose.onNodeWithText("Route name").performTextInput("ingress")
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("renameRoute:r1:ingress"), r.log)                                   // the model puts it in capitals
    }

    @Test
    fun `a route is exported on its own with the threats as the box says`() {
        val r = home(RoutesUiState(open = open(one), includeThreats = true), threats = 1)
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Export this route as .msnx").performClick()
        assertEquals(listOf("exportRoute:r1:true"), r.log)
    }

    @Test
    fun `deleting a route is asked about first, and keeping it is the prominent answer`() {
        val r = home(RoutesUiState(open = open(one)))
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"ROUTE 1\"? You can undo this until the set is closed.").assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithContentDescription("More actions for ROUTE 1").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("deleteRoute:r1"), r.log)
    }

    // -- The set's own menu and name --------------------------------------------------------------------------------------

    @Test
    fun `the set's menu has undo and redo when there is something to take back, close and delete`() {
        val r = home(RoutesUiState(open = open(one, canUndo = true, canRedo = false)))
        compose.onNodeWithContentDescription("More actions for SKETCHED ROUTES").performClick()
        compose.onNodeWithText("Redo").assertIsNotEnabled()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("More actions for SKETCHED ROUTES").performClick()
        compose.onNodeWithText("Takes these routes out of this session").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("undo", "close:s1"), r.log)
    }

    @Test
    fun `a set of sketched routes is added to a Mission Pack from its menu, never a mission`() {
        val log = mutableListOf<String>()
        val withPacks = RoutesActions(addToPack = { uuid, name -> log += "pack:$uuid:$name" })
        var state by mutableStateOf(RoutesUiState(open = open(one, name = "GOAT SUCKER", mission = true)))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RoutesHomeContent(state, withPacks, threatCount = 0) } } }
        compose.onNodeWithContentDescription("More actions for GOAT SUCKER").performClick()
        compose.onNodeWithText("Add to Mission Pack…").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        state = RoutesUiState(open = open())
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More actions for SKETCHED ROUTES").performClick()
        compose.onNodeWithText("Add to Mission Pack…").performClick()
        assertEquals(listOf("pack:s1:SKETCHED ROUTES"), log)
    }

    @Test
    fun `deleting the set is asked about first`() {
        val r = home(RoutesUiState(open = open(one, name = "NIGHT INFIL", madeUp = false)))
        compose.onNodeWithContentDescription("More actions for NIGHT INFIL").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"NIGHT INFIL\" and its routes? It is removed from this device and from your account.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("deleteSet:s1"), r.log)
    }

    @Test
    fun `the set is renamed with its pencil, and a made-up name is refused at the field`() {
        val r = home(RoutesUiState(open = open(one, name = "NIGHT INFIL", madeUp = false)))
        compose.onNodeWithContentDescription("Rename NIGHT INFIL").performClick()
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Name").performTextInput("sketched routes 3")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText(RouteSetNames.MADE_UP_REFUSED).assertIsDisplayed()
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Name").performTextInput("night exfil")
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("renameSet:s1:NIGHT EXFIL"), r.log)
    }

    @Test
    fun `a conflict copy on the map offers the three choices`() {
        val r = home(RoutesUiState(open = open(one, conflictOf = "s0", sync = SyncStatus.CONFLICT)))
        compose.onNodeWithText("Conflict copy").assertIsDisplayed()
        compose.onNodeWithText("Keep both").performScrollTo().performClick()
        assertEquals(listOf("resolve:s1:${SyncEngine.Resolution.KEEP_BOTH}"), r.log)
    }

    // -- Also open, every set ---------------------------------------------------------------------------------------------

    @Test
    fun `the other sets in the session are also open, and a tap opens one on the map`() {
        val r = home(RoutesUiState(open = open(one), alsoOpen = listOf(row("s2", "GOAT SUCKER", mission = true)), sets = listOf(row("s1", "SKETCHED ROUTES", open = true), row("s2", "GOAT SUCKER", mission = true))))
        compose.onNodeWithText("Also open").assertIsDisplayed()
        compose.onNodeWithText("AMPS mission · 2 routes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("GOAT SUCKER").performClick()
        compose.onNodeWithText("Every route set").performScrollTo().performClick()
        assertEquals(listOf("open:s2", "browse"), r.log)                                      // the Library, on its Routes tab
        compose.onNodeWithText("2 in your Library").assertIsDisplayed()
    }

    @Test
    fun `with nothing open it says how to begin`() {
        home(RoutesUiState())
        compose.onNodeWithText("No routes open. Sketch a route on the map, or import an AMPS mission file.").assertIsDisplayed()
        assertEquals(0, count("Every route set"))
    }

    @Test
    fun `a conflict waiting for a choice is said at the top, and Review shows every set`() {
        val r = home(RoutesUiState(sets = listOf(row("s1", "ONE"), row("s9", "ONE (from this device)", conflictOf = "s1", sync = SyncStatus.CONFLICT))))
        compose.onNodeWithText("1 route set needs a choice: another device changed it too.").assertIsDisplayed()
        compose.onNodeWithText("Review").performClick()
        assertEquals(listOf("browse"), r.log)                                                  // the Library, where conflicts are settled
    }

    @Test
    fun `the foot sketches a route and imports a mission`() {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutesFooter(RoutesUiState(), r.actions) } }
        compose.onNodeWithText("Sketch a route").performClick()
        compose.onNodeWithText("Import .msnx").performClick()
        assertEquals(listOf("sketch", "import"), r.log)
    }

    @Test
    fun `while a route is drawn the foot cancels it, and finishes it once it has two points`() {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutesFooter(RoutesUiState(drawing = RouteDrawingUi(1, false)), r.actions) } }
        compose.onNodeWithText("Add two points").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancelDrawing"), r.log)
        assertEquals(0, count("Sketch a route"))
    }

    @Test
    fun `a route with two points is finished from the foot`() {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { RoutesFooter(RoutesUiState(drawing = RouteDrawingUi(2, true)), r.actions) } }
        compose.onNodeWithText("Finish route").assertIsEnabled().performClick()
        assertEquals(listOf("finish"), r.log)
    }

    // -- Over the map ------------------------------------------------------------------------------------------------------------

    private fun toolbar(drawing: RouteDrawingUi, error: String? = null): Recorder {
        val r = Recorder()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { RouteToolbar(drawing, error, r.actions) } } }
        return r
    }

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
    fun `the toolbar takes no room when nothing is being drawn, and is there while a route is`() {
        var state by mutableStateOf(RoutesUiState())
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box { RouteToolbarWhileDrawing(state, Recorder().actions) } } }
        assertEquals(0, count("Finish"))
        state = RoutesUiState(drawing = RouteDrawingUi(2, true))
        compose.onNodeWithText("Finish").assertIsDisplayed()
    }

    @Test
    fun `while drawing, the panel says so and its error is the toolbar's`() {
        home(RoutesUiState(open = open(one), drawing = RouteDrawingUi(1, false), error = "That point could not be added."))
        compose.onNodeWithText("Drawing a route: 1 point", substring = true).assertIsDisplayed()
        assertEquals(0, count("That point could not be added."))                                   // said once, over the map, where the buttons are
    }

    // -- Naming ------------------------------------------------------------------------------------------------------------------

    private fun naming(open: OpenSetUi, others: List<String> = emptyList()): MutableList<String> {
        val saved = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                SaveRoutesContent(
                    open, others.mapIndexed { i, name -> "o$i" to name },
                    onSave = { name, replacing -> saved += if (replacing == null) name else "$name replacing $replacing" }, onCancel = { saved += "cancel" },
                )
            }
        }
        return saved
    }

    @Test
    fun `a set of one route is offered under the route's name, with what is saved`() {
        val saved = naming(open(one))
        compose.onNodeWithText("Save routes").assertIsDisplayed()
        compose.onNodeWithText("Saves the sketched routes with their plans.").assertIsDisplayed()
        compose.onNodeWithText("ROUTE 1").assertIsDisplayed()
        compose.onNodeWithText("You can rename it any time.").assertIsDisplayed()
        compose.onNodeWithText("1 route, 4 points, 4.2 nm").assertIsDisplayed()
        compose.onNodeWithText("Threats are never saved. Export them as a .ths if you need them next to the mission.").assertIsDisplayed()
        compose.onNodeWithText("Saves to your Library").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("ROUTE 1"), saved)
    }

    @Test
    fun `a name another set has offers keeping both, as the next free name, or replacing it`() {
        val saved = naming(open(one), others = listOf("ROUTE 1"))
        compose.onNodeWithText("Name is already used: you already have a set of routes with this name.").assertIsDisplayed()
        compose.onNodeWithText("Save this one as \u201cROUTE 1 2\u201d.").assertIsDisplayed()
        compose.onNodeWithText("Save as new").performClick()
        compose.onNodeWithText("Replace the saved one").performClick()
        compose.onNodeWithText("Replace").performClick()
        assertEquals(listOf("ROUTE 1", "ROUTE 1 replacing o0"), saved)
    }

    @Test
    fun `a set of several routes is offered under its made-up name, which is refused once Save is pressed`() {
        val saved = naming(open(one, hidden))
        compose.onNodeWithText("SKETCHED ROUTES").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText(RouteSetNames.MADE_UP_REFUSED).assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear the name").performClick()
        compose.onNodeWithText("Give it a name.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("night infil")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("NIGHT INFIL", "cancel"), saved)
    }
}
