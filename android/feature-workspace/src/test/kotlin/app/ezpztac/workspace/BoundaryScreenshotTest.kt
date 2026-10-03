package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Boundary drawing's sheet section and toolbar drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h640dp-xxhdpi")
class BoundaryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.padding(vertical = 12.dp)) { Column(Modifier.padding(horizontal = 12.dp)) { content() } } } }
        }
        compose.onRoot().captureRoboImage("build/screenshots/boundary-$name.png")
    }

    @Test fun sheetReady() = shot("sheet-ready") { BoundarySection(BoundaryUiState(canStart = true), BoundaryActions()) }

    @Test fun sheetClears() = shot("sheet-clears", ThemeMode.Light) { BoundarySection(BoundaryUiState(canStart = true, clears = true, drawnPoints = 4), BoundaryActions()) }

    @Test fun toolbarTwo() = shot("toolbar-two") { BoundaryToolbar(BoundaryUiState(drawing = true, points = 2), BoundaryActions()) }

    @Test fun toolbarCanFinish() = shot("toolbar-finish", ThemeMode.Night) { BoundaryToolbar(BoundaryUiState(drawing = true, points = 4, canFinish = true), BoundaryActions()) }

    @Test fun toolbarError() = shot("toolbar-error", ThemeMode.Light) {
        BoundaryToolbar(BoundaryUiState(drawing = true, points = 1, error = "Move the map to where the corner should go first."), BoundaryActions())
    }
}
