package app.ezpztac.scene3d

import app.ezpztac.model.RouteSets
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The routes in 3D, every case of contracts/fixtures/viewer3d/routeScene.json: the places asked, and the shapes and labels for each answer. */
class RouteScene3DTest {
    private val fixture = Fixtures.load("viewer3d/routeScene.json")

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test
    fun `the constants are the web's`() {
        val c = fixture.getValue("constants").jsonObject
        assertEquals(c.getValue("FT_TO_M").jsonPrimitive.double, RouteScene3D.FT_TO_M)
        assertEquals(c.getValue("SAMPLE_STEP_M").jsonPrimitive.double, RouteScene3D.SAMPLE_STEP_M)
        assertEquals(c.getValue("MAX_SAMPLES").jsonPrimitive.int, RouteScene3D.MAX_SAMPLES)
        assertEquals(c.getValue("AGL_MISMATCH_FT").jsonPrimitive.double, RouteScene3D.AGL_MISMATCH_FT)
    }

    @Test
    fun `every case is placed as the web places it`() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            // The routes as the app reads a saved set: one it cannot read (a point with no latitude) is kept aside, as the web leaves it undrawn.
            val set = RouteSets.parse("set", null, "SET", JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to case.getValue("routes"))))
            val prepared = RouteScene3D.prepare(set.routes, LocalDate.of(2026, 10, 20))
            checkPrepared(name, case.getValue("prepared").jsonObject, prepared)
            for (answer in case.getValue("placed").jsonArray.map { it.jsonObject }) {
                val label = "$name / ${answer.getValue("answer").jsonPrimitive.content}"
                // A long case keeps no heights: they are the fixture's flat ground, 300 m, under the geoid at this latitude.
                val h = answer["heights"] as? JsonObject
                val heights = if (h == null) {
                    RouteHeights(List(prepared.samples.size) { 300.0 }, List(prepared.samples.size) { -30.35 })
                } else {
                    RouteHeights(
                        h.getValue("groundM").jsonArray.map { (it as? JsonPrimitive)?.doubleOrNull },
                        (h["geoidM"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull },
                    )
                }
                val placed = RouteScene3D.place(prepared, heights)
                checkPlaced(label, answer.getValue("routes").jsonArray, placed)
                checkFraming(label, answer["framing"], placed)
            }
        }
    }

    // What *Routes* frames: Cesium's own sphere (its `BoundingSphere.fromPoints`, recorded by the web), held to the port's, worked out in an LZ's
    // frame from the lines' places and taken back to geocentric metres.
    private fun checkFraming(label: String, expected: JsonElement?, placed: List<PlacedRoute>) {
        val frame = LocalFrame(34.6, -84.1)
        val sphere = BoundingSpheres.fromPoints(RouteGeometry.of(placed, frame).positions, frame)
        if (expected == null || expected is JsonNull) {
            assertEquals(null, sphere, "$label: nothing to frame")
            return
        }
        val e = expected.jsonObject
        val center = e.getValue("center").jsonArray.map { it.jsonPrimitive.double }
        assertTrue(sphere != null, "$label: a sphere")
        val at = frame.toEcef(sphere!!.center)
        assertEquals(center[0], at.x, 1e-3, "$label: centre x")
        assertEquals(center[1], at.y, 1e-3, "$label: centre y")
        assertEquals(center[2], at.z, 1e-3, "$label: centre z")
        assertEquals(e.getValue("radius").jsonPrimitive.double, sphere.radius, 1e-3, "$label: radius")
    }

    private fun checkSample(name: String, expected: JsonObject, actual: GroundSample) {
        assertEquals(expected.getValue("lat").jsonPrimitive.double, actual.lat, name)
        assertEquals(expected.getValue("lon").jsonPrimitive.double, actual.lon, name)
    }

    private fun checkPrepared(name: String, expected: JsonObject, actual: PreparedRoutes) {
        when (val samples = expected.getValue("samples")) {
            is JsonArray -> {
                assertEquals(samples.size, actual.samples.size, "$name: samples")
                for ((i, s) in samples.withIndex()) checkSample("$name: sample $i", s.jsonObject, actual.samples[i])
            }
            // A long case's samples by a few of them: how many, the first and last five, and every 500th.
            else -> {
                val digest = samples.jsonObject
                assertEquals(digest.getValue("count").jsonPrimitive.int, actual.samples.size, "$name: samples")
                for ((i, s) in digest.getValue("first").jsonArray.withIndex()) checkSample("$name: first $i", s.jsonObject, actual.samples[i])
                val last = digest.getValue("last").jsonArray
                for ((i, s) in last.withIndex()) checkSample("$name: last $i", s.jsonObject, actual.samples[actual.samples.size - last.size + i])
                for ((i, s) in digest.getValue("every500").jsonArray.withIndex()) checkSample("$name: every 500th $i", s.jsonObject, actual.samples[i * 500])
            }
        }
        val routes = expected.getValue("routes").jsonArray.map { it.jsonObject }
        assertEquals(routes.size, actual.routes.size, "$name: routes")
        for ((r, route) in routes.withIndex()) {
            val a = actual.routes[r]
            assertEquals(route.text("id"), a.id, name)
            assertEquals(route.text("name"), a.name, name)
            assertEquals(route.text("color"), a.color, name)
            assertEquals(route.getValue("first").jsonPrimitive.int, a.first, name)
            assertEquals(route.getValue("count").jsonPrimitive.int, a.count, name)
            when (val distances = route.getValue("distances")) {
                is JsonArray -> {
                    assertEquals(distances.size, a.distances.size, name)
                    for ((i, d) in distances.withIndex()) assertEquals(d.jsonPrimitive.double, a.distances[i], "$name: distance $i")
                }
                else -> assertEquals(distances.jsonPrimitive.int, a.distances.size, "$name: distances")
            }
            val anchors = route.getValue("anchors").jsonArray.map { it.jsonObject }
            assertEquals(anchors.size, a.anchors.size, name)
            for ((i, anchor) in anchors.withIndex()) {
                val b = a.anchors[i]
                assertEquals(anchor.getValue("sample").jsonPrimitive.int, b.sample, "$name: anchor $i")
                assertEquals(anchor.getValue("distance").jsonPrimitive.double, b.distance, "$name: anchor $i")
                assertEquals(anchor.text("name"), b.name, "$name: anchor $i")
                assertEquals(anchor.text("ptType"), b.ptType, "$name: anchor $i")
                assertEquals(anchor.getValue("touchdown").jsonPrimitive.boolean, b.touchdown, "$name: anchor $i")
                val plan = anchor.getValue("plan").jsonObject
                assertEquals(plan.number("mslFt"), b.mslFt, "$name: anchor $i msl")
                assertEquals(plan.number("aglFt"), b.aglFt, "$name: anchor $i agl")
            }
        }
    }

    private fun checkPosition(label: String, expected: JsonArray, actual: RoutePosition) {
        assertEquals(expected[0].jsonPrimitive.double, actual.lon, label)
        assertEquals(expected[1].jsonPrimitive.double, actual.lat, label)
        assertEquals(expected[2].jsonPrimitive.double, actual.heightM, 1e-9, label)
    }

    private fun checkPlaced(label: String, expected: JsonArray, actual: List<PlacedRoute>) {
        assertEquals(expected.size, actual.size, "$label: routes")
        for ((r, element) in expected.withIndex()) {
            val route = element.jsonObject
            val a = actual[r]
            assertEquals(route.text("id"), a.id, label)
            route.text("color")?.let { assertEquals(it, a.color, label) }
            // A long case keeps how many lines and walls there are, not their places.
            when (val lines = route.getValue("lines")) {
                is JsonArray -> {
                    assertEquals(lines.size, a.lines.size, "$label: lines")
                    for ((l, line) in lines.withIndex()) {
                        assertEquals(line.jsonArray.size, a.lines[l].size, "$label: line $l")
                        for ((i, p) in line.jsonArray.withIndex()) checkPosition("$label: line $l at $i", p.jsonArray, a.lines[l][i])
                    }
                }
                else -> assertEquals(lines.jsonPrimitive.int, a.lines.size, "$label: lines")
            }
            when (val walls = route.getValue("walls")) {
                is JsonArray -> {
                    assertEquals(walls.size, a.walls.size, "$label: walls")
                    for ((w, element) in walls.withIndex()) {
                        val wall = element.jsonObject
                        val positions = wall.getValue("positions").jsonArray
                        assertEquals(positions.size, a.walls[w].positions.size, "$label: wall $w")
                        for ((i, p) in positions.withIndex()) checkPosition("$label: wall $w at $i", p.jsonArray, a.walls[w].positions[i])
                        assertEquals(wall.getValue("groundM").jsonArray.map { it.jsonPrimitive.double }, a.walls[w].groundM, "$label: wall $w ground")
                    }
                }
                else -> assertEquals(walls.jsonPrimitive.int, a.walls.size, "$label: walls")
            }
            val markers = route.getValue("markers").jsonArray.map { it.jsonObject }
            assertEquals(markers.size, a.markers.size, "$label: markers")
            for ((m, marker) in markers.withIndex()) {
                val b = a.markers[m]
                assertEquals(marker.getValue("lon").jsonPrimitive.double, b.lon, label)
                assertEquals(marker.getValue("lat").jsonPrimitive.double, b.lat, label)
                assertEquals(marker.getValue("heightM").jsonPrimitive.double, b.heightM, 1e-9, label)
                assertEquals(marker.number("groundM"), b.groundM, label)
                assertEquals(marker.text("ptType"), b.ptType, label)
                assertEquals(marker.getValue("label").jsonPrimitive.content, b.label, "$label: marker $m")
                val measured = marker.number("measuredAglFt")
                if (measured == null) assertEquals(null, b.measuredAglFt, label) else assertEquals(measured, b.measuredAglFt!!, 1e-9, label)
                assertEquals(marker.getValue("mismatch").jsonPrimitive.boolean, b.mismatch, "$label: marker $m")
            }
        }
    }

    @Test
    fun `feet are written as the web writes them, halves up, thousands with commas, and a negative zero kept`() {
        assertEquals("1,500'", RouteScene3D.formatFt(1500.0))
        assertEquals("1,235'", RouteScene3D.formatFt(1234.5))
        assertEquals("416'", RouteScene3D.formatFt(416.1745))
        assertEquals("-25'", RouteScene3D.formatFt(-25.4))
        assertEquals("-25'", RouteScene3D.formatFt(-25.5))
        assertEquals("-1,234'", RouteScene3D.formatFt(-1234.4))
        assertEquals("-0'", RouteScene3D.formatFt(-0.3))
        assertEquals("-0'", RouteScene3D.formatFt(-0.5))
        assertEquals("0'", RouteScene3D.formatFt(0.0))
        assertEquals("1,000,000'", RouteScene3D.formatFt(999_999.6))
    }
}
