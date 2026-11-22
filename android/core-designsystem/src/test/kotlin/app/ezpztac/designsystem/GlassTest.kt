package app.ezpztac.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The glass over the map (Glass.kt): how much of the map the panel lets through at each height, the tiles' rows, a section card that folds, and, for someone to
 * look at (`-Pezpz.screenshots`), the panel and a bar over a stand-in map in each palette: a satellite's dark greens and a VFR chart's white, the hardest case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassTest {
    @get:Rule
    val compose = createComposeRule()

    // -- The arithmetic ----------------------------------------------------------------------------------------------------------

    @Test
    fun `the panel is as solid as ever at half and below, and lets the map through only as it rises to full height`() {
        assertEquals(0f, Glass.openness(edgePx = 1200f, halfPx = 900f, fullPx = 200f))      // lowered
        assertEquals(0f, Glass.openness(900f, 900f, 200f))                                  // half
        assertEquals(0.5f, Glass.openness(550f, 900f, 200f))
        assertEquals(1f, Glass.openness(200f, 900f, 200f))                                  // full
        assertEquals(1f, Glass.openness(150f, 900f, 200f))                                  // past it, while a finger overshoots
        assertEquals("not laid out yet", 0f, Glass.openness(Float.NaN, 900f, 200f))
        assertEquals("anchors with no room between them", 0f, Glass.openness(500f, 200f, 200f))

        val (restTop, restFoot) = Glass.sheetAlpha(0f)
        assertEquals(Glass.SHEET_RESTING, restTop)
        assertEquals(Glass.SHEET_RESTING, restFoot)
        val (top, foot) = Glass.sheetAlpha(1f)
        assertEquals(Glass.SHEET_OPEN_TOP, top)
        assertEquals(Glass.SHEET_OPEN_BOTTOM, foot)
        assertTrue("the foot, over the bar of panels, stays the more solid", foot > top)
        val (midTop, _) = Glass.sheetAlpha(0.5f)
        assertTrue(midTop < restTop && midTop > top)
        // Never so clear that words on the panel itself lose the map under them.
        assertTrue(Glass.SHEET_OPEN_TOP >= 0.8f)
    }

    @Test
    fun `three figures to a row on a phone, fewer as they would no longer fit, and never more than there are`() {
        assertEquals(3, tileColumns(304.dp, 96.dp, 8.dp, 3))
        assertEquals(2, tileColumns(304.dp, 96.dp * 1.3f, 8.dp, 3))
        assertEquals(1, tileColumns(304.dp, 96.dp * 2f, 8.dp, 3))
        assertEquals(1, tileColumns(80.dp, 96.dp, 8.dp, 3))                                  // too narrow even for one: one, cut to the width
        assertEquals(2, tileColumns(900.dp, 96.dp, 8.dp, 2))
        assertEquals(1, tileColumns(304.dp, 96.dp, 8.dp, 0))
    }

    // -- A section card ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a card folds and unfolds from its heading, saying what it holds while folded, and its action is a button of its own`() {
        var refreshed = 0
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                SectionCard("Weather", icon = EzpzIcons.air, summary = "Wind 270° 12 kt", foldKey = "test:weather", action = { TextAction("Refresh", onClick = { refreshed++ }) }) {
                    Text("Fetched just now.")
                }
            }
        }
        compose.onNodeWithText("Fetched just now.").assertExists()
        compose.onNodeWithText("Wind 270° 12 kt").assertDoesNotExist()
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshed)
        compose.onNodeWithText("Fetched just now.").assertExists()                               // the action did not fold it

        compose.onNodeWithText("Weather").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).performClick()
        compose.onNodeWithText("Fetched just now.").assertDoesNotExist()
        compose.onNodeWithText("Wind 270° 12 kt", substring = true).assertExists()
        compose.onNodeWithText("Weather").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
        compose.onNodeWithText("Fetched just now.").assertExists()
    }

    @Test
    fun `a card with no fold key does not fold`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { SectionCard("Mission aircraft", icon = EzpzIcons.flight) { Text("UH-60L") } } }
        compose.onNodeWithText("Mission aircraft").performClick()
        compose.onNodeWithText("UH-60L").assertExists()
        compose.onNodeWithText("Mission aircraft").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // -- What it looks like ----------------------------------------------------------------------------------------------------------

    /** A stand-in map: bands of a satellite's greens and browns, and a VFR chart's white and yellow, with lines across them. */
    @Composable
    private fun StandInMap() {
        Canvas(Modifier.fillMaxSize()) {
            val bands = listOf(Color(0xFF2E4A2B), Color(0xFF5B4A32), Color(0xFFF4F1E4), Color(0xFFE9D98A), Color(0xFF1E3A20), Color(0xFFFFFFFF))
            val band = size.height / bands.size
            bands.forEachIndexed { i, c -> drawRect(c, topLeft = Offset(0f, i * band), size = androidx.compose.ui.geometry.Size(size.width, band)) }
            var x = -size.height
            while (x < size.width) {
                drawLine(Color(0xFF2B5FA8), Offset(x, 0f), Offset(x + size.height, size.height), strokeWidth = 6f)
                x += 120f
            }
        }
    }

    private fun sheetShot(name: String, mode: ThemeMode, detent: SheetDetent) {
        compose.setContent {
            EzpzTheme(mode) {
                Box(Modifier.fillMaxSize()) {
                    StandInMap()
                    PanelSheet(rememberPanelSheetState(detent), topLimit = 64.dp, header = { PanelHeader("LZ/PZ 1", subtitle = "16S GC 28864 55349", subtitleMono = true) }) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                            Text("Saves to your Library")
                            SectionCard("LZ/PZ analysis", icon = EzpzIcons.terrain, summary = "Analyzed", foldKey = "shot:analysis") {
                                TileGrid(
                                    listOf(
                                        { m -> StatTile("Capacity", "13", m, unit = "UH-60L", emphasis = true) },
                                        { m -> StatTile("Area (ft²)", "854,831", m) },
                                        { m -> StatTile("Elevation (MSL)", "4050 ft", m) },
                                    ),
                                )
                            }
                            SectionCard("Weather", icon = EzpzIcons.air, foldKey = "shot:weather") { Text("Fetched just now.") }
                            repeat(6) { Text("Row $it", Modifier.height(40.dp)) }
                        }
                    }
                    GlassSurface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 12.dp, end = 12.dp).fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Helicopter · UH-60L")
                            Text("16S GC 28864 55349", style = EzpzText.grid)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/glass-$name.png")
    }

    @Test fun fullDark() = sheetShot("full-dark", ThemeMode.Dark, SheetDetent.Full)
    @Test fun halfDark() = sheetShot("half-dark", ThemeMode.Dark, SheetDetent.Half)
    @Test fun fullLight() = sheetShot("full-light", ThemeMode.Light, SheetDetent.Full)
    @Test fun fullNight() = sheetShot("full-night", ThemeMode.Night, SheetDetent.Full)
}
