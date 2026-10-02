package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The map's overlays over a plain green stand-in for the imagery (the GPU-drawn map cannot be drawn here). `-Pezpz.screenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MapScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode, gps: GpsState = GpsState.Off, error: String? = null) {
        val styles = MapStyles.available("pk.t")
        compose.setContent {
            EzpzTheme(mode) {
                MapScreen(
                    state = MapUiState(styles, styles.first(), Readout.of(LatLon(34.783817, -84.08219)), error, gps),
                    camera = CameraState(LatLon(34.78, -84.08), 16.0, 25.0), gps = gps,
                    onSearch = {}, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                    map = { Box(Modifier.fillMaxSize().background(Color(0xFF55703F))) },
                )
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/map-$name.png")
    }

    @Test fun dark() = shot("dark", ThemeMode.Dark, gps = GpsState.Tracking(UserLocation(LatLon(34.78, -84.08), 5f, null, null, 0L)))
    @Test fun light() = shot("light", ThemeMode.Light, error = "Could not read that as a grid or a coordinate.")
    @Test fun night() = shot("night", ThemeMode.Night)
}
