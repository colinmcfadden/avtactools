package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a saved route set keeps through a read and a write: everything the web wrote, and what this version does not know. */
class RouteSetTest {
    private val fixtureRoutes = Fixtures.load("routes/sketch.json").getValue("routes").jsonObject

    private fun doc(vararg routes: JsonElement, extra: JsonObject = JsonObject(emptyMap())) =
        JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to JsonArray(routes.toList())) + extra)

    private fun read(data: JsonObject) = RouteSets.parse("uuid-1", null, "SET", data)

    /** The fixture's routes all say `sketch-1`; a set needs each its own. */
    private fun named(id: String, route: JsonElement) = JsonObject(route.jsonObject + ("id" to JsonPrimitive(id)))

    private fun assertSameJson(expected: JsonElement, actual: JsonElement, what: String) {
        val differences = JsonCompare.differences(expected, actual)
        assertTrue(differences.isEmpty(), "$what: ${differences.take(8)}")
    }

    @Test
    fun `every route the web's sketch fixture makes is read and written back as it was`() {
        assertTrue(fixtureRoutes.size >= 7)
        for ((name, route) in fixtureRoutes) {
            val data = doc(route)
            val set = read(data)
            assertEquals(1, set.routes.size, "$name was read")
            assertTrue(set.unreadable.isEmpty(), name)
            assertSameJson(data, RouteSets.serialize(set), name)
        }
    }

    @Test
    fun `a set with several routes keeps their order`() {
        val data = doc(*fixtureRoutes.values.mapIndexed { i, r -> named("sketch-$i", r) }.toTypedArray())
        val set = read(data)
        assertEquals(fixtureRoutes.size, set.routes.size)
        assertEquals(data.getValue("routes").jsonArray.map { it.jsonObject.getValue("id") }, set.routes.map { JsonPrimitive(it.id) })
        assertSameJson(data, RouteSets.serialize(set), "all routes together")
    }

    @Test
    fun `a field a newer release adds survives at every level it can`() {
        val route = fixtureRoutes.getValue("planned").jsonObject
        val points = route.getValue("points").jsonArray.mapIndexed { i, p ->
            JsonObject(p.jsonObject + ("newPointField" to JsonPrimitive("point $i")))
        }
        val plan = JsonObject(route.getValue("plan").jsonObject + ("newPlanField" to buildJsonObject { put("a", 1) }))
        val newer = JsonObject(route + mapOf("points" to JsonArray(points), "plan" to plan, "newRouteField" to JsonPrimitive(true)))
        val data = doc(newer, extra = buildJsonObject { put("newDocumentField", "x") })

        val set = read(data)
        assertEquals(JsonPrimitive(true), set.routes.single().extras["newRouteField"])
        assertEquals(JsonPrimitive("point 2"), set.routes.single().points[2].extras["newPointField"])
        assertNotNull(set.routes.single().plan.extras["newPlanField"])
        assertEquals(JsonPrimitive("x"), set.extras["newDocumentField"])
        assertSameJson(data, RouteSets.serialize(set), "a newer document")
    }

    @Test
    fun `an edit does not lose what the point was carrying and what is edited is what is written`() {
        val route = fixtureRoutes.getValue("planned").jsonObject
        val points = route.getValue("points").jsonArray.map { JsonObject(it.jsonObject + ("keepMe" to JsonPrimitive(1))) }
        val set = read(doc(JsonObject(route + ("points" to JsonArray(points)))))

        val moved = set.mapRoute("sketch-1") { r -> r.copy(points = r.points.map { if (it.id == "p2") it.copy(lat = 12.5) else it }) }
        val written = RouteSets.serialize(moved).getValue("routes").jsonArray.single().jsonObject.getValue("points").jsonArray
        val p2 = written.map { it.jsonObject }.single { it["id"] == JsonPrimitive("p2") }
        assertEquals(JsonPrimitive(12.5), p2["lat"])
        assertEquals(JsonPrimitive(1), p2["keepMe"])
    }

    @Test
    fun `a field this version knows is never overwritten by a stale extra`() {
        val route = RouteSets.parse("u", null, "S", doc(fixtureRoutes.getValue("planned"))).routes.single()
        val clashing = route.copy(name = "RENAMED", extras = JsonObject(mapOf("name" to JsonPrimitive("OLD"))))
        val written = RouteSets.serialize(RouteSet("u", null, "S", listOf(clashing))).getValue("routes").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("RENAMED"), written["name"])
    }

    @Test
    fun `a route that cannot be read is kept as it was, after the others, and does not fail the set`() {
        val broken = buildJsonObject { put("id", "sketch-broken"); put("name", "no points, no colour"); put("color", 7) }
        val notEvenAnObject = JsonPrimitive("junk")
        val good = fixtureRoutes.getValue("twoAmps")
        val set = read(doc(broken, good, notEvenAnObject))

        assertEquals(1, set.routes.size)
        assertEquals(listOf<JsonElement>(broken, notEvenAnObject), set.unreadable)
        val written = RouteSets.serialize(set).getValue("routes").jsonArray
        assertEquals(3, written.size)
        assertEquals(JsonPrimitive("sketch-broken"), written[1].jsonObject["id"])
        assertEquals(notEvenAnObject, written[2])
    }

    @Test
    fun `a document with no routes is an empty set and a point with no position makes its route unreadable`() {
        assertEquals(emptyList<SketchRoute>(), read(JsonObject(emptyMap())).routes)
        assertEquals(emptyList<SketchRoute>(), read(JsonObject(mapOf("routes" to JsonNull))).routes)

        val route = fixtureRoutes.getValue("twoAmps").jsonObject
        val points = route.getValue("points").jsonArray.map { JsonObject(it.jsonObject - "lat") }
        val set = read(doc(JsonObject(route + ("points" to JsonArray(points)))))
        assertTrue(set.routes.isEmpty())
        assertEquals(1, set.unreadable.size)
    }

    @Test
    fun `the version is kept as found and a document with none is written as 1`() {
        assertEquals(JsonPrimitive(2), RouteSets.serialize(read(JsonObject(mapOf("version" to JsonPrimitive(2), "routes" to JsonArray(emptyList()))))).getValue("version"))
        assertEquals(JsonPrimitive(1), RouteSets.serialize(read(JsonObject(emptyMap()))).getValue("version"))
        assertEquals(JsonPrimitive(1), RouteSets.serialize(RouteSet("u", null, "S", emptyList())).getValue("version"))
    }

    @Test
    fun `the saved id and the name belong to the record, not the document`() {
        val set = RouteSets.parse("uuid-9", 42, "NAME", doc(fixtureRoutes.getValue("twoAmps")))
        assertEquals("uuid-9", set.id)
        assertEquals(42, set.savedId)
        assertEquals("NAME", set.name)
        assertFalse(RouteSets.serialize(set).containsKey("name"))
        assertFalse(RouteSets.serialize(set).containsKey("id"))
    }

    @Test
    fun `a route is found, changed, added and removed by its id`() {
        val set = read(doc(named("sketch-a", fixtureRoutes.getValue("twoAmps")), named("sketch-b", fixtureRoutes.getValue("threeAmps"))))
        val first = set.routes[0].id
        val second = set.routes[1].id

        assertEquals(second, set.route(second)!!.id)
        assertEquals(null, set.route("nope"))

        assertEquals("X", set.mapRoute(first) { it.copy(name = "X") }.route(first)!!.name)
        assertEquals(set.routes[1], set.mapRoute(first) { it.copy(name = "X") }.route(second))
        assertSame(set, set.mapRoute("nope") { it.copy(name = "X") })
        assertSame(set, set.mapRoute(first) { it })

        assertEquals(listOf(first), set.without(second).routes.map { it.id })
        assertSame(set, set.without("nope"))
        assertEquals(listOf(first, second, "new"), set.plus(set.routes[0].copy(id = "new")).routes.map { it.id })
    }
}
