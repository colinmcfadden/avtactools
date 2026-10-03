package app.ezpztac.planning

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Wind
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Held to contracts/fixtures/routes/sketch.json: what the web's `sketchOps` make of a sketched route, case by case. */
class SketchFixtureTest {
    private val fixture = Fixtures.load("routes/sketch.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun label(c: JsonObject) = (c["label"] ?: c["name"])!!.jsonPrimitive.content

    private fun route(element: JsonElement) = json.decodeFromJsonElement<SketchRoute>(element)
    private fun encoded(route: SketchRoute?): JsonElement = route?.let { json.encodeToJsonElement(it) } ?: JsonNull

    private fun counter(prefix: String): () -> String {
        var n = 0
        return { "$prefix${++n}" }
    }

    private fun assertSame(expected: JsonElement, actual: JsonElement, what: String) {
        val differences = JsonCompare.differences(expected, actual)
        assertTrue(differences.isEmpty(), "$what: ${differences.take(8)}")
    }

    private fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.doubleOrNull(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun airspeed(o: JsonObject) = Airspeed(o.double("value"), o.text("type")!!)
    private fun altitude(o: JsonObject) = AltitudeSetting(o.double("value"), o.text("ref")!!)
    private fun wind(o: JsonObject) = Wind(o.double("dirTrue"), o.double("speedKts"))

    @Test
    fun `the standard attack profile is the same for every length of route`() {
        val all = cases("auto")
        assertTrue(all.size >= 13)
        for (c in all) {
            val index = c.getValue("index").jsonPrimitive.int
            val last = c.getValue("lastIndex").jsonPrimitive.int
            val expected = c.getValue("expected")
            val actual = SketchOps.autoDesignation(index, last)
            if (expected is JsonNull) assertEquals(null, actual, "index $index of $last")
            else {
                assertEquals(expected.jsonObject.text("ptType"), actual?.ptType, "type at $index of $last")
                assertEquals(expected.jsonObject.text("name"), actual?.name, "name at $index of $last")
            }
        }
    }

    @Test
    fun `a finished sketch is the route the web makes`() {
        val all = cases("build")
        assertTrue(all.size >= 10)
        for (c in all) {
            val draft = c.getValue("draft").jsonArray.map { it.jsonObject }.map { p ->
                val d = (p["designation"] as? JsonObject)
                DraftPoint(
                    p.double("lat"), p.double("lon"),
                    d?.let { Designation(it.text("ptType"), it.text("name"), it.doubleOrNull("chartElevationFt")) },
                )
            }
            val profile = (c["plan"] as? JsonObject)?.let { AircraftProfile.normalize(it) }
            val built = SketchOps.build(draft, name = "ROUTE 1", id = "sketch-1", color = "#FF453A", plan = RoutePlans.default(profile), newPointId = counter("p"))
            assertSame(c.getValue("expected"), encoded(built), label(c))
        }
    }

    @Test
    fun `changing what a point is gives the web's route, and refuses what it refuses`() {
        val all = cases("designate")
        assertTrue(all.size >= 11)
        for (c in all) {
            val args = c.getValue("args").jsonObject
            val spec = args.getValue("spec").jsonObject
            val result = SketchOps.designate(route(c.getValue("route")), args.text("pointId")!!, PointSpec(spec.text("kind")!!, spec.text("ptType"), spec.text("name")))
            assertSame(c.getValue("expected"), encoded(result), label(c))
        }
    }

    @Test
    fun `moving a point`() {
        for (c in cases("move")) {
            val a = c.getValue("args").jsonObject
            val result = SketchOps.move(route(c.getValue("route")), a.text("pointId")!!, a.double("lat"), a.double("lon"), a.doubleOrNull("chartElevationFt"))
            assertSame(c.getValue("expected"), encoded(result), label(c))
        }
    }

    @Test
    fun `adding a shaping point to the nearest leg`() {
        val all = cases("insert")
        assertTrue(all.size >= 8)
        for (c in all) {
            val a = c.getValue("args").jsonObject
            val result = SketchOps.insertShaping(route(c.getValue("route")), a.double("lat"), a.double("lon"), counter("n"))
            assertSame(c.getValue("expected"), encoded(result), label(c))
        }
    }

    @Test
    fun `appending a designated point`() {
        for (c in cases("append")) {
            val a = c.getValue("args").jsonObject
            val o = a.getValue("options").jsonObject
            val start = route(c.getValue("route"))
            // Nothing said means the defaults the function itself has; only what the case gives is passed.
            val result = if (o.isEmpty()) SketchOps.appendAmps(start, a.double("lat"), a.double("lon"), newPointId = counter("n"))
            else SketchOps.appendAmps(start, a.double("lat"), a.double("lon"), name = o.text("name")!!, ptType = o.text("ptType")!!, chartElevationFt = o.doubleOrNull("chartElevationFt"), newPointId = counter("n"))
            assertSame(c.getValue("expected"), encoded(result), label(c))
        }
    }

    @Test
    fun `plan settings, per-point values and the clock anchor`() {
        val all = cases("plan")
        assertTrue(all.size >= 9)
        for (c in all) {
            val a = c.getValue("args").jsonObject
            val start = route(c.getValue("route"))
            val result = when {
                "clock" in a -> SketchOps.withClock(start, a.text("pointId")!!, a.text("clock"))
                "patch" in a && a["patch"] is JsonNull -> SketchOps.clearOverrides(start, a.text("pointId")!!)
                "pointId" in a -> {
                    val p = a.getValue("patch").jsonObject
                    SketchOps.withOverride(
                        start, a.text("pointId")!!,
                        OverridePatch(
                            (p["altitude"] as? JsonObject)?.let(::altitude), (p["airspeed"] as? JsonObject)?.let(::airspeed), (p["wind"] as? JsonObject)?.let(::wind),
                        ),
                    )
                }
                else -> {
                    val p = a.getValue("patch").jsonObject
                    SketchOps.withPlan(
                        start,
                        PlanPatch(
                            airspeed = (p["airspeed"] as? JsonObject)?.let(::airspeed), altitude = (p["altitude"] as? JsonObject)?.let(::altitude),
                            wind = (p["wind"] as? JsonObject)?.let(::wind), tempC = p.doubleOrNull("tempC"), fuelFlowLbHr = p.doubleOrNull("fuelFlowLbHr"), date = p.text("date"),
                        ),
                    )
                }
            }
            assertSame(c.getValue("expected"), encoded(result), label(c))
        }
    }

    @Test
    fun `renaming a point, and snapping it to a local point`() {
        for (c in cases("rename")) {
            val a = c.getValue("args").jsonObject
            val coords = a["coords"] as? JsonObject
            val result = SketchOps.rename(
                route(c.getValue("route")), a.text("pointId")!!, a.text("name")!!,
                snapTo = coords?.let { it.double("lat") to it.double("lon") }, chartElevationFt = a.doubleOrNull("chartElevationFt"),
            )
            assertSame(c.getValue("expected"), encoded(result), label(c))
        }
    }
}
