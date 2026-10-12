package app.ezpztac.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsFocused
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

/** The Library as a person meets it: the tabs and their counts, search and sort, each item with what can be done to it, and the asks before a delete and a name taken. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = LibraryActions(
        close = { log += "close" }, showTab = { log += "tab:$it" }, search = { log += "search:$it" }, sortBy = { log += "sort:$it" },
        open = { log += "open:${it.uuid}" }, rename = { row, name, replacing -> log += "rename:${row.uuid}:$name:$replacing" }, duplicate = { log += "duplicate:${it.uuid}" },
        delete = { log += "delete:${it.uuid}" }, resolve = { row, how -> log += "resolve:${row.uuid}:$how" },
    )

    private fun show(state: LibraryUiState) = compose.setContent { EzpzTheme(ThemeMode.Dark) { LibraryPage(state, actions) } }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val hawk = LibraryRowUi("lz-1", LibraryTab.LZ, "LZ HAWK (ROCK)", grid = "16S GC 31204 58811", updated = "Updated Oct 3 · 09:12", inSession = true)
    private val shope = LibraryRowUi("lz-2", LibraryTab.LZ, "LZ HAWK (SHOPE)", grid = "16S GC 24118 57290", updated = "Updated Aug 25 · 17:34")
    private val lzState = LibraryUiState(
        rows = listOf(hawk, shope), counts = mapOf(LibraryTab.LZ to 12, LibraryTab.ROUTES to 4, LibraryTab.POINTS to 3),
        names = mapOf(LibraryTab.LZ to listOf("lz-1" to "LZ HAWK (ROCK)", "lz-2" to "LZ HAWK (SHOPE)", "lz-3" to "LZ RAZORBILL")),
    )

    @Test
    fun `the tabs say how many each holds, the search what it looks in, and the list how many there are`() {
        show(lzState)
        compose.onNodeWithText("Everything you have saved. Opening one adds it to this session.").assertIsDisplayed()
        compose.onNodeWithContentDescription("LZ/PZ, 12").assertIsSelected()
        compose.onNodeWithContentDescription("Routes, 4").performClick()
        compose.onNodeWithText("Search LZ/PZs by name or grid").assertIsDisplayed()
        compose.onNodeWithText("12 saved LZ/PZs").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close Library").performClick()
        assertEquals(listOf("tab:ROUTES", "close"), log)
    }

    @Test
    fun `an item shows its grid, when it changed and whether it is open, and Open is for one not open yet`() {
        show(lzState)
        compose.onNodeWithText("16S GC 31204 58811").assertIsDisplayed()
        compose.onNodeWithText("Updated Oct 3 · 09:12").assertIsDisplayed()
        compose.onNodeWithText("Open in session").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open LZ HAWK (ROCK), already open in session").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open LZ HAWK (SHOPE)").assertIsEnabled().performClick()
        assertEquals(listOf("open:lz-2"), log)
    }

    @Test
    fun `a set of points shown on the map says so`() {
        show(LibraryUiState(tab = LibraryTab.POINTS, rows = listOf(LibraryRowUi("p1", LibraryTab.POINTS, "NORTH GA", detail = "9 points", inSession = true))))
        compose.onNodeWithText("On the map").assertIsDisplayed()
        compose.onNodeWithText("9 points").assertIsDisplayed()
    }

    @Test
    fun `the search is typed and cleared, and a search that finds nothing says so`() {
        show(lzState.copy(query = "zz", rows = emptyList()))
        compose.onNodeWithText("No saved LZ/PZ matches that name or grid.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("a")
        assertEquals(listOf("search:", "search:azz"), log)
    }

    @Test
    fun `the order is chosen from its menu`() {
        show(lzState)
        compose.onNodeWithContentDescription("Sort: Recently updated. Change sort").performClick()
        compose.onNodeWithText("Name A to Z").performClick()
        assertEquals(listOf("sort:NAME"), log)
    }

    @Test
    fun `an item is duplicated from its menu, and deleted only once the person says so`() {
        show(lzState)
        compose.onNodeWithContentDescription("More actions for LZ HAWK (SHOPE)").performClick()
        compose.onNodeWithText("Duplicate").performClick()
        compose.onNodeWithContentDescription("More actions for LZ HAWK (SHOPE)").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete “LZ HAWK (SHOPE)”?").assertIsDisplayed()
        compose.onNodeWithText("This removes it from your Library on every device you use.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("duplicate:lz-2"), log)
        compose.onNodeWithContentDescription("More actions for LZ HAWK (SHOPE)").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete")[0].performClick()                                     // the dialog's own
        assertEquals(listOf("duplicate:lz-2", "delete:lz-2"), log)
    }

    @Test
    fun `Rename in an item's menu opens its dialog`() {
        var renaming: String? = null
        compose.setContent { EzpzTheme(ThemeMode.Dark) { LibraryPage(lzState, actions, renaming = renaming, onRenaming = { renaming = it }) } }
        compose.onNodeWithContentDescription("More actions for LZ HAWK (SHOPE)").performClick()
        compose.onNodeWithText("Rename").performClick()
        assertEquals("lz-2", renaming)
    }

    /** The rename dialog's content, on its own: a text field in a dialog window never settles under the test harness. */
    private fun renaming(row: LibraryRowUi, others: List<Pair<String, String>>) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            LibraryRenameContent(row, others, onRename = { name, replacing -> log += "rename:${row.uuid}:$name:$replacing" }, onDismiss = { log += "dismiss" })
        }
    }

    @Test
    fun `a rename to a name another item has keeps both by default, or replaces it`() {
        renaming(shope, listOf("lz-1" to "LZ HAWK (ROCK)", "lz-3" to "LZ RAZORBILL"))
        compose.onNodeWithText("Rename LZ/PZ").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("lz razorbill")
        compose.onNodeWithText("Name is already used: you already have an LZ/PZ with this name.").assertIsDisplayed()
        compose.onNodeWithText("Save this one as \u201clz razorbill 2\u201d.").assertIsDisplayed()
        compose.onAllNodesWithText("Keep both")[1].performClick()                                   // the button; the first is the choice
        assertEquals(listOf("rename:lz-2:lz razorbill 2:null"), log)
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("LZ RAZORBILL")
        compose.onNodeWithText("Replace the saved one").performClick()
        compose.onNodeWithText("Replace").performClick()
        assertEquals("rename:lz-2:LZ RAZORBILL:lz-3", log.last())
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("dismiss", log.last())
    }

    @Test
    fun `the rename field opens with the name chosen and ready, and a name over the limit can always be made shorter`() {
        val long = "LZ " + "H".repeat(110)                                                      // a conflict copy's name can pass 100
        renaming(shope.copy(name = long), emptyList())
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performTextReplacement(long.dropLast(1))                // shorter: taken
        compose.onNodeWithText(long.dropLast(1)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement(long)                            // longer again, past the limit: not taken
        compose.onNodeWithText(long.dropLast(1)).assertIsDisplayed()
    }

    @Test
    fun `a made-up name or none is refused at the field, and nothing is renamed`() {
        renaming(shope, emptyList())
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("LZ/PZ 4")
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText(LzNames.MADE_UP_REFUSED).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Give it a name.").assertIsDisplayed()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a set of routes is renamed in capitals, as the web writes them, and a set of points as typed`() {
        renaming(LibraryRowUi("r1", LibraryTab.ROUTES, "NIGHT INFIL"), emptyList())
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("day exfil")
        compose.onNodeWithText("Rename").performClick()
        assertEquals(listOf("rename:r1:DAY EXFIL:null"), log)
        assertEquals("North GA", cleanName(LibraryTab.POINTS, " North GA "))
    }

    @Test
    fun `a conflict copy says so, cannot be opened as it is, and offers its three choices`() {
        val copy = LibraryRowUi("c1", LibraryTab.LZ, "LZ HAWK (from this device, 14:32)", grid = "16S GC 1 1", sync = SyncStatus.CONFLICT, conflictOf = "lz-1")
        show(LibraryUiState(rows = listOf(copy)))
        compose.onNodeWithText("Conflict copy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open LZ HAWK (from this device, 14:32)").assertIsNotEnabled()
        compose.onNodeWithText("Keep both").performClick()
        assertEquals(listOf("resolve:c1:${SyncEngine.Resolution.KEEP_BOTH}"), log)
    }

    @Test
    fun `an account with one kind of item has no tabs, and an empty tab says how to begin`() {
        show(LibraryUiState(tab = LibraryTab.LZ, tabs = listOf(LibraryTab.LZ)))
        assertEquals(0, count("Routes"))
        compose.onNodeWithText("No LZ/PZs saved yet. Make one in the LZ/PZ panel.").assertIsDisplayed()
        compose.onNodeWithText("0 saved LZ/PZs").assertIsDisplayed()
    }
}
