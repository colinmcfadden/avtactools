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
import app.ezpztac.model.DiagramStatus
import app.ezpztac.sync.SyncStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tab drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots (without it they are not drawn). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class DiagramsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: DiagramsUiState, suggested: String? = null, mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            EzpzTheme(mode) {
                // On the sheet's surface, as it is in the app, which also sets the colour of text that names none.
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { DiagramsContent(state, suggested, DiagramsActions()) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/diagrams-$name.png")
    }

    private val rows = listOf(
        DiagramRow("a", "LZ HAWK", DiagramStatus.ANALYZED, "16S GD 66993 52949", SyncStatus.SYNCED, null, isActive = true),
        DiagramRow("b", "PZ OAK", DiagramStatus.TARGETED, "16S GD 67993 52949", SyncStatus.PENDING, null, isActive = false),
        DiagramRow("c", "LZ HAWK (from this device, 14:32)", DiagramStatus.DRAFT, null, SyncStatus.PENDING, "a", isActive = false),
    )

    @Test fun empty() = shot("empty", DiagramsUiState())
    @Test fun list() = shot("list", DiagramsUiState(rows = rows))
    @Test fun listNight() = shot("list-night", DiagramsUiState(rows = rows), mode = ThemeMode.Night)
    @Test fun listLight() = shot("list-light", DiagramsUiState(rows = rows), mode = ThemeMode.Light)
    @Test fun creating() = shot("creating", DiagramsUiState(rows = rows.take(1), creating = true), suggested = "16S GD 66993 52949")
    @Test fun error() = shot("error", DiagramsUiState(rows = rows.take(2), error = "That diagram is no longer here."))
}
