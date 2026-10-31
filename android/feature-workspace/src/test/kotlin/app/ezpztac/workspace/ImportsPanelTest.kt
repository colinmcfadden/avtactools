package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.data.ThreatFileImport
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Imports panel as a person meets it: what was brought in by kind, how each set is shown and kept, and the point being held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ImportsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder(routes: Boolean) {
        val log = mutableListOf<String>()
        var renameProblem: String? = null
        val actions = ImportsActions(
            toggleVisible = { log += "toggle:$it" }, recolor = { id, color -> log += "recolor:$id:$color" },
            rename = { id, name -> log += "rename:$id:$name"; renameProblem }, delete = { log += "delete:$it" },
            removeFromSession = { log += "remove:$it" }, saveToLibrary = { log += "save:$it" }, resolve = { id, how -> log += "resolve:$id:$how" },
            deselect = { log += "deselect" }, useForHeldRoutePoint = { log += "useFor" }, addToHeldRoute = { log += "addTo" },
            openMission = if (routes) { id -> log += "mission:$id" } else null, openThreats = { log += "threats" },
        )
    }

    private fun content(state: PointsUiState, routes: Boolean = true, configure: Recorder.() -> Unit = {}): Recorder {
        val r = Recorder(routes).apply(configure)
        // A window to scroll in: content that appears later must have room.
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ImportsContent(state, r.actions) } } }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val north = PointSetRowUi("s1", "NORTH GA", 1234, "#0A84FF", visible = true, sync = SyncStatus.SYNCED, conflictOf = null)
    private val taxi = PointSetRowUi("s2", "KGVL TAXI", 46, "#FF453A", visible = false, sync = SyncStatus.PENDING, conflictOf = null, inSession = true)
    private val mission = MissionFileUi("m1", "GOAT_SUCKER", "GOAT_SUCKER.msnx", 2, conflictOf = null)

    private fun held(useFor: String? = null, addTo: String? = null, elevationFt: Double? = 1730.0, description: String = "Landing zone", group: String = "LZ") = HeldPointUi(
        setId = "s1", setName = "NORTH GA", pointId = "p1", rawName = "BLUE 1", at = LatLon(34.5123, -84.2231), elevationFt = elevationFt, description = description, group = group,
        grid = "16S GD 66993 52949", latLon = "34.51230, -84.22310", useFor = useFor, addTo = addTo,
    )

    // -- The groups ------------------------------------------------------------------------------------------------

    @Test
    fun `with nothing brought in it says where files come from and how to begin`() {
        content(PointsUiState())
        compose.onNodeWithText("Open files in another app and choose EZ/PZ, or tap Import.").assertIsDisplayed()
        compose.onNodeWithText(".msnx · .LPS · .ths").assertIsDisplayed()
        compose.onNodeWithText("No local points yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No missions yet", substring = true).assertIsDisplayed()
        assertEquals(0, count("Threat files"))                                                // nothing to list: no group
    }

    @Test
    fun `the header line counts what is listed`() {
        assertEquals("Nothing imported yet", importsLine(0))
        assertEquals("1 item", importsLine(1))
        assertEquals("5 items", importsLine(5))
    }

    @Test
    fun `each set shows its name, its points, whether it is hidden, and where it is kept`() {
        content(PointsUiState(sets = listOf(north, taxi)))
        compose.onNodeWithText("NORTH GA").assertIsDisplayed()
        compose.onNodeWithText("1,234 points").assertIsDisplayed()
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("46 points · hidden").assertIsDisplayed()
        compose.onNodeWithText("This session").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawn in blue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawn in red, hidden").assertIsDisplayed()
        assertEquals(0, count("No local points yet", substring = true))
    }

    @Test
    fun `a set is hidden and shown from its row, and each button says which set`() {
        val r = content(PointsUiState(sets = listOf(north, taxi)))
        compose.onNodeWithContentDescription("Hide NORTH GA").performClick()
        compose.onNodeWithContentDescription("Show KGVL TAXI").performClick()
        assertEquals(listOf("toggle:s1", "toggle:s2"), r.log)
    }

    @Test
    fun `a colour is chosen from the palette under More, and the choices fold away once one is made`() {
        val r = content(PointsUiState(sets = listOf(north)))
        compose.onNodeWithContentDescription("More actions for NORTH GA").performClick()
        compose.onNodeWithText("Colour").performClick()
        compose.onNodeWithContentDescription("Colour blue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Colour green").performScrollTo().performClick()
        assertEquals(listOf("recolor:s1:#32D74B"), r.log)
        assertEquals(0, count("Colour green"))
    }

    @Test
    fun `renaming sends what was typed, and a refusal is shown at the field`() {
        val r = content(PointsUiState(sets = listOf(north))) { renameProblem = "A set needs a name." }
        compose.onNodeWithContentDescription("More actions for NORTH GA").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextInput("X")
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        assertEquals(listOf("rename:s1:XNORTH GA"), r.log)                                    // the field starts with the name: typing goes in front of it
        compose.onNodeWithText("A set needs a name.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a saved set is deleted only after being asked`() {
        val r = content(PointsUiState(sets = listOf(north)))
        compose.onNodeWithContentDescription("More actions for NORTH GA").performClick()
        assertEquals(0, count("Save to Library"))                                             // it is in the Library already
        compose.onNodeWithText("Delete").performClick()
        assertEquals(emptyList<String>(), r.log)                                              // asked first, nothing yet
        compose.onNodeWithText("Delete NORTH GA?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithContentDescription("More actions for NORTH GA").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete")[0].performClick()                                 // the dialog's own choice
        assertEquals(listOf("delete:s1"), r.log)
    }

    @Test
    fun `a session's set is saved to the Library or let go, never deleted`() {
        val r = content(PointsUiState(sets = listOf(taxi)))
        compose.onNodeWithContentDescription("More actions for KGVL TAXI").performClick()
        assertEquals(0, count("Delete"))
        compose.onNodeWithText("Save to Library").performClick()
        compose.onNodeWithContentDescription("More actions for KGVL TAXI").performClick()
        compose.onNodeWithText("Remove from this session").performClick()
        assertEquals(listOf("save:s2", "remove:s2"), r.log)
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

    @Test
    fun `a mission is listed by its file and opens in Routes`() {
        val r = content(PointsUiState(missions = listOf(mission)))
        compose.onNodeWithText("Mission files").assertIsDisplayed()
        compose.onNodeWithText("GOAT_SUCKER.msnx").assertIsDisplayed()
        compose.onNodeWithText("2 routes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open GOAT_SUCKER in Routes").performScrollTo().performClick()
        assertEquals(listOf("mission:m1"), r.log)
    }

    @Test
    fun `opening a mission or the threats takes the focus off a rename left open, so the hidden panel takes no typing`() {
        val r = content(PointsUiState(sets = listOf(north), missions = listOf(mission), threatFiles = listOf(ThreatFileImport("THREATS_OCT.ths", 7))))
        compose.onNodeWithContentDescription("More actions for NORTH GA").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Name").performScrollTo().performClick()
        compose.onNodeWithText("Name").assertIsFocused()
        compose.onNodeWithContentDescription("Open GOAT_SUCKER in Routes").performScrollTo().performClick()
        compose.onNodeWithText("Name").assertIsNotFocused()
        compose.onNodeWithText("Name").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Open Threats").performScrollTo().performClick()
        compose.onNodeWithText("Name").assertIsNotFocused()
        assertEquals(listOf("mission:m1", "threats"), r.log)
    }

    @Test
    fun `with no Routes panel a mission is listed but not offered to open`() {
        content(PointsUiState(missions = listOf(mission)), routes = false)
        compose.onNodeWithText("GOAT_SUCKER.msnx").assertIsDisplayed()
        assertEquals(0, count("Open in Routes"))
    }

    @Test
    fun `a threat file says how many threats it brought, that they stay on the device, and opens Threats`() {
        val r = content(PointsUiState(threatFiles = listOf(ThreatFileImport("THREATS_OCT.ths", 7))))
        compose.onNodeWithText("Threat files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("7 threats · listed under Threats").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This device only").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Threats").performScrollTo().performClick()
        assertEquals(listOf("threats"), r.log)
    }

    // -- The held point -------------------------------------------------------------------------------------------------

    @Test
    fun `the held point shows what the file said of it, and close puts it down`() {
        val r = content(PointsUiState(sets = listOf(north), held = held()))
        compose.onNodeWithText("BLUE 1").assertIsDisplayed()
        compose.onNodeWithText("16S GD 66993 52949").assertIsDisplayed()
        compose.onNodeWithText("34.51230, -84.22310").assertIsDisplayed()
        compose.onNodeWithText("Elevation 1,730 ft").assertIsDisplayed()
        compose.onNodeWithText("Group: LZ").assertIsDisplayed()
        compose.onNodeWithText("Landing zone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Put BLUE 1 down").performClick()
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
        compose.onNodeWithContentDescription("Add this point to the end of ROUTE 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move the held route point onto this local point: ROUTE 1: .SP").performScrollTo().performClick()
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

    @Test
    fun `with a read-only pack's set open it says why nothing is offered, not how to open a set`() {
        content(PointsUiState(held = held().copy(locked = "OP DK is finished: nothing in it can be changed.")))
        compose.onNodeWithText("OP DK is finished: nothing in it can be changed.").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("Open a set of routes", substring = true))
        assertEquals(0, count("Add to", substring = true))
    }
}
