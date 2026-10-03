package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The local points tab as a person meets it: the sets, how each is shown, and the point being held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PointsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        var renameProblem: String? = null
        val actions = PointsActions(
            importFile = { log += "import" }, dismissError = { log += "dismissError" }, dismissNote = { log += "dismissNote" },
            toggleVisible = { log += "toggle:$it" }, recolor = { id, color -> log += "recolor:$id:$color" },
            rename = { id, name -> log += "rename:$id:$name"; renameProblem }, delete = { log += "delete:$it" }, resolve = { id, how -> log += "resolve:$id:$how" },
            deselect = { log += "deselect" }, useForHeldRoutePoint = { log += "useFor" }, addToHeldRoute = { log += "addTo" },
        )
    }

    private fun content(state: PointsUiState, configure: Recorder.() -> Unit = {}): Recorder {
        val r = Recorder().apply(configure)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PointsContent(state, r.actions) } } }       // a window to scroll in: content that appears later must have room
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val north = PointSetRowUi("s1", "NORTH GA", 9, "#0A84FF", visible = true, sync = SyncStatus.SYNCED, conflictOf = null)
    private val hidden = PointSetRowUi("s2", "FARPS", 1, "#FF453A", visible = false, sync = SyncStatus.PENDING, conflictOf = null)

    private fun held(useFor: String? = null, addTo: String? = null, elevationFt: Double? = 1730.0, description: String = "Landing zone", group: String = "LZ") = HeldPointUi(
        setId = "s1", setName = "NORTH GA", pointId = "p1", rawName = "BLUE 1", at = LatLon(34.5123, -84.2231), elevationFt = elevationFt, description = description, group = group,
        grid = "16S GD 66993 52949", latLon = "34.51230, -84.22310", useFor = useFor, addTo = addTo,
    )

    // -- The list ------------------------------------------------------------------------------------------------

    @Test
    fun `with nothing saved it says how to begin, and Import starts the picker`() {
        val r = content(PointsUiState())
        compose.onNodeWithText("Local points").assertIsDisplayed()
        compose.onNodeWithText("No local points yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Import .LPS").performClick()
        assertEquals(listOf("import"), r.log)
    }

    @Test
    fun `a file being read says so, and cannot be started again`() {
        content(PointsUiState(importing = true))
        compose.onNodeWithText("Reading…").assertIsDisplayed()
        assertEquals(0, count("Import .LPS"))
    }

    @Test
    fun `each set shows its name, its points counted, whether it is hidden, and where it stands`() {
        content(PointsUiState(sets = listOf(north, hidden)))
        compose.onNodeWithText("NORTH GA").assertIsDisplayed()
        compose.onNodeWithText("9 points").assertIsDisplayed()
        compose.onNodeWithText("1 point · hidden").assertIsDisplayed()
        compose.onNodeWithText("Synced").assertIsDisplayed()
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
        assertEquals(0, count("No local points yet", substring = true))
        compose.onNodeWithContentDescription("Drawn in blue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawn in red").assertIsDisplayed()
    }

    @Test
    fun `a set is hidden and shown from its card, and each button says which set`() {
        val r = content(PointsUiState(sets = listOf(north, hidden)))
        compose.onNodeWithContentDescription("Hide NORTH GA on the map").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Show FARPS on the map").performScrollTo().performClick()
        assertEquals(listOf("toggle:s1", "toggle:s2"), r.log)
    }

    @Test
    fun `a colour is chosen from the palette, and the one in use is marked`() {
        val r = content(PointsUiState(sets = listOf(north)))
        compose.onAllNodesWithText("Colour")[0].performScrollTo().performClick()
        compose.onNodeWithContentDescription("Colour blue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Colour green").performScrollTo().performClick()
        assertEquals(listOf("recolor:s1:#32D74B"), r.log)
        assertEquals(0, count("Colour green"))                                                // the choices fold away once one is made
    }

    @Test
    fun `renaming sends what was typed, and a refusal is shown at the field`() {
        val r = content(PointsUiState(sets = listOf(north))) { renameProblem = "A set needs a name." }
        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextInput("X")
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        assertEquals(listOf("rename:s1:XNORTH GA"), r.log)                                    // the field starts with the name: typing goes in front of it
        compose.onNodeWithText("A set needs a name.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a rename that is taken closes the field`() {
        val r = content(PointsUiState(sets = listOf(north)))
        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        assertEquals(listOf("rename:s1:NORTH GA"), r.log)
        assertEquals(0, count("Save name"))
    }

    @Test
    fun `a set is deleted only after being asked, and keeping it is the prominent answer`() {
        val r = content(PointsUiState(sets = listOf(north)))
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertEquals(emptyList<String>(), r.log)                                              // asked first, nothing yet
        compose.onNodeWithText("Delete NORTH GA?", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep it").performScrollTo().performClick()
        assertEquals(emptyList<String>(), r.log)
        assertEquals(0, count("Keep it"))
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete set").performScrollTo().performClick()
        assertEquals(listOf("delete:s1"), r.log)
    }

    @Test
    fun `a conflict copy says so and offers the three ways to settle it`() {
        val copy = north.copy(uuid = "copy-1", name = "NORTH GA (from this device, 14:32)", sync = SyncStatus.CONFLICT, conflictOf = "s1")
        val r = content(PointsUiState(sets = listOf(north, copy)))
        compose.onNodeWithText("Conflict copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep both").performScrollTo().performClick()
        compose.onNodeWithText("Keep theirs (discard mine)").performScrollTo().performClick()
        assertEquals(listOf("resolve:copy-1:KEEP_BOTH", "resolve:copy-1:KEEP_THEIRS"), r.log)
    }

    // -- Messages -------------------------------------------------------------------------------------------------------

    @Test
    fun `an error and a note are shown and can be dismissed`() {
        val r = content(PointsUiState(error = "This .LPS file contains no readable points.", note = "Imported NORTH GA: 9 points."))
        compose.onNodeWithText("This .LPS file contains no readable points.").assertIsDisplayed()
        compose.onNodeWithText("Imported NORTH GA: 9 points.").assertIsDisplayed()
        compose.onAllNodesWithText("Dismiss")[0].performClick()
        compose.onAllNodesWithText("Dismiss")[1].performClick()
        assertEquals(listOf("dismissError", "dismissNote"), r.log)
    }

    // -- The held point -------------------------------------------------------------------------------------------------

    @Test
    fun `the held point shows what the file said of it, and Done puts it down`() {
        val r = content(PointsUiState(sets = listOf(north), held = held()))
        compose.onNodeWithText("Held").assertIsDisplayed()
        compose.onNodeWithText("BLUE 1").assertIsDisplayed()
        compose.onNodeWithText("16S GD 66993 52949").assertIsDisplayed()
        compose.onNodeWithText("34.51230, -84.22310").assertIsDisplayed()
        compose.onNodeWithText("Elevation 1,730 ft").assertIsDisplayed()
        compose.onNodeWithText("Group: LZ").assertIsDisplayed()
        compose.onNodeWithText("Landing zone").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("deselect"), r.log)
    }

    @Test
    fun `what the file did not say is not shown, and a point with no name is called so`() {
        content(PointsUiState(held = held(elevationFt = null, description = "", group = "").copy(rawName = "")))
        compose.onNodeWithText("(unnamed)").assertIsDisplayed()
        assertEquals(0, count("Elevation", substring = true))
        assertEquals(0, count("Group:", substring = true))
    }

    @Test
    fun `with a route to add to and a route point held, both are offered, each saying what it will do`() {
        val r = content(PointsUiState(held = held(useFor = "ROUTE 1: .SP", addTo = "ROUTE 1")))
        compose.onNodeWithText("Add to ROUTE 1").performScrollTo().performClick()
        compose.onNodeWithText("Use for ROUTE 1: .SP").performScrollTo().performClick()
        assertEquals(listOf("addTo", "useFor"), r.log)
        assertEquals(0, count("Open a set of routes", substring = true))
    }

    @Test
    fun `with no route to use it in, it says how to get one`() {
        content(PointsUiState(held = held()))
        compose.onNodeWithText("Open a set of routes", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(0, count("Add to", substring = true))
        assertEquals(0, count("Use for", substring = true))
    }
}
