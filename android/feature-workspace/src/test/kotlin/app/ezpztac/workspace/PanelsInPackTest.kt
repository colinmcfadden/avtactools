package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.DiagramStatus
import app.ezpztac.sync.SyncStatus
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

/** The LZ/PZ and Routes panels while a Mission Pack is open (P2b, the design's PackLive): the pack's items, no Save, where they are saved. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class PanelsInPackTest {
    @get:Rule
    val compose = createComposeRule()

    private val inPack = InPackUi("OP DK", "Saved to OP DK as you work. 4 members can see it.", "Sam B. is also here", readOnly = false)

    private val lzState = DiagramsUiState(
        current = activeLz(name = "LZ IBIS (OWEST)").copy(uuid = "pack:p:lz-1", chips = packLzChips(DiagramStatus.ANALYZED, false, null, false, false), madeUpName = false, inPack = inPack),
        pack = PackLzListUi(
            "OP DK", "OP DK · 3 items · live",
            listOf(
                DiagramRow("pack:p:lz-2", "LZ HAWK (ROCK)", DiagramStatus.ANALYZED, "16S GC 31204 58811", SyncStatus.SYNCED, null, false,
                    packLzChips(DiagramStatus.ANALYZED, false, "changed", true, false), presence = "Jess R. has it open"),
                DiagramRow("pack:p:lz-3", "LZ BLUEBIRD (TWMILE)", DiagramStatus.TARGETED, "16S GC 27390 54102", SyncStatus.SYNCED, null, false,
                    packLzChips(DiagramStatus.TARGETED, false, null, false, true)),
                DiagramRow("pack:p:lz-4", "LZ KESTREL", DiagramStatus.ANALYZED, "16S GC 25110 50023", SyncStatus.SYNCED, null, false,
                    packLzChips(DiagramStatus.ANALYZED, false, null, false, false) - SYNCED_IN_PACK),
            ),
            count = 4,
        ),
    )

    private fun show(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) = compose.setContent {
        EzpzTheme(mode) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
            }
        }
    }

    @Test
    fun `the LZ-PZ panel in a pack lists the pack's, has no Save, and says where its work is saved`() {
        val log = mutableListOf<String>()
        show { LzHomeContent(lzState, LzPanelActions(open = { log += "open $it" }, close = { log += "close $it" }, save = { log += "save" }), GRID) }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Saves to your Library").assertDoesNotExist()
        compose.onNodeWithText("Saved to OP DK as you work. 4 members can see it.").assertIsDisplayed()
        compose.onNodeWithText("Sam B. is also here").assertIsDisplayed()
        compose.onNodeWithText("In OP DK · 4").assertIsDisplayed()
        compose.onNodeWithText("Jess R. has it open").assertIsDisplayed()
        compose.onNodeWithText("Recent in Library").assertDoesNotExist()
        compose.onNodeWithText("New LZ/PZs you start while OP DK is open are added to it.").assertIsDisplayed()
        compose.onNodeWithText("LZ HAWK (ROCK)").performClick()
        compose.onNodeWithContentDescription("More actions for LZ IBIS (OWEST)").performClick()
        compose.onNodeWithText("It stays in the pack").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Close here").performClick()
        assertEquals(listOf("open pack:p:lz-2", "close pack:p:lz-1"), log)
    }

    @Test
    fun `an empty pack says what goes into it`() {
        show { LzHomeContent(DiagramsUiState(pack = PackLzListUi("OP DK", "OP DK · 0 items · live", emptyList())), LzPanelActions(), GRID) }
        compose.onNodeWithText("Nothing in OP DK yet. Tap New LZ/PZ at the crosshair, or long-press the map, and the new LZ/PZ goes into the pack.").assertIsDisplayed()
    }

    @Test
    fun `a pack's rows say how far along each is beside its grid, and what holds of it under them only when it is not just synced`() {
        show { LzHomeContent(lzState, LzPanelActions(), GRID) }
        compose.onNodeWithText("Target set").assertIsDisplayed()                                    // BLUEBIRD is not analysed yet: the row says so
        compose.onNodeWithText("Waiting to send").assertIsDisplayed()
        compose.onNodeWithText("Original changed").assertIsDisplayed()
        compose.onAllNodesWithText("Synced").assertCountEquals(1)                                  // the open card's, never a row's
        // The label counts the pack's, and stands above the card it counts.
        val label = compose.onNodeWithText("In OP DK · 4").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithText("LZ IBIS (OWEST)").getUnclippedBoundsInRoot()
        assertTrue(label.top < card.top)
    }

    @Test
    fun `a pack opening says so in place of its items, in both panels`() {
        val opening = PackLzListUi("the pack", "Opening…", emptyList(), count = 0, opening = "Opening the pack…")
        show {
            androidx.compose.foundation.layout.Column {
                LzHomeContent(DiagramsUiState(pack = opening), LzPanelActions(), GRID)
                RoutesHomeContent(RoutesUiState(pack = PackRoutesListUi("the pack", "Opening…", emptyList(), count = 0, opening = "Opening the pack…")), RoutesActions())
            }
        }
        compose.onAllNodesWithText("Opening the pack…").assertCountEquals(2)
        compose.onNodeWithText("Nothing in", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No routes in", substring = true).assertDoesNotExist()
        compose.onNodeWithText("In the pack", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the plan of a pack's LZ-PZ has no Save, and says where it is saved and who else is in it`() {
        show { LzPlanContent(lzState.current!!, GraphicsUiState(), BoundaryUiState(canStart = true), null, LzPanelActions(), GraphicsActions(), GRID) }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithText("Saves to your Library").assertDoesNotExist()
        compose.onNodeWithText("Saved to OP DK as you work. 4 members can see it.").assertIsDisplayed()
        compose.onNodeWithText("Sam B. is also here").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for LZ IBIS (OWEST)").performClick()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun `a pack's LZ-PZ takes the pack's own LZ-PZ n as a name, where the person's own refuses it`() {
        val renamed = mutableListOf<String>()
        val current = lzState.current!!.copy(name = "LZ/PZ 1")
        show { LzHomeContent(lzState.copy(current = current), LzPanelActions(rename = { _, name -> renamed += name }), GRID) }
        compose.onNodeWithContentDescription("Rename LZ/PZ 1").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("LZ/PZ 4")
        compose.onNodeWithText("Save name").performClick()
        assertEquals(listOf("LZ/PZ 4"), renamed)
    }

    @Test
    fun `in a pack the Library's conflicts, every route set and Import msnx are not offered`() {
        val conflicted = DiagramRow("lib-1", "LZ HAWK (from this device, 14:32)", DiagramStatus.ANALYZED, GRID, SyncStatus.SYNCED, "lib-0", false)
        val ownSet = RouteSetRow("lib-2", "NIGHT INFIL", 1, SyncStatus.SYNCED, "lib-3", false)
        show {
            androidx.compose.foundation.layout.Column {
                LzHomeContent(lzState.copy(rows = listOf(conflicted)), LzPanelActions(), GRID)
                RoutesHomeContent(routesState.copy(sets = listOf(ownSet)), RoutesActions())
                RoutesFooter(routesState, RoutesActions())
            }
        }
        compose.onNodeWithText("needs a choice", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Every route set").assertDoesNotExist()
        compose.onNodeWithText("Import .msnx").assertDoesNotExist()
        compose.onNodeWithText("Sketch a route").assertExists()
    }

    @Test
    fun `outside a pack the foot still offers Import msnx`() {
        show { RoutesFooter(RoutesUiState(), RoutesActions()) }
        compose.onNodeWithText("Import .msnx").assertIsDisplayed()
    }

    private val routesState = RoutesUiState(
        open = OpenSetUi(
            "pack:p:rt-1", "OP DK INGRESS", listOf(RouteRowUi("r1", "RED 1", "#E8A25C", true, 6, 4, "12.3 nm · 8:15", false)), canUndo = true, canRedo = false,
            sync = null, conflictOf = null, chips = packRouteChips(false, null, false, true), inPack = inPack.copy(alsoHere = null),
        ),
        pack = PackRoutesListUi(
            "OP DK", "OP DK · 2 route sets · live",
            listOf(
                RouteSetRow(
                    "pack:p:rt-2", "OP DK EGRESS", 1, SyncStatus.SYNCED, null, false, pointCount = 5, chips = packRouteChips(false, "changed", true, false),
                    presence = "Sam B., Jess R. and Mike W. have it open",
                ),
            ),
            count = 2,
        ),
    )

    @Test
    fun `the Routes panel in a pack lists the pack's sets, has no Save, and says where they are saved`() {
        val log = mutableListOf<String>()
        show { RoutesHomeContent(routesState, RoutesActions(openSet = { log += "open $it" }, close = { log += "close $it" })) }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Saved to OP DK as you work. 4 members can see it.").assertIsDisplayed()
        compose.onNodeWithText("Waiting to send").assertIsDisplayed()
        compose.onNodeWithText("In OP DK · 2").assertIsDisplayed()
        compose.onNodeWithText("Sam B., Jess R. and Mike W. have it open").assertIsDisplayed()
        compose.onNodeWithText("OP DK EGRESS").performClick()
        compose.onNodeWithContentDescription("More actions for OP DK INGRESS").performClick()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Close here").performClick()
        assertEquals(listOf("open pack:p:rt-2", "close pack:p:rt-1"), log)
    }

    @Test fun lzInPack() = shot("lz-in-pack") { LzHomeContent(lzState, LzPanelActions(), GRID) }

    @Test fun lzInPackNight() = shot("lz-in-pack-night", ThemeMode.Night) { LzHomeContent(lzState, LzPanelActions(), GRID) }

    @Test fun routesInPack() = shot("routes-in-pack") { RoutesHomeContent(routesState, RoutesActions()) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun lzInPackNarrow() = shot("lz-in-pack-narrow") { LzHomeContent(lzState, LzPanelActions(), GRID) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 2.0f)
    fun lzInPackLargest() {
        shot("lz-in-pack-narrow-2x") { LzHomeContent(lzState, LzPanelActions(), GRID) }
        // The grid is never cut: it is as wide as the row lets it be, on two lines.
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val grid = compose.onNodeWithText("16S GC 31204 58811", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(grid.right <= root.right)
        val state = compose.onNodeWithText("Original changed", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("two lines of grid: $grid against $state", grid.bottom - grid.top > state.bottom - state.top)
        // Where the grid and the state do not fit side by side, the state starts its own line, under the grid, with no separator before it.
        val bluebird = compose.onNodeWithText("16S GC 27390 54102", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val targetSet = compose.onNodeWithText("Target set", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(bluebird.left, targetSet.left)
        assertTrue(targetSet.top >= bluebird.bottom)
    }

    @Test
    fun `a grid may break only between its halves, and one that is not four groups is left as it is`() {
        assertEquals("16S\u00A0GC 31204\u00A058811", gridBreakingBetweenHalves("16S GC 31204 58811"))
        assertEquals("16SGC3120458811", gridBreakingBetweenHalves("16SGC3120458811"))
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun routesInPackNarrow() = shot("routes-in-pack-narrow") { RoutesHomeContent(routesState, RoutesActions()) }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 2.0f)
    fun routesInPackLargest() {
        shot("routes-in-pack-narrow-2x") { RoutesHomeContent(routesState, RoutesActions()) }
        compose.onNodeWithText("Sam B., Jess R. and Mike W. have it open").assertIsDisplayed()
        compose.onNodeWithText("1 route · 5 points").assertIsDisplayed()
        // Who has it open wraps rather than being cut (what it holds goes under its chip whole: DotJoined).
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Sam B., Jess R. and Mike W. have it open", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
        assertEquals(2, layouts.single().lineCount)
    }

    @Test fun lzPlanInPack() = shot("lz-plan-in-pack") { LzPlanContent(lzState.current!!, GraphicsUiState(), BoundaryUiState(canStart = true), null, LzPanelActions(), GraphicsActions(), GRID) }

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        show(mode, content)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }
}
