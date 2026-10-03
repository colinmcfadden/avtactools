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
import app.ezpztac.model.AircraftDraft
import app.ezpztac.sync.SyncStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The aircraft section drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1700dp-xxhdpi")
class AircraftScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: AircraftUiState, mode: ThemeMode = ThemeMode.Dark, canMake: Boolean = true) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { AircraftContent(state, canMake, AircraftActions(), startOpen = true) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/aircraft-$name.png")
    }

    private val uh60 = AircraftRowUi("uh60l", null, "uh60l", "UH-60L — UH-60L Black Hawk", "76 m spacing · 100 kt", own = false, chosen = true, usable = true, unverified = false, sync = null, conflictOf = null)
    private val ch47 = AircraftRowUi("ch47f", null, "ch47f", "CH-47F — CH-47F Chinook", "93 m spacing · 100 kt", own = false, chosen = false, usable = true, unverified = true, sync = null, conflictOf = null)
    private val mine = AircraftRowUi("u-1", "u-1", "alpha", "X-1 — Alpha Heli", "72 m spacing · 100 kt", own = true, chosen = false, usable = true, unverified = false, sync = SyncStatus.SYNCED, conflictOf = null)
    private val pending = mine.copy(key = "u-2", uuid = "u-2", slug = "", title = "MH-60M — Night Hawk", usable = false, sync = SyncStatus.PENDING)
    private val copy = mine.copy(key = "c-1", uuid = "c-1", title = "X-1 — Alpha Heli (from this device, 14:32)", usable = false, sync = SyncStatus.CONFLICT, conflictOf = "u-1")

    @Test fun list() = shot("list", AircraftUiState(rows = listOf(uh60, ch47, mine, pending)))

    @Test fun conflict() = shot("conflict", AircraftUiState(rows = listOf(mine, copy)), mode = ThemeMode.Night)

    @Test fun cannotMake() = shot("cannot-make", AircraftUiState(rows = listOf(uh60, ch47)), mode = ThemeMode.Light, canMake = false)

    @Test fun form() = shot("form", AircraftUiState(form = AircraftFormUi(null, AircraftDraft(name = "Night Hawk", designation = "MH-60M"))))

    @Test fun formRefused() = shot(
        "form-refused",
        AircraftUiState(form = AircraftFormUi("u-1", AircraftDraft(name = "Alpha Heli", designation = "X-1", rotorDiameterM = "61"), error = "Rotor diameter must be between 1 and 60.")),
        mode = ThemeMode.Light,
    )
}
