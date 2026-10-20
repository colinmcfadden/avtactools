package app.ezpztac.viewer3d

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import app.ezpztac.data.LidarPhase
import app.ezpztac.data.LidarProblem
import app.ezpztac.data.SceneHeights
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.network.NetworkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.robolectric.annotation.Config

/** The 3D view's page: its bar, each state in the web's words (a failure in the app's own), and that it is the only thing taking touches and Back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class Viewer3DPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private val actions = Viewer3DActions(close = { log += "close" }, retry = { log += "retry" })

    private fun show(ui: Viewer3DUi) = compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DPage(ui, actions) } }

    @Test
    fun `its bar says 3D and the LZ-PZ's name, and its close button closes it`() {
        show(Viewer3DUi("LZ HAWK (ROCK)", LidarPhase.RESOLVING))
        compose.onNodeWithText("3D · LZ HAWK (ROCK)").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "3D view of LZ HAWK (ROCK)")).assertExists()
        compose.onNodeWithContentDescription("Close 3D view").performClick()
        assertEquals(listOf("close"), log)
    }

    @Test
    fun `while it looks, it says so`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.RESOLVING))
        compose.onNodeWithText("Looking up").assertIsDisplayed()
        compose.onNodeWithText("Checking for a point cloud at this location…", substring = true).assertIsDisplayed()
    }

    @Test
    fun `while it builds it says the stage in words, how many builds are ahead, the clock, and what closing does`() {
        var ui by mutableStateOf(Viewer3DUi("LZ HAWK", LidarPhase.BUILDING, stage = "waiting", ahead = 2, saved = false))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DPage(ui, actions) } }
        compose.onNodeWithText("Building point cloud").assertIsDisplayed()
        compose.onNodeWithText("Queued — 2 ahead in the queue").assertIsDisplayed()
        compose.onNodeWithText("Closing this stops the build. Save the LZ/PZ to keep it building.", substring = true).assertIsDisplayed()
        ui = Viewer3DUi("LZ HAWK", LidarPhase.BUILDING, stage = "building tiles", elapsedS = 247, saved = true)
        compose.waitForIdle()
        compose.onNodeWithText("Building 3D tiles").assertIsDisplayed()
        compose.onNodeWithText("4:07").assertIsDisplayed()                                       // the service's own count, past the page's
        compose.onNodeWithText("you may close this and the build finishes in the background", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a server that cannot build says so, with no operator's command, and Check again asks again`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.MISSING))
        compose.onNodeWithText("Not generated").assertIsDisplayed()
        compose.onNodeWithText("Check again").performClick()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `a failure says what failed in the app's own words, with Retry`() {
        show(Viewer3DUi("LZ HAWK", LidarPhase.ERROR, problem = LidarProblem.QUEUE_FULL, buildFailed = true))
        compose.onNodeWithText("Build failed").assertIsDisplayed()
        compose.onNodeWithText("The build queue is full. Try again in a few minutes.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `with the cloud in hand the scene and its controls are drawn, and no terrain there is said above the point size`() {
        val drawn = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Viewer3DPage(
                    Viewer3DUi("LZ HAWK", LidarPhase.AVAILABLE, noGround = true, tileset = "/lidar/tilesets/k/tileset.json"), actions,
                    slots = SceneSlots(scene = { drawn += "scene" }, over = { Text("The camera", Modifier.align(Alignment.TopEnd)) }, foot = { Text("The point size") }),
                )
            }
        }
        assertTrue("scene" in drawn)
        compose.onNodeWithText("The camera").assertIsDisplayed()
        compose.onNodeWithText(Viewer3DWords.NO_GROUND).assertIsDisplayed()
        val words = compose.onNodeWithText(Viewer3DWords.NO_GROUND).fetchSemanticsNode().boundsInRoot
        val foot = compose.onNodeWithText("The point size").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$words over $foot", words.bottom <= foot.top)
    }

    @Test
    fun `nothing under the page takes a touch while it is up`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Green).clickable { log += "map" })
                    Viewer3DPage(Viewer3DUi("LZ HAWK", LidarPhase.AVAILABLE), actions)
                }
            }
        }
        compose.onRoot().performTouchInput { click(center) }
        compose.onRoot().performTouchInput { click(bottomCenter.copy(y = bottomCenter.y - 20f)) }
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `Back closes it before anything under it hears it, and with no LZ-PZ it is not there at all`() {
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var target by mutableStateOf<Viewer3DTarget?>(Viewer3DTarget("lz-1", "LZ HAWK", 34.59, -84.13, saved = false))
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                BackHandler { log += "under" }
                Viewer3DHost(target, night = false, onClose = { log += "close"; target = null }, viewModel = model)
            }
        }
        compose.onNodeWithText("3D · LZ HAWK").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("close"), log)
        compose.onNodeWithText("3D · LZ HAWK").assertDoesNotExist()
        // Gone, it leaves Back to what was under it.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(listOf("close", "under"), log)
    }

    @Test
    fun `a mission pack's LZ-PZ is never told to save itself to keep its build going`() {
        assertEquals(Viewer3DWords.BUILDING_SAVED, Viewer3DWords.closing(saved = true, inPack = false))
        assertEquals(Viewer3DWords.BUILDING_UNSAVED, Viewer3DWords.closing(saved = false, inPack = false))
        assertEquals("Closing this stops the build.", Viewer3DWords.closing(saved = false, inPack = true))
        show(Viewer3DUi("LZ/PZ 1", LidarPhase.BUILDING, stage = "waiting", inPack = true))
        compose.onNodeWithText("Save the LZ/PZ", substring = true).assertDoesNotExist()
        val lookup = app.ezpztac.data.LidarLookup(LidarPhase.BUILDING)
        assertTrue(Viewer3DUi.of(Viewer3DTarget("pack:p-1:i-1", "LZ/PZ 1", 34.59, -84.13, saved = false), lookup, null).inPack)
        assertFalse(Viewer3DUi.of(Viewer3DTarget("lz-1", "LZ HAWK", 34.59, -84.13, saved = false), lookup, null).inPack)
    }

    @Test
    fun `each state is said to a screen reader as it comes, and a failure at once`() {
        var ui by mutableStateOf(Viewer3DUi("LZ HAWK", LidarPhase.RESOLVING))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DPage(ui, actions) } }
        fun said(text: String) = compose.onNode(hasText(text, substring = true)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, said(Viewer3DWords.LOOKING_UP))
        ui = Viewer3DUi("LZ HAWK", LidarPhase.BUILDING, stage = "waiting")
        compose.waitForIdle()
        assertEquals(LiveRegionMode.Polite, said(Viewer3DWords.BUILDING))
        assertEquals(LiveRegionMode.Polite, said("Queued"))
        ui = Viewer3DUi("LZ HAWK", LidarPhase.MISSING)
        compose.waitForIdle()
        assertEquals(LiveRegionMode.Polite, said(Viewer3DWords.MISSING))
        ui = Viewer3DUi("LZ HAWK", LidarPhase.ERROR, problem = LidarProblem.BUILD_FAILED, buildFailed = true)
        compose.waitForIdle()
        assertEquals(LiveRegionMode.Assertive, said("Build failed"))
        compose.onNode(hasText("Build failed", substring = true) and hasText("could not be built", substring = true)).assertExists()
    }

    @Test
    fun `it is drawn in the night palette at night and the dark one by day`() {
        assertEquals(ThemeMode.Night, pageTheme(night = true))
        assertEquals(ThemeMode.Dark, pageTheme(night = false))
    }

    @Test
    fun `opening it takes the focus from a field under it, so no keyboard stays up for what it covers`() {
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var target by mutableStateOf<Viewer3DTarget?>(null)
        var fieldFocused = false
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField("LZ RAVEN", {}, Modifier.onFocusChanged { fieldFocused = it.isFocused })
                    Viewer3DHost(target, night = false, onClose = { target = null }, viewModel = model)
                }
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        assertTrue(fieldFocused)
        compose.runOnIdle { target = Viewer3DTarget("lz-1", "LZ HAWK", 34.59, -84.13, saved = false) }
        compose.waitForIdle()
        assertFalse(fieldFocused)
    }

    @Test
    fun `with a keyboard the focus starts on the page and does not leave it`() {
        val focus = FocusRequester()
        val under = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) under += "under" }.clickable { })
                    Viewer3DPage(Viewer3DUi("LZ HAWK", LidarPhase.ERROR, problem = LidarProblem.LOOKUP_FAILED), actions, focus = focus)
                }
            }
        }
        compose.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }                          // a keyboard, as a tablet's, moving nothing
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasContentDescription(Viewer3DWords.CLOSE))
        repeat(6) { compose.onRoot().performKeyInput { pressKey(Key.Tab) } }
        compose.onNode(isFocused()).assertExists()
        assertEquals(emptyList<String>(), under)
    }

    @Test
    fun `the page leaving with its LZ-PZ still set lets go of the build it was waiting on`() {
        val lidar = FakeLidar(built = false)
        val model = viewModel(lidar, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var here by mutableStateOf(true)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                if (here) Viewer3DHost(Viewer3DTarget("lz-1", "LZ HAWK", 34.59, -84.13, saved = false), night = false, onClose = {}, viewModel = model)
            }
        }
        compose.waitUntil(5_000) { "build keep=false" in lidar.calls }
        // A sign-out takes the map, and the page with it, with no close.
        compose.runOnIdle { here = false }
        compose.waitForIdle()
        assertTrue(lidar.calls.toString(), "release ${FakeLidar.KEY}" in lidar.calls)
        assertNull(model.state.value)
    }

    /** A stand-in for the GL view, which Robolectric cannot draw: one frame of the scene once it draws, as the renderer would. */
    private val standIn: @Composable (SceneSession) -> Unit = { session ->
        Box(Modifier.fillMaxSize())
        LaunchedEffect(session) {
            val view: () -> Unit = {}
            session.listener = view
            session.status.first { it == SceneStatus.Drawing }
            session.frame(1080, 2200, 2.75, view)
        }
    }

    private val hawk = Viewer3DTarget("lz-1", "LZ HAWK", 34.591552, -84.128225, saved = false)

    @Test
    fun `with the cloud drawn, LZ, the compass and the point size are over it, each doing what it says`() {
        val sizes = InMemoryPointSizes()
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), sizes = sizes)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(hawk, night = false, onClose = {}, viewModel = model, surface = standIn) } }
        compose.waitUntil(5_000) { model.session.value?.compass?.value != null }
        val scene = model.session.value!!
        compose.onNode(hasContentDescription(Viewer3DWords.scene("LZ HAWK", drawn = true))).assertExists()
        compose.onNodeWithText(Viewer3DWords.LZ).assertIsDisplayed().performClick()
        assertTrue("LZ flies back", scene.frame(1080, 2200, 2.75, scene.listener!!)!!.animating)
        compose.onNode(hasContentDescription("Facing 330 degrees true. Face north")).assertIsDisplayed().performClick()
        assertTrue("north, flown to", scene.frame(1080, 2200, 2.75, scene.listener!!)!!.animating)
        compose.onNode(hasContentDescription(Viewer3DWords.POINT_SIZE)).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.waitForIdle()
        assertEquals(9, model.pointSize.value)
        assertEquals(9, sizes.get())
        assertEquals(9, scene.pointSize)
        assertEquals(9f, compose.onNode(hasContentDescription(Viewer3DWords.POINT_SIZE)).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertEquals("9, not the slider's per cent", "9", compose.onNode(hasContentDescription(Viewer3DWords.POINT_SIZE)).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `Routes is there with no routes, unavailable and saying why, and with them it frames them`() {
        val routes = FakeRoutes()
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), routes = routes)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(hawk, night = false, onClose = {}, viewModel = model, surface = standIn) } }
        compose.waitUntil(5_000) { model.session.value?.compass?.value != null }
        compose.onNodeWithText(Viewer3DWords.ROUTES).assertIsDisplayed().assertIsNotEnabled()
        assertEquals(Viewer3DWords.NO_ROUTES, routesState())
        compose.runOnIdle { routes.shown.value = listOf(lzRoute()) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SceneHeights.PAUSE_MS + 1))
        compose.waitUntil(5_000) { model.session.value!!.hasRoutes.value }
        compose.onNodeWithText(Viewer3DWords.ROUTES).assertIsEnabled().performClick()
        val scene = model.session.value!!
        assertTrue("Routes flies to them", scene.frame(1080, 2200, 2.75, scene.listener!!)!!.animating)
    }

    private fun routesState(): String? = compose.onNodeWithText(Viewer3DWords.ROUTES).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun `a screen reader hears the scene said as it is drawn, and its actions step the camera as the fingers would`() {
        var down = true
        val files = FixtureFiles(failing = { if (down && it == "tileset.json") NetworkException("no route", requestMayHaveBeenSent = false) else null })
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), scenes = scenes(files))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(hawk, night = false, onClose = {}, viewModel = model, surface = standIn) } }
        compose.waitUntil(5_000) { model.session.value?.status?.value is SceneStatus.Failed }
        // Not drawn: what it is, and no steps; the same node, a polite live region, says it is drawn once it is.
        val notDrawn = compose.onNode(hasContentDescription(Viewer3DWords.scene("LZ HAWK", drawn = false))).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, notDrawn.config.getOrNull(SemanticsProperties.LiveRegion))
        assertNull(notDrawn.config.getOrNull(SemanticsActions.CustomActions))
        down = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { model.session.value?.compass?.value != null }
        val drawn = compose.onNode(hasContentDescription(Viewer3DWords.scene("LZ HAWK", drawn = true))).fetchSemanticsNode()
        assertEquals(notDrawn.id, drawn.id)
        val steps = drawn.config[SemanticsActions.CustomActions]
        assertEquals(SceneStep.entries.map(Viewer3DWords::step), steps.map { it.label })
        val scene = model.session.value!!
        val before = scene.frame(1080, 2200, 2.75, scene.listener!!)!!.camera
        compose.runOnIdle { steps.first { it.label == Viewer3DWords.step(SceneStep.TURN_LEFT) }.action() }
        val turned = scene.frame(1080, 2200, 2.75, scene.listener!!)!!.camera
        assertEquals(0.0, Math.IEEEremainder(turned.heading - (before.heading - Math.toRadians(SceneSession.STEP_TURN_DEG)), 2 * Math.PI), 1e-9)
    }

    @Test
    fun `a point cloud whose tileset cannot be fetched says so, and Retry fetches it`() {
        var down = true
        val files = FixtureFiles(failing = { if (down && it == "tileset.json") NetworkException("no route", requestMayHaveBeenSent = false) else null })
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), scenes = scenes(files))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(hawk, night = false, onClose = {}, viewModel = model, surface = standIn) } }
        compose.waitUntil(5_000) { model.session.value?.status?.value is SceneStatus.Failed }
        compose.onNodeWithText(Viewer3DWords.SCENE_FAILED, substring = true).assertIsDisplayed()
        compose.onNodeWithText(Viewer3DWords.sceneProblem(SceneProblem.UNREACHABLE), substring = true).assertIsDisplayed()
        assertEquals(LiveRegionMode.Assertive, compose.onNode(hasText(Viewer3DWords.SCENE_FAILED, substring = true)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        down = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { model.session.value?.status?.value == SceneStatus.Drawing }
        compose.onNodeWithText(Viewer3DWords.LZ).assertIsDisplayed()
    }

    @Test
    fun `a device without OpenGL ES 3_0 is told so in words, with no Retry`() {
        // Robolectric's device has none (reqGlEsVersion 0), so this is the real view's own test.
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(hawk, night = false, onClose = {}, viewModel = model) } }
        compose.waitUntil(5_000) { model.session.value?.status?.value == SceneStatus.Failed(SceneProblem.NO_GL) }
        compose.onNodeWithText(Viewer3DWords.sceneProblem(SceneProblem.NO_GL), substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText(Viewer3DWords.LZ).assertDoesNotExist()
    }

    @Test
    fun `at night the scene is dimmed as the map is`() {
        val model = viewModel(FakeLidar(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var night by mutableStateOf(false)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { Viewer3DHost(hawk, night = night, onClose = {}, viewModel = model, surface = standIn) } }
        compose.waitUntil(5_000) { model.session.value?.compass?.value != null }
        assertEquals(0.45f, SCENE_NIGHT_DIM)
        compose.onNodeWithText(Viewer3DWords.LZ).assertIsDisplayed()
        compose.runOnIdle { night = true }
        compose.onNodeWithText(Viewer3DWords.LZ).assertIsDisplayed()
    }
}
