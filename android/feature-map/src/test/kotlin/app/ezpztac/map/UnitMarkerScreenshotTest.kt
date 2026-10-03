package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
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
 * Units over a stand-in for imagery, with the real pre-rendered presets from the app's assets: a preset draws as the web draws it, one that
 * needs the JavaScript sandbox (which a unit test does not have) and an older image unit show the plain marker, and nothing is ever missing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h300dp-xxhdpi")
class UnitMarkerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val renderer = DefaultSymbolRenderer(listOf(PresetSymbols(ApplicationProvider.getApplicationContext<android.content.Context>().assets)))

    private fun unit(id: String, sidc: String?, designation: String = "", higher: String = "", selected: Boolean = false) =
        SceneUnit(GraphicRef("units", id), app.ezpztac.model.LatLon(0.0, 0.0), sidc, designation, higher, selected)

    private fun show(vararg units: Pair<SceneUnit, ScreenPoint>) {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalSymbolRenderer provides renderer) {
                    Box(Modifier.size(411.dp, 300.dp).background(Color(0xFF55703F))) { units.forEach { (u, at) -> UnitMarker(PlacedUnit(u, at)) } }
                }
            }
        }
    }

    @Test
    fun `presets draw as the web draws them, the rest as a plain marker, and each says what it is`() {
        show(
            unit("a", "SFGPUCI--------") to ScreenPoint(150.0, 150.0),
            unit("b", "SHGPUCI--------") to ScreenPoint(400.0, 150.0),
            unit("c", "SNGPUCIL-------") to ScreenPoint(650.0, 150.0),
            unit("d", "SUGPUCIA-------") to ScreenPoint(900.0, 150.0),
            unit("e", "SFGPUCI--------", designation = "A/1-171", higher = "2-101") to ScreenPoint(150.0, 450.0),
            unit("f", null, designation = "TANK") to ScreenPoint(450.0, 450.0),
            unit("g", "SHGPEWRR------") to ScreenPoint(750.0, 450.0),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Friendly unit").assertIsDisplayed()                                          // a preset: the web's picture
        compose.onNodeWithContentDescription("Friendly unit A/1-171 of 2-101 (symbol not available)").assertIsDisplayed()   // labels need the sandbox
        compose.onNodeWithContentDescription("Unknown unit TANK (symbol not available)").assertIsDisplayed()               // an older image unit
        compose.onRoot().captureRoboImage("build/screenshots/units.png")
    }

    @Test
    fun `an older image unit has the plain marker with its designation`() {
        show(unit("f", null, designation = "TANK") to ScreenPoint(300.0, 300.0))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Unknown unit TANK (symbol not available)").assertIsDisplayed()
    }

    @Test
    fun `without a renderer every unit shows the plain marker`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.size(411.dp, 300.dp)) { UnitMarker(PlacedUnit(unit("a", "SHGPUCI--------", "B/2"), ScreenPoint(300.0, 300.0))) } } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hostile unit B/2 (symbol not available)").assertIsDisplayed()
    }
}
