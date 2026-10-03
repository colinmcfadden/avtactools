package app.ezpztac.formats

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Wind
import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Held to the files the web itself exports (`contracts/fixtures/msnx/sketch-*.msnx`), built there with a stub that hands out the ids
 * `00000000-0000-4000-8000-000000000001`, `…02`, and so on. A counter in the same order gives the same ids, so a part that differs differs
 * in *content*: the comparison is of the parsed documents (names, namespaces, attributes, text, order), not of bytes, because the two serializers
 * need not agree on how to write the same document.
 */
class MsnxWriterFixtureTest {
    private val template = Fixtures.repoBytes("frontend/public/msnx_template.msnx")
    private val today = LocalDate.of(2026, 7, 15)

    private val parts = listOf(
        "mission.gpx", "mission/points.xml", "mission/legs.xml", "mission/segments.xml", "mission/routes.xml", "mission/mission.xml", "mission/missionsummary.xml",
    )

    private fun counter(): () -> String {
        var n = 0
        return { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
    }

    private fun point(id: String, lat: Double, lon: Double, kind: String, ptType: String?, name: String) =
        RoutePoint(id = id, lat = lat, lon = lon, kind = kind, ptType = ptType, name = name)

    private val sketch = SketchRoute(
        id = "sketch-1", name = "FIXTURE ROUTE", color = "#FF453A",
        points = listOf(
            point("s1", 34.5, -84.2, "amps", "turn", ".SP"),
            point("sh1", 34.52, -84.15, "shaping", null, ""),
            point("c1", 34.55, -84.05, "amps", "ip", ".IP1"),
            point("sh2", 34.58, -84.02, "shaping", null, ""),
            point("sh3", 34.6, -84.0, "shaping", null, ""),
            point("t1", 34.65, -83.95, "amps", "target", ".LZ1"),
        ),
        elevations = mapOf("s1" to 1200.0, "c1" to 1500.0, "t1" to 1350.0),
        plan = RoutePlan(
            airspeed = Airspeed(110.0, "indicated"), altitude = AltitudeSetting(500.0, "agl"), wind = Wind(270.0, 15.0), tempC = 25.0, date = "2026-07-15",
            perPoint = mapOf(
                "c1" to PointOverride(altitude = AltitudeSetting(1800.0, "msl"), wind = Wind(300.0, 20.0)),
                "t1" to PointOverride(clock = "10:00:00"),
            ),
        ),
    )

    private val second = SketchRoute(
        id = "sketch-2", name = "SECOND ROUTE", color = "#0A84FF",
        points = listOf(
            point("a1", 34.4, -84.3, "amps", "turn", ".SP"),
            point("a2", 34.45, -84.1, "amps", "turn", ".CP1"),
            point("a3", 34.5, -83.9, "amps", "target", ".PZ1"),
        ),
        plan = RoutePlan(airspeed = Airspeed(90.0, "ground"), altitude = AltitudeSetting(200.0, "agl"), date = "2026-07-15"),
    )

    // The edges the two routes above do not reach (the web's EDGES, NOON and MIDNIGHT): an empty name, no kind, an unknown type, shaping points before the first and
    // after the last AMPS point, both hemispheres, special characters, MSL, a charted elevation, and clocks either side of noon and midnight.
    private val edges = SketchRoute(
        id = "sketch-3", name = "R&D <TEST> \"edges\"", color = "#32D74B",
        points = listOf(
            point("e0", 34.1, -84.1, "shaping", null, ""),
            point("e1", 0.0, 0.0, "amps", "weird", "").copy(ele = 12.5),
            point("e2", -12.5, -84.2, "shaping", null, "").copy(ele = 7.0),
            RoutePoint(id = "e3", lat = -12.4, lon = 20.5, name = ".A&B"),
            point("e4", -12.3, 20.6, "amps", "target", ".LZ2").copy(chartElevationFt = 800.0),
            point("e5", -12.2, 20.7, "shaping", null, ""),
        ),
        elevations = mapOf("e1" to 500.0, "e3" to 1500.0),
        plan = RoutePlan(
            airspeed = Airspeed(95.0, "true"), altitude = AltitudeSetting(300.0, "msl"), wind = Wind(90.0, 5.0), date = "2026-12-31",
            perPoint = mapOf(
                "e3" to PointOverride(airspeed = Airspeed(70.0, "weird"), altitude = AltitudeSetting(200.0, "agl")),
                "e1" to PointOverride(clock = "23:50:00"),
            ),
        ),
    )

    private fun clockWalk(name: String, first: String) = SketchRoute(
        id = "sketch-$name", name = name, color = "#0A84FF",
        points = (0..3).map { i -> point("${name[0]}$i", 34.5 + i * 0.05, -84.2, "amps", if (i == 3) "target" else "turn", ".${name[0]}$i") },
        plan = RoutePlan(airspeed = Airspeed(60.0, "ground"), wind = Wind(0.0, 0.0), date = "2026-07-15", perPoint = mapOf("${name[0]}0" to PointOverride(clock = first))),
    )

    // -- Reading a package and comparing documents -----------------------------------------------------------------------

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

    private fun assertSameAsWeb(fixture: String, built: ByteArray) {
        val web = entries(Fixtures.bytes("msnx/$fixture"))
        val ours = entries(built)
        for (part in parts) {
            val expected = parse(web.getValue(part), "the web's $part")
            val actual = parse(ours.getValue(part), "our $part")
            val d = difference(expected.documentElement, actual.documentElement, "$fixture $part")
            assertNull(d, "$fixture $part: $d")
        }
    }

    // -- The cases ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a sketched route is exported as the web exports it`() {
        val built = MsnxWriter.build(template, listOf(sketch), "Fixture mission", counter(), today)
        assertSameAsWeb("sketch-export.msnx", built)
    }

