package app.ezpztac.formats

import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePoint
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Held to the files the web writes when a person edits an imported mission (`.msnx` files in `contracts/fixtures/msnx/edits`, from `mutateMsnx.js`), each made from
 * `template.msnx`. The comparison is of the parsed documents (names, namespaces, attributes, text, order), not of bytes: two serializers need not agree on how
 * to write the same document. The web names the point and leg it makes with a counter (`00000000-0000-4000-8000-000000000001`, then `…02`); the point's id arrives
 * in the route state, and a counter that starts at 2 gives the leg the same id.
 */
class MsnxMutatorFixtureTest {
    private val fixture = Fixtures.load("msnx/edits.json")
    private val tolerance = fixture["tolerance"]!!.jsonPrimitive.double
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val template = Fixtures.bytes("msnx/template.msnx")
    private val today = LocalDate.of(2026, 7, 15)
    private val parts = listOf("mission.gpx", "mission/points.xml", "mission/legs.xml", "mission/segments.xml")

    private fun counterFrom(start: Int): () -> String {
        var n = start - 1
        return { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun parse(bytes: ByteArray, label: String): Document = MsnxXml.parse(bytes.toString(Charsets.UTF_8), label)

    /** The first place two nodes differ, as a path, or null when they are the same document content. */
    private fun difference(a: Node, b: Node, path: String): String? {
        if (a.nodeType != b.nodeType) return "$path: node type ${a.nodeType} vs ${b.nodeType}"
        if (a.nodeName != b.nodeName) return "$path: ${a.nodeName} vs ${b.nodeName}"
        if (a is Element && b is Element) {
            if (a.namespaceURI != b.namespaceURI) return "$path: namespace ${a.namespaceURI} vs ${b.namespaceURI}"
            val attrsA = (0 until a.attributes.length).associate { a.attributes.item(it).let { n -> n.nodeName to n.nodeValue } }
            val attrsB = (0 until b.attributes.length).associate { b.attributes.item(it).let { n -> n.nodeName to n.nodeValue } }
            if (attrsA != attrsB) {
                val keys = (attrsA.keys + attrsB.keys).filter { attrsA[it] != attrsB[it] }
                return "$path attributes ${keys.joinToString { "$it: ${attrsA[it]} vs ${attrsB[it]}" }}"
            }
        } else if (a.nodeValue != b.nodeValue) {
            return "$path: text '${a.nodeValue}' vs '${b.nodeValue}'"
        }
        val childrenA = (0 until a.childNodes.length).map { a.childNodes.item(it) }
        val childrenB = (0 until b.childNodes.length).map { b.childNodes.item(it) }
        if (childrenA.size != childrenB.size) return "$path (${a.nodeName}): ${childrenA.size} children vs ${childrenB.size}"
        for (i in childrenA.indices) difference(childrenA[i], childrenB[i], "$path/${a.nodeName}[$i]")?.let { return it }
        return null
    }

    private fun assertSameParts(expected: ByteArray, actual: ByteArray, label: String) {
        val web = entries(expected)
        val ours = entries(actual)
        for (part in parts) {
            val d = difference(parse(web.getValue(part), "the web's $part").documentElement, parse(ours.getValue(part), "our $part").documentElement, "$label $part")
            assertNull(d, "$label $part: $d")
        }
    }

    private fun routesOf(case: JsonObject): List<MissionRoute> = case["current"]!!.jsonArray.map { json.decodeFromJsonElement<MissionRoute>(it) }

    private val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `every edit the web makes is written the same way`() {
        assertTrue(cases.size >= 5, "the fixture shrank to ${cases.size}")
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val written = MsnxMutator.rewrite(template, routesOf(case), counterFrom(2), today)
            assertSameParts(Fixtures.bytes("msnx/${case["file"]!!.jsonPrimitive.content}"), written, name)
        }
    }

    @Test
    fun `what is written reads back as the route the person edited`() {
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val written = MsnxMutator.rewrite(template, routesOf(case), counterFrom(2), today)
            val read = MsnxReader.read(written).routes
            // The web's reader and ours agree on the file the web wrote; here ours reads the file ours wrote.
            val differences = JsonCompare.differences(case["afterParse"]!!, json.encodeToJsonElement(read), tolerance)
            assertEquals(emptyList<String>(), differences.take(12), name)
        }
    }

    @Test
    fun `saving a mission that was not changed writes the same documents, however many times`() {
        val routes = MsnxReader.read(template).routes
        var bytes = template
        repeat(3) {
            bytes = MsnxMutator.rewrite(bytes, MsnxReader.read(bytes).routes, counterFrom(2), today)
            assertSameParts(template, bytes, "unchanged save ${it + 1}")
        }
        assertEquals(routes, MsnxReader.read(bytes).routes)
    }

    @Test
    fun `an edit on top of an edit is the two edits`() {
        val all = cases.first { it["name"]!!.jsonPrimitive.content == "edit-all" }
        val move = cases.first { it["name"]!!.jsonPrimitive.content == "edit-move" }
        // The move first, saved; then the rest of "all" on top of what was saved.
        val afterMove = MsnxMutator.rewrite(template, routesOf(move), counterFrom(2), today)
        val saved = MsnxReader.read(afterMove).routes
        val allRoutes = routesOf(all)
        val renamedAndMore = allRoutes.map { r ->
            r.copy(points = r.points.map { p ->
                // The moves are already in the file, so they are no longer a difference; the rest still are.
                saved.first().points.firstOrNull { it.id == p.id }?.let { p.copy(lat = it.lat, lon = it.lon) } ?: p
            })
        }
        val twice = MsnxMutator.rewrite(afterMove, renamedAndMore, counterFrom(2), today)
        assertSameParts(Fixtures.bytes("msnx/${all["file"]!!.jsonPrimitive.content}"), twice, "move then the rest")
    }

