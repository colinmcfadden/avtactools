package app.ezpztac.auth

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screens as a person meets them: typing, tapping, what is shown and what is not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AuthScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()
        val actions = AuthActions(
            signIn = { e, p -> log += "signIn $e $p" },
            register = { n, e -> log += "register $n $e" },
            verify = { p, c -> log += "verify $p $c" },
            resend = { log += "resend $it" },
            forgot = { log += "forgot $it" },
            reset = { p, c -> log += "reset $p $c" },
            open = { log += "open $it" },
            google = { log += "google" },
            dismissError = { log += "dismiss" },
        )
    }

    private fun show(state: AuthUiState, google: Boolean = false, recorder: Recorder = Recorder()): Recorder {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { AuthContent(state, google, recorder.actions) } }
        return recorder
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    // -- Sign in -------------------------------------------------------------------------------------

    @Test
    fun `typing an address and a password and tapping Sign in sends exactly what was typed`() {
        val r = show(AuthUiState())
        field("Email address").performTextInput("pilot@example.com")
        field("Password").performTextInput("a secure flight password")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        assertEquals(listOf("signIn pilot@example.com a secure flight password"), r.log)
    }

    /** What the field actually shows: dots while hidden, the text once revealed. */
    private fun shownIn(label: String): String =
        field(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `the password is hidden until the person asks to see it`() {
        show(AuthUiState())
        field("Password").performTextInput("secret passphrase!")
        assertEquals("•".repeat("secret passphrase!".length), shownIn("Password"))
        compose.onNodeWithText("Show").performClick()
        assertEquals("secret passphrase!", shownIn("Password"))
        compose.onNodeWithText("Hide").performClick()
        assertEquals("•".repeat("secret passphrase!".length), shownIn("Password"))
    }

    @Test
    fun `the links go where they say`() {
        val r = show(AuthUiState())
        field("Email address").performTextInput(" pilot@example.com ")
        compose.onNodeWithText("Forgot password?").performClick()
        compose.onNodeWithText("Resend verification email").performClick()
        compose.onNodeWithText("Register with email").performClick()
        assertEquals(
            listOf("open ${AuthRoute.Forgot}", "open ${AuthRoute.Resend("pilot@example.com")}", "open ${AuthRoute.Register}"),
            r.log,
        )
    }

    @Test
    fun `an error is shown with a way to dismiss it, and a notice beside it`() {
        val r = show(AuthUiState(error = "Invalid email or password.", notice = "Your session expired. Sign in again to continue."))
        compose.onNodeWithText("Invalid email or password.").assertIsDisplayed()
        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), r.log)
    }

    @Test
    fun `while a request is out the form cannot be changed and the button says what is happening`() {
        val r = show(AuthUiState(busy = true))
        compose.onNode(hasText("Email address") and isNotEnabled()).assertExists()
        compose.onNode(hasText("Password") and isNotEnabled()).assertExists()
        compose.onNodeWithText("Signing in\u2026").assertIsDisplayed()
        compose.onNodeWithText("Signing in\u2026").performClick()
        assertTrue("a tap while busy does nothing", r.log.isEmpty())
    }

    @Test
    fun `Google is offered only where the app can do it`() {
        show(AuthUiState(), google = false)
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
    }

    @Test
    fun `Google is offered, and sends the person to Google's own sheet`() {
        val r = show(AuthUiState(), google = true)
        compose.onNodeWithText("Continue with Google").performClick()
        assertEquals(listOf("google"), r.log)
    }

    // -- Register --------------------------------------------------------------------------------------

    @Test
    fun `registering sends the name and the address, and shows the data-handling notice`() {
        val r = show(AuthUiState(route = AuthRoute.Register))
        compose.onNodeWithText("Data-handling notice", substring = true).assertIsDisplayed()
        field("Full name").performTextInput("New Pilot")
        field("Email address").performTextInput("new@example.com")
        compose.onNode(hasText("Create account") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf("register New Pilot new@example.com"), r.log)
    }

    // -- Check your inbox -------------------------------------------------------------------------------

    @Test
    fun `check your inbox shows a masked address and the wait before another link`() {
        show(AuthUiState(route = AuthRoute.CheckEmail("pilot@example.com"), resendCooldownSeconds = 42))
        compose.onNodeWithText("pi•••@example.com", substring = true).assertIsDisplayed()          // the sentence masks it; the field below holds it to edit
        compose.onNodeWithText("Resend available in 42s").assertIsNotEnabled()
    }

    @Test
    fun `after the wait the link can be asked for again`() {
        val r = show(AuthUiState(route = AuthRoute.CheckEmail("pilot@example.com"), resendCooldownSeconds = 0))
        compose.onNodeWithText("Resend verification email").assertIsEnabled().performClick()
        assertEquals(listOf("resend pilot@example.com"), r.log)
    }

    // -- Verify and reset ------------------------------------------------------------------------------------

    @Test
    fun `a verification link with no token says it is incomplete and offers a new one`() {
        val r = show(AuthUiState(route = AuthRoute.Verify("")))
        compose.onNodeWithText("This verification link is incomplete.").assertIsDisplayed()
        compose.onNodeWithText("Request a new link").performClick()
        assertEquals(listOf("open ${AuthRoute.Resend("")}"), r.log)
    }

    @Test
    fun `choosing a password from the verification link sends both entries`() {
        val r = show(AuthUiState(route = AuthRoute.Verify("tok")))
        field("Password").performTextInput("a secure flight password")
        field("Confirm password").performTextInput("a secure flight password")
        compose.onNode(hasText("Activate account") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf("verify a secure flight password a secure flight password"), r.log)
    }

    @Test
    fun `a verified account is told so and sent to the sign-in`() {
        val r = show(AuthUiState(route = AuthRoute.Verify("tok"), completed = true))
        compose.onNodeWithText("Email verified").assertIsDisplayed()
        compose.onNodeWithText("Continue to sign in").performClick()
        assertEquals(listOf("open ${AuthRoute.SignIn}"), r.log)
    }

    @Test
    fun `a reset that failed offers a fresh link`() {
        val r = show(AuthUiState(route = AuthRoute.Reset("old"), error = "This password reset link is invalid or has expired."))
        compose.onNodeWithText("This password reset link is invalid or has expired.").assertIsDisplayed()
        compose.onNodeWithText("Request a new link").performScrollTo().performClick()
        assertEquals(listOf("open ${AuthRoute.Forgot}"), r.log)
    }

    @Test
    fun `a reset link with no token is incomplete`() {
        show(AuthUiState(route = AuthRoute.Reset("")))
        compose.onNodeWithText("This password-reset link is incomplete.").assertIsDisplayed()
    }

    @Test
    fun `forgot says the same thing for every address once asked`() {
        show(AuthUiState(route = AuthRoute.Forgot, resetRequested = true, email = "pilot@example.com"))
        compose.onNodeWithText("If an account matches pi•••@example.com, a password-reset link has been sent.").assertIsDisplayed()
    }

    @Test
    fun `asking for a reset link sends the address`() {
        val r = show(AuthUiState(route = AuthRoute.Forgot))
        field("Email address").performTextInput("pilot@example.com")
        compose.onNodeWithText("Send reset link").performClick()
        assertEquals(listOf("forgot pilot@example.com"), r.log)
    }
}
