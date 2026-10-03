package app.ezpztac.planning

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.Wind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanDraftTest {
    private fun valid(d: PlanDraft) = (d.check() as PlanDraft.Checked.Valid).patch
    private fun refused(d: PlanDraft) = (d.check() as PlanDraft.Checked.Refused).message

    @Test
    fun `a plan is shown as it is, in plain numbers`() {
        val plan = RoutePlan(
            airspeed = Airspeed(120.5, Airspeed.TYPE_INDICATED), altitude = AltitudeSetting(1500.0, AltitudeSetting.REF_MSL),
            wind = Wind(270.0, 15.0), tempC = -5.5, fuelFlowLbHr = 2400.0, date = "2026-10-03",
        )
        assertEquals(
            PlanDraft(
                date = "2026-10-03", tempC = "-5.5", fuelFlowLbHr = "2400", airspeedValue = "120.5", airspeedType = "indicated",
                altitudeValue = "1500", altitudeRef = "msl", windDir = "270", windSpeed = "15",
            ),
            PlanDraft.of(plan),
        )
    }

    @Test
    fun `a value the plan does not hold takes the web's default`() {
        val draft = PlanDraft.of(RoutePlan(wind = null, tempC = null, fuelFlowLbHr = null))
        assertEquals("15", draft.tempC)
        assertEquals("960", draft.fuelFlowLbHr)
        assertEquals("0", draft.windDir)
        assertEquals("0", draft.windSpeed)
    }

    @Test
    fun `a plan shown and applied unchanged is the same plan`() {
        val plan = RoutePlan(airspeed = Airspeed(98.7, Airspeed.TYPE_TRUE), altitude = AltitudeSetting(-120.0, AltitudeSetting.REF_MSL), wind = Wind(45.0, 3.5), tempC = 12.25, fuelFlowLbHr = 1111.0, date = "2027-01-02")
        val patch = valid(PlanDraft.of(plan))
        assertEquals(plan.airspeed, patch.airspeed)
        assertEquals(plan.altitude, patch.altitude)
        assertEquals(plan.wind, patch.wind)
        assertEquals(plan.tempC, patch.tempC)
        assertEquals(plan.fuelFlowLbHr, patch.fuelFlowLbHr)
        assertEquals(plan.date, patch.date)
    }

    @Test
    fun `typed numbers are read, with a sign and a point, and nothing else`() {
        val patch = valid(PlanDraft(airspeedValue = " 120. ", altitudeValue = "+300", windDir = ".5", tempC = "-3"))
        assertEquals(120.0, patch.airspeed!!.value)
        assertEquals(300.0, patch.altitude!!.value)
        assertEquals(0.5, patch.wind!!.dirTrue)
        assertEquals(-3.0, patch.tempC)
        for (bad in listOf("12 kt", "1e3", "1,5", "--1", "0x10", "٣")) assertEquals("Airspeed is not a number.", refused(PlanDraft(airspeedValue = bad)), bad)
        assertEquals("Airspeed needs a number.", refused(PlanDraft(airspeedValue = "")))
        assertEquals("Airspeed needs a number.", refused(PlanDraft(airspeedValue = "   ")))
    }

    @Test
    fun `each number is held to its range, at both ends`() {
        assertEquals("Airspeed must be between 0 and 400.", refused(PlanDraft(airspeedValue = "-1")))
        assertEquals("Airspeed must be between 0 and 400.", refused(PlanDraft(airspeedValue = "400.1")))
        assertEquals(0.0, valid(PlanDraft(airspeedValue = "0")).airspeed!!.value)
        assertEquals(400.0, valid(PlanDraft(airspeedValue = "400")).airspeed!!.value)
        assertEquals("Altitude must be between -2000 and 30000.", refused(PlanDraft(altitudeValue = "30001")))
        assertEquals("Altitude must be between -2000 and 30000.", refused(PlanDraft(altitudeValue = "-2001")))
        assertEquals("Wind direction must be between 0 and 360.", refused(PlanDraft(windDir = "361")))
        assertEquals("Wind direction must be between 0 and 360.", refused(PlanDraft(windDir = "-1")))
        assertEquals(360.0, valid(PlanDraft(windDir = "360")).wind!!.dirTrue)
        assertEquals("Wind speed must be between 0 and 200.", refused(PlanDraft(windSpeed = "-0.5")))
        assertEquals("Temperature must be between -100 and 100.", refused(PlanDraft(tempC = "101")))
        assertEquals("Fuel flow must be between 0 and 20000.", refused(PlanDraft(fuelFlowLbHr = "20001")))
        assertEquals(0.0, valid(PlanDraft(fuelFlowLbHr = "0")).fuelFlowLbHr)
    }

    @Test
    fun `the first field that is wrong is the one named, in the order the form is read`() {
        assertEquals("Airspeed is not a number.", refused(PlanDraft(airspeedValue = "x", altitudeValue = "y", windDir = "z")))
        assertEquals("Altitude is not a number.", refused(PlanDraft(altitudeValue = "y", windDir = "z")))
        assertEquals("Wind direction is not a number.", refused(PlanDraft(windDir = "z", windSpeed = "q", tempC = "t")))
        assertEquals("Wind speed is not a number.", refused(PlanDraft(windSpeed = "q", tempC = "t")))
        assertEquals("Temperature is not a number.", refused(PlanDraft(tempC = "t", fuelFlowLbHr = "f")))
        assertEquals("Fuel flow is not a number.", refused(PlanDraft(fuelFlowLbHr = "f")))
    }

    @Test
    fun `the references must be ones the planner knows`() {
        assertEquals("Airspeed reference must be ground, indicated or true.", refused(PlanDraft(airspeedType = "mach")))
        assertEquals("Altitude reference must be AGL or MSL.", refused(PlanDraft(altitudeRef = "feet")))
        for (type in PlanDraft.AIRSPEED_TYPES) assertEquals(type, valid(PlanDraft(airspeedType = type)).airspeed!!.type)
        for (ref in PlanDraft.ALTITUDE_REFS) assertEquals(ref, valid(PlanDraft(altitudeRef = ref)).altitude!!.ref)
    }

    @Test
    fun `the date is a day or nothing`() {
        assertEquals("", valid(PlanDraft(date = "")).date)
        assertEquals("", valid(PlanDraft(date = "   ")).date)
        assertEquals("2026-10-03", valid(PlanDraft(date = " 2026-10-03 ")).date)
        for (bad in listOf("2026-13-01", "2026-02-30", "26-10-03", "2026/10/03", "next tuesday", "2026-1-3")) {
            assertEquals("The date must be written year-month-day, like 2026-10-03.", refused(PlanDraft(date = bad)), bad)
        }
        assertEquals("2028-02-29", valid(PlanDraft(date = "2028-02-29")).date)                 // a leap day is a day
    }

    @Test
    fun `nothing is applied when anything is refused`() {
        assertTrue(PlanDraft(windSpeed = "x").check() is PlanDraft.Checked.Refused)
        assertNull((PlanDraft(windSpeed = "x").check() as? PlanDraft.Checked.Valid)?.patch)
    }

    @Test
    fun `an override that a plan has makes no difference to what the route-wide form shows`() {
        val plan = RoutePlan(perPoint = mapOf("p2" to PointOverride(altitude = AltitudeSetting(999.0, AltitudeSetting.REF_MSL))))
        assertEquals(PlanDraft.of(RoutePlan()), PlanDraft.of(plan))
    }
}
