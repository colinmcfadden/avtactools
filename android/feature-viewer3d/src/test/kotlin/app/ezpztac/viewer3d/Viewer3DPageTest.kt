package app.ezpztac.viewer3d

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import app.ezpztac.data.LidarPhase
import app.ezpztac.data.LidarProblem
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 3D view's page: its bar, each state in the web's words (a failure in the app's own), and that it is the only thing taking touches and Back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class Viewer3DPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private val actions = Viewer3DActions(close = { log += "close" }, retry = { log += "retry" })

    private fun show(ui: Viewer3DUi) = compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DPage(ui, actions) } }

    @Test
    fun `its bar says 3D and the LZ-PZ's name, and its close button closes it`() {
        show(Viewer3DUi("LZ HAWK (ROCK)", LidarPhase.RESOLVING))
        compose.onNodeWithText("3D · LZ HAWK (ROCK)").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "3D view of LZ HAWK (ROCK)")).assertExists()
        compose.onNodeWithContentDescription("Close 3D view").performClick()
        assertEquals(listOf("close"), log)
    }

    @Test
    fun `while it looks, it says so`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.RESOLVING))
        compose.onNodeWithText("Looking up").assertIsDisplayed()
        compose.onNodeWithText("Checking for a point cloud at this place…").assertIsDisplayed()
    }

    @Test
    fun `while it builds it says the stage in words, how many builds are ahead, the clock, and what closing does`() {
        var ui by mutableStateOf(Viewer3DUi("LZ HAWK", LidarPhase.BUILDING, stage = "waiting", ahead = 2, saved = false))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DPage(ui, actions) } }
        compose.onNodeWithText("Building point cloud").assertIsDisplayed()
        compose.onNodeWithText("Queued — 2 ahead in the queue").assertIsDisplayed()
        compose.onNodeWithText("Closing this stops the build. Save the LZ/PZ to keep it building.", substring = true).assertIsDisplayed()
        ui = Viewer3DUi("LZ HAWK", LidarPhase.BUILDING, stage = "building tiles", elapsedS = 247, saved = true)
        compose.waitForIdle()
        compose.onNodeWithText("Building 3D tiles").assertIsDisplayed()
        compose.onNodeWithText("4:07").assertIsDisplayed()                                       // the service's own count, past the page's
        compose.onNodeWithText("you may close this and the build finishes in the background", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a server that cannot build says so, with no operator's command, and Check again asks again`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.MISSING))
        compose.onNodeWithText("Not generated").assertIsDisplayed()
        compose.onNodeWithText("Check again").performClick()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `a failure says what failed in the app's own words, with Retry`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.ERROR, problem = LidarProblem.QUEUE_FULL, buildFailed = true))
        compose.onNodeWithText("Build failed").assertIsDisplayed()
        compose.onNodeWithText("The build queue is full. Try again in a few minutes.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `with the cloud in hand the scene's area says so, and no terrain there is said over it`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.AVAILABLE, noGround = true, tileset = "/lidar/tilesets/k/tileset.json"))
        compose.onNodeWithText("Point cloud ready").assertIsDisplayed()
        compose.onNodeWithText(Viewer3DWords.NO_GROUND).assertIsDisplayed()
    }

    @Test
    fun `nothing under the page takes a touch while it is up`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Green).clickable { log += "map" })
                    Viewer3DPage(Viewer3DUi("LZ HAWK", LidarPhase.AVAILABLE), actions)
                }
            }
        }
        compose.onRoot().performTouchInput { click(center) }
        compose.onRoot().performTouchInput { click(bottomCenter.copy(y = bottomCenter.y - 20f)) }
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `Back closes it before anything under it hears it, and with no LZ-PZ it is not there at all`() {
        val model = Viewer3DViewModel(lidarBuilds(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)), FakeProbe())
        var target by mutableStateOf<Viewer3DTarget?>(Viewer3DTarget("lz-1", "LZ HAWK", 34.59, -84.13, saved = false))
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                BackHandler { log += "under" }
                Viewer3DHost(target, night = false, onClose = { log += "close"; target = null }, viewModel = model)
            }
        }
        compose.onNodeWithText("3D · LZ HAWK").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("close"), log)
        compose.onNodeWithText("3D · LZ HAWK").assertDoesNotExist()
        // Gone, it leaves Back to what was under it.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(listOf("close", "under"), log)
    }
}
