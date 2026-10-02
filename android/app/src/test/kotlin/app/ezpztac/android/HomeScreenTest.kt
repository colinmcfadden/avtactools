package app.ezpztac.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `it says which version this is and that the app is unclassified only`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { HomeScreen(version = "1.7.6", build = 212) } }

        compose.onNodeWithText("Version 1.7.6 (212)").assertIsDisplayed()
        compose.onNodeWithText("not authorised for CUI", substring = true).assertIsDisplayed()
    }
}
