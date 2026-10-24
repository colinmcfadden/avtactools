package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether the map's view draws: started and stopped once each way as its screen comes and goes and as the 3D view covers it and goes. */
class MapRenderingTest {
    private val said = mutableListOf<String>()
    private val rendering = MapRendering({ said += "start" }, { said += "stop" })

    @Test
    fun `it draws while its screen is started and nothing covers it, and is told once each way`() {
        rendering.set(started = true, covered = false)
        rendering.set(started = true, covered = false)
        assertEquals(listOf("start"), said)
        // The 3D view opens over it, and the app goes to the back and comes again meanwhile: it is not woken under the page.
        rendering.set(started = true, covered = true)
        rendering.set(started = false, covered = true)
        rendering.set(started = true, covered = true)
        assertEquals(listOf("start", "stop"), said)
        // The page closes: it draws again; then the screen goes to the back, and the page opens while it is there.
        rendering.set(started = true, covered = false)
        rendering.set(started = false, covered = false)
        rendering.set(started = false, covered = true)
        assertEquals(listOf("start", "stop", "start", "stop"), said)
        // The view going: nothing more to say to one already stopped.
        rendering.set(started = false, covered = true)
        assertEquals(4, said.size)
    }

    @Test
    fun `a screen that was never started is never stopped`() {
        rendering.set(started = false, covered = false)
        rendering.set(started = false, covered = true)
        assertEquals(emptyList<String>(), said)
    }
}
