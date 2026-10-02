package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.DiagramStatus
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
        )
    }

    private fun row(
        uuid: String = "u1", name: String = "LZ HAWK", status: DiagramStatus = DiagramStatus.TARGETED, grid: String? = "16S GD 66993 52949",
        sync: SyncStatus = SyncStatus.SYNCED, conflictOf: String? = null, active: Boolean = false,
    ) = DiagramRow(uuid, name, status, grid, sync, conflictOf, active)

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
}
