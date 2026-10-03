package app.ezpztac.planning

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.Wind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointDraftTest {
    private val plan = RoutePlan(
        airspeed = Airspeed(100.0, Airspeed.TYPE_GROUND), altitude = AltitudeSetting(50.0, AltitudeSetting.REF_AGL), wind = Wind(90.0, 10.0),
        perPoint = mapOf(
            "p2" to PointOverride(altitude = AltitudeSetting(200.0, AltitudeSetting.REF_MSL), airspeed = Airspeed(90.0, Airspeed.TYPE_INDICATED)),
            "p3" to PointOverride(wind = Wind(270.0, 15.0), clock = "12:30:00"),
        ),
    )

    private fun valid(d: PointDraft, before: PointDraft, first: Boolean = false) = d.check(before, first) as PointDraft.Checked.Valid
    private fun refused(d: PointDraft, before: PointDraft, first: Boolean = false) = (d.check(before, first) as PointDraft.Checked.Refused).message

    @Test
    fun `a point with no override shows the route's values, and one with some shows its own`() {
        assertEquals(PointDraft("50", "agl", "100", "ground", "90", "10"), PointDraft.of(plan, "p1"))
        assertEquals(PointDraft("200", "msl", "90", "indicated", "90", "10"), PointDraft.of(plan, "p2"))                    // altitude and speed its own, the wind the route's
        assertEquals(PointDraft("50", "agl", "100", "ground", "270", "15", clock = "12:30:00"), PointDraft.of(plan, "p3"))
        assertEquals(PointDraft.of(plan, "p1"), PointDraft.of(plan, "unknown"))
    }

    @Test
    fun `a plan with no wind shows no wind`() {
        val none = PointDraft.of(plan.copy(wind = null), "p1")
        assertEquals("0", none.windDir)
        assertEquals("0", none.windSpeed)
    }

    @Test
    fun `looking at a point and applying changes nothing`() {
        for (id in listOf("p1", "p2", "p3")) {
            val shown = PointDraft.of(plan, id)
            val checked = valid(shown, shown)
            assertTrue(checked.isEmpty, id)
        }
    }

    @Test
    fun `only the group that was changed is applied, and its other half goes with it`() {
        val before = PointDraft.of(plan, "p1")
        val altitude = valid(before.copy(altitudeValue = "300"), before)
        assertEquals(AltitudeSetting(300.0, AltitudeSetting.REF_AGL), altitude.patch.altitude)                              // the reference as it was shown
        assertNull(altitude.patch.airspeed)
        assertNull(altitude.patch.wind)
        assertNull(altitude.clock)

        val reference = valid(before.copy(altitudeRef = "msl"), before)
        assertEquals(AltitudeSetting(50.0, AltitudeSetting.REF_MSL), reference.patch.altitude)

        val speed = valid(before.copy(speedType = "true"), before)
        assertEquals(Airspeed(100.0, "true"), speed.patch.airspeed)
        assertNull(speed.patch.altitude)

        val wind = valid(before.copy(windSpeed = "20"), before)
        assertEquals(Wind(90.0, 20.0), wind.patch.wind)                                                                     // the direction as it was shown
        assertNull(wind.patch.altitude)
    }

    @Test
    fun `typing the same number differently is a change only when the text differs`() {
        val before = PointDraft.of(plan, "p1")
        assertEquals(50.0, valid(before.copy(altitudeValue = "50.0"), before).patch.altitude!!.value)                        // typed again, so it is applied
        assertNull(valid(before.copy(altitudeValue = "50"), before).patch.altitude)
    }

    @Test
    fun `the first point has no arriving leg, so its speed and wind are not read`() {
        val before = PointDraft.of(plan, "p1")
        val typed = before.copy(altitudeValue = "100", speedValue = "banana", windDir = "banana", windSpeed = "-9")
        val checked = valid(typed, before, first = true)
        assertEquals(AltitudeSetting(100.0, AltitudeSetting.REF_AGL), checked.patch.altitude)
        assertNull(checked.patch.airspeed)
        assertNull(checked.patch.wind)
    }

    @Test
    fun `a number that cannot be read, or is out of range, is refused with its name, and nothing else is applied`() {
        val before = PointDraft.of(plan, "p2")
        assertEquals("Altitude is not a number.", refused(before.copy(altitudeValue = "high"), before))
        assertEquals("Altitude must be between -2000 and 30000.", refused(before.copy(altitudeValue = "99999"), before))
        assertEquals("Airspeed is not a number.", refused(before.copy(speedValue = "fast"), before))
        assertEquals("Airspeed must be between 0 and 400.", refused(before.copy(speedValue = "-5"), before))
        assertEquals("Wind direction must be between 0 and 360.", refused(before.copy(windDir = "400"), before))
        assertEquals("Wind speed needs a number.", refused(before.copy(windSpeed = ""), before.copy(windSpeed = "1")))
        assertEquals("Altitude reference must be AGL or MSL.", refused(before.copy(altitudeRef = "x"), before))
        assertEquals("Airspeed reference must be ground, indicated or true.", refused(before.copy(speedType = "x"), before))
    }

    @Test
    fun `a clock is a time of day, and blank clears it`() {
        val before = PointDraft.of(plan, "p1")
        for (good in listOf("09:15", "9:15", "09:15:30", "23:59:59", "0:00", "00:00:00")) assertEquals(good, valid(before.copy(clock = good), before).clock, good)
        assertEquals("12:30", valid(before.copy(clock = "  12:30 "), before).clock)                                          // trimmed
        for (bad in listOf("24:00", "12:60", "12:5", "1230", "12:30:60", "noon", "12.30", "-1:00")) {
            assertEquals("The time must be written hours:minutes, like 09:15 or 09:15:30.", refused(before.copy(clock = bad), before), bad)
        }
        val clocked = PointDraft.of(plan, "p3")
        assertEquals("", valid(clocked.copy(clock = ""), clocked).clock)                                                      // cleared: blank is the change
        assertNull(valid(clocked.copy(clock = " 12:30:00 "), clocked).clock)                                                  // the same time, with spaces: no change
        assertFalse(valid(clocked.copy(clock = "13:00"), clocked).isEmpty)
    }

    @Test
    fun `a clock can be set together with the values of the same point`() {
        val before = PointDraft.of(plan, "p1")
        val checked = valid(before.copy(altitudeValue = "75", clock = "10:00"), before)
        assertEquals(75.0, checked.patch.altitude!!.value)
        assertEquals("10:00", checked.clock)
    }
}
