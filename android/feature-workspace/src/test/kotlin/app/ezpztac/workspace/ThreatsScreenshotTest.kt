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
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.model.ThreatDraft
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Threats panel and the threat's form, beside the design's AP-Threats and AP-Confirm. `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ThreatsScreenshotTest {
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
        compose.onRoot().captureRoboImage("build/screenshots/threats-$name.png")
    }

    private val one = ThreatRowUi("t1", "Threat 1", "SHGPEWRR------", "SAM Launcher", "16S GC 12345 67890", "Detect 25 nm · Engage 15 nm", visible = true, held = false, mask = ThreatMaskUi.Status.SHOWING)
    private val two = ThreatRowUi("t2", "Threat 2", "SHGPEWRR------", "SAM Launcher", "16S GD 67993 52949", "Detect 8 nm · Engage 5 nm", visible = true, held = false)

    @Test fun panel() = shot("panel") { ThreatsContent(ThreatsUiState(threats = listOf(one, two)), ThreatsActions(), crosshairGrid = "16S GD 66993 52949") }
    @Test fun panelNight() = shot("panel-night", ThemeMode.Night) { ThreatsContent(ThreatsUiState(threats = listOf(one, two)), ThreatsActions(), crosshairGrid = "16S GD 66993 52949") }
    @Test fun empty() = shot("empty") { ThreatsContent(ThreatsUiState(), ThreatsActions(), crosshairGrid = "16S GD 66993 52949") }

    private fun editing(id: String?, error: String? = null) = ThreatEditUi(
        id, LatLon(34.78, -84.08), ThreatDraft.forNew(0), error = error, grid = "16S GD 66993 52949", mask = if (id != null) ThreatMaskUi() else null,
    )

    @Test fun form() = shot("form", scroll = false) { ThreatFormContent(editing("t1"), ThreatsActions(), "16S GD 66993 52949") }
    @Test fun formNew() = shot("form-new", scroll = false) { ThreatFormContent(editing(null, error = "Detection range is not a number."), ThreatsActions(), null) }
    @Test fun formNight() = shot("form-night", ThemeMode.Night, scroll = false) { ThreatFormContent(editing("t1"), ThreatsActions(), "16S GD 66993 52949") }
}