    @Test
    fun `two routes in one mission are exported as the web exports them`() {
        val built = MsnxWriter.build(template, listOf(sketch, second), "Two routes", counter(), today)
        assertSameAsWeb("sketch-two-routes.msnx", built)
    }

    @Test
    fun `the cases the first two do not reach are exported as the web exports them`() {
        val built = MsnxWriter.build(template, listOf(edges, clockWalk("NOON", "11:58:30"), clockWalk("MIDNIGHT", "23:58:30")), "Edges & <more>", counter(), today)
        assertSameAsWeb("sketch-edges.msnx", built)
    }

    @Test
    fun `what the writer does not touch is carried through byte for byte`() {
        val built = entries(MsnxWriter.build(template, listOf(sketch), "Fixture mission", counter(), today))
        val original = entries(template)
        assertEquals(original.keys.toList(), built.keys.toList())                          // the same entries, in the same order
        for (name in original.keys - parts.toSet()) assertTrue(original.getValue(name).contentEquals(built.getValue(name)), name)
    }

    @Test
    fun `the mission parts carry a byte order mark and one declaration, and the GPX has neither mark nor second declaration`() {
        val built = entries(MsnxWriter.build(template, listOf(sketch), "Fixture mission", counter(), today))
        for (part in parts - "mission.gpx") {
            val text = built.getValue(part).toString(Charsets.UTF_8)
            assertTrue(text.startsWith("﻿<?xml version=\"1.0\" encoding=\"utf-8\"?><"), part)
            assertEquals(1, Regex("<\\?xml").findAll(text).count(), part)
        }
        val gpx = built.getValue("mission.gpx").toString(Charsets.UTF_8)
        assertTrue(gpx.startsWith("<?xml version=\"1.0\"?><gpx"))
        assertEquals(1, Regex("<\\?xml").findAll(gpx).count())
    }

    @Test
    fun `the file it makes is one the reader reads back as the route that went in`() {
        val built = MsnxWriter.build(template, listOf(sketch, second), "Two routes", counter(), today)
        val mission = MsnxReader.read(built)
        assertEquals(listOf("FIXTURE ROUTE", "SECOND ROUTE"), mission.routes.map { it.name })
        // AMPS points only: the shaping points are geometry on a leg, not points of the route.
        assertEquals(listOf(".SP", ".IP1", ".LZ1"), mission.routes[0].points.filter { it.kind != RoutePoint.KIND_SHAPING }.map { it.name })
        assertEquals(listOf(".SP", ".CP1", ".PZ1"), mission.routes[1].points.filter { it.kind != RoutePoint.KIND_SHAPING }.map { it.name })
    }

    @Test
    fun `a route with fewer than two designated points is refused, naming it`() {
        val lonely = sketch.copy(points = listOf(point("s1", 34.5, -84.2, "amps", "turn", ".SP"), point("sh1", 34.52, -84.15, "shaping", null, "")))
        val e = assertThrows(MsnxException::class.java) { MsnxWriter.build(template, listOf(lonely), "x", counter(), today) }
        assertTrue(e.message!!.contains("FIXTURE ROUTE") && e.message!!.contains("two designated"), e.message)
    }

    @Test
    fun `nothing to export is refused`() {
        assertThrows(MsnxException::class.java) { MsnxWriter.build(template, emptyList(), "x", counter(), today) }
    }

    @Test
    fun `something that is not a mission package is refused`() {
        assertThrows(MsnxException::class.java) { MsnxWriter.build("not a zip".toByteArray(), listOf(sketch), "x", counter(), today) }
    }
}
