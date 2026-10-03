package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The planning-graphics panel as a person meets it: what is offered, what a tap asks for, and what a tap must not ask for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class GraphicsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        val log = mutableListOf<String>()

        /** What the view model says to what was typed: nothing for most, and a complaint for "bad". */
        private fun problem(typed: String): String? = if (typed == "bad") "That is not one of those." else null

        val actions = GraphicsActions(
            place = { log += "place ${it.name}" },
            select = { log += "select ${it.collection}/${it.key}" },
            deselect = { log += "deselect" },
            nudge = { n, e -> log += "nudge $n $e" },
            moveToCrosshair = { log += "to crosshair" },
            moveToText = { log += "to [$it]" },
            rotateBy = { log += "rotate $it" },
            setRotation = { log += "heading $it" },
            changeReach = { log += "reach $it" },
            tipToCrosshair = { log += "tip" },
            setDirection = { log += "direction $it" },
            setDoghouseLabel = { log += "label [$it]"; problem(it) },
            setDoghouseTime = { log += "time [$it]"; problem(it) },
            setDoghouseDistance = { log += "distance [$it]"; problem(it) },
            setDoghouseAirspeed = { log += "airspeed [$it]"; problem(it) },
            delete = { log += "delete" },
            undo = { log += "undo" },
            redo = { log += "redo" },
            dismissError = { log += "dismiss" },
        )
    }

    private val heli = GraphicRef("helicopters", "1")
    private val pz = GraphicRef("pzMarkers", "pz-2")
    private val sector = GraphicRef("sectorsOfFire", "sec-3")
    private val goAround = GraphicRef("goArounds", "ga-4")

    private fun row(ref: GraphicRef, kind: GraphicKind, title: String, detail: String?, selected: Boolean = false, warning: Boolean = false) =
        GraphicRowUi(ref, kind, title, "16S GD 66993 52949", detail, warning, selected)

    private val helicopterRow = row(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "heading 90°")

    private fun held(ref: GraphicRef, kind: GraphicKind, title: String, rotation: Double? = null, reachFt: Long? = null, direction: String? = null) =
        InspectorUi(ref, kind, title, "16S GD 66993 52949", "34.78382, -84.08219", rotation, reachFt, direction)

    private fun show(state: GraphicsUiState, grid: String? = "16S GD 66993 52949", recorder: Recorder = Recorder()): Recorder {
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { Box(Modifier.verticalScroll(rememberScrollState())) { GraphicsContent(state, grid, recorder.actions) } }
        }
        return recorder
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun tapDescribed(description: String) = compose.onNodeWithContentDescription(description).performScrollTo().performClick()

    private fun editable(vararg rows: GraphicRowUi, inspector: InspectorUi? = null, alerts: List<String> = emptyList(), undo: Int = 0, redo: Int = 0, error: String? = null) =
        GraphicsUiState(canEdit = true, rows = rows.toList(), inspector = inspector, alerts = alerts, undoDepth = undo, redoDepth = redo, error = error)

    // -- Before there is anything to place -----------------------------------------------------------------------------------

    @Test
    fun `until the diagram is analysed the panel says why nothing can be placed, and offers nothing`() {
        show(GraphicsUiState())
        compose.onNodeWithText("Analyze the landing zone first", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Place helicopter at the crosshair").assertDoesNotExist()
    }

    @Test
    fun `undo stays offered after a reset, so the work that was done can still be taken back`() {
        val r = show(GraphicsUiState(canEdit = false, undoDepth = 2))
        tap("Undo")
        assertEquals(listOf("undo"), r.log)
    }

    // -- Placing -------------------------------------------------------------------------------------------------------------

    @Test
    fun `each kind is placed with one tap, and the crosshair's grid is named`() {
        val r = show(editable())
        compose.onNodeWithText("Place at the crosshair · 16S GD 66993 52949").assertIsDisplayed()
        tapDescribed("Place helicopter at the crosshair")
        tapDescribed("Place pz marker at the crosshair")
        tapDescribed("Place sector of fire at the crosshair")
        tapDescribed("Place go-around at the crosshair")
        assertEquals(listOf("place HELICOPTER", "place PZ_MARKER", "place SECTOR_OF_FIRE", "place GO_AROUND"), r.log)
    }

    @Test
    fun `with no crosshair grid the label still reads`() {
        show(editable(), grid = null)
        compose.onNodeWithText("Place at the crosshair").assertIsDisplayed()
    }

    @Test
    fun `with nothing placed the panel says how to start`() {
        show(editable())
        compose.onNodeWithText("Nothing placed yet", substring = true).assertIsDisplayed()
    }

    // -- The list ------------------------------------------------------------------------------------------------------------

    @Test
    fun `each graphic is a line with its grid and what is worth knowing, and a tap holds it`() {
        val r = show(editable(helicopterRow, row(pz, GraphicKind.PZ_MARKER, "PZ marker", "reach 328 ft")))
        compose.onNodeWithText("Nothing placed yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("16S GD 66993 52949  ·  heading 90°", substring = true).assertIsDisplayed()
        compose.onNodeWithText("16S GD 66993 52949  ·  reach 328 ft", substring = true).assertIsDisplayed()
        tap("Helicopter · UH-60L")
        assertEquals(listOf("select helicopters/1"), r.log)
    }

    @Test
    fun `the held graphic's line says it is selected`() {
        show(editable(row(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "heading 90°", selected = true), row(pz, GraphicKind.PZ_MARKER, "PZ marker", null)))
        compose.onNode(hasText("Helicopter · UH-60L") and androidx.compose.ui.test.hasClickAction()).assertIsSelected()
    }

    @Test
    fun `an aircraft that is too close says so in words, not colour alone`() {
        show(editable(row(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "heading 0°", warning = true), alerts = listOf("Separation Alert (A–B): Rotor edges are only 12 ft apart.")))
        compose.onNodeWithText("TOO CLOSE").assertIsDisplayed()
        compose.onNodeWithText("Separation Alert (A–B): Rotor edges are only 12 ft apart.").assertIsDisplayed()
    }

    @Test
    fun `an aircraft that is clear has no warning`() {
        show(editable(helicopterRow))
        compose.onNodeWithText("TOO CLOSE").assertDoesNotExist()
    }

    // -- Undo and the error ---------------------------------------------------------------------------------------------------

    @Test
    fun `undo and redo are offered only when there is something to undo or redo`() {
        val r = show(editable(helicopterRow, undo = 1, redo = 0))
        compose.onNodeWithText("Undo").assertIsEnabled()
        compose.onNodeWithText("Redo").assertIsNotEnabled()
        tap("Undo")
        assertEquals(listOf("undo"), r.log)
    }

    @Test
    fun `redo is taken when it is there`() {
        val r = show(editable(helicopterRow, undo = 0, redo = 3))
        compose.onNodeWithText("Undo").assertIsNotEnabled()
        tap("Redo")
        assertEquals(listOf("redo"), r.log)
    }

    @Test
    fun `an error is shown with a way to dismiss it`() {
        val r = show(editable(error = "Move the map to where it should go first."))
        compose.onNodeWithText("Move the map to where it should go first.").assertIsDisplayed()
        tap("Dismiss")
        assertEquals(listOf("dismiss"), r.log)
    }

    // -- The inspector -------------------------------------------------------------------------------------------------------

    private fun aircraftHeld() = editable(
        row(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", "heading 90°", selected = true),
        inspector = held(heli, GraphicKind.HELICOPTER, "Helicopter · UH-60L", rotation = 90.0),
    )

    @Test
    fun `nothing held shows no controls for it`() {
        show(editable(helicopterRow))
        compose.onNodeWithText("Move", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Delete this helicopter").assertDoesNotExist()
    }

    @Test
    fun `the inspector names the graphic, its grid and its position`() {
        show(aircraftHeld())
        compose.onNodeWithText("Held").assertIsDisplayed()
        compose.onNodeWithText("34.78382, -84.08219").assertIsDisplayed()
    }

    @Test
    fun `an arrow moves the held graphic by the chosen step, in feet`() {
        val r = show(aircraftHeld())
        tapDescribed("Move north 50 feet")                                                 // 50 is the step it starts on
        tapDescribed("Move south 50 feet")
        tapDescribed("Move east 50 feet")
        tapDescribed("Move west 50 feet")
        assertEquals(listOf("nudge 50.0 0.0", "nudge -50.0 0.0", "nudge 0.0 50.0", "nudge 0.0 -50.0"), r.log)
    }

    @Test
    fun `a different step changes how far the arrows go, and the step in use is the one filled in`() {
        val r = show(aircraftHeld())
        tap("200 ft")
        tapDescribed("Move north 200 feet")
        tap("10 ft")
        tapDescribed("Move east 10 feet")
        assertEquals(listOf("nudge 200.0 0.0", "nudge 0.0 10.0"), r.log)
        compose.onNodeWithText("10 ft").assertIsSelected()
    }

    @Test
    fun `the held graphic can be brought to the crosshair`() {
        val r = show(aircraftHeld())
        tap("Put at the crosshair")
        assertEquals(listOf("to crosshair"), r.log)
    }

    @Test
    fun `a grid typed in moves it there, and the field empties once it was taken`() {
        val r = show(aircraftHeld())
        compose.onNode(hasSetTextAction() and hasText("Move to a grid")).performScrollTo().performTextInput("16S GD 66993 52949")
        tap("Go")
        assertEquals(listOf("to [16S GD 66993 52949]"), r.log)
        compose.onNode(hasSetTextAction() and hasText("Move to a grid")).assertTextEqualsEmpty()
    }

    @Test
    fun `nothing is sent for an empty grid`() {
        show(aircraftHeld())
        compose.onNodeWithText("Go").assertIsNotEnabled()
    }

    @Test
    fun `an aircraft is turned in small and large steps`() {
        val r = show(aircraftHeld())
        compose.onNodeWithText("Heading 90°").assertIsDisplayed()
        tapDescribed("Turn left 15 degrees")
        tapDescribed("Turn left 1 degrees")
        tapDescribed("Turn right 1 degrees")
        tapDescribed("Turn right 15 degrees")
        assertEquals(listOf("rotate -15.0", "rotate -1.0", "rotate 1.0", "rotate 15.0"), r.log)
    }

    @Test
    fun `a heading is typed in degrees, and what is not a number is refused in words`() {
        val r = show(aircraftHeld())
        val field = compose.onNode(hasSetTextAction() and hasText("Set heading (°)"))
        field.performScrollTo().performTextInput("abc")
        tap("Set")
        compose.onNodeWithText("Enter degrees, such as 270.").assertIsDisplayed()
        assertEquals(emptyList<String>(), r.log)

        field.performTextInput("270")                                                      // typing again clears the complaint
        compose.onNodeWithText("Enter degrees, such as 270.").assertDoesNotExist()
    }

    @Test
    fun `a typed heading is sent`() {
        val r = show(aircraftHeld())
        compose.onNode(hasSetTextAction() and hasText("Set heading (°)")).performScrollTo().performTextInput("270")
        tap("Set")
        assertEquals(listOf("heading 270.0"), r.log)
    }

    @Test
    fun `a PZ marker has its reach and its tip, and no pattern`() {
        val r = show(editable(inspector = held(pz, GraphicKind.PZ_MARKER, "PZ marker", rotation = 270.0, reachFt = 1_312)))
        compose.onNodeWithText("Reach 1,312 ft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pattern").assertDoesNotExist()
        tapDescribed("Lengthen the PZ by 25 feet")
        tapDescribed("Shorten the PZ by 100 feet")
        tap("Put the tip at the crosshair")
        assertEquals(listOf("reach 25.0", "reach -100.0", "tip"), r.log)
    }

    @Test
    fun `a go-around is pointed the other way with one tap, and the way it already goes does nothing`() {
        val r = show(editable(inspector = held(goAround, GraphicKind.GO_AROUND, "Go-around", rotation = 0.0, direction = "left")))
        compose.onNodeWithText("Left").assertIsSelected()
        tap("Left")
        assertEquals(emptyList<String>(), r.log)
        tap("Right")
        assertEquals(listOf("direction right"), r.log)
    }

    @Test
    fun `a sector has nothing that turns, reaches or points`() {
        show(editable(inspector = held(sector, GraphicKind.SECTOR_OF_FIRE, "Sector of fire")))
        compose.onNodeWithText("Put at the crosshair").assertIsDisplayed()
        compose.onNodeWithText("Pattern").assertDoesNotExist()
        compose.onNodeWithContentDescription("Turn left 15 degrees").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lengthen the PZ by 25 feet").assertDoesNotExist()
    }

    @Test
    fun `deleting names what is deleted, and done puts it down`() {
        val r = show(aircraftHeld())
        tap("Delete this helicopter")
        tap("Done")
        assertEquals(listOf("delete", "deselect"), r.log)
    }

    // -- A doghouse ----------------------------------------------------------------------------------------------------------

    private val doghouseRef = GraphicRef("doghouses", "d-sp1")

    private fun doghouseHeld(feeds: String? = "Sets the takeoff heading on the LZ card") = editable(
        row(doghouseRef, GraphicKind.DOGHOUSE, "Doghouse · [SP1]", "090° · 01+57 · 3.13 km · 60 kts", selected = true),
        inspector = held(doghouseRef, GraphicKind.DOGHOUSE, "Doghouse · [SP1]", rotation = 90.0)
            .copy(doghouse = DoghouseUi("[SP1]", "01+57", "3.13", "60", feeds)),
    )

    @Test
    fun `a doghouse is not offered to place, because the analysis makes the two and the web makes no more`() {
        show(editable())
        compose.onNodeWithContentDescription("Place doghouse at the crosshair").assertDoesNotExist()
        compose.onNodeWithText("Doghouse").assertDoesNotExist()
    }

    @Test
    fun `a doghouse is listed with what it says`() {
        val r = show(editable(row(doghouseRef, GraphicKind.DOGHOUSE, "Doghouse · [SP1]", "090° · 01+57 · 3.13 km · 60 kts")))
        compose.onNodeWithText("16S GD 66993 52949  ·  090° · 01+57 · 3.13 km · 60 kts", substring = true).assertIsDisplayed()
        tap("Doghouse · [SP1]")
        assertEquals(listOf("select doghouses/d-sp1"), r.log)
    }

    @Test
    fun `a held doghouse says which heading on the LZ card it sets, and turns like an aircraft`() {
        val r = show(doghouseHeld())
        compose.onNodeWithText("Sets the takeoff heading on the LZ card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Heading 90°").performScrollTo().assertIsDisplayed()
        tapDescribed("Turn right 15 degrees")
        assertEquals(listOf("rotate 15.0"), r.log)
    }

    @Test
    fun `a doghouse that sets neither heading says nothing about the card`() {
        show(doghouseHeld(feeds = null))
        compose.onNodeWithText("Sets the takeoff heading on the LZ card").assertDoesNotExist()
        compose.onNodeWithText("Doghouse").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each field of a doghouse is typed over and sent, and the field empties once it was taken`() {
        val r = show(doghouseHeld())
        fun enter(label: String, text: String, set: String) {
            compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextInput(text)
            tap(set)
        }
        enter("Label · [SP1]", "[SP2]", "Set label")
        enter("Time · 01+57", "3:20", "Set time")
        enter("Distance · 3.13 km", "12.5", "Set distance")
        enter("Airspeed · 60 kts", "55", "Set airspeed")
        assertEquals(listOf("label [[SP2]]", "time [3:20]", "distance [12.5]", "airspeed [55]"), r.log)
        compose.onNode(hasSetTextAction() and hasText("Time · 01+57")).assertTextEqualsEmpty()
    }

    @Test
    fun `what the field cannot take is said under it, and kept for the person to correct`() {
        val r = show(doghouseHeld())
        val field = compose.onNode(hasSetTextAction() and hasText("Time · 01+57"))
        field.performScrollTo().performTextInput("bad")
        tap("Set time")
        compose.onNodeWithText("That is not one of those.").assertIsDisplayed()
        assertEquals(listOf("time [bad]"), r.log)

        field.performTextInput("3")                                                          // typing again clears the complaint
        compose.onNodeWithText("That is not one of those.").assertDoesNotExist()
    }

    @Test
    fun `nothing is sent for an empty doghouse field`() {
        show(doghouseHeld())
        compose.onNodeWithText("Set time").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Set label").performScrollTo().assertIsNotEnabled()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEqualsEmpty() {
    val text = fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
    assertEquals("", text)
}
