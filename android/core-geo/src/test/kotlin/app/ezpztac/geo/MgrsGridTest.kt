package app.ezpztac.geo

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Held to contracts/fixtures/mgrs/grid.json, the web's mgrsGrid.js run over the awkward views: every spacing, Norway and Svalbard, the
 * equator, the antimeridian, several world copies, covered edges, the caps and the views that draw nothing.
 *
 * Everything is compared as the web wrote it, but for what a JVM's maths library and a browser's may decide differently in a double's
 * last digit (AGENTS.md section 11):
 * - a line's polyline is sampled until it is within a quarter of a pixel of its curve, which could tip one sample's test the other way, so
 *   one with another count of points passes only if every point of each lies within a hundredth of a pixel of the other (the same count is
 *   compared point for point, to [POINT_DEG]: a line's end is found on its edge to a millimetre, so the two may land a millimetre apart);
 * - a line's label is placed where its line crosses the edge, found to 1e-10 degrees, so its box is compared to a thousandth of a pixel;
 * - the 100 km squares are labelled largest first, and squares mirrored across a zone boundary (16 and 17 either side of 84 W) have equal
 *   areas in exact arithmetic, so their order is the last digit's: the labels are compared as a set, which still fails if a different
 *   order placed a different label.
 */
class MgrsGridTest {
    private val fixture = Fixtures.load("mgrs/grid.json")

    private companion object {
        // About a centimetre: a line's end is found to a millimetre either side.
        const val POINT_DEG = 1e-7
    }
    private val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }

    private fun JsonElement.d() = jsonPrimitive.double

    private fun boundsOf(element: JsonElement?): GridBounds? = (element as? JsonObject)?.let {
        GridBounds(it["south"]!!.d(), it["west"]!!.d(), it["north"]!!.d(), it["east"]!!.d())
    }

    private fun optionsOf(element: JsonObject): GridOptions {
        val defaults = GridOptions()
        return GridOptions(
            pad = element["pad"]?.d() ?: defaults.pad,
            avoid = element["avoid"]?.jsonArray?.map { box -> box.jsonArray.let { GridBox(it[0].d(), it[1].d(), it[2].d(), it[3].d()) } } ?: emptyList(),
            maxLines = element["maxLines"]?.jsonPrimitive?.int ?: defaults.maxLines,
            maxPoints = element["maxPoints"]?.jsonPrimitive?.int ?: defaults.maxPoints,
            maxLabels = element["maxLabels"]?.jsonPrimitive?.int ?: defaults.maxLabels,
        )
    }

    // The result as the web writes it, but for each line's points, which are compared on their own.
    private fun json(result: GridResult): JsonObject = buildJsonObject {
        put("spacing", result.spacing?.let(::JsonPrimitive) ?: JsonNull)
        put("lines", buildJsonArray {
            result.lines.forEach { line ->
                add(buildJsonObject {
                    put("kind", line.kind)
                    line.zone?.let { put("zone", it) }
                    put("axis", line.axis)
                    put("value", line.value)
                })
            }
        })
        put("labels", buildJsonArray {
            result.labels.sortedWith(LABEL_ORDER).forEach { label ->
                add(buildJsonObject {
                    put("kind", label.kind)
                    label.edge?.let { put("edge", it) }
                    put("text", label.text)
                    label.zone?.let { put("zone", it) }
                    label.value?.let { put("value", it) }
                    put("lat", label.lat)
                    put("lon", label.lon)
                })
            }
        })
        put("dropped", buildJsonArray { result.dropped.forEach { add(JsonPrimitive(it)) } })
    }

    // The web's result with each line's points and each label's box taken out (compared on their own), and its labels in the set's order.
    private fun withoutPoints(expected: JsonObject): JsonObject = JsonObject(
        expected + mapOf(
            "lines" to JsonArray(expected["lines"]!!.jsonArray.map { line -> JsonObject(line.jsonObject - "points") }),
            "labels" to JsonArray(expected["labels"]!!.jsonArray.map { JsonObject(it.jsonObject - "box") }.sortedWith(JSON_LABEL_ORDER)),
        ),
    )

    private fun boxOf(label: JsonObject) = label["box"]!!.jsonArray.let { GridBox(it[0].d(), it[1].d(), it[2].d(), it[3].d()) }

    private fun keyOf(kind: String, edge: String?, text: String, zone: Int?, value: Double?) = listOf(kind, edge ?: "", text, zone?.toString() ?: "", value?.toString() ?: "")

    private val LABEL_ORDER = Comparator<GridLabel> { a, b -> compareKeys(keyOf(a.kind, a.edge, a.text, a.zone, a.value), keyOf(b.kind, b.edge, b.text, b.zone, b.value)) }
    private val JSON_LABEL_ORDER = Comparator<JsonObject> { a, b -> compareKeys(jsonKey(a), jsonKey(b)) }

    private fun jsonKey(label: JsonObject) = keyOf(
        label["kind"]!!.jsonPrimitive.content, label["edge"]?.jsonPrimitive?.content, label["text"]!!.jsonPrimitive.content,
        label["zone"]?.jsonPrimitive?.int, label["value"]?.d(),
    )

    private fun compareKeys(a: List<String>, b: List<String>): Int = a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0

    // Metres-free closeness on the map: how far a point is from a polyline, in degrees scaled to pixels at the case's scale.
    private fun offPolyline(p: LatLon, line: List<LatLon>, pxPerDeg: Double): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val dx = b.lon - a.lon
            val dy = b.lat - a.lat
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else max(0.0, min(1.0, ((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / len))
            best = min(best, hypot(p.lon - (a.lon + t * dx), p.lat - (a.lat + t * dy)) * pxPerDeg)
        }
        return if (line.size == 1) hypot(p.lon - line[0].lon, p.lat - line[0].lat) * pxPerDeg else best
    }

    private fun pointsDiffer(name: String, i: Int, expected: List<LatLon>, actual: List<LatLon>, metersPerPixel: Double): List<String> {
        if (expected.size == actual.size) {
            return expected.indices.mapNotNull { j ->
                val e = expected[j]
                val a = actual[j]
                if (kotlin.math.abs(e.lat - a.lat) <= POINT_DEG && kotlin.math.abs(e.lon - a.lon) <= POINT_DEG) null else "$name: line $i point $j: $e, got $a"
            }
        }
        // About a degree of latitude's width in pixels, generous for this check's purpose: a hundredth of a pixel either way.
        val pxPerDeg = 111320.0 / metersPerPixel
        val far = (expected.map { offPolyline(it, actual, pxPerDeg) } + actual.map { offPolyline(it, expected, pxPerDeg) }).maxOrNull() ?: 0.0
        return if (far <= 0.01) emptyList() else listOf("$name: line $i has ${actual.size} points for ${expected.size}, ${far} px apart")
    }

    @Test
    fun `every view's grid is the web's`() {
        assertTrue(cases.size >= 30, "the fixture shrank to ${cases.size}")
        val problems = mutableListOf<String>()
        var resampled = 0
        cases.forEach { case ->
            val name = case["name"]!!.jsonPrimitive.content
            val expected = case["expected"]!!.jsonObject
            val metersPerPixel = case["metersPerPixel"]!!.d()
            val result = MgrsGrid.grid(boundsOf(case["bounds"]), metersPerPixel, optionsOf(case["options"]!!.jsonObject))
            problems += JsonCompare.differences(withoutPoints(expected), json(result), tolerance = 1e-11, path = name)
            val wantBoxes = expected["labels"]!!.jsonArray.map { it.jsonObject }.sortedWith(JSON_LABEL_ORDER).map(::boxOf)
            val gotBoxes = result.labels.sortedWith(LABEL_ORDER).map { it.box }
            wantBoxes.zip(gotBoxes).forEachIndexed { i, (want, got) ->
                val off = listOf(want.x0 - got.x0, want.y0 - got.y0, want.x1 - got.x1, want.y1 - got.y1).maxOf { kotlin.math.abs(it) }
                if (off > 1e-3) problems += "$name: label $i box $want, got $got"
            }
            val lines = expected["lines"]!!.jsonArray
            if (lines.size == result.lines.size) {
                lines.forEachIndexed { i, line ->
                    val want = line.jsonObject["points"]!!.jsonArray.map { p -> p.jsonArray.let { LatLon(it[0].d(), it[1].d()) } }
                    if (want.size != result.lines[i].points.size) resampled++
                    problems += pointsDiffer(name, i, want, result.lines[i].points, metersPerPixel)
                }
            }
        }
        assertEquals(emptyList<String>(), problems.take(20), "${problems.size} differences")
        // Resampling is the allowance above, not the rule: if most lines took another count, something else is wrong.
        assertTrue(resampled <= 3, "$resampled polylines were sampled differently from the web's")
    }

    @Test
    fun `the spacing is the finest whose lines stay 70 pixels apart, and digits are a line's value within its square`() {
        assertEquals(100.0, MgrsGrid.gridSpacing(1.2))
        assertEquals(1000.0, MgrsGrid.gridSpacing(14.2))
        assertEquals(10000.0, MgrsGrid.gridSpacing(14.3))
        assertNull(MgrsGrid.gridSpacing(1500.0))
        assertNull(MgrsGrid.gridSpacing(0.0))
        assertNull(MgrsGrid.gridSpacing(Double.NaN))
        assertEquals("66", MgrsGrid.principalDigits(766000.0, 1000.0))
        assertEquals("06", MgrsGrid.principalDigits(706000.0, 1000.0))
        assertEquals("660", MgrsGrid.principalDigits(766000.0, 100.0))
        assertEquals("6", MgrsGrid.principalDigits(760000.0, 10000.0))
    }

    @Test
    fun `the equator is 0, never -0, however its multiples are counted, so it is one line wherever it is labelled`() {
        val counted = MgrsGrid.multiplesIn(-30.0, 50.0, 100.0)
        assertEquals(listOf(0.0), counted)
        assertTrue(1 / counted.single() > 0, "-0.0 is a different key from 0.0 in a set")
        assertEquals(setOf(0.0), (MgrsGrid.multiplesIn(-130.0, 50.0, 100.0) + counted).filter { it == 0.0 }.toSet())
    }

    @Test
    fun `the helpers the grid uses agree with the converter`() {
        val utm = MgrsConverter.toUtm(34.62, -84.18)!!
        val inZone = MgrsConverter.toUtmInZone(34.62, -84.18, utm.zone)!!
        assertEquals(utm, inZone)
        val back = MgrsConverter.fromUtm(utm.zone, false, utm.easting, utm.northing)!!
        assertEquals(34.62, back.lat, 1e-9)
        assertEquals(-84.18, back.lon, 1e-9)
        val grid = MgrsConverter.toMgrs(34.62, -84.18)!!
        assertEquals(grid.square, MgrsConverter.squareLetters(utm.zone, utm.easting, utm.northing))
        assertEquals(-81.0, MgrsConverter.centralMeridian(17))
        assertEquals('S', MgrsConverter.bandFor(34.62))
        assertEquals('X', MgrsConverter.bandFor(84.0))
        assertNull(MgrsConverter.toUtmInZone(84.1, 0.0, 31))
        assertTrue(MgrsConverter.toUtmInZone(84.0, 0.0, 31) != null)                // the top of band X is in it, as a grid line's end needs
        assertNull(MgrsConverter.toUtm(84.0, 0.0))                                     // where toUtm's range stops short of it
        assertNull(MgrsConverter.toUtmInZone(34.0, 0.0, 61))
        assertNull(MgrsConverter.fromUtm(0, false, 500000.0, 0.0))
        // Zone 60's east edge, +180, measured from the near side rather than wrapped to the far one.
        assertTrue(MgrsConverter.toUtmInZone(-17.0, 180.0, 60)!!.easting > 500000)
    }
}
