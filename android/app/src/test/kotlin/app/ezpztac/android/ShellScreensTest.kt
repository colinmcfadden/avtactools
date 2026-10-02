package app.ezpztac.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.network.SignedOutReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ShellScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()

    @Test
    fun `an update that is required names the version and offers the one way out`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { UpdateRequiredScreen("1.8.0") } }
        compose.onNodeWithText("Update required").assertIsDisplayed()
        compose.onNodeWithText("Update to version 1.8.0 or newer", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open the store").assertIsDisplayed()
    }

    @Test
    fun `an update with no version named still says what to do`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { UpdateRequiredScreen(null) } }
        compose.onNodeWithText("Update to the latest version or newer", substring = true).assertIsDisplayed()
    }

    @Test
    fun `plans from another account say how much clearing them would lose, and leave the choice`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { DataConflictScreen("pilot@example.com", 3, onClear = { log += "clear" }, onSignOut = { log += "out" }) } }
        compose.onNodeWithText("3 changes on this device have not reached the server", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Clear them and continue").performClick()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf("clear", "out"), log)
    }

    @Test
    fun `one change is said in the singular, and none says nothing`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { DataConflictScreen("pilot@example.com", 1, {}, {}) } }
        compose.onNodeWithText("1 change on this device has not reached the server", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no unsynced changes shows no warning`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { DataConflictScreen("pilot@example.com", 0, {}, {}) } }
        compose.onNodeWithText("have not reached the server", substring = true).assertDoesNotExist()
    }

    // -- Why the person is at the sign-in --------------------------------------------------------

    @Test
    fun `a person who just has not signed in yet is told nothing`() {
        assertNull(signedOutNotice(SignedOutReason.NOT_SIGNED_IN, null))
    }

    @Test
    fun `being offline too long says why, and that the plans are kept`() {
        val text = signedOutNotice(SignedOutReason.SESSION_ENDED, "offline_too_long")!!
        assertTrue(text.contains("14 days"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun `any other ended session is an expired one`() {
        assertEquals("Your session expired. Sign in again to continue.", signedOutNotice(SignedOutReason.SESSION_ENDED, "session_revoked"))
        assertEquals("Your session expired. Sign in again to continue.", signedOutNotice(SignedOutReason.SESSION_ENDED, null))
    }
}
