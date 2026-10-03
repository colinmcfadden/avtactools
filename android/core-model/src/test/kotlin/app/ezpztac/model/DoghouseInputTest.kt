package app.ezpztac.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a person may type into a doghouse's fields, and what is stored: the web's formats (`03+20`, `3.5km`, `55 kts`), from input the web never checked. */
class DoghouseInputTest {
    private fun stored(field: String, value: String) = JsonObject(mapOf(field to JsonPrimitive(value)))

    @Test
    fun `a label is kept as typed, trimmed`() {
        assertEquals(stored("id_val", "[SP2]"), Doghouses.labelPatch("  [SP2] "))
        assertEquals(stored("id_val", "IP 2"), Doghouses.labelPatch("IP 2"))
        assertEquals(stored("id_val", "[1234567890]"), Doghouses.labelPatch("[1234567890]"))               // exactly as many characters as fit
    }

    @Test
    fun `a label that is blank or too long for the triangle is refused`() {
        assertNull(Doghouses.labelPatch(""))
        assertNull(Doghouses.labelPatch("   "))
        assertNull(Doghouses.labelPatch("[12345678901]"))
    }

    @Test
    fun `a time is minutes and seconds, stored with two digits each`() {
        assertEquals(stored("time", "03+20"), Doghouses.timePatch("03+20"))
        assertEquals(stored("time", "03+20"), Doghouses.timePatch("3+20"))
        assertEquals(stored("time", "03+05"), Doghouses.timePatch("3:5"))
        assertEquals(stored("time", "03+05"), Doghouses.timePatch(" 3 + 05 "))
        assertEquals(stored("time", "123+59"), Doghouses.timePatch("123+59"))
        assertEquals(stored("time", "00+00"), Doghouses.timePatch("0+0"))
    }

    @Test
    fun `a time that is not minutes and seconds is refused`() {
        for (typed in listOf("", "320", "3+", "+20", "3+60", "3+100", "a+b", "3.5+20", "-3+20", "1234+20", "3+20+10", "٣+٢٠")) {
            assertNull(Doghouses.timePatch(typed), "time \"$typed\"")
        }
    }

    @Test
    fun `a distance is kilometres, stored as the web stores it`() {
        assertEquals(stored("dist", "3.13km"), Doghouses.distancePatch("3.13"))
        assertEquals(stored("dist", "12km"), Doghouses.distancePatch(" 12 "))
        assertEquals(stored("dist", "0km"), Doghouses.distancePatch("0"))
        assertEquals(stored("dist", "12.50km"), Doghouses.distancePatch("12.50"))
    }

    @Test
    fun `what is not a distance is refused`() {
        for (typed in listOf("", "far", "3.", ".5", "-3", "3,5", "1e3", "3.1234", "12345", "3 km")) {
            assertNull(Doghouses.distancePatch(typed), "distance \"$typed\"")
        }
    }

    @Test
    fun `an airspeed is knots, stored as the web stores it`() {
        assertEquals(stored("airspeed", "55 kts"), Doghouses.airspeedPatch("55"))
        assertEquals(stored("airspeed", "120 kts"), Doghouses.airspeedPatch(" 120"))
    }

    @Test
    fun `what is not an airspeed is refused`() {
        for (typed in listOf("", "fast", "5.5", "-5", "1000", "55 kts")) {
            assertNull(Doghouses.airspeedPatch(typed), "airspeed \"$typed\"")
        }
    }

    @Test
    fun `what is stored reads back as what was typed`() {
        val doghouse = JsonObject(mapOf("id_val" to JsonPrimitive("[SP1]"), "heading" to JsonPrimitive("090°")) + Doghouses.timePatch("3:20")!! + Doghouses.distancePatch("3.5")!! + Doghouses.airspeedPatch("55")!!)
        val shown = Doghouses.display(doghouse, Doghouses.rotation(doghouse))
        assertEquals("03", shown.minutes)
        assertEquals("20", shown.seconds)
        assertEquals(3.5, shown.distance, 0.0)
        assertEquals(55.0, shown.airspeed, 0.0)
        assertEquals("090", shown.heading)
    }
}
