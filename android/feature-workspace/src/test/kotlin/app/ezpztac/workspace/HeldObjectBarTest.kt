package app.ezpztac.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
import app.ezpztac.planning.PlanDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a tap on the map holds, and the bar over the map that says so: its name, how to turn it, and a way to the rest of what can be done to it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HeldObjectBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = HeldObjectActions(rotateBy = { log += "turn $it" }, options = { log += "options" }, done = { log += "done" })

    private fun show(held: HeldObjectUi) = compose.setContent { EzpzTheme(ThemeMode.Dark) { HeldObjectBar(held, actions) } }

    private fun present(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // -- What is held ----------------------------------------------------------------------------------------------------

    private val inspector = InspectorUi(
        ref = GraphicRef("helicopters", "1"), kind = GraphicKind.HELICOPTER, title = "Helicopter 1", grid = "16S GD 66993 52949", latLon = "34.78, -84.08", rotation = 270.0,
        reachFt = null, direction = null,
    )
    private val threat = HeldThreatUi("t1", "SA-6", "SA-6 Gainful", "16S GD 66000 52000", "34.77, -84.07", "", "", emptyList())

    private fun route(point: PlanPointUi? = null, shaping: ShapingPointUi? = null) = RouteDetailUi(
        routeId = "r1", name = "ROUTE 1", aircraft = "UH-60L", plan = PlanDraft.of(app.ezpztac.model.RoutePlan()), points = listOfNotNull(point), shapingPoints = 0,
        heldShaping = shaping, totals = null, warnings = emptyList(), hasElevations = false,
    )

    private fun point(held: Boolean, name: String = ".LZ") = PlanPointUi(
        id = "p1", name = name, ptType = "target", first = false, values = app.ezpztac.planning.PointDraft.of(app.ezpztac.model.RoutePlan(), "p1"), clock = "--:--:--", hasClock = false,
        facts = "", elapsed = "", held = held, grid = "16S GD 66900 52900",
    )

    @Test
    fun `an LZ PZ also open, held from the map, is opened or hidden from its bar, and comes after everything else held`() {
        val other = AlsoOpenUi("lz-2", "LZ HAWK", "16S GD 66993 52949", "Also open")
        val held = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(), alsoOpen = other)!!
        assertEquals("LZ HAWK", held.title)
        assertEquals("Also open · 16S\u00A0GD 66993\u00A052949", held.subtitle)                     // a grid breaks only between its halves
        assertEquals("a graphic held first", "Helicopter 1", heldObjectOf(GraphicsUiState(inspector = inspector), ThreatsUiState(), RoutesUiState(), alsoOpen = other)!!.title)
        val asked = mutableListOf<String>()
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { HeldObjectBar(held, HeldObjectActions(openOther = { asked += "open $it" }, hideOther = { asked += "hide $it" })) }
        }
        compose.onNodeWithContentDescription("Open LZ HAWK").performClick()
        compose.onNodeWithContentDescription("Hide LZ HAWK on the map").performClick()
        assertEquals(listOf("open lz-2", "hide lz-2"), asked)
        assertTrue(present("Drawn faded while another LZ/PZ is open."))
        assertFalse("nothing to move it with: it is not the one worked on", present("Press and hold to move it."))
        compose.onNodeWithText("More options").assertDoesNotExist()
    }

    @Test
    fun `something held of a finished pack can be looked at, not turned, moved or deleted, and the bar says why`() {
        val reason = "OP DK is finished: nothing in it can be changed."
        val graphic = heldObjectOf(GraphicsUiState(locked = reason, inspector = inspector), ThreatsUiState(), RoutesUiState())!!
        assertEquals(reason, graphic.locked)
        show(graphic)
        assertTrue(present("Heading 270°"))
        assertTrue(present(reason))
        assertFalse(present("Press and hold to move it."))
        compose.onNodeWithContentDescription("Turn left 15 degrees").assertDoesNotExist()
        val corner = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(), HeldCornerUi("Boundary corner 2 of 4", "16S GD 66000 52000", canDelete = true, locked = reason))!!
        assertFalse(corner.deleteEnabled)
        assertNull("the bar says why once, in place of the invitation to move it", corner.deleteNote)
        assertEquals(reason, corner.locked)
        // A threat is the person's own: never locked.
        assertNull(heldObjectOf(GraphicsUiState(locked = reason), ThreatsUiState(held = threat), RoutesUiState())!!.locked)
    }

    @Test
    fun `a held boundary corner offers delete, and says why when it cannot`() {
        val ok = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(), HeldCornerUi("Boundary corner 2 of 4", "16S GD 66000 52000", canDelete = true))!!
        assertEquals("Delete corner", ok.deleteLabel)
        assertEquals(true, ok.deleteEnabled)
        assertNull(ok.deleteNote)
        val no = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(), HeldCornerUi("Boundary corner 1 of 3", "16S GD 66000 52000", canDelete = false))!!
        assertEquals(false, no.deleteEnabled)
        assertEquals("A boundary needs at least 3 corners.", no.deleteNote)
    }

    @Test
    fun `a threat outranks a corner and a bar for something else has no delete`() {
        val corner = HeldCornerUi("Boundary corner 1 of 4", "g", canDelete = true)
        assertNull(heldObjectOf(GraphicsUiState(), ThreatsUiState(held = threat), RoutesUiState(), corner)!!.deleteLabel)
    }

    @Test
    fun `nothing held, no bar`() {
        assertNull(heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState()))
    }

    @Test
    fun `a held graphic is named, placed and turns`() {
        val held = heldObjectOf(GraphicsUiState(inspector = inspector), ThreatsUiState(), RoutesUiState())!!
        assertEquals(HeldObjectUi("Helicopter 1", "16S GD 66993 52949", 270.0), held)
    }

    @Test
    fun `a graphic that does not turn has no heading`() {
        val sector = inspector.copy(kind = GraphicKind.SECTOR_OF_FIRE, title = "Sector of fire", rotation = null)
        assertNull(heldObjectOf(GraphicsUiState(inspector = sector), ThreatsUiState(), RoutesUiState())!!.heading)
    }

    @Test
    fun `a held threat is named and placed, and does not turn`() {
        assertEquals(HeldObjectUi("SA-6", "16S GD 66000 52000", null), heldObjectOf(GraphicsUiState(), ThreatsUiState(held = threat), RoutesUiState()))
    }

    @Test
    fun `a held point of a route says which route it is on`() {
        val state = RoutesUiState(detail = route(point = point(held = true)))
        assertEquals(HeldObjectUi(".LZ", "ROUTE 1 · 16S GD 66900 52900", null), heldObjectOf(GraphicsUiState(), ThreatsUiState(), state))
        assertNull(heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(detail = route(point = point(held = false)))))      // the route is held, no point of it
    }

    @Test
    fun `a held shaping point, and a point with no name`() {
        val shaping = RoutesUiState(detail = route(shaping = ShapingPointUi("s1", held = true, grid = "16S GD 1 2")))
        assertEquals(HeldObjectUi("Shaping point", "ROUTE 1 · 16S GD 1 2", null), heldObjectOf(GraphicsUiState(), ThreatsUiState(), shaping))
        val unnamed = RoutesUiState(detail = route(point = point(held = true, name = "")))
        assertEquals("Route point", heldObjectOf(GraphicsUiState(), ThreatsUiState(), unnamed)!!.title)
    }

    @Test
    fun `a graphic is held over a threat, and a threat over a point, as a tap takes them`() {
        val both = heldObjectOf(GraphicsUiState(inspector = inspector), ThreatsUiState(held = threat), RoutesUiState(detail = route(point = point(true))))!!
        assertEquals("Helicopter 1", both.title)
        val threatAndPoint = heldObjectOf(GraphicsUiState(), ThreatsUiState(held = threat), RoutesUiState(detail = route(point = point(true))))!!
        assertEquals("SA-6", threatAndPoint.title)
    }

    // -- The bar ---------------------------------------------------------------------------------------------------------

    @Test
    fun `an aircraft's bar turns it by a degree or fifteen, either way, and says how to move it`() {
        show(HeldObjectUi("Helicopter 1", "16S GD 66993 52949", 270.0))
        compose.onNodeWithText("Helicopter 1").assertIsDisplayed()
        compose.onNodeWithText("Heading 270°").assertIsDisplayed()
        assertTrue(present("Press and hold to move it."))
        compose.onNodeWithContentDescription("Turn left 15 degrees").performClick()
        compose.onNodeWithContentDescription("Turn left 1 degrees").performClick()
        compose.onNodeWithContentDescription("Turn right 1 degrees").performClick()
        compose.onNodeWithContentDescription("Turn right 15 degrees").performClick()
        assertEquals(listOf("turn -15.0", "turn -1.0", "turn 1.0", "turn 15.0"), log)
    }

    @Test
    fun `something that does not turn has no turn buttons`() {
        show(HeldObjectUi("SA-6", "16S GD 66000 52000", null))
        assertTrue(compose.onAllNodesWithText("Heading", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescriptionSubstring("Turn").isEmpty())
    }

    @Test
    fun `more options opens the sheet and done puts it down`() {
        show(HeldObjectUi("Helicopter 1", null, 10.0))
        compose.onNodeWithText("More options").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("options", "done"), log)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionSubstring(text: String) =
        onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes()

    // -- A doghouse's own fields (the owner's request, 2026-10-21) ------------------------------------------------------

    private val doghouse = DoghouseUi(label = "[RP1]", time = "03+20", distanceKm = "3.1", airspeedKts = "60", feeds = "Sets the takeoff heading on the LZ card")
    private val doghouseHeld = HeldObjectUi(
        "Doghouse · [RP1]", "16S GD 66993 52949", heading = 105.0, doghouse = doghouse, graphic = GraphicOf("lz-1", GraphicRef("doghouses", "dh2")),
    )

    @Test
    fun `a held doghouse brings its fields to the bar, and other graphics none`() {
        val held = heldObjectOf(GraphicsUiState(inspector = inspector.copy(kind = GraphicKind.DOGHOUSE, doghouse = doghouse, diagramId = "lz-1")), ThreatsUiState(), RoutesUiState())!!
        assertEquals(doghouse, held.doghouse)
        assertEquals(GraphicOf("lz-1", GraphicRef("helicopters", "1")), held.graphic)
        assertNull(heldObjectOf(GraphicsUiState(inspector = inspector), ThreatsUiState(), RoutesUiState())!!.doghouse)
    }

    @Test
    fun `a doghouse's bar has its fields, each holding its value, and still its turn buttons`() {
        show(doghouseHeld)
        listOf("[RP1]", "105", "03+20", "3.1", "60").forEach { assertTrue("$it is in a field", present(it)) }
        assertTrue(present("Sets the takeoff heading on the LZ card"))
        assertFalse("its heading is a field, not a line of words", present("Heading 105°"))
        compose.onNodeWithContentDescription("Turn right 15 degrees").assertIsDisplayed()
        // Up beside Done, an icon: the bar is tall enough with its fields.
        compose.onNodeWithContentDescription("More options").assertIsDisplayed().performClick()
        assertEquals(listOf("options"), log)
    }

    @Test
    fun `what is typed is taken on Done, and a refusal is said with what was typed kept to be put right`() {
        val typed = mutableListOf<String>()
        val refusal = "Enter a time as minutes and seconds, such as 03+20."
        val bar = HeldObjectActions(
            setDoghouseTime = { typed += "time $it"; if (it == "soon") refusal else null },
            setHeading = { typed += "heading $it"; null },
            setDoghouseLabel = { typed += "label $it"; null },
        )
        compose.setContent { EzpzTheme(ThemeMode.Dark) { HeldObjectBar(doghouseHeld, bar) } }
        compose.onNodeWithText("03+20").performTextReplacement("soon")
        compose.onNodeWithText("soon").performImeAction()
        assertTrue(present(refusal))
        assertTrue("what was typed stays", present("soon"))
        compose.onNodeWithText("soon").performTextReplacement("04+00")
        compose.onNodeWithText("04+00").performImeAction()
        assertFalse("taken: the words go", present(refusal))
        compose.onNodeWithText("105").performTextReplacement("120")
        compose.onNodeWithText("120").performImeAction()
        compose.onNodeWithText("[RP1]").performTextReplacement("[RP2]")
        compose.onNodeWithText("[RP2]").performImeAction()
        assertEquals(listOf("time soon", "time 04+00", "heading 120", "label [RP2]"), typed)
    }

    @Test
    fun `a field shows a value changed under it, and another doghouse held shows its own`() {
        var shown by mutableStateOf(doghouseHeld)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { HeldObjectBar(shown, actions) } }
        shown = doghouseHeld.copy(doghouse = doghouse.copy(time = "05+00"))                     // an undo, or someone else's change in a pack
        compose.waitForIdle()
        assertTrue(present("05+00"))
        compose.onNodeWithText("[RP1]").performTextReplacement("XX")                             // typed, not taken
        shown = doghouseHeld.copy(
            title = "Doghouse · [SP1]", doghouse = doghouse.copy(label = "[SP1]"), graphic = GraphicOf("lz-1", GraphicRef("doghouses", "dh1")),
        )
        compose.waitForIdle()
        assertTrue(present("[SP1]"))
        assertFalse("what was typed for one is never shown in another's", present("XX"))
    }

    @Test
    fun `a doghouse of a finished pack says its values and offers no field`() {
        show(doghouseHeld.copy(locked = "OP DK is finished: nothing in it can be changed."))
        assertTrue(present("Label [RP1] · Time 03+20 · 3.1 km · 60 kts"))
        assertTrue(present("Heading 105°"))
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `a heading is typed in degrees or refused in words`() {
        var set: Double? = null
        assertEquals("Enter degrees, such as 270.", headingTyped("west") { set = it })
        assertNull(set)
        assertNull(headingTyped(" 270 ") { set = it })
        assertEquals(270.0, set!!, 0.0)
    }

    @Test
    fun `a field with focus follows a value changed under it until the person types, and never puts the old one back`() {
        val typed = mutableListOf<String>()
        var shown by mutableStateOf(doghouseHeld)
        compose.setContent { EzpzTheme(ThemeMode.Dark) { HeldObjectBar(shown, HeldObjectActions(setHeading = { typed += "heading $it"; null })) } }
        compose.onNodeWithText("105").performClick()                                             // in the field, nothing typed
        shown = doghouseHeld.copy(heading = 135.0)                                              // turned from the bar's own buttons
        compose.waitForIdle()
        assertTrue(present("135"))
        compose.onNodeWithText("135").performImeAction()
        assertTrue("nothing typed, nothing taken: the turn stands", typed.isEmpty())
    }

    @Test
    fun `a refusal goes once its field is left holding its value, and only its own`() {
        val refusal = "Enter a time as minutes and seconds, such as 03+20."
        compose.setContent { EzpzTheme(ThemeMode.Dark) { HeldObjectBar(doghouseHeld, HeldObjectActions(setDoghouseTime = { if (it == "soon") refusal else null })) } }
        compose.onNodeWithText("03+20").performTextReplacement("soon")
        compose.onNodeWithText("soon").performImeAction()
        assertTrue(present(refusal))
        compose.onNodeWithText("3.1").performClick()                                             // another field: the refusal is the time's
        compose.onNodeWithText("3.1").performImeAction()
        assertTrue(present(refusal))
        compose.onNodeWithText("03+20").performTextReplacement("03+20")
        compose.onNodeWithText("03+20").performImeAction()
        assertFalse(present(refusal))
    }
}
