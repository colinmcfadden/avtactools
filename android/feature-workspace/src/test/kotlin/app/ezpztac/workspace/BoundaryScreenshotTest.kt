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

    @Test fun offerReady() = shot("offer-ready") { AnalyzeOfferBar(AnalyzeOfferUi(points = 5), null, BoundaryActions()) }

    @Test fun offerRunning() = shot("offer-running", ThemeMode.Light) { AnalyzeOfferBar(AnalyzeOfferUi(points = 5, stage = "Finding the landing area…"), null, BoundaryActions()) }

    @Test fun offerFailed() = shot("offer-failed", ThemeMode.Night) {
        AnalyzeOfferBar(AnalyzeOfferUi(points = 5, failed = "The analysis service could not be reached. Try again."), null, BoundaryActions())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun offerNarrowLargeText() = shot("offer-narrow-large") { AnalyzeOfferBar(AnalyzeOfferUi(points = 12), null, BoundaryActions()) }

    // The doghouse's bar, where its fields now are.
    private val doghouseHeld = HeldObjectUi(
        "Doghouse · [RP1]", "16S GD 66993 52949", heading = 105.0,
        doghouse = DoghouseUi(label = "[RP1]", time = "02+10", distanceKm = "5.2", airspeedKts = "40", feeds = "Sets the landing heading on the LZ card"),
        graphic = GraphicOf("lz-1", app.ezpztac.model.GraphicRef("doghouses", "dh2")),
    )

    @Test fun doghouseBar() = shot("held-doghouse") { HeldObjectBar(doghouseHeld, HeldObjectActions()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun doghouseBarNarrowLargeText() = shot("held-doghouse-narrow-large", ThemeMode.Light) { HeldObjectBar(doghouseHeld, HeldObjectActions()) }

    @Test fun offerFresh() = shot("offer-fresh") { AnalyzeOfferBar(AnalyzeOfferUi(points = 0, fresh = true, name = "LZ/PZ 3"), null, BoundaryActions()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun offerFreshFailedNarrow() = shot("offer-fresh-failed-narrow", ThemeMode.Light) {
        AnalyzeOfferBar(AnalyzeOfferUi(points = 0, fresh = true, name = "LZ/PZ 3", failed = "No distinct landing area was found here."), null, BoundaryActions())
    }
}

