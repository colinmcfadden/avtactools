package app.ezpztac.data

import app.ezpztac.geo.MapZoom
import app.ezpztac.model.LatLon
import app.ezpztac.planning.MissionRoutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapFocusTest {
    @Test
    fun `a request reaches whoever is listening`() = runTest(UnconfinedTestDispatcher()) {
        val focus = MapFocus()
        val seen = mutableListOf<MapFocus.Request>()
        val job = launch { focus.requests.collect { seen += it } }
        focus.show(LatLon(34.0, -84.0), 12.0)
        assertEquals(listOf(MapFocus.Request(LatLon(34.0, -84.0), 12.0)), seen)
        job.cancel()
    }

    @Test
    fun `a request nobody is listening for is not kept for later`() = runTest(UnconfinedTestDispatcher()) {
        val focus = MapFocus()
        focus.show(LatLon(34.0, -84.0), 12.0)
        val seen = mutableListOf<MapFocus.Request>()
        val job = launch { focus.requests.collect { seen += it } }
        assertTrue(seen.isEmpty())
        job.cancel()
    }

    @Test
    fun `an extent is shown at the zoom that fits it`() = runTest(UnconfinedTestDispatcher()) {
        val focus = MapFocus()
        val seen = mutableListOf<MapFocus.Request>()
        val job = launch { focus.requests.collect { seen += it } }
        focus.show(MissionRoutes.Extent(LatLon(34.0, -84.0), 20_000.0))
        assertEquals(MapZoom.fitting(34.0, 20_000.0), seen.single().zoom, 0.0)
        job.cancel()
    }
}
