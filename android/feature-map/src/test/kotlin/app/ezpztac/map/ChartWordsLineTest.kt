package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the map says of its charts, where it says it. The pictures are over a stand-in for the chart (`-Pezpz.screenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ChartWordsLineTest {
    @get:Rule
    val compose = createComposeRule()

    private val expires = "2026-12-24T09:01Z"
    private val expiresAt = requireNotNull(chartInstant(expires))
    private val edition = ChartEdition("2026-10-29", "2026-12-23", expires, expiresAt)
    private val atlanta = CoverageChart(
        "Atlanta Sectional", "sectional", 500_000.0, edition, shownFrom = 5, nativeLevel = 10.58,
        ring = listOf(ChartPoint(-90.0, 30.0), ChartPoint(-80.0, 30.0), ChartPoint(-80.0, 38.0), ChartPoint(-90.0, 38.0)),
    )
    private val live = VfrLive(
        build = "b", tiles = "https://api.test/charts/vfr/b/{z}/{x}/{y}.webp", coverage = "/charts/vfr/b/coverage.json", tileSize = 512, minZoom = 5, maxZoom = 14,
        effective = "2026-10-29T09:01Z", expires = expires, bounds = ChartBox(-125.0, 24.0, -66.0, 50.0), fallback = emptyList(),
    )
    private val charts = VfrCharts(live, ChartCoverage("b", 5, listOf(atlanta)))
    private val camera = CameraState(LatLon(34.78, -84.08), 13.0)

    @Test
    fun `its line turns to expired at the instant the chart runs out, with nothing else having moved`() {
        var now by mutableStateOf(expiresAt - 1000)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                rememberChartLines(charts, camera, IntSize(1000, 2000), 1.0, now = { now })?.let { ChartWordsLine(it) }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("FAA Atlanta Sectional, 29 Oct – 23 Dec 2026").assertIsDisplayed()
        now = expiresAt
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithText("FAA Atlanta Sectional · Expired 23 Dec 2026 — not current").assertIsDisplayed()
    }

    private fun screen(styleId: String, built: VfrCharts = charts, mode: ThemeMode = ThemeMode.Dark, name: String? = null, at: CameraState = camera) {
        val styles = MapStyles.available("pk.t", built.live)
        compose.setContent {
            EzpzTheme(mode) {
                MapScreen(
                    state = MapUiState(styles, styles.first { it.id == styleId }, Readout.of(at.center), charts = built),
                    camera = at, gps = GpsState.Off,
                    onSearch = { true }, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                    map = { Box(Modifier.fillMaxSize().background(Color(0xFFD9C9A3))) },
                )
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        if (name != null) compose.onRoot().captureRoboImage("build/screenshots/charts-$name.png")
    }

    @Test
    fun `the VFR map names its charts`() {
        screen(MapStyles.VFR_ID)
        compose.onNodeWithText("FAA Atlanta Sectional, 29 Oct – 23 Dec 2026", substring = false).assertIsDisplayed()
    }

    @Test
    fun `the other base maps say nothing of charts`() {
        screen("satellite")
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("FAA", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `with no build the VFR map says ArcGIS`() {
        screen(MapStyles.VFR_ID, VfrCharts())
        compose.onNodeWithText(ChartWords.ARCGIS).assertIsDisplayed()
    }

    @Test
    fun `past a chart's own detail it says how far enlarged`() {
        screen(MapStyles.VFR_ID, at = camera.copy(zoom = 15.0))
        compose.onNodeWithText("Sectional enlarged 16× — no finer chart here").assertIsDisplayed()
    }

    @Test
    fun `the words take no touch from the map under them`() {
        var tapped = 0
        val styles = MapStyles.available("pk.t", live)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                MapScreen(
                    state = MapUiState(styles, styles.first { it.id == MapStyles.VFR_ID }, Readout.of(camera.center), charts = charts),
                    camera = camera, gps = GpsState.Off,
                    onSearch = { true }, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                    map = { Box(Modifier.fillMaxSize().background(Color.Gray).clickable { tapped++ }) },
                )
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("FAA Atlanta Sectional, 29 Oct – 23 Dec 2026").performClick()
        assertEquals(1, tapped)
    }

    @Test fun picture() = screen(MapStyles.VFR_ID, name = "dark", at = camera.copy(zoom = 15.0))
    @Test fun pictureNight() = screen(MapStyles.VFR_ID, mode = ThemeMode.Night, name = "night", at = camera.copy(zoom = 15.0))
    @Test fun pictureLight() = screen(MapStyles.VFR_ID, mode = ThemeMode.Light, name = "light", at = camera.copy(zoom = 15.0))
}
