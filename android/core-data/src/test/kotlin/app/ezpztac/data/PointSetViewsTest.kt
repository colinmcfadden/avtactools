package app.ezpztac.data

import app.ezpztac.planning.RouteColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How each set of points is shown on this device: the colour it keeps and whether it is on the map. */
class PointSetViewsTest {
    private class Memory(var kept: Map<String, PointSetView> = emptyMap()) : PointSetViewStore {
        var saves = 0

        override fun load() = kept

        override fun save(views: Map<String, PointSetView>) {
            kept = views
            saves++
        }
    }

    private val palette = RouteColors.PALETTE

    @Test
    fun `a set that is not known yet is shown, in the colour it would take`() {
        val views = PointSetViews(Memory())
        assertEquals(PointSetView(palette[0], visible = true), views.viewOf("a"))
        assertTrue(views.views.value.isEmpty())                                                // asking gives nothing away
    }

    @Test
    fun `settling gives each set its own colour in order, and keeps it`() {
        val store = Memory()
        val views = PointSetViews(store)
        views.settle(listOf("a", "b", "c"))
        assertEquals(palette.take(3), listOf("a", "b", "c").map { views.views.value.getValue(it).color })
        assertEquals(views.views.value, store.kept)                                           // and it is kept for the next launch
        assertEquals(views.views.value, PointSetViews(store).views.value)
    }

    @Test
    fun `a colour does not change when another set is deleted or arrives, and a deleted set's is free for the next`() {
        val views = PointSetViews(Memory())
        views.settle(listOf("a", "b", "c"))
        views.settle(listOf("a", "c"))                                                         // b is gone
        assertEquals(palette[0], views.views.value.getValue("a").color)
        assertEquals(palette[2], views.views.value.getValue("c").color)
        assertFalse("b" in views.views.value)
        views.settle(listOf("a", "c", "d"))
        assertEquals(palette[1], views.views.value.getValue("d").color)                       // b's colour, now free
    }

    @Test
    fun `settling what already agrees changes and saves nothing`() {
        val store = Memory()
        val views = PointSetViews(store)
        views.settle(listOf("a", "b"))
        val saves = store.saves
        views.settle(listOf("a", "b"))
        assertEquals(saves, store.saves)
    }

    @Test
    fun `a set is hidden and shown again, and the choice is kept`() {
        val store = Memory()
        val views = PointSetViews(store)
        views.settle(listOf("a"))
        views.toggle("a")
        assertFalse(views.views.value.getValue("a").visible)
        assertEquals(palette[0], views.views.value.getValue("a").color)                       // hiding does not change the colour
        assertFalse(PointSetViews(store).views.value.getValue("a").visible)
        views.toggle("a")
        assertTrue(views.views.value.getValue("a").visible)
    }

    @Test
    fun `a colour can be chosen`() {
        val views = PointSetViews(Memory())
        views.settle(listOf("a", "b"))
        views.recolor("a", "#123456")
        assertEquals("#123456", views.views.value.getValue("a").color)
        assertEquals(palette[1], views.views.value.getValue("b").color)
    }

    @Test
    fun `toggling a set nobody has settled yet gives it a view first`() {
        val views = PointSetViews(Memory())
        views.toggle("late")
        assertEquals(PointSetView(palette[0], visible = false), views.views.value.getValue("late"))
    }
}
