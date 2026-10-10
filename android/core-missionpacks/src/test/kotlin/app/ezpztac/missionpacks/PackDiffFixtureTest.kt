package app.ezpztac.missionpacks

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Every case of `contracts/fixtures/packs/diff.json`, which the web's `packDiff.js` wrote: the same change must become the
 * same operations, in the same order, held strictly (null kept apart from absent, numbers as written, keys in JavaScript's
 * order); a refusal must be a refusal; and the operations, applied in order with [PackOps] as the server applies them, must
 * give back the new version.
 *
 * A case or pair marked `webBug` pins something the web gets wrong (it reads a missing field through JavaScript's
 * prototype, and drops a changed `__proto__`). The port must not copy those: they are left out of the replay, and the tests
 * below them hold the right behaviour instead.
 */
class PackDiffFixtureTest {
    private val fixture = Fixtures.load("packs/diff.json")
    private val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
    private val pairs = fixture.getValue("sameData").jsonArray.map { it.jsonObject }

    private fun name(case: JsonObject): String = case.getValue("name").jsonPrimitive.content

    private fun flag(case: JsonObject, key: String): Boolean = (case[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun webBug(case: JsonObject): Boolean = flag(case, "webBug")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // The kind the data is, as the web's fixture test reads it: a point set is a list, a set of routes has a list of routes.
    private fun kindOf(data: JsonElement?): String = when {
        data is JsonArray -> "pointset"
        (data as? JsonObject)?.get("routes") is JsonArray -> "route"
        else -> "lz"
    }

    // Applies the operations (diffItem's, on item "it") to [data] as the server would, insisting each one is well formed
    // and applies.
    private fun applyAll(data: JsonElement, ops: List<JsonObject>): JsonElement {
        var items = mapOf("it" to PackItemState(kindOf(data), "IT", data, deleted = false))
        ops.forEachIndexed { i, op ->
            assertNull(PackOps.validate(op), "op $i is well formed: $op")
            val result = PackOps.apply(items, op)
            assertEquals(OpStatus.APPLIED, result.status, "op $i applies: $op (${result.reason})")
            items = result.items
        }
        return items.getValue("it").data
    }

    private fun assertOps(expected: String, actual: List<JsonObject>) {
        val differences = StrictJson.differences(json(expected), JsonArray(actual))
        assertTrue(differences.isEmpty(), differences.joinToString("\n"))
    }

    // -- The fixture ---------------------------------------------------------------------------------------------------

    @TestFactory
    fun `each change becomes the web's operations, in the web's order`(): List<DynamicTest> = cases.filterNot(::webBug).map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val before = case.getValue("before")
            val after = case.getValue("after")
            if (flag(case, "throws")) {
                val refused = assertThrows<PackDiffException> { PackDiff.diffData(before, after) }
                assertEquals("A pack item's data can only be changed inside, never replaced whole.", refused.message)
                assertThrows<PackDiffException> { PackDiff.diffItem("it", before, after) }
            } else {
                val ops = PackDiff.diffData(before, after)
                val differences = StrictJson.differences(case.getValue("ops"), JsonArray(ops), "ops")
                assertTrue(differences.isEmpty(), differences.joinToString("\n"))
            }
        }
    }

    // Every case the port does not refuse, those the web gets wrong included: here they rebuild too.
    @TestFactory
    fun `the operations, applied in order, give back the new version`(): List<DynamicTest> =
        cases.filter { webBug(it) || !flag(it, "throws") }.map { case ->
            DynamicTest.dynamicTest(name(case)) {
                val before = case.getValue("before")
                val after = case.getValue("after")
                val ops = PackDiff.diffItem("it", before, after)
                assertTrue(PackDiff.sameData(applyAll(before, ops), after), "rebuilt")
                // And nothing is sent exactly when nothing changed.
                assertEquals(PackDiff.sameData(before, after), ops.isEmpty())
            }
        }

    @TestFactory
    fun `sameData answers as the web does, whichever way round it is asked`(): List<DynamicTest> = pairs.filterNot(::webBug).map { pair ->
        DynamicTest.dynamicTest(name(pair)) {
            val same = pair.getValue("same").jsonPrimitive.boolean
            assertEquals(same, PackDiff.sameData(pair.getValue("a"), pair.getValue("b")))
            assertEquals(same, PackDiff.sameData(pair.getValue("b"), pair.getValue("a")))
        }
    }

