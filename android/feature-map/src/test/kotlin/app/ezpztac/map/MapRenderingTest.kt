package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether the map's view draws: told once each way as its screen comes and goes and as the 3D view covers it and goes. */
class MapRenderingTest {
    private val said = mutableListOf<String>()
    private val rendering = MapRendering({ said += "resume" }, { said += "pause" })

    @Test
    fun `it draws while its screen is resumed and nothing covers it, and is told once each way`() {
        rendering.set(resumed = true, covered = false)
        rendering.set(resumed = true, covered = false)
        assertEquals(listOf("resume"), said)
        // The 3D view opens over it, and the app goes to the back and comes again meanwhile: it is not woken under the page.
        rendering.set(resumed = true, covered = true)
        rendering.set(resumed = false, covered = true)
        rendering.set(resumed = true, covered = true)
        assertEquals(listOf("resume", "pause"), said)
        // The page closes: it draws again; then the screen goes to the back, and the page opens while it is there.
        rendering.set(resumed = true, covered = false)
        rendering.set(resumed = false, covered = false)
        rendering.set(resumed = false, covered = true)
        assertEquals(listOf("resume", "pause", "resume", "pause"), said)
        // The view going: nothing more to say to one already paused.
        rendering.set(resumed = false, covered = true)
        assertEquals(4, said.size)
    }

    @Test
    fun `a screen that was never resumed is never paused`() {
        rendering.set(resumed = false, covered = false)
        rendering.set(resumed = false, covered = true)
        assertEquals(emptyList<String>(), said)
    }
}
