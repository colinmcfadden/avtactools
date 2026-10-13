package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import app.ezpztac.data.PlaceTool
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.GraphicRef
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val GRID = "16S GC 28864 55349"

/** An open LZ/PZ as the panel shows it: analysed and still to be named, unless the test says otherwise. */
internal fun activeLz(
    name: String = "LZ/PZ 1",
    status: DiagramStatus = DiagramStatus.ANALYZED,
    sync: SyncStatus = SyncStatus.SYNCED,
    slopeShown: Boolean? = true,
): ActiveDiagramUi {
    val madeUp = LzNames.isMadeUp(name)
    return ActiveDiagramUi(
        uuid = "lz-1", name = name, status = status, grid = GRID, canAnalyze = true, analysis = AnalysisUi.Idle, summary = null, sync = sync, conflictOf = null,
        madeUpName = madeUp, chips = lzChips(status, sync, null), slopeShown = slopeShown.takeIf { status == DiagramStatus.ANALYZED },
        saveContents = listOf("Boundary, target $GRID and slope analysis", "3 helicopters, 2 sectors, doghouses and flight data"),
    )
}

internal fun lzRow(uuid: String, name: String, grid: String, status: DiagramStatus = DiagramStatus.ANALYZED, sync: SyncStatus = SyncStatus.SYNCED, conflictOf: String? = null) =
    DiagramRow(uuid, name, status, grid, sync, conflictOf, isActive = false, chips = lzChips(status, sync, conflictOf))

/** The panel as the design draws it: one open and named or not, two also open, and the Library's recent ones. */
internal fun homeState(current: ActiveDiagramUi? = activeLz()) = DiagramsUiState(
    current = current,
    open = listOf(lzRow("lz-2", "LZ HAWK (ROCK)", "16S GC 31204 58811"), lzRow("lz-3", "LZ/PZ 3", "16S GC 27390 54102", DiagramStatus.TARGETED)),
    openCount = if (current != null) 3 else 2,
    recent = listOf(RecentLz("lz-4", "LZ HAWK (SHOPE)", "Aug 25 · 17:34"), RecentLz("lz-5", "LZ RAZORBILL", "Aug 25 · 17:27")),
    rows = listOf(lzRow("lz-1", "LZ/PZ 1", GRID), lzRow("lz-2", "LZ HAWK (ROCK)", "16S GC 31204 58811"), lzRow("lz-4", "LZ HAWK (SHOPE)", "16S GC 1 1")),
    otherNames = listOf("LZ HAWK (ROCK)", "LZ/PZ 3", "LZ HAWK (SHOPE)", "LZ RAZORBILL"),
)

