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
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The question put to the person when a file arrives, drawn for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h700dp-xxhdpi")
class IncomingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: IncomingUiState, mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(24.dp)) { IncomingOfferContent(state, IncomingActions({}, {}, {})) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/incoming-$name.png")
    }

    @Test fun threats() = shot("threats", IncomingUiState(IncomingOfferUi.Threats("SA-6 site.ths", 7), waiting = 1))

    @Test fun points() = shot("points", IncomingUiState(IncomingOfferUi.Points("NORTH GA.LPS", "NORTH GA", 1234)), mode = ThemeMode.Light)

    @Test fun problem() = shot("problem", IncomingUiState(IncomingOfferUi.Problem("notes.txt", "EZ/PZ opens AMPS local points (.LPS), threat (.ths) and mission (.msnx) files. This is none of them.")), mode = ThemeMode.Night)

    @Test fun mission() = shot("mission", IncomingUiState(IncomingOfferUi.Mission("NEPTUNE RUN.msnx", routes = 2, namedPoints = 14, aircraft = "UH-60L")))

    @Test fun savedThenNext() = shot("saved-then-next", IncomingUiState(IncomingOfferUi.Threats("b.ths", 2), result = "Saved NORTH GA with 1,234 points."))
}
