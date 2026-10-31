package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreatSelectionTest {
    @Test
    fun `a threat can be held, put down, and replaced by another`() {
        val selection = ThreatSelection()
        assertNull(selection.held.value)

        selection.select("one")
        assertEquals("one", selection.held.value)
        selection.toggle("two")
        assertEquals("two", selection.held.value)
        selection.toggle("two")
        assertNull(selection.held.value)
        selection.select("one")
        selection.clear()
        assertNull(selection.held.value)
    }
}
