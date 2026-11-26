package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which threats' rings and masks this device draws: all, until the person turns one off, kept across a launch, ids only. */
class ThreatDisplayTest {
    @Test
    fun `rings and masks are on for every threat until turned off, and each is turned off and on alone`() {
        val store = InMemoryThreatDisplayStore()
        val display = ThreatDisplay(store)
        assertTrue(display.ringsOn("t1"))
        assertTrue(display.maskOn("t1"))
        display.setRings("t1", false)
        assertFalse(display.ringsOn("t1"))
        assertTrue("the mask is a choice of its own", display.maskOn("t1"))
        display.setMask("t2", false)
        assertEquals(setOf("t1"), display.ringsOff.value)
        assertEquals(setOf("t2"), display.masksOff.value)
        assertEquals(listOf("t1"), store.rings)
        assertEquals(listOf("t2"), store.masks)
        display.setRings("t1", true)
        display.setRings("t1", true)                                                     // on twice is on
        assertTrue(display.ringsOn("t1"))
        assertEquals(emptyList<String>(), store.rings)
    }

    @Test
    fun `what was turned off comes back at the next launch, the newest kept where there are too many`() {
        val many = (1..ThreatDisplay.MAX + 20).map { "t$it" }
        val display = ThreatDisplay(InMemoryThreatDisplayStore(rings = many, masks = listOf("m1")))
        assertFalse(display.ringsOn("t${ThreatDisplay.MAX + 20}"))
        assertTrue("the oldest let go of", display.ringsOn("t1"))
        assertEquals(ThreatDisplay.MAX, display.ringsOff.value.size)
        assertFalse(display.maskOn("m1"))
    }

    @Test
    fun `a store that cannot be read or written costs the choice, never the app`() {
        val broken = object : ThreatDisplayStore {
            override fun load(): Pair<List<String>, List<String>> = error("unreadable")
            override fun save(ringsOff: List<String>, masksOff: List<String>) = error("disk full")
        }
        val display = ThreatDisplay(broken)
        assertTrue(display.ringsOn("t1"))
        display.setRings("t1", false)
        assertFalse("still turned off while the app runs", display.ringsOn("t1"))
    }
}
