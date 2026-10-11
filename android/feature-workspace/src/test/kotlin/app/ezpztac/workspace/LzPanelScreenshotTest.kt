package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.data.PlaceTool
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.DiagramStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/** The LZ/PZ panel's pages drawn on the sheet's colour, beside the design's AP-Saved, AP-LzPlan and AP-SaveLz. `-Pezpz.screenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class LzPanelScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, scroll: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(if (scroll) Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp) else Modifier) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/lz-$name.png")
    }

    @Test fun home() = shot("home") { LzHomeContent(homeState(), LzPanelActions(), GRID) }
    @Test fun homeSaved() = shot("home-saved") { LzHomeContent(homeState(activeLz(name = "LZ HAWK (BETA)")), LzPanelActions(), GRID) }
    @Test fun homeNight() = shot("home-night", ThemeMode.Night) { LzHomeContent(homeState(), LzPanelActions(), GRID) }
    @Test fun empty() = shot("empty") { LzHomeContent(DiagramsUiState(), LzPanelActions(), GRID) }

    @Test
    fun plan() = shot("plan") {
        LzPlanContent(
            activeLz(), GraphicsUiState(canEdit = true), BoundaryUiState(canStart = true, clears = true), PlacementUi(PlaceTool.HELICOPTER, 2, null),
            LzPanelActions(), GraphicsActions(), GRID,
        )
    }

    @Test
    fun planNotAnalysed() = shot("plan-not-analysed") {
        LzPlanContent(activeLz(status = DiagramStatus.TARGETED), GraphicsUiState(), BoundaryUiState(canStart = true), null, LzPanelActions(), GraphicsActions(), GRID)
    }

    @Test fun save() = shot("save", scroll = false) { SaveLzContent(activeLz(), listOf("LZ HAWK"), onSave = {}, onCancel = {}) }

    @Test
    fun placingToolbar() = shot("placing", scroll = false) {
        Box(Modifier.padding(top = 200.dp)) { PlacementToolbar(PlacementUi(PlaceTool.HELICOPTER, 2, null), {}, {}, {}, {}) }
    }
}
