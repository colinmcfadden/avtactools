package app.ezpztac.workspace

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.InMemoryAircraftChoice
import app.ezpztac.data.InMemoryMasterProfileStore
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.GraphicSelection
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.UnitDraft
import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class GraphicsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val grid = "16S GD 66993 52949"
    private val crosshair = LatLon(34.78382, -84.08219)

    private class Rig(scope: TestScope, masterList: List<app.ezpztac.model.AircraftProfile> = emptyList()) {
        val device = Device("A", FakeServer())
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope)
        val selection = GraphicSelection()
        val choice = InMemoryAircraftChoice()
        val master = InMemoryMasterProfileStore(masterList)
        val aircraft = AircraftProfiles(device.store as InMemorySyncStore, master, { emptyList() }, choice, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, RecordingScheduler())
        val model = GraphicsViewModel(session, selection, aircraft)
        val state get() = model.state.value
        val diagram: Diagram get() = checkNotNull(session.active.value)

        /** A diagram that is targeted and open, and (when [analysed]) analysed, so graphics may be placed on it. */
        suspend fun open(analysed: Boolean, grid: String, doghouses: Boolean = false) {
            val made = repository.create(DiagramTarget(34.783817, -84.08219, grid), "LZ HAWK")
            session.open(made.id)
            // An analysis makes the standard SP and RP doghouses (once); without them the diagram is analysed and bare.
            if (analysed) session.setQuietly { if (doghouses) DiagramOps.afterAnalysis(it, null) else DiagramOps.completeAnalysis(it, null) }
        }

        fun graphic(ref: GraphicRef): JsonObject = checkNotNull(DiagramOps.graphic(diagram, ref.collection, ref.key))
    }

    private suspend fun TestScope.opened(analysed: Boolean = true, doghouses: Boolean = false, fleet: List<app.ezpztac.model.AircraftProfile> = emptyList()): Rig = Rig(this, fleet).also {
        it.open(analysed, grid, doghouses)
        advanceUntilIdle()
    }

    private fun text(o: JsonObject, field: String) = (o[field] as JsonPrimitive).content

    // -- Placing -------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing can be placed until the diagram is analysed`() = runTest(dispatcher) {
        val r = opened(analysed = false)
        assertFalse(r.state.canEdit)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        assertTrue(r.state.rows.isEmpty())
        assertEquals(0, r.state.undoDepth)
        assertTrue(r.diagram.graphics.helicopters.isEmpty())
        assertNull("nothing was made, so nothing is held", r.selection.selected.value)     // the diagram refuses it too; this is the guard's own say
    }

    @Test
    fun `with no diagram open there is nothing to edit`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        assertEquals(GraphicsUiState(), r.state)
    }

    @Test
    fun `an aircraft is placed at the crosshair, held, and listed with its grid and heading`() = runTest(dispatcher) {
        val r = opened()
        assertTrue(r.state.canEdit)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()

        val row = r.state.rows.single()
        assertEquals(GraphicKind.HELICOPTER, row.kind)
        assertEquals("Helicopter · UH-60L", row.title)
        assertTrue(row.selected)
        assertFalse(row.warning)
        assertNotNull(row.grid)
        assertEquals("heading 0°", row.detail)
        val at = GraphicEdits.position("helicopters", r.graphic(row.ref))!!
        val (north, east) = GraphicEdits.metresBetween(crosshair, at)
        assertEquals(0.0, north, 0.5)                                                       // the first one goes exactly where it was asked
        assertEquals(0.0, east, 0.5)
        assertEquals(row.ref, r.selection.selected.value)
        assertEquals(row.ref, r.state.inspector!!.ref)
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `each kind of graphic is placed, with the controls that apply to it`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        r.state.inspector!!.let {
            assertEquals(GraphicKind.PZ_MARKER, it.kind)
            assertNotNull(it.reachFt)
            assertNotNull(it.rotation)
            assertNull(it.direction)
        }

        r.model.place(GraphicKind.SECTOR_OF_FIRE, crosshair)
        advanceUntilIdle()
        r.state.inspector!!.let {
            assertEquals(GraphicKind.SECTOR_OF_FIRE, it.kind)
            assertNull(it.rotation)                                                           // a sector does not turn
            assertNull(it.reachFt)
            assertNull(it.direction)
        }

        r.model.place(GraphicKind.GO_AROUND, crosshair, direction = "right")
        advanceUntilIdle()
        r.state.inspector!!.let {
            assertEquals(GraphicKind.GO_AROUND, it.kind)
            assertEquals("right", it.direction)
            assertNotNull(it.rotation)
            assertNull(it.reachFt)
        }
        assertEquals(listOf(GraphicKind.PZ_MARKER, GraphicKind.SECTOR_OF_FIRE, GraphicKind.GO_AROUND), r.state.rows.map { it.kind })
    }

    @Test
    fun `a go-around with no direction given goes left`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.GO_AROUND, crosshair)
        advanceUntilIdle()
        assertEquals("left", r.state.inspector!!.direction)
    }

    @Test
    fun `graphics placed in the same instant do not share an id`() = runTest(dispatcher) {
        val r = opened()
        repeat(3) { r.model.place(GraphicKind.PZ_MARKER, crosshair) }
        repeat(3) { r.model.place(GraphicKind.HELICOPTER, crosshair) }
        advanceUntilIdle()
        assertEquals(6, r.state.rows.size)
        assertEquals(6, r.state.rows.map { it.ref }.toSet().size)
        assertEquals(3, r.diagram.graphics.pzMarkers.size)
        assertEquals(3, r.diagram.graphics.helicopters.size)
    }

    @Test
    fun `with the map not yet showing a position nothing is placed and the person is told`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, at = null)
        advanceUntilIdle()
        assertTrue(r.state.rows.isEmpty())
        assertEquals("Move the map to where it should go first.", r.state.error)
        assertEquals(0, r.state.undoDepth)
        r.model.dismissError()
        advanceUntilIdle()
        assertNull(r.state.error)
    }

    @Test
    fun `placing something clears the last complaint`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, at = null)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        assertNull(r.state.error)
        assertEquals(1, r.state.rows.size)
    }

    @Test
    fun `an edit that works clears the complaint an earlier one left`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        r.model.moveToCrosshair(null)
        advanceUntilIdle()
        assertNotNull(r.state.error)
        r.model.nudge(10.0, 0.0)
        advanceUntilIdle()
        assertNull(r.state.error)
    }

    @Test
    fun `a second aircraft at the same place is set clear of the first`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val (a, b) = r.state.rows.map { GraphicEdits.position("helicopters", r.graphic(it.ref))!! }
        val (north, east) = GraphicEdits.metresBetween(a, b)
        assertTrue("two aircraft placed at one point are apart", abs(north) + abs(east) > 5.0)
        assertTrue("and clear of one another, so no alert", r.state.alerts.isEmpty())
        assertTrue(r.state.rows.none { it.warning })
    }

    // -- Choosing ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a tap holds a graphic, and a second tap puts it down`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        r.model.place(GraphicKind.GO_AROUND, crosshair)
        advanceUntilIdle()
        val (pz, ga) = r.state.rows.map { it.ref }
        assertEquals(ga, r.state.inspector!!.ref)                                          // the newest is held

        r.model.select(pz)
        advanceUntilIdle()
        assertEquals(pz, r.state.inspector!!.ref)
        assertEquals(listOf(true, false), r.state.rows.map { it.selected })

        r.model.select(pz)
        advanceUntilIdle()
        assertNull(r.state.inspector)
        assertTrue(r.state.rows.none { it.selected })
    }

    @Test
    fun `deselect lets go`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        r.model.deselect()
        advanceUntilIdle()
        assertNull(r.state.inspector)
    }

    @Test
    fun `a selection that is no longer in the diagram shows no controls and edits nothing`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        r.selection.select(GraphicRef("pzMarkers", "no-such-graphic"))
        advanceUntilIdle()
        assertNull(r.state.inspector)
        r.model.nudge(10.0, 0.0)
        advanceUntilIdle()
        assertEquals(1, r.state.undoDepth)
    }

    // -- Editing the one that is held ----------------------------------------------------------------------------------------

    @Test
    fun `nudging moves the held graphic by metres, as one undo step`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        val before = GraphicEdits.position("helicopters", r.graphic(ref))!!

        r.model.nudge(northFt = 10.0, eastFt = -4.0)
        advanceUntilIdle()
        val (north, east) = GraphicEdits.metresBetween(before, GraphicEdits.position("helicopters", r.graphic(ref))!!)
        assertEquals(10.0 * 0.3048, north, 0.01)                                           // feet in, metres on the ground
        assertEquals(-4.0 * 0.3048, east, 0.01)
        assertEquals(2, r.state.undoDepth)

        r.model.undo()
        advanceUntilIdle()
        assertEquals(before, GraphicEdits.position("helicopters", r.graphic(ref)))
    }

    @Test
    fun `nudging does nothing with nothing held`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        r.model.deselect()
        advanceUntilIdle()
        r.model.nudge(10.0, 0.0)
        advanceUntilIdle()
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `a sector moves with all of its points`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.SECTOR_OF_FIRE, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        val before = GraphicEdits.position("sectorsOfFire", r.graphic(ref))!!
        r.model.nudge(0.0, 25.0)
        advanceUntilIdle()
        val (north, east) = GraphicEdits.metresBetween(before, GraphicEdits.position("sectorsOfFire", r.graphic(ref))!!)
        assertEquals(0.0, north, 0.01)
        assertEquals(25.0 * 0.3048, east, 0.01)
    }

    @Test
    fun `the held graphic can be put at the crosshair`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        val elsewhere = LatLon(34.7900, -84.0900)

        r.model.moveToCrosshair(elsewhere)
        advanceUntilIdle()
        val moved = GraphicEdits.position("pzMarkers", r.graphic(ref))!!
        assertEquals(elsewhere.lat, moved.lat, 1e-9)
        assertEquals(elsewhere.lon, moved.lon, 1e-9)
        assertEquals(2, r.state.undoDepth)

        r.model.moveToCrosshair(null)
        advanceUntilIdle()
        assertEquals("Move the map to where it should go first.", r.state.error)
        assertEquals(2, r.state.undoDepth)
    }

    @Test
    fun `the held graphic can be put at a grid the person types`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref

        r.model.moveToText("16S GD 66993 52949")
        advanceUntilIdle()
        val at = GraphicEdits.position("helicopters", r.graphic(ref))!!
        assertEquals(34.783817, at.lat, 0.0001)
        assertEquals(-84.08219, at.lon, 0.0001)
        assertNull(r.state.error)
        assertEquals(2, r.state.undoDepth)
    }

    @Test
    fun `a grid that cannot be read is said in words and moves nothing`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        val before = r.graphic(ref)

        r.model.moveToText("not a place")
        advanceUntilIdle()
        assertNotNull(r.state.error)
        assertTrue(r.state.error!!.isNotBlank())
        assertEquals(before, r.graphic(ref))
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `an aircraft turns by degrees and by heading, and the list says which way it points`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        r.model.rotateBy(90.0)
        advanceUntilIdle()
        assertEquals(90.0, r.state.inspector!!.rotation!!, 1e-9)
        assertEquals("heading 90°", r.state.rows.single().detail)

        r.model.rotateBy(-100.0)                                                          // through north: 90 - 100 = 350
        advanceUntilIdle()
        assertEquals(350.0, r.state.inspector!!.rotation!!, 1e-9)

        r.model.setRotation(45.0)
        advanceUntilIdle()
        assertEquals(45.0, r.state.inspector!!.rotation!!, 1e-9)
        assertEquals(4, r.state.undoDepth)
    }

    @Test
    fun `a sector cannot be turned, and the person is told`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.SECTOR_OF_FIRE, crosshair)
        advanceUntilIdle()
        r.model.rotateBy(90.0)
        advanceUntilIdle()
        assertEquals("That cannot be done to this graphic.", r.state.error)
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `a PZ marker is lengthened and shortened, and never below nothing`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        val start = r.state.inspector!!.reachFt!!

        r.model.changeReach(50.0)
        advanceUntilIdle()
        assertEquals((start + 50).toDouble(), r.state.inspector!!.reachFt!!.toDouble(), 1.0)                       // each is rounded to a whole foot
        assertEquals("reach ${r.state.inspector!!.reachFt} ft", r.state.rows.single().detail)

        r.model.changeReach(-100_000.0)
        advanceUntilIdle()
        assertEquals(0L, r.state.inspector!!.reachFt!!)
    }

    @Test
    fun `reach is for PZ markers only`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        r.model.changeReach(10.0)
        advanceUntilIdle()
        assertEquals("That cannot be done to this graphic.", r.state.error)
        assertNull(r.state.inspector!!.reachFt)
    }

    @Test
    fun `a PZ marker's tip goes to the crosshair`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        val tip = LatLon(34.7850, -84.0800)
        r.model.tipToCrosshair(tip)
        advanceUntilIdle()
        val saved = r.graphic(ref)
        assertEquals(tip.lat, text(saved, "tipLat").toDouble(), 1e-9)
        assertEquals(tip.lon, text(saved, "tipLon").toDouble(), 1e-9)

        r.model.tipToCrosshair(null)
        advanceUntilIdle()
        assertNotNull(r.state.error)
    }

    @Test
    fun `a go-around is pointed left or right, and nothing else`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.GO_AROUND, crosshair)
        advanceUntilIdle()
        r.model.setDirection("right")
        advanceUntilIdle()
        assertEquals("right", r.state.inspector!!.direction)
        assertTrue(r.state.rows.single().detail!!.startsWith("right · "))

        r.model.setDirection("sideways")
        advanceUntilIdle()
        assertEquals("right", r.state.inspector!!.direction)
        assertEquals("That cannot be done to this graphic.", r.state.error)
    }

    @Test
    fun `direction is for go-arounds only`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        r.model.setDirection("left")
        advanceUntilIdle()
        assertEquals("That cannot be done to this graphic.", r.state.error)
    }

    // -- Separation ----------------------------------------------------------------------------------------------------------

    @Test
    fun `two aircraft pulled too close are flagged, and cleared when moved apart`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val (first, second) = r.state.rows.map { it.ref }
        assertTrue(r.state.alerts.isEmpty())

        // The second one is held: put it 5 m from the first, far inside a rotor disc.
        val firstAt = GraphicEdits.position("helicopters", r.graphic(first))!!
        r.model.moveToCrosshair(GraphicEdits.offset(firstAt, 5.0, 0.0))
        advanceUntilIdle()
        assertEquals(second, r.state.inspector!!.ref)
        assertEquals(1, r.state.alerts.size)
        assertTrue(r.state.alerts.single().isNotBlank())
        assertEquals(listOf(true, true), r.state.rows.map { it.warning })

        r.model.nudge(northFt = 700.0, eastFt = 0.0)
        advanceUntilIdle()
        assertTrue(r.state.alerts.isEmpty())
        assertEquals(listOf(false, false), r.state.rows.map { it.warning })
    }

    @Test
    fun `only aircraft raise alerts`() = runTest(dispatcher) {
        val r = opened()
        repeat(2) { r.model.place(GraphicKind.PZ_MARKER, crosshair) }
        advanceUntilIdle()
        assertTrue(r.state.alerts.isEmpty())
        assertTrue(r.state.rows.none { it.warning })
    }

    // -- Deleting and undo ---------------------------------------------------------------------------------------------------

    @Test
    fun `deleting removes the held graphic, lets go of it, and can be undone`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val (heli, pz) = r.state.rows.map { it.ref }                                       // the list runs aircraft first, whatever was placed first

        r.model.delete()
        advanceUntilIdle()
        assertEquals(listOf(pz), r.state.rows.map { it.ref })
        assertNull(r.state.inspector)
        assertNull(r.selection.selected.value)
        assertTrue(r.diagram.graphics.helicopters.isEmpty())

        r.model.undo()
        advanceUntilIdle()
        assertEquals(setOf(pz, heli), r.state.rows.map { it.ref }.toSet())
        assertEquals(1, r.state.redoDepth)

        r.model.redo()
        advanceUntilIdle()
        assertEquals(listOf(pz), r.state.rows.map { it.ref })
        assertEquals(0, r.state.redoDepth)
    }

    @Test
    fun `deleting with nothing held does nothing`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        r.model.deselect()
        advanceUntilIdle()
        r.model.delete()
        advanceUntilIdle()
        assertEquals(1, r.state.rows.size)
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `undo takes back a placement, and the depths say what is left to undo and redo`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        r.model.place(GraphicKind.GO_AROUND, crosshair)
        advanceUntilIdle()
        assertEquals(2, r.state.undoDepth)
        assertEquals(0, r.state.redoDepth)

        r.model.undo()
        advanceUntilIdle()
        assertEquals(listOf(GraphicKind.PZ_MARKER), r.state.rows.map { it.kind })
        assertEquals(1, r.state.undoDepth)
        assertEquals(1, r.state.redoDepth)

        r.model.undo()
        r.model.undo()                                                                    // nothing left: no harm
        advanceUntilIdle()
        assertTrue(r.state.rows.isEmpty())
        assertEquals(0, r.state.undoDepth)
        assertEquals(2, r.state.redoDepth)
    }

    @Test
    fun `an edit changes only what it names`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        // A field a newer web release might add must survive an edit made here.
        r.session.setQuietly { DiagramOps.patchGraphic(it, "helicopters", r.graphic(ref)["id"], JsonObject(mapOf("callsign" to JsonPrimitive("HAWK 21")))) }
        r.model.rotateBy(30.0)
        advanceUntilIdle()
        val saved = r.graphic(ref)
        assertEquals("HAWK 21", text(saved, "callsign"))
        assertEquals(30.0, text(saved, "rotation").toDouble(), 1e-9)
    }

    @Test
    fun `a diagram whose analysis is reset shows no controls again`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        r.session.setQuietly { DiagramOps.resetAnalysis(it) }
        advanceUntilIdle()
        assertFalse(r.state.canEdit)
        assertTrue(r.state.rows.isEmpty())
        assertNull(r.state.inspector)
    }


    // -- Doghouses -------------------------------------------------------------------------------------------------------

    private fun Rig.doghouse(role: String) = diagram.graphics.doghouses.map { it as JsonObject }.first { text(it, "role") == role }
    private fun Rig.doghouseRef(role: String) = GraphicRef("doghouses", text(doghouse(role), "id"))
    private fun Rig.flight(key: String) = (diagram.flightData[key] as? JsonPrimitive)?.content

    @Test
    fun `the two standard doghouses are listed after the other graphics, with what they say`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        val rows = r.state.rows
        assertEquals(listOf(GraphicKind.PZ_MARKER, GraphicKind.DOGHOUSE, GraphicKind.DOGHOUSE), rows.map { it.kind })
        assertEquals(listOf("PZ marker", "Doghouse · [SP1]", "Doghouse · [RP1]"), rows.map { it.title })
        assertEquals("000° · 01+57 · 3.13 km · 60 kts", rows[1].detail)
        assertEquals("000° · 02+10 · 5.2 km · 40 kts", rows[2].detail)
        assertNotNull(rows[1].grid)
    }

    @Test
    fun `a doghouse cannot be placed, only the two the analysis makes can be edited`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        r.model.place(GraphicKind.DOGHOUSE, crosshair)
        advanceUntilIdle()
        assertEquals(2, r.state.rows.size)
        assertEquals(0, r.state.undoDepth)
        assertNull(r.state.error)
        assertEquals(listOf(GraphicKind.HELICOPTER, GraphicKind.PZ_MARKER, GraphicKind.SECTOR_OF_FIRE, GraphicKind.GO_AROUND), GraphicKind.entries.filter { it.placeable })
    }

    @Test
    fun `a held doghouse shows its fields and which flight-data heading it sets`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        r.model.select(r.doghouseRef("takeoff"))
        advanceUntilIdle()
        val held = r.state.inspector!!
        assertEquals(GraphicKind.DOGHOUSE, held.kind)
        assertEquals(DoghouseUi("[SP1]", "01+57", "3.13", "60", "Sets the takeoff heading on the LZ card"), held.doghouse)
        assertEquals(0.0, held.rotation!!, 0.0)
        assertNull(held.reachFt)
        assertNull(held.direction)

        r.model.select(r.doghouseRef("landing"))
        advanceUntilIdle()
        assertEquals("Sets the landing heading on the LZ card", r.state.inspector!!.doghouse!!.feeds)
    }

    @Test
    fun `turning a doghouse writes its heading as the web does and the flight data follows, and undo takes both back`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        r.model.select(r.doghouseRef("landing"))
        advanceUntilIdle()
        assertEquals("000°", r.flight("landing_hdg"))

        r.model.rotateBy(90.0)
        advanceUntilIdle()
        assertEquals("090°", text(r.doghouse("landing"), "heading"))
        assertEquals("090°", r.flight("landing_hdg"))
        assertEquals("000°", r.flight("takeoff_hdg"))                                      // the other one is not touched
        assertEquals("090° · 02+10 · 5.2 km · 40 kts", r.state.rows.first { it.ref == r.doghouseRef("landing") }.detail)

        r.model.setRotation(270.0)
        advanceUntilIdle()
        assertEquals("270°", r.flight("landing_hdg"))

        r.model.undo()
        r.model.undo()
        advanceUntilIdle()
        assertEquals("000°", text(r.doghouse("landing"), "heading"))
        assertEquals("000°", r.flight("landing_hdg"))
    }

    @Test
    fun `a doghouse is moved like any graphic`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        val ref = r.doghouseRef("takeoff")
        r.model.select(ref)
        advanceUntilIdle()
        val before = GraphicEdits.position("doghouses", r.graphic(ref))!!
        r.model.nudge(northFt = 100.0, eastFt = 0.0)
        advanceUntilIdle()
        assertEquals(100.0 * 0.3048, GraphicEdits.metresBetween(before, GraphicEdits.position("doghouses", r.graphic(ref))!!).first, 0.01)
        r.model.moveToCrosshair(crosshair)
        advanceUntilIdle()
        assertEquals(crosshair, GraphicEdits.position("doghouses", r.graphic(ref)))
        assertEquals("01+57", r.state.inspector!!.doghouse!!.time)                         // its fields are untouched by a move
        assertEquals("[SP1]", r.state.inspector!!.doghouse!!.label)
    }

    @Test
    fun `the fields are typed over, stored as the web stores them, each one undo step`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        val ref = r.doghouseRef("takeoff")
        r.model.select(ref)
        advanceUntilIdle()

        assertNull(r.model.setDoghouseTime("3:20"))
        assertNull(r.model.setDoghouseDistance("12.5"))
        assertNull(r.model.setDoghouseAirspeed("55"))
        assertNull(r.model.setDoghouseLabel("[SP2]"))
        advanceUntilIdle()
        val saved = r.graphic(ref)
        assertEquals("03+20", text(saved, "time"))
        assertEquals("12.5km", text(saved, "dist"))
        assertEquals("55 kts", text(saved, "airspeed"))
        assertEquals("[SP2]", text(saved, "id_val"))
        assertEquals(DoghouseUi("[SP2]", "03+20", "12.5", "55", "Sets the takeoff heading on the LZ card"), r.state.inspector!!.doghouse)
        assertEquals("Doghouse · [SP2]", r.state.rows.first { it.ref == ref }.title)
        assertEquals(4, r.state.undoDepth)

        r.model.undo()
        advanceUntilIdle()
        assertEquals("[SP1]", text(r.graphic(ref), "id_val"))
    }

    @Test
    fun `what is not that field is answered in words, changes nothing and leaves the panel's banner alone`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        val ref = r.doghouseRef("takeoff")
        r.model.select(ref)
        advanceUntilIdle()
        val before = r.graphic(ref)

        assertEquals("Enter a time as minutes and seconds, such as 03+20.", r.model.setDoghouseTime("later"))
        assertEquals("Enter a distance in kilometres, such as 3.1.", r.model.setDoghouseDistance("far"))
        assertEquals("Enter an airspeed in knots, such as 60.", r.model.setDoghouseAirspeed("fast"))
        assertEquals("Enter a label of up to 12 characters, such as [SP1].", r.model.setDoghouseLabel(""))
        advanceUntilIdle()
        assertEquals(before, r.graphic(ref))
        assertEquals(0, r.state.undoDepth)
        assertNull(r.state.error)
    }

    @Test
    fun `a doghouse's field cannot be set on another kind of graphic`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        assertNull(r.model.setDoghouseTime("3:20"))                                       // the typing was fine; it is the wrong graphic
        advanceUntilIdle()
        assertEquals("That cannot be done to this graphic.", r.state.error)
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `with nothing held a doghouse field edit does nothing`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        assertNull(r.model.setDoghouseTime("3:20"))
        advanceUntilIdle()
        assertEquals(0, r.state.undoDepth)
        assertNull(r.state.error)
    }

    @Test
    fun `a doghouse can be deleted and brought back, and the flight data goes with it`() = runTest(dispatcher) {
        val r = opened(doghouses = true)
        val ref = r.doghouseRef("landing")
        r.model.select(ref)
        r.model.rotateBy(45.0)
        advanceUntilIdle()
        assertEquals("045°", r.flight("landing_hdg"))
        r.model.delete()
        advanceUntilIdle()
        assertEquals(1, r.state.rows.size)
        assertEquals("045°", r.flight("landing_hdg"))                                     // with no landing doghouse the heading stays as it was
        r.model.undo()
        advanceUntilIdle()
        assertEquals(2, r.state.rows.size)
    }
