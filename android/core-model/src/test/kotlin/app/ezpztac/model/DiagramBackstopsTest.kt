package app.ezpztac.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Backstops are drawn on the web alone so far; a save here must neither lose one nor add the field to a diagram that has none. */
class DiagramBackstopsTest {
    private val backstops = Json.parseToJsonElement("""[{"id": "bs-1", "points": [{"lat": 34.8, "lon": -84.09}, {"lat": 34.8, "lon": -84.07}], "later": 1}]""")

    private fun doc(graphics: String): JsonElement = Json.parseToJsonElement("""{"id": "d", "graphics": $graphics}""")

    private fun graphicsAfterASave(source: JsonElement) =
        DiagramJson.encode(DiagramNormalizer.serialize(DiagramNormalizer.normalize(source))).getValue("graphics").jsonObject

    @Test
    fun `a backstop the web drew comes through a save whole`() {
        assertEquals(backstops, graphicsAfterASave(doc("""{"backstops": $backstops}""")).getValue("backstops"))
    }

    @Test
    fun `a diagram with none has no such field`() {
        assertFalse("backstops" in graphicsAfterASave(doc("{}")))
    }

    @Test
    fun `an operation can name them, on a diagram that has none yet`() {
        assertEquals(true, "backstops" in DiagramOps.GRAPHIC_COLLECTIONS)
    }
}
