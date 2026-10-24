package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ezpztac.data.PlaceTool
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The LZ/PZ tools on the map's edge (the owner's request of 2026-10-22): which are offered when, what each asks for, and how much of the rail fits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class LzToolRailTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()

    private fun tools(g: GraphicsUiState, b: BoundaryUiState, placing: PlaceTool? = null) = railTools(
        g, b, placing, draw = { log += "draw" }, place = { log += "place $it" }, done = { log += "done" }, unit = { log += "unit" },
    )

    private val analysed = GraphicsUiState(listed = true, canEdit = true)
    private val targeted = BoundaryUiState(canStart = true)

    // -- Which are offered ---------------------------------------------------------------------------------------------------

    @Test
    fun `no LZ-PZ to work on, or a read-only pack's, has no rail at all`() {
        assertNull(tools(GraphicsUiState(), BoundaryUiState()))
        val locked = "OP DK is finished: nothing in it can be changed."
        assertNull(tools(GraphicsUiState(listed = true, locked = locked), BoundaryUiState(locked = locked)))
    }

    @Test
    fun `an LZ-PZ not yet analysed offers its boundary, and the rest off until it is`() {
        val rail = tools(GraphicsUiState(), targeted)!!
        assertEquals(listOf("Draw LZ", "Helicopter", "PZ marker", "Sector of fire", "Unit", "Left go-around", "Right go-around"), rail.map { it.label })
        assertEquals(listOf(true, false, false, false, false, false, false), rail.map { it.enabled })
        assertTrue(rail.none { it.on })
    }

    @Test
    fun `an analysed LZ-PZ offers every tool, each asking for its own`() {
        val rail = tools(analysed, targeted)!!
        assertTrue(rail.all { it.enabled })
        rail.forEach { it.onClick() }
        assertEquals(
            listOf("draw", "place HELICOPTER", "place PZ_MARKER", "place SECTOR_OF_FIRE", "unit", "place GO_AROUND_LEFT", "place GO_AROUND_RIGHT"),
            log,
        )
    }

    @Test
    fun `the tool whose mode is on is marked, and tapped again finishes it, keeping what it placed`() {
        val rail = tools(analysed, targeted, placing = PlaceTool.PZ_MARKER)!!
        assertEquals(listOf("PZ marker"), rail.filter { it.on }.map { it.label })
        rail.single { it.label == "PZ marker" }.onClick()
        rail.single { it.label == "Helicopter" }.onClick()                                     // another tool: its own mode, as the grid's tile does
        assertEquals(listOf("done", "place HELICOPTER"), log)
    }

    @Test
    fun `what is off says why, and the tool on says a tap finishes it`() {
        val before = tools(GraphicsUiState(), targeted)!!
        assertEquals("Place helicopters. Analyze the LZ/PZ first", before[1].description)
        assertEquals("Add a unit at the crosshair. Analyze the LZ/PZ first", before[4].description)
        val placing = tools(analysed, targeted, placing = PlaceTool.HELICOPTER)!!
        assertEquals("Placing helicopters. Tap to finish", placing[1].description)
        assertEquals("Place PZ markers", placing[2].description)
        val busy = tools(analysed, BoundaryUiState(canStart = false, cannotStart = "Wait for the analysis to finish, or stop it, before drawing a boundary."))!!
        assertEquals("Draw the LZ/PZ boundary. Wait for the analysis to finish, or stop it, before drawing a boundary.", busy[0].description)
        assertEquals("Drawing the LZ/PZ boundary", tools(GraphicsUiState(), BoundaryUiState(drawing = true))!![0].description)
    }

    @Test
    fun `while a boundary is drawn its tool is marked and off, and the LZ-PZ has no analysis to place on`() {
        val rail = tools(GraphicsUiState(), BoundaryUiState(drawing = true, points = 2))!!
        val draw = rail.first()
        assertTrue(draw.on)
        assertTrue(!draw.enabled)
        assertTrue(rail.drop(1).none { it.enabled })
    }

    // -- The rail ------------------------------------------------------------------------------------------------------------

    private fun show(height: Dp, rail: List<RailTool>) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) { Box(Modifier.height(height)) { LzToolRail(rail) } }
    }

    @Test
    fun `with room for them all the tools stand in a column, each a button named for what it does`() {
        show(railHeight(7), tools(analysed, targeted, placing = PlaceTool.SECTOR_OF_FIRE)!!)
        compose.onNodeWithContentDescription("Place helicopters").assertIsEnabled().assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Placing sectors of fire. Tap to finish").assertIsSelected()
        compose.onNodeWithContentDescription("Add a unit at the crosshair").performClick()
        compose.onNodeWithContentDescription("Add to the LZ/PZ").assertDoesNotExist()
        assertEquals(listOf("place HELICOPTER", "unit"), log)
    }

    @Test
    fun `a tool that cannot be used is there, and off`() {
        show(railHeight(7), tools(GraphicsUiState(), targeted)!!)
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").assertIsEnabled()
        compose.onNodeWithContentDescription("Place helicopters. Analyze the LZ/PZ first").assertIsNotEnabled()
    }

    @Test
    fun `short of room for all, as many as fit stand, the last place a More button that lists the rest`() {
        show(railHeight(7) - 1.dp, tools(analysed, targeted)!!)
        listOf("Draw the LZ/PZ boundary", "Place helicopters", "Place PZ markers", "Place sectors of fire", "Add a unit at the crosshair").forEach {
            compose.onNodeWithContentDescription(it).assertIsEnabled()
        }
        assertEquals(0, compose.onAllNodesWithContentDescription("Place left go-arounds").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Add to the LZ/PZ").assertDoesNotExist()
        compose.onNodeWithContentDescription("More LZ/PZ tools").performClick()
        compose.onNodeWithText("Left go-around").assertIsEnabled()
        compose.onNodeWithText("Right go-around").performClick()
        compose.onNodeWithText("Right go-around").assertDoesNotExist()                          // the list goes once a tool is chosen
        assertEquals(listOf("place GO_AROUND_RIGHT"), log)
    }

    @Test
    fun `the tool whose mode is on is never left in the list, it takes the last place shown`() {
        val rail = tools(analysed, targeted, placing = PlaceTool.GO_AROUND_RIGHT)!!
        val (shown, rest) = railSplit(rail, 3)
        assertEquals(listOf("Draw LZ", "Helicopter", "Right go-around"), shown.map { it.label })
        assertEquals(listOf("PZ marker", "Sector of fire", "Unit", "Left go-around"), rest.map { it.label })
        assertEquals("one already shown stays where it is", listOf("Draw LZ", "Helicopter", "PZ marker"), railSplit(tools(analysed, targeted, PlaceTool.HELICOPTER)!!, 3).first.map { it.label })
        assertEquals(emptyList<String>(), railSplit(rail, 0).first.map { it.label })
        assertEquals(listOf(7, 6, 1, 0), listOf(railFits(railHeight(7)), railFits(railHeight(7) - 1.dp), railFits(48.dp), railFits(47.dp)))
    }

    @Test
    fun `with room for one button, it lists every tool, the one on ticked, and choosing it finishes its mode`() {
        show(60.dp, tools(analysed, targeted, placing = PlaceTool.HELICOPTER)!!)
        compose.onNodeWithContentDescription("Add to the LZ/PZ").performClick()
        compose.onNodeWithText("Helicopter").assertIsSelected().performClick()
        assertEquals(listOf("done"), log)
    }

    @Test
    fun `in the list a tool that cannot be used is off`() {
        show(60.dp, tools(GraphicsUiState(), targeted)!!)
        compose.onNodeWithContentDescription("Add to the LZ/PZ").performClick()
        compose.onNodeWithText("Helicopter").assertIsNotEnabled()
        compose.onNodeWithText("Draw LZ").assertIsEnabled().performClick()
        assertEquals(listOf("draw"), log)
    }

    @Test
    fun `with no room even for one button there is nothing`() {
        show(40.dp, tools(analysed, targeted)!!)
        compose.onNodeWithContentDescription("Add to the LZ/PZ").assertDoesNotExist()
        compose.onNodeWithContentDescription("Place helicopters").assertDoesNotExist()
    }

    // -- The rail over the view models' answers ---------------------------------------------------------------------------------------------------------

    private val said = mutableListOf<String>()

    private fun content(state: () -> BoundaryUiState, drawRefusal: String? = null, placeRefusal: String? = null, unitRefusal: String? = null) = compose.setContent {
        EzpzTheme(ThemeMode.Dark) {
            Box(Modifier.height(railHeight(7))) {
                LzToolRailContent(
                    analysed, state(), placing = null, onSaid = { said += it },
                    startDrawing = { log += "draw"; drawRefusal }, place = { log += "place $it"; placeRefusal }, done = { log += "done" },
                    addUnit = { log += "unit"; unitRefusal },
                )
            }
        }
    }

    @Test
    fun `drawing over an analysis asks first, and Draw starts it`() {
        content({ BoundaryUiState(canStart = true, clears = true, diagramId = "lz-1") })
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").performClick()
        compose.onNodeWithText("Draw a new boundary?").assertExists()
        assertEquals("nothing is cleared until it is answered", emptyList<String>(), log)
        compose.onNodeWithText("Draw").performClick()
        compose.onNodeWithText("Draw a new boundary?").assertDoesNotExist()
        assertEquals(listOf("draw"), log)
    }

    @Test
    fun `a refusal from any tool is said where the person is`() {
        content(
            { BoundaryUiState(canStart = true, diagramId = "lz-1") }, drawRefusal = "Finish or cancel the placement first.",
            placeRefusal = "Finish or cancel the route first.", unitRefusal = NO_CROSSHAIR,
        )
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").performClick()
        compose.onNodeWithContentDescription("Place helicopters").performClick()
        compose.onNodeWithContentDescription("Add a unit at the crosshair").performClick()
        assertEquals(listOf("Finish or cancel the placement first.", "Finish or cancel the route first.", NO_CROSSHAIR), said)
    }

    @Test
    fun `the question goes when drawing can no longer start there, and does not come back by itself`() {
        var state by mutableStateOf(BoundaryUiState(canStart = true, clears = true, diagramId = "lz-1"))
        content({ state })
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").performClick()
        compose.onNodeWithText("Draw a new boundary?").assertExists()
        state = state.copy(canStart = false, locked = "OP DK is finished: nothing in it can be changed.")      // the pack finished meanwhile
        compose.waitForIdle()
        compose.onNodeWithText("Draw a new boundary?").assertDoesNotExist()
        state = state.copy(canStart = true, locked = null)                                       // reopened
        compose.waitForIdle()
        compose.onNodeWithText("Draw a new boundary?").assertDoesNotExist()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `the question is the LZ-PZ's it was asked about, and another opened puts it away`() {
        var state by mutableStateOf(BoundaryUiState(canStart = true, clears = true, diagramId = "lz-1"))
        content({ state })
        compose.onNodeWithContentDescription("Draw the LZ/PZ boundary").performClick()
        state = state.copy(diagramId = "lz-2")
        compose.waitForIdle()
        compose.onNodeWithText("Draw a new boundary?").assertDoesNotExist()
        assertEquals("nothing drawn over the other one", emptyList<String>(), log)
    }
}

