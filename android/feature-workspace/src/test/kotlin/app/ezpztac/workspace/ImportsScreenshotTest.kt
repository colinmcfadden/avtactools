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
import app.ezpztac.data.ThreatFileImport
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

/**
 * The Imports panel, the import review and the Import menu drawn, to be looked at beside the design's AP-Imports, AP-ImportReview and AP-Import:
 * `-Pezpz.screenshots` writes them to build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ImportsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, padded: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = if (padded) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background) {
                    if (padded) Box(Modifier.padding(vertical = 12.dp)) { Column(Modifier.padding(horizontal = 16.dp)) { content() } } else content()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/imports-$name.png")
    }

    private val north = PointSetRowUi("s1", "NORTH GA POINTS", 1234, "#FF9F0A", visible = true, sync = SyncStatus.SYNCED, conflictOf = null)
    private val taxi = PointSetRowUi("s2", "KGVL TAXI POINTS", 46, "#BF5AF2", visible = false, sync = SyncStatus.PENDING, conflictOf = null, inSession = true)
    private val panel = PointsUiState(
        sets = listOf(north, taxi),
        missions = listOf(MissionFileUi("m1", "GOAT SUCKER", "GOAT SUCKER.msnx", 2, conflictOf = null)),
        threatFiles = listOf(ThreatFileImport("THREATS_OCT.ths", 7)),
    )
    private val held = HeldPointUi(
        setId = "s1", setName = "NORTH GA POINTS", pointId = "p1", rawName = "BLUE 1", at = LatLon(34.5123, -84.2231), elevationFt = 1730.0,
        description = "Landing zone, east of the road", group = "LZ", grid = "16S GD 66993 52949", latLon = "34.51230, -84.22310", useFor = "ROUTE 1: .SP", addTo = "ROUTE 1",
    )

    private val review = IncomingUiState(
        listOf(
            ImportItemUi.Points(1, "NORTH_GA_POINTS.LPS", "82 KB", 1234, "NORTH GA POINTS", ImportPlace.LIBRARY),
            ImportItemUi.Threats(2, "THREATS_OCT.ths", "12 KB", 7),
            ImportItemUi.Mission(3, "GOAT_SUCKER.msnx", "1.5 MB", routes = 2, namedPoints = 91, aircraft = "UH-60L"),
            ImportItemUi.Problem(4, "notes.docx", "EZ/PZ opens AMPS local points (.LPS), threat (.ths) and mission (.msnx) files. This is none of them."),
        ),
    )

    @Test fun panel() = shot("panel") { ImportsContent(panel, ImportsActions(openMission = {})) }

    @Test fun panelEmpty() = shot("panel-empty") { ImportsContent(PointsUiState(), ImportsActions(openMission = {})) }

    @Test fun panelHeld() = shot("panel-held") { ImportsContent(panel.copy(held = held), ImportsActions(openMission = {})) }

    @Test fun panelLight() = shot("panel-light", ThemeMode.Light) { ImportsContent(panel, ImportsActions(openMission = {})) }

    @Test fun panelNight() = shot("panel-night", ThemeMode.Night) { ImportsContent(panel, ImportsActions(openMission = {})) }

    @Test fun review() = shot("review", padded = false) { ImportReviewContent(review, ImportReviewActions()) }

    @Test fun reviewSession() = shot("review-session", ThemeMode.Light, padded = false) {
        ImportReviewContent(IncomingUiState(listOf(ImportItemUi.Points(1, "KGVL_TAXI.LPS", "3 KB", 46, "KGVL TAXI", ImportPlace.SESSION))), ImportReviewActions())
    }

    @Test fun reviewNight() = shot("review-night", ThemeMode.Night, padded = false) { ImportReviewContent(review, ImportReviewActions()) }

    @Test fun menu() = shot("menu") { ImportSheetContent(threatsAllowed = true, onChoose = {}) }
}
