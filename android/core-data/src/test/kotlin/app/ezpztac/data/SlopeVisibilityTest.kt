package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slope heat map's look on this device: drawn at launch, and how opaque, kept between launches on the slider's steps. */
class SlopeVisibilityTest {
    private class Memory(var kept: Int? = null) : SlopeOpacityMemory {
        var writes = 0

        override fun slopeOpacity() = kept

        override fun setSlopeOpacity(percent: Int) {
            kept = percent
            writes++
        }
    }

    @Test
    fun `the slider runs from 10 to 100 percent in steps of 5, and starts at the web's 0,55`() {
        assertEquals(55, SlopeOpacity.DEFAULT)
        assertEquals(17, SlopeOpacity.STOPS_BETWEEN)                                                // 10, 15, … 100: nineteen stops, seventeen between the ends
        assertEquals((10..100 step 5).toList(), (0..120).map(SlopeOpacity::of).distinct())
    }

    @Test
    fun `a value is brought onto the nearest step, and within the ends`() {
        assertEquals(10, SlopeOpacity.of(12))
        assertEquals(15, SlopeOpacity.of(13))
        assertEquals(55, SlopeOpacity.of(57))
        assertEquals(60, SlopeOpacity.of(58))
        assertEquals(10, SlopeOpacity.of(0))
        assertEquals(10, SlopeOpacity.of(Int.MIN_VALUE))
        assertEquals(100, SlopeOpacity.of(98))
        assertEquals(100, SlopeOpacity.of(Int.MAX_VALUE))
    }

    @Test
    fun `drawn at launch at the default opacity when none was kept`() {
        val look = SlopeVisibility(Memory())
        assertTrue(look.shown.value)
        assertEquals(55, look.opacity.value)
    }

    @Test
    fun `an opacity set is drawn at once and kept for the next launch, and the switch is not`() {
        val memory = Memory()
        val look = SlopeVisibility(memory)
        look.toggle()
        look.setOpacity(30)
        assertEquals(30, look.opacity.value)
        assertEquals(30, memory.kept)
        val next = SlopeVisibility(memory)
        assertEquals(30, next.opacity.value)
        assertTrue("drawn again at launch", next.shown.value)
        assertFalse(look.shown.value)
    }

    @Test
    fun `a value off the steps is set and kept on the nearest one, and the same again writes nothing`() {
        val memory = Memory()
        val look = SlopeVisibility(memory)
        look.setOpacity(72)
        assertEquals(70, look.opacity.value)
        assertEquals(70, memory.kept)
        look.setOpacity(71)                                                                         // the same step
        look.setOpacity(70)
        assertEquals(1, memory.writes)
        look.setOpacity(55)                                                                         // the default, set: still kept
        assertEquals(55, memory.kept)
        assertEquals(2, memory.writes)
    }

    @Test
    fun `what another version kept off the steps or out of range is read onto them, and not written back until changed`() {
        assertEquals(10, SlopeVisibility(Memory(kept = 3)).opacity.value)
        assertEquals(100, SlopeVisibility(Memory(kept = 250)).opacity.value)
        val memory = Memory(kept = 33)
        assertEquals(35, SlopeVisibility(memory).opacity.value)
        assertEquals(0, memory.writes)
    }

    @Test
    fun `the in-memory memory keeps nothing until told`() {
        val memory = InMemorySlopeOpacity()
        assertNull(memory.slopeOpacity())
        SlopeVisibility(memory).setOpacity(40)
        assertEquals(40, memory.slopeOpacity())
    }
}
