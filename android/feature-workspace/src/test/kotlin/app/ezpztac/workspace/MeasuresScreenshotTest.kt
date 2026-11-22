package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.ezpztac.data.HeldMeasure
import app.ezpztac.data.MeasureKind
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.planning.SetLines
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The control measures' screens drawn, for someone to look at (`-Pezpz.screenshots` writes them to build/screenshots), and what their buttons do: the bar over the
 * map while one is drawn, the phase line's name, and the Routes panel's *Control measures* cards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-xxhdpi")
class MeasuresScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.surface) { Box(Modifier.padding(vertical = 12.dp)) { content() } }
            }
        }
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/screenshots/measures-$name.png")

    private val flot = MeasureLineUi(HeldMeasure.Line("m-1", SetLines.FLOTS, "flot-1"), "FLOT", "3.2 km", held = false)
    private val red = MeasureLineUi(HeldMeasure.Line("m-1", SetLines.PHASE_LINES, "pl-1"), "PL RED", "Phase line · 2.4 km", held = true, name = "RED")
    private val own = MeasuresCardUi("m-1", "CONTROL MEASURES", attached = false, summary = "1 FLOT · 1 phase line", lines = listOf(flot, red))
    private val older = MeasuresCardUi(
        "r-1", "ROUTES TO HAWK", attached = true, summary = "1 phase line",
        lines = listOf(MeasureLineUi(HeldMeasure.Line("r-1", SetLines.PHASE_LINES, "pl-2"), "PL BLUE", "Phase line · 1.1 km", held = false, name = "BLUE")),
    )

    @Test
    fun `the bar while a FLOT is drawn, and a backstop's at large text`() {
        show { MeasuresToolbar(MeasureDraftUi(MeasureKind.FLOT, 1, canFinish = false), null, MeasuresActions()) }
        compose.onNodeWithText("Add two points").assertIsDisplayed()
        shot("toolbar-flot")
    }

    @Test
    fun `the bar with a refusal, in the night palette`() {
        show(ThemeMode.Night) { MeasuresToolbar(MeasureDraftUi(MeasureKind.PHASE_LINE, 3, canFinish = true), "The pack would not take the control measures.", MeasuresActions()) }
        compose.onNodeWithText("3 points so far.", substring = true).assertIsDisplayed()
        shot("toolbar-refused-night")
    }

    @Test
    fun `the phase line's name`() {
        var named: String? = null
        show { PhaseLineNameContent("ALPHA", onConfirm = { named = it }, onCancel = {}) }
        compose.onNodeWithText("Finish phase line").performClick()
        assertEquals("ALPHA", named)
        shot("name")
    }

    @Test
    fun `the cards of control measures and of lines saved inside a route set`() {
        val held = mutableListOf<HeldMeasure.Line>()
        val kept = mutableListOf<String>()
        show { Column(Modifier.padding(horizontal = 16.dp)) { MeasuresSection(listOf(own, older), MeasuresCardActions(hold = { held += it }, keepOnTheirOwn = { kept += it })) } }
        compose.onNodeWithText("Control measures · 3").assertIsDisplayed()
        compose.onNodeWithText("Saved with the route set ROUTES TO HAWK", substring = true).assertIsDisplayed()
        shot("cards")
        compose.onNodeWithText("FLOT").performClick()
        assertEquals(listOf(flot.line), held)
        compose.onNodeWithText("Keep on their own").performClick()
        assertEquals(listOf("r-1"), kept)
    }

    @Test
    fun `control measures of the person's own go into a Mission Pack from their card, and lines saved in a route set or a pack's are not offered it`() {
        val added = mutableListOf<Pair<String, String>>()
        show {
            Column(Modifier.padding(horizontal = 16.dp)) {
                MeasuresSection(listOf(own, older), MeasuresCardActions(addToPack = { uuid, name -> added += uuid to name }))
            }
        }
        compose.onNodeWithText("Add to Mission Pack…").assertIsDisplayed().performClick()
        assertEquals(listOf("m-1" to "CONTROL MEASURES"), added)
        assertEquals("only the set of their own offers it", 1, compose.onAllNodes(androidx.compose.ui.test.hasText("Add to Mission Pack…")).fetchSemanticsNodes().size)
        shot("cards-add-to-pack")
    }

    @Test
    fun `no pack to add to offers nothing`() {
        show { Column(Modifier.padding(horizontal = 16.dp)) { MeasuresSection(listOf(own), MeasuresCardActions()) } }
        compose.onNodeWithText("Add to Mission Pack…").assertDoesNotExist()
    }

    @Test
    fun `a pack's own control measures are not offered to a pack`() {
        show { Column(Modifier.padding(horizontal = 16.dp)) { MeasuresSection(listOf(own.copy(inPack = true)), MeasuresCardActions(addToPack = { _, _ -> })) } }
        compose.onNodeWithText("Add to Mission Pack…").assertDoesNotExist()
    }

    @Test
    fun `a read-only pack's card offers no change, in the light palette`() {
        show(ThemeMode.Light) {
            Column(Modifier.padding(horizontal = 16.dp)) { MeasuresSection(listOf(own.copy(inPack = true, locked = "OP DK is finished.")), MeasuresCardActions()) }
        }
        compose.onNodeWithContentDescription("Close the control measures CONTROL MEASURES here: they stay in the pack").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescriptionCount("Remove this FLOT"))
        shot("cards-locked-light")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes().size
}
