package app.ezpztac.briefs

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.ezpztac.brief.SandtableSteps
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.map.CameraState
import app.ezpztac.map.MapProjection
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live sandtable's screen (docs/MISSION_BRIEF.md §2.8, B9), laid out from OP DK's brief over a stand-in for the map that draws the step's shapes as
 * [SandtableOverlay] hands them to MapLibre (which needs a GPU), the marks and words over them as the app draws them, flown as [SandtableFlights] would fly the map.
 * `-Pezpz.screenshots` draws the pictures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-mdpi")
class SandtableScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private val actions = SandtableActions(
        end = { log += "end" }, goTo = { log += "go $it" }, next = { log += "next" }, previous = { log += "previous" }, first = { log += "first" },
        last = { log += "last" }, showSteps = { log += "steps $it" }, showConstants = { log += "constants $it" },
    )

    private val ids = (BriefFixtures.model["steps"] as JsonArray).map { ((it as JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content }

    private fun shown(id: String = "1", stepsOpen: Boolean = true, constantsOpen: Boolean = true, pack: String? = "OP DK", model: JsonObject = BriefFixtures.model) =
        SandtableShown.of(BriefFixtures.state(pack, model = model), false, SandtableUi(stepId = id, anchor = ids.indexOf(id).coerceAtLeast(0), stepsOpen, constantsOpen))

    private var current by mutableStateOf<SandtableShown?>(null)
    private var theme by mutableStateOf(ThemeMode.Dark)

    /** Shows [shown] (set once; what follows changes [current] and [theme]). */
    private fun set(shown: SandtableShown) {
        current = shown
        compose.setContent {
            EzpzTheme(theme) {
                val boxes = remember { SandtableBoxes() }
                val now = current ?: return@EzpzTheme
                // As the tablet's frame draws it: the map where it is, the sandtable over it.
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    StandInMap(now, boxes)
                    SandtableFrame(now, actions, boxes)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun show(shown: SandtableShown, mode: ThemeMode = theme) {
        current = shown
        theme = mode
        compose.waitForIdle()
    }

    private fun picture(name: String) = compose.onRoot().captureRoboImage("build/screenshots/$name.png")

    // -- What it says -------------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the bar says what is presented, where, and how to end it`() {
        set(shown("4.e@lz:lz-hawk"))
        compose.onNodeWithText("Presenting").assertIsDisplayed()
        compose.onNodeWithText("OP DK · Air movement brief").assertIsDisplayed()
        compose.onNodeWithText(SandtableWords.SUB).assertIsDisplayed()
        compose.onNodeWithText("UNCLASSIFIED").assertIsDisplayed()
        compose.onNodeWithText("Step 8 of 18").assertIsDisplayed()
        compose.onNodeWithText("End brief").performClick()
        assertEquals(listOf("end"), log)
        picture("sandtable-lz")
    }

    @Test
    fun `the card says the step, who briefs it, when, its script and its facts`() {
        set(shown("4.e@lz:lz-hawk"))
        compose.onNodeWithText("4.E LANDING SEQUENCE").assertIsDisplayed()
        compose.onNode(hasText("Flight lead") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).assertIsDisplayed()
        val hawk = shown("4.e@lz:lz-hawk")
        // Its planned time, beside the tags (the list says it too, and the facts).
        compose.onAllNodes(hasText(hawk.current!!.time!!) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).onFirst().assertIsDisplayed()
        // Its title says where the brief is to a screen reader as it changes.
        compose.onNodeWithContentDescription("Step 8 of 18: Landing sequence · LZ HAWK").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite) and hasText("LZ HAWK")).assertExists()
        val sentence = BriefReading.sentence(hawk.step!!.script.first())
        compose.onNodeWithText(sentence, substring = true).assertExists()
        compose.onNodeWithText("203°").assertExists()
        // The constants, each value or a write-in line.
        compose.onNodeWithText("Mission constants").assertIsDisplayed()
        compose.onNodeWithText("Enroute airspeed").assertIsDisplayed()
        compose.onNodeWithText("120 KIAS").assertIsDisplayed()
    }

    @Test
    fun `the steps list every step, the one shown marked, and a step chosen is shown`() {
        set(shown("4.e@lz:lz-hawk"))
        compose.onNodeWithText("Steps · 18").assertIsDisplayed()
        compose.onNode(hasText("Landing sequence · LZ HAWK") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        compose.onNodeWithText("Roll call").performClick()
        compose.onNodeWithContentDescription(SandtableWords.HIDE_STEPS).performClick()
        assertEquals(listOf("go 0", "steps false"), log)
    }

    @Test
    fun `only the step shown is selected, and says it is the current step`() {
        set(shown("4.e@lz:lz-hawk"))
        val selected = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
        assertEquals(1, selected.size)
        assertEquals(SandtableWords.CURRENT, selected.single().config[SemanticsProperties.StateDescription])
        // The window is no stop of its own for a screen reader: nothing focusable is the whole frame.
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).fetchSemanticsNodes().none { it.config.getOrNull(SemanticsProperties.PaneTitle) == SandtableWords.PANE })
    }

    @Test
    fun `the arrows step, neither past an end, and the constants fold`() {
        set(shown("1"))
        compose.onNodeWithContentDescription(SandtableWords.PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithContentDescription(SandtableWords.NEXT).assertIsEnabled().performClick()
        compose.onNodeWithText("Mission constants").performClick()
        assertEquals(listOf("next", "constants false"), log)
        show(shown(ids.last()))
        compose.onNodeWithContentDescription(SandtableWords.NEXT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(SandtableWords.PREVIOUS).assertIsEnabled().performClick()
        assertEquals("previous", log.last())
        picture("sandtable-last")
    }

    @Test
    fun `a presenter's clicker, the arrow keys, Home, End and the space bar step, and Escape and Back end it`() {
        set(shown("4.c@route:set-opdk/r-alderaan"))
        fun press(code: Int) = compose.onRoot().performKeyPress(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, code)))
        press(NativeKeyEvent.KEYCODE_PAGE_DOWN)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_PAGE_UP)
        press(NativeKeyEvent.KEYCODE_DPAD_UP)
        press(NativeKeyEvent.KEYCODE_MOVE_HOME)
        press(NativeKeyEvent.KEYCODE_MOVE_END)
        press(NativeKeyEvent.KEYCODE_SPACE)
        press(NativeKeyEvent.KEYCODE_ENTER)
        press(NativeKeyEvent.KEYCODE_ESCAPE)
        assertEquals(listOf("next", "next", "previous", "previous", "first", "last", "next", "next", "end"), log)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("end", log.last())
        assertEquals(2, log.count { it == "end" })
        picture("sandtable-route")
    }

    @Test
    fun `a key with Ctrl, Alt or Meta, or a key that is not a step's, does nothing`() {
        fun event(code: Int, meta: Int = 0, action: Int = NativeKeyEvent.ACTION_DOWN) = KeyEvent(NativeKeyEvent(0, 0, action, code, 0, meta))
        assertFalse(stepKey(event(NativeKeyEvent.KEYCODE_DPAD_RIGHT, NativeKeyEvent.META_CTRL_ON or NativeKeyEvent.META_CTRL_LEFT_ON), actions))
        assertFalse(stepKey(event(NativeKeyEvent.KEYCODE_DPAD_RIGHT, NativeKeyEvent.META_ALT_ON), actions))
        assertFalse(stepKey(event(NativeKeyEvent.KEYCODE_DPAD_RIGHT, NativeKeyEvent.META_META_ON), actions))
        assertFalse(stepKey(event(NativeKeyEvent.KEYCODE_A), actions))
        assertFalse(stepKey(event(NativeKeyEvent.KEYCODE_DPAD_RIGHT, action = NativeKeyEvent.ACTION_UP), actions))
        assertFalse(spaceKey(event(NativeKeyEvent.KEYCODE_SPACE, action = NativeKeyEvent.ACTION_UP), actions))
        assertTrue(log.isEmpty())
        assertTrue(spaceKey(event(NativeKeyEvent.KEYCODE_SPACE), actions))
        assertEquals(listOf("next"), log)
    }

    // -- How it stands ------------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is shown or flown to while the brief is read, and a brief that could not be put together says so`() {
        val reading = SandtableShown.of(null, false, SandtableUi())
        set(reading)
        assertEquals(2, compose.onAllNodes(hasText(SandtableWords.OPENING)).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(SandtableWords.NEXT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(SandtableWords.PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithText("This session").assertIsDisplayed()
        picture("sandtable-opening")
        show(SandtableShown.of(null, true, SandtableUi()))
        compose.onNodeWithText(SandtableWords.FAILED).assertIsDisplayed()
    }

    @Test
    fun `a brief with no steps says so`() {
        val none = JsonObject(BriefFixtures.model + ("steps" to JsonArray(emptyList())))
        set(SandtableShown.of(BriefFixtures.state(model = none), false, SandtableUi()))
        compose.onNodeWithText(SandtableWords.NO_STEPS).assertIsDisplayed()
        compose.onNodeWithText("Steps · 0").assertIsDisplayed()
    }

    @Test
    fun `folded, the bar offers the steps again and the constants show only their heading`() {
        set(shown("4.c@route:set-opdk/r-alderaan", stepsOpen = false, constantsOpen = false))
        compose.onNodeWithText("Steps · 18").assertDoesNotExist()
        compose.onNodeWithText("Enroute airspeed").assertDoesNotExist()
        compose.onNodeWithText("Mission constants").assertIsDisplayed()
        compose.onNodeWithText("Steps").performClick()
        assertEquals(listOf("steps true"), log)
        picture("sandtable-folded")
    }

    @Test
    @Config(qualifiers = "w845dp-h800dp-mdpi")
    fun `in a narrow window the steps lie over the map, a step chosen folds them, and the bar keeps to its essentials`() {
        set(shown("4.d@lz:pz-ibis", stepsOpen = false, constantsOpen = false))
        compose.onNodeWithText(SandtableWords.SUB).assertDoesNotExist()
        // *Steps* is its icon, and where the brief is goes to the card's way, under the map's figure.
        compose.onNodeWithContentDescription(SandtableWords.SHOW_STEPS).assertIsDisplayed()
        val position = compose.onNodeWithText("Step 10 of 18").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("the place is in the card: $position", position.top > 600f)
        picture("sandtable-narrow")
        show(shown("4.d@lz:pz-ibis", stepsOpen = true, constantsOpen = false))
        picture("sandtable-narrow-steps")
        compose.onNodeWithText("Roll call").performClick()
        assertEquals(listOf("go 0", "steps false"), log)
        // Back folds the steps lying over the map before it ends the brief.
        log.clear()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("steps false"), log)
        show(shown("4.d@lz:pz-ibis", stepsOpen = false, constantsOpen = false))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("steps false", "end"), log)
    }

    @Test
    fun `the steps fold when the window narrows past their wide layout`() {
        var width by mutableStateOf(1000.dp)
        current = shown("4.c@route:set-opdk/r-alderaan")
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                val boxes = remember { SandtableBoxes() }
                val now = current ?: return@EzpzTheme
                androidx.compose.foundation.layout.Box(Modifier.width(width)) {
                    StandInMap(now, boxes)
                    SandtableFrame(now, actions, boxes)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(log.isEmpty())
        width = 845.dp
        compose.waitForIdle()
        assertEquals(listOf("steps false"), log)
        width = 1000.dp
        compose.waitForIdle()
        assertEquals(listOf("steps false"), log)
    }

    @Test
    @Config(qualifiers = "w1280dp-h600dp-mdpi")
    fun `each step's card starts at its top, however far the last one was read`() {
        set(shown("5.a"))
        compose.onNodeWithText("Rally points are ACP2 (inbound), RP5 (outbound).", substring = true).performScrollTo()
        compose.onNodeWithContentDescription("Step 13 of 18: Contingencies").assertIsNotDisplayed()          // read down past its title
        // The next long step opens at its title, not as far down as the last was read.
        show(shown("4.c@route:set-opdk/r-alderaan"))
        compose.onNodeWithContentDescription("Step 6 of 18: Enroute sequence · ALDERAAN").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h600dp-mdpi", fontScale = 2.0f)
    fun `at large text in a short window the arrows keep their size and the card keeps room for the step`() {
        set(shown("4.e@lz:lz-hawk", stepsOpen = false))
        // The bar keeps to its essentials: *Steps* is its icon, the place goes to the card.
        compose.onNodeWithContentDescription(SandtableWords.SHOW_STEPS).assertIsDisplayed()
        compose.onNodeWithText(SandtableWords.SUB).assertDoesNotExist()
        compose.onNodeWithText("Step 8 of 18").assertIsDisplayed()
        listOf(SandtableWords.PREVIOUS, SandtableWords.NEXT).forEach { arrow ->
            val bounds = compose.onNodeWithContentDescription(arrow).fetchSemanticsNode().boundsInRoot
            assertEquals(56f, bounds.width, 0.5f)
            assertEquals(56f, bounds.height, 0.5f)
        }
        val card = compose.onNodeWithContentDescription("Step 8 of 18: Landing sequence · LZ HAWK").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("the title has room: $card", card.height > 20f)
        compose.onNodeWithText("End brief").assertIsDisplayed()
        picture("sandtable-large-text")
    }

    @Test
    @Config(qualifiers = "ldrtl-w1280dp-h800dp-mdpi")
    fun `in a right-to-left language it is laid out as the map is, the steps on the left and back before forward`() {
        set(shown("4.e@lz:lz-hawk"))
        val steps = compose.onNodeWithText("Steps · 18").fetchSemanticsNode().boundsInRoot
        assertTrue("the steps are on the left: $steps", steps.left < 100f)
        val previous = compose.onNodeWithContentDescription(SandtableWords.PREVIOUS).fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription(SandtableWords.NEXT).fetchSemanticsNode().boundsInRoot
        assertTrue(previous.left < next.left)
    }

    @Test
    fun `a long constant keeps to its side, its label beside it`() {
        val constants = (BriefFixtures.model["constants"] as JsonArray).mapIndexed { i, c ->
            if (i != 0) c else JsonObject((c as JsonObject) + ("value" to kotlinx.serialization.json.JsonPrimitive("Staggered left, 3 rotor discs, 45° back, closing to 2 discs over the release point")))
        }
        set(shown("1", model = JsonObject(BriefFixtures.model + ("constants" to JsonArray(constants)))))
        val label = compose.onNodeWithText("Enroute airspeed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the label keeps its room: $label", label.width > 60f)
    }

    @Test
    fun `the steps' list keeps the step shown in sight`() {
        set(shown("1"))
        show(shown(ids.last()))
        compose.onNode(hasText((BriefFixtures.model["steps"] as JsonArray).let { ((it.last() as JsonObject)["title"] as kotlinx.serialization.json.JsonPrimitive).content }) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assertIsDisplayed()
    }

    @Test
    fun `steps that share an id are each listed`() {
        assertEquals(
            listOf("step:a", "step:b", "step:a#2", "step:a#3"),
            stepKeys(listOf("a", "b", "a", "a").map { SandtableSteps.Step(it, "", "", "", "", "", null) }),
        )
    }

    // -- Pictures -----------------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `pictures of the overview, the rally step and night`() {
        set(shown("1"))
        picture("sandtable-overview")
        val rally = ids.indices.first { SandtableSteps.look(BriefFixtures.model, it)!!.rally }
        show(shown(ids[rally]))
        picture("sandtable-rally")
        show(shown("4.e@lz:lz-hawk"), ThemeMode.Night)
        picture("sandtable-night")
        show(shown("4.c@route:set-opdk/r-alderaan"), ThemeMode.Light)
        picture("sandtable-light")
    }


}

/**
 * A stand-in for the map: a plain ground, the step's shapes drawn as [SandtableOverlay] hands them to MapLibre (lines by their order, then the boundary, then the
 * dots, a dot's ring outside its fill), and the marks and words as [SandtableMarksLayer] draws them, the camera where [SandtableFlights] would fly it.
 */
@Composable
internal fun StandInMap(shown: SandtableShown, boxes: SandtableBoxes) {
    val density = LocalDensity.current.density
    val bounds = shown.view?.bounds
    val camera by remember(bounds) {
        derivedStateOf {
            val map = boxes.map?.takeIf { it.width > 0f && it.height > 0f } ?: return@derivedStateOf null
            bounds?.let { SandtableFit.of(it, map, boxes.covering(), density) }?.let { CameraState(it.center, it.zoom) }
        }
    }
    val measurer = rememberTextMeasurer()
    val scene = shown.scene
    Canvas(Modifier.fillMaxSize().background(Color(0xFF3B4636))) {
        val cam = camera ?: return@Canvas
        val view = MapProjection(cam, size.width.toDouble(), size.height.toDouble(), density.toDouble())
        fun at(p: LatLon) = view.toScreen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        fun path(points: List<LatLon>, close: Boolean) = Path().apply {
            points.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            if (close) close()
        }
        scene.lines.sortedBy { it.order }.forEach { l ->
            drawPath(path(l.points, false), hex(l.colour).copy(alpha = l.opacity.toFloat()), style = Stroke(l.width.toFloat() * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        scene.areas.forEach { a ->
            drawPath(path(a.ring, true), hex(a.colour).copy(alpha = a.fillOpacity.toFloat()))
            drawPath(path(a.ring, true), hex(a.colour), style = Stroke(a.stroke.toFloat() * density, join = StrokeJoin.Round))
        }
        scene.dots.sortedBy { it.order }.forEach { d ->
            drawCircle(hex(d.fill).copy(alpha = d.fillOpacity.toFloat()), radius = ((d.radius - d.stroke / 2) * density).toFloat(), center = at(d.at))
            drawCircle(hex(d.colour).copy(alpha = d.opacity.toFloat()), radius = (d.radius * density).toFloat(), center = at(d.at), style = Stroke(d.stroke.toFloat() * density))
        }
        drawFigure(scene, cam, measurer)
    }
}

private fun hex(colour: String): Color = Color(0xFF000000 or colour.removePrefix("#").toLong(16))

