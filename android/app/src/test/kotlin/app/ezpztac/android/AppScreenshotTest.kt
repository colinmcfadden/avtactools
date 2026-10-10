package app.ezpztac.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ezpztac.android.packs.InviteNotices
import app.ezpztac.android.packs.JOINED
import app.ezpztac.android.packs.OFFLINE
import app.ezpztac.android.packs.joinedPack
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.missionpacks.InviteState
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

    // An invitation's toasts, at the foot of the screen where they sit above the sheet. The clock is turned by hand so the toast is up.
    private fun toast(name: String, state: InviteState, mode: ThemeMode = ThemeMode.Dark) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EzpzTheme(mode) {
                val host = remember { SnackbarHostState() }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ToastHost(host, Modifier.align(Alignment.BottomCenter))
                    InviteNotices(state, 1, host, onRetry = {}, onDismiss = {})
                }
            }
        }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("build/screenshots/app-$name.png")
    }

    @Test fun inviteJoined() = toast("invite-joined", InviteState.Joined(joinedPack(), JOINED))
    @Test fun inviteJoinedLight() = toast("invite-joined-light", InviteState.Joined(joinedPack(), JOINED), ThemeMode.Light)
    @Test fun inviteRetry() = toast("invite-retry", InviteState.Failed(OFFLINE, retryable = true))
    @Test fun inviteRetryNight() = toast("invite-retry-night", InviteState.Failed(OFFLINE, retryable = true), ThemeMode.Night)
}
