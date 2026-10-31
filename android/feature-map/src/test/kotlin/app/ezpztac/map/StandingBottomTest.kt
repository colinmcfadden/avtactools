package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the readout and what floats above it stand: on the sheet's edge, or on the keyboard's top where that is higher, so a field of the held bar is not under the keyboard. */
class StandingBottomTest {
    @Test
    fun `with no keyboard it stands on the sheet's edge, or at the foot with none`() {
        assertEquals(1500f, standingBottom(1500f, 2400, 0), 0f)
        assertEquals(2400f, standingBottom(null, 2400, 0), 0f)
    }

    @Test
    fun `a keyboard higher than the sheet's edge is stood on, a lower one is not`() {
        assertEquals("the keyboard's top, 1000 px up from the foot", 1400f, standingBottom(2000f, 2400, 1000), 0f)
        assertEquals("the sheet's edge is higher still", 1000f, standingBottom(1000f, 2400, 1000), 0f)
        assertEquals(1400f, standingBottom(null, 2400, 1000), 0f)
    }
}