    @Test
    fun `the fixture has refusals, changes that send nothing, and every kind of operation`() {
        assertTrue(cases.count { flag(it, "throws") && !webBug(it) } >= 6)
        assertTrue(cases.count { !flag(it, "throws") && it.getValue("ops").jsonArray.isEmpty() } >= 4)
        val types = cases.flatMap { (it["ops"] as? JsonArray).orEmpty() }.map { it.jsonObject.getValue("type").jsonPrimitive.content }.toSet()
        assertEquals(setOf("patch", "insert", "remove"), types)
        // Every mark is on something the web gets wrong: a refusal that is not one, or a case that does not rebuild.
        cases.filter(::webBug).forEach { assertTrue(flag(it, "throws") || (it["rebuilds"] as? JsonPrimitive)?.booleanOrNull == false, name(it)) }
        assertTrue(pairs.any(::webBug))
    }

    // -- What the web gets wrong, put right ----------------------------------------------------------------------------

    @TestFactory
    fun `a case the web gets wrong is sent here so that it rebuilds`(): List<DynamicTest> = cases.filter(::webBug).map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val before = case.getValue("before")
            val after = case.getValue("after")
            val ops = PackDiff.diffData(before, after)                                  // not refused here
            assertTrue(PackDiff.sameData(applyAll(before, PackDiff.diffItem("it", before, after)), after))
            // Not what the web sends, or the web is fixed and the mark should have gone with a regenerated fixture.
            if (!flag(case, "throws")) assertFalse(StrictJson.differences(case.getValue("ops"), JsonArray(ops)).isEmpty())
        }
    }

    // The mark on a pair means the web's answer is not what plain JSON says, so the right answer is the other one.
    @TestFactory
    fun `a pair the web gets wrong gets the plain JSON answer here`(): List<DynamicTest> = pairs.filter(::webBug).map { pair ->
        DynamicTest.dynamicTest(name(pair)) {
            val web = pair.getValue("same").jsonPrimitive.boolean
            assertEquals(!web, PackDiff.sameData(pair.getValue("a"), pair.getValue("b")))
            assertEquals(!web, PackDiff.sameData(pair.getValue("b"), pair.getValue("a")))
        }
    }

    @Test
    fun `a changed field named __proto__ is sent as data, in its object's patch`() {
        val before = json("""{"schemaVersion": 2, "status": "analyzed", "__proto__": {"polluted": false}}""")
        val after = json("""{"schemaVersion": 2, "status": "targeted", "__proto__": {"polluted": true}}""")
        val ops = PackDiff.diffData(before, after)
        // The web sends only the status and leaves the object under __proto__ as it was for everyone.
        assertOps("""[{"type": "patch", "path": [], "value": {"status": "targeted", "__proto__": {"polluted": true}}}]""", ops)
        assertEquals(after, applyAll(before, PackDiff.diffItem("it", before, after)))
    }

    @Test
    fun `a field named constructor that went is sent as null, as any field that went is`() {
        val before = json("""{"flightData": {"callSign": "HAWK 6", "constructor": {"net": "A"}}, "status": "analyzed"}""")
        val after = json("""{"flightData": {"callSign": "HAWK 6"}, "status": "analyzed"}""")
        // The web reads the constructor every JavaScript object inherits, cannot copy it, and fails.
        assertOps("""[{"type": "patch", "path": ["flightData"], "value": {"constructor": null}}]""", PackDiff.diffData(before, after))
    }

    @Test
    fun `a field named after what every JavaScript object has is read only as the object's own`() {
        listOf("toString", "constructor", "valueOf", "hasOwnProperty", "__proto__", "__lookupGetter__").forEach { key ->
            val withNull = json("""{"a": 1, "$key": null}""")
            val without = json("""{"a": 1}""")
            assertTrue(PackDiff.sameData(withNull, without), key)
            assertTrue(PackDiff.sameData(without, withNull), key)
            // The web sends `key: null` here: to it, the field was a function and is now null.
            assertEquals(emptyList<JsonObject>(), PackDiff.diffData(without, withNull), key)
            assertEquals(emptyList<JsonObject>(), PackDiff.diffData(withNull, without), key)
        }
    }

    // -- Kotlin's own ways of getting it wrong -------------------------------------------------------------------------

    @Test
    fun `an object's keys are walked in JavaScript's order, however the document lists them`() {
        // Read from a store or a server, a document need not list array indexes first, as every fixture does.
        val before = json("""{"layers": {"b": {"v": 1}, "10": {"v": 1}, "2": {"v": 1}}, "x": 1, "5": 1}""")
        val after = json("""{"layers": {"b": {"v": 2}, "10": {"v": 2}, "2": {"v": 2}}, "y": 1}""")
        val ops = PackDiff.diffData(before, after)
        assertOps(
            """[
                {"type": "patch", "path": [], "value": {"5": null, "y": 1, "x": null}},
                {"type": "patch", "path": ["layers", "2"], "value": {"v": 2}},
                {"type": "patch", "path": ["layers", "10"], "value": {"v": 2}},
                {"type": "patch", "path": ["layers", "b"], "value": {"v": 2}}
            ]""",
            ops,
        )
        // What diffData({x: 1, 5: 1}, {y: 1}) gives in Node: the patch's own keys as the object holds them, an index first.
        assertEquals(listOf("5", "y", "x"), ops[0].getValue("value").jsonObject.keys.toList())
    }

    @Test
    fun `ids compare as JavaScript compares them`() {
        // 1 and 1.0 are one number, so one element: a patch at it, not a remove and an insert.
        val ops = PackDiff.diffData(json("""{"units": [{"id": 1, "v": 1}]}"""), json("""{"units": [{"id": 1.0, "v": 2}]}"""))
        assertEquals(1, ops.size)
        assertEquals(JsonPrimitive("patch"), ops[0]["type"])
        val path = ops[0].getValue("path").jsonArray
        assertEquals(JsonPrimitive("units"), path[0])
        assertTrue(Js.strictEquals(JsonPrimitive(1), path[1].jsonObject["id"]))
        assertEquals(json("""{"v": 2}"""), ops[0]["value"])
        assertTrue(PackDiff.sameData(json("""{"id": 1}"""), json("""{"id": 1.0}""")))
        assertFalse(PackDiff.sameData(json("""{"id": 1}"""), json("""{"id": "1"}""")))

        // So 1 and 1.0 in one list are one id twice, and the list has no ids to address: it is sent whole.
        assertOps(
            """[{"type": "patch", "path": [], "value": {"units": [{"id": 1, "v": 2}, {"id": 1.0}]}}]""",
            PackDiff.diffData(json("""{"units": [{"id": 1}, {"id": 1.0}]}"""), json("""{"units": [{"id": 1, "v": 2}, {"id": 1.0}]}""")),
        )
    }

    @Test
    fun `the run of elements kept in order is the one the web picks`() {
        // longestRising in packDiff.js, run in Node: where several runs are as long, the later elements are kept.
        mapOf(
            listOf<Int>() to setOf(),
            listOf(0) to setOf(0),
            listOf(1, 0) to setOf(1),
            listOf(0, 1, 2) to setOf(0, 1, 2),
            listOf(2, 1, 0) to setOf(2),
            listOf(3, 0, 1, 2) to setOf(1, 2, 3),
            listOf(1, 2, 3, 0) to setOf(0, 1, 2),
            listOf(1, 0, 3, 2) to setOf(1, 3),
            listOf(2, 3, 0, 1) to setOf(2, 3),
            listOf(2, 0, 3, 1) to setOf(1, 3),
            listOf(4, 5, 1, 2, 3) to setOf(2, 3, 4),
            listOf(3, 4, 0, 1, 2, 5) to setOf(2, 3, 4, 5),
            listOf(0, 4, 1, 5, 2, 6, 3) to setOf(0, 2, 4, 6),
            listOf(5, 0, 6, 1, 7, 2, 3) to setOf(1, 3, 5, 6),
        ).forEach { (values, kept) -> assertEquals(kept, PackDiff.longestRising(values), values.toString()) }
    }

    @Test
    fun `an operation on an item names it last, and keeps its nulls`() {
        val before = json("""{"graphics": {"goArounds": [{"id": "ga-2"}]}, "notes": "x"}""")
        val after = json("""{"graphics": {"goArounds": [{"id": "ga-1"}, {"id": "ga-2"}]}}""")
        val ops = PackDiff.diffItem("lz-1", before, after)
        assertOps(
            """[
                {"type": "patch", "path": [], "value": {"notes": null}, "item": "lz-1"},
                {"type": "insert", "path": ["graphics", "goArounds"], "after": null, "value": {"id": "ga-1"}, "item": "lz-1"}
            ]""",
            ops,
        )
        // A null here is a value to send, never a key to leave out.
        assertEquals(JsonNull, ops[0].getValue("value").jsonObject["notes"])
        assertEquals(JsonNull, ops[1]["after"])
    }

    @Test
    fun `nothing is a change from nothing, and nothing cannot become something`() {
        assertEquals(emptyList<JsonObject>(), PackDiff.diffData(null, null))
        assertEquals(emptyList<JsonObject>(), PackDiff.diffData(null, JsonNull))
        assertTrue(PackDiff.sameData(null, JsonNull))
        assertThrows<PackDiffException> { PackDiff.diffData(null, json("{}")) }
        assertThrows<PackDiffException> { PackDiff.diffData(json("[]"), null) }
        assertThrows<PackDiffException> { PackDiff.diffData(json("1"), json("2")) }
    }
}
