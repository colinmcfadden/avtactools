package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GraphicLookupTest {
    @Test
    fun `an id reads as JavaScript writes it`() {
        assertEquals("12", DiagramOps.idText(JsonPrimitive(12)))
        assertEquals("12.5", DiagramOps.idText(JsonPrimitive(12.5)))
        assertEquals("1700000000000", DiagramOps.idText(JsonPrimitive(1_700_000_000_000L)))
        assertEquals("12", DiagramOps.idText(JsonPrimitive(12.0)))
        assertEquals("pz-1", DiagramOps.idText(JsonPrimitive("pz-1")))
        assertEquals("12", DiagramOps.idText(JsonPrimitive("12")))                          // text and number with the same digits read the same
        assertEquals("true", DiagramOps.idText(JsonPrimitive(true)))
        assertEquals("null", DiagramOps.idText(JsonNull))
        assertEquals("undefined", DiagramOps.idText(null))
    }

    private fun diagram(vararg helicopters: JsonObject) = Diagram(
        id = "d", createdAt = "t", updatedAt = "t", status = DiagramStatus.ANALYZED, target = DiagramTarget(1.0, 2.0),
        graphics = DiagramGraphics(helicopters = helicopters.toList()),
    )

    @Test
    fun `a graphic is found by its id as text`() {
        val a = JsonObject(mapOf("id" to JsonPrimitive(1700000000000L), "lat" to JsonPrimitive(1)))
        val b = JsonObject(mapOf("id" to JsonPrimitive("pz-1")))
        val d = diagram(a, b)
        assertEquals(a, DiagramOps.graphic(d, "helicopters", "1700000000000"))
        assertEquals(b, DiagramOps.graphic(d, "helicopters", "pz-1"))
        assertNull(DiagramOps.graphic(d, "helicopters", "nope"))
        assertNull(DiagramOps.graphic(d, "units", "pz-1"))                                  // another collection
        assertNull(DiagramOps.graphic(d, "no such collection", "pz-1"))
    }

    @Test
    fun `items that are not objects are never found`() {
        val d = Diagram(
            id = "d", createdAt = "t", updatedAt = "t", status = DiagramStatus.ANALYZED, target = DiagramTarget(1.0, 2.0),
            graphics = DiagramGraphics(helicopters = JsonArray(listOf(JsonPrimitive(7), JsonPrimitive("x"), JsonNull)).toList()),
        )
        assertNull(DiagramOps.graphic(d, "helicopters", "7"))
        assertNull(DiagramOps.graphic(d, "helicopters", "x"))
    }
}
