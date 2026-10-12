package app.ezpztac.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a set of routes is called before it is named, and the route plan's date as the design's field writes it. */
class RouteSetNamesTest {
    @Test
    fun `the made-up name is the web's, numbered past the ones taken`() {
        assertEquals("SKETCHED ROUTES", RouteSetNames.next(emptyList()))
        assertEquals("SKETCHED ROUTES 2", RouteSetNames.next(listOf("sketched routes")))
        assertEquals("SKETCHED ROUTES 3", RouteSetNames.next(listOf("SKETCHED ROUTES", " SKETCHED ROUTES 2 ")))
        assertEquals("SKETCHED ROUTES", RouteSetNames.next(listOf("SKETCHED ROUTES 2")))
    }

    @Test
    fun `a made-up name is told from a name of the person's own`() {
        assertTrue(RouteSetNames.isMadeUp("SKETCHED ROUTES"))
        assertTrue(RouteSetNames.isMadeUp(" sketched routes 12 "))
        assertFalse(RouteSetNames.isMadeUp("SKETCHED ROUTES NORTH"))
        assertFalse(RouteSetNames.isMadeUp("MISSION 1"))
        assertFalse(RouteSetNames.isMadeUp("ROUTE 1"))
    }

    @Test
    fun `a name is kept in capitals, and one taken is kept apart`() {
        assertEquals("NIGHT INFIL", RouteSetNames.clean("  night infil "))
        assertEquals("NIGHT INFIL 2", RouteSetNames.free("NIGHT INFIL", listOf("night infil")))
        assertEquals("NIGHT INFIL", RouteSetNames.free("NIGHT INFIL", listOf("DAY INFIL")))
    }

    @Test
    fun `the plan's date is shown month first, and blank is today`() {
        assertEquals("10/03/2026", PlanDates.display("2026-10-03"))
        assertEquals("", PlanDates.display(""))
        assertEquals("not a date", PlanDates.display(" not a date "))                     // shown as it is: never thrown away
    }

    @Test
    fun `a date typed month first or as the plan keeps it is kept as the plan keeps it, and only once it is whole and real`() {
        assertEquals("2026-10-03", PlanDates.toPlan("10/3/2026"))
        assertEquals("2026-10-03", PlanDates.toPlan(" 10/03/2026 "))
        assertEquals("2026-10-03", PlanDates.toPlan("2026-10-03"))
        assertEquals("", PlanDates.toPlan("  "))
        assertNull(PlanDates.toPlan("10/3"))
        assertNull(PlanDates.toPlan("02/30/2026"))
        assertNull(PlanDates.toPlan("13/01/2026"))
        assertNull(PlanDates.toPlan("3 Oct 2026"))
    }

    @Test
    fun `the calendar counts midnight UTC, and its answer comes back as the same day`() {
        val millis = PlanDates.millisOf("2026-10-03")!!
        assertEquals(1_790_985_600_000L, millis)
        assertEquals("2026-10-03", PlanDates.fromMillis(millis))
        assertNull(PlanDates.millisOf(""))
    }
}