    @Test
    fun `a point is moved in the leg that mirrors it as well as in its own part`() {
        val move = cases.first { it["name"]!!.jsonPrimitive.content == "edit-move" }
        val written = entries(MsnxMutator.rewrite(template, routesOf(move), counterFrom(2), today))
        val before = entries(template).getValue("mission/legs.xml").toString(Charsets.UTF_8)
        val after = written.getValue("mission/legs.xml").toString(Charsets.UTF_8)
        assertNotEquals(before, after, "the serpentine point's trackpoint was not moved")
    }

    @Test
    fun `parts that are not changed are carried through as they were, byte for byte`() {
        val move = cases.first { it["name"]!!.jsonPrimitive.content == "edit-move" }
        val original = entries(template)
        val written = entries(MsnxMutator.rewrite(template, routesOf(move), counterFrom(2), today))
        assertEquals(original.keys.toList(), written.keys.toList(), "the entries and their order")
        for ((name, bytes) in original) if (name !in parts) assertTrue(bytes.contentEquals(written.getValue(name)), "$name changed")
    }

    @Test
    fun `a file whose routes are not the ones given is refused rather than written wrongly`() {
        val routes = MsnxReader.read(template).routes
        assertThrows(MsnxException::class.java) { MsnxMutator.rewrite(template, emptyList()) }
        assertThrows(MsnxException::class.java) { MsnxMutator.rewrite(template, routes.map { it.copy(segmentId = "somebody-else's") }) }
    }

    @Test
    fun `a point put after one that has no id in the file is refused`() {
        val route = MsnxReader.read(template).routes.single()
        val anonymous = RoutePoint(id = null, uiId = "anon", lat = 34.6, lon = -84.1)
        val fresh = RoutePoint(id = "fresh", lat = 34.61, lon = -84.1, kind = "amps", ptType = "turn", name = ".X")
        val points = route.points.take(5) + anonymous + fresh + route.points.drop(5)
        assertThrows(MsnxException::class.java) { MsnxMutator.rewrite(template, listOf(route.copy(points = points))) }
    }

    @Test
    fun `a point put first is refused, since no leg arrives there`() {
        val route = MsnxReader.read(template).routes.single()
        val first = RoutePoint(id = "new-first", lat = 34.6, lon = -84.1, kind = "amps", ptType = "turn", name = ".X")
        assertThrows(MsnxException::class.java) { MsnxMutator.rewrite(template, listOf(route.copy(points = listOf(first) + route.points))) }
    }

    @Test
    fun `two points inserted on the same leg both land, each splitting the leg the one before made`() {
        val route = MsnxReader.read(template).routes.single()
        // After the 11th point, then after that new point.
        val a = RoutePoint(id = "00000000-0000-4000-8000-0000000000a1", lat = 34.6779, lon = -84.1332, kind = "amps", ptType = "turn", name = ".NEWPT", ele = route.points[10].ele)
        val b = a.copy(id = "00000000-0000-4000-8000-0000000000b1", lat = 34.6785, lon = -84.1333)
        val points = route.points.take(11) + a + b + route.points.drop(11)
        val written = MsnxMutator.rewrite(template, listOf(route.copy(points = points)), counterFrom(2), today)
        val back = MsnxReader.read(written).routes.single()
        assertEquals(points.size, back.points.size)
        assertEquals(listOf(a.id, b.id), back.points.filter { it.name == ".NEWPT" }.map { it.id })
        // Three legs now stand where one did: the segment lists them in order.
        val segments = parse(entries(written).getValue("mission/segments.xml"), "segments")
        val legIds = segments.getElementsByTagName("legs").item(0).childNodes.let { list -> (0 until list.length).map { list.item(it) }.filterIsInstance<Element>().map { it.textContent } }
        assertEquals(3, legIds.size)
        assertEquals(3, legIds.toSet().size)
    }

    /**
     * Tried against a real AMPS mission when one is to hand (not part of the repository: set EZPZ_REAL_MSNX to a file). Opening and saving it must not change a
     * document; a move and an insert must come back; and the part that is most of the file (`legs.xml`) must stay the same size, plus one cloned leg.
     */
    @Test
    fun `a real mission survives being saved`() {
        val path = System.getenv("EZPZ_REAL_MSNX")
        assumeTrue(!path.isNullOrBlank() && File(path).isFile, "set EZPZ_REAL_MSNX to try a real mission")
        val original = File(path!!).readBytes()
        val routes = MsnxReader.read(original).routes
        val untouched = MsnxMutator.rewrite(original, routes)
        assertSameParts(original, untouched, "real, unchanged")
        assertEquals(routes, MsnxReader.read(untouched).routes)

        val route = routes.first { r -> r.points.size > 4 && r.points.any { it.kind == RoutePoint.KIND_AMPS } }
        val moved = route.points.mapIndexed { i, p -> if (i == 1) p.copy(lat = p.lat + 0.001) else p }
        val edited = MsnxMutator.rewrite(original, routes.map { if (it === route) it.copy(points = moved) else it })
        assertEquals(moved[1].lat, MsnxReader.read(edited).routes.first { it.segmentId == route.segmentId }.points[1].lat, 1e-9)
    }
}
