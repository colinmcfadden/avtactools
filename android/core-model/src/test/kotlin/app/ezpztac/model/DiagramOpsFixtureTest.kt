package app.ezpztac.model

import app.ezpztac.model.DiagramNormalizer.Environment
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Held to contracts/fixtures/workspace/ops.json: what the web's reducer does to one diagram when it is analysed, its graphics are
 * edited, or its view or name change, including every case where it refuses. `updatedAt` and the dirty flag are the web's clock and
 * bookkeeping, and the native stores do those differently, so they are not compared.
 */
class DiagramOpsFixtureTest {
    private val fixture = Fixtures.load("workspace/ops.json")
    private val json = Json { encodeDefaults = true }
    private val env = Environment(now = { error("the fixture supplies timestamps") }, newId = { error("the fixture supplies an id") })

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun name(c: JsonObject) = c.getValue("name").jsonPrimitive.content

    private fun read(diagram: JsonElement): Diagram = DiagramNormalizer.normalize(diagram, env = env)

    /** The diagram as the fixture writes it: without the two fields the web stamps itself. */
    private fun written(diagram: Diagram): JsonElement {
        val all = json.encodeToJsonElement(diagram).jsonObject
        return JsonObject(all - "updatedAt" - "dirty")
    }

    private fun check(label: String, expected: JsonElement, actual: JsonElement) {
        assertEquals(emptyList<String>(), JsonCompare.differences(expected, actual).take(10), label)
    }

    private fun run(diagram: Diagram, op: String, args: JsonObject): Diagram {
        val collection = args["collection"]?.jsonPrimitive?.content ?: ""
        return when (op) {
            "completeAnalysis" -> DiagramOps.completeAnalysis(diagram, args["analysis"])
            "resetAnalysis" -> DiagramOps.resetAnalysis(diagram)
            "setAnalysisDraft" -> DiagramOps.setAnalysisDraft(diagram, args["analysis"])
            "setRuntimeTerrainData" -> DiagramOps.setRuntimeTerrainData(diagram, args["terrainData"] ?: JsonNull)
            "setGraphics" -> DiagramOps.setGraphics(diagram, args["graphics"])
            "setGraphicCollection" -> DiagramOps.setGraphicCollection(diagram, collection, args["items"])
            "upsertGraphic" -> DiagramOps.upsertGraphic(diagram, collection, args.getValue("item"))
            "patchGraphic" -> DiagramOps.patchGraphic(diagram, collection, args["id"], args["patch"])
            "removeGraphic" -> DiagramOps.removeGraphic(diagram, collection, args["id"])
            "setFlightData" -> DiagramOps.setFlightData(diagram, args["flightData"])
            "setView" -> DiagramOps.setView(diagram, args["view"])
            "setName" -> DiagramOps.setName(diagram, (args["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)
            else -> error("the fixture has an operation this test does not know: $op")
        }
    }

    @Test
    fun `every operation does what the web's reducer does`() {
        val all = cases("ops")
        assertTrue(all.size >= 40, "the fixture shrank to ${all.size}")
        for (c in all) {
            val start = read(c.getValue("diagram"))
            check("${name(c)} (the starting diagram)", c.getValue("diagram"), written(start))
            val result = run(start, c.getValue("op").jsonPrimitive.content, c.getValue("args").jsonObject)
            check(name(c), c.getValue("expected"), written(result))
        }
    }

    @Test
    fun `a refused operation hands back the very diagram it was given`() {
        val refused = cases("ops").filter { it.getValue("diagram") == it.getValue("expected") }
        assertTrue(refused.size >= 8)
        for (c in refused) {
            val start = read(c.getValue("diagram"))
            val result = run(start, c.getValue("op").jsonPrimitive.content, c.getValue("args").jsonObject)
            assertEquals(start, result, name(c))
        }
    }

    @Test
    fun `the standard doghouses are made as the web makes them`() {
        for (c in cases("defaultDoghouses")) {
            val namespace = c.getValue("namespace")
            val actual = DiagramOps.defaultDoghouses(c.getValue("target"), namespace.takeIf { it !is JsonNull })
            check(name(c), c.getValue("expected"), JsonArray(actual))
        }
    }

    @Test
    fun `analysis makes the doghouses once`() {
        for (c in cases("afterAnalysis")) {
            val actual = DiagramOps.afterAnalysis(read(c.getValue("diagram")), c.getValue("analysis"))
            check(name(c), c.getValue("expected"), written(actual))
        }
    }

    @Test
    fun `the convenience form of the doghouses is the same as the general one`() {
        val general = DiagramOps.defaultDoghouses(JsonArray(listOf(JsonPrimitive(34.5), JsonPrimitive(-84.1))), JsonPrimitive("abc"))
        assertEquals(general, DiagramOps.defaultDoghouses(34.5, -84.1, "abc"))
        assertEquals(2, general.size)
    }
}
