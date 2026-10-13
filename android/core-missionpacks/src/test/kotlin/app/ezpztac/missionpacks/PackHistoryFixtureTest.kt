package app.ezpztac.missionpacks

import app.ezpztac.network.PackEvent
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every case of `contracts/fixtures/packs/history.json`, which the web wrote: a pack's log read as its history's lines, one for each change
 * however many operations it went to the pack as. The events are read as the server's (a field it does not send fails here, as the
 * recorded responses are read), so the rule is tried on what the History panel is given.
 */
class PackHistoryFixtureTest {
    private val cases: List<JsonObject> = Fixtures.load("packs/history.json").getValue("cases").jsonArray.map { it.jsonObject }

    // Json's defaults are strict, as the recorded responses are read: an unknown field fails rather than being passed over.
    private fun events(case: JsonObject): List<PackEvent> = case.getValue("events").jsonArray.map { Json.decodeFromJsonElement(PackEvent.serializer(), it) }

    private fun lines(case: JsonObject): List<List<Long>> =
        PackHistory.lines(events(case), case["since"]?.jsonPrimitive?.longOrNull).map { line -> line.map { it.seq } }

    @TestFactory
    fun `each log is read as the web reads it`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.getValue("name").jsonPrimitive.content) {
            assertEquals(case.getValue("lines").jsonArray.map { line -> line.jsonArray.map { it.jsonPrimitive.long } }, lines(case))
        }
    }

    @Test
    fun `a doghouse turned is two events in the log and one line of the history`() {
        val turn = cases.first()
        assertEquals(listOf("flightData", "graphics"), events(turn).map { it.op.getValue("path").jsonArray.first().jsonPrimitive.content })
        assertEquals(listOf(listOf(1L, 2L)), lines(turn))
    }
}
