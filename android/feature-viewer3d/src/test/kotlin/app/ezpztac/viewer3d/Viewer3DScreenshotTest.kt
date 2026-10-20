package app.ezpztac.viewer3d

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ezpztac.data.LidarPhase
import app.ezpztac.data.LidarProblem
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 3D view's page in each state, on a 360 and a 412 dp phone and a tablet, in the dark and night palettes and at larger text. `-Pezpz.screenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class Viewer3DScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, ui: Viewer3DUi, mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent { EzpzTheme(mode) { Viewer3DPage(ui, Viewer3DActions()) } }
        compose.onRoot().captureRoboImage("build/screenshots/viewer3d-$name.png")
    }

    private val name = "LZ HAWK (ROCK)"
    private val building = Viewer3DUi(name, LidarPhase.BUILDING, stage = "building tiles", ahead = 2, elapsedS = 247, saved = true)

    @Test fun lookingUp() = shot("looking-up", Viewer3DUi(name, LidarPhase.RESOLVING))
    @Test fun buildingSaved() = shot("building-saved", building)
    @Test fun buildingUnsaved() = shot("building-unsaved", building.copy(stage = "waiting", saved = false, elapsedS = null))
    @Test fun buildingNight() = shot("building-night", building, ThemeMode.Night)
    @Test fun missing() = shot("missing", Viewer3DUi(name, LidarPhase.MISSING))
    @Test fun failed() = shot("failed", Viewer3DUi(name, LidarPhase.ERROR, problem = LidarProblem.BUILD_FAILED, buildFailed = true))
    @Test fun ready() = shot("ready", Viewer3DUi(name, LidarPhase.AVAILABLE, noGround = true))

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.3f)
    fun narrowLarge() = shot("building-360-1.3", building.copy(name = "LZ HAWK (ROCK) NORTH OF THE RIDGE LINE"))

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 2.0f)
    fun narrowLargest() = shot("failed-360-2.0", Viewer3DUi(name, LidarPhase.ERROR, problem = LidarProblem.LOOKUP_UNREACHABLE))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tablet() = shot("building-tablet", building)
}
