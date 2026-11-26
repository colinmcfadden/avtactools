package app.ezpztac.data

import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.SetLines
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backstops, FLOTs and phase lines drawn, kept and changed ([ControlMeasures]): a backstop in the open LZ/PZ's graphics, a FLOT or phase line in a set of control
 * measures of its own (a route set's document with no routes, `CONTROL MEASURES` until renamed), as the web keeps them (AGENTS.md §4, *Control measures*).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlMeasuresTest {
    private class Memory : LastMeasures {
        var id: String? = null
        override fun id(): String? = id
        override fun remember(id: String?) { this.id = id }
    }

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val store = device.store as InMemorySyncStore
        val diagramRepo = DiagramRepository(device.repository, store, RecordingScheduler())
        val diagrams = DiagramSession(diagramRepo, scope.backgroundScope)
        val routeRepo = RouteRepository(device.repository, store, RecordingScheduler())
        val routes = RouteSession(routeRepo, scope.backgroundScope)
        val measuresSession = MeasuresSession(routeRepo, scope.backgroundScope)
        val mode = DrawingMode()
        val last = Memory()
        val measures = ControlMeasures(diagrams, routes, measuresSession, routeRepo, OpenPack.None, mode, last)

        suspend fun openAnalysed() {
            diagrams.open(diagramRepo.create(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), "LZ HAWK").id)
            diagrams.setQuietly { DiagramOps.completeAnalysis(it, null) }
        }

        fun draw(kind: MeasureKind, vararg at: LatLon) {
            assertNull(measures.start(kind))
            at.forEach { assertTrue(measures.addPoint(it)) }
        }

        val set get() = checkNotNull(measuresSession.active.value)
    }

    private val a = LatLon(34.79, -84.09)
    private val b = LatLon(34.79, -84.07)
    private val c = LatLon(34.80, -84.06)

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    @Test
    fun `a FLOT drawn with nothing open makes a set of control measures for it, kept in the Library and opened, the line held`() = runTest {
        val r = rig()
        r.draw(MeasureKind.FLOT, a, b)
        assertNull(r.measures.finish())
        val set = r.set
        assertEquals(SetLines.MEASURES_NAME, set.name)
        assertTrue("no routes: control measures, not a route set", set.routes.isEmpty())
        val flot = SetLines.of(set, SetLines.FLOTS).single()
        assertEquals(listOf(a, b), SetLines.places(flot))
        assertTrue(flot.getValue("id").jsonPrimitive.content.startsWith("flot-"))
        assertEquals(set.id, r.last.id)
        assertEquals(HeldMeasure.Line(set.id, SetLines.FLOTS, flot.getValue("id").jsonPrimitive.content), r.measures.held.value)
        assertNull("drawing is over", r.measures.draft.value)
        assertNull("and the map's taps are free again", r.mode.begin(DrawingMode.Kind.ROUTE))
        // What is kept is the web's document, opened as it would be on the web.
        val reopened = checkNotNull(r.routeRepo.open(set.id))
        assertTrue(SetLines.isMeasures(reopened))
    }

    @Test
    fun `a second line goes into the same set, and a phase line is offered the first name of the phonetic alphabet no line has`() = runTest {
        val r = rig()
        r.draw(MeasureKind.PHASE_LINE, a, b)
        assertEquals("ALPHA", r.measures.nextPhaseLineName())
        assertNull(r.measures.finish())
        val id = r.set.id
        r.draw(MeasureKind.PHASE_LINE, b, c)
        assertEquals("BRAVO", r.measures.nextPhaseLineName())
        assertNull(r.measures.finish("pl red"))
        assertEquals(id, r.set.id)
        assertEquals(listOf("ALPHA", "RED"), SetLines.of(r.set, SetLines.PHASE_LINES).map { it.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `a line of one point is not finished, and drawing goes on`() = runTest {
        val r = rig()
        r.draw(MeasureKind.FLOT, a)
        assertEquals(ControlMeasures.ADD_TWO, r.measures.finish())
        assertNotNull(r.measures.draft.value)
        assertNull(r.measuresSession.active.value)
        r.measures.undoPoint()
        assertEquals(0, r.measures.draft.value!!.points.size)
        r.measures.cancel()
        assertNull(r.measures.draft.value)
    }

    @Test
    fun `a backstop is drawn on an analysed LZ-PZ only, and goes into its graphics, held`() = runTest {
        val r = rig()
        assertEquals("Open an LZ/PZ first.", r.measures.refusal(MeasureKind.BACKSTOP))
        r.diagrams.open(r.diagramRepo.create(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), "LZ HAWK").id)
        assertEquals("Analyze the LZ/PZ first.", r.measures.start(MeasureKind.BACKSTOP))
        r.diagrams.setQuietly { DiagramOps.completeAnalysis(it, null) }
        r.draw(MeasureKind.BACKSTOP, a, b, c)
        assertNull(r.measures.finish())
        val diagram = checkNotNull(r.diagrams.active.value)
        val backstop = diagram.graphics.backstops.orEmpty().single() as JsonObject
        assertTrue(backstop.getValue("id").jsonPrimitive.content.startsWith("bs-"))
        assertEquals(3, backstop.getValue("points").jsonArray.size)
        assertFalse("no arrow until it is moved: it runs from the line's middle to the target", "arrow" in backstop)
        assertEquals(HeldMeasure.Backstop(diagram.id, backstop.getValue("id").jsonPrimitive.content), r.measures.held.value)
        assertNull("it is no control measure", r.measuresSession.active.value)
        // Deleting it is one step, undone as one.
        assertNull(r.measures.deleteHeld())
        assertTrue(r.diagrams.active.value!!.graphics.backstops.orEmpty().isEmpty())
        r.diagrams.undo()
        assertEquals(1, r.diagrams.active.value!!.graphics.backstops.orEmpty().size)
    }

    @Test
    fun `one line is drawn at a time, and none while a route is`() = runTest {
        val r = rig()
        r.openAnalysed()
        r.draw(MeasureKind.FLOT, a)
        assertEquals("Finish or cancel the FLOT first.", r.measures.refusal(MeasureKind.PHASE_LINE))
        assertNotNull("the map's taps are the FLOT's", r.mode.begin(DrawingMode.Kind.ROUTE))
        r.measures.cancel()
        assertNull(r.mode.begin(DrawingMode.Kind.ROUTE))
        assertNotNull(r.measures.start(MeasureKind.BACKSTOP))
    }

    @Test
    fun `a line is flipped, renamed and removed where it is kept, each a step, and a blank name is refused`() = runTest {
        val r = rig()
        r.draw(MeasureKind.FLOT, a, b)
        r.measures.finish()
        val flot = r.measures.held.value as HeldMeasure.Line
        r.draw(MeasureKind.PHASE_LINE, b, c)
        r.measures.finish()
        val pl = r.measures.held.value as HeldMeasure.Line

        assertNull(r.measures.flipLine(flot))
        assertTrue(SetLines.of(r.set, SetLines.FLOTS).single().getValue("flipped").jsonPrimitive.boolean)
        assertEquals("Only a FLOT is flipped.", r.measures.flipLine(pl))
        assertEquals("Give the phase line a name.", r.measures.renameLine(pl, "  pl  "))
        assertNull(r.measures.renameLine(pl, "blue"))
        assertEquals("BLUE", SetLines.of(r.set, SetLines.PHASE_LINES).single().getValue("name").jsonPrimitive.content)

        assertNull(r.measures.deleteLine(pl))
        assertNull("what was held goes with it", r.measures.held.value)
        assertTrue(SetLines.of(r.set, SetLines.PHASE_LINES).isEmpty())
        r.measuresSession.undo()
        assertEquals(1, SetLines.of(r.set, SetLines.PHASE_LINES).size)
        assertEquals("That is no longer here.", r.measures.deleteLine(HeldMeasure.Line(r.set.id, SetLines.FLOTS, "flot-gone")))
    }

    @Test
    fun `the set is renamed in capitals, a blank name refused, and closing it forgets it for the next launch`() = runTest {
        val r = rig()
        r.draw(MeasureKind.FLOT, a, b)
        r.measures.finish()
        assertEquals("Give the control measures a name.", r.measures.renameSet("   "))
        assertNull(r.measures.renameSet(" op dk lines "))
        assertEquals("OP DK LINES", r.set.name)
        val id = r.set.id
        r.measures.closeSet()
        assertNull(r.measuresSession.active.value)
        assertNull(r.measures.held.value)
        assertNull(r.last.id)
        assertEquals("OP DK LINES", r.routeRepo.open(id)!!.name)
    }

    @Test
    fun `the set last open comes back at launch, and a saved set of them opens as control measures, a route set does not`() = runTest {
        val r = rig()
        r.draw(MeasureKind.FLOT, a, b)
        r.measures.finish()
        val id = r.set.id
        r.measuresSession.close()
        r.measures.restore()
        assertEquals(id, r.measuresSession.active.value?.id)

        val routeSet = r.routeRepo.create("ROUTES", listOf(route()))
        assertNull("a route set is opened as routes", r.measures.openIfMeasures(routeSet.id))
        r.measuresSession.close()
        assertEquals(true, r.measures.openIfMeasures(id))
        assertEquals(id, r.measuresSession.active.value?.id)
    }

    @Test
    fun `lines saved inside a route set are kept on their own, and the route set keeps its routes`() = runTest {
        val r = rig()
        val flot = JsonObject(mapOf("id" to JsonPrimitive("flot-old"), "points" to SetLines.plain(listOf(a, b))))
        val made = r.routeRepo.create("OLD SET", listOf(route()), extras = JsonObject(mapOf(SetLines.FLOTS to kotlinx.serialization.json.JsonArray(listOf(flot)))))
        r.routes.open(made.id)
        assertEquals(made.id, r.measures.setsNow().single().id)

        assertNull(r.measures.keepOnTheirOwn(made.id))
        val routeSet = checkNotNull(r.routes.active.value)
        assertEquals(1, routeSet.routes.size)
        assertEquals(0, SetLines.count(routeSet))
        assertEquals(listOf("flot-old"), SetLines.of(r.set, SetLines.FLOTS).map { it.getValue("id").jsonPrimitive.content })
        assertEquals(SetLines.MEASURES_NAME, r.set.name)

        // A second route set's lines join the same control measures.
        val pl = JsonObject(mapOf("id" to JsonPrimitive("pl-old"), "name" to JsonPrimitive("RED"), "points" to SetLines.plain(listOf(b, c))))
        val other = r.routeRepo.create("OTHER", listOf(route()), extras = JsonObject(mapOf(SetLines.PHASE_LINES to kotlinx.serialization.json.JsonArray(listOf(pl)))))
        r.routes.open(other.id)
        assertNull(r.measures.keepOnTheirOwn(other.id))
        assertEquals(1, SetLines.of(r.set, SetLines.FLOTS).size)
        assertEquals(1, SetLines.of(r.set, SetLines.PHASE_LINES).size)
    }

    private fun route() = SketchRoute(
        id = "sketch-1", name = "ROUTE 1", color = "#ff5500",
        points = listOf(RoutePoint(id = "p1", lat = a.lat, lon = a.lon, kind = "amps"), RoutePoint(id = "p2", lat = c.lat, lon = c.lon, kind = "amps")),
    )
}
