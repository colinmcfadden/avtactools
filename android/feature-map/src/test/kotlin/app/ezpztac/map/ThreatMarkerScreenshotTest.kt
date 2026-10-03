package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.symbols.DefaultSymbolRenderer
import app.ezpztac.symbols.LocalSymbolRenderer
import app.ezpztac.symbols.PresetSymbols
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Threats over a stand-in for imagery, with the real pre-rendered presets from the app's assets: a preset draws as the web draws it, one that needs the JavaScript
 * sandbox (which a unit test does not have) shows the web's red diamond, and a threat is never missing from the map.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h300dp-xxhdpi")
class ThreatMarkerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val renderer = DefaultSymbolRenderer(listOf(PresetSymbols(ApplicationProvider.getApplicationContext<android.content.Context>().assets)))

    private fun threat(id: String, name: String, sidc: String, selected: Boolean = false) = ThreatPin(id, name, sidc, LatLon(0.0, 0.0), selected)

    private fun show(mode: ThemeMode = ThemeMode.Dark, withRenderer: Boolean = true, vararg threats: Pair<ThreatPin, ScreenPoint>) {
        compose.setContent {
            EzpzTheme(mode) {
                CompositionLocalProvider(LocalSymbolRenderer provides if (withRenderer) renderer else null) {
                    Box(Modifier.size(411.dp, 300.dp).background(Color(0xFF55703F))) { threats.forEach { (t, at) -> ThreatMarker(PlacedThreat(t, at)) } }
                }
            }
        }
    }

    @Test
    fun `a preset draws as the web draws it, with its name, and a held one has a ring`() {
        show(
            threats = arrayOf(
                threat("a", "SA-6", "SHGPEWRR------") to ScreenPoint(200.0, 200.0),
                threat("b", "ZSU-23 held", "SHGPEWAH------", selected = true) to ScreenPoint(700.0, 200.0),
                threat("c", "Odd symbol", "SHAPMFF-------") to ScreenPoint(200.0, 520.0),                 // not a preset: needs the sandbox
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Threat SA-6").assertIsDisplayed()
        compose.onNodeWithContentDescription("Threat ZSU-23 held").assertIsDisplayed()
        compose.onNodeWithContentDescription("Held threat ZSU-23 held").assertIsDisplayed()                  // the ring round the held one
        compose.onNodeWithContentDescription("Threat Odd symbol (symbol not available)").assertIsDisplayed()
        compose.onNodeWithText("SA-6").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screenshots/threats.png")
    }

    @Test
    fun `without a renderer every threat shows the diamond, so none is missing`() {
        show(withRenderer = false, threats = arrayOf(threat("a", "SA-6", "SHGPEWRR------") to ScreenPoint(300.0, 300.0)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Threat SA-6 (symbol not available)").assertIsDisplayed()
        compose.onNodeWithText("SA-6").assertIsDisplayed()
    }
}
