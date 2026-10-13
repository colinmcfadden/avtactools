package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.name
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every case of `contracts/fixtures/packs/settings.json`, which the web wrote: each setting of a planning graphic or a route the web makes in a
 * mission pack, as the item it leaves. The port reads the item before and after the change and writes each back unchanged, through the kinds as
 * the editors use them; finds the web's operations for the change, strictly and in order; and what it sends instead, kept whole where the web
 * sends a part ([PackKind.keepWhole]), gives the same item.
 */
class PackSettingsFixtureTest {
    private val fixture = Fixtures.load("packs/settings.json")
    private val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
    private val lzKind = LzPackKind(PackFixtures.env())

    private fun kindOf(case: JsonObject): String = case.getValue("kind").jsonPrimitive.content

    // The items the fixture's description names.
    private fun item(case: JsonObject, data: JsonElement): PackItemView = when (kindOf(case)) {
        "lz" -> PackItemView(uuid = "b7e3c1a4-2d5f-4e6a-9b8c-0d1e2f3a4b5c", kind = "lz", name = "LZ HAWK", data = data)
        else -> PackItemView(uuid = "c8f4d2b5-3e6a-4f7b-8c9d-1e2f3a4b5c6d", kind = "route", name = "ROUTES 1", data = data)
    }

    private fun kind(case: JsonObject): PackKind<*> = if (kindOf(case) == "lz") lzKind else RoutePackKind

    // What an editor makes of an item holding [data] once it opens it.
    private fun shape(case: JsonObject, data: JsonElement): JsonElement {
        val item = item(case, data)
        return kind(case).currentShape(PackRef.localId(PackFixtures.PACK, item.uuid), item)
    }

    private fun before(case: JsonObject): JsonElement = case.getValue("before")

    private fun after(case: JsonObject): JsonElement = case.getValue("after")

    private fun sent(case: JsonObject): List<JsonObject> = kind(case).keepWhole(PackDiff.diffData(before(case), after(case)), after(case))

    private fun replay(check: (JsonObject) -> Unit): List<DynamicTest> = cases.map { case -> DynamicTest.dynamicTest(name(case)) { check(case) } }

    @TestFactory
    fun `the item before and after each setting is read and written back unchanged`() = replay { case ->
        assertValue(before(case), shape(case, before(case)), "before")
        assertValue(after(case), shape(case, after(case)), "after")
    }

    @TestFactory
    fun `each setting becomes the web's operations, in the web's order`() = replay { case ->
        val differences = StrictJson.differences(case.getValue("ops"), JsonArray(PackDiff.diffData(before(case), after(case))), "ops")
        assertTrue(differences.isEmpty(), differences.joinToString("\n"))
    }

    @TestFactory
    fun `what is sent, kept whole where the web sends a part, gives the item after the setting`() = replay { case ->
        assertTrue(PackDiff.sameData(PackFixtures.applyAll(kindOf(case), before(case), sent(case)), after(case)), "rebuilt")
    }

    @Test
    fun `the port sends more than the web only where it keeps a value whole, or a point's first values apart`() {
        val more = cases.filterNot { StrictJson.differences(it.getValue("ops"), JsonArray(sent(it))).isEmpty() }.map(::name)
        assertEquals(
            listOf(
                "a PZ marker's tip is dragged",                     // its anchor goes with it
                "a point's altitude is typed",                      // a point's first value: a set of its own
                "a point's altitude reference is chosen",           // part of a plan value: the whole of it
                "a point's speed type is chosen",
                "a point's wind speed is typed",
                "another point is given its first altitude",
                "the clock is set at a point",
                "winds are fetched for the route",                  // both
            ),
            more,
        )
    }

    @Test
    fun `the fixture has every graphic and route setting, and the two that send nothing`() {
        assertTrue(cases.count { kindOf(it) == "lz" } >= 30)
        assertTrue(cases.count { kindOf(it) == "route" } >= 25)
        assertEquals(
            listOf("the view is each person's own: nothing is sent", "a route hidden is each person's own: nothing is sent"),
            cases.filter { it.getValue("ops").jsonArray.isEmpty() }.map(::name),
        )
    }
}
