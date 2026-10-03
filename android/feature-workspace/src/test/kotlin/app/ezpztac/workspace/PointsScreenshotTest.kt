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
import app.ezpztac.model.LatLon
import app.ezpztac.sync.SyncStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The local points tab drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp-xxhdpi")
class PointsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.padding(vertical = 12.dp)) { Column(Modifier.padding(horizontal = 12.dp)) { content() } } } }
        }
        compose.onRoot().captureRoboImage("build/screenshots/points-$name.png")
    }

    private val north = PointSetRowUi("s1", "NORTH GEORGIA", 412, "#0A84FF", visible = true, sync = SyncStatus.SYNCED, conflictOf = null)
    private val farps = PointSetRowUi("s2", "FARPS", 1, "#FF453A", visible = false, sync = SyncStatus.PENDING, conflictOf = null)
    private val copy = PointSetRowUi("s3", "NORTH GEORGIA (from this device, 14:32)", 409, "#32D74B", visible = true, sync = SyncStatus.CONFLICT, conflictOf = "s1")

    private val held = HeldPointUi(
        setId = "s1", setName = "NORTH GEORGIA", pointId = "p1", rawName = "BLUE 1", at = LatLon(34.5123, -84.2231), elevationFt = 1730.0,
        description = "Landing zone, east of the road", group = "LZ", grid = "16S GD 66993 52949", latLon = "34.51230, -84.22310", useFor = "ROUTE 1: .SP", addTo = "ROUTE 1",
    )

    @Test fun empty() = shot("empty") { PointsContent(PointsUiState(), PointsActions()) }

    @Test fun list() = shot("list") { PointsContent(PointsUiState(sets = listOf(north, farps), note = "Imported NORTH GEORGIA: 412 points."), PointsActions()) }

    @Test fun heldPoint() = shot("held") { PointsContent(PointsUiState(sets = listOf(north), held = held), PointsActions()) }

    @Test fun conflictNight() = shot("conflict-night", ThemeMode.Night) { PointsContent(PointsUiState(sets = listOf(north, copy)), PointsActions()) }

    @Test fun error() = shot("error-light", ThemeMode.Light) {
        PointsContent(PointsUiState(sets = listOf(north), error = "This .LPS file contains no readable points."), PointsActions())
    }
}
