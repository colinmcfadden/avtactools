package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSelectionTest {
    @Test
    fun `nothing is held to begin with`() {
        assertNull(RouteSelection().held.value)
    }

    @Test
    fun `a route is held with no point, or with one`() {
        val s = RouteSelection()
        s.select("r1")
        assertEquals(RouteHeld("r1", null), s.held.value)
        s.select("r2", "p3")
        assertEquals(RouteHeld("r2", "p3"), s.held.value)
    }

    @Test
    fun `a point is held on the route being worked on, and put down again`() {
        val s = RouteSelection()
        s.select("r1")
        s.holdPoint("p2")
        assertEquals(RouteHeld("r1", "p2"), s.held.value)
        s.releasePoint()
        assertEquals(RouteHeld("r1", null), s.held.value)
    }

    @Test
    fun `a point cannot be held with no route`() {
        val s = RouteSelection()
        s.holdPoint("p2")
        assertNull(s.held.value)
        s.releasePoint()
        assertNull(s.held.value)
    }

    @Test
    fun `clearing puts everything down`() {
        val s = RouteSelection()
        s.select("r1", "p1")
        s.clear()
        assertNull(s.held.value)
        s.clear()
        assertNull(s.held.value)
    }
}
