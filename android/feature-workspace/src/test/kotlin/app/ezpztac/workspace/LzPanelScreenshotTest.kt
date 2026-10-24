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

    // With 3D: the card's buttons, the plan's, and each other open LZ/PZ's icon; on a narrow phone at large text the buttons wrap and their words go under.
    private val withTarget = activeLz().copy(target = app.ezpztac.model.LatLon(34.783817, -84.08219))
    private fun withTargets() = homeState(withTarget).let { it.copy(open = it.open.map { row -> row.copy(hasTarget = true) }) }

    @Test fun home3d() = shot("home-3d") { LzHomeContent(withTargets(), LzPanelActions(), GRID) }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 1.3f)
    fun home3dNarrow() = shot("home-3d-360-1.3") { LzHomeContent(withTargets(), LzPanelActions(), GRID) }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi", fontScale = 2.0f)
    fun home3dLargest() = shot("home-3d-360-2.0") { LzHomeContent(withTargets(), LzPanelActions(), GRID) }

    @Test
    fun plan3d() = shot("plan-3d") {
        LzPlanContent(withTarget, GraphicsUiState(listed = true, canEdit = true), BoundaryUiState(canStart = true, clears = true), null, LzPanelActions(), GraphicsActions(), GRID)
    }
    @Test fun homeSaved() = shot("home-saved") { LzHomeContent(homeState(activeLz(name = "LZ HAWK (BETA)")), LzPanelActions(), GRID) }
    @Test fun homeNight() = shot("home-night", ThemeMode.Night) { LzHomeContent(homeState(), LzPanelActions(), GRID) }
    @Test fun empty() = shot("empty") { LzHomeContent(DiagramsUiState(), LzPanelActions(), GRID) }

    // The eye on each LZ/PZ also open, one of them hidden from the map (the owner's request of 2026-10-21).
    @Test fun homeEyes() = shot("home-eyes") { LzHomeContent(homeState(), LzPanelActions(toggleOnMap = {}), GRID, hidden = setOf("lz-3")) }

    @Test fun homeEyesLight() = shot("home-eyes-light", ThemeMode.Light) { LzHomeContent(homeState(), LzPanelActions(toggleOnMap = {}), GRID, hidden = setOf("lz-3")) }

    @Test
    @Config(qualifiers = "w360dp-h915dp-xxhdpi", fontScale = 1.3f)
    fun homeEyesNarrowLarge() = shot("home-eyes-narrow-large") { LzHomeContent(homeState(), LzPanelActions(toggleOnMap = {}), GRID, hidden = setOf("lz-3")) }

    @Test fun homeWithTools() = shot("home-tools") {
        val current = activeLz()
        LzHomeContent(homeState(current), LzPanelActions(), GRID, tools = {
            LzToolsSection(current, GraphicsUiState(listed = true, canEdit = true), BoundaryUiState(canStart = true, clears = true), null, LzPanelActions(), GRID)
        })
    }

    @Test
    fun plan() = shot("plan") {
        LzPlanContent(
            activeLz(), GraphicsUiState(listed = true, canEdit = true), BoundaryUiState(canStart = true, clears = true), PlacementUi(PlaceTool.HELICOPTER, 2, null),
            LzPanelActions(), GraphicsActions(), GRID,
        )
    }

    /** The analysis's view switches (the design's LzPlan): the slope map with its opacity, and the LZ box, in each palette. */
    private fun viewShot(name: String, mode: ThemeMode, current: ActiveDiagramUi) = shot(name, mode) {
        LzPlanContent(current, GraphicsUiState(listed = true, canEdit = true), BoundaryUiState(canStart = true, clears = true), null, LzPanelActions(), GraphicsActions(), GRID)
    }

    @Test fun planView() = viewShot("plan-view", ThemeMode.Dark, activeLz().copy(slopeOpacity = 30))
    @Test fun planViewNight() = viewShot("plan-view-night", ThemeMode.Night, activeLz().copy(slopeOpacity = 30))
    @Test fun planViewLight() = viewShot("plan-view-light", ThemeMode.Light, activeLz(outlineShown = false))
    @Test fun planViewSlopeOff() = viewShot("plan-view-slope-off", ThemeMode.Dark, activeLz(slopeShown = false, outlineShown = false))

    @Test
    fun planNotAnalysed() = shot("plan-not-analysed") {
        LzPlanContent(activeLz(status = DiagramStatus.TARGETED), GraphicsUiState(), BoundaryUiState(canStart = true), null, LzPanelActions(), GraphicsActions(), GRID)
    }

    @Test fun save() = shot("save", scroll = false) { SaveLzContent(activeLz(), listOf("lz-9" to "LZ HAWK"), onSave = { _, _ -> }, onCancel = {}) }

    @Test
    fun placingToolbar() = shot("placing", scroll = false) {
        Box(Modifier.padding(top = 200.dp)) { PlacementToolbar(PlacementUi(PlaceTool.HELICOPTER, 2, null), {}, {}, {}, {}) }
    }
}