// -- Units -----------------------------------------------------------------------------------------------------------

    private val hostileArmor = UnitDraft("H", "UCA---", "D", "A/1-171", "2-101")

    @Test
    fun `a unit made in the builder is added where the crosshair is, held, and listed with what it is`() = runTest(dispatcher) {
        val r = opened()
        r.model.addUnit(hostileArmor, crosshair)
        advanceUntilIdle()

        val row = r.state.rows.single()
        assertEquals(GraphicKind.UNIT, row.kind)
        assertEquals("Unit · A/1-171", row.title)
        assertEquals("Hostile · Armor · Platoon", row.detail)
        assertTrue(row.selected)
        assertEquals(hostileArmor, r.state.inspector!!.unit)
        assertNull(r.state.inspector!!.rotation)                                           // a unit stays upright
        val saved = r.graphic(row.ref)
        assertEquals("SHGPUCA---D----", text(saved, "sidc"))
        assertEquals("A/1-171", text(saved, "uniqueDesignation"))
        assertEquals("2-101", text(saved, "higherFormation"))
        assertEquals(crosshair, GraphicEdits.position("units", saved))                      // where it was pointed, not off at random as on the web
        assertEquals(setOf("id", "sidc", "uniqueDesignation", "higherFormation", "lat", "lon"), saved.keys)
        assertTrue(row.ref.key.startsWith("unit-"))
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `a unit with no designation is named for what it is`() = runTest(dispatcher) {
        val r = opened()
        r.model.addUnit(UnitDraft(), crosshair)
        r.model.addUnit(UnitDraft(functionId = "UXXXXX"), crosshair)
        advanceUntilIdle()
        assertEquals(listOf("Unit · Infantry", "Unit · UXXXXX"), r.state.rows.map { it.title })
        assertEquals("Friendly · Infantry", r.state.rows[0].detail)
        assertEquals("Friendly", r.state.rows[1].detail)
    }

    @Test
    fun `units placed in the same instant do not share an id`() = runTest(dispatcher) {
        val r = opened()
        repeat(4) { r.model.addUnit(UnitDraft(), crosshair) }
        advanceUntilIdle()
        assertEquals(4, r.state.rows.map { it.ref }.toSet().size)
    }

    @Test
    fun `a unit is not added before the diagram is analysed, or with the map not showing a position`() = runTest(dispatcher) {
        val bare = opened(analysed = false)
        bare.model.addUnit(UnitDraft(), crosshair)
        advanceUntilIdle()
        assertEquals(0, bare.state.undoDepth)
        assertNull("nothing was made, so nothing is held", bare.selection.selected.value)

        val r = opened()
        r.model.addUnit(UnitDraft(), at = null)
        advanceUntilIdle()
        assertTrue(r.state.rows.isEmpty())
        assertEquals("Move the map to where it should go first.", r.state.error)
    }

    @Test
    fun `applying the builder to the held unit changes its symbol and labels and nothing else, as one step`() = runTest(dispatcher) {
        val r = opened()
        r.model.addUnit(UnitDraft(), crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        // A unit may carry what a newer release of the web added; applying the builder must not lose it.
        r.session.setQuietly { DiagramOps.patchGraphic(it, "units", r.graphic(ref)["id"], JsonObject(mapOf("callsign" to JsonPrimitive("DOG 6")))) }
        val before = r.graphic(ref)

        r.model.updateUnit(hostileArmor)
        advanceUntilIdle()
        val saved = r.graphic(ref)
        assertEquals("SHGPUCA---D----", text(saved, "sidc"))
        assertEquals("A/1-171", text(saved, "uniqueDesignation"))
        assertEquals("DOG 6", text(saved, "callsign"))
        assertEquals(text(before, "lat"), text(saved, "lat"))
        assertEquals(2, r.state.undoDepth)

        r.model.undo()
        advanceUntilIdle()
        assertEquals("SFGPUCI--------", text(r.graphic(ref), "sidc"))
    }

    @Test
    fun `the builder cannot be applied to what is not a unit`() = runTest(dispatcher) {
        val r = opened()
        r.model.place(GraphicKind.PZ_MARKER, crosshair)
        advanceUntilIdle()
        r.model.updateUnit(UnitDraft())
        advanceUntilIdle()
        assertEquals("That cannot be done to this graphic.", r.state.error)
        assertEquals(1, r.state.undoDepth)
    }

    @Test
    fun `a unit can be moved, turned down for a heading, and deleted like any graphic`() = runTest(dispatcher) {
        val r = opened()
        r.model.addUnit(UnitDraft(), crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        val before = GraphicEdits.position("units", r.graphic(ref))!!
        r.model.nudge(northFt = 100.0, eastFt = 0.0)
        advanceUntilIdle()
        assertEquals(100.0 * 0.3048, GraphicEdits.metresBetween(before, GraphicEdits.position("units", r.graphic(ref))!!).first, 0.01)

        r.model.rotateBy(90.0)
        advanceUntilIdle()
        assertEquals("That cannot be done to this graphic.", r.state.error)

        r.model.delete()
        advanceUntilIdle()
        assertTrue(r.state.rows.isEmpty())
        r.model.undo()
        advanceUntilIdle()
        assertEquals(1, r.state.rows.size)
    }

    @Test
    fun `an older unit that was an image is listed as one, and applying the builder makes it a symbol`() = runTest(dispatcher) {
        val r = opened()
        val old = JsonObject(mapOf("id" to JsonPrimitive("old"), "type" to JsonPrimitive("tank"), "path" to JsonPrimitive("/units/tank.svg"), "lat" to JsonPrimitive(34.78), "lon" to JsonPrimitive(-84.08)))
        r.session.setQuietly { DiagramOps.upsertGraphic(it, "units", old) }
        advanceUntilIdle()
        val row = r.state.rows.single()
        assertEquals("Unit (image)", row.title)
        assertNull(row.detail)
        r.model.select(row.ref)
        advanceUntilIdle()
        assertEquals(UnitDraft(), r.state.inspector!!.unit)                                 // the builder starts from its defaults

        r.model.updateUnit(UnitDraft("F", "UCA---", "-", "TANK", ""))
        advanceUntilIdle()
        val saved = r.graphic(row.ref)
        assertEquals("SFGPUCA--------", text(saved, "sidc"))
        assertEquals("/units/tank.svg", text(saved, "path"))                                // what the unit already had is kept
        assertEquals("Unit · TANK", r.state.rows.single().title)
    }
// -- The mission aircraft --------------------------------------------------------------------------------------------

    private val blackHawk = app.ezpztac.model.AircraftProfile()
    private val chinook = app.ezpztac.model.AircraftProfile(id = 2, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29, rotorTipClearanceM = 75.0)

    @Test
    fun `an aircraft is placed as the mission aircraft, and keeps that when the mission aircraft changes`() = runTest(dispatcher) {
        val r = opened(fleet = listOf(blackHawk, chinook))
        r.aircraft.select("ch47f")
        advanceUntilIdle()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val first = r.state.rows.single()
        assertEquals("Helicopter · CH-47F", first.title)
        assertEquals("ch47f", text(r.graphic(first.ref), "profileId"))

        r.aircraft.select("uh60l")
        advanceUntilIdle()
        r.model.place(GraphicKind.HELICOPTER, GraphicEdits.offset(crosshair, 300.0, 0.0))
        advanceUntilIdle()
        assertEquals(listOf("Helicopter · CH-47F", "Helicopter · UH-60L"), r.state.rows.map { it.title })     // the first keeps what it was placed as
        assertEquals("uh60l", text(r.graphic(r.state.rows[1].ref), "profileId"))
    }

    @Test
    fun `separation is measured with each aircraft's own airframe`() = runTest(dispatcher) {
        val r = opened(fleet = listOf(blackHawk, chinook))
        r.aircraft.select("ch47f")
        advanceUntilIdle()
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val big = r.state.rows.single().ref
        val bigAt = GraphicEdits.position("helicopters", r.graphic(big))!!
        r.aircraft.select("uh60l")
        advanceUntilIdle()
        r.model.place(GraphicKind.HELICOPTER, crosshair)                                   // placed clear of the Chinook, wherever that is
        advanceUntilIdle()

        // 80 m from the Chinook's middle: the rotor edges are 62.7 m apart, short of the 75 m a CH-47F asks for, so there is an alert...
        r.model.moveToCrosshair(GraphicEdits.offset(bigAt, 80.0, 0.0))
        advanceUntilIdle()
        assertEquals(1, r.state.alerts.size)
        assertTrue(r.state.alerts.single().contains("75 m") || r.state.alerts.single().contains("246 ft"))

        // ...and 100 m away it is clear.
        r.model.moveToCrosshair(GraphicEdits.offset(bigAt, 100.0, 0.0))
        advanceUntilIdle()
        assertTrue(r.state.alerts.isEmpty())
    }

    @Test
    fun `two UH-60Ls the same 80 m apart are clear, because their clearance is 60 m`() = runTest(dispatcher) {
        val r = opened(fleet = listOf(blackHawk, chinook))
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val first = GraphicEdits.position("helicopters", r.graphic(r.state.rows.single().ref))!!
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        r.model.moveToCrosshair(GraphicEdits.offset(first, 80.0, 0.0))
        advanceUntilIdle()
        assertTrue(r.state.alerts.isEmpty())
    }

    @Test
    fun `an aircraft placed as an airframe this device does not know is shown as the mission aircraft`() = runTest(dispatcher) {
        val r = opened(fleet = listOf(blackHawk, chinook))
        r.model.place(GraphicKind.HELICOPTER, crosshair)
        advanceUntilIdle()
        val ref = r.state.rows.single().ref
        r.session.setQuietly { DiagramOps.patchGraphic(it, "helicopters", r.graphic(ref)["id"], JsonObject(mapOf("profileId" to JsonPrimitive("gone-from-the-list")))) }
        advanceUntilIdle()
        assertEquals("Helicopter · UH-60L", r.state.rows.single().title)
    }
}
