package app.ezpztac.android

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.LatLon
import app.ezpztac.workspace.LibraryTab
import app.ezpztac.workspace.MapPointAction
import app.ezpztac.workspace.savedToLibrary
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The map's long-press menu (the design's MapMenu), and the Saved toast's *Open Library* (the design's Saved). `-Pezpz.screenshots` draws the menu. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class MapMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val place = MapMenuPlace(LatLon(34.78, -84.08), "16S GC 28788 55432")

    private fun show(choices: MapMenuChoices, mode: ThemeMode = ThemeMode.Dark, dismissed: () -> Unit = {}) = compose.setContent {
        EzpzTheme(mode) {
            Column(Modifier.padding(16.dp).width(256.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large).padding(vertical = 6.dp)) {
                MapMenuItems(place, choices, dismissed)
            }
        }
    }

    @Test
    fun `the menu is headed by the grid and offers what the account has, each choice closing it first`() {
        val done = mutableListOf<String>()
        show(
            MapMenuChoices(
                newLz = { done += "lz" }, addThreat = { done += "threat" }, routePoint = MapPointAction.AddTo("ROUTE 1") to { done += "point" }, copyGrid = { done += "copy" },
            ),
            dismissed = { done += "shut" },
        )
        compose.onNodeWithText("16S GC 28788 55432").assertIsDisplayed()
        compose.onNodeWithText("Opens another LZ/PZ with this target").assertIsDisplayed()
        compose.onNodeWithText("Adds a turn point to the end of ROUTE 1").assertIsDisplayed()
        compose.onNode(hasText("New LZ/PZ here") and hasClickAction(), useUnmergedTree = false).performClick()
        compose.onNode(hasText("Add threat here") and hasClickAction()).performClick()
        compose.onNode(hasText("Add route point here") and hasClickAction()).performClick()
        compose.onNode(hasText("Copy grid") and hasClickAction()).performClick()
        assertEquals(listOf("shut", "lz", "shut", "threat", "shut", "point", "shut", "copy"), done)
    }

    @Test
    fun `a choice whose panel the account is not offered is not shown, and copying the grid always is`() {
        show(MapMenuChoices())
        compose.onNodeWithText("New LZ/PZ here").assertDoesNotExist()
        compose.onNodeWithText("Add threat here").assertDoesNotExist()
        compose.onNodeWithText("Add route point here").assertDoesNotExist()
        compose.onNodeWithText("Copy grid").assertIsDisplayed()
    }

    private fun routePoint(action: MapPointAction, words: String, enabled: Boolean) {
        show(MapMenuChoices(routePoint = action to {}))
        compose.onNodeWithText(words).assertIsDisplayed()
        val item = compose.onNode(hasText("Add route point here") and hasClickAction())
        if (enabled) item.assertIsEnabled() else item.assertIsNotEnabled()
    }

    @Test
    fun `add route point here with no route to add to says it starts one`() = routePoint(MapPointAction.StartRoute, "Starts a new route here", enabled = true)

    @Test
    fun `add route point here with routes to choose between asks for one, and cannot be chosen`() =
        routePoint(MapPointAction.ChooseRoute, "Tap a route first, to choose which", enabled = false)

    @Test
    fun `add route point here in an imported mission says why not, and cannot be chosen`() =
        routePoint(MapPointAction.Mission, "An imported mission's routes are its file's", enabled = false)

    @Test
    fun `add route point here in a finished pack says it is off, and cannot be chosen`() =
        routePoint(MapPointAction.Locked("Off in a finished pack"), "Off in a finished pack", enabled = false)

    @Test
    fun `a new LZ-PZ here is off in a finished pack, and says so where it would say what it does`() {
        show(MapMenuChoices(newLz = {}, newLzOff = "Off in a finished pack"))
        compose.onNodeWithText("Off in a finished pack").assertIsDisplayed()
        compose.onNodeWithText("Opens another LZ/PZ with this target").assertDoesNotExist()
        compose.onNode(hasText("New LZ/PZ here") and hasClickAction()).assertIsNotEnabled()
    }

    // -- The real menu: its popup, where it goes, and its grid ----------------------------------------------------------------

    private fun menu(choices: MapMenuChoices, mode: ThemeMode = ThemeMode.Dark, at: IntOffset = IntOffset(300, 1100)) = compose.setContent {
        EzpzTheme(mode) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF3A4A3A))) {          // the map's stand-in
                MapMenu(expanded = true, place = place, at = at, choices = choices, onDismiss = {})
            }
        }
    }

    @Test
    fun `the menu says what it is to a screen reader and heads itself with the grid, at the design's width`() {
        menu(MapMenuChoices(newLz = {}))
        val pane = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Map menu for 16S GC 28788 55432"))
        pane.assertExists()
        assertEquals(256f, with(compose.density) { pane.fetchSemanticsNode().size.width.toDp().value }, 0.5f)
        compose.onNode(isHeading() and hasText("16S GC 28788 55432")).assertExists()
    }

    @Test
    fun `nothing is drawn while the menu is shut`() {
        compose.setContent { EzpzTheme(ThemeMode.Dark) { MapMenu(expanded = false, place = place, at = IntOffset(10, 10), choices = MapMenuChoices(), onDismiss = {}) } }
        compose.onNodeWithText("Copy grid").assertDoesNotExist()
    }

    private fun gridOnOneLine() {
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("16S GC 28788 55432", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val l = layout.single()
        assertEquals(1, l.lineCount)
        assertFalse("the grid is cut", l.didOverflowWidth || l.isLineEllipsized(0))
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun `at large text the grid stays on one line, the menu widening for it`() {
        menu(MapMenuChoices(newLz = {}), at = IntOffset(200, 1400))
        gridOnOneLine()
        val width = with(compose.density) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode().size.width.toDp().value }
        assertTrue("the menu is $width dp", width > 256f && width <= 344f)
        captureScreenRoboImage("build/screenshots/map-menu-large-text.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 2.0f)
    fun `at the largest text the grid is made smaller rather than split, the menu no wider than the window`() {
        menu(MapMenuChoices(newLz = {}), at = IntOffset(200, 1400))
        gridOnOneLine()
        val width = with(compose.density) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode().size.width.toDp().value }
        assertTrue("the menu is $width dp", width <= 344f)
    }

    @Test
    fun `the menu goes above and to the right of the press, else to the left, else below, never off the window`() {
        val window = IntSize(1080, 2400)
        val menu = IntSize(600, 800)
        fun at(x: Int, y: Int) = mapMenuPosition(IntOffset(x, y), menu, window, beside = 102, below = 24, margin = 24)
        assertEquals(IntOffset(300 + 102, 1500 + 24 - 800), at(300, 1500))                        // the design's: right, its foot just under the press
        assertEquals(IntOffset(900 - 102 - 600, 1500 + 24 - 800), at(900, 1500))                  // near the right edge: to the left
        assertEquals(IntOffset(300 + 102, 400 - 24), at(300, 400))                                  // near the top: below
        assertEquals(IntOffset(1080 - 24 - 600, 1500 + 24 - 800), at(540, 1500))                   // no room on either side: against the right margin
        assertEquals(IntOffset(300 + 102, 2400 - 24 - 800), at(300, 2390))                         // at the foot: no lower than the margin
        assertEquals(IntOffset(24, 24), mapMenuPosition(IntOffset(10, 10), IntSize(2000, 3000), window, 102, 24, 24))   // bigger than the window: its corner
    }

    @Test fun menu() = shot("map-menu") { MapMenuChoices(newLz = {}, addThreat = {}, routePoint = MapPointAction.AddTo("ROUTE 1") to {}) }

    @Test fun menuNight() = shot("map-menu-night", ThemeMode.Night) { MapMenuChoices(newLz = {}, addThreat = {}, routePoint = MapPointAction.ChooseRoute to {}) }

    @Test fun menuLight() = shot("map-menu-light", ThemeMode.Light) { MapMenuChoices(newLz = {}, routePoint = MapPointAction.StartRoute to {}) }

    @Test
    fun `the grid heads the menu on one line, as the design draws it`() {
        show(MapMenuChoices())
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("16S GC 28788 55432", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertEquals(1, layout.single().lineCount)
    }

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, choices: () -> MapMenuChoices) {
        menu(choices(), mode)
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    // -- The Saved toast ------------------------------------------------------------------------------------------------

    @Test
    fun `the saved toast offers open library at its own tab, and opens it when asked`() = runTest {
        val snackbars = SnackbarHostState()
        val opened = mutableListOf<LibraryTab>()
        val shown = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snackbars.panelToast(savedToLibrary("LZ HAWK"), LibraryTab.LZ, LibraryTab.entries) { opened += it }
        }
        assertEquals("Open Library", snackbars.currentSnackbarData!!.visuals.actionLabel)
        snackbars.currentSnackbarData!!.performAction()
        shown.join()
        assertEquals(listOf(LibraryTab.LZ), opened)
    }

    @Test
    fun `any other toast, or a saved one whose tab the account has not, is said as it is`() = runTest {
        val snackbars = SnackbarHostState()
        val opened = mutableListOf<LibraryTab>()
        for ((text, tabs) in listOf("Added a point to the end of ROUTE 1." to LibraryTab.entries, savedToLibrary("TAXI") to listOf(LibraryTab.LZ))) {
            val shown = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                snackbars.panelToast(text, LibraryTab.POINTS, tabs) { opened += it }
            }
            assertNull(snackbars.currentSnackbarData!!.visuals.actionLabel)
            snackbars.currentSnackbarData!!.dismiss()
            shown.join()
        }
        assertEquals(emptyList<LibraryTab>(), opened)
    }
}
