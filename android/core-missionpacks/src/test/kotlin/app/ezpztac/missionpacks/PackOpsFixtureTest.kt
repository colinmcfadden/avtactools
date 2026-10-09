package app.ezpztac.missionpacks

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every case of `contracts/fixtures/packs/ops.json`, which the web's `packOps.js` wrote, held strictly: the status, the
 * reason, and the items afterwards with null and absent kept apart, numbers as written, and every object's keys in the
 * order JavaScript would enumerate them (a key edited keeps its place, a new one goes last).
 */
class PackOpsFixtureTest {
    private val cases = Fixtures.load("packs/ops.json").getValue("cases").jsonArray.map { it.jsonObject }

    private fun items(json: JsonElement): Map<String, PackItemState> = json.jsonObject.mapValues { (_, item) ->
        val fields = item.jsonObject
        PackItemState(
            kind = fields.getValue("kind").jsonPrimitive.content,
            name = fields.getValue("name").jsonPrimitive.content,
            data = fields.getValue("data"),
            deleted = fields.getValue("deleted").jsonPrimitive.boolean,
        )
    }

    private fun text(case: JsonObject, key: String): String? = (case[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun name(case: JsonObject): String = case.getValue("name").jsonPrimitive.content

    @TestFactory
    fun `each operation does what the web's does`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val before = items(case.getValue("items"))
            val result = PackOps.apply(before, case.getValue("op"))

            assertEquals(text(case, "status"), result.status.name.lowercase())
            assertEquals(text(case, "reason"), result.reason)

            val expected = case.getValue("expected").jsonObject
            assertEquals(expected.keys.toList(), Js.orderedKeys(result.items.keys), "the items, in order")
            expected.forEach { (uuid, item) -> assertItem(item.jsonObject, result.items.getValue(uuid), uuid) }
            if (result.status != OpStatus.APPLIED) assertSame(before, result.items, "a skipped or malformed operation gives the items back untouched")
        }
    }

    // No case has more than one item, so the replay above cannot say whether an edit leaves the other items alone. Each case
    // runs again here between two items no operation names: they must come back the very instances they were, and the
    // case's own item in its place between them (a new one last).
    @TestFactory
    fun `an edit leaves every other item as it was, and in its place`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val own = items(case.getValue("items"))
            val first = PackItemState("lz", "FIRST", Json.parseToJsonElement("""{"id": "a", "graphics": {"units": []}}"""), deleted = false)
            val last = PackItemState("pointset", "LAST", Json.parseToJsonElement("""[{"id": "z"}]"""), deleted = false)
            val before = LinkedHashMap<String, PackItemState>()
            before["filler-a"] = first
            before.putAll(own)
            before["filler-z"] = last
            val op = case.getValue("op")
            assertFalse("filler-" in op.toString(), "no operation in the fixture names a filler")

            val result = PackOps.apply(before, op)

