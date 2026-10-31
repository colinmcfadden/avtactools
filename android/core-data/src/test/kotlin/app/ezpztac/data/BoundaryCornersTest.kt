package app.ezpztac.data

import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taking a corner off the boundary: one undo step, never below three corners, in the ring the corner is in. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryCornersTest {
    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope)
        val corners = BoundaryCorners(session)
    }

    private fun ring(vararg p: LatLon) = JsonArray(p.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })

    private suspend fun Rig.open(key: String, vararg p: LatLon) {
        val made = repository.create(target, "LZ HAWK")
        session.open(made.id)
        session.setQuietly { DiagramOps.setAnalysisDraft(it, JsonObject(mapOf(key to ring(*p)))) }
    }

    private fun Rig.points(key: String): List<LatLon> {
        val a = session.active.value!!.analysis
        return DiagramGeometry.points(if (key == "customLZ") a.customLZ else a.detectedLZ)
    }

    private val a = LatLon(34.0, -84.0)
    private val b = LatLon(34.0, -83.9)
    private val c = LatLon(34.1, -83.9)
    private val d = LatLon(34.1, -84.0)

    @Test
    fun `a corner of the drawn boundary is deleted as one step that undo takes back`() = runTest {
        val r = Rig(this)
        r.open("customLZ", a, b, c, d)
        r.corners.select(BoundaryCornerRef(drawn = true, index = 1))
        val before = r.session.undoDepth.value
        assertNull(r.corners.deleteHeld())
        assertEquals(listOf(a, c, d), r.points("customLZ"))
        assertEquals(before + 1, r.session.undoDepth.value)
        assertNull(r.corners.held.value)
        r.session.undo()
        assertEquals(listOf(a, b, c, d), r.points("customLZ"))
    }

    @Test
    fun `a corner of the analysis boundary is deleted the same way`() = runTest {
        val r = Rig(this)
        r.open("detectedLZ", a, b, c, d)
        r.corners.select(BoundaryCornerRef(drawn = false, index = 3))
        assertNull(r.corners.deleteHeld())
        assertEquals(listOf(a, b, c), r.points("detectedLZ"))
    }

    @Test
    fun `a boundary keeps three corners and says so`() = runTest {
        val r = Rig(this)
        r.open("customLZ", a, b, c)
        val corner = BoundaryCornerRef(true, 0)
        r.corners.select(corner)
        assertFalse(r.corners.canDelete(corner))
        assertEquals("A boundary needs at least 3 corners.", r.corners.deleteHeld())
        assertEquals(3, r.points("customLZ").size)
        assertEquals(corner, r.corners.held.value)
    }

    @Test
    fun `nothing held or a corner no longer there deletes nothing`() = runTest {
        val r = Rig(this)
        r.open("customLZ", a, b, c, d)
        assertEquals("No corner is held.", r.corners.deleteHeld())
        r.corners.select(BoundaryCornerRef(true, 9))
        assertEquals("That corner is no longer there.", r.corners.deleteHeld())
        assertEquals(4, r.points("customLZ").size)
        assertNull(r.corners.held.value)
    }

    @Test
    fun `only a polygon has corners and each ring is counted by itself`() = runTest {
        val r = Rig(this)
        r.open("customLZ", a, b)
        assertEquals(0, r.corners.cornersIn(drawn = true))
        r.session.setQuietly { DiagramOps.setAnalysisDraft(it, JsonObject(mapOf("detectedLZ" to ring(a, b, c, d)))) }
        assertEquals(4, r.corners.cornersIn(drawn = false))
        assertTrue(r.corners.canDelete(BoundaryCornerRef(false, 3)))
    }
}
