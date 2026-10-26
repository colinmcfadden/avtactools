package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The LZ/PZ plan's view switches on a 360 dp phone with large text (1.3, and 2.0): the slope map, its opacity and the LZ box, each said whole and in reach,
 * the slider across the panel. `-Pezpz.screenshots` draws them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
class LzPlanNarrowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show() = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LzPlanContent(
                        activeLz().copy(slopeOpacity = 100), GraphicsUiState(listed = true, canEdit = true), BoundaryUiState(canStart = true, clears = true), null,
                        LzPanelActions(), GraphicsActions(), GRID,
                    )
                }
            }
        }
    }

    private fun check(picture: String) {
        show()
        val slider = compose.onNode(hasContentDescription("Slope map opacity")).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        // The panel's width less its padding (328 dp at three pixels to the point): the percentage over it never takes the track's room.
        assertTrue("the slider is ${slider.size.width} px across", slider.size.width >= 328 * 3 - 3)
        compose.onNode(isToggleable() and hasText("Slope map")).assertIsDisplayed()
        compose.onNode(isToggleable() and hasText("LZ box")).performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/lz-view-$picture.png")
    }

    @Test
    fun `at 1,3 the switches and the slider are whole and in reach`() = check("narrow")

    @Test
    @Config(fontScale = 2.0f)
    fun `at 2,0 too`() = check("narrow-2x")
}
