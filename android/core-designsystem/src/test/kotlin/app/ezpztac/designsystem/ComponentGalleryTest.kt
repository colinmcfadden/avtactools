package app.ezpztac.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared components drawn in every theme, as pictures: written to build/screenshots for someone to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h900dp-xxhdpi")
class ComponentGalleryTest {
    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Gallery() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.width(400.dp).padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                ScreenHeader("Sign in", subtitle = "Continue to the mission planning workspace.", eyebrow = "ACCOUNT ACCESS")
                Banner("Your session expired. Sign in again to continue.", BannerKind.Info)
                Banner("Invalid email or password.", BannerKind.Error)
                Banner("A new verification link is on its way.", BannerKind.Success)
                Banner("The server is down for maintenance. Planning on this device still works.", BannerKind.Warning, actionLabel = "Retry", onAction = {})
                EzpzTextField("pilot@example.com", {}, "Email address", hint = "The address you registered with.")
                EzpzTextField("pilot@", {}, "Email address", error = "Enter a valid email address.")
                PasswordField("a secure flight password", {}, "Password", hint = "15 or more characters.")
                PrimaryButton("Sign in", {})
                PrimaryButton("Sign in", {}, busy = true, busyText = "Signing in…")
                SecondaryButton("Continue with Google", {})
                LabelledDivider("or")
                TextAction("Forgot password?", {})
            }
        }
    }

    private fun shot(name: String, mode: ThemeMode) {
        compose.setContent { EzpzTheme(mode) { Gallery() } }
        compose.onRoot().captureRoboImage("build/screenshots/gallery-$name.png")
    }

    @Test fun dark() = shot("dark", ThemeMode.Dark)
    @Test fun light() = shot("light", ThemeMode.Light)
    @Test fun night() = shot("night", ThemeMode.Night)
}