            assertEquals(text(case, "status"), result.status.name.lowercase())
            assertEquals(text(case, "reason"), result.reason)
            assertSame(first, result.items["filler-a"])
            assertSame(last, result.items["filler-z"])
            val expected = case.getValue("expected").jsonObject
            val order = listOf("filler-a") + own.keys + "filler-z" + (expected.keys - own.keys)
            assertEquals(Js.orderedKeys(order), Js.orderedKeys(result.items.keys), "the items, in order")
            expected.forEach { (uuid, item) -> assertItem(item.jsonObject, result.items.getValue(uuid), uuid) }
            if (result.status != OpStatus.APPLIED) assertSame(before, result.items)
        }
    }

    // A path that runs into the wrong kind of value on the way. No case of ops.json reaches these, so each answer here is
    // what the web's packOps.js gives in Node for the same data and operation.
    @Test
    fun `a path through the wrong kind of value says which kind it needed`() {
        val data = Json.parseToJsonElement("""{"flightData": {"landingHeading": 90}, "graphics": {"helicopters": [{"id": "h-1"}]}}""")
        val items = mapOf("lz-1" to PackItemState("lz", "LZ", data, deleted = false))
        val element = """{"id": "e-1"}"""
        mapOf(
            // A key asked of a number, and an id asked of an object.
            """{"type": "patch", "path": ["flightData", "landingHeading", "x"], "value": {"a": 1}}""" to "not_an_object",
            """{"type": "patch", "path": ["flightData", {"id": "x"}], "value": {"a": 1}}""" to "not_an_array",
            """{"type": "set", "path": ["flightData", "landingHeading", "x"], "value": 1}""" to "not_an_object",
            """{"type": "set", "path": ["flightData", {"id": "x"}, "y"], "value": 1}""" to "not_an_array",
            // The list to add to is named by a key of a list, or by an id inside an object.
            """{"type": "upsert", "path": ["graphics", "helicopters", "x"], "value": $element}""" to "not_an_object",
            """{"type": "insert", "path": ["graphics", "helicopters", "x"], "after": null, "value": $element}""" to "not_an_object",
            """{"type": "upsert", "path": ["flightData", {"id": "x"}], "value": $element}""" to "not_an_array",
            """{"type": "insert", "path": ["flightData", {"id": "x"}], "after": null, "value": $element}""" to "not_an_array",
            // An element found by its id is an object, never a list to add to.
            """{"type": "upsert", "path": ["graphics", "helicopters", {"id": "h-1"}], "value": $element}""" to "not_an_array",
            """{"type": "upsert", "path": ["graphics", "helicopters", {"id": "h-9"}], "value": $element}""" to "target_missing",
            // What to remove from is a number, or is looked for by id inside an object.
            """{"type": "remove", "path": ["flightData", "landingHeading", {"id": "x"}]}""" to "not_an_array",
            """{"type": "remove", "path": ["flightData", {"id": "x"}, {"id": "y"}]}""" to "not_an_array",
            """{"type": "remove", "path": ["flightData", {"id": "x"}]}""" to "not_an_array",
        ).forEach { (op, reason) ->
            val fields = Json.parseToJsonElement(op).jsonObject
            val result = PackOps.apply(items, JsonObject(fields + ("item" to JsonPrimitive("lz-1"))))
            assertEquals(OpStatus.SKIPPED, result.status, op)
            assertEquals(reason, result.reason, op)
            assertSame(items, result.items, op)
        }
    }

    @Test
    fun `validate refuses exactly what apply calls malformed`() {
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val expected = if (text(case, "status") == "invalid") text(case, "reason") else null
            assertEquals(expected, PackOps.validate(case.getValue("op")), name(case))
        }
        assertEquals("bad_op", PackOps.validate(null))
    }

    @Test
    fun `the fixture has every outcome`() {
        val statuses = cases.map { text(it, "status") }.toSet()
        assertEquals(setOf("applied", "skipped", "invalid"), statuses)
    }

    @Test
    fun `only what is on the edited path is new`() {
        val before = items(cases.first { name(it) == "a field of an element is set by its id" }.getValue("items"))
        val op = cases.first { name(it) == "a field of an element is set by its id" }.getValue("op")
        val was = before.getValue("lz-1").data.jsonObject
        val now = PackOps.apply(before, op).items.getValue("lz-1").data.jsonObject

        assertNotSame(was, now)
        assertSame(was["flightData"], now["flightData"])
        assertSame(was["target"], now["target"])
        val wasGraphics = was.getValue("graphics").jsonObject
        val nowGraphics = now.getValue("graphics").jsonObject
        assertNotSame(wasGraphics, nowGraphics)
        assertSame(wasGraphics["units"], nowGraphics["units"])
        val wasHelicopters = wasGraphics.getValue("helicopters").jsonArray
        val nowHelicopters = nowGraphics.getValue("helicopters").jsonArray
        assertSame(wasHelicopters[0], nowHelicopters[0])
        assertNotSame(wasHelicopters[1], nowHelicopters[1])
    }

    @Test
    fun `1 finds 1_0 and 1_0 finds 1, but neither finds the text 1`() {
        val data = Json.parseToJsonElement("""{"units": [{"id": "1", "tag": "text"}, {"id": 1.0, "tag": "number"}]}""")
        val items = mapOf("lz-1" to PackItemState("lz", "LZ", data, deleted = false))
        fun tagged(op: String): JsonElement? {
            val result = PackOps.apply(items, Json.parseToJsonElement(op))
            assertEquals(OpStatus.APPLIED, result.status, result.reason)
            return result.items.getValue("lz-1").data.jsonObject.getValue("units").jsonArray
                .firstOrNull { it.jsonObject["hit"] != null }?.jsonObject?.get("tag")
        }
        assertEquals(JsonPrimitive("number"), tagged("""{"type": "set", "item": "lz-1", "path": ["units", {"id": 1}, "hit"], "value": true}"""))
        assertEquals(JsonPrimitive("text"), tagged("""{"type": "set", "item": "lz-1", "path": ["units", {"id": "1"}, "hit"], "value": true}"""))

        // Setting an element whole may write its id as 1.0 where the path says 1: one number.
        val whole = """{"type": "set", "item": "lz-1", "path": ["units", {"id": 1}], "value": {"id": 1.0, "tag": "again"}}"""
        assertEquals(null, PackOps.validate(Json.parseToJsonElement(whole)))
        assertEquals("bad_value", PackOps.validate(Json.parseToJsonElement(whole.replace("1.0", "\"1\""))))
    }

    @Test
    fun `an item id ends where it ends, line break or not`() {
        val op = { item: String -> JsonObject(mapOf("type" to JsonPrimitive("item.delete"), "item" to JsonPrimitive(item))) }
        assertEquals(null, PackOps.validate(op("lz-1")))
        assertEquals("bad_item", PackOps.validate(op("lz-1\n")))
        assertEquals("bad_item", PackOps.validate(op("")))
        assertEquals("bad_item", PackOps.validate(JsonObject(mapOf("type" to JsonPrimitive("item.delete"), "item" to JsonPrimitive(1)))))
    }

    @Test
    fun `a name counts characters, not UTF-16 units, and a lone surrogate is one`() {
        fun rename(name: String) = PackOps.validate(
            JsonObject(mapOf("type" to JsonPrimitive("item.rename"), "item" to JsonPrimitive("lz-1"), "name" to JsonPrimitive(name))),
        )
        assertEquals(null, rename("\uD83D".repeat(100)))
        assertEquals("bad_name", rename("\uD83D".repeat(101)))
        assertEquals(null, rename("\u00a0"))                      // only space, tab and line breaks are blank to the server
    }

    @Test
    fun `an element that is not an object is passed over by an id search`() {
        val data = Json.parseToJsonElement("""{"units": [7, null, [{"id": "u-1"}], {"id": "u-1", "tag": "found"}]}""")
        val items = mapOf("lz-1" to PackItemState("lz", "LZ", data, deleted = false))
        val result = PackOps.apply(items, Json.parseToJsonElement("""{"type": "remove", "item": "lz-1", "path": ["units", {"id": "u-1"}]}"""))
        assertEquals(OpStatus.APPLIED, result.status)
        assertEquals(Json.parseToJsonElement("""{"units": [7, null, [{"id": "u-1"}]]}"""), result.items.getValue("lz-1").data)
    }

    private fun assertItem(expected: JsonObject, actual: PackItemState, uuid: String) {
        assertEquals(expected.getValue("kind").jsonPrimitive.content, actual.kind, "$uuid.kind")
        assertEquals(expected.getValue("name").jsonPrimitive.content, actual.name, "$uuid.name")
        assertEquals(expected.getValue("deleted").jsonPrimitive.boolean, actual.deleted, "$uuid.deleted")
        val differences = differences(expected.getValue("data"), actual.data, "$uuid.data")
        assertTrue(differences.isEmpty(), differences.joinToString("\n"))
    }

    // Strict JSON equality: null is not absent, text is not a number, numbers as written, and keys in JavaScript's order.
    private fun differences(expected: JsonElement, actual: JsonElement, path: String): List<String> = when {
        expected is JsonObject && actual is JsonObject -> {
            val order = Js.orderedKeys(actual.keys)
            if (order != expected.keys.toList()) listOf("$path: keys $order, expected ${expected.keys.toList()}")
            else expected.keys.flatMap { differences(expected.getValue(it), actual.getValue(it), "$path.$it") }
        }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) listOf("$path: ${actual.size} elements, expected ${expected.size}")
            else expected.indices.flatMap { differences(expected[it], actual[it], "$path[$it]") }
        expected is JsonNull || actual is JsonNull -> if (expected is JsonNull && actual is JsonNull) emptyList() else listOf("$path: $actual, expected $expected")
        expected is JsonPrimitive && actual is JsonPrimitive ->
            if (expected.isString == actual.isString && expected.content == actual.content) emptyList() else listOf("$path: $actual, expected $expected")
        else -> listOf("$path: $actual, expected $expected")
    }
}
