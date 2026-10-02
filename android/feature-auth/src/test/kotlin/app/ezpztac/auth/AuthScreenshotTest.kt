package app.ezpztac.auth

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each screen drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots (without it they are not drawn). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AuthScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: AuthUiState, mode: ThemeMode = ThemeMode.Dark, google: Boolean = true) {
        compose.setContent { EzpzTheme(mode) { AuthContent(state, google, AuthActions()) } }
        compose.onRoot().captureRoboImage("build/screenshots/auth-$name.png")
    }

    @Test fun signIn() = shot("sign-in", AuthUiState(notice = "Your session expired. Sign in again to continue.", error = "Invalid email or password."))
    @Test fun signInNight() = shot("sign-in-night", AuthUiState(), ThemeMode.Night)
    @Test fun signInLight() = shot("sign-in-light", AuthUiState(), ThemeMode.Light, google = false)
    @Test fun register() = shot("register", AuthUiState(route = AuthRoute.Register))
    @Test fun checkEmail() = shot("check-email", AuthUiState(route = AuthRoute.CheckEmail("pilot@example.com"), resendCooldownSeconds = 42, email = "pilot@example.com"))
    @Test fun verify() = shot("verify", AuthUiState(route = AuthRoute.Verify("tok")))
    @Test fun verified() = shot("verified", AuthUiState(route = AuthRoute.Verify("tok"), completed = true))
    @Test fun forgot() = shot("forgot", AuthUiState(route = AuthRoute.Forgot))
    @Test fun reset() = shot("reset", AuthUiState(route = AuthRoute.Reset("tok"), error = "This password reset link is invalid or has expired."))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AffiliationScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: AffiliationUiState) {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                AffiliationContent(state, "pilot@example.com", {}, {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/affiliation-$name.png")
    }

    @Test fun email() = shot("email", AffiliationUiState())
    @Test fun code() = shot("code", AffiliationUiState(step = AffiliationStep.Code, email = "name@army.mil", notice = "A code was sent. Check your .mil inbox."))
}
