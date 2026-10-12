package app.ezpztac.android.frame

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.designsystem.rememberPanelSheetState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tablet's frame (the design's AT screens): the rail, the panel's pane, the map, the supporting panes. `-Pezpz.screenshots` draws it at 1280 × 800. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletFrameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val picked = mutableListOf<Panel>()
    private val said = mutableListOf<String>()
    private var mapFrame: MapFrame? = null
    private var askAgain: () -> Unit = {}

    private fun frame(mode: ThemeMode = ThemeMode.Dark, withSupport: Boolean = false, withRoute: Boolean = false) = compose.setContent {
        var current by remember { mutableStateOf(Panel.Lz) }
        var support by remember { mutableStateOf(withSupport) }
        var route by remember { mutableStateOf(withRoute) }
        var asked by remember { mutableIntStateOf(0) }
        askAgain = { asked++ }
        EzpzTheme(mode) {
            TabletFrame(
                panels = Panel.entries, current = current, counts = PanelCounts(threats = 1, imports = 3),
                onPick = { current = it; picked += it }, snackbars = remember { SnackbarHostState() },
                rail = TabletRail(
                    importMenu = { dismiss -> Text("The import menu", Modifier.semantics { contentDescription = "Close the import menu" }.padding(8.dp)); said += "importMenu"; if (false) dismiss() },
                    onLibrary = { said += "library" },
                    account = { Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge).semantics { contentDescription = "Account" }) },
                ),
                head = PanelHead(current.label, "3 open · this session"),
                supporting = listOfNotNull(
                    if (support) {
                        SupportingPane(
                            key = "lz:1", title = "LZ/PZ 1", subtitle = "Aircraft, analysis and tools", closeLabel = "Close the LZ/PZ 1 pane",
                            onClose = { support = false; said += "close" }, request = asked,
                        ) { Column(Modifier.padding(16.dp)) { Text("Aircraft, analysis and the tools") } }
                    } else {
                        null
                    },
                    if (route) SupportingPane(key = "route:r1", title = null, onClose = { route = false; said += "closeRoute" }) { Text("ROUTE 1 plan") } else null,
                ),
                panel = { panel -> Column(Modifier.padding(16.dp)) { Text("${panel.label} panel body") } },
            ) { frame ->
                mapFrame = frame
                Box(Modifier.fillMaxSize().background(Color(0xFF3A4A3A))) { Text("The map", Modifier.padding(16.dp)) }
            }
        }
    }

    @Test
    fun `the panes stand beside the map while it keeps 400 dp, else the one opened last`() {
        assertEquals(TabletPanes(list = true, support = true), tabletPanes(1280.dp, collapsed = false, hasSupport = true, listLast = true))
        assertEquals(TabletPanes(list = true, support = false), tabletPanes(1280.dp, collapsed = false, hasSupport = false, listLast = true))
        assertEquals(TabletPanes(list = false, support = true), tabletPanes(1280.dp, collapsed = true, hasSupport = true, listLast = true))
        // 96 + 344 + 320 + 400 = 1160: narrower than that, one of the two.
        assertEquals(TabletPanes(list = true, support = true), tabletPanes(1160.dp, collapsed = false, hasSupport = true, listLast = false))
        assertEquals(TabletPanes(list = false, support = true), tabletPanes(1159.dp, collapsed = false, hasSupport = true, listLast = false))
        assertEquals(TabletPanes(list = true, support = false), tabletPanes(1024.dp, collapsed = false, hasSupport = true, listLast = true))
        assertEquals(TabletPanes(list = false, support = false), tabletPanes(1024.dp, collapsed = true, hasSupport = false, listLast = true))
    }

    @Test
    fun `the tablet's frame is for a window wide and tall enough, and a phone on its side keeps the phone's`() {
        assertTrue(usesTabletFrame(1280.dp, 800.dp))
        assertTrue(usesTabletFrame(840.dp, 480.dp))
        assertFalse(usesTabletFrame(839.dp, 800.dp))                                              // a tablet in portrait
        assertFalse(usesTabletFrame(915.dp, 411.dp))                                              // a phone on its side
        assertFalse(usesTabletFrame(840.dp, 479.dp))
    }

    @Test
    fun `the frame's state brings the list forward for a panel, and a supporting pane for a new one or one asked for again, not for one that comes back`() {
        val s = TabletFrameState()
        s.panelShown(Panel.Lz)
        assertTrue(s.listLast)
        s.supportShown("lz:1", 0)
        assertFalse(s.listLast)                                                                  // opened: it stands
        s.panelShown(Panel.Lz)
        assertFalse(s.listLast)                                                                  // the same panel again changes nothing
        s.panelShown(Panel.Threats)
        assertTrue(s.listLast)                                                                   // a new panel: the list
        s.supportShown(null, 0)                                                                   // the pane goes while Threats shows...
        s.supportShown("lz:1", 0)                                                                 // ...and comes back with LZ/PZ: the list the person picked stays
        assertTrue(s.listLast)
        s.supportShown("lz:1", 1)                                                                 // asked for again (Plan, the held bar's options): it stands
        assertFalse(s.listLast)
        s.supportShown("lz:2", 1)
        s.collapse()
        assertTrue(s.collapsed)
        s.showList()                                                                             // the Library's Open, the aircraft page, a held object's options
        assertFalse(s.collapsed)
        assertTrue(s.listLast)
        s.supportShown("lz:2", 1)
        assertTrue(s.listLast)                                                                   // nothing new about it
    }

    @Test
    fun `the rail picks a panel, counts what the panels hold in words, and holds Import, the Library and the account`() {
        frame()
        compose.onNodeWithText("LZ/PZ panel body").assertIsDisplayed()
        compose.onNodeWithContentDescription("Threats, 1 threat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Imports, 3 items").assertIsDisplayed()
        compose.onNodeWithText("Routes").performClick()
        assertEquals(listOf(Panel.Routes), picked)
        compose.onNodeWithText("Routes panel body").assertIsDisplayed()
        compose.onNodeWithText("LZ/PZ panel body").assertDoesNotExist()                          // composed, out of sight and of the semantics
        compose.onNodeWithText("Library").performClick()
        assertEquals(listOf("library"), said)
        compose.onNodeWithContentDescription("Account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Workspace: Personal").assertIsDisplayed()
    }

    @Test
    fun `the Import tile is one button, named, and opens its menu at the tile, and the Library is a button, not a fifth tab`() {
        frame()
        compose.onAllNodesWithContentDescription("Import a mission, local points or threats").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithContentDescription("Import a mission, local points or threats").assertHasClickAction().performClick()
        compose.onNodeWithText("The import menu").assertIsDisplayed()
        val library = compose.onNodeWithText("Library").fetchSemanticsNode()
        assertFalse(SemanticsProperties.Selected in library.config)
        // No button in the rail goes unnamed.
        for (node in compose.onRoot().fetchSemanticsNode().let { root -> generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten() }) {
            if (hasClickAction().matches(node)) {
                val named = SemanticsProperties.ContentDescription in node.config || SemanticsProperties.Text in node.config ||
                    node.children.any { SemanticsProperties.Text in it.config }
                assertTrue("an unnamed button: ${node.config}", named)
            }
        }
    }

    @Test
    fun `the switcher at the workspace tile goes with packs, and does not come back by itself`() {
        var packs by mutableStateOf(true)
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) {
                TabletFrame(
                    Panel.entries, Panel.Lz, PanelCounts(), {}, remember { SnackbarHostState() },
                    TabletRail(
                        {}, {}, {},
                        workspace = RailWorkspace(
                            label = "OP DK", description = "Workspace: Mission Pack OP DK. Change workspace", pack = true,
                            menu = if (packs) ({ _ -> Text("The switcher") }) else null,
                        ),
                    ),
                    panel = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithContentDescription("Workspace: Mission Pack OP DK. Change workspace").performClick()
        compose.onNodeWithText("The switcher").assertIsDisplayed()
        packs = false                                                                              // Mission Packs turned off with it open
        compose.waitForIdle()
        compose.onNodeWithText("The switcher").assertDoesNotExist()
        packs = true
        compose.waitForIdle()
        compose.onNodeWithText("The switcher").assertDoesNotExist()
    }

    @Test
    fun `the panel's pane collapses to give the map the room, and a panel picked brings it back`() {
        frame()
        compose.onNodeWithContentDescription("Collapse the LZ/PZ panel").performClick()
        compose.onNodeWithText("LZ/PZ panel body").assertDoesNotExist()
        compose.onNodeWithText("LZ/PZ").performClick()
        compose.onNodeWithText("LZ/PZ panel body").assertIsDisplayed()
    }

    @Test
    fun `the supporting pane is headed by its title, and its close button or Back puts it away`() {
        frame(withSupport = true)
        compose.onNodeWithText("Aircraft, analysis and the tools").assertIsDisplayed()
        compose.onNodeWithText("Aircraft, analysis and tools").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "LZ/PZ 1")).assertExists()
        compose.onNodeWithText("LZ/PZ panel body").assertIsDisplayed()                            // at 1280 dp both stand
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(listOf("close"), said)
        compose.onNodeWithText("Aircraft, analysis and the tools").assertDoesNotExist()
    }

    @Test
    fun `a route's plan stands over the LZ-PZ's, which stays composed beside it and comes back when the plan is put away`() {
        frame(withSupport = true, withRoute = true)
        compose.onNodeWithText("ROUTE 1 plan").assertIsDisplayed()
        compose.onNodeWithText("Aircraft, analysis and the tools").assertDoesNotExist()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(listOf("closeRoute"), said)
        compose.onNodeWithText("Aircraft, analysis and the tools").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-xhdpi")
    fun `where both panes do not fit the one opened last stands, a panel picked brings the list back, and the pane asked for again comes forward`() {
        frame(withSupport = true)
        compose.onNodeWithText("Aircraft, analysis and the tools").assertIsDisplayed()           // opened with it: it stands
        compose.onNodeWithText("LZ/PZ panel body").assertDoesNotExist()
        compose.onNodeWithText("LZ/PZ").performClick()
        compose.onNodeWithText("LZ/PZ panel body").assertIsDisplayed()
        compose.onNodeWithText("Aircraft, analysis and the tools").assertDoesNotExist()          // waiting, composed
        compose.activity.onBackPressedDispatcher.onBackPressed()                                // Back does not take away a pane out of sight
        compose.waitForIdle()
        assertEquals(emptyList<String>(), said)
        askAgain()                                                                               // its Plan button, or the held bar's options
        compose.waitForIdle()
        compose.onNodeWithText("Aircraft, analysis and the tools").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h480dp-xhdpi")
    fun `on a short window the rail scrolls, so the Library and the account are never pushed off`() {
        frame()
        compose.onNodeWithText("Library").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Account").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the map has no sheet to stand on, so its readout stands at its foot`() {
        frame()
        compose.waitForIdle()
        val frame = requireNotNull(mapFrame)
        val height = compose.onNodeWithText("The map").fetchSemanticsNode().layoutInfo.parentInfo!!.height.toFloat()
        assertEquals(height, frame.controlsBottom()!!, 1f)
        assertEquals(0.dp, frame.padding.bottom)
    }

    @Test
    fun `what the frames hold moves between them, not built again, when the window crosses the line`() {
        var tablet by mutableStateOf(true)
        val made = mutableListOf<Any>()
        var disposed = 0
        compose.setContent {
            // As MapHome holds the map and the panels: movable content, placed by whichever frame shows.
            val map = remember {
                movableContentOf { frame: MapFrame ->
                    remember { Any().also { made += it } }
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Box(Modifier.fillMaxSize()) { Text("The map ${frame.padding.top}") }
                }
            }
            val panelBody = remember { movableContentOf { remember { Any().also { made += it } }; Text("LZ/PZ panel body") } }
            val sheet = rememberPanelSheetState(SheetDetent.Lowered)
            val snackbars = remember { SnackbarHostState() }
            EzpzTheme(ThemeMode.Dark) {
                if (tablet) {
                    TabletFrame(Panel.entries, Panel.Lz, PanelCounts(), {}, snackbars, TabletRail({}, {}, {}), panel = { if (it == Panel.Lz) panelBody() }, mapScreen = map)
                } else {
                    PhoneFrame(Panel.entries, Panel.Lz, PanelCounts(), {}, sheet, snackbars, panel = { if (it == Panel.Lz) panelBody() }, mapScreen = map)
                }
            }
        }
        compose.waitForIdle()
        tablet = false
        compose.waitForIdle()
        tablet = true
        compose.waitForIdle()
        assertEquals(2, made.size)                                                               // the map and the panel, each made once
        assertEquals(0, disposed)
    }

    @Test fun tablet() = shot("tablet-frame", withSupport = true)

    @Test fun tabletNight() = shot("tablet-frame-night", ThemeMode.Night, withSupport = true)

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, withSupport: Boolean) {
        frame(mode, withSupport)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }
}
