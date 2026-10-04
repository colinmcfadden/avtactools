package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
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
import app.ezpztac.model.ThreatDraft
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The threats tab drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2200dp-xxhdpi")
class ThreatsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: ThreatsUiState, mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { ThreatsContent(state, ThreatsActions(), crosshairGrid = "16S GD 66993 52949") }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/threats-$name.png")
    }

    private val sa6 = ThreatRowUi("t1", "SA-6", "SHGPEWRR------", "SAM Launcher", "16S GD 66993 52949", "Detection 25 nm · Engagement 15 nm", visible = true, held = true)
    private val zsu = ThreatRowUi("t2", "ZSU-23", "SHAPMFF-------", "Custom symbol", "16S GD 67993 52949", "Engagement 2.5 nm", visible = false, held = false)
    private val held = HeldThreatUi(
        "t1", "SA-6", "SAM Launcher", "16S GD 66993 52949", "34.78382, -84.08219", "HUMINT", "Seen at 0300",
        listOf("Detection · 25 nm · antenna 20 ft AGL · rings on", "Engagement · 15 nm · antenna 20 ft MSL · rings off"),
    )

    @Test fun list() = shot("list", ThreatsUiState(threats = listOf(sa6, zsu), held = held, note = "Imported 2 threats."))

    @Test fun empty() = shot("empty", ThreatsUiState(), mode = ThemeMode.Light)

    @Test fun form() = shot(
        "form-refused",
        ThreatsUiState(editing = ThreatEditUi(null, LatLon(34.78, -84.08), ThreatDraft.forNew(0), error = "Detection range is not a number.")),
        mode = ThemeMode.Night,
    )
}
