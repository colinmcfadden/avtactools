package app.ezpztac.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AffiliationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()

    private fun show(state: AffiliationUiState) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            AffiliationContent(
                state = state, signedInAs = "pilot@example.com",
                onSendCode = { log += "send $it" }, onVerify = { log += "verify $it" }, onResend = { log += "resend" },
                onDifferentAddress = { log += "different" }, onDismissError = { log += "dismiss" }, onSignOut = { log += "signOut" },
            )
        }
    }

    @Test
    fun `it says who is signed in and why they are held here`() {
        show(AffiliationUiState())
        compose.onNodeWithText("Signed in as pilot@example.com.").assertIsDisplayed()
        compose.onNodeWithText("Access is limited to Army/DoD personnel", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a mil address is entered and a code is sent for it`() {
        show(AffiliationUiState())
        compose.onNode(hasSetTextAction() and hasText(".mil email address")).performTextInput("name@army.mil")
        compose.onNodeWithText("Send verification code").performClick()
        assertEquals(listOf("send name@army.mil"), log)
    }

    @Test
    fun `the code is entered in capitals, as the server's alphabet is`() {
        show(AffiliationUiState(step = AffiliationStep.Code, email = "name@army.mil", notice = "A code was sent. Check your .mil inbox."))
        compose.onNodeWithText("Sent to name@army.mil. Expires in 30 minutes.").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Verification code")).performTextInput("abcd2345")
        compose.onNodeWithText("Verify affiliation").performClick()
        assertEquals(listOf("verify ABCD2345"), log)
    }

    @Test
    fun `resend, another address and signing out are all one tap`() {
        show(AffiliationUiState(step = AffiliationStep.Code, email = "name@army.mil", error = "That code is invalid or has expired."))
        compose.onNodeWithText("That code is invalid or has expired.").assertIsDisplayed()
        compose.onNodeWithText("Resend code").performScrollTo().performClick()
        compose.onNodeWithText("Use a different address").performScrollTo().performClick()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        assertEquals(listOf("resend", "different", "signOut"), log)
    }
}
