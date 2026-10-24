package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * contracts/fixtures/brief/strip-grid.json: the MGRS 1 km grid on each strip chart of the briefs in brief/compile.json, as the web adds it when
 * the PDF is asked for (stripGrid.js). Each strip is read from compile.json's expected model, as the app reads the model it compiled.
 *
 * Compared as the web wrote it but for what a JVM's maths library and a browser's may decide differently in a double's last digit, the
 * allowances contracts/fixtures/mgrs/grid.json is held to (MgrsGridTest): a line sampled with another count of points passes when every
 * point of each lies within a hundredth of a pixel (at the grid's 10 m a pixel, 10 cm) of the other; the same count is compared point for
 * point to 1e-7 degrees (a line's end is found on its edge to a millimetre).
 */
class StripGridTest {
    private val grids = Fixtures.load("brief/strip-grid.json")["cases"]!!.jsonArray.map { it.jsonObject }
    private val compiled = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray.map { it.jsonObject }

    private companion object {
        const val POINT_DEG = 1e-7
        const val METRES_PER_PX = 10.0
    }

    private fun stripsOf(caseName: String): Map<String, JsonObject> {
        val case = compiled.first { it["name"]!!.jsonPrimitive.content == caseName }
        return case["expected"]!!.jsonObject["strips"]!!.jsonArray.associate { s -> s.jsonObject["id"]!!.jsonPrimitive.content to s.jsonObject }
    }

    private fun pointsOf(line: JsonObject): List<DoubleArray> =
        line["points"]!!.jsonArray.map { p -> p.jsonArray.let { doubleArrayOf(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }

    // How far a point is from a polyline, in degrees scaled to pixels at the grid's scale.
    private fun offPolyline(p: DoubleArray, line: List<DoubleArray>, pxPerDeg: Double): Double {
        if (line.size == 1) return hypot(p[1] - line[0][1], p[0] - line[0][0]) * pxPerDeg
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val dx = b[1] - a[1]
            val dy = b[0] - a[0]
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else max(0.0, min(1.0, ((p[1] - a[1]) * dx + (p[0] - a[0]) * dy) / len))
            best = min(best, hypot(p[1] - (a[1] + t * dx), p[0] - (a[0] + t * dy)) * pxPerDeg)
        }
        return best
    }

    private fun lineDifferences(where: String, expected: JsonObject, actual: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        listOf("kind", "axis", "text").forEach { key ->
            val e = expected[key]
            val a = actual[key]
            val same = if (e is JsonNull || e == null) a is JsonNull || a == null else (e as JsonPrimitive).contentOrNull == (a as? JsonPrimitive)?.contentOrNull
            if (!same) problems += "$where.$key: $e, got $a"
        }
        val ez = (expected["zone"] as? JsonPrimitive)?.intOrNull
        val az = (actual["zone"] as? JsonPrimitive)?.intOrNull
        if (ez != az) problems += "$where.zone: $ez, got $az"
        if (abs(expected["value"]!!.jsonPrimitive.double - actual["value"]!!.jsonPrimitive.double) > 1e-6) problems += "$where.value: ${expected["value"]}, got ${actual["value"]}"
        val want = pointsOf(expected)
        val got = pointsOf(actual)
        if (want.size == got.size) {
            want.indices.forEach { j ->
                if (abs(want[j][0] - got[j][0]) > POINT_DEG || abs(want[j][1] - got[j][1]) > POINT_DEG) problems += "$where point $j: ${want[j].toList()}, got ${got[j].toList()}"
            }
        } else {
            val pxPerDeg = 111320.0 / METRES_PER_PX
            val far = (want.map { offPolyline(it, got, pxPerDeg) } + got.map { offPolyline(it, want, pxPerDeg) }).maxOrNull() ?: 0.0
            if (far > 0.01) problems += "$where has ${got.size} points for ${want.size}, $far px apart"
        }
        return problems
    }

    @TestFactory
    fun `every strip of strip-grid json is gridded as the web grids it`(): List<DynamicTest> = grids.map { case ->
        val name = case["name"]!!.jsonPrimitive.content
        DynamicTest.dynamicTest(name) {
            val strips = stripsOf(name)
            val problems = mutableListOf<String>()
            var resampled = 0
            case["strips"]!!.jsonArray.forEach { s ->
                val want = s.jsonObject
                val id = want["id"]!!.jsonPrimitive.content
                val strip = strips.getValue(id)
                StripGrid.stripCorners(strip).zip(want["corners"]!!.jsonArray).forEachIndexed { i, (got, expected) ->
                    val e = expected.jsonArray
                    if (abs(Js.round(got[0] * 1e7) / 1e7 - e[0].jsonPrimitive.double) > POINT_DEG ||
                        abs(Js.round(got[1] * 1e7) / 1e7 - e[1].jsonPrimitive.double) > POINT_DEG
                    ) problems += "$id corner $i: $e, got ${got.toList()}"
                }
                val grid = StripGrid.stripGrid(strip)
                val wantGrid = want["grid"]!!.jsonObject
                assertEquals(wantGrid["squares"], grid["squares"], "$id squares")
                assertEquals(wantGrid["others"], grid["others"], "$id others")
                val wantLines = wantGrid["lines"]!!.jsonArray
                val gotLines = grid["lines"]!!.jsonArray
                if (wantLines.size != gotLines.size) {
                    problems += "$id: ${gotLines.size} lines for ${wantLines.size}"
                } else {
                    wantLines.zip(gotLines).forEachIndexed { i, (e, a) ->
                        if (pointsOf(e.jsonObject).size != pointsOf(a.jsonObject).size) resampled++
                        problems += lineDifferences("$id line $i", e.jsonObject, a.jsonObject)
                    }
                }
            }
            assertEquals(emptyList<String>(), problems.take(20), "${problems.size} differences")
            // Resampling is the allowance, not the rule: if many lines took another count, something else is wrong.
            assertTrue(resampled <= 3, "$resampled lines were sampled differently from the web's")
        }
    }

    @Test
    fun `the fixture covers every brief with strip charts but the one at the cap`() {
        val withStrips = compiled.filter { c ->
            val n = c["expected"]!!.jsonObject["strips"]!!.jsonArray.size
            n in 1 until StripCharts.MAX_STRIPS
        }.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(withStrips, grids.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `the squares beside a strip are the ones its digits belong to, as where it reaches into zone 17`() {
        val fifth = stripsOf(compiled[0]["name"]!!.jsonPrimitive.content).getValue("route:set-opdk/r-alderaan#5")
        val grid = StripGrid.stripGrid(fifth)
        assertEquals(listOf("16S GD"), grid["squares"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(grid["others"]!!.jsonArray.any { it.jsonPrimitive.content.startsWith("17S") })
        assertTrue(grid["lines"]!!.jsonArray.any { (it.jsonObject["zone"] as? JsonPrimitive)?.intOrNull == 17 })
    }

    @Test
    fun `the grid is added only to a model with strips, and changes nothing else`() {
        val model = compiled[0]["expected"]!!.jsonObject
        val gridded = StripGrid.withStripGrids(model)
        assertEquals(model.keys.toList(), gridded.keys.toList())
        model.keys.filter { it != "strips" }.forEach { assertSame(model[it], gridded[it], it) }
        val strips = gridded["strips"]!!.jsonArray.map { it.jsonObject }
        assertTrue(strips.all { it["grid"] is JsonObject })
        assertEquals(model["strips"], JsonArray(strips.map { JsonObject(it - "grid") }))
        val none = JsonObject(model + ("strips" to JsonArray(emptyList())))
        assertSame(none, StripGrid.withStripGrids(none))
    }

    @Test
    fun `a strip with no numbers draws no grid and names no square`() {
        val blank = JsonObject(mapOf("id" to JsonPrimitive("r#1")))
        val grid = StripGrid.stripGrid(blank)
        assertEquals(0, grid["lines"]!!.jsonArray.size)
        assertEquals(0, grid["squares"]!!.jsonArray.size)
        assertEquals(0, grid["others"]!!.jsonArray.size)
    }
}
