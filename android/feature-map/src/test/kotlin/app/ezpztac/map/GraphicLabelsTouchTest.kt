package app.ezpztac.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What is written over the map takes no touches: a tap on an aircraft's name or on the feet between two aircraft is the map's, to hold what is under it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class GraphicLabelsTouchTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = LatLon(34.78, -84.08)

    private fun aircraft(id: String, at: LatLon, name: String?) = SceneAircraft(
        ref = GraphicRef("helicopters", id), at = at, rotationDeg = 0.0, diameterM = 16.36, clearanceM = 60.0, iconKey = "uh60", designation = "UH-60L",
        violating = false, selected = false, name = name,
    )

    @Test
    fun `a tap on an aircraft's name or on the feet between two is the map's`() {
        var taps = 0
        val west = LatLon(here.lat, here.lon - 0.0002)                                            // both on the screen at zoom 18
        val east = LatLon(here.lat, here.lon + 0.0002)
        val scene = GraphicsScene(
            aircraft = listOf(aircraft("1", west, "CHALK 1"), aircraft("2", east, null)),
            separations = listOf(SceneSeparation(west, east, gapFt = 240.0, violating = false)),
        )
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().clickable { taps++ })                              // stands in for the map, under what is written over it
                GraphicLabels({ CameraState(here, 18.0) }, scene)
            }
        }
        compose.onNodeWithContentDescription("CHALK 1").performClick()
        compose.onNodeWithContentDescription("Rotor edges 240 ft apart").performClick()
        assertEquals(2, taps)
    }
}
