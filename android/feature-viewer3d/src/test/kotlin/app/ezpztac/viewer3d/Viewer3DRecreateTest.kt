package app.ezpztac.viewer3d

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.ezpztac.data.LidarPhase
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The 3D view across its activity being made again (a font size, a keyboard attached): it goes on as it was, its build not let go of. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Viewer3DRecreateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the host going as its activity is made again for a new configuration leaves an unsaved LZ-PZ's build going`() {
        val api = FakeLidar(built = false)
        val model = viewModel(api, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var shown by mutableStateOf(true)
        val target = Viewer3DTarget("lz-1", "LZ HAWK", 34.591552, -84.128225, saved = false)
        compose.setContent { if (shown) EzpzTheme(ThemeMode.Dark) { Viewer3DHost(target, night = false, onClose = {}, viewModel = model, surface = {}) } }
        compose.waitUntil(5_000) { model.state.value?.phase == LidarPhase.BUILDING }
        // As the system marks an activity it is making again (an indeterminate progress bar never settles outside a compose rule, so the real
        // recreation is tried below on a scene, which has none).
        ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", true)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertTrue("not let go of", api.calls.none { it.startsWith("release") })
        assertEquals(LidarPhase.BUILDING, model.state.value?.phase)
        ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", false)
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertEquals("the same build, not asked for again", 1, api.calls.count { it.startsWith("build") })
        // The host leaving for good (a sign-out takes the map away) lets the build go.
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertTrue(api.calls.any { it.startsWith("release") })
    }
}
