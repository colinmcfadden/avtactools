package app.ezpztac.briefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which step the map was flown for (the web's `flown` ref): flown once, again only when a step with no area gets one, and noted only once the flight is asked. */
class FlownStepTest {
    @Test
    fun `a step is flown once, whatever changes under it`() {
        val flown = FlownStep()
        assertTrue(flown.wants("a", hasBounds = true))
        flown.flew("a", hasBounds = true)
        assertFalse(flown.wants("a", hasBounds = true))
        assertFalse(flown.wants("a", hasBounds = false))
        assertTrue(flown.wants("b", hasBounds = true))
    }

    @Test
    fun `a step seen with no area is flown to once it has one`() {
        val flown = FlownStep()
        flown.flew("a", hasBounds = false)
        assertFalse(flown.wants("a", hasBounds = false))
        assertTrue(flown.wants("a", hasBounds = true))
    }

    @Test
    fun `nothing is noted until a flight is asked for, so one cut short is flown again`() {
        val flown = FlownStep()
        assertTrue(flown.wants("a", hasBounds = true))
        // The flight waited for the screen and was cut short (the step's area gone and back): asked again, it flies.
        assertTrue(flown.wants("a", hasBounds = true))
        flown.flew("a", hasBounds = true)
        flown.clear()
        assertTrue(flown.wants("a", hasBounds = true))
    }
}
