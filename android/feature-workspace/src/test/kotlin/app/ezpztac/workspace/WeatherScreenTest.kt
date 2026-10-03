package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The weather tiles as a crew meets them: what a station said, how old it is, and the NOTAMs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class WeatherScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val now = 10_000_000_000L

    private fun weather(
        hasReport: Boolean = true, windSpeed: String = "12", windGust: String? = "G20", windFrom: Int? = 270, windVariable: Boolean = false, temp: String = "18",
        altimeter: String = "30.00", station: String? = "KRYY · Cobb County Airport · 0.7 mi", category: String? = "VFR", fetchedAgo: Long? = 12 * minute,
        fetching: Boolean = false, failure: String? = null, notams: Notams? = Notams.Clear,
    ) = WeatherUi(hasReport, windSpeed, windGust, windFrom, windVariable, temp, altimeter, station, category, fetchedAgo?.let { now - it }, fetching, failure, notams)

    private fun show(weather: WeatherUi): MutableList<String> {
        val log = mutableListOf<String>()
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { WeatherSection(weather, now) { log += "refresh" } } } }
        return log
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    // -- The tiles ---------------------------------------------------------------------------------------------------

    @Test
    fun `the tiles say what the station reported, with its name, distance and flight category`() {
        show(weather())
        compose.onNodeWithText("12 G20").assertIsDisplayed()
        compose.onNodeWithText("18").assertIsDisplayed()
        compose.onNodeWithText("30.00").assertIsDisplayed()
        compose.onNodeWithText("KRYY · Cobb County Airport · 0.7 mi").assertIsDisplayed()
        compose.onNodeWithText("Flight category VFR").assertIsDisplayed()
        compose.onNodeWithText("Fetched 12 min ago.").assertIsDisplayed()
    }

    @Test
    fun `the wind is said in words for a screen reader, with its direction and gusts`() {
        show(weather())
        compose.onNodeWithContentDescription("Wind from 270 degrees at 12 knots, gusting 20").assertIsDisplayed()
    }

    @Test
    fun `a variable wind says so, and a wind that was not reported says that`() {
        show(weather(windVariable = true, windFrom = null, windGust = null, windSpeed = "3"))
        compose.onNodeWithText("VRB").assertIsDisplayed()
        compose.onNodeWithContentDescription("Wind variable at 3 knots").assertIsDisplayed()
    }

    @Test
    fun `a number the station did not report is two dashes, not a zero`() {
        show(weather(windSpeed = "--", windGust = null, windFrom = null, temp = "--", altimeter = "--"))
        assertEquals(3, count("--"))
        compose.onNodeWithContentDescription("Wind not reported").assertIsDisplayed()
    }

    @Test
    fun `with no station the tiles are dashes and it says no station reported, with when it looked`() {
        show(weather(hasReport = false, windSpeed = "--", windGust = null, windFrom = null, temp = "--", altimeter = "--", station = null, category = null, fetchedAgo = 3 * minute))
        compose.onNodeWithText("No weather station reported near this position. Checked 3 min ago.").assertIsDisplayed()
        assertEquals(0, count("Flight category", substring = true))
    }

    // -- How old -------------------------------------------------------------------------------------------------------

    @Test
    fun `a report is fresh for ninety minutes, and then it says in words that it is old`() {
        show(weather(fetchedAgo = 90 * minute))
        compose.onNodeWithText("Fetched 1 h ago.").assertIsDisplayed()
        assertEquals(0, count("This report is old", substring = true))
    }

    @Test
    fun `an old report says so in words, not only in colour`() {
        show(weather(fetchedAgo = 3 * 60 * minute))
        compose.onNodeWithText("Fetched 3 h ago. This report is old.").assertIsDisplayed()
    }

    @Test
    fun `before anything is fetched it says so, and while the first is coming it says that`() {
        show(weather(hasReport = false, station = null, category = null, fetchedAgo = null, notams = null))
        compose.onNodeWithText("The weather has not been fetched yet.").assertIsDisplayed()
    }

    @Test
    fun `while the first is coming it says it is fetching, and Refresh is not offered`() {
        show(weather(hasReport = false, station = null, category = null, fetchedAgo = null, fetching = true, notams = null))
        compose.onNodeWithText("Fetching the weather…").assertIsDisplayed()
        compose.onNodeWithText("Updating…").assertIsDisplayed()
        assertEquals(0, count("Refresh"))
    }

    @Test
    fun `an update that fails says why and keeps the old report on screen with its age`() {
        show(weather(fetchedAgo = 40 * minute, failure = "No connection, so the weather could not be updated."))
        compose.onNodeWithText("No connection, so the weather could not be updated.").assertIsDisplayed()
        compose.onNodeWithText("Fetched 40 min ago.").assertIsDisplayed()
        compose.onNodeWithText("12 G20").assertIsDisplayed()
    }

    @Test
    fun `a first fetch that fails says why and nothing else about the age`() {
        show(weather(hasReport = false, station = null, category = null, fetchedAgo = null, failure = "No connection, so the weather could not be updated.", notams = null))
        compose.onNodeWithText("No connection, so the weather could not be updated.").assertIsDisplayed()
        assertEquals(0, count("has not been fetched yet", substring = true))
    }

    @Test
    fun `Refresh asks for the weather again`() {
        val log = show(weather())
        compose.onNodeWithContentDescription("Update the weather").performScrollTo().performClick()
        assertEquals(listOf("refresh"), log)
    }

    // -- NOTAMs -------------------------------------------------------------------------------------------------------------

    private val groups = listOf(
        NotamGroup("Obstruction", listOf("!FDC 6/1234 CRANE 340FT AGL 3NM N", "!FDC 6/2222 TOWER LGT OTS")),
        NotamGroup("Airspace", listOf("!ZTL 10/044 TEMPORARY FLIGHT RESTRICTION", "!ZTL 10/055 PARACHUTE JUMPING", "!ZTL 10/066 UAS OPS")),
    )

    @Test
    fun `no NOTAMs says none are active, and a search that could not be done says not to take that as none`() {
        show(weather(notams = Notams.Clear))
        compose.onNodeWithText("NOTAMs · none active within 10 nm").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a NOTAM search that could not be done is a warning and not a clear`() {
        show(weather(notams = Notams.Unavailable))
        compose.onNodeWithText("NOTAMs could not be fetched. Do not take that to mean there are none: check them before flight.").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("none active", substring = true))
    }

    @Test
    fun `NOTAMs are counted, folded away until asked for, and can be hidden again`() {
        show(weather(notams = Notams.Listed(groups)))
        compose.onNodeWithText("NOTAMs · 5 active within 10 nm").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("!FDC", substring = true))
        compose.onNodeWithText("Read").performScrollTo().performClick()
        compose.onNodeWithText("!FDC 6/1234 CRANE 340FT AGL 3NM N").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Obstruction").assertIsDisplayed()
        compose.onNodeWithText("Hide").performScrollTo().performClick()
        assertEquals(0, count("!FDC", substring = true))
    }

    @Test
    fun `a longer list can be searched by a word of the text or by the kind`() {
        show(weather(notams = Notams.Listed(groups)))
        compose.onNodeWithText("Read").performScrollTo().performClick()
        compose.onNodeWithText("Search the NOTAMs").performScrollTo().performTextInput("crane")
        compose.onNodeWithText("!FDC 6/1234 CRANE 340FT AGL 3NM N").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("TOWER LGT", substring = true))
        assertEquals(0, count("Airspace"))
        compose.onNodeWithText("crane").performTextInput("xyz")
        compose.onNodeWithText("No NOTAM matches that.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a short list is not offered a search`() {
        show(weather(notams = Notams.Listed(listOf(NotamGroup("Obstruction", listOf("a", "b", "c"))))))
        compose.onNodeWithText("Read").performScrollTo().performClick()
        assertEquals(0, count("Search the NOTAMs"))
    }

    @Test
    fun `the filter keeps a whole kind when the kind matches, and only the matching texts otherwise`() {
        assertEquals(groups, NotamFilter.apply(groups, ""))
        assertEquals(groups, NotamFilter.apply(groups, "   "))
        assertEquals(listOf(groups[0]), NotamFilter.apply(groups, "obstruction"))
        assertEquals(listOf(NotamGroup("Airspace", listOf("!ZTL 10/055 PARACHUTE JUMPING"))), NotamFilter.apply(groups, "parachute"))
        assertEquals(groups, NotamFilter.apply(groups, "!"))                                        // every text has it
        assertTrue(NotamFilter.apply(groups, "zzz").isEmpty())
    }
}
