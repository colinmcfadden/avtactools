package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A set of threats as the Library keeps it: the web's document, read and written the same way, held to what the web itself does with each case. */
class ThreatSetsTest {
    private val fixture = Fixtures.load("threats/sets.json")

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private fun threatOf(element: JsonElement) = ThreatSets.read(buildJsonObject { put("threats", buildJsonArray { add(element) }) }).threats.single().entry.threat

    /** The written document with each id the reader chose put back to the fixture's `<new>`. */
    private fun markNew(written: JsonObject, chosen: Set<String>): JsonObject {
        val threats = written["threats"] as? JsonArray ?: return written
        return JsonObject(
            written + ("threats" to JsonArray(
                threats.map { element ->
                    val threat = element as? JsonObject
                    val id = (threat?.get("id") as? JsonPrimitive)?.content
                    if (threat != null && id in chosen) JsonObject(threat + ("id" to JsonPrimitive("<new>"))) else element
                },
            )),
        )
    }

    @Test
    fun `every document the web was run over reads and writes the same`() {
        val documents = cases("documents")
        assertTrue(documents.size >= 25)
        for (case in documents) {
            val name = case.name()
            val data = case.getValue("data").jsonObject
            val used = case.getValue("usedIds").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val read = ThreatSets.read(data, used, "fresh")
            val shown = case.getValue("shown").jsonArray.map { it.jsonObject }
            assertEquals(shown.size, read.threats.size, "$name: how many are read")
            assertEquals(case.getValue("unreadable").jsonPrimitive.int, read.unreadable.size, "$name: how many cannot be read")
            val chosen = HashSet<String>()
            shown.zip(read.threats).forEach { (want, got) ->
                val id = want.getValue("id").jsonPrimitive.content
                if (id == "<new>") {
                    assertFalse(got.entry.id in used, "$name: a new id is not one another set holds")
                    chosen.add(got.entry.id)
                } else {
                    assertEquals(id, got.entry.id, "$name: the id")
                }
                assertEquals(want.getValue("visible").jsonPrimitive.boolean, got.entry.visible, "$name: shown or not")
                assertEquals(want.getValue("masked").jsonPrimitive.boolean, got.mask != null, "$name: its mask")
            }
            assertEquals(read.threats.size, read.threats.map { it.entry.id }.toSet().size, "$name: no two share an id")
            assertTrue(ThreatSets.sameValue(case.getValue("written"), markNew(ThreatSets.write(read), chosen)), "$name: what is written back: ${ThreatSets.write(read)}")
        }
    }

    @Test
    fun `a document read again gives a threat with no id the same one`() {
        val data = Json.parseToJsonElement("""{"threats":[{"lat":1,"lon":2,"radars":[]},{"lat":3,"lon":4,"radars":[]}]}""").jsonObject
        val first = ThreatSets.read(data, freshPrefix = "x").threats.map { it.entry.id }
        assertEquals(first, ThreatSets.read(data, freshPrefix = "x").threats.map { it.entry.id })
        assertEquals(listOf("x-0", "x-1"), first)
    }

    @Test
    fun `what a mask depends on is what the web says`() {
        for (case in cases("subjects")) {
            assertTrue(ThreatSets.sameValue(case.getValue("subject"), ThreatSets.subjectOf(threatOf(case.getValue("threat")))))
        }
    }

    @Test
    fun `whether a mask still fits is what the web says`() {
        for (case in cases("masks")) {
            val mask = case.getValue("mask") as? JsonObject
            assertEquals(case.getValue("fits").jsonPrimitive.boolean, ThreatSets.maskFits(threatOf(case.getValue("subject")), mask), case.name())
        }
    }

    @Test
    fun `two versions differing only in their masks are the same, as the web says`() {
        for (case in cases("sameApartFromMasks")) {
            assertEquals(case.getValue("same").jsonPrimitive.boolean, ThreatSets.sameApartFromMasks(case.getValue("a").jsonObject, case.getValue("b").jsonObject), case.name())
        }
    }

    @Test
    fun `a file is named as the web names it`() {
        for (case in cases("names")) assertEquals(case.getValue("name").jsonPrimitive.content, ThreatSets.nameFromFile(case.getValue("file").jsonPrimitive.content), case.getValue("file").toString())
    }

