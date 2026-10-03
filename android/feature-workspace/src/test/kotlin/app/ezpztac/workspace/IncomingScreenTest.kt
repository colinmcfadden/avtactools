package app.ezpztac.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The question put to the person when another app hands over a file: what it says, and which button does what. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class IncomingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = IncomingActions(accept = { log += "accept" }, decline = { log += "decline" }, closeResult = { log += "close" })

    private fun show(state: IncomingUiState) = compose.setContent { EzpzTheme(ThemeMode.Dark) { IncomingOfferContent(state, actions) } }

    private fun present(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a threat file asks before adding, says the threats stay on the device, and accepts or declines`() {
        show(IncomingUiState(IncomingOfferUi.Threats("SA-6 site.ths", 3)))
        compose.onNodeWithText("Add threats?").assertIsDisplayed()
        assertTrue(present("SA-6 site.ths has 3 threats.", substring = true))
        assertTrue(present("not saved to your account", substring = true))
        compose.onNodeWithText("Add 3 threats").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf("accept", "decline"), log)
    }

    @Test
    fun `a single threat is not pluralised`() {
        show(IncomingUiState(IncomingOfferUi.Threats("one.ths", 1)))
        compose.onNodeWithText("Add 1 threat").assertIsDisplayed()
    }

    @Test
    fun `a points file asks before saving and says it syncs with the account`() {
        show(IncomingUiState(IncomingOfferUi.Points("NORTH GA.LPS", "NORTH GA", 1234)))
        compose.onNodeWithText("Save local points?").assertIsDisplayed()
        assertTrue(present("1,234 points", substring = true))
        assertTrue(present("called NORTH GA", substring = true))
        assertTrue(present("sync with your account", substring = true))
        compose.onNodeWithText("Save 1,234 points").performClick()
        assertEquals(listOf("accept"), log)
    }

    @Test
    fun `while a set is being saved the file cannot be declined, and the button says so`() {
        show(IncomingUiState(IncomingOfferUi.Points("a.LPS", "A", 5), busy = true))
        compose.onNodeWithText("Not now").assertIsNotEnabled()
        compose.onNodeWithText("Saving").assertIsDisplayed()
    }

    @Test
    fun `a mission says it cannot be opened yet and offers only to close`() {
        show(IncomingUiState(IncomingOfferUi.Mission("plan.msnx")))
        compose.onNodeWithText("Missions can't be opened yet").assertIsDisplayed()
        assertTrue(!present("Add", substring = true) && !present("Save", substring = true))
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("decline"), log)
    }

    @Test
    fun `a file that cannot be opened says why and closes`() {
        show(IncomingUiState(IncomingOfferUi.Problem("notes.txt", "This is not one of ours.")))
        compose.onNodeWithText("Can't open notes.txt").assertIsDisplayed()
        compose.onNodeWithText("This is not one of ours.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("decline"), log)
    }

    @Test
    fun `a file still being looked at can be put off`() {
        show(IncomingUiState(IncomingOfferUi.Reading("big.LPS")))
        compose.onNodeWithText("Opening big.LPS").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf("decline"), log)
    }

    @Test
    fun `files behind the first are counted`() {
        show(IncomingUiState(IncomingOfferUi.Threats("a.ths", 2), waiting = 2))
        compose.onNodeWithText("2 more files waiting.").assertIsDisplayed()
        compose.onNodeWithText("Add 2 threats").assertIsDisplayed()
    }

    @Test
    fun `a result with no offer left is closed with Close`() {
        show(IncomingUiState(result = "Added 2 threats from a.ths."))
        compose.onNodeWithText("Added 2 threats from a.ths.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("close"), log)
    }

    @Test
    fun `a failure to save is shown over the question`() {
        show(IncomingUiState(IncomingOfferUi.Points("a.LPS", "A", 5), error = "That file could not be saved."))
        compose.onNodeWithText("That file could not be saved.").assertIsDisplayed()
        compose.onNodeWithText("Save 5 points").assertIsDisplayed()
    }
}
