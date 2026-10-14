package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.ezpztac.data.ThreatFileImport
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.sync.SyncStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The import review and the Imports panel on a 360 dp phone with large text (1.3): the words that say where a file goes, and the buttons that act on it, whole.
 * `-Pezpz.screenshots` draws them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
class ImportsNarrowTest {
    @get:Rule
    val compose = createComposeRule()

    private val review = IncomingUiState(
        listOf(
            ImportItemUi.Points(1, "NORTH_GA_POINTS_OCTOBER_2026.LPS", "82 KB", 1234, "NORTH GA POINTS", ImportPlace.SESSION),
            ImportItemUi.Threats(2, "THREATS_OCT.ths", "12 KB", 7),
            ImportItemUi.Mission(3, "GOAT_SUCKER.msnx", "1.5 MB", routes = 2, namedPoints = 91, aircraft = "UH-60L"),
        ),
    )

    private val panel = PointsUiState(
        sets = listOf(
            PointSetRowUi("s1", "NORTH GA POINTS", 1234, "#FF9F0A", visible = true, sync = SyncStatus.SYNCED, conflictOf = null),
            PointSetRowUi("s2", "KGVL TAXI POINTS", 46, "#BF5AF2", visible = false, sync = SyncStatus.PENDING, conflictOf = null, inSession = true),
        ),
        missions = listOf(MissionFileUi("m1", "GOAT SUCKER", "GOAT SUCKER.msnx", 2, conflictOf = null)),
        threatFiles = listOf(ThreatFileImport("THREATS_OCT.ths", 7)),
    )

    private fun showReview() = compose.setContent { EzpzTheme(ThemeMode.Dark) { ImportReviewContent(review, ImportReviewActions()) } }

    private fun showPanel() = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { ImportsContent(panel, ImportsActions(openMission = {})) }
            }
        }
    }

    /** Whether [words], wherever they are, are drawn whole: not ending in an ellipsis, not cut at the foot. */
    private fun assertWhole(words: String) {
        for (node in compose.onAllNodesWithText(words, useUnmergedTree = true).fetchSemanticsNodes()) {
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.isLineEllipsized(l.lineCount - 1) || l.didOverflowHeight)
        }
    }

    @Test
    fun `the review's places and buttons say their words whole`() {
        showReview()
        for (words in listOf("Library", "This session", "This device only", "Cancel", "Import 3 files")) assertWhole(words)
    }

    @Test
    fun `the panel's places and buttons say their words whole`() {
        showPanel()
        for (words in listOf("Library", "This session", "This device only", "Open in Routes", "Open in Threats")) assertWhole(words)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at the largest text, the review's places say their words whole`() {
        showReview()
        for (words in listOf("Library", "This session", "This device only")) assertWhole(words)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at the largest text, the panel's places say their words whole`() {
        showPanel()
        for (words in listOf("Library", "This session", "This device only")) assertWhole(words)
    }

    @Test
    fun reviewPicture() {
        showReview()
        compose.onRoot().captureRoboImage("build/screenshots/imports-review-360.png")
    }

    @Test
    fun panelPicture() {
        showPanel()
        compose.onRoot().captureRoboImage("build/screenshots/imports-panel-360.png")
    }
}