/** The LZ/PZ panel's pages: what they show and what their buttons ask for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class LzPanelScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()

    private val actions = LzPanelActions(
        open = { log += "open $it" }, newHere = { log += "new" }, close = { log += "close $it" }, rename = { id, name -> log += "rename $id $name" },
        delete = { log += "delete $it" }, save = { log += "save" }, showPage = { log += "page $it" }, browse = { log += "browse" }, analyze = { log += "analyze" },
        toggleSlope = { log += "slope" }, beginTool = { log += "tool $it" }, drawBoundary = { log += "draw" }, selectAircraft = { log += "aircraft $it" },
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { content() } }
        }
    }

    // -- Home ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the open one is a card with its grid and chips, and one still to be named offers Save`() {
        show { LzHomeContent(homeState(), actions, GRID) }
        compose.onNodeWithText("LZ/PZ 1").assertIsDisplayed()
        compose.onNodeWithText("Analyzed").assertIsDisplayed()
        compose.onAllNodesWithText("Synced").onFirst().assertIsDisplayed()                       // the card's chip (the rows say it too)
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.onNodeWithText("Plan").performClick()
        assertEquals(listOf("save", "page PLAN"), log)
    }

    @Test
    fun `one already named says it is saved, and cannot be saved again`() {
        show { LzHomeContent(homeState(activeLz(name = "LZ HAWK (BETA)")), actions, GRID) }
        compose.onNodeWithText("Saved").assertIsNotEnabled()
    }

    @Test
    fun `one with a name of its own but not analysed is not shown as saved`() {
        show { LzHomeContent(homeState(activeLz(name = "LZ HAWK", status = DiagramStatus.TARGETED)), actions, GRID) }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onAllNodesWithText("Analyze to save")[0].assertIsDisplayed()
    }

    @Test
    fun `renaming says at the field why a made-up name will not do, and an unchanged name changes nothing`() {
        show { LzHomeContent(homeState(activeLz(name = "LZ HAWK (BETA)")), actions, GRID) }
        compose.onNodeWithContentDescription("Rename LZ HAWK (BETA)").performClick()
        compose.onNodeWithText("Save name").performClick()                                         // unchanged: the field closes
        compose.onNodeWithText("Save name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rename LZ HAWK (BETA)").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("LZ/PZ 4")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText(LzNames.MADE_UP_REFUSED).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("LZ RAVEN")
        compose.onNodeWithText(LzNames.MADE_UP_REFUSED).assertDoesNotExist()                     // typing clears it
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("rename lz-1 LZ RAVEN"), log)
    }

    @Test
    fun `one not analysed yet cannot be named, and its chip says why`() {
        show { LzHomeContent(homeState(activeLz(status = DiagramStatus.TARGETED)), actions, GRID) }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onAllNodesWithText("Analyze to save").onFirst().assertIsDisplayed()
    }

    @Test
    fun `the others open are a tap away, and so are the Library's recent ones`() {
        show { LzHomeContent(homeState(), actions, GRID) }
        compose.onNodeWithText("LZ HAWK (ROCK)").performClick()
        compose.onNodeWithContentDescription("Open LZ HAWK (SHOPE)").performScrollTo().performClick()
        compose.onNodeWithText("Browse all").performScrollTo().performClick()
        assertEquals(listOf("open lz-2", "open lz-4", "browse"), log)       // the Library, on its LZ/PZ tab
    }

    @Test
    fun `a new LZ-PZ starts at the crosshair, which the row names`() {
        show { LzHomeContent(homeState(), actions, GRID) }
        compose.onNodeWithText("New LZ/PZ at the crosshair").performScrollTo().performClick()
        compose.onNode(hasText("New LZ/PZ at the crosshair") and hasText(GRID)).assertIsDisplayed()
        assertEquals(listOf("new"), log)
    }

    @Test
    fun `with nothing open it says how to start`() {
        show { LzHomeContent(DiagramsUiState(), actions, null) }
        compose.onNodeWithText("No LZ/PZ open", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Move the map to where it should be").assertIsDisplayed()
    }

    @Test
    fun `close takes it out of the open ones, and delete asks first with keeping it the prominent answer`() {
        show { LzHomeContent(homeState(), actions, GRID) }
        compose.onNodeWithContentDescription("More actions for LZ/PZ 1").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("More actions for LZ/PZ 1").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"LZ/PZ 1\"?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(listOf("close lz-1"), log)
    }

    @Test
    fun `the person's own LZ is added to a Mission Pack from its menu, analysed or not, as the Library's are`() {
        val withPacks = LzPanelActions(addToPack = { uuid, name -> log += "pack $uuid $name" })
        show { LzHomeContent(homeState(activeLz(status = DiagramStatus.TARGETED)), withPacks, GRID) }
        compose.onNodeWithContentDescription("More actions for LZ/PZ 1").performClick()
        compose.onNodeWithText("Add to Mission Pack…").performClick()
        assertEquals(listOf("pack lz-1 LZ/PZ 1"), log)
    }

    @Test
    fun `without packs here none is offered`() {
        show { LzHomeContent(homeState(), actions, GRID) }
        compose.onNodeWithContentDescription("More actions for LZ/PZ 1").performClick()
        compose.onNodeWithText("Add to Mission Pack…").assertDoesNotExist()
    }

    @Test
    fun `a conflict waiting for a choice is said at the top, and leads to it`() {
        val state = homeState().let { it.copy(rows = it.rows + lzRow("copy", "LZ HAWK (from this device, 14:32)", GRID, conflictOf = "lz-2")) }
        show { LzHomeContent(state, actions, GRID) }
        compose.onNodeWithText("1 LZ/PZ needs a choice", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Review").performClick()
        assertEquals(listOf("browse"), log)                                                  // the Library, where conflicts are settled
    }

    // -- Plan ------------------------------------------------------------------------------------------------------------------

    private fun plan(current: ActiveDiagramUi = activeLz(), canEdit: Boolean = true, canDraw: Boolean = true, placing: PlacementUi? = null) {
        show {
            LzPlanContent(
                current, GraphicsUiState(listed = canEdit, canEdit = canEdit), BoundaryUiState(canStart = canDraw, clears = current.status == DiagramStatus.ANALYZED), placing,
                actions, GraphicsActions(), GRID,
            )
        }
    }

    @Test
    fun `a tool turns its mode on`() {
        plan()
        compose.onNodeWithContentDescription("Place helicopters").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Place right go-arounds").performScrollTo().performClick()
        assertEquals(listOf("tool HELICOPTER", "tool GO_AROUND_RIGHT"), log)
    }

    @Test
    fun `before an analysis only Draw LZ can be used, and the plan says why`() {
        plan(activeLz(status = DiagramStatus.TARGETED), canEdit = false)
        compose.onNodeWithContentDescription("Place helicopters").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").assertIsEnabled().performClick()
        compose.onNodeWithText("Analyze the LZ/PZ to place", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Analyze").performScrollTo().performClick()
        assertEquals(listOf("draw", "analyze"), log)
    }

    @Test
    fun `drawing a boundary over an analysis asks first`() {
        plan()
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").performScrollTo().performClick()
        compose.onNodeWithText("Drawing a new boundary clears this analysis", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Draw").performClick()
        assertEquals(listOf("draw"), log)
    }

    @Test
    fun `the slope map switch turns the heat map off and on`() {
        plan()
        compose.onNode(isToggleable() and hasText("Slope map")).performScrollTo().assertIsOn().performClick()   // one target, said with its name
        assertEquals(listOf("slope"), log)
    }

    @Test
    fun `the mission aircraft is one row, a tap offers the others, and Manage opens the profiles`() {
        val two = AircraftUi.UH60L.copy(options = AircraftUi.UH60L.options + AircraftOptionUi("ch47f", "CH-47F — CH-47F Chinook"))
        plan(activeLz().copy(aircraft = two))
        compose.onNodeWithContentDescription("Mission aircraft: UH-60L — UH-60L Black Hawk").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText("UH-60L — UH-60L Black Hawk") and hasAnyAncestor(isPopup())).assertIsSelected().performClick()   // chosen already: nothing to ask
        compose.onNodeWithContentDescription("Mission aircraft: UH-60L — UH-60L Black Hawk").performClick()
        compose.onNode(hasText("CH-47F — CH-47F Chinook") and hasAnyAncestor(isPopup())).assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Manage aircraft profiles").performClick()
        assertEquals(listOf("aircraft ch47f", "page AIRCRAFT"), log)
    }

    @Test
    fun `unverified numbers are said to be, in words`() {
        plan(activeLz().copy(aircraft = AircraftUi.UH60L.copy(unverified = true)))
        compose.onNodeWithText("Unverified performance", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a held graphic's controls are brought into view, however far down the plan they are`() {
        val rows = (1..12).map { GraphicRowUi(GraphicRef("sectorsOfFire", "sec-$it"), GraphicKind.SECTOR_OF_FIRE, "Sector $it", GRID, null, warning = false, selected = it == 12) }
        val held = InspectorUi(GraphicRef("sectorsOfFire", "sec-12"), GraphicKind.SECTOR_OF_FIRE, "The held sector", GRID, null, rotation = null, reachFt = null, direction = null)
        show {
            LzPlanContent(
                activeLz(), GraphicsUiState(listed = true, canEdit = true, rows = rows, inspector = held), BoundaryUiState(canStart = true, clears = true), null,
                actions, GraphicsActions(), GRID,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("The held sector").assertIsDisplayed()
    }

    @Test
    fun `the tool whose mode is on is marked`() {
        plan(placing = PlacementUi(PlaceTool.PZ_MARKER, 2, null))
        compose.onNodeWithContentDescription("Place PZ markers").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Place helicopters").assertIsNotSelected()
    }

    // -- Naming ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the made-up name the sheet offers cannot be what it is saved as`() {
        var saved: String? = null
        compose.setContent { EzpzTheme(ThemeMode.Dark) { SaveLzContent(activeLz(), emptyList(), onSave = { name, _ -> saved = name }, onCancel = {}) } }
        compose.onNodeWithText("You can rename it any time.").assertIsDisplayed()                 // offered to type over, not an error
        compose.onNode(hasText("Save") and hasText("Saves to your Library").not()).performClick()
        compose.onNodeWithText(LzNames.MADE_UP_REFUSED).assertIsDisplayed()
        assertEquals(null, saved)
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("LZ HAWK")
        compose.onNode(hasText("Save") and hasText("Saves to your Library").not()).performClick()
        assertEquals("LZ HAWK", saved)
    }

    @Test
    fun `naming offers the made-up name to type over, and says what is kept`() {
        var saved: Pair<String, String?>? = null
        // The sheet's content scrolls itself, as the modal sheet it lives in gives it a height.
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { SaveLzContent(activeLz(), listOf("lz-9" to "LZ HAWK"), onSave = { name, replacing -> saved = name to replacing }, onCancel = { log += "cancel" }) }
        }
        compose.onNodeWithText("Boundary, target $GRID and slope analysis").assertIsDisplayed()
        compose.onNodeWithText("Threats and routes are not included", substring = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Give it a name.").assertIsDisplayed()
        compose.onNode(hasText("Save") and hasText("Saves to your Library").not()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("LZ HAWK")
        // A name the Library has: keep both, this one as the next free name (the default), or replace the other (the design's Confirm A).
        compose.onNodeWithText("Name is already used: you already have an LZ/PZ with this name.").assertIsDisplayed()
        compose.onNodeWithText("Save this one as \u201cLZ HAWK 2\u201d.").assertIsDisplayed()
        compose.onNodeWithText("Save as new").performClick()
        assertEquals("LZ HAWK" to null, saved)                                                   // the view model gives it the free name
        compose.onNodeWithText("Replace the saved one").performClick()
        compose.onNodeWithText("Replace").performClick()
        assertEquals("LZ HAWK" to "lz-9", saved)
    }
}
