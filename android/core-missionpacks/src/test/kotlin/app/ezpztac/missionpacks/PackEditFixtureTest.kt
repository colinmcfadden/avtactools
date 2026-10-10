package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.item
import app.ezpztac.missionpacks.PackFixtures.localId
import app.ezpztac.missionpacks.PackFixtures.name
import app.ezpztac.missionpacks.PackFixtures.text
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Every case of `contracts/fixtures/packs/edit.json`, which the web's `packEdit.js` wrote: what one change an editor made to a pack
 * item is sent as, from the case alone (its own `shared` and `current`), operations in order and each as the web wrote it. The kinds
 * are then held to those `shared` and `current` too, since they are what an editor here works them out with.
 */
class PackEditFixtureTest {
    private val cases = Fixtures.load("packs/edit.json").getValue("cases").jsonArray.map { it.jsonObject }
    private val lzKind = LzPackKind(PackFixtures.env())

    private fun version(json: JsonElement): EditVersion = EditVersion(text(json.jsonObject["name"]), json.jsonObject["doc"])

    private fun describe(kind: String): ChangeSentence = when (kind) {
        "lz" -> ChangeSentence(PackLz::describeLzChange)
        "route" -> ChangeSentence(PackRoutes::describeRouteChange)
        else -> ChangeSentence(PackPoints::describePointsChange)
    }

    private fun compose(case: JsonObject): ComposedEdit? {
        val item = item(case.getValue("item"))
        return PackEdit.composeEdit(
            uuid = item.uuid,
            base = version(case.getValue("base")),
            mine = version(case.getValue("mine")),
            item = item,
            shared = { case.getValue("shared") },
            current = { case.getValue("current") },
            describe = describe(item.kind),
            actor = text(case["actor"]),
        )
    }

    @TestFactory
    fun `each change is sent as the web sends it`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(name(case)) {
            if ((case["throws"] as? JsonPrimitive)?.booleanOrNull == true) {
                assertThrows<PackDiffException> { compose(case) }
                return@dynamicTest
            }
            val expected = case.getValue("sent")
            val sent = compose(case)
            if (expected is JsonNull) {
                assertNull(sent)
                return@dynamicTest
            }
            requireNotNull(sent) { "something is sent" }
            val ops = expected.jsonObject.getValue("ops").jsonArray
            assertEquals(ops.size, sent.ops.size, "how many operations")
            ops.forEachIndexed { i, op -> assertValue(op, sent.ops[i], "op $i") }
            assertEquals(expected.jsonObject.getValue("name").jsonPrimitive.content, sent.name)
        }
    }

    @TestFactory
    fun `each kind works out the shared data and today's shape the web gave the case`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val item = item(case.getValue("item"))
            val (shared, current) = when (item.kind) {
                "lz" -> lzKind.shared(item.data) to lzKind.currentShape(localId(item), item)
                "route" -> RoutePackKind.shared(item.data) to RoutePackKind.currentShape(localId(item), item)
                // A point set has no editor here: its data is the list as it is (none is an empty one).
                else -> (item.data as? JsonArray ?: JsonArray(emptyList())).let { it to it }
            }
            assertValue(case.getValue("shared"), shared, "shared")
            assertValue(case.getValue("current"), current, "current")
        }
    }

    @Test
    fun `the cases cover a rename, a change, both, a reshape, nothing and a refusal, for every kind`() {
        val kinds = cases.map { item(it.getValue("item")).kind }.toSet()
        assertEquals(setOf("lz", "route", "pointset"), kinds)
        assertTrue(cases.count { it["sent"] is JsonNull } >= 3)
        assertEquals(1, cases.count { (it["throws"] as? JsonPrimitive)?.booleanOrNull == true })
        val types = cases.mapNotNull { (it["sent"] as? JsonObject)?.getValue("ops")?.jsonArray?.map { op -> op.jsonObject.getValue("type").jsonPrimitive.content } }
        assertTrue(types.any { it == listOf("item.rename") })
        assertTrue(types.any { it.firstOrNull() == "item.rename" && it.size > 1 })
        assertTrue(types.any { "item.rename" !in it })
    }
}
