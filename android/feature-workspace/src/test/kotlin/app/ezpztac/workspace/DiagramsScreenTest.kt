package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.model.DiagramStatus
import app.ezpztac.planning.LzSummary
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Diagrams tab as a person meets it: what is shown, what a tap does, and what a tap must not do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class DiagramsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val actions = DiagramsActions(
            startCreating = { log += "start" },
            cancelCreating = { log += "cancel" },
            create = { n, t -> log += "create [$n] [$t]" },
            open = { log += "open $it" },
            rename = { u, n -> log += "rename $u [$n]" },
            delete = { log += "delete $it" },
            resolve = { u, r -> log += "resolve $u $r" },
            dismissError = { log += "dismiss" },
            analyze = { log += "analyze" },
            stopAnalysis = { log += "stop" },
            dismissAnalysis = { log += "dismiss analysis" },
            selectAircraft = { log += "aircraft $it" },
        )
    }

    private fun row(
        uuid: String = "u1", name: String = "LZ HAWK", status: DiagramStatus = DiagramStatus.TARGETED, grid: String? = "16S GD 66993 52949",
        sync: SyncStatus = SyncStatus.SYNCED, conflictOf: String? = null, isActive: Boolean = false,
    ) = DiagramRow(uuid, name, status, grid, sync, conflictOf, isActive)

    private fun show(state: DiagramsUiState, suggested: String? = null, recorder: Recorder = Recorder()): Recorder {
        // The sheet that holds the tab scrolls; so does this, or an item below the screen could not be reached.
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { DiagramsContent(state, suggested, recorder.actions) } }
        }
        return recorder
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    // -- The list ------------------------------------------------------------------------------------

    @Test
    fun `with nothing made it says how to start, and offers to`() {
        val r = show(DiagramsUiState())
        compose.onNodeWithText("No diagrams yet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("New diagram").performClick()
        assertEquals(listOf("start"), r.log)
    }

    @Test
    fun `a row shows its name, status, grid and whether the server has it`() {
        show(DiagramsUiState(rows = listOf(row(), row("u2", "LZ CROW", DiagramStatus.ANALYZED, null, SyncStatus.PENDING))))
        compose.onNodeWithText("LZ HAWK").assertIsDisplayed()
        compose.onNodeWithText("16S GD 66993 52949").assertIsDisplayed()
        compose.onNodeWithText("Targeted").assertIsDisplayed()
        compose.onNodeWithText("Synced").assertIsDisplayed()
        compose.onNodeWithText("LZ CROW").assertIsDisplayed()
        compose.onNodeWithText("Analyzed").assertIsDisplayed()                              // no grid: the status alone
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("No diagrams yet.", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `tapping a row opens that diagram`() {
        val r = show(DiagramsUiState(rows = listOf(row("u1", "one"), row("u2", "two"))))
        compose.onNodeWithText("two").performClick()
        assertEquals(listOf("open u2"), r.log)
    }

    // -- Making one ----------------------------------------------------------------------------------

    @Test
    fun `the form starts with the grid under the crosshair, and sends what is in it`() {
        val r = show(DiagramsUiState(creating = true), suggested = "16S GD 66993 52949")
        compose.onNodeWithText("16S GD 66993 52949").assertIsDisplayed()
        field("Name (optional)").performTextInput("  LZ HAWK ")
        compose.onNodeWithText("Create diagram").performScrollTo().performClick()
        assertEquals(listOf("create [  LZ HAWK ] [16S GD 66993 52949]"), r.log)            // trimming is the model's job, not the screen's
    }

    @Test
    fun `the target can be changed before making the diagram`() {
        val r = show(DiagramsUiState(creating = true), suggested = "16S GD 66993 52949")
        field("Target (MGRS grid or lat/long)").performTextClearance()
        field("Target (MGRS grid or lat/long)").performTextInput("34.78, -84.08")
        compose.onNodeWithText("Create diagram").performScrollTo().performClick()
        assertEquals(listOf("create [] [34.78, -84.08]"), r.log)
    }

    @Test
    fun `cancelling the form says so, and while the form is open there is no second New button`() {
        val r = show(DiagramsUiState(creating = true))
        assertEquals(0, compose.onAllNodesWithText("New diagram").fetchSemanticsNodes().size)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), r.log)
    }

    @Test
    fun `while a diagram is being made a tap on Create does nothing`() {
        val r = show(DiagramsUiState(creating = true, busy = true), suggested = "16S GD 66993 52949")
        compose.onNodeWithText("Creating…").performScrollTo().performClick()
        assertEquals(emptyList<String>(), r.log)
    }

    // -- Renaming and deleting -----------------------------------------------------------------------

    @Test
    fun `rename opens a field with the current name and sends the new one`() {
        val r = show(DiagramsUiState(rows = listOf(row())))
        compose.onNodeWithText("Rename").performClick()
        field("Name").performTextClearance()
        field("Name").performTextInput("LZ CROW")
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("rename u1 [LZ CROW]"), r.log)
        compose.onNodeWithText("Rename").assertIsDisplayed()                                // back to the row's usual actions
    }

    @Test
    fun `cancelling a rename sends nothing`() {
        val r = show(DiagramsUiState(rows = listOf(row())))
        compose.onNodeWithText("Rename").performClick()
        field("Name").performTextInput("x")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun `delete asks first, and keeping it sends nothing`() {
        val r = show(DiagramsUiState(rows = listOf(row())))
        compose.onNodeWithText("Delete").performClick()
        assertEquals(emptyList<String>(), r.log)                                            // one tap deletes nothing
        compose.onNodeWithText("Delete \"LZ HAWK\"?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun `confirming the delete sends it for that diagram`() {
        val r = show(DiagramsUiState(rows = listOf(row("u1", "one"), row("u2", "two"))))
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.onAllNodesWithText("Delete")[1].performClick()                              // the other row still shows its own Delete before it
        assertEquals(listOf("delete u2"), r.log)
    }

    // -- Conflicts and errors ------------------------------------------------------------------------

    private fun settle(label: String, resolution: SyncEngine.Resolution) {
        val r = show(DiagramsUiState(rows = listOf(row("copy", "LZ HAWK (from this device, 14:32)", conflictOf = "u1", sync = SyncStatus.PENDING))))
        compose.onNodeWithText("Conflict copy").assertIsDisplayed()
        compose.onNodeWithText("Nothing was overwritten.", substring = true).assertIsDisplayed()
        compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(listOf("resolve copy $resolution"), r.log)
    }

    @Test fun `a conflict copy can keep both`() = settle("Keep both", SyncEngine.Resolution.KEEP_BOTH)
    @Test fun `a conflict copy can keep mine`() = settle("Keep mine (replaces the other version)", SyncEngine.Resolution.KEEP_MINE)
    @Test fun `a conflict copy can keep theirs`() = settle("Keep theirs (discard mine)", SyncEngine.Resolution.KEEP_THEIRS)

    @Test
    fun `an ordinary row offers no conflict choices`() {
        show(DiagramsUiState(rows = listOf(row())))
        assertEquals(0, compose.onAllNodesWithText("Keep both").fetchSemanticsNodes().size)
    }

    @Test
    fun `an error is shown and can be dismissed`() {
        val r = show(DiagramsUiState(error = "That diagram is no longer here."))
        compose.onNodeWithText("That diagram is no longer here.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }

    // -- The open diagram and its analysis ----------------------------------------------------------------------------------

    private fun open(
        status: DiagramStatus = DiagramStatus.TARGETED, analysis: AnalysisUi = AnalysisUi.Idle, summary: SummaryUi? = null, canAnalyze: Boolean = true,
        sync: SyncStatus? = SyncStatus.SYNCED, conflictOf: String? = null, aircraft: AircraftUi = AircraftUi.UH60L, weather: WeatherUi? = null,
    ) = ActiveDiagramUi("u1", "LZ HAWK", status, "16S GD 66993 52949", canAnalyze, analysis, summary, weather, sync, conflictOf, aircraft)

    private fun tiles(slope: SlopeTileUi = SlopeTileUi.Measured(LzSummary.SlopeCall(LzSummary.SlopeLevel.SAFE, "LANDING", 4.2), "local_highres_cog", 10.2)) =
        SummaryUi("UH-60L", 13, 854_831, "4050", slope)

    @Test
    fun `a diagram that has not been analysed offers to analyse it`() {
        val r = show(DiagramsUiState(current = open(), rows = listOf(row(isActive = true))))
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Analyze landing zone").performScrollTo().performClick()
        assertEquals(listOf("analyze"), r.log)
    }

    @Test
    fun `while the area is sought the button says so and does nothing, and Stop stops`() {
        val r = show(DiagramsUiState(current = open(analysis = AnalysisUi.Running(AnalysisStatus.Running.Stage.FINDING_AREA))))
        compose.onNodeWithText("Finding the landing area…").performScrollTo().performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Stop").performScrollTo().performClick()
        assertEquals(listOf("stop"), r.log)
        compose.onNodeWithText("Needs a connection", substring = true).assertIsDisplayed()
    }

    @Test
    fun `while the slope is measured the button says that`() {
        show(DiagramsUiState(current = open(DiagramStatus.ANALYZED, AnalysisUi.Running(AnalysisStatus.Running.Stage.MEASURING_SLOPE), tiles(SlopeTileUi.Measuring))))
        compose.onNodeWithText("Measuring the slope…").assertIsDisplayed()
        compose.onNodeWithText("Measuring…").assertIsDisplayed()                              // and the tile is waiting too
    }

    @Test
    fun `a failure is shown with the way to try again`() {
        val r = show(DiagramsUiState(current = open(analysis = AnalysisUi.Failed("There is no connection to the server."))))
        compose.onNodeWithText("There is no connection to the server.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(listOf("dismiss analysis", "analyze"), r.log)
    }

    @Test
    fun `an analysed diagram shows its summary, and analysing again is the quieter choice`() {
        val r = show(DiagramsUiState(current = open(DiagramStatus.ANALYZED, summary = tiles())))
        compose.onNodeWithText("Capacity · UH-60L").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("13").assertIsDisplayed()
        compose.onNodeWithText("854,831").assertIsDisplayed()                                 // thousands, written here, not by the platform
        compose.onNodeWithText("4050 ft").assertIsDisplayed()
        compose.onNodeWithText("4.2°").assertIsDisplayed()
        compose.onNodeWithText("LANDING").assertIsDisplayed()
        compose.onNodeWithText("local_highres_cog · 10.2 m cells").assertIsDisplayed()        // where the numbers came from
        compose.onNodeWithText("Analyze again").performScrollTo().performClick()
        assertEquals(listOf("analyze"), r.log)
        assertEquals(0, compose.onAllNodesWithText("Analyze landing zone").fetchSemanticsNodes().size)
    }

    @Test
    fun `a slope that could not be measured says why`() {
        show(DiagramsUiState(current = open(DiagramStatus.ANALYZED, summary = tiles(SlopeTileUi.Unavailable("No terrain data covers this landing zone.")))))
        compose.onNodeWithText("No terrain data covers this landing zone.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("13").assertIsDisplayed()                                       // the rest of the summary stands
    }

    @Test
    fun `an elevation the server could not give is shown as it is, and none as a dash`() {
        show(DiagramsUiState(current = open(DiagramStatus.ANALYZED, summary = tiles().copy(elevation = "TBD"))))
        compose.onNodeWithText("TBD").assertIsDisplayed()
    }

    @Test
    fun `a diagram with no target says what it needs, and offers nothing to run`() {
        show(DiagramsUiState(current = open(canAnalyze = false)))
        compose.onNodeWithText("Set a target to analyze this landing zone.").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Analyze landing zone").fetchSemanticsNodes().size)
    }

    @Test
    fun `the slope call is spoken as well as coloured`() {
        show(DiagramsUiState(current = open(DiagramStatus.ANALYZED, summary = tiles(SlopeTileUi.Measured(LzSummary.SlopeCall(LzSummary.SlopeLevel.DANGER, "LIMIT EXCEEDED", 16.04), "terrarium", 19.0)))))
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Maximum terrain slope 16.0 degrees. LIMIT EXCEEDED.")).performScrollTo().assertIsDisplayed()
    }

    // -- Numbers ------------------------------------------------------------------------------------------------------------

    @Test
    fun `thousands are grouped by hand`() {
        assertEquals("0", withCommas(0))
        assertEquals("999", withCommas(999))
        assertEquals("1,000", withCommas(1_000))
        assertEquals("854,831", withCommas(854_831))
        assertEquals("47,183,726,349", withCommas(47_183_726_349))
        assertEquals("-1,234", withCommas(-1_234))
    }

    @Test
    fun `one decimal is toFixed's, from the exact value with ties up`() {
        assertEquals("4.2", oneDecimal(4.2))
        assertEquals("4.3", oneDecimal(4.25))                                                  // exactly representable, a true tie: up
        assertEquals("1.1", oneDecimal(1.05))                                                  // 1.0500000000000000444: above the tie
        assertEquals("1.1", oneDecimal(1.15))                                                  // 1.149999999999999911: below it, and so it reads 1.1
        assertEquals("0.0", oneDecimal(0.0))
        assertEquals("10.0", oneDecimal(10.0))
        assertEquals("-3.5", oneDecimal(-3.5))
    }

    // -- One home for the open diagram ----------------------------------------------------------------------------------------

    @Test
    fun `the open diagram is in its card, not again in the list, and the list is the others`() {
        show(DiagramsUiState(current = open(), rows = listOf(row("u1", "LZ HAWK", isActive = true), row("u2", "PZ OAK"))))
        assertEquals(1, compose.onAllNodesWithText("LZ HAWK").fetchSemanticsNodes().size)
        compose.onNodeWithText("Other diagrams").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("PZ OAK").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with no other diagrams there is no list heading`() {
        show(DiagramsUiState(current = open(), rows = listOf(row("u1", "LZ HAWK", isActive = true))))
        assertEquals(0, compose.onAllNodesWithText("Other diagrams").fetchSemanticsNodes().size)
    }

    @Test
    fun `the card renames and deletes the open diagram`() {
        val r = show(DiagramsUiState(current = open(), rows = listOf(row("u1", "LZ HAWK", isActive = true))))
        compose.onNodeWithText("Rename").performScrollTo().performClick()
        field("Name").performTextClearance()
        field("Name").performTextInput("LZ CROW")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()                                       // the confirmation, in place of the question
        assertEquals(listOf("rename u1 [LZ CROW]", "delete u1"), r.log)
    }

    @Test
    fun `the card says whether the server has the diagram`() {
        show(DiagramsUiState(current = open(sync = SyncStatus.PENDING), rows = emptyList()))
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
    }

    @Test
    fun `an open conflict copy keeps its row, so its choices are not lost`() {
        show(DiagramsUiState(current = open(conflictOf = "u0"), rows = listOf(row("u1", "LZ HAWK (from this device)", conflictOf = "u0", isActive = true))))
        compose.onNodeWithText("Keep both").performScrollTo().assertIsDisplayed()
    }
// -- The mission aircraft ----------------------------------------------------------------------------------------------

    private val fleet = AircraftUi(
        options = listOf(AircraftOptionUi("uh60l", "UH-60L — UH-60L Black Hawk"), AircraftOptionUi("ch47f", "CH-47F — CH-47F Chinook"), AircraftOptionUi("sct", "SCT — Scout (yours)")),
        activeSlug = "uh60l", activeLabel = "UH-60L — UH-60L Black Hawk", spacingM = 76, cruiseKts = 100, unverified = false, waiting = emptyList(),
    )

    @Test
    fun `the open diagram shows its mission aircraft with its spacing and speed`() {
        show(DiagramsUiState(current = open(aircraft = fleet), rows = listOf(row(isActive = true))))
        compose.onNodeWithContentDescription("Mission aircraft: UH-60L — UH-60L Black Hawk").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("76 m spacing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("100 kt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unverified performance").assertDoesNotExist()
    }

    @Test
    fun `another airframe is chosen from the menu`() {
        val r = show(DiagramsUiState(current = open(aircraft = fleet), rows = listOf(row(isActive = true))))
        compose.onNodeWithContentDescription("Mission aircraft: UH-60L — UH-60L Black Hawk").performScrollTo().performClick()
        compose.onNodeWithText("CH-47F — CH-47F Chinook").performClick()
        assertEquals(listOf("aircraft ch47f"), r.log)
    }

    @Test
    fun `a spec sheet's numbers are called that, in words`() {
        show(DiagramsUiState(current = open(aircraft = fleet.copy(activeSlug = "ch47f", activeLabel = "CH-47F — CH-47F Chinook", unverified = true)), rows = listOf(row(isActive = true))))
        compose.onNodeWithText("Unverified performance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("These numbers are from published specifications", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an own profile waiting for the server is named, and cannot be chosen`() {
        show(DiagramsUiState(current = open(aircraft = fleet.copy(waiting = listOf("Draft", "Other"))), rows = listOf(row(isActive = true))))
        compose.onNodeWithText("Waiting to sync before it can be chosen: Draft, Other").performScrollTo().assertIsDisplayed()
    }
}
