package app.ezpztac.planning

import app.ezpztac.model.Mission
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissionRoutesTest {
    private fun point(id: String, lat: Double, lon: Double, kind: String = RoutePoint.KIND_AMPS) =
        RoutePoint(id = id, lat = lat, lon = lon, kind = kind, name = id.uppercase(), ptType = if (kind == RoutePoint.KIND_AMPS) "turn" else null)

    private fun route(name: String, vararg points: RoutePoint, plan: RoutePlan = RoutePlan(), elevations: Map<String, Double> = emptyMap()) =
        MissionRoute(name = name, segmentId = "seg-$name", points = points.toList(), plan = plan, elevations = elevations)

    private val ids = generateSequence(1) { it + 1 }.iterator()
    private fun newId() = "sketch-${ids.next()}"

    @Test
    fun `each route comes across with its points, its plan and its ground elevations`() {
        val plan = RoutePlan(date = "2026-10-03")
        val mission = Mission(listOf(route("NEPTUNE", point("a", 34.0, -84.0), point("b", 34.1, -84.1), plan = plan, elevations = mapOf("a" to 910.0))))
        val made = MissionRoutes.toSketchRoutes(mission, ::newId).single()
        assertEquals("NEPTUNE", made.name)
        assertEquals(listOf("a", "b"), made.points.map { it.id })
        assertEquals(plan, made.plan)
        assertEquals(mapOf("a" to 910.0), made.elevations)
        assertTrue(made.visible)
        assertTrue(made.id.startsWith("sketch-"))                                          // the web tells a sketch from an imported route by this prefix
    }

    @Test
    fun `routes take the palette's colours in turn, after any already in use`() {
        val mission = Mission(listOf(route("A", point("a", 1.0, 1.0)), route("B", point("b", 1.0, 1.0)), route("C", point("c", 1.0, 1.0))))
        assertEquals(RouteColors.PALETTE.take(3), MissionRoutes.toSketchRoutes(mission, ::newId).map { it.color })
        assertEquals(RouteColors.PALETTE.drop(1).take(3), MissionRoutes.toSketchRoutes(mission, ::newId, takenColors = listOf(RouteColors.PALETTE[0])).map { it.color })
    }

    @Test
    fun `each route gets an id of its own`() {
        val mission = Mission(listOf(route("A", point("a", 1.0, 1.0)), route("B", point("b", 1.0, 1.0))))
        val made = MissionRoutes.toSketchRoutes(mission, ::newId)
        assertEquals(2, made.map { it.id }.toSet().size)
    }

    @Test
    fun `a route with no points has nothing to draw and is left out, and a blank name is called by its place`() {
        val mission = Mission(listOf(route("EMPTY"), route("   ", point("a", 1.0, 1.0)), route(" TRIMMED ", point("b", 1.0, 1.0))))
        val made = MissionRoutes.toSketchRoutes(mission, ::newId)
        assertEquals(listOf("ROUTE 2", "TRIMMED"), made.map { it.name })                    // the empty one still counts in the numbering
    }

    @Test
    fun `a mission with no routes makes none`() {
        assertTrue(MissionRoutes.toSketchRoutes(Mission(emptyList()), ::newId).isEmpty())
    }

    @Test
    fun `named points are the designated ones, not the shaping points`() {
        val mission = Mission(listOf(route("A", point("a", 1.0, 1.0), point("s", 1.0, 1.1, RoutePoint.KIND_SHAPING), point("b", 1.0, 1.2)), route("B", point("c", 2.0, 2.0))))
        assertEquals(3, MissionRoutes.namedPoints(MissionRoutes.toSketchRoutes(mission, ::newId)))
    }

    @Test
    fun `the extent is the middle of the box round every point and its longer side`() {
        val mission = Mission(listOf(route("A", point("a", 34.0, -84.0), point("b", 34.0, -83.9)), route("B", point("c", 34.2, -84.0))))
        val extent = MissionRoutes.extentOf(MissionRoutes.toSketchRoutes(mission, ::newId))!!
        assertEquals(34.1, extent.center.lat, 1e-9)
        assertEquals(-83.95, extent.center.lon, 1e-9)
        // 0.2 degrees of latitude is 22.2 km; 0.1 degree of longitude at 34.1 north is 9.2 km: the north-south side is the longer.
        assertEquals(22_240.0, extent.spanMeters, 150.0)
    }

    @Test
    fun `a single point has an extent of no length`() {
        val extent = MissionRoutes.extentOf(MissionRoutes.toSketchRoutes(Mission(listOf(route("A", point("a", 34.0, -84.0)))), ::newId))!!
        assertEquals(0.0, extent.spanMeters, 1e-9)
        assertEquals(34.0, extent.center.lat, 1e-9)
    }

    @Test
    fun `no points, no extent`() {
        assertNull(MissionRoutes.extentOf(emptyList()))
    }
}
