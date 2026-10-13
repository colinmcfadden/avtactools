package app.ezpztac.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MapScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private val styles = MapStyles.available("pk.t")

    /** What the search finds: a place (true), or words it did not understand (false, said as the view model says it). */
    private var found = true
    private val search = MapSearchState()
    private var said by mutableStateOf<String?>(null)
    private val notUnderstood = "Could not read that as a grid or a coordinate."

    private fun state(
        readout: Readout? = Readout.of(LatLon(34.783817, -84.08219)),
        error: String? = null,
        style: MapBaseStyle = styles.first(),
    ) = MapUiState(styles = styles, style = style, readout = readout, searchError = error)

    private fun show(
        state: MapUiState, gps: GpsState = GpsState.Off, camera: CameraState? = CameraState(LatLon(34.78, -84.08), 16.0, 30.0), mode: ThemeMode = ThemeMode.Dark,
        padding: MapPadding = MapPadding(),
        buttonsAtFoot: Boolean = false,
        grid: Boolean? = null,
        chrome: MapChromeBoxes? = null,
    ) {
        compose.setContent {
            EzpzTheme(mode) {
                MapScreen(
                    state = said?.let { state.copy(searchError = it) } ?: state, camera = camera, gps = gps,
                    onSearch = { log += "search $it"; if (!found) said = notUnderstood; found },
                    onClearSearchError = { log += "clearError"; said = null }, onSelectStyle = { log += "style $it" },
                    onToggleGps = { log += "gps" }, onGpsPermissionResult = { log += "permission $it" }, onLocateMe = { log += "locate" },
                    onFaceNorth = { log += "north" }, onCopyReadout = { log += "copy $it" }, padding = padding, buttonsAtFoot = buttonsAtFoot,
                    onToggleGrid = grid?.let { { log += "grid" } }, chrome = chrome, search = search,
                    map = { Box(Modifier.fillMaxSize().background(Color(0xFF3A5A40))) },          // stands in for the GPU-drawn map
                )
            }
        }
    }

    @Test
    fun `the crosshair is drawn at the middle of the map the padding leaves, where the camera's centre is`() {
        show(state(), padding = MapPadding(top = 100.dp, bottom = 300.dp))
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val cross = compose.onNodeWithContentDescription("Map centre").fetchSemanticsNode()
        val middle = cross.positionInRoot.y + cross.size.height / 2f
        val expected = with(compose.density) { 100.dp.toPx() + (screen - 100.dp.toPx() - 300.dp.toPx()) / 2f }
        assertEquals(expected, middle, 1f)
    }

    @Test
    fun `a tablet's map buttons stand at its foot, where no chips or sheet are`() {
        show(state(), buttonsAtFoot = true)
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val layers = compose.onNodeWithContentDescription("Base map", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the buttons are not at the foot: ${layers.bottom} of $screen", layers.bottom > screen * 0.75f)
    }

    @Test
    fun `a phone's map buttons stand under the row`() {
        show(state())
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val layers = compose.onNodeWithContentDescription("Base map", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the phone's buttons left the top: ${layers.top}", layers.top < screen * 0.3f)
    }

    @Test
    fun `the readout under the crosshair is the grid, and a tap copies it`() {
        show(state())
        compose.onNodeWithText("16S GD 66993 52949").assertIsDisplayed()
        compose.onNodeWithText("34.78382, -84.08219").assertDoesNotExist()                 // the design's pill is the grid alone
        compose.onNodeWithContentDescription("Map centre").assertIsDisplayed()
        compose.onNodeWithContentDescription("Grid under the crosshair, 16S GD 66993 52949. Tap to copy").performClick()
        assertEquals(listOf("copy 16S GD 66993 52949"), log)
    }

    @Test
    fun `where there is no grid the readout gives the degrees, and copies those`() {
        show(state(readout = Readout(null, "89.90000, 10.00000")))
        compose.onNodeWithText("89.90000, 10.00000").performClick()
        assertEquals(listOf("copy 89.90000, 10.00000"), log)
    }

    @Test
    fun `before the camera has reported there is no readout and no compass`() {
        show(state(readout = null), camera = null)
        compose.onNode(hasContentDescription("Grid under the crosshair", substring = true)).assertDoesNotExist()
        compose.onNode(hasContentDescription("Face north", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `facing north there is no compass to tap`() {
        show(state(), camera = CameraState(LatLon(34.78, -84.08), 16.0, 0.2))
        compose.onNode(hasContentDescription("Face north", substring = true)).assertDoesNotExist()
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription("Search a grid or lat/long").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the folded search is one named button, and opening it puts the field in focus across the row`() {
        show(state())
        compose.onNode(hasSetTextAction()).assertDoesNotExist()                                   // folded: a button, no field
        compose.onNode(hasContentDescription("Search a grid or lat/long") and hasClickAction()).assertIsDisplayed()
        openSearch()
        assertTrue(search.expanded)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        // Across the row, over the chips and the account: from one side of the screen to the other but for its margins.
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("Close the search").fetchSemanticsNode().boundsInRoot
        val go = compose.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("the bar spans the row: $back … $go of $width", back.left < width * 0.1f && go.right > width * 0.85f && field.width > width * 0.5f)
        compose.onNodeWithContentDescription("Search a grid or lat/long").assertDoesNotExist()   // the row under it says nothing
    }

    @Test
    fun `typing a grid and pressing the keyboard's search sends it, and a place found folds the search`() {
        show(state())
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("16S GD 66993 52949")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("search 16S GD 66993 52949"), log)
        assertFalse(search.expanded)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals("16S GD 66993 52949", search.text)                                           // kept for the next opening
    }

    @Test
    fun `the search action at the end of the open bar sends it too`() {
        show(state())
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("34.5, -84.1")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        assertEquals(listOf("search 34.5, -84.1"), log)
        assertFalse(search.expanded)
    }

    @Test
    fun `a search that was not understood stays open and shows why, and typing again clears it`() {
        found = false
        show(state())
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("16S GD 6699Z")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertTrue(search.expanded)                                                                // still open, to be put right
        compose.onNodeWithText(notUnderstood).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performTextInput("1")
        assertEquals(listOf("search 16S GD 6699Z", "clearError"), log)
        compose.onNodeWithText(notUnderstood).assertDoesNotExist()
    }

    @Test
    fun `the back arrow folds the search, and so does system Back, keeping what was typed`() {
        show(state())
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("16S GD")
        compose.onNodeWithContentDescription("Close the search").performClick()
        compose.waitForIdle()
        assertFalse(search.expanded)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search a grid or lat/long").assertIsDisplayed()
        openSearch()
        compose.onNodeWithText("16S GD").assertIsDisplayed()                                      // what was typed is there again
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(search.expanded)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `folding the search lets go of what it said was wrong`() {
        found = false
        show(state())
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("NOWHERE")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText(notUnderstood).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close the search").performClick()
        compose.waitForIdle()
        assertEquals(listOf("search NOWHERE", "clearError"), log)
        compose.onNodeWithText(notUnderstood).assertDoesNotExist()
    }

    @Test
    fun `a tap on the map with the search open only folds it, and with it folded the tap is the map's`() {
        show(state())
        openSearch()
        assertTrue(search.foldOnTap())                                                             // the tap is taken by folding
        compose.waitForIdle()
        assertFalse(search.expanded)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertFalse(search.foldOnTap())                                                            // folded: the map's
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `the search, the chips and the account share one height on the row, and each is a 48 dp touch target`() {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                MapScreen(
                    state = state(), camera = null, gps = GpsState.Off, onSearch = { true }, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {},
                    onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {}, search = search,
                    account = { Surface(onClick = {}, shape = CircleShape, modifier = Modifier.size(MapChrome.CHIPS_HEIGHT).semantics { contentDescription = "Account" }) {} },
                    chips = {
                        MapChip(EzpzIcons.person, "Personal", description = "Workspace: Personal", onClick = {})
                        MapChip(EzpzIcons.fileOpen, "", description = "Import", onClick = {})
                    },
                    map = { Box(Modifier.fillMaxSize().background(Color(0xFF3A5A40))) },
                )
            }
        }
        val minimum = with(compose.density) { 48.dp.toPx() } - 0.5f
        val drawn = with(compose.density) { MapChrome.CHIPS_HEIGHT.toPx() }
        val nodes = listOf("Search a grid or lat/long", "Workspace: Personal", "Import", "Account").map { compose.onNodeWithContentDescription(it).fetchSemanticsNode() }
        nodes.forEach { node ->
            assertTrue("touch ${node.touchBoundsInRoot}", node.touchBoundsInRoot.width >= minimum && node.touchBoundsInRoot.height >= minimum)
            assertEquals(drawn, node.boundsInRoot.height, 1f)
        }
        val middles = nodes.map { it.boundsInRoot.center.y }
        assertTrue("lined up: $middles", middles.all { abs(it - middles[0]) < 1f })
        val lefts = nodes.map { it.boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)                                                        // in the row's order, the map between them
        // The account's middle over the middle of the map's buttons, under it at the end.
        val account = nodes.last().boundsInRoot.center.x
        val layers = compose.onNodeWithContentDescription("Base map", substring = true).fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(layers, account, 1f)
    }

    @Test
    fun `a tablet's search bar is always open, and sends what is typed`() {
        show(state(), buttonsAtFoot = true)
        compose.onNode(hasSetTextAction()).performTextInput("34.5, -84.1")
        compose.onNodeWithContentDescription("Search a grid or lat/long").performClick()
        assertEquals(listOf("search 34.5, -84.1"), log)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()                                    // never folded
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
    fun `the Layers menu has the MGRS grid's switch under the base maps, and the button says when it is on`() {
        show(state().copy(grid = true), grid = true)
        compose.onNodeWithContentDescription("Base map: Satellite. MGRS grid on").performClick()
        compose.onNode(hasText("MGRS grid") and isToggleable()).assertIsOn().performClick()
        assertEquals(listOf("grid"), log)
        compose.onNodeWithText("Topo").assertIsDisplayed()                              // the menu stays open to see it turn off
    }

    @Test
    fun `the grid's switch is off while the grid is`() {
        show(state(), grid = false)
        compose.onNodeWithContentDescription("Base map: Satellite").performClick()
        compose.onNode(hasText("MGRS grid") and isToggleable()).assertIsOff()
    }

    @Test
    fun `with nothing to turn it, the grid's switch is not offered`() {
        show(state())
        compose.onNodeWithContentDescription("Base map: Satellite").performClick()
        compose.onNodeWithText("MGRS grid").assertDoesNotExist()
    }

    @Test
    fun `what floats over the map is reported where it is, for the grid's labels to keep off`() {
        val chrome = MapChromeBoxes()
        show(state(), chrome = chrome)
        compose.waitForIdle()
        val boxes = chrome.shown()
        assertTrue("the row, the buttons and the readout: $boxes", boxes.size >= 3)
        val row = compose.onNodeWithContentDescription("Search a grid or lat/long").fetchSemanticsNode().boundsInRoot
        assertTrue(boxes.any { it.top <= row.top && it.bottom >= row.bottom && it.left <= row.left })
        // From the window's top, status bar and all: the map is drawn under it.
        assertTrue("the row's box starts at the window's top", boxes.any { it.top == 0f && it.bottom >= row.bottom })
        val pill = compose.onNodeWithText("16S", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(boxes.any { it.contains(pill.center) })
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
