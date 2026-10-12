package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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

/** The Threats panel and the form for one threat as a person meets them (docs/native-design: Threats, Confirm). */
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
            select = { log += "select:$it" }, toggleVisible = { log += "visible:$it" }, remove = { log += "remove:$it" }, removeEditing = { log += "removeEditing" },
            formNudge = { n, e -> log += "nudge:${n.toLong()}:${e.toLong()}" }, formToCrosshair = { log += "crosshair" },
            formToText = { text -> log += "text:$text"; moveProblem }, showMask = { log += "showMask:$it" }, hideMask = { log += "hideMask:$it" },
            removeAll = { log += "removeAll" }, dismissError = { log += "dismissError" }, dismissNote = { log += "dismissNote" },
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

    private fun form(editing: ThreatEditUi, crosshairGrid: String? = "16S GD 66993 52949", configure: Recorder.() -> Unit = {}): Recorder {
        val r = Recorder().apply(configure)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { ThreatFormContent(editing, r.actions, crosshairGrid) } }
        return r
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val sa8 = ThreatRowUi("t1", "SA-8", "SHGPEWRR------", "SAM Launcher", "16S GC 12345 67890", "Detect 25 nm · Engage 15 nm", visible = true, held = false, mask = ThreatMaskUi.Status.SHOWING)
    private val zsu = ThreatRowUi("t2", "ZSU-23", "SHAPMFF-------", "Custom symbol", "16S GD 67993 52949", "Engage 2.5 nm", visible = false, held = true)

    // -- The panel -------------------------------------------------------------------------------------------------------------

    @Test
    fun `the panel says first where threats are kept, and an empty picture how to begin`() {
        show(ThreatsUiState())
        compose.onNodeWithText("Threats stay on this device only", substring = true).assertIsDisplayed()
        compose.onNodeWithText("cleared when you sign out", substring = true).assertIsDisplayed()
        assertEquals(0, count("48 hours", substring = true))                                     // the owner's decision of 2026-10-12: no expiry
        compose.onNodeWithText("No threats on the map. Add one at the crosshair, or import an AMPS .ths file.").assertIsDisplayed()
        assertEquals(0, count(".ths for AMPS"))
        assertEquals(0, count("Remove all threats"))
    }

    @Test
    fun `Add puts a threat at the crosshair, which it names, and Import reads a file`() {
        val r = show(ThreatsUiState())
        compose.onNodeWithText("Add threat puts one at the crosshair, 16S GD 66993 52949.").assertIsDisplayed()
        compose.onNodeWithText("Add threat").performClick()
        compose.onNodeWithText("Import .ths").performClick()
        assertEquals(listOf("add", "import"), r.log)
    }

    @Test
    fun `each threat is a row with its reach and terrain mask, and a hidden one says so`() {
        show(ThreatsUiState(threats = listOf(sa8, zsu)))
        compose.onNodeWithText("On the map · 2").assertIsDisplayed()
        compose.onNodeWithText("Detect 25 nm · Engage 15 nm").assertIsDisplayed()
        compose.onNodeWithText("Terrain mask on").assertIsDisplayed()
        compose.onNodeWithText("Engage 2.5 nm · hidden").assertIsDisplayed()
        compose.onNodeWithText("Rings only").assertIsDisplayed()
    }

    @Test
    fun `a row holds its threat with a tap, and hides, edits and removes it with its buttons, removing only once asked`() {
        val r = show(ThreatsUiState(threats = listOf(sa8, zsu)))
        compose.onNodeWithText("SA-8").performClick()
        compose.onNodeWithContentDescription("Hide SA-8").performClick()
        compose.onNodeWithContentDescription("Show ZSU-23").performClick()
        compose.onNodeWithContentDescription("Edit SA-8").performClick()
        compose.onNodeWithContentDescription("Remove SA-8").performClick()
        compose.onNodeWithText("Remove SA-8?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("select:t1", "visible:t1", "visible:t2", "edit:t1"), r.log)
        compose.onNodeWithContentDescription("Remove SA-8").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals("remove:t1", r.log.last())
    }

    @Test
    fun `the held row is marked`() {
        show(ThreatsUiState(threats = listOf(sa8, zsu)))
        compose.onNodeWithText("ZSU-23").assertIsSelected()
    }

    @Test
    fun `the threats go to AMPS as a ths, and the note says what leaves the device`() {
        val r = show(ThreatsUiState(threats = listOf(sa8)))
        compose.onNodeWithText("The .ths file is built on this device", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(".ths for AMPS").performScrollTo().performClick()
        assertEquals(listOf("export"), r.log)
    }

    @Test
    fun `while the file is built the button says so`() {
        show(ThreatsUiState(threats = listOf(sa8), exporting = true))
        compose.onNodeWithText("Building .ths…").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `removing all asks first, in the design's words, and happens only when chosen`() {
        val r = show(ThreatsUiState(threats = listOf(sa8, zsu)))
        compose.onNodeWithText("Remove all threats").performScrollTo().performClick()
        compose.onNodeWithText("Remove all threats?").assertIsDisplayed()
        compose.onNodeWithText("2 threats come off the map", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Remove all threats").performScrollTo().performClick()
        compose.onNodeWithText("Remove all").performClick()
        assertEquals(listOf("removeAll"), r.log)
    }

    @Test
    fun `an error and a note are shown and can be dismissed`() {
        val r = show(ThreatsUiState(error = "That file could not be read.", note = "Imported 2 threats."))
        compose.onNodeWithText("That file could not be read.").assertIsDisplayed()
        compose.onNodeWithText("Imported 2 threats.").assertIsDisplayed()
        compose.onAllNodesWithText("Dismiss")[0].performClick()
        compose.onAllNodesWithText("Dismiss")[1].performClick()
        assertEquals(listOf("dismissError", "dismissNote"), r.log)
    }

    // -- The form ----------------------------------------------------------------------------------------------------------------

    private val at = LatLon(34.75, -84.05)

    private fun editing(id: String? = "t1", mask: ThreatMaskUi? = if (id != null) ThreatMaskUi() else null, error: String? = null) =
        ThreatEditUi(id, at, ThreatDraft.forNew(0).copy(name = "SA-8"), error = error, grid = "16S GD 66993 52949", mask = mask)

    @Test
    fun `the form names itself, says where the threat is, and saves or closes`() {
        val r = form(editing(id = null))
        compose.onNodeWithText("Add threat").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Add threat")).assertExists()
        compose.onNodeWithContentDescription("Threat position 16S GD 66993 52949").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithContentDescription("Close without saving").performClick()
        assertEquals(listOf("save", "cancel"), r.log)
    }

    @Test
    fun `the threat is moved in the form by the pad, to the crosshair whose grid it says, or to a grid typed`() {
        val r = form(editing()) { moveProblem = "That is not a grid or a coordinate." }
        assertEquals(0, count("Put at the crosshair"))                                           // folded away until asked for
        compose.onNodeWithContentDescription("Show controls to move this threat").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move north 50 feet").performScrollTo().performClick()
        compose.onNodeWithText("Crosshair: 16S GD 66993 52949").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Put this threat at the crosshair, 16S GD 66993 52949").performScrollTo().performClick()
        compose.onNodeWithText("Move to a grid").performScrollTo().performTextInput("nowhere")
        compose.onNodeWithText("Go").performScrollTo().performClick()
        assertEquals(listOf("nudge:50:0", "crosshair", "text:nowhere"), r.log)
        compose.onNodeWithText("That is not a grid or a coordinate.").assertIsDisplayed()
    }

    @Test
    fun `what is wrong with the form is said under its bar`() {
        form(editing(error = "A threat needs a name."))
        compose.onNodeWithText("A threat needs a name.").assertIsDisplayed()
    }

    @Test
    fun `a threat on the map has its terrain mask in the form, and a new one does not`() {
        val r = form(editing(mask = ThreatMaskUi()))
        compose.onNodeWithText("Show terrain mask").performScrollTo().performClick()
        compose.onNodeWithText("save first to include what you changed here", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("showMask:t1"), r.log)
    }

    @Test
    fun `a new threat has no mask and nothing to remove`() {
        form(editing(id = null))
        assertEquals(0, count("Terrain mask"))
        assertEquals(0, count("Remove this threat"))
    }

    @Test
    fun `removing from the form asks first`() {
        val r = form(editing())
        compose.onNodeWithText("Remove this threat").performScrollTo().performClick()
        compose.onNodeWithText("Remove SA-8?").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("removeEditing"), r.log)
    }

    @Test
    fun `the fields change the draft, the symbol from the common ones or typed`() {
        val r = form(editing())
        compose.onNodeWithText("Name").performScrollTo().performTextInput("X")
        assertTrue(r.drafts.toString(), r.drafts.any { "X" in it.name })                           // the stand-in never updates the field, so where the cursor lands is its own
        compose.onNodeWithContentDescription("Choose a common threat symbol").performScrollTo().performClick()
        val preset = app.ezpztac.model.SymbolPresets.threats.last()
        compose.onNodeWithText(preset.label).performClick()
        assertEquals(preset.sidc, r.drafts.last().milstdId)
    }
}
