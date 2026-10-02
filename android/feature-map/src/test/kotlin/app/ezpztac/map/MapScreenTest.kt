package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MapScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val styles = MapStyles.available("pk.t")

    private fun state(
        readout: Readout? = Readout.of(LatLon(34.783817, -84.08219)),
        error: String? = null,
        style: MapBaseStyle = styles.first(),
    ) = MapUiState(styles = styles, style = style, readout = readout, searchError = error)

    private fun show(state: MapUiState, gps: GpsState = GpsState.Off, camera: CameraState? = CameraState(LatLon(34.78, -84.08), 16.0, 30.0), mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            EzpzTheme(mode) {
                MapScreen(
                    state = state, camera = camera, gps = gps,
                    onSearch = { log += "search $it" }, onClearSearchError = { log += "clearError" }, onSelectStyle = { log += "style $it" },
                    onToggleGps = { log += "gps" }, onGpsPermissionResult = { log += "permission $it" }, onLocateMe = { log += "locate" },
                    onFaceNorth = { log += "north" },
                    map = { Box(Modifier.fillMaxSize().background(Color(0xFF3A5A40))) },          // stands in for the GPU-drawn map
                )
            }
        }
    }

    @Test
    fun `the readout under the crosshair is the grid first, then the degrees`() {
        show(state())
        compose.onNodeWithText("16S GD 66993 52949").assertIsDisplayed()
        compose.onNodeWithText("34.78382, -84.08219").assertIsDisplayed()
        compose.onNodeWithContentDescription("Map centre").assertIsDisplayed()
    }

    @Test
    fun `where there is no grid the readout says so`() {
        show(state(readout = Readout(null, "89.90000, 10.00000")))
        compose.onNodeWithText("No grid here").assertIsDisplayed()
    }

    @Test
    fun `before the camera has reported there is no readout and no compass`() {
        show(state(readout = null), camera = null)
        compose.onNodeWithText("No grid here").assertDoesNotExist()
        compose.onNode(hasContentDescription("Face north", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `typing a grid and pressing the keyboard's search sends it`() {
        show(state())
        compose.onNode(hasSetTextAction()).performTextInput("16S GD 66993 52949")
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("search 16S GD 66993 52949"), log)
    }

    @Test
    fun `the Go button sends it too`() {
        show(state())
        compose.onNode(hasSetTextAction()).performTextInput("34.5, -84.1")
        compose.onNodeWithText("Go").performClick()
        assertEquals(listOf("search 34.5, -84.1"), log)
    }

    @Test
    fun `a search that was not understood shows why, and typing again clears it`() {
        show(state(error = "Could not read that as a grid or a coordinate."))
        compose.onNodeWithText("Could not read that as a grid or a coordinate.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("1")
        assertEquals(listOf("clearError"), log)
    }

    @Test
    fun `the map button names the base map, and choosing another sends its id`() {
        show(state())
        compose.onNodeWithContentDescription("Base map: Satellite").performClick()
        compose.onNodeWithText("Satellite  ✓").assertIsDisplayed()                      // the one in use is marked
        compose.onNodeWithText("Topo").performClick()
        assertEquals(listOf("style topo"), log)
    }

    @Test
    fun `with no token only the FAA chart is in the menu`() {
        val only = MapStyles.available(null)
        show(MapUiState(styles = only, style = only.first()))
        compose.onNodeWithContentDescription("Base map: VFR").performClick()
        compose.onNodeWithText("Satellite").assertDoesNotExist()
    }

    @Test
    fun `the GPS button turns it on, and says what it is doing`() {
        show(state(), gps = GpsState.Off)
        compose.onNodeWithContentDescription("Turn the GPS on").performClick()
        assertEquals(listOf("gps"), log)
    }

    @Test
    fun `with a fix the button centres on it instead`() {
        val fix = UserLocation(LatLon(34.78, -84.08), 5f, null, null, 0L)
        show(state(), gps = GpsState.Tracking(fix))
        compose.onNodeWithContentDescription("GPS on. Tap to turn off").performClick()
        assertEquals(listOf("locate"), log)
    }

    @Test
    fun `a GPS that cannot work says why instead of just failing`() {
        show(state(), gps = GpsState.Unavailable("Location is not allowed. Turn it on for this app in the system settings."))
        compose.onNodeWithContentDescription("Location is not allowed. Turn it on for this app in the system settings.").assertIsDisplayed()
    }

    @Test
    fun `the north arrow faces north when tapped, and says how far the map is turned`() {
        show(state(), camera = CameraState(LatLon(34.78, -84.08), 16.0, 30.0))
        compose.onNodeWithContentDescription("Face north. The map is turned 30 degrees").performClick()
        assertEquals(listOf("north"), log)
    }
}
