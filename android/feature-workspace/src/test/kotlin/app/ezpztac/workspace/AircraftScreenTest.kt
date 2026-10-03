package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.AircraftDraft
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The aircraft section as a person meets it: what is shown, what a tap does, and what a tap must not do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AircraftScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val actions = AircraftActions(
            choose = { log += "choose $it" },
            startNew = { log += "new" },
            startCopy = { log += "copy $it" },
            startEdit = { log += "edit $it" },
            change = { log += "change ${it.designation}|${it.name}|${it.rotorDiameterM}" },
            cancel = { log += "cancel" },
            save = { log += "save" },
            delete = { log += "delete $it" },
            resolve = { u, r -> log += "resolve $u $r" },
            dismissError = { log += "dismiss" },
        )
    }

    private val uh60 = AircraftRowUi("uh60l", null, "uh60l", "UH-60L — UH-60L Black Hawk", "76 m spacing · 100 kt", own = false, chosen = true, usable = true, unverified = false, sync = null, conflictOf = null)
    private val ch47 = AircraftRowUi("ch47f", null, "ch47f", "CH-47F — CH-47F Chinook", "93 m spacing · 100 kt", own = false, chosen = false, usable = true, unverified = true, sync = null, conflictOf = null)
    private val mine = AircraftRowUi("u-1", "u-1", "alpha", "X-1 — Alpha Heli", "72 m spacing · 100 kt", own = true, chosen = false, usable = true, unverified = false, sync = SyncStatus.SYNCED, conflictOf = null)
    private val pending = mine.copy(key = "u-2", uuid = "u-2", slug = "", title = "MH-60M — Night Hawk", usable = false, sync = SyncStatus.PENDING)

    private fun show(state: AircraftUiState, canMake: Boolean = true, startOpen: Boolean = true): Recorder {
        val recorder = Recorder()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { AircraftContent(state, canMake, recorder.actions, startOpen = startOpen) } }
        }
        return recorder
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    // -- The section ---------------------------------------------------------------------------------------------

    @Test
    fun `the section starts shut, and opens to the list`() {
        show(AircraftUiState(rows = listOf(uh60)), startOpen = false)
        compose.onNodeWithText("Aircraft").assertIsDisplayed()
        assertEquals(0, count("UH-60L — UH-60L Black Hawk"))
        compose.onNodeWithText("Manage aircraft").performClick()
        compose.onNodeWithText("UH-60L — UH-60L Black Hawk").assertIsDisplayed()
        compose.onNodeWithText("Hide").performClick()
        assertEquals(0, count("UH-60L — UH-60L Black Hawk"))
    }

    @Test
    fun `a row says who it is, how far apart two stand and which is the mission aircraft`() {
        show(AircraftUiState(rows = listOf(uh60, ch47, mine)))
        compose.onNodeWithText("UH-60L — UH-60L Black Hawk").assertIsDisplayed()
        compose.onNodeWithText("76 m spacing · 100 kt").assertIsDisplayed()
        compose.onNodeWithText("Mission aircraft").assertIsDisplayed()
        compose.onNodeWithText("Unverified performance").assertIsDisplayed()
        compose.onNodeWithText("Yours").assertIsDisplayed()
    }

    @Test
    fun `an own aircraft the server has not named says it is waiting, and cannot be used`() {
        val r = show(AircraftUiState(rows = listOf(uh60, pending)))
        compose.onNodeWithText("Waiting to sync before it can be chosen", substring = true).assertIsDisplayed()
        assertEquals(0, count("Use"))                                                       // the UH-60L is chosen already, and the other is not there to be chosen
        assertEquals(emptyList<String>(), r.log)
    }

    // -- Row actions ----------------------------------------------------------------------------------------------

    @Test
    fun `use chooses an airframe, and the mission aircraft is not offered itself`() {
        val r = show(AircraftUiState(rows = listOf(uh60, ch47)))
        assertEquals(1, count("Use"))                                                       // the UH-60L is the mission aircraft already
        compose.onNodeWithText("Use").performClick()
        assertEquals(listOf("choose ch47f"), r.log)
    }

    @Test
    fun `an admin's airframe can be copied but not changed or deleted`() {
        val r = show(AircraftUiState(rows = listOf(ch47)))
        assertEquals(0, count("Edit"))
        assertEquals(0, count("Delete"))
        compose.onNodeWithText("Copy").performClick()
        assertEquals(listOf("copy ch47f"), r.log)
    }

    @Test
    fun `an own aircraft can be copied, changed and deleted`() {
        val r = show(AircraftUiState(rows = listOf(mine)))
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Edit").performClick()
        assertEquals(listOf("copy u-1", "edit u-1"), r.log)
    }

    @Test
    fun `a delete is asked about first`() {
        val r = show(AircraftUiState(rows = listOf(mine)))
        compose.onNodeWithText("Delete").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Delete \"X-1 — Alpha Heli\"?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(emptyList<String>(), r.log)
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()                                     // now the confirmation's own button
        assertEquals(listOf("delete u-1"), r.log)
        // The question is closed once answered: if the delete fails and the row stays, it must not still be asking.
        compose.onNodeWithText("Edit").assertIsDisplayed()
        assertEquals(0, count("Keep it"))
    }

    @Test
    fun `an account that cannot make its own aircraft can still choose, and is told why it cannot make one`() {
        val r = show(AircraftUiState(rows = listOf(uh60, ch47, mine)), canMake = false)
        assertEquals(0, count("New aircraft"))
        assertEquals(0, count("Copy"))
        assertEquals(0, count("Edit"))
        compose.onNodeWithText("Making your own aircraft is switched off", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()                                // an own one it made before is still its to remove
        assertEquals(2, count("Use"))                                                       // the admin's CH-47F and its own: the UH-60L is the mission aircraft
        compose.onAllNodesWithText("Use")[0].performClick()
        assertEquals(listOf("choose ch47f"), r.log)
    }

    @Test
    fun `new aircraft starts the form`() {
        val r = show(AircraftUiState(rows = listOf(uh60)))
        compose.onNodeWithText("New aircraft").performClick()
        assertEquals(listOf("new"), r.log)
    }

    // -- Conflicts -----------------------------------------------------------------------------------------------

    @Test
    fun `a conflict copy offers the three ways to settle it and nothing else`() {
        val copy = mine.copy(key = "c-1", uuid = "c-1", title = "X-1 — Alpha Heli (from this device, 14:32)", usable = false, sync = SyncStatus.CONFLICT, conflictOf = "u-1")
        val r = show(AircraftUiState(rows = listOf(copy)))
        compose.onNodeWithText("This is your version", substring = true).assertIsDisplayed()
        assertEquals(0, count("Edit"))
        assertEquals(0, count("Use"))
        compose.onNodeWithText("Keep both").performClick()
        compose.onNodeWithText("Keep mine (replaces the other version)").performClick()
        compose.onNodeWithText("Keep theirs (discard mine)").performClick()
        assertEquals(
            listOf("resolve c-1 ${SyncEngine.Resolution.KEEP_BOTH}", "resolve c-1 ${SyncEngine.Resolution.KEEP_MINE}", "resolve c-1 ${SyncEngine.Resolution.KEEP_THEIRS}"),
            r.log,
        )
    }

    // -- The form ------------------------------------------------------------------------------------------------

    private fun form(draft: AircraftDraft = AircraftDraft(), editing: String? = null, error: String? = null, busy: Boolean = false) =
        AircraftUiState(form = AircraftFormUi(editing, draft, error, busy))

    @Test
    fun `the form shows the web's fields and the spacing its numbers make`() {
        show(form())
        for (label in listOf("Designation", "Name", "Rotor diameter (m)", "Tip clearance (m)", "Cruise airspeed (kt)", "Max indicated (kt)", "Default altitude (ft)", "Fuel flow (lb/hr)", "Gross weight (lb)")) {
            field(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Centre spacing works out to 76.4 m.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create aircraft").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a form for an existing aircraft says it is changing one`() {
        show(form(editing = "u-1"))
        compose.onNodeWithText("Change aircraft").assertIsDisplayed()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing sends the whole draft with the change in it`() {
        val r = show(form(AircraftDraft(name = "Night Hawk")))
        field("Designation").performTextInput("mh-60m")
        assertEquals(listOf("change mh-60m|Night Hawk|16.36"), r.log)
    }

    @Test
    fun `save and cancel do what they say`() {
        val r = show(form(AircraftDraft(name = "Night Hawk", designation = "MH-60M")))
        compose.onNodeWithText("Create aircraft").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("save", "cancel"), r.log)
    }

    @Test
    fun `done on the last field saves, as the button does`() {
        val r = show(form(AircraftDraft(name = "Night Hawk", designation = "MH-60M")))
        field("Gross weight (lb)").performScrollTo().performImeAction()
        assertEquals(listOf("save"), r.log)
    }

    @Test
    fun `a refusal is shown above the fields`() {
        show(form(error = "Rotor diameter must be between 1 and 60."))
        compose.onNodeWithText("Rotor diameter must be between 1 and 60.").assertIsDisplayed()
    }

    @Test
    fun `while it is saving the form says so`() {
        show(form(busy = true))
        compose.onNodeWithText("Saving…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an error on the list can be dismissed`() {
        val r = show(AircraftUiState(rows = listOf(uh60), error = "The aircraft could not be deleted."))
        compose.onNodeWithText("The aircraft could not be deleted.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }
}
