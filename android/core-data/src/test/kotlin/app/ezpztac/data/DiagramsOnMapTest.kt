package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which open LZ/PZs the person hid from the map, and the one held there: this device's own, kept between launches, never more than a bounded few. */
class DiagramsOnMapTest {
    private val store = InMemoryHiddenDiagramStore()
    private val onMap = DiagramsOnMap(store)

    @Test
    fun `the eye hides one and shows it again, and the device keeps what it says`() {
        onMap.toggle("a")
        onMap.toggle("b")
        assertEquals(setOf("a", "b"), onMap.hidden.value)
        assertEquals(listOf("a", "b"), store.kept)
        onMap.toggle("a")
        assertEquals(setOf("b"), onMap.hidden.value)
        assertEquals(listOf("b"), store.kept)
        // What was kept is what the next launch starts from.
        assertEquals(setOf("b"), DiagramsOnMap(store).hidden.value)
    }

    @Test
    fun `opening one shows it, and a pack's item is hidden by its own id`() {
        onMap.setShown("pack:p:lz-1", false)
        onMap.setShown("own", false)
        onMap.shown("pack:p:lz-1")
        assertEquals(setOf("own"), onMap.hidden.value)
    }

    @Test
    fun `hiding the one held puts it down, and hiding another leaves it held`() {
        onMap.hold("a")
        onMap.setShown("b", false)
        assertEquals("a", onMap.held.value)
        onMap.setShown("a", false)
        assertNull(onMap.held.value)
    }

    @Test
    fun `the ones hidden longest ago are forgotten past the limit, and a blank id is none`() {
        repeat(DiagramsOnMap.MAX + 3) { onMap.setShown("lz-$it", false) }
        assertEquals(DiagramsOnMap.MAX, onMap.hidden.value.size)
        assertEquals("lz-3", onMap.hidden.value.first())
        onMap.setShown(" ", false)
        assertEquals(DiagramsOnMap.MAX, store.kept.size)
        assertEquals("lz-${DiagramsOnMap.MAX + 2}", store.kept.last())
    }

    @Test
    fun `what the device kept is read with blanks and repeats dropped`() {
        val read = DiagramsOnMap(InMemoryHiddenDiagramStore(listOf("a", "", "b", "a")))
        assertEquals(listOf("a", "b"), read.hidden.value.toList())
    }
}
