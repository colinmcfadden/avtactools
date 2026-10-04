package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.MissionLink
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Threat
import app.ezpztac.model.Radars
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking something up on the map and putting it down: it follows the finger, keeps its shape and the offset it was grabbed at, and the whole drag is one step to undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapDragTest {
    private class Vault : ThreatVault {
        var kept: ThreatPicture? = null
        var saves = 0
        override fun load() = kept
        override fun save(picture: ThreatPicture) { kept = picture; saves++ }
        override fun wipe() { kept = null }
    }

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val store = device.store as InMemorySyncStore
        val diagrams = DiagramRepository(device.repository, store, RecordingScheduler())
        val session = DiagramSession(diagrams, scope.backgroundScope)
        val routes = RouteRepository(device.repository, store, RecordingScheduler())
        val routeSession = RouteSession(routes, scope.backgroundScope)
        val vault = Vault()
        val threats = ThreatStore(vault, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler)) { 1_000L }
        val drag = MapDrag(session, routeSession, threats)
        val diagram: Diagram get() = checkNotNull(session.active.value)

        suspend fun openDiagram() {
            val made = diagrams.create(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), "LZ HAWK")
            session.open(made.id)
            session.setQuietly { DiagramOps.completeAnalysis(it, null) }
        }

        fun place(collection: String, item: JsonObject) = session.setQuietly { DiagramOps.upsertGraphic(it, collection, item) }

        fun position(collection: String, id: String): LatLon = GraphicEdits.position(collection, checkNotNull(DiagramOps.graphic(diagram, collection, id)))!!
    }

    private val home = LatLon(34.78, -84.08)

    private fun helicopter(id: Int, at: LatLon) = buildJsonObject { put("id", id); put("lat", at.lat); put("lon", at.lon); put("rotation", 90.0) }

    private fun TestScope.rig() = Rig(this)

    private fun metres(from: LatLon, to: LatLon) = GraphicEdits.metresBetween(from, to)

    // -- Graphics -------------------------------------------------------------------------------------------------------

    @Test
    fun `a graphic follows the finger by how far it has moved, so it does not jump to the finger`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("helicopters", helicopter(1, home))
        val grabbedAt = GraphicEdits.offset(home, 12.0, -9.0)                        // the finger was not on its middle
        assertTrue(r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), grabbedAt))
        r.drag.move(GraphicEdits.offset(grabbedAt, 100.0, 250.0))
        val (north, east) = metres(home, r.position("helicopters", "1"))
        assertEquals(100.0, north, 0.01)
        assertEquals(250.0, east, 0.01)
        r.drag.end(GraphicEdits.offset(grabbedAt, 100.0, 250.0))
        assertNull(r.drag.active.value)
    }

    @Test
    fun `a whole drag is one step to undo, however many times the finger moved`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("helicopters", helicopter(1, home))
        val before = r.session.undoDepth.value
        r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), home)
        repeat(60) { i -> r.drag.move(GraphicEdits.offset(home, i * 2.0, i * 3.0)) }
        r.drag.end(GraphicEdits.offset(home, 120.0, 180.0))
        assertEquals(before + 1, r.session.undoDepth.value)
        r.session.undo()
        assertEquals(0.0, metres(home, r.position("helicopters", "1")).first, 0.01)
        assertEquals(0.0, metres(home, r.position("helicopters", "1")).second, 0.01)
        r.session.redo()
        assertEquals(120.0, metres(home, r.position("helicopters", "1")).first, 0.01)
    }

    @Test
    fun `two drags are two steps`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("helicopters", helicopter(1, home))
        val before = r.session.undoDepth.value
        repeat(2) {
            r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), home)
            r.drag.end(GraphicEdits.offset(home, 50.0, 0.0))
        }
        assertEquals(before + 2, r.session.undoDepth.value)
    }

    @Test
    fun `dropping it back where it started leaves nothing to undo`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("helicopters", helicopter(1, home))
        val before = r.session.undoDepth.value
        r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), home)
        r.drag.move(GraphicEdits.offset(home, 80.0, 80.0))
        r.drag.end(home)
        assertEquals(before, r.session.undoDepth.value)
        assertEquals(0.0, metres(home, r.position("helicopters", "1")).first, 0.01)
    }

    @Test
    fun `cancelling puts it back where it was picked up from`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("helicopters", helicopter(1, home))
        val before = r.session.undoDepth.value
        r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), home)
        r.drag.move(GraphicEdits.offset(home, 300.0, 0.0))
        r.drag.cancel()
        assertEquals(0.0, metres(home, r.position("helicopters", "1")).first, 0.01)
        assertEquals(before, r.session.undoDepth.value)
        assertNull(r.drag.active.value)
    }

    @Test
    fun `a PZ marker keeps its tip and a sector keeps its shape`() = runTest {
        val r = rig(); r.openDiagram()
        val tip = GraphicEdits.offset(home, 0.0, 200.0)
        r.place("pzMarkers", buildJsonObject { put("id", 7); put("lat", home.lat); put("lon", home.lon); put("tipLat", tip.lat); put("tipLon", tip.lon) })
        val corners = listOf(home, GraphicEdits.offset(home, 100.0, 0.0), GraphicEdits.offset(home, 100.0, 100.0))
        r.place("sectorsOfFire", buildJsonObject {
            put("id", 8)
            put("points", JsonArray(corners.map { buildJsonObject { put("lat", it.lat); put("lng", it.lon) } }))
        })
        r.drag.start(DragTarget.Graphic(GraphicRef("pzMarkers", "7")), home)
        r.drag.end(GraphicEdits.offset(home, 40.0, -60.0))
        val marker = checkNotNull(DiagramOps.graphic(r.diagram, "pzMarkers", "7"))
        assertEquals(200.0, GraphicEdits.pzReachM(marker)!!, 0.05)                    // the reach did not change
        assertEquals(40.0, metres(home, GraphicEdits.position("pzMarkers", marker)!!).first, 0.05)

        r.drag.start(DragTarget.Graphic(GraphicRef("sectorsOfFire", "8")), home)
        r.drag.end(GraphicEdits.offset(home, 500.0, 500.0))
        val sector = checkNotNull(DiagramOps.graphic(r.diagram, "sectorsOfFire", "8"))
        val moved = (sector["points"] as JsonArray).map { (it as JsonObject).let { p -> LatLon((p["lat"] as JsonPrimitive).content.toDouble(), (p["lng"] as JsonPrimitive).content.toDouble()) } }
        assertEquals(100.0, metres(moved[0], moved[1]).first, 0.05)                   // the same shape, somewhere else
        assertEquals(100.0, metres(moved[1], moved[2]).second, 0.05)
        assertEquals(500.0, metres(corners[0], moved[0]).first, 0.05)
    }

    @Test
    fun `something that is not there cannot be picked up, and nothing is open to pick from`() = runTest {
        val r = rig()
        assertFalse(r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), home))          // no diagram open
        r.openDiagram()
        assertFalse(r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "nope")), home))
        assertFalse(r.drag.start(DragTarget.RoutePoint("r", "p"), home))                              // no set open
        assertFalse(r.drag.start(DragTarget.Threat("t"), home))
        assertNull(r.drag.active.value)
    }

    @Test
    fun `picking something else up while one is held puts the first back`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("helicopters", helicopter(1, home))
        r.place("helicopters", helicopter(2, GraphicEdits.offset(home, 500.0, 0.0)))
        r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "1")), home)
        r.drag.move(GraphicEdits.offset(home, 90.0, 0.0))
        assertTrue(r.drag.start(DragTarget.Graphic(GraphicRef("helicopters", "2")), home))
        assertEquals(0.0, metres(home, r.position("helicopters", "1")).first, 0.01)
        assertEquals(DragTarget.Graphic(GraphicRef("helicopters", "2")), r.drag.active.value)
    }

    // -- Route points ------------------------------------------------------------------------------------------------------

    private fun pt(id: String, at: LatLon, name: String = "") = RoutePoint(id = id, lat = at.lat, lon = at.lon, kind = RoutePoint.KIND_AMPS, ptType = "turn", name = name)

    private suspend fun Rig.openSet(routes: List<SketchRoute>, mission: Boolean = false) {
        val made = this.routes.create("SET", routes)
        routeSession.open(made.id)
        if (mission) routeSession.setQuietly { it.copy(mission = MissionLink("m.msnx")) }
    }

    @Test
    fun `a point of a route is moved, forgets its ground elevation, and the drag is one step`() = runTest {
        val r = rig()
        val a = pt("a", home, ".SP")
        val b = pt("b", GraphicEdits.offset(home, 1000.0, 0.0), ".LZ")
        r.openSet(listOf(SketchRoute("r1", "ROUTE 1", "#FF453A", points = listOf(a, b), plan = RoutePlan(), elevations = mapOf("a" to 800.0, "b" to 900.0))))
        val before = r.routeSession.undoDepth.value
        assertTrue(r.drag.start(DragTarget.RoutePoint("r1", "b"), b.let { LatLon(it.lat, it.lon) }))
        repeat(20) { i -> r.drag.move(GraphicEdits.offset(LatLon(b.lat, b.lon), i * 5.0, i * 5.0)) }
        r.drag.end(GraphicEdits.offset(LatLon(b.lat, b.lon), 100.0, 0.0))
        val route = r.routeSession.active.value!!.routes.single()
        assertEquals(100.0, metres(LatLon(b.lat, b.lon), LatLon(route.points[1].lat, route.points[1].lon)).first, 0.01)
        assertEquals(mapOf("a" to 800.0), route.elevations)                             // b's ground is not the new place's; a's is untouched
        assertEquals(before + 1, r.routeSession.undoDepth.value)
        r.routeSession.undo()
        assertEquals(b.lat, r.routeSession.active.value!!.routes.single().points[1].lat, 1e-12)
    }

    @Test
    fun `a hand-off point is moved with its twin in an imported mission, and alone in a set drawn here`() = runTest {
        suspend fun twins(mission: Boolean): Pair<Double, Double> {
            val r = rig()
            val shared = GraphicEdits.offset(home, 1000.0, 0.0)
            val r1 = SketchRoute("r1", "ONE", "#FF453A", points = listOf(pt("a1", home, ".SP"), pt("lz1", shared, ".LZ")), plan = RoutePlan())
            val r2 = SketchRoute("r2", "TWO", "#0A84FF", points = listOf(pt("lz2", shared, "LZ"), pt("b2", GraphicEdits.offset(home, 2000.0, 0.0), ".RP")), plan = RoutePlan())
            r.openSet(listOf(r1, r2), mission = mission)
            r.drag.start(DragTarget.RoutePoint("r1", "lz1"), shared)
            r.drag.end(GraphicEdits.offset(shared, 0.0, 70.0))
            val set = r.routeSession.active.value!!
            return metres(shared, LatLon(set.route("r1")!!.points[1].lat, set.route("r1")!!.points[1].lon)).second to
                metres(shared, LatLon(set.route("r2")!!.points[0].lat, set.route("r2")!!.points[0].lon)).second
        }
        val (ownM, twinM) = twins(mission = true)
        assertEquals(70.0, ownM, 0.05)
        assertEquals(70.0, twinM, 0.05)                                                   // AMPS keeps the hand-off as two points; they move together
        val (ownS, twinS) = twins(mission = false)
        assertEquals(70.0, ownS, 0.05)
        assertEquals(0.0, twinS, 0.05)                                                    // in a set of your own they are different points
    }

    // -- Threats --------------------------------------------------------------------------------------------------------------

    @Test
    fun `a threat follows the finger at once but is written to its sealed file only when the finger lifts`() = runTest {
        val r = rig()
        val id = r.threats.add(Threat("SA-6", "SHGPEWRR------", home.lat, home.lon, "", "SOF", radars = Radars.defaultPair()))
        advanceUntilIdle()
        val saved = r.vault.saves
        assertTrue(r.drag.start(DragTarget.Threat(id), home))
        repeat(40) { i -> r.drag.move(GraphicEdits.offset(home, i * 3.0, 0.0)) }
        advanceUntilIdle()
        assertEquals(saved, r.vault.saves)                                                // nothing written while it is being dragged
        val live = r.threats.entries.value.single().threat
        assertEquals(117.0, metres(home, LatLon(live.lat, live.lon)).first, 0.05)
        r.drag.end(GraphicEdits.offset(home, 200.0, 0.0))
        advanceUntilIdle()
        assertEquals(saved + 1, r.vault.saves)                                            // once
        val placed = r.vault.kept!!.entries.single().threat
        assertEquals(200.0, metres(home, LatLon(placed.lat, placed.lon)).first, 0.05)
        assertNotNull(placed)
    }

    @Test
    fun `cancelling a threat drag puts it back and writes it`() = runTest {
        val r = rig()
        val id = r.threats.add(Threat("SA-6", "SHGPEWRR------", home.lat, home.lon, "", "SOF", radars = Radars.defaultPair()))
        advanceUntilIdle()
        r.drag.start(DragTarget.Threat(id), home)
        r.drag.move(GraphicEdits.offset(home, 500.0, 500.0))
        r.drag.cancel()
        advanceUntilIdle()
        val back = r.threats.entries.value.single().threat
        assertEquals(0.0, metres(home, LatLon(back.lat, back.lon)).first, 0.05)
        assertEquals(0.0, metres(home, LatLon(r.vault.kept!!.entries.single().threat.lat, r.vault.kept!!.entries.single().threat.lon)).first, 0.05)
    }

    @Test
    fun `dragging the end of a PZ marker's arrow moves the tip alone, so the reach and the bearing change and the anchor stays`() = runTest {
        val r = rig(); r.openDiagram()
        val tip = GraphicEdits.offset(home, 0.0, 200.0)
        r.place("pzMarkers", buildJsonObject { put("id", 7); put("lat", home.lat); put("lon", home.lon); put("tipLat", tip.lat); put("tipLon", tip.lon) })
        val before = r.session.undoDepth.value
        assertTrue(r.drag.start(DragTarget.PzTip(GraphicRef("pzMarkers", "7")), tip))
        repeat(10) { i -> r.drag.move(GraphicEdits.offset(tip, i * 10.0, 0.0)) }
        r.drag.end(GraphicEdits.offset(tip, 200.0, 0.0))                                      // 200 m further north: the arrow now points north-east
        val marker = checkNotNull(DiagramOps.graphic(r.diagram, "pzMarkers", "7"))
        assertEquals(0.0, metres(home, GraphicEdits.position("pzMarkers", marker)!!).first, 0.01)      // the anchor did not move
        assertEquals(Math.hypot(200.0, 200.0), GraphicEdits.pzReachM(marker)!!, 0.5)
        assertEquals(45.0, GraphicEdits.rotation("pzMarkers", marker)!!, 0.5)
        assertEquals(before + 1, r.session.undoDepth.value)
        r.session.undo()
        assertEquals(200.0, GraphicEdits.pzReachM(checkNotNull(DiagramOps.graphic(r.diagram, "pzMarkers", "7")))!!, 0.05)
    }

    @Test
    fun `a PZ marker with no tip can be picked up by its tip, where it is drawn`() = runTest {
        val r = rig(); r.openDiagram()
        r.place("pzMarkers", buildJsonObject { put("id", 9); put("lat", home.lat); put("lon", home.lon) })
        assertTrue(r.drag.start(DragTarget.PzTip(GraphicRef("pzMarkers", "9")), home))
        r.drag.end(GraphicEdits.offset(home, 50.0, 0.0))
        assertNotNull(DiagramOps.graphic(r.diagram, "pzMarkers", "9")!!["tipLat"])
    }
}
