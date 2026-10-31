package app.ezpztac.android.frame

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelSheetDefaults
import app.ezpztac.designsystem.PanelSheetState
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.map.GpsState
import app.ezpztac.map.MapChrome
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapStyles
import app.ezpztac.map.MapUiState
import app.ezpztac.map.Readout
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.LatLon
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.packs.PackStatusLine
import app.ezpztac.packs.PackWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's frame over a stand-in map: which panel shows, the tabs and their counts, the sheet's heights, that the map still takes a tap, and the frame hidden
 * by a tap on the map (the owner's decision of 2026-10-13), with the real map screen's row and readout over the stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PhoneFrameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private lateinit var sheet: PanelSheetState
    private val frameState = FrameState()
    private var mapFrame: MapFrame? = null
    private val pack = OpenPackUi("p-dk", "OP DK", emptyList(), PackStatus.OFFLINE, finished = false, readOnly = false, pending = 0)

    private fun show(
        initial: Panel = Panel.Lz,
        detent: SheetDetent = SheetDetent.Half,
        counts: PanelCounts = PanelCounts(threats = 2, imports = 3),
        panels: List<Panel> = Panel.entries - Panel.Pack,
        onFrame: (MapFrame) -> Unit = {},
        footer: (@Composable () -> Unit)? = null,
        snackbars: SnackbarHostState? = null,
        /** The real map screen over the stand-in map: its row (with a pack's chip and line) and its readout and buttons. */
        withRow: Boolean = false,
        backShows: Boolean = true,
        before: @Composable () -> Unit = {},
        panel: @Composable (Panel) -> Unit = { Text("Inside ${it.label}") },
    ) {
        sheet = PanelSheetState(detent)
        frameState.choose(initial)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                before()
                PhoneFrame(
                    panels = panels, current = shownPanel(frameState.chosen, panels), counts = counts,
                    onPick = { log += "pick ${it.label}"; frameState.choose(it) },
                    sheet = sheet, snackbars = snackbars ?: remember { SnackbarHostState() },
                    panel = panel,
                    footer = footer,
                    hidden = frameState.hidden, onShow = frameState::show, backShows = backShows,
                    closed = frameState.closed, onClose = frameState::close,
                ) { frame ->
                    mapFrame = frame
                    onFrame(frame)
                    val map: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color(0xFF55703F)).clickable { log += "map" }) }
                    if (withRow) {
                        val styles = MapStyles.available("pk.t")
                        MapScreen(
                            state = MapUiState(styles, styles.first(), Readout.of(LatLon(34.783817, -84.08219))), camera = null, gps = GpsState.Off,
                            onSearch = { true }, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                            padding = frame.padding, controlsBottom = frame.controlsBottom, hiding = frame.hiding,
                            chips = { WorkspaceChips(pack, packsHere = true, onSwitcher = {}, onImport = {}, onLibrary = {}) },
                            belowChips = { PackStatusLine(pack) },
                            map = map,
                        )
                    } else {
                        map()
                    }
                }
            }
        }
    }

    private fun hide() {
        compose.runOnIdle { frameState.hide() }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `the chosen panel shows and the others do not, though they stay alive`() {
        show(Panel.Routes)
        compose.onNodeWithText("Inside Routes").assertIsDisplayed()
        compose.onNodeWithText("Inside LZ/PZ").assertDoesNotExist()
        compose.onNodeWithText("Inside Threats").assertDoesNotExist()
    }

    @Test
    fun `while a pack is open its tab comes first, with a dot that says what changed`() {
        show(Panel.Lz, counts = PanelCounts(packNew = 2), panels = Panel.entries)
        compose.onNodeWithContentDescription("Pack, changed since you looked").assertIsDisplayed()
        val tabs = compose.onAllNodes(isSelectable()).fetchSemanticsNodes().map { it.boundsInRoot.left }
        val pack = compose.onNodeWithContentDescription("Pack, changed since you looked").fetchSemanticsNode().boundsInRoot.left
        assertEquals(tabs.minOrNull(), pack)
    }

    @Test
    fun `a tab picks its panel, and is marked`() {
        show(Panel.Lz)
        compose.onNode(hasText("Threats") and isSelectable()).performClick()
        compose.onNodeWithText("Inside Threats").assertIsDisplayed()
        compose.onNode(hasText("Threats") and isSelectable()).assertIsSelected()
        assertEquals(listOf("pick Threats"), log)
    }

    @Test
    fun `threats and imports carry their counts, and say what they count`() {
        show()
        compose.onNodeWithContentDescription("Threats, 2 threats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Imports, 3 items").assertIsDisplayed()
    }

    @Test
    fun `nothing to count carries no badge`() {
        show(counts = PanelCounts())
        compose.onNodeWithContentDescription("Threats, 0 threats").assertDoesNotExist()
        compose.onNodeWithContentDescription("Threats", substring = true).assertDoesNotExist()   // the tab says only its label
    }

    @Test
    fun `a panel whose feature is off has no tab`() {
        show(panels = listOf(Panel.Lz, Panel.Imports))
        compose.onNode(hasText("Routes") and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("Threats") and isSelectable()).assertDoesNotExist()
    }

    @Test
    fun `the header opens the panel to full height, then lowers it to half`() {
        show(Panel.Imports, SheetDetent.Half)
        compose.onNodeWithContentDescription("Open the Imports panel full height").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Full, sheet.settled)
        compose.onNodeWithContentDescription("Lower the Imports panel").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Half, sheet.settled)
    }

    @Test
    fun `the sheet does not take a tap meant for the map above it`() {
        show(detent = SheetDetent.Half)
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.12f)) }       // well above a half-height sheet
        assertEquals(listOf("map"), log)
    }

    @Test
    fun `the readout stands on the sheet's edge, and a sheet raised past half slides over it`() {
        var edge: () -> Float? = { null }
        show(detent = SheetDetent.Lowered, onFrame = { edge = it.controlsBottom })
        compose.waitForIdle()
        assertEquals(sheet.anchors!!.loweredPx, edge()!!, 0.5f)
        compose.onNodeWithContentDescription("Open the LZ/PZ panel full height").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Full, sheet.settled)
        assertEquals(sheet.anchors!!.halfPx, edge()!!, 0.5f)                                   // not the full sheet's edge, under the row
    }

    @Test
    fun `the map's middle follows the height the sheet rests at, the bar under it, and stays at half's when it is full`() {
        show(detent = SheetDetent.Half)
        compose.waitForIdle()
        val half = mapFrame!!.padding.bottom
        val halfHeight = with(compose.density) { sheet.anchors!!.heightOf(SheetDetent.Half).toDp() }
        // At half, the half sheet's height and the bar's (the map is drawn under the bar too).
        assertEquals(halfHeight.value + 80f, half.value, 0.5f)
        assertTrue(half > PanelSheetDefaults.LoweredHeight + 100.dp)                               // and not the lowered one's
        compose.onNodeWithContentDescription("Open the LZ/PZ panel full height").performClick()
        compose.waitForIdle()
        assertEquals(SheetDetent.Full, sheet.settled)
        assertEquals(half, mapFrame!!.padding.bottom)
    }

    @Test
    fun `lowered, the map's middle is the middle of all but the sheet's header and the bar`() {
        show(detent = SheetDetent.Lowered)
        compose.waitForIdle()
        assertEquals(PanelSheetDefaults.LoweredHeight.value + 80f, mapFrame!!.padding.bottom.value, 0.5f)
        assertEquals(MapChrome.ROW_BOTTOM, mapFrame!!.padding.top)                                 // the row's bottom (no status bar here)
    }

    @Test
    fun `lowered, the panel's header still shows`() {
        show(Panel.Routes, SheetDetent.Lowered)
        compose.onNodeWithContentDescription("Open the Routes panel full height").assertIsDisplayed()
    }

    @Test
    fun `at full height a toast stands above the panel's footer, not on its buttons`() {
        val toasts = SnackbarHostState()
        show(Panel.Routes, detent = SheetDetent.Full, snackbars = toasts, footer = { Box(Modifier.fillMaxWidth().height(64.dp)) { Text("Sketch a route") } })
        compose.runOnIdle { CoroutineScope(Dispatchers.Main).launch { toasts.showSnackbar("Saved") } }
        compose.waitForIdle()
        val toast = compose.onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot
        val foot = compose.onNodeWithText("Sketch a route").fetchSemanticsNode().boundsInRoot
        assertTrue("toast ${toast.bottom} over the footer's top ${foot.top}", toast.bottom <= foot.top)
    }

    // -- The panel closed by its header's close button -----------------------------------------------------------------------------

    @Test
    fun `the close button takes the panel away and leaves the bar, and the map has the screen above the bar`() {
        show(Panel.Routes, SheetDetent.Half)
        compose.onNodeWithContentDescription("Close the Routes panel").performClick()
        compose.waitForIdle()
        assertTrue(frameState.closed)
        compose.onNodeWithText("Inside Routes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open the Routes panel full height").assertDoesNotExist()
        compose.onNode(hasText("Routes") and isSelectable()).assertIsDisplayed()                       // the bar stays, to bring it back
        // The map's middle and the readout go down to the bar's top; where the sheet was is the map's.
        assertEquals(80f, mapFrame!!.padding.bottom.value, 0.5f)
        val root = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val barTop = root - with(compose.density) { 80.dp.toPx() }
        assertEquals(barTop, mapFrame!!.controlsBottom()!!, 1.5f)
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.7f)) }
        assertEquals(listOf("map"), log)
    }

    @Test
    fun `a closed panel comes back when a panel is picked on the bar, and stays composed meanwhile`() {
        show(Panel.Lz, SheetDetent.Half, panel = { each -> var taps by remember { mutableStateOf(0) }; Text("${each.label} tapped $taps", Modifier.clickable { taps++ }) })
        compose.onNodeWithText("LZ/PZ tapped 0").performClick()
        compose.onNodeWithContentDescription("Close the LZ/PZ panel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("LZ/PZ tapped 1").assertDoesNotExist()
        compose.onNode(hasText("LZ/PZ") and isSelectable()).performClick()
        compose.waitForIdle()
        assertFalse(frameState.closed)
        compose.onNodeWithText("LZ/PZ tapped 1").assertIsDisplayed()                                  // the same panel, not one made again
    }

    @Test
    fun `closed, a toast stands above the readout over the bar`() {
        val toasts = SnackbarHostState()
        show(Panel.Lz, SheetDetent.Full, snackbars = toasts, withRow = true)
        compose.onNodeWithContentDescription("Close the LZ/PZ panel").performClick()
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main).launch { toasts.showSnackbar("Saved") } }
        compose.waitForIdle()
        val toast = compose.onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNode(hasContentDescription("Grid under the crosshair", substring = true)).fetchSemanticsNode().boundsInRoot
        val tab = compose.onNode(hasText("LZ/PZ") and isSelectable()).fetchSemanticsNode().boundsInRoot
        assertTrue("the toast ${toast.bottom} above the pill ${pill.top}", toast.bottom <= pill.top)
        assertTrue("the pill ${pill.bottom} above the bar ${tab.top}", pill.bottom <= tab.top)
    }

    // -- The frame hidden by a tap on the map --------------------------------------------------------------------------------------

    @Test
    fun `hidden, the row, the pack's line, the sheet and the bar are neither read nor touched, and the readout and the map's buttons stay`() {
        show(Panel.Routes, SheetDetent.Half, withRow = true)
        compose.onNodeWithContentDescription("Search a grid or lat/long").assertIsDisplayed()
        compose.onNodeWithText(PackWords.status(pack)).assertIsDisplayed()
        hide()
        compose.onNodeWithContentDescription("Search a grid or lat/long").assertDoesNotExist()
        compose.onNodeWithContentDescription(PackWords.chipDescription(pack)).assertDoesNotExist()
        compose.onNodeWithText(PackWords.status(pack)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Open the Routes panel full height").assertDoesNotExist()
        compose.onNodeWithText("Inside Routes").assertDoesNotExist()
        compose.onAllNodes(isSelectable()).fetchSemanticsNodes().let { assertTrue("the bar's tabs: $it", it.isEmpty()) }
        compose.onNode(hasContentDescription("Grid under the crosshair", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Base map: Satellite").assertIsDisplayed()
        // Where the bar and the sheet were is the map's now (beside the readout, which stands at the foot).
        compose.onRoot().performTouchInput { click(Offset(40f, height - 20f)) }
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.6f)) }
        assertEquals(listOf("map", "map"), log)
    }

    @Test
    fun `hidden, Back shows it again, the sheet at the height it had and the panel as it was`() {
        show(Panel.Threats, SheetDetent.Half, withRow = true)
        hide()
        assertTrue(frameState.hidden)
        back()
        assertFalse(frameState.hidden)
        assertEquals(SheetDetent.Half, sheet.settled)
        compose.onNodeWithText("Inside Threats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search a grid or lat/long").assertIsDisplayed()
        compose.onNode(hasText("Threats") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `hidden, Back is asked before any other, even one that came after the frame, and shown it asks nothing`() {
        // One composed once the frame was already up (as a panel's own would be): newer than the frame, yet the hidden frame is asked first.
        var later by mutableStateOf(false)
        show(Panel.Lz, withRow = true, before = { if (later) BackHandler { log += "the page's" } })
        compose.runOnIdle { later = true }
        compose.waitForIdle()
        hide()
        back()
        assertFalse("the frame first", frameState.hidden)
        assertEquals(emptyList<String>(), log)
        back()
        assertEquals(listOf("the page's"), log)                                                    // shown: the frame no longer asks
    }

    @Test
    fun `with a page over the frame that has Back for itself, Back is the page's`() {
        show(Panel.Lz, withRow = true, backShows = false, before = { BackHandler { log += "the page's" } })
        hide()
        back()
        assertTrue(frameState.hidden)
        assertEquals(listOf("the page's"), log)
    }

    @Test
    fun `a panel chosen in code shows the frame, even the panel already chosen`() {
        show(Panel.Routes, SheetDetent.Lowered, withRow = true)
        hide()
        compose.runOnIdle { frameState.choose(Panel.Routes) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open the Routes panel full height").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search a grid or lat/long").assertIsDisplayed()
        hide()
        compose.runOnIdle { frameState.choose(Panel.Threats) }
        compose.waitForIdle()
        compose.onNodeWithText("Inside Threats").assertExists()
        compose.onNode(hasText("Threats") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `hidden, the camera's padding is the system's bars alone, and the readout stands at the foot of the screen`() {
        show(Panel.Lz, SheetDetent.Half, withRow = true)
        compose.waitForIdle()
        assertEquals(MapChrome.ROW_BOTTOM, mapFrame!!.padding.top)
        assertTrue(mapFrame!!.padding.bottom > 300.dp)
        hide()
        // No status bar or system buttons in this window: the whole map.
        assertEquals(0f, mapFrame!!.padding.top.value, 0.01f)
        assertEquals(0f, mapFrame!!.padding.bottom.value, 0.01f)
        val root = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        assertEquals(root, mapFrame!!.controlsBottom()!!, 0.5f)
        val pill = compose.onNode(hasContentDescription("Grid under the crosshair", substring = true)).fetchSemanticsNode().boundsInRoot
        assertEquals(root - with(compose.density) { MapChrome.PILL_GAP.toPx() }, pill.bottom, 1.5f)
        back()
        assertEquals(MapChrome.ROW_BOTTOM, mapFrame!!.padding.top)
        assertTrue(mapFrame!!.padding.bottom > 300.dp)
    }

    @Test
    fun `hidden, a toast stands above the readout at the foot of the screen`() {
        val toasts = SnackbarHostState()
        show(Panel.Lz, SheetDetent.Full, snackbars = toasts, withRow = true)
        hide()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main).launch { toasts.showSnackbar("Saved") } }
        compose.waitForIdle()
        val toast = compose.onNodeWithText("Saved").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNode(hasContentDescription("Grid under the crosshair", substring = true)).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("the toast ${toast.bottom} above the pill ${pill.top}", toast.bottom <= pill.top)
        assertTrue("near the foot: ${toast.bottom} of $root", toast.bottom > root * 0.75f)
    }

    @Test
    fun `hiding the frame lets go of what a panel's field held, and the keyboard with it`() {
        show(Panel.Lz, SheetDetent.Half, panel = { var text by remember { mutableStateOf("") }; Column { BasicTextField(text, { text = it }) } })
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        hide()
        back()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `hidden, what is in the frame stays composed and comes back as it was`() {
        show(Panel.Lz, SheetDetent.Half, panel = { each -> var taps by remember { mutableStateOf(0) }; Text("${each.label} tapped $taps", Modifier.clickable { taps++ }) })
        compose.onNodeWithText("LZ/PZ tapped 0").performClick()
        compose.onNodeWithText("LZ/PZ tapped 1").assertIsDisplayed()
        hide()
        compose.onNodeWithText("LZ/PZ tapped 1").assertDoesNotExist()
        back()
        compose.onNodeWithText("LZ/PZ tapped 1").assertIsDisplayed()                                  // the same panel, not one made again
    }

    @Test
    fun `while the frame slides away it says nothing`() {
        show(Panel.Lz, SheetDetent.Half)
        compose.mainClock.autoAdvance = false
        // A state written from the test is seen by the composition once its change is announced, which idling would do and a clock turned by hand does not.
        frameState.hide()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)                                                         // a third of the way
        assertTrue("hidden: ${frameState.hidden}", frameState.hidden)
        val tabs = compose.onAllNodes(isSelectable()).fetchSemanticsNodes()
        assertTrue("the bar's tabs while it slides: ${tabs.size}", tabs.isEmpty())
        compose.onNodeWithText("Inside LZ/PZ").assertDoesNotExist()                                   // nor the panel (that neither takes a touch: KeptAwayTest)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(frameState.hidden)
        assertTrue(compose.onAllNodes(isSelectable()).fetchSemanticsNodes().isEmpty())
    }
}
