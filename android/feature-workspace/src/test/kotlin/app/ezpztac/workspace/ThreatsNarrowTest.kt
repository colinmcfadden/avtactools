package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Threats panel's rows on a 360 dp phone with large text (1.3): every chip's words whole, nothing cut. `-Pezpz.screenshots` draws it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
class ThreatsNarrowTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = ThreatMaskUi.Status.entries.mapIndexed { i, status ->
        ThreatRowUi("t$i", "Threat ${i + 1}", "SHGPEWRR------", "SAM Launcher", "16S GC 12345 67890", "Detect 25 nm · Engage 15 nm", visible = true, held = false, mask = status)
    }

    private fun show() = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { ThreatsContent(ThreatsUiState(threats = rows), ThreatsActions()) }
            }
        }
    }

    @Test
    fun `each mask chip says its words whole`() {
        show()
        for (words in listOf("Terrain mask on", "Working out the mask…", "Mask failed", "Mask out of date", "Mask: nothing in sight", "Rings only")) {
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            // A chip that does not fit ends in an ellipsis (StateChip), and one that is too short to show its line is cut at the foot.
            val l = layout.single()
            assertFalse("\"$words\" is cut", l.isLineEllipsized(l.lineCount - 1) || l.didOverflowHeight)
        }
    }

    @Test
    fun picture() {
        show()
        compose.onRoot().captureRoboImage("build/screenshots/threats-360.png")
    }
}
