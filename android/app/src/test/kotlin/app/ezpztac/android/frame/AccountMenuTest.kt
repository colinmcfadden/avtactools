package app.ezpztac.android.frame

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The account button and its menu: who is signed in, night mode, the version and its notice, and Sign out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()

    private fun show(night: Boolean = false) {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                AccountButton(
                    name = "Colin McFadden", email = "pilot@example.com", version = "Version 1.9.0 (212)", night = night,
                    onNight = { log += "night $it" }, onSignOut = { log += "out" }, notice = "Unclassified only.",
                )
            }
        }
    }

    @Test
    fun `the button shows the initials and names the account`() {
        show()
        compose.onNodeWithContentDescription("Account, Colin McFadden").assertIsDisplayed()
    }

    @Test
    fun `the menu says who is signed in, the version and the notice`() {
        show()
        compose.onNodeWithContentDescription("Account, Colin McFadden").performClick()
        compose.onNodeWithText("pilot@example.com").assertIsDisplayed()
        compose.onNodeWithText("Version 1.9.0 (212)").assertIsDisplayed()
        compose.onNodeWithText("Unclassified only.").assertIsDisplayed()
    }

    @Test
    fun `night mode turns on from the menu`() {
        show(night = false)
        compose.onNodeWithContentDescription("Account, Colin McFadden").performClick()
        compose.onNodeWithText("Night mode").performClick()
        assertEquals(listOf("night true"), log)
    }

    @Test
    fun `with night mode on, the same row turns it off`() {
        show(night = true)
        compose.onNodeWithContentDescription("Account, Colin McFadden").performClick()
        compose.onNodeWithText("Night mode").performClick()
        assertEquals(listOf("night false"), log)
    }

    @Test
    fun `sign out signs out`() {
        show()
        compose.onNodeWithContentDescription("Account, Colin McFadden").performClick()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf("out"), log)
    }
}