    // -- What the web's fixture cannot say ---------------------------------------------------------------------------

    private fun threat(lat: Double = 34.5, radars: List<Radar> = listOf(Radars.default(Radars.DETECTION))) = Threat("SA-6", "SHAPMFS-------", lat, -84.25, "", "SOF", true, radars)

    private fun stored(id: String, lat: Double = 34.5, mask: JsonObject? = null, visible: Boolean = true) = StoredThreat(ThreatEntry(id, threat(lat), visible), mask)

    private fun maskFor(t: Threat, padding: Int = 10) = buildJsonObject {
        put("png", "x".repeat(padding))
        put("for", ThreatSets.subjectOf(t))
    }

    @Test
    fun `a threat this version cannot hold is written back as it came, after the others`() {
        val odd = Json.parseToJsonElement("""{"id":"odd","lat":1,"lon":2,"radars":[{"type":0,"bands":[]}]}""")
        val read = ThreatSets.read(JsonObject(mapOf("threats" to JsonArray(listOf(odd)))))
        assertEquals(listOf(odd), read.unreadable)
        val added = read.copy(threats = listOf(stored("a")))
        val written = ThreatSets.write(added).getValue("threats").jsonArray
        assertEquals("a", written[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(odd, written[1])
    }

    @Test
    fun `a name, symbol, note or source that is missing takes the default, and one that is not text cannot be read`() {
        val bare = ThreatSets.read(Json.parseToJsonElement("""{"threats":[{"lat":1,"lon":2,"radars":[]}]}""").jsonObject).threats.single().entry.threat
        assertEquals(Radars.DEFAULT_NAME, bare.name)
        assertEquals(Radars.DEFAULT_MILSTD_ID, bare.milstdId)
        assertEquals("", bare.information)
        assertEquals(Radars.DEFAULT_SOURCE, bare.source)
        assertEquals(1, ThreatSets.read(Json.parseToJsonElement("""{"threats":[{"name":4,"lat":1,"lon":2,"radars":[]}]}""").jsonObject).unreadable.size)
    }

    @Test
    fun `numbers are written as JavaScript writes them`() {
        val written = ThreatSets.write(ThreatSetDocument(listOf(stored("a")))).toString()
        assertTrue("\"rangeNmi\":25," in written, written)
        assertTrue("\"altFt\":50," in written, written)
        assertTrue("\"type\":0," in written, written)
    }

    @Test
    fun `a radar's unknown field goes back unchanged until the radar is changed`() {
        val data = Json.parseToJsonElement(
            """{"threats":[{"id":"a","lat":1,"lon":2,"radars":[{"type":0,"rangeNmi":25,"antennaHeightFt":20,"aglNotMsl":true,"showMask":true,"showRangeRings":true,"bands":[],"sector":[1,2]}]}]}""",
        ).jsonObject
        val read = ThreatSets.read(data)
        assertTrue("sector" in ThreatSets.write(read).toString())
        val stored = read.threats.single()
        val changed = stored.copy(entry = stored.entry.copy(threat = stored.entry.threat.copy(radars = stored.entry.threat.radars.map { it.copy(rangeNmi = 30.0) })))
        assertFalse("sector" in ThreatSets.write(read.copy(threats = listOf(changed))).toString())
    }

    @Test
    fun `a mask is left out once the set's masks come to more than the budget, and the rest still go`() {
        val big = threat()
        val nearly = maskFor(big, ThreatSets.MASK_BUDGET - 2_000)
        val small = maskFor(big, 100)
        val written = ThreatSets.write(ThreatSetDocument(listOf(StoredThreat(ThreatEntry("a", big), nearly), StoredThreat(ThreatEntry("b", big), nearly), StoredThreat(ThreatEntry("c", big), small))))
        val threats = written.getValue("threats").jsonArray.map { it.jsonObject }
        assertTrue("mask" in threats[0])
        assertFalse("mask" in threats[1])
        assertTrue("mask" in threats[2] || nearly.toString().length + small.toString().length > ThreatSets.MASK_BUDGET)
    }

    @Test
    fun `a mask gone stale is not written`() {
        val mask = maskFor(threat(lat = 1.0))
        val written = ThreatSets.write(ThreatSetDocument(listOf(StoredThreat(ThreatEntry("a", threat(lat = 2.0)), mask))))
        assertFalse("mask" in written.getValue("threats").jsonArray.single().jsonObject)
    }

    @Test
    fun `a set name is cleaned as the web cleans it, and never cut through an emoji`() {
        assertEquals("OP DK", ThreatSets.cleanName("  op dk "))
        val long = "a".repeat(99) + "😀b"
        assertEquals("A".repeat(99) + "😀", ThreatSets.cleanName(long))
    }

    @Test
    fun `sameValue tells numbers by value, keys by name and null from nothing`() {
        fun json(text: String) = Json.parseToJsonElement(text)
        assertTrue(ThreatSets.sameValue(json("""{"a":1.50,"b":[2]}"""), json("""{"b":[2.0],"a":1.5}""")))
        assertFalse(ThreatSets.sameValue(json("""{"a":null}"""), json("{}")))
        assertFalse(ThreatSets.sameValue(json("""{"a":"1"}"""), json("""{"a":1}""")))
        assertFalse(ThreatSets.sameValue(JsonNull, json("0")))
        assertTrue(ThreatSets.sameValue(JsonNull, JsonNull))
    }

    // -- Laying someone else's change over this device's -------------------------------------------------------------

    private fun ids(list: List<StoredThreat>) = list.map { it.entry.id }

    @Test
    fun `a threat changed only there takes their version`() {
        val out = ThreatSets.merge(listOf(stored("a")), listOf(stored("a")), listOf(stored("a", lat = 2.0)))
        assertEquals(2.0, out.single().entry.threat.lat)
    }

    @Test
    fun `a threat changed only here keeps this device's version`() {
        val out = ThreatSets.merge(listOf(stored("a")), listOf(stored("a", lat = 3.0)), listOf(stored("a")))
        assertEquals(3.0, out.single().entry.threat.lat)
    }

    @Test
    fun `a threat changed on both keeps this device's`() {
        val out = ThreatSets.merge(listOf(stored("a")), listOf(stored("a", lat = 3.0)), listOf(stored("a", lat = 2.0)))
        assertEquals(3.0, out.single().entry.threat.lat)
    }

    @Test
    fun `one added on each side is kept on both, theirs first`() {
        assertEquals(listOf("a", "b", "c"), ids(ThreatSets.merge(listOf(stored("a")), listOf(stored("a"), stored("c")), listOf(stored("a"), stored("b")))))
    }

    @Test
    fun `one removed here that they left alone goes, and one removed there that this device left alone goes`() {
        assertEquals(listOf("b"), ids(ThreatSets.merge(listOf(stored("a"), stored("b")), listOf(stored("b")), listOf(stored("a"), stored("b")))))
        assertEquals(listOf("b"), ids(ThreatSets.merge(listOf(stored("a"), stored("b")), listOf(stored("a"), stored("b")), listOf(stored("b")))))
    }

    @Test
    fun `one edited there and removed here stays, as edited, and one edited here and removed there stays`() {
        assertEquals(2.0, ThreatSets.merge(listOf(stored("a")), emptyList(), listOf(stored("a", lat = 2.0))).single().entry.threat.lat)
        assertEquals(3.0, ThreatSets.merge(listOf(stored("a")), listOf(stored("a", lat = 3.0)), emptyList()).single().entry.threat.lat)
    }

    @Test
    fun `a mask is never a reason to keep an older threat, and is kept when nothing else changed`() {
        val mask = buildJsonObject { put("for", "x") }
        assertEquals(2.0, ThreatSets.merge(listOf(stored("a")), listOf(stored("a", mask = mask)), listOf(stored("a", lat = 2.0))).single().entry.threat.lat)
        assertEquals(mask, ThreatSets.merge(listOf(stored("a")), listOf(stored("a", mask = mask)), listOf(stored("a"))).single().mask)
        assertEquals(mask, ThreatSets.merge(listOf(stored("a")), listOf(stored("a")), listOf(stored("a", mask = mask))).single().mask)
        assertNull(ThreatSets.merge(listOf(stored("a")), listOf(stored("a")), listOf(stored("a"))).single().mask)
    }

    @Test
    fun `whether a threat is shown is part of the plan`() {
        assertFalse(ThreatSets.merge(listOf(stored("a")), listOf(stored("a")), listOf(stored("a", visible = false))).single().entry.visible)
    }
}
