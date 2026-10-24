package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Strip charts (docs/MISSION_BRIEF.md §2.6): the rules the web's briefCompileFixtures.test.js asks of them directly, beside compile.json's
 * strip cases, which CompileFixtureTest holds every number of.
 */
class StripChartsTest {
    private val cases = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray.map { it.jsonObject }
    private val amb = BriefFormats.format("amb")!!

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }
    private fun compile(c: JsonObject) = CompileBrief.compile(BriefFormats.format(c.text("format"))!!, c["document"], c["plan"], c["context"])
    private fun case(named: String) = cases.single { Regex(named).containsMatchIn(it.text("name")) }
    private val opDk by lazy { compile(cases[0]) }
    private val dog by lazy { compile(case("dogleg")) }

    private fun metres(a: JsonElement, b: JsonElement): Double {
        val (lat1, lon1) = a.jsonArray.map { it.jsonPrimitive.double }
        val (lat2, lon2) = b.jsonArray.map { it.jsonPrimitive.double }
        val r = Math.PI / 180
        val h = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
        return 2 * 3440.065 * 1852 * asin(sqrt(h))
    }

    private fun first(path: JsonObject) = path["path"]!!.jsonArray.first()
    private fun last(path: JsonObject) = path["path"]!!.jsonArray.last()

    /** A route of OP DK's ALDERAAN with [points] instead, timed from its first. */
    private fun withRoute(points: String, document: String = "{}"): JsonObject {
        val alderaan = cases[0]["plan"]!!.jsonObject["routeSets"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject["routes"]!!.jsonArray[0].jsonObject
        val plan = JsonObject(alderaan["plan"]!!.jsonObject + ("perPoint" to Json.parseToJsonElement("""{"a": {"clock": "08:00:00"}}""")))
        val route = JsonObject(alderaan + mapOf("id" to JsonPrimitive("r"), "name" to JsonPrimitive("R"), "points" to Json.parseToJsonElement(points), "plan" to plan))
        val planJson = Json.parseToJsonElement("""{"name": "X", "routeSets": [{"id": "s", "name": "S", "data": {"version": 1, "routes": [$route]}}]}""")
        return CompileBrief.compile(amb, Json.parseToJsonElement(document), planJson, cases[0]["context"])
    }

    private fun pt(id: String, lat: Double, lon: Double) = """{"id": "$id", "name": "${id.uppercase()}", "ptType": "turn", "kind": "amps", "lat": $lat, "lon": $lon}"""

    @Test
    fun `follow the nav logs, three to a page, a route's strips together, before the IIMC card`() {
        val pages = opDk.list("pages")
        val kinds = pages.map { it.text("kind") }
        assertTrue(kinds.lastIndexOf("navLog") < kinds.indexOf("strips"))
        assertTrue(kinds.lastIndexOf("strips") < kinds.indexOf("iimc"))
        val stripPages = pages.filter { it.text("kind") == "strips" }
        assertTrue(stripPages.all { it["strips"]!!.jsonArray.size in 1..3 })
        assertEquals(opDk.list("strips").map { it.text("id") }, stripPages.flatMap { p -> p["strips"]!!.jsonArray.map { it.jsonPrimitive.content } })
        assertEquals(
            listOf("Strip charts · ALDERAAN (1–3 of 6)", "Strip charts · ALDERAAN (4–6 of 6)", "Strip charts · BESPIN (1–3 of 6)", "Strip charts · BESPIN (4–6 of 6)"),
            stripPages.map { it.text("title") },
        )
        assertEquals(listOf("Strip charts · DOGLEG (1–3 of 4)", "Strip charts · DOGLEG (4 of 4)"), dog.list("pages").filter { it.text("kind") == "strips" }.map { it.text("title") })
    }

    @Test
    fun `run each route on unbroken, each strip from where the last ended, none longer than the strip less its margins`() {
        listOf(opDk, dog).forEach { model ->
            model.list("flow").filter { it.text("kind") == "route" }.forEach { route ->
                val strips = model.list("strips").filter { it.text("ref") == route.text("ref") }
                strips.forEachIndexed { i, s ->
                    assertEquals(i + 1, s["part"]!!.jsonPrimitive.int)
                    assertEquals(strips.size, s["parts"]!!.jsonPrimitive.int)
                    if (i > 0) assertEquals(last(strips[i - 1]), first(s))
                    assertTrue(metres(first(s), last(s)) <= s["length"]!!.jsonPrimitive.double * 0.9 + 1)
                }
            }
        }
    }

    @Test
    fun `draw at 1-100,000 unless the brief asks 1-250,000, and none when strips are off or a route has no legs`() {
        val first = opDk.list("strips").first()
        assertEquals(listOf("100000", "21500", "5500"), listOf("scale", "length", "width").map { first.text(it) })
        assertEquals(listOf("250000", "53750", "13750"), listOf("scale", "length", "width").map { dog.list("strips").first().text(it) })
        val odd = CompileBrief.compile(amb, Json.parseToJsonElement("""{"options": {"stripScale": 50000}}"""), cases[0]["plan"], cases[0]["context"])
        assertEquals("100000", odd.list("strips").first().text("scale"))
        assertEquals(emptyList<JsonObject>(), compile(cases[2]).list("strips"))
        assertEquals(emptyList<JsonObject>(), compile(cases[4]).list("strips"))
        val off = CompileBrief.compile(amb, Json.parseToJsonElement("""{"options": {"strips": false}}"""), cases[0]["plan"], cases[0]["context"])
        assertTrue(off.list("pages").none { it.text("kind") == "strips" } && off.list("strips").isEmpty())
    }

    @Test
    fun `never make a stub, nor turn a piece with no length yet to the north`() {
        // A leg a little longer than a strip, then a turn onto another long leg, at both scales.
        fun ahead(lat: Double, lon: Double, course: Double, m: Double): Pair<Double, Double> {
            val r = Math.PI / 180
            return lat + m * cos(course * r) / 111320 to lon + m * sin(course * r) / (111320 * cos(lat * r))
        }
        listOf(100000 to 19650.0, 250000 to 48575.0).forEach { (scale, length) ->
            val b = ahead(34.0, -84.0, 120.0, length)
            val c = ahead(b.first, b.second, 30.0, 60000.0)
            val model = withRoute("[${pt("a", 34.0, -84.0)}, ${pt("b", b.first, b.second)}, ${pt("c", c.first, c.second)}]", """{"options": {"stripScale": $scale}}""")
            val strips = model.list("strips")
            assertTrue(strips.isNotEmpty())
            strips.forEach { assertTrue(metres(first(it), last(it)) > it["length"]!!.jsonPrimitive.double * 0.9 * 0.25, "a stub at 1:$scale") }
            assertTrue(strips.none { it.text("courseText") == "000°" })
        }
    }

    @Test
    fun `give no strips past 84 degrees of latitude, and a far-flung point costs no more than the most strips`() {
        val polar = withRoute("[${pt("a", 80.0, 10.0)}, ${pt("b", 85.0, 10.0)}]")
        assertTrue(polar.list("strips").isEmpty())
        assertTrue(polar.list("problems").any { it.text("message") == "R runs beyond 84° of latitude, past what the map and the MGRS grid show: it has no strip charts." })
        assertTrue(withRoute("[${pt("a", 34.0, -84.0)}, ${pt("b", 1e6, -84.0)}]").list("strips").isEmpty())
        val started = System.nanoTime()
        val far = withRoute("[${pt("a", -80.0, -84.0)}, ${pt("b", 83.9, -84.0)}]")
        assertEquals(StripCharts.MAX_STRIPS, far.list("strips").size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
        assertTrue(far.list("problems").any { it.text("message") == "Strip charts stop at 60 strips: the rest of R has none." })
        // A route that goes nowhere has none.
        assertTrue(withRoute("[${pt("a", 34.0, -84.0)}, ${pt("b", 34.0, -84.0)}]").list("strips").isEmpty())
    }

    @Test
    fun `say each leg as the nav log says it`() {
        val log = opDk["geometry"]!!.jsonObject["routes"]!!.jsonObject["route:set-opdk/r-alderaan"]!!.jsonObject.list("log")
        opDk.list("strips").filter { it.text("ref") == "route:set-opdk/r-alderaan" }.flatMap { it.list("legs") }.forEach { leg ->
            assertTrue(log.any { row -> listOf("from", "to", "course", "distance", "time", "altitude").all { row[it] == leg[it] } }, leg.toString())
        }
    }

    @Test
    fun `measure on the web's sphere, with V8's hypot`() {
        assertEquals(5.0, Sphere.hypot(3.0, 4.0))
        assertEquals(0.0, Sphere.hypot(0.0, -0.0))
        assertEquals(Double.POSITIVE_INFINITY, Sphere.hypot(Double.NaN, Double.NEGATIVE_INFINITY))
        assertTrue(Sphere.hypot(Double.NaN, 1.0).isNaN())
        assertEquals(1e300 * Math.sqrt(2.0), Sphere.hypot(1e300, 1e300), 1e286)                    // scaled, so it does not overflow
    }
}
