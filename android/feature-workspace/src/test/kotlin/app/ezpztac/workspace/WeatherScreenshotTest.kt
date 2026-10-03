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
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The weather tiles drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xxhdpi")
class WeatherScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val now = 10_000_000_000L

    private fun weather(
        hasReport: Boolean = true, windSpeed: String = "12", windGust: String? = "G20", windFrom: Int? = 270, windVariable: Boolean = false, temp: String = "18",
        altimeter: String = "30.00", station: String? = "KRYY · Cobb County Airport · 0.7 mi", category: String? = "VFR", fetchedAgo: Long? = 12 * minute,
        fetching: Boolean = false, failure: String? = null, notams: Notams? = Notams.Clear,
    ) = WeatherUi(hasReport, windSpeed, windGust, windFrom, windVariable, temp, altimeter, station, category, fetchedAgo?.let { now - it }, fetching, failure, notams)

    private fun shot(name: String, weather: WeatherUi, mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { WeatherSection(weather, now) {} }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/weather-$name.png")
    }

    private val notams = Notams.Listed(
        listOf(
            NotamGroup("Obstruction", listOf("!FDC 6/1234 CRANE 340FT AGL 3NM N", "!FDC 6/2222 TOWER LGT OTS")),
            NotamGroup("Airspace", listOf("!ZTL 10/044 TEMPORARY FLIGHT RESTRICTION")),
        ),
    )

    @Test fun fresh() = shot("fresh", weather(notams = notams))

    @Test fun clear() = shot("clear", weather(), mode = ThemeMode.Light)

    @Test fun stale() = shot("stale", weather(fetchedAgo = 3 * 60 * minute, failure = "No signal: the weather could not be fetched.", notams = Notams.Unavailable), mode = ThemeMode.Night)

    @Test fun neverFetched() = shot("never", weather(hasReport = false, windSpeed = "--", windGust = null, windFrom = null, temp = "--", altimeter = "--", station = null, category = null, fetchedAgo = null, notams = null))
}
