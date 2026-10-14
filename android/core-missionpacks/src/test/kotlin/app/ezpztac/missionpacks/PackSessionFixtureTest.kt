package app.ezpztac.missionpacks

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every scenario of `contracts/fixtures/packs/session.json`, which the web's packSession.js wrote, played step by step from
 * the JSON alone: after each step the whole session, what is visible and what the function answered must be what the web
 * recorded, and at the end the dropped edits must make the same versions.
 *
 * As the fixture asks, a key that is absent and one that is null are told apart (a 423 that says `finished_by: null` is not
 * one that says nothing). The fixture lets an object's keys come in any order; this holds them to the order JavaScript
 * gives them too, because the order of an item's keys is the order a later diff of it lists its operations in.
 */
class PackSessionFixtureTest {
    private val fixture = Fixtures.load("packs/session.json")
    private val scenarios = fixture.getValue("scenarios").jsonArray.map { it.jsonObject }

    private fun text(json: JsonObject, key: String): String = json.getValue(key).jsonPrimitive.content

    @Test
    fun `a batch holds as many operations as the web's`() {
        assertEquals(fixture.getValue("max_batch").jsonPrimitive.int, PackSessions.MAX_BATCH)
    }

    @Test
    fun `the fixture plays every function, and drops and versions something`() {
        val played = scenarios.flatMap { it.getValue("steps").jsonArray }.map { text(it.jsonObject, "do") }.toSet()
        assertEquals(setOf("open", "edit", "nextBatch", "receive", "batchAnswered", "batchFailed", "abandon", "reload"), played)
        assertTrue(scenarios.any { it.getValue("dropped_versions").jsonArray.isNotEmpty() })
    }

    @TestFactory
    fun `every scenario plays as the web's session plays it`(): List<DynamicTest> = scenarios.map { scenario ->
        DynamicTest.dynamicTest(text(scenario, "name")) { play(scenario) }
    }

    private fun play(scenario: JsonObject) {
        val me = scenario.getValue("me").jsonPrimitive.int
        var taken = 0
        val newId = { "op-${++taken}" }
        val steps = scenario.getValue("steps").jsonArray.map { it.jsonObject }
        assertEquals("open", text(steps.first(), "do"), "a scenario starts by opening the pack")

        var session = PackSessions.open(scenario.getValue("pack").jsonObject, me)
        check(steps.first(), 0, session, result = null)
        steps.drop(1).forEachIndexed { i, step ->
            // Kotlin's null is a step with no result at all; JSON null is a result of null (nextBatch with nothing to send).
            val result: JsonElement?
            when (val action = text(step, "do")) {
                "edit" -> {
                    val out = PackSessions.edit(session, opsOf(step.getValue("ops")), newId)
                    session = out.session
                    result = buildJsonObject { put("refused", out.refused) }
                }
                "nextBatch" -> {
                    val out = PackSessions.nextBatch(session)
                    session = out?.session ?: session
                    result = out?.let { buildJsonObject { put("batch", it.batch) } } ?: JsonNull
                }
                "receive" -> {
                    val out = PackSessions.receive(session, step.getValue("events").jsonArray.map { it.jsonObject })
                    session = out.session
                    result = buildJsonObject { put("gap", out.gap) }
                }
                "batchAnswered" -> {
                    val out = PackSessions.batchAnswered(session, step.getValue("answer").jsonObject)
                    session = out.session
                    result = buildJsonObject { put("catchUp", out.catchUp) }
                }
                "batchFailed" -> {
                    session = PackSessions.batchFailed(session, PackFailure.fromJson(step.getValue("failure").jsonObject))
                    result = null
                }
                "abandon" -> {
                    session = PackSessions.abandon(session, text(step, "reason"))
                    result = null
                }
                "reload" -> {
                    session = PackSessions.reload(session, step.getValue("pack").jsonObject)
                    result = null
                }
                else -> error("a step this test does not know: $action")
            }
            check(step, i + 1, session, result)
        }

        val versions = JsonArray(
            PackSessions.droppedVersions(session).map { version ->
                buildJsonObject {
                    put("uuid", version.uuid)
                    put("kind", version.kind)
                    put("name", version.name)
                    put("data", version.data)
                }
            },
        )
        assertSame(scenario.getValue("dropped_versions"), versions, "dropped_versions")
    }

    // One operation given alone is a list of one, as the web's edit takes it.
    private fun opsOf(ops: JsonElement): List<JsonElement> = if (ops is JsonArray) ops else listOf(ops)

    private fun check(step: JsonObject, index: Int, session: PackSession, result: JsonElement?) {
        val where = "step $index (${text(step, "do")})"
        if ("result" in step) {
            assertFalse(result == null, "$where: the web's answered more than the session")
            assertSame(step.getValue("result"), result!!, "$where: result")
        } else {
            assertEquals(null, result, "$where: the web's gave nothing but the session")
        }
        assertSame(step.getValue("session"), PackSessions.toJson(session), "$where: session")
        assertSame(step.getValue("visible"), JsonArray(PackSessions.visibleItems(session)), "$where: visible")
    }

    private fun assertSame(expected: JsonElement, actual: JsonElement, what: String) {
        val differences = StrictJson.differences(expected, actual)
        assertTrue(differences.isEmpty(), "$what:\n" + differences.take(MAX_SHOWN).joinToString("\n"))
    }

    private companion object {
        const val MAX_SHOWN = 20
    }
}
