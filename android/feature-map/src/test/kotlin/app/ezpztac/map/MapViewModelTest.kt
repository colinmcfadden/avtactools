package app.ezpztac.map

import app.ezpztac.model.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Tokens(initial: String?) : MapTokenSource {
        override val token = MutableStateFlow(initial)
    }

    private class FakeLocation(var allowed: Boolean = true) : LocationSource {
        var fixes = Channel<UserLocation>(Channel.UNLIMITED)
        var starts = 0
        var switchedOff = false
        override fun hasPermission() = allowed
        override fun updates(): Flow<UserLocation> = if (switchedOff) flow { } else flow { starts++; emitAll(fixes.consumeAsFlow()) }
    }

    private class Memory(var saved: CameraState? = null) : CameraMemory {
        val writes = mutableListOf<CameraState>()
        override fun last() = saved
        override fun save(camera: CameraState) { saved = camera; writes += camera }
    }

    private fun fix(lat: Double = 34.78, lon: Double = -84.08, accuracy: Float? = 5f) = UserLocation(LatLon(lat, lon), accuracy, null, null, 0L)

    private fun TestScope.model(token: String? = "pk.t", location: FakeLocation = FakeLocation(), memory: Memory = Memory()) =
        MapViewModel(Tokens(token), location, memory).also { advanceUntilIdle() } to location

    private fun TestScope.commandsOf(vm: MapViewModel): MutableList<MapCommand> {
        val seen = mutableListOf<MapCommand>()
        // Unconfined: the collector is subscribed the moment it is launched, so nothing emitted after this is missed.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.commands.collect { seen += it } }
        return seen
    }

    // -- Base maps ---------------------------------------------------------------------------------------

    @Test
    fun `it starts on satellite when it can, and offers all three`() = runTest(dispatcher) {
        val (vm, _) = model()
        assertEquals("satellite", vm.state.value.style.id)
        assertEquals(3, vm.state.value.styles.size)
    }

    @Test
    fun `choosing a base map is remembered for the diagram, not for the token`() = runTest(dispatcher) {
        val (vm, _) = model()
        vm.selectStyle("topo")
        assertEquals("topo", vm.state.value.style.id)
        assertEquals("topo", vm.chosenStyleId())
    }

    @Test
    fun `with no token the FAA chart is drawn, but the diagram still remembers satellite`() = runTest(dispatcher) {
        val (vm, _) = model(token = null)
        assertEquals("vfr-sectional", vm.state.value.style.id)
        assertEquals("satellite", vm.chosenStyleId())
        assertEquals(1, vm.state.value.styles.size)
    }

    @Test
    fun `opening a diagram goes to its target and brings back its base map`() = runTest(dispatcher) {
        val (vm, _) = model()
        val seen = commandsOf(vm)
        vm.showDiagram(LatLon(34.78, -84.08), "topo")
        assertEquals(listOf<MapCommand>(MapCommand.FlyTo(LatLon(34.78, -84.08), MapViewModel.DIAGRAM_ZOOM)), seen)
        assertEquals("topo", vm.state.value.style.id)
        assertEquals("topo", vm.chosenStyleId())
    }

    @Test
    fun `a diagram with no target leaves the camera, and one with no base map leaves the style`() = runTest(dispatcher) {
        val (vm, _) = model()
        vm.selectStyle("topo")
        val seen = commandsOf(vm)
        vm.showDiagram(null, null)
        assertEquals(emptyList<MapCommand>(), seen)
        assertEquals("topo", vm.state.value.style.id)
    }

    @Test
    fun `a token that arrives later brings satellite back without the person doing anything`() = runTest(dispatcher) {
        val tokens = Tokens(null)
        val vm = MapViewModel(tokens, FakeLocation(), Memory())
        advanceUntilIdle()
        assertEquals("vfr-sectional", vm.state.value.style.id)
        tokens.token.value = "pk.arrived"
        advanceUntilIdle()
        assertEquals("satellite", vm.state.value.style.id)
        assertEquals(3, vm.state.value.styles.size)
    }

    @Test
    fun `a diagram opened with its own base map is drawn on it, and an unknown one falls back`() = runTest(dispatcher) {
        val (vm, _) = model()
        vm.restoreStyle("vfr-sectional")
        assertEquals("vfr-sectional", vm.state.value.style.id)
        vm.restoreStyle("a-style-from-the-future")
        assertEquals("satellite", vm.state.value.style.id)
        assertEquals("a-style-from-the-future", vm.chosenStyleId())                          // kept, so saving does not lose it
        vm.restoreStyle(null)
        assertEquals("satellite", vm.chosenStyleId())
    }

    // -- The crosshair -------------------------------------------------------------------------------------

    @Test
    fun `the readout follows the camera, and a camera that did not move changes nothing`() = runTest(dispatcher) {
        val (vm, _) = model()
        assertNull(vm.state.value.readout)
        assertNull(vm.state.value.center)
        vm.onCamera(CameraState(LatLon(34.783817, -84.08219), 16.0))
        assertEquals("16S GD 66993 52949", vm.state.value.readout!!.mgrs)
        assertEquals(LatLon(34.783817, -84.08219), vm.state.value.center)                  // the same place, as a position, for placing a graphic
        val before = vm.state.value
        vm.onCamera(CameraState(LatLon(34.783817, -84.08219), 16.0))
        assertTrue(before === vm.state.value)
        vm.onCamera(CameraState(LatLon(34.596407, -84.128098), 16.0))
        assertEquals("16S GD 63385 32037", vm.state.value.readout!!.mgrs)
        assertEquals(LatLon(34.596407, -84.128098), vm.state.value.center)
    }

    @Test
    fun `the first launch looks at the whole country, later ones where the person left off`() = runTest(dispatcher) {
        assertEquals(MapViewModel.FIRST_LAUNCH, model().first.initialCamera)
        val remembered = CameraState(LatLon(34.78, -84.08), 16.0)
        assertEquals(remembered, model(memory = Memory(remembered)).first.initialCamera)
    }

    @Test
    fun `the camera is written down once it has been still, not on every frame of a pan`() = runTest(dispatcher) {
        val memory = Memory()
        val (vm, _) = model(memory = memory)
        for (i in 1..5) {
            vm.onCamera(CameraState(LatLon(34.0 + i * 0.001, -84.0), 16.0))
            advanceTimeBy(100)
        }
        assertTrue(memory.writes.isEmpty())
        advanceTimeBy(MapViewModel.SAVE_AFTER_STILL_MS)
        assertEquals(1, memory.writes.size)
        assertEquals(34.005, memory.writes.single().center.lat, 1e-9)                       // the last place it stopped
    }

    // -- Search ----------------------------------------------------------------------------------------------

    @Test
    fun `a grid typed in the search flies the map there`() = runTest(dispatcher) {
        val (vm, _) = model()
        val seen = commandsOf(vm)
        vm.search("16S GD 66993 52949")
        advanceUntilIdle()
        val fly = seen.single() as MapCommand.FlyTo
        assertEquals(34.783817, fly.at.lat, 1e-4)
        assertEquals(MapViewModel.SEARCH_ZOOM, fly.zoom!!, 0.0)
        assertNull(vm.state.value.searchError)
    }

    @Test
    fun `something that is not a place says so and moves nothing, until the person types again`() = runTest(dispatcher) {
        val (vm, _) = model()
        val seen = commandsOf(vm)
        vm.search("somewhere nice")
        advanceUntilIdle()
        assertTrue(seen.isEmpty())
        assertEquals("Could not read that as a grid or a coordinate.", vm.state.value.searchError)
        vm.clearSearchError()
        assertNull(vm.state.value.searchError)
        vm.search("34.5, -84.1")
        advanceUntilIdle()
        assertTrue(seen.single() is MapCommand.FlyTo)
    }

    @Test
    fun `facing north is a command to the map`() = runTest(dispatcher) {
        val (vm, _) = model()
        val seen = commandsOf(vm)
        vm.faceNorth()
        advanceUntilIdle()
        assertEquals(listOf<MapCommand>(MapCommand.FaceNorth), seen)
    }

    // -- GPS -------------------------------------------------------------------------------------------------

    @Test
    fun `the GPS button asks for permission before it starts anything`() = runTest(dispatcher) {
        val (vm, location) = model(location = FakeLocation(allowed = false))
        vm.toggleGps()
        advanceUntilIdle()
        assertEquals(GpsState.NeedsPermission, vm.state.value.gps)
        assertEquals(0, location.starts)
    }

    @Test
    fun `once allowed it searches, shows the first fix, and takes the map there once`() = runTest(dispatcher) {
        val (vm, location) = model(location = FakeLocation(allowed = false))
        val seen = commandsOf(vm)
        vm.toggleGps()
        location.allowed = true
        vm.permissionResult(true)
        advanceUntilIdle()
        assertEquals(GpsState.Searching, vm.state.value.gps)
        location.fixes.send(fix(34.78, -84.08))
        advanceUntilIdle()
        assertEquals(GpsState.Tracking(fix(34.78, -84.08)), vm.state.value.gps)
        location.fixes.send(fix(34.79, -84.09))
        advanceUntilIdle()
        assertEquals(GpsState.Tracking(fix(34.79, -84.09)), vm.state.value.gps)
        assertEquals(1, seen.filterIsInstance<MapCommand.FlyTo>().size)                      // the person moves the camera after that
    }

    @Test
    fun `a refusal of the permission is explained, not retried`() = runTest(dispatcher) {
        val (vm, location) = model(location = FakeLocation(allowed = false))
        vm.toggleGps()
        vm.permissionResult(false)
        advanceUntilIdle()
        val state = vm.state.value.gps as GpsState.Unavailable
        assertTrue(state.message.contains("not allowed"))
        assertEquals(0, location.starts)
    }

    @Test
    fun `tapping it again turns the GPS off and stops the receiver`() = runTest(dispatcher) {
        val (vm, location) = model()
        vm.toggleGps()
        advanceUntilIdle()
        location.fixes.send(fix())
        advanceUntilIdle()
        vm.toggleGps()
        advanceUntilIdle()
        assertEquals(GpsState.Off, vm.state.value.gps)
        location.fixes.trySend(fix(1.0, 1.0))                                               // a fix that arrives after it was turned off
        advanceUntilIdle()
        assertEquals(GpsState.Off, vm.state.value.gps)
    }

    @Test
    fun `location switched off on the device is said, and the button can be tried again`() = runTest(dispatcher) {
        val location = FakeLocation().apply { switchedOff = true }
        val (vm, _) = model(location = location)
        vm.toggleGps()
        advanceUntilIdle()
        assertTrue(vm.state.value.gps is GpsState.Unavailable)
        location.switchedOff = false
        vm.toggleGps()
        advanceUntilIdle()
        assertEquals(GpsState.Searching, vm.state.value.gps)
    }

    @Test
    fun `locate me centres on the fix, and does nothing without one`() = runTest(dispatcher) {
        val (vm, location) = model()
        val seen = commandsOf(vm)
        vm.locateMe()
        advanceUntilIdle()
        assertTrue(seen.isEmpty())
        vm.toggleGps()
        advanceUntilIdle()
        location.fixes.send(fix(34.7, -84.0))
        advanceUntilIdle()
        seen.clear()
        vm.locateMe()
        advanceUntilIdle()
        assertEquals(34.7, (seen.single() as MapCommand.FlyTo).at.lat, 0.0)
    }
}
