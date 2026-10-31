package app.ezpztac.model

import app.ezpztac.model.DiagramNormalizer.Environment
import app.ezpztac.model.DiagramNormalizer.Options
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Held to contracts/fixtures/workspace/diagram.json, which records what the web's
 * useLzWorkspace.js makes of each saved shape. The native apps must open every
 * diagram the web has ever saved, and write ones the web opens.
 */
class DiagramFixtureTest {
    private val fixture = Fixtures.load("workspace/diagram.json")
    private val json = Json { encodeDefaults = true }

    // The fixtures always supply ids and timestamps; a normalizer that reaches for
    // the clock or a random id here is doing something the web would do differently.
    private val env = Environment(
        now = { error("the fixture should supply timestamps") },
        newId = { error("the fixture should supply an id") },
    )

    private fun cases(key: String) = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun name(c: JsonObject) = c["name"]!!.jsonPrimitive.content

    private fun options(element: JsonElement?): Options {
        val o = element as? JsonObject ?: return Options()
        fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return Options(
            id = text("id"),
            savedId = o["savedId"],
            name = text("name"),
            dirty = (o["dirty"] as? JsonPrimitive)?.booleanOrNull,
            createdAt = text("createdAt"),
            updatedAt = text("updatedAt"),
        )
    }

    private fun check(label: String, expected: JsonElement, actual: JsonElement) {
        val differences = JsonCompare.differences(expected, actual)
        assertEquals(emptyList<String>(), differences.take(10), label)
    }

    @Test
    fun `every saved shape normalizes the way the web does`() {
        val all = cases("normalize")
        assertTrue(all.size >= 20, "the fixture shrank to ${all.size}")
        all.forEach { c ->
            val actual = DiagramNormalizer.normalize(c["source"], options(c["options"]), env)
            check(name(c), c["expected"]!!, json.encodeToJsonElement(actual))
        }
    }

    @Test
    fun `a legacy snapshot or API record becomes one diagram`() {
        cases("legacySnapshot").forEach { c ->
            val actual = DiagramNormalizer.normalizeLegacySnapshot(c["snapshot"], options(c["options"]), env)
            check(name(c), c["expected"]!!, json.encodeToJsonElement(actual))
        }
    }

    @Test
    fun `a blank diagram bound to a target`() {
        cases("fromTarget").forEach { c ->
            val a = c["args"]!!.jsonObject
            val actual = DiagramNormalizer.fromTarget(
                target = a["target"],
                mgrs = a["mgrs"]?.jsonPrimitive?.contentOrNull ?: "",
                id = a["id"]?.jsonPrimitive?.contentOrNull,
                name = a["name"]?.jsonPrimitive?.contentOrNull ?: "",
                savedId = a["savedId"],
                mapData = a["mapData"] as? JsonObject ?: JsonObject(emptyMap()),
                view = a["view"] as? JsonObject,
                createdAt = a["createdAt"]?.jsonPrimitive?.contentOrNull,
                env = env,
            )
            val expected = c["expected"]!!
            if (expected is JsonNull) assertEquals(null, actual, name(c))
            else check(name(c), expected, json.encodeToJsonElement(requireNotNull(actual) { name(c) }))
        }
    }

    @Test
    fun `saving strips the terrain raster and the dirty flag`() {
        cases("serialize").forEach { c ->
            val input = c["diagram"]!!
            val includeTerrain = (c["options"] as? JsonObject)?.get("includeTerrainData")?.jsonPrimitive?.boolean ?: false
            val expected = c["expected"]!!
            if (input is JsonNull) {
                assertEquals(JsonNull, expected, name(c))
            } else {
                val diagram = DiagramNormalizer.normalize(input, env = env)
                check(name(c), expected, json.encodeToJsonElement(DiagramNormalizer.serialize(diagram, includeTerrain)))
            }
        }
    }

    @Test
    fun `a target is read from an array or an object, and a blank grid stays as given`() {
        cases("target").forEach { c ->
            val actual = DiagramNormalizer.normalizeTarget(c["target"], c["mgrs"])
            val expected = c["expected"]!!
            val label = "${c["target"]} / ${c["mgrs"]}"
            if (expected is JsonNull) assertEquals(null, actual, label)
            else check(label, expected, json.encodeToJsonElement(requireNotNull(actual) { label }))
        }
    }

    @Test
    fun `what a diagram may be used for follows its target and status`() {
        cases("capabilities").forEach { c ->
            val d = c["diagram"]!!
            if (d is JsonNull) {
                assertFalse(c["canAnalyze"]!!.jsonPrimitive.boolean, name(c))
                return@forEach
            }
            val diagram = DiagramNormalizer.normalize(
                JsonObject(mapOf("id" to JsonPrimitive("x"), "createdAt" to JsonPrimitive("t"), "target" to d.jsonObject["target"]!!, "status" to d.jsonObject["status"]!!)),
                env = env,
            )
            // normalize derives status from the target and analysis, so for these inputs
            // (no analysis) "analyzed" stands only because it was asked for.
            assertEquals(c["canAnalyze"]!!.jsonPrimitive.boolean, diagram.canAnalyze, name(c))
            assertEquals(c["canEditGraphics"]!!.jsonPrimitive.boolean, diagram.canEditGraphics, name(c))
        }
    }

    @Test
    fun `a session starts from nothing, a list, a workspace or a single snapshot`() {
        val all = cases("workspace")
        assertTrue(all.size >= 10)
        all.forEach { c ->
            val actual = DiagramNormalizer.createWorkspace(c["source"].takeIf { it !is JsonNull }, env)
            check(name(c), c["expected"]!!, json.encodeToJsonElement(actual))
        }
    }

    @Test
    fun `a diagram survives the trip through the model unchanged`() {
        // The web must be able to open what the app saves: encode a normalized diagram
        // and normalize it again, and nothing may move.
        cases("normalize").forEach { c ->
            val first = DiagramNormalizer.normalize(c["source"], options(c["options"]), env)
            val again = DiagramNormalizer.normalize(json.encodeToJsonElement(first), env = env)
            assertEquals(first, again, name(c))
        }
    }

    @Test
    fun `fields a newer web release adds to a graphic are kept`() {
        val c = cases("normalize").first { name(it).startsWith("a full current diagram") }
        val diagram = DiagramNormalizer.normalize(c["source"], env = env)
        val helicopter = diagram.graphics.helicopters.single().jsonObject
        assertTrue("futureField" in helicopter, "an unknown field on a helicopter was dropped")
        val reencoded = json.encodeToJsonElement(diagram).jsonObject["graphics"]!!.jsonObject["helicopters"]!!.jsonArray.single().jsonObject
        assertEquals(helicopter, reencoded)
    }
}
