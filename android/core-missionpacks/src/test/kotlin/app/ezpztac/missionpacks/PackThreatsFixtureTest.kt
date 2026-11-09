package app.ezpztac.missionpacks

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** `contracts/fixtures/packs/threats.json`, which the web's `packThreats.js` wrote, held strictly: null kept apart from absent, numbers as written, and every object's keys in order. */
class PackThreatsFixtureTest {
    private val fixture = Fixtures.load("packs/threats.json")

    private fun cases(key: String): List<JsonObject> = fixture.getValue(key).jsonArray.map { it.jsonObject }

    private fun name(case: JsonObject): String = case["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: case.toString().take(60)

    private fun strict(expected: JsonElement?, actual: JsonElement?, what: String) {
        val differences = StrictJson.differences(expected ?: JsonNull, actual ?: JsonNull)
        assertTrue(differences.isEmpty(), "$what:\n" + differences.joinToString("\n"))
    }

    private fun readJson(read: PackThreats.Read): JsonObject = JsonObject(
        linkedMapOf("threats" to JsonArray(read.threats), "unreadable" to JsonArray(read.unreadable), "version" to read.version, "extras" to read.extras),
    )

    @TestFactory
    fun `what everyone shares of a threat`(): List<DynamicTest> = cases("sharedThreat").map { case ->
        DynamicTest.dynamicTest(name(case)) { strict(case.getValue("expected"), PackThreats.sharedThreat(case.getValue("value")), "shared") }
    }

    @TestFactory
    fun `what everyone shares of a set`(): List<DynamicTest> = cases("sharedData").map { case ->
        DynamicTest.dynamicTest(name(case)) { strict(case["expected"], PackThreats.sharedThreatData(case["value"]), "shared") }
    }

    @TestFactory
    fun `an item read as this person's set, and written back`(): List<DynamicTest> = cases("read").map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val read = PackThreats.threatSetFromItem(case["data"], case["previous"]?.takeUnless { it is JsonNull })
            strict(case.getValue("expected"), readJson(read), "read")
            strict(case.getValue("doc"), PackThreats.threatSetDoc(read), "doc")
        }
    }

    @TestFactory
    fun `a mask is kept while it fits`(): List<DynamicTest> = cases("masks").map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val previous = case["previous"]?.takeUnless { it is JsonNull }?.let { JsonObject(mapOf("threats" to JsonArray(listOf(it)))) }
            val read = PackThreats.threatSetFromItem(JsonObject(mapOf("threats" to JsonArray(listOf(case.getValue("threat"))))), previous).threats.single()
            val expected = case.getValue("expected").jsonObject
            expected.forEach { (key, value) -> strict(value, read[key], key) }
        }
    }

    @TestFactory
    fun `the history's sentence for a change`(): List<DynamicTest> = cases("describe").map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val set = (if ("setName" in case) PackFixtures.text(case["setName"]) else "AIR DEFENCE")
            val actor = (if ("actor" in case) PackFixtures.text(case["actor"]) else "Colin M.")
            assertEquals(case.getValue("expected").jsonPrimitive.content, PackThreats.describeThreatChange(case["before"], case["after"], set, actor))
        }
    }

    @TestFactory
    fun `a new set's name`(): List<DynamicTest> = cases("newItemName").map { case ->
        DynamicTest.dynamicTest(name(case)) {
            assertEquals(case.getValue("expected").jsonPrimitive.content, PackSentences.newItemName("threatset", PackFixtures.text(case["name"]), case.getValue("count").jsonPrimitive.int))
        }
    }

    @TestFactory
    fun `a new set's sentence`(): List<DynamicTest> = cases("createSummary").map { case ->
        DynamicTest.dynamicTest(name(case)) {
            assertEquals(
                case.getValue("expected").jsonPrimitive.content,
                PackSentences.createSummary(PackFixtures.text(case["actor"]), "threatset", case.getValue("name").jsonPrimitive.content, case.getValue("count").jsonPrimitive.int),
            )
        }
    }

    @TestFactory
    fun `a new set's operation`(): List<DynamicTest> = cases("newOp").map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val input = case.getValue("input").jsonObject
            val made = PackThreats.newThreatSetOp(PackFixtures.text(input["name"]), input.getValue("threats").jsonArray, PackFixtures.text(input["actor"]), input["count"]?.jsonPrimitive?.int ?: 0) { "fixed" }
            strict(case.getValue("expected"), made, "op")
        }
    }

    // -- The kind the editors use ----------------------------------------------------------------------------------------------------------------------------------------------------

    private val threatA = """{"id": "a", "name": "T-a", "lat": 34.5, "lon": -84.1, "milstdId": "SHAPMFG--------", "source": "", "information": "", "showThreat": true,
        "radars": [{"type": 1, "rangeNmi": 25, "antennaHeightFt": 30, "aglNotMsl": true, "showMask": true, "showRangeRings": true,
        "bands": [{"altFt": 300, "color": "#FF0000", "alpha": 128, "colorIndex": 1, "viewable": true}]}]}"""

    private fun item(data: String, name: String = "AIR DEFENCE") = PackItemView(uuid = "ts-1", kind = "threatset", name = name, data = Json.parseToJsonElement(data))

    private val local = PackRef.localId(PackFixtures.PACK, "ts-1")

    @Test
    fun `a set opened and not changed sends nothing`() {
        val made = item("""{"version": 1, "threats": [$threatA]}""")
        val base = PackItemSync.settled(ThreatPackKind, local, made)
        assertEquals(null, PackItemSync.flushPlan(ThreatPackKind, local, base, made, ThreatPackKind.fromItem(local, made, null), "Colin M."))
    }

    @Test
    fun `hiding a threat is this person's own and sends nothing`() {
        val made = item("""{"version": 1, "threats": [$threatA]}""")
        val base = PackItemSync.settled(ThreatPackKind, local, made)
        val set = ThreatPackKind.fromItem(local, made, null)
        val hidden = set.copy(document = set.document.copy(threats = set.document.threats.map { it.copy(entry = it.entry.copy(visible = false)) }))
        assertEquals(null, PackItemSync.flushPlan(ThreatPackKind, local, base, made, hidden, "Colin M."))
        assertEquals("""{"hidden":["a"]}""", ThreatPackKind.ownOf(hidden).toString())
        assertFalse(ThreatPackKind.fromItem(local, made, ThreatPackKind.ownOf(hidden)).document.threats.single().entry.visible)
    }

    @Test
    fun `a threat moved one way goes with both of its coordinates`() {
        val made = item("""{"version": 1, "threats": [$threatA]}""")
        val base = PackItemSync.settled(ThreatPackKind, local, made)
        val set = ThreatPackKind.fromItem(local, made, null)
        val moved = set.copy(document = set.document.copy(threats = set.document.threats.map { t -> t.copy(entry = t.entry.copy(threat = t.entry.threat.copy(lat = 35.0))) }))
        val plan = checkNotNull(PackItemSync.flushPlan(ThreatPackKind, local, base, made, moved, "Colin M."))
        val value = plan.ops.single()["value"]!!.jsonObject
        assertEquals(setOf("lat", "lon"), value.keys)
        assertEquals("Colin M. moved the threat T-a.", plan.ops.single()["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `someone else's change keeps what this person hid, and a mask that still fits`() {
        val made = item("""{"version": 1, "threats": [$threatA]}""")
        val set = ThreatPackKind.fromItem(local, made, JsonObject(mapOf("hidden" to JsonArray(listOf(JsonPrimitive("a"))))))
        val subject = app.ezpztac.model.ThreatSets.subjectOf(set.document.threats.single().entry.threat)
        val mask = JsonObject(mapOf("for" to subject, "bounds" to JsonArray(emptyList())))
        val masked = set.copy(document = set.document.copy(threats = set.document.threats.map { it.copy(mask = mask) }))
        val renamed = Json.parseToJsonElement("""{"version": 1, "threats": [$threatA]}""").jsonObject.let { data ->
            JsonObject(data + ("threats" to JsonArray(listOf(JsonObject(data.getValue("threats").jsonArray.single().jsonObject + ("name" to JsonPrimitive("OTHER")))))))
        }
        val taken = ThreatPackKind.withShared(masked, renamed, "NEW NAME")
        val threat = taken.document.threats.single()
        assertEquals("NEW NAME", taken.name)
        assertEquals("OTHER", threat.entry.threat.name)
        assertFalse(threat.entry.visible)
        assertEquals(mask, threat.mask)
    }

    @Test
    fun `a mask is never sent`() {
        val made = item("""{"version": 1, "threats": [$threatA]}""")
        val set = ThreatPackKind.fromItem(local, made, null)
        val subject = app.ezpztac.model.ThreatSets.subjectOf(set.document.threats.single().entry.threat)
        val masked = set.copy(document = set.document.copy(threats = set.document.threats.map { it.copy(mask = JsonObject(mapOf("for" to subject))) }))
        assertFalse("mask" in ThreatPackKind.docOf(masked, made.data).toString())
    }

    @Test
    fun `a threat the types here cannot hold is written back as it came`() {
        val odd = """{"id": "b", "name": "ODD", "lat": 34.5, "lon": -84.1, "radars": [{"bands": [{"altFt": 300, "color": 1}]}]}"""
        val made = item("""{"version": 1, "threats": [$threatA, $odd]}""")
        val set = ThreatPackKind.fromItem(local, made, null)
        assertEquals(listOf("a"), set.document.threats.map { it.entry.id })
        assertEquals("ODD", ThreatPackKind.docOf(set, made.data).jsonObject.getValue("threats").jsonArray[1].jsonObject.getValue("name").jsonPrimitive.content)
    }
}
