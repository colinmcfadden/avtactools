package app.ezpztac.data

import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drawing a boundary by hand: what starting, adding, undoing, finishing and cancelling do to the draft and to the diagram. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryDrawingTest {
    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")
    private val a = LatLon(34.70, -84.10)
    private val b = LatLon(34.80, -84.00)
    private val c = LatLon(34.80, -84.10)

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope)
        val drawing = BoundaryDrawing(session)
    }

    private fun ring(vararg points: LatLon) = JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })

    private suspend fun Rig.open(analyse: Boolean = false, name: String = "LZ HAWK"): String {
        val made = repository.create(target, name)
        session.open(made.id)
        if (analyse) {
            session.edit("Analyze") { DiagramOps.completeAnalysis(it, JsonObject(mapOf("detectedLZ" to ring(a, b, c)))) }
            session.edit("Place") { DiagramOps.upsertGraphic(it, "helicopters", JsonObject(mapOf("id" to JsonPrimitive(1), "lat" to JsonPrimitive(34.78), "lon" to JsonPrimitive(-84.08)))) }
        }
        return made.id
    }

    private val Rig.diagram get() = session.active.value!!

    // -- Starting ---------------------------------------------------------------------------------------------------

    @Test
    fun `with no diagram open there is nothing to draw on`() = runTest {
        val r = Rig(this)
        assertEquals("Open a diagram first.", r.drawing.start())
        assertNull(r.drawing.draft.value)
    }

    @Test
    fun `a diagram with no target has nothing to draw a boundary around`() = runTest {
        val r = Rig(this)
        r.open()
        r.session.edit("Clear target") { it.copy(target = null) }
        assertEquals("Set a target before drawing a boundary.", r.drawing.start())
        assertNull(r.drawing.draft.value)
    }

    @Test
    fun `starting on a diagram that has nothing to clear starts a draft and records no edit`() = runTest {
        val r = Rig(this)
        val id = r.open()
        assertNull(r.drawing.start())
        assertEquals(BoundaryDraft(id, emptyList()), r.drawing.draft.value)
        assertEquals(0, r.session.undoDepth.value)                                          // nothing changed, so there is nothing to undo
    }

    @Test
    fun `starting again while drawing keeps the points already put down`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        r.drawing.addPoint(a)
        assertNull(r.drawing.start())
        assertEquals(listOf(a), r.drawing.draft.value!!.points)
    }

    @Test
    fun `starting clears an earlier drawn boundary`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        listOf(a, b, c).forEach(r.drawing::addPoint)
        r.drawing.finish()
        assertEquals(listOf(a, b, c), DiagramGeometry.drawn(r.diagram))
        r.drawing.start()
        assertEquals(emptyList<LatLon>(), DiagramGeometry.drawn(r.diagram))
        assertEquals(JsonNull, r.diagram.analysis.customLZ)
    }

    @Test
    fun `starting on an analysed diagram clears the analysis, keeps the graphics, and can be undone`() = runTest {
        val r = Rig(this)
        r.open(analyse = true)
        assertEquals(DiagramStatus.ANALYZED, r.diagram.status)
        assertNull(r.drawing.start())
        assertEquals(DiagramStatus.TARGETED, r.diagram.status)
        assertEquals(emptyList<LatLon>(), DiagramGeometry.boundary(r.diagram))
        assertEquals(1, r.diagram.graphics.helicopters.size)                                // the aircraft are where they were put
        r.session.undo()
        assertEquals(DiagramStatus.ANALYZED, r.diagram.status)
        assertEquals(3, DiagramGeometry.boundary(r.diagram).size)
    }

    // -- Putting points down ---------------------------------------------------------------------------------------

    @Test
    fun `points are kept in the order they were put down`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        assertTrue(r.drawing.addPoint(a))
        assertTrue(r.drawing.addPoint(b))
        assertEquals(listOf(a, b), r.drawing.draft.value!!.points)
    }

    @Test
    fun `a point is refused when nothing is being drawn`() = runTest {
        val r = Rig(this)
        r.open()
        assertFalse(r.drawing.addPoint(a))
        assertNull(r.drawing.draft.value)
    }

    @Test
    fun `a position that is not one is refused`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        for (bad in listOf(LatLon(Double.NaN, 0.0), LatLon(0.0, Double.POSITIVE_INFINITY), LatLon(91.0, 0.0), LatLon(-90.5, 0.0), LatLon(0.0, 180.5), LatLon(0.0, -181.0))) {
            assertFalse("$bad", r.drawing.addPoint(bad))
        }
        assertEquals(emptyList<LatLon>(), r.drawing.draft.value!!.points)
        assertTrue(r.drawing.addPoint(LatLon(90.0, 180.0)))                                  // the edges themselves are positions
        assertTrue(r.drawing.addPoint(LatLon(-90.0, -180.0)))
    }

    @Test
    fun `a point is refused once another diagram is open`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        val other = r.repository.create(DiagramTarget(35.0, -85.0, "16S GD 1 1"), "LZ CROW")
        r.session.open(other.id)
        assertFalse(r.drawing.addPoint(a))
        assertEquals(emptyList<LatLon>(), r.drawing.draft.value!!.points)
    }

    @Test
    fun `taking a point back removes the last, and with none it does nothing`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        r.drawing.removeLastPoint()
        assertEquals(emptyList<LatLon>(), r.drawing.draft.value!!.points)
        r.drawing.addPoint(a); r.drawing.addPoint(b); r.drawing.addPoint(c)
        r.drawing.removeLastPoint()
        assertEquals(listOf(a, b), r.drawing.draft.value!!.points)
    }

    @Test
    fun `taking a point back with nothing being drawn does nothing`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.removeLastPoint()
        assertNull(r.drawing.draft.value)
    }

    // -- Finishing and cancelling ----------------------------------------------------------------------------------

    @Test
    fun `a boundary needs three points, and drawing goes on until it has them`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        assertFalse(r.drawing.draft.value!!.canFinish)
        r.drawing.addPoint(a); r.drawing.addPoint(b)
        assertFalse(r.drawing.draft.value!!.canFinish)
        assertEquals("A boundary needs at least 3 points.", r.drawing.finish())
        assertNotNull(r.drawing.draft.value)                                                // still drawing, the two points kept
        assertEquals(2, r.drawing.draft.value!!.points.size)
        assertEquals(emptyList<LatLon>(), DiagramGeometry.drawn(r.diagram))
        r.drawing.addPoint(c)
        assertTrue(r.drawing.draft.value!!.canFinish)
    }

    @Test
    fun `finishing saves the ring as the diagram's drawn boundary, as latitude and longitude pairs, and stops drawing`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        listOf(a, b, c).forEach(r.drawing::addPoint)
        assertNull(r.drawing.finish())
        assertNull(r.drawing.draft.value)
        assertEquals(ring(a, b, c), r.diagram.analysis.customLZ)                             // [[lat, lon], ...] as the web saves it
        assertEquals(listOf(a, b, c), DiagramGeometry.drawn(r.diagram))
        assertEquals(DiagramStatus.TARGETED, r.diagram.status)                              // drawing is not analysing
    }

    @Test
    fun `a finished boundary is one step to undo, and undoing it leaves nothing drawn`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        listOf(a, b, c).forEach(r.drawing::addPoint)
        r.drawing.finish()
        assertEquals(1, r.session.undoDepth.value)
        r.session.undo()
        assertEquals(emptyList<LatLon>(), DiagramGeometry.drawn(r.diagram))
    }

    @Test
    fun `finishing is saved with the diagram`() = runTest {
        val r = Rig(this)
        val id = r.open()
        r.drawing.start()
        listOf(a, b, c).forEach(r.drawing::addPoint)
        r.drawing.finish()
        r.session.flush()
        runCurrent()
        assertEquals(listOf(a, b, c), DiagramGeometry.drawn(r.repository.open(id)!!))
    }

    @Test
    fun `finishing with nothing being drawn says so`() = runTest {
        val r = Rig(this)
        r.open()
        assertEquals("Nothing is being drawn.", r.drawing.finish())
    }

    @Test
    fun `finishing after another diagram was opened drops the draft and changes nothing`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        listOf(a, b, c).forEach(r.drawing::addPoint)
        val other = r.repository.create(DiagramTarget(35.0, -85.0, "16S GD 1 1"), "LZ CROW")
        r.session.open(other.id)
        assertEquals("The diagram this was drawn on is no longer open.", r.drawing.finish())
        assertNull(r.drawing.draft.value)
        assertEquals(emptyList<LatLon>(), DiagramGeometry.drawn(r.diagram))
    }

    @Test
    fun `cancelling forgets the points and leaves the diagram as starting left it`() = runTest {
        val r = Rig(this)
        r.open()
        r.drawing.start()
        r.drawing.addPoint(a); r.drawing.addPoint(b)
        r.drawing.cancel()
        assertNull(r.drawing.draft.value)
        assertEquals(emptyList<LatLon>(), DiagramGeometry.drawn(r.diagram))
        r.drawing.cancel()                                                                  // and again is nothing
        assertNull(r.drawing.draft.value)
    }

    @Test
    fun `cancelling after starting on an analysed diagram does not bring the analysis back`() = runTest {
        val r = Rig(this)
        r.open(analyse = true)
        r.drawing.start()
        r.drawing.cancel()
        assertEquals(DiagramStatus.TARGETED, r.diagram.status)                              // as on the web: the analysis was thrown away at the start (undo has it)
    }
}
