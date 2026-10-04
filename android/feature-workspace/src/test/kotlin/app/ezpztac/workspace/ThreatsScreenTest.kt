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
import app.ezpztac.model.ThreatDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ThreatsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val drafts = mutableListOf<ThreatDraft>()
        var moveProblem: String? = null
        val actions = ThreatsActions(
            importFile = { log += "import" }, export = { log += "export" }, beginAdd = { log += "add" }, beginEdit = { log += "edit:$it" },
            updateDraft = { drafts += it; log += "updateDraft" }, saveEdit = { log += "save" }, cancelEdit = { log += "cancel" },
            select = { log += "select:$it" }, deselect = { log += "deselect" }, toggleVisible = { log += "visible:$it" },
            moveToCrosshair = { log += "crosshair:$it" }, nudge = { id, n, e -> log += "nudge:$id:$n:$e" }, moveToText = { id, text -> log += "text:$id:$text"; moveProblem },
            remove = { log += "remove:$it" }, showMask = { log += "showMask:$it" }, hideMask = { log += "hideMask:$it" }, removeAll = { log += "removeAll" }, dismissError = { log += "dismissError" }, dismissNote = { log += "dismissNote" },
        )
    }

    private fun show(state: ThreatsUiState, crosshairGrid: String? = "16S GD 66993 52949", configure: Recorder.() -> Unit = {}): Recorder {
        val r = Recorder().apply(configure)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ThreatsContent(state, r.actions, crosshairGrid = crosshairGrid) }
            }
        }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val sa8 = ThreatRowUi("t1", "SA-8", "SHGPEWRR------", "SAM Launcher", "16S GC 12345 67890", "Detection 25 nm · Engagement 15 nm", visible = true, held = false)
    private val zsu = ThreatRowUi("t2", "ZSU-23", "SHAPMFF-------", "Custom symbol", "16S GD 67993 52949", "Engagement 2.5 nm", visible = false, held = false)

    private fun held() = HeldThreatUi(
        id = "t1", name = "SA-8", symbol = "SAM Launcher", grid = "16S GD 66993 52949", latLon = "34.78382, -84.08219", source = "HUMINT", information = "Seen at 0300",
        radars = listOf("Detection · 25 nm · antenna 20 ft AGL · rings on", "Engagement · 15 nm · antenna 20 ft MSL · rings off"),
    )

    // -- The list ------------------------------------------------------------------------------------------------------------

    @Test
    fun `an empty picture explains how to add or import and that it stays local`() {
        show(ThreatsUiState())
        compose.onNodeWithText("No threats in the picture. Move the map centre to a position and add one, or import an AMPS .ths file.").assertIsDisplayed()
        compose.onNodeWithText("Threats are never synced or saved to the server. The sealed device copy is wiped at sign-out and 48 hours after its last change.").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("Share .ths"))
        assertEquals(0, count("Remove all threats"))
    }

    @Test
    fun `Add and Import start their actions`() {
        val r = show(ThreatsUiState())
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Import .ths").performClick()
        assertEquals(listOf("add", "import"), r.log)
    }

    @Test
    fun `a file being read says so, and cannot be started again`() {
        show(ThreatsUiState(importing = true))
        compose.onNodeWithText("Reading…").assertIsDisplayed()
        assertEquals(0, count("Import .ths"))
    }

    @Test
    fun `each threat is a card with its name, symbol, reach and grid, and a hidden one says so`() {
        show(ThreatsUiState(threats = listOf(sa8, zsu)))
        compose.onNodeWithText("SA-8").assertIsDisplayed()
        compose.onNodeWithText("SAM Launcher").assertIsDisplayed()
        compose.onNodeWithText("Detection 25 nm · Engagement 15 nm").assertIsDisplayed()
        compose.onNodeWithText("16S GC 12345 67890").assertIsDisplayed()
        compose.onNodeWithText("Custom symbol · hidden").assertIsDisplayed()
        compose.onNodeWithContentDescription("Threat ZSU-23, hidden").assertIsDisplayed()
        assertEquals(0, count("No threats in the picture", substring = true))
    }

    @Test
    fun `touching a card holds it, the switch hides it, and Share builds the file`() {
        val r = show(ThreatsUiState(threats = listOf(sa8)))
        compose.onNodeWithContentDescription("Threat SA-8").performClick()
        compose.onNodeWithContentDescription("Hide SA-8 on the map").performClick()
        compose.onNodeWithText("Share .ths").performScrollTo().performClick()
        assertEquals(listOf("select:t1", "visible:t1", "export"), r.log)
    }

    @Test
    fun `an error and a note are shown and can be dismissed`() {
        val r = show(ThreatsUiState(error = "That .ths file contains no threats.", note = "Imported 3 threats."))
        compose.onNodeWithText("That .ths file contains no threats.").assertIsDisplayed()
        compose.onNodeWithText("Imported 3 threats.").assertIsDisplayed()
        compose.onAllNodesWithText("Dismiss")[0].performClick()
        compose.onAllNodesWithText("Dismiss")[1].performClick()
        assertEquals(listOf("dismissError", "dismissNote"), r.log)
    }

    @Test
    fun `removing all asks first, Keep them is the prominent answer, and the removal happens only when chosen`() {
        val r = show(ThreatsUiState(threats = listOf(sa8, zsu)))
        compose.onNodeWithText("Remove all threats").performScrollTo().performClick()
        compose.onNodeWithText("Remove all 2 threats from this device? There is no undo.").assertIsDisplayed()
        assertTrue(r.log.isEmpty())
        compose.onNodeWithText("Keep them").performClick()
        assertTrue(r.log.isEmpty())
        compose.onNodeWithText("Remove all threats").performScrollTo().performClick()
        compose.onNodeWithText("Remove all").performClick()
        assertEquals(listOf("removeAll"), r.log)
    }

    // -- The held threat --------------------------------------------------------------------------------------------------

    @Test
    fun `the held threat is told in full`() {
        show(ThreatsUiState(threats = listOf(sa8.copy(held = true)), held = held()))
        compose.onNodeWithText("Held").assertIsDisplayed()
        compose.onNodeWithContentDescription("Threat position 16S GD 66993 52949").assertIsDisplayed()
        compose.onNodeWithText("34.78382, -84.08219").assertIsDisplayed()
        compose.onNodeWithText("Detection · 25 nm · antenna 20 ft AGL · rings on").assertIsDisplayed()
        compose.onNodeWithText("Engagement · 15 nm · antenna 20 ft MSL · rings off").assertIsDisplayed()
        compose.onNodeWithText("Source: HUMINT").assertIsDisplayed()
        compose.onNodeWithText("Seen at 0300").assertIsDisplayed()
    }

    @Test
    fun `Edit and Done act on the held threat`() {
        val r = show(ThreatsUiState(threats = listOf(sa8.copy(held = true)), held = held()))
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("edit:t1", "deselect"), r.log)
    }

    @Test
    fun `Move shows the pad, which moves it by the step chosen, or to the crosshair, or to a typed grid`() {
        val r = show(ThreatsUiState(threats = listOf(sa8.copy(held = true)), held = held()))
        assertEquals(0, count("Move to a grid"))
        compose.onNodeWithText("Move").performClick()
        compose.onNodeWithContentDescription("Move north 50 feet").performScrollTo().performClick()
        compose.onNodeWithText("200 ft").performClick()
        compose.onNodeWithContentDescription("Move west 200 feet").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Put this threat at the crosshair").performScrollTo().performClick()
        compose.onNodeWithText("Move to a grid").performScrollTo().performTextInput("16S GD 1 1")
        compose.onNodeWithText("Go").performClick()
        assertEquals(listOf("nudge:t1:50.0:0.0", "nudge:t1:0.0:-200.0", "crosshair:t1", "text:t1:16S GD 1 1"), r.log)
    }

    @Test
    fun `a typed grid that is not understood is said at the field`() {
        show(ThreatsUiState(threats = listOf(sa8.copy(held = true)), held = held())) { moveProblem = "That is not a place." }
        compose.onNodeWithText("Move").performClick()
        compose.onNodeWithText("Move to a grid").performScrollTo().performTextInput("zzz")
        compose.onNodeWithText("Go").performClick()
        compose.onNodeWithText("That is not a place.").assertIsDisplayed()
    }

    @Test
    fun `Remove asks first, Keep it is the prominent answer, and the removal happens only when chosen`() {
        val r = show(ThreatsUiState(threats = listOf(sa8.copy(held = true)), held = held()))
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove SA-8 from this threat picture? There is no undo.").assertIsDisplayed()
        assertTrue(r.log.isEmpty())
        compose.onNodeWithText("Keep it").performClick()
        assertTrue(r.log.isEmpty())
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove threat").performClick()
        assertEquals(listOf("remove:t1"), r.log)
    }

    // -- The form -----------------------------------------------------------------------------------------------------------

    private fun form(draft: ThreatDraft = ThreatDraft.forNew(0), id: String? = null, error: String? = null) =
        ThreatsUiState(editing = ThreatEditUi(id, LatLon(34.75, -84.05), draft, error))

    @Test
    fun `the form offers both radars and sends save and cancel actions`() {
        val r = show(form())
        compose.onNodeWithText("Detection radar").assertIsDisplayed()
        compose.onNodeWithText("Engagement radar").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Add threat")[1].performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("save", "cancel"), r.log)
    }

    @Test
    fun `a refusal is shown in the form beside Save, where the person is looking`() {
        show(form(error = "Detection range is not a number."))
        compose.onNodeWithText("Detection range is not a number.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a threat being changed says so and saves as changes`() {
        show(form(ThreatDraft(name = "SA-8"), id = "t1"))
        compose.onNodeWithText("Edit threat").assertIsDisplayed()
        compose.onNodeWithText("Save threat").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing in the form hands the whole draft back changed, nothing else disturbed`() {
        val r = show(form())
        // The content is stateless (what is typed goes to the caller, which is not here to put it back), so the text lands in front of what was there.
        compose.onNodeWithText("Name").performTextInput("SA-6 ")
        assertEquals("SA-6 Threat 1", r.drafts.last().name)
        assertEquals(ThreatDraft.forNew(0).radars, r.drafts.last().radars)
    }

    // -- The terrain mask ----------------------------------------------------------------------------------------------------

    private fun withMask(status: ThreatMaskUi.Status, message: String? = null) =
        ThreatsUiState(threats = listOf(sa8.copy(held = true)), held = held().copy(mask = ThreatMaskUi(status, message)))

    @Test
    fun `before the mask is asked for, the card offers it and says exactly what is sent`() {
        val r = show(withMask(ThreatMaskUi.Status.OFF))
        assertTrue(count("Terrain mask") >= 1)
        assertEquals(1, count("sends this threat's position and radar settings to the server once", substring = true))
        assertEquals(1, count("The server keeps nothing.", substring = true))
        compose.onNodeWithText("Show terrain mask").performClick()
        assertEquals(listOf("showMask:t1"), r.log)
    }

    @Test
    fun `a mask being worked out shows it, and the button is not offered again`() {
        show(withMask(ThreatMaskUi.Status.WORKING))
        assertTrue(count("Working out the mask", substring = true) >= 1)
        assertEquals(0, count("Show terrain mask"))
    }

    @Test
    fun `a mask on the map can be hidden or updated`() {
        val r = show(withMask(ThreatMaskUi.Status.SHOWING))
        assertEquals(1, count("Shown on the map", substring = true))
        compose.onNodeWithText("Hide mask").performClick()
        compose.onNodeWithText("Update").performClick()
        assertEquals(listOf("hideMask:t1", "showMask:t1"), r.log)
    }

    @Test
    fun `a mask that is out of date says why and offers to update it`() {
        val r = show(withMask(ThreatMaskUi.Status.OUT_OF_DATE, "This threat was moved or its radars changed after this mask was made, so it is not shown."))
        assertEquals(1, count("was moved or its radars changed", substring = true))
        compose.onNodeWithText("Update mask").performClick()
        assertEquals(listOf("showMask:t1"), r.log)
    }

    @Test
    fun `a failure is in words and can be tried again`() {
        val r = show(withMask(ThreatMaskUi.Status.FAILED, "There is no terrain data for this area."))
        assertEquals(1, count("There is no terrain data for this area."))
        compose.onNodeWithText("Try the terrain mask again").performClick()
        assertEquals(listOf("showMask:t1"), r.log)
    }
}
