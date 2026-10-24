package app.ezpztac.brief

import app.ezpztac.brief.SandtableSteps.Box
import app.ezpztac.brief.SandtableSteps.Insets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The web's sandtableSteps.test.js, case for case: where a figure is fitted, finding a step, the overview and the colours; then what the port reads loosely. */
class SandtableStepsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // The map's box on the page, and what lies over it, as the sandtable measures them.
    private val map = Box(left = 300.0, top = 56.0, right = 1440.0, bottom = 900.0)
    private val card = Box(left = 480.0, top = 500.0, right = 1260.0, bottom = 884.0)            // along the bottom, in the middle
    private val constants = Box(left = 1158.0, top = 68.0, right = 1428.0, bottom = 260.0)        // the top right corner
    private val margins = Insets(16.0, 16.0, 16.0, 16.0)

    @Test
    fun `keeps a margin from every edge when nothing lies over the map`() {
        assertEquals(margins, SandtableSteps.fitInsets(map, emptyList()))
        assertEquals(margins, SandtableSteps.fitInsets(map, listOf(null, null)))
    }

    @Test
    fun `lifts the figure above the script card`() {
        assertEquals(Insets(top = 16.0, right = 16.0, bottom = 900.0 - 500 + 16, left = 16.0), SandtableSteps.fitInsets(map, listOf(card)))
    }

    @Test
    fun `keeps clear of the constants box from the side once the card has the bottom, never squeezing the height twice`() {
        val insets = SandtableSteps.fitInsets(map, listOf(constants, card))
        assertEquals(416.0, insets.bottom)
        assertEquals(1440.0 - 1158 + 16, insets.right)
        assertEquals(16.0, insets.top)
    }

    @Test
    fun `takes the corner box from the top when that leaves more map`() {
        val wide = Box(300.0, 56.0, 2400.0, 900.0)
        val insets = SandtableSteps.fitInsets(wide, listOf(Box(2100.0, 68.0, 2388.0, 140.0)))
        assertEquals(140.0 - 56 + 16, insets.top)
        assertEquals(16.0, insets.right)
    }

    @Test
    fun `ignores what does not lie over the map, or lies only where the figure is already kept from`() {
        assertEquals(margins, SandtableSteps.fitInsets(map, listOf(Box(0.0, 56.0, 290.0, 900.0))))
        val under = Box(600.0, 700.0, 900.0, 880.0)                                                  // inside the band the card already keeps clear
        assertEquals(SandtableSteps.fitInsets(map, listOf(card)), SandtableSteps.fitInsets(map, listOf(card, under)))
    }

    @Test
    fun `keeps steps laid over the map (a narrow window) to the left`() {
        val narrow = Box(0.0, 56.0, 800.0, 860.0)
        val steps = Box(0.0, 56.0, 300.0, 860.0)
        assertEquals(316.0, SandtableSteps.fitInsets(narrow, listOf(steps)).left)
    }

    @Test
    fun `always leaves some map, however much lies over it`() {
        val insets = SandtableSteps.fitInsets(map, listOf(Box(300.0, 120.0, 1440.0, 900.0)))
        assertTrue(844 - insets.top - insets.bottom >= 160, insets.toString())
        val tiny = SandtableSteps.fitInsets(Box(0.0, 0.0, 100.0, 100.0), listOf(Box(0.0, 10.0, 100.0, 100.0)))
        assertTrue(listOf(tiny.top, tiny.right, tiny.bottom, tiny.left).all { it >= 0 }, tiny.toString())
    }

    @Test
    fun `gives the margins alone for a map with no size (before it is laid out)`() {
        assertEquals(margins, SandtableSteps.fitInsets(Box(0.0, 0.0, 0.0, 0.0), listOf(card)))
        assertEquals(margins, SandtableSteps.fitInsets(null, listOf(card)))
    }

    @Test
    fun `finds a step by its id wherever it now is`() {
        val model = json("""{"steps":[{"id":"1"},{"id":"2"},{"id":"3"}]}""")
        assertEquals(2, SandtableSteps.indexOfStep(model, "3", 0))
    }

    @Test
    fun `stays at the same place in the list when the step has gone`() {
        val model = json("""{"steps":[{"id":"1"},{"id":"2"},{"id":"3"}]}""")
        assertEquals(1, SandtableSteps.indexOfStep(model, "gone", 1))
        assertEquals(2, SandtableSteps.indexOfStep(model, "gone", 9))
        assertEquals(0, SandtableSteps.indexOfStep(model, null as String?, 0))
        assertEquals(0, SandtableSteps.indexOfStep(model, "gone", -4))
    }

    @Test
    fun `finds nothing in a brief with no steps`() {
        assertEquals(-1, SandtableSteps.indexOfStep(json("""{"steps":[]}"""), "1", 0))
        assertEquals(-1, SandtableSteps.indexOfStep(null, "1", 0))
    }

    @Test
    fun `an id is found by JavaScript's equality, so text is never a number`() {
        val model = json("""{"steps":[{"id":1},{"id":"1"}]}""")
        assertEquals(1, SandtableSteps.indexOfStep(model, "1", 0))
        assertEquals(0, SandtableSteps.indexOfStep(model, JsonPrimitive(1.0), 1))
    }

    @Test
    fun `the overview is the first overview figure's area, else every figure's area together`() {
        assertEquals(
            json("[[0,0],[5,5]]"),
            SandtableSteps.overviewBounds(json("""{"steps":[{"figure":{"kind":"route","bounds":[[1,1],[2,2]]}},{"figure":{"kind":"overview","bounds":[[0,0],[5,5]]}}]}""")),
        )
        assertEquals(
            json("[[1,-1],[4,2]]"),
            SandtableSteps.overviewBounds(json("""{"steps":[{"figure":{"kind":"route","bounds":[[1,1],[2,2]]}},{"figure":null},{"figure":{"kind":"stop","bounds":[[3,-1],[4,1.5]]}}]}""")),
        )
        assertEquals(JsonNull, SandtableSteps.overviewBounds(json("""{"steps":[{"figure":{"kind":"overview","bounds":null}}]}""")))
    }

    @Test
    fun `an area that is not two pairs of numbers is no area`() {
        listOf("[[1,1],[2]]", "[[1,1],[2,\"2\"]]", "[[1,1],[2,2],[3,3]]", "{}", "\"[[1,1],[2,2]]\"").forEach { bad ->
            assertEquals(JsonNull, SandtableSteps.overviewBounds(json("""{"steps":[{"figure":{"kind":"overview","bounds":$bad}}]}""")), bad)
            assertEquals(null, SandtableSteps.boundsOf(json(bad)), bad)
        }
        assertEquals(SandtableSteps.Bounds(1.0, 2.0, 3.0, 4.0), SandtableSteps.boundsOf(json("[[1,2],[3,4]]")))
    }

    @Test
    fun `the colours are the PDF's, a role the figures do not know drawn as an LZ`() {
        assertEquals("#6fe3a5", SandtableSteps.roleColour("PZ"))
        assertEquals(SandtableSteps.Colours.ROLES["LZ"], SandtableSteps.roleColour("nope"))
        assertEquals(SandtableSteps.Colours.ROLES["LZ"], SandtableSteps.roleColour(null as String?))
        assertEquals(SandtableSteps.Colours.ROLES["LZ"], SandtableSteps.roleColour(JsonNull))
        assertEquals("#f0b429", SandtableSteps.roleColour(JsonPrimitive("FARP")))
    }

    // -- What the port reads, as the web reads it --------------------------------------------------------------------------------------------------------------

    @Test
    fun `names a step for what it is about only while that is still in the flow`() {
        val model = json(
            """{"steps":[
              {"id":"a","number":"4.e","title":"Landing sequence · LZ HAWK","who":"Flight lead","ref":"lz:hawk"},
              {"id":"b","number":"4.e","title":"Landing sequence · LZ GONE","who":"Flight lead","ref":"lz:gone"},
              {"id":"c","number":"4.c","title":"Enroute sequence","who":"Flight lead","ref":"route:s/r"}],
             "flow":[{"ref":"lz:hawk","kind":"stop","name":"LZ HAWK","arrive":"12:25:54"},{"ref":"lz:gone","gone":true,"name":"LZ GONE"},
                     {"ref":"route:s/r","kind":"route","name":"ALDERAAN","start":null}]}""",
        )
        val listed = SandtableSteps.listed(model)
        assertEquals(SandtableSteps.Step("a", "Step 1 of 3", "4.e", "Landing sequence", "LZ HAWK", "Flight lead", "12:25:54"), listed[0])
        // Gone from the flow: the step keeps its whole title and has no time.
        assertEquals(SandtableSteps.Step("b", "Step 2 of 3", "4.e", "Landing sequence · LZ GONE", "Landing sequence · LZ GONE", "Flight lead", null), listed[1])
        // Its title does not end with the route's name: the section is the whole title, and the title the route's name.
        assertEquals(SandtableSteps.Step("c", "Step 3 of 3", "4.c", "Enroute sequence", "ALDERAAN", "Flight lead", null), listed[2])
        // A step with no `who` has none in its JSON, as JSON.stringify leaves an undefined out.
        val bare = SandtableSteps.steps(json("""{"steps":[{"id":"x","title":"T"}]}"""))[0]
        assertEquals(json("""{"id":"x","position":"Step 1 of 1","section":"T","title":"T","time":null}"""), bare)
    }

    @Test
    fun `reads only a geometry's own refs`() {
        val model = json(
            """{"steps":[{"id":"1","figure":{"kind":"overview","bounds":[[0,0],[1,1]],"routes":["constructor","route:a"],"stops":[]}}],
                "geometry":{"routes":{"route:a":{"name":"A"}},"stops":{"lz:b":{"name":"B"}}}}""",
        )
        val look = SandtableSteps.look(model, 0)!!
        assertEquals(listOf("route:a"), look.routes.map { it.ref })
        // A figure that names no LZ/PZ shows every one the brief has, as the PDF's does.
        assertEquals(listOf("lz:b"), look.stops.map { it.ref })
    }
}
