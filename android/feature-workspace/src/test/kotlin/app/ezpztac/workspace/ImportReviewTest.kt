package app.ezpztac.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The import review and the Import menu as a person meets them: what each file is, where it goes, and which control does what. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ImportReviewTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = ImportReviewActions(
        setName = { id, name -> log += "name:$id:$name" }, setPlace = { id, place -> log += "place:$id:$place" }, remove = { log += "remove:$it" },
        cancel = { log += "cancel" }, importAll = { log += "import" },
    )

    private fun show(state: IncomingUiState) = compose.setContent { EzpzTheme(ThemeMode.Dark) { ImportReviewContent(state, actions) } }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private val points = ImportItemUi.Points(1, "NORTH_GA_POINTS.LPS", "82 KB", 1234, "NORTH GA POINTS", ImportPlace.LIBRARY)
    private val threats = ImportItemUi.Threats(2, "THREATS_OCT.ths", "12 KB", 7)
    private val mission = ImportItemUi.Mission(3, "GOAT_SUCKER.msnx", "1.5 MB", routes = 2, namedPoints = 91, aircraft = "UH-60L")
    private val notes = ImportItemUi.Problem(4, "notes.docx", "EZ/PZ opens AMPS local points (.LPS), threat (.ths) and mission (.msnx) files. This is none of them.")
    private val all = IncomingUiState(listOf(points, threats, mission, notes))

    @Test
    fun `each file says what it is, what is in it, and where it goes`() {
        show(all)
        compose.onAllNodesWithText("Import 3 files")[0].assertIsDisplayed()                  // the title counts what can be brought in (and the button says the same)
        compose.onNodeWithText("NORTH_GA_POINTS.LPS").assertIsDisplayed()
        compose.onNodeWithText("1,234 points · 82 KB").assertIsDisplayed()
        compose.onNodeWithText("Local points").assertIsDisplayed()
        compose.onNodeWithText("Saves to your Library and syncs with your account.").assertIsDisplayed()
        compose.onNodeWithText("7 threats · 12 KB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Goes to This device only").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 routes · 91 named points · 1.5 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Goes to Library").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Planned for the UH-60L in AMPS.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not imported.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 to Library · 1 stays on this device").assertIsDisplayed()
    }

    @Test
    fun `a set of points goes to the Library or this session, and says what each means`() {
        show(IncomingUiState(listOf(points)))
        compose.onNodeWithText("Library").assertIsSelected()
        compose.onNodeWithText("This session").performClick()
        assertEquals(listOf("place:1:SESSION"), log)
    }

    @Test
    fun `a set held for this session says nothing is saved`() {
        show(IncomingUiState(listOf(points.copy(place = ImportPlace.SESSION))))
        compose.onNodeWithText("This session").assertIsSelected()
        compose.onNodeWithText("Shown in this session only. Nothing is saved", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 stays in this session").assertIsDisplayed()
    }

    @Test
    fun `the name typed is sent as it is typed`() {
        show(IncomingUiState(listOf(points)))
        compose.onNodeWithText("Name").performTextInput("X")
        assertEquals(listOf("name:1:XNORTH GA POINTS"), log)
    }

    @Test
    fun `a name typed is not brought back from saved state for another file with the same id`() {
        // Files are numbered from 1 in each process: after the system ended the app, a new file can have an old one's id. What is shown must be what is brought in.
        val tester = StateRestorationTester(compose)
        var state by mutableStateOf(IncomingUiState(listOf(points)))
        tester.setContent { EzpzTheme(ThemeMode.Dark) { ImportReviewContent(state, actions) } }
        compose.onNodeWithText("Name").performTextInput("X")
        state = IncomingUiState(listOf(points.copy(fileName = "B.LPS", name = "B POINTS")))
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("B POINTS").assertIsDisplayed()
        assertEquals(0, count("XNORTH GA POINTS"))
    }

    @Test
    fun `any file can be taken out of the list, by name`() {
        show(all)
        compose.onNodeWithContentDescription("Remove notes.docx from the list").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Remove THREATS_OCT.ths from the list").performScrollTo().performClick()
        assertEquals(listOf("remove:4", "remove:2"), log)
    }

    @Test
    fun `Import brings them in, and Cancel and close bring nothing`() {
        show(all)
        compose.onAllNodesWithText("Import 3 files")[1].performClick()                       // the button; the first is the title
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Close without importing").performClick()
        assertEquals(listOf("import", "cancel", "cancel"), log)
    }

    @Test
    fun `one file is not pluralised`() {
        show(IncomingUiState(listOf(threats)))
        assertEquals(2, count("Import 1 file"))
    }

    @Test
    fun `nothing that can be brought in leaves Import off, and says so`() {
        show(IncomingUiState(listOf(notes)))
        compose.onAllNodesWithText("Import")[1].assertIsNotEnabled()
        compose.onNodeWithText("Nothing here can be imported.").assertIsDisplayed()
    }

    @Test
    fun `Import waits for a file still being looked at`() {
        show(IncomingUiState(listOf(points, ImportItemUi.Reading(5, "big.LPS"))))
        compose.onNodeWithText("Looking at what is in it…").assertIsDisplayed()
        compose.onAllNodesWithText("Import 1 file")[1].assertIsNotEnabled()
        compose.onNodeWithText("Looking at what is in the files…").assertIsDisplayed()
    }

    @Test
    fun `while files are brought in, nothing else can be pressed`() {
        show(all.copy(busy = true))
        compose.onNodeWithText("Importing…").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close without importing").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove notes.docx from the list").performScrollTo().assertIsNotEnabled()
    }

    // -- The Import menu ---------------------------------------------------------------------------------------------

    @Test
    fun `the Import menu offers what the app takes, and each opens the picker`() {
        var chosen = 0
        compose.setContent { EzpzTheme(ThemeMode.Dark) { ImportSheetContent(threatsAllowed = true, onChoose = { chosen++ }) } }
        compose.onNodeWithText("You check each file, and where it goes, before anything is added.").assertIsDisplayed()
        compose.onNodeWithText("Mission file").performClick()
        compose.onNodeWithText("Local points").performClick()
        compose.onNodeWithText("Threats").performClick()
        assertEquals(3, chosen)
        compose.onNodeWithText(".ths · stays on this device").assertIsDisplayed()
        compose.onNodeWithText("Or open a file in Files, Gmail or Drive and choose EZ/PZ. We work out the type.").assertIsDisplayed()
        assertEquals(0, count("Map or overlay"))                                              // not in the first build (the owner's decision 2)
    }

    @Test
    fun `an account without threats is not offered a threat file`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { ImportSheetContent(threatsAllowed = false, onChoose = {}) } }
        assertTrue(count("Mission file") == 1)
        assertEquals(0, count("Threats"))
        compose.onNodeWithText("Local points").assertIsEnabled()
    }
}
