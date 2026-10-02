package app.ezpztac.android

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

/** The shell's own screens drawn for someone to look at (`-Pezpz.screenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AppScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { EzpzTheme(mode) { content() } }
        compose.onRoot().captureRoboImage("build/screenshots/app-$name.png")
    }

    @Test fun updateRequired() = shot("update-required") { UpdateRequiredScreen("1.8.0") }
    @Test fun dataConflict() = shot("data-conflict") { DataConflictScreen("pilot@example.com", 3, {}, {}) }
    @Test fun starting() = shot("starting") { StartingScreen() }
}
