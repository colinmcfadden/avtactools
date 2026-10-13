package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.item
import app.ezpztac.missionpacks.PackFixtures.localId
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An editor's change to a route set always reaches the pack ([RoutePackKind.docOf]). The editor works on types and the pack holds
 * JSON, and they disagree where the types fill in what the JSON lacks (a plan's altitude, airspeed or per-point values that are null
 * are read as the defaults) and where the routes cannot be addressed by id (two share one): a change there is sent coarser, never
 * dropped. Nor is a route ever written a second time, and a point's place goes whole ([RoutePackKind.keepWhole]). Android only, so
 * held here.
 */
class PackRouteEditTest {
    private val routeShapes = Fixtures.load("packs/shared.json").getValue("routeShape").jsonArray.map { it.jsonObject }

    // The item of shared.json's routeShape case whose name starts with [name].
    private fun case(name: String): PackItemView = item(routeShapes.single { PackFixtures.name(it).startsWith(name) }.getValue("item"))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun shape(item: PackItemView): JsonElement = RoutePackKind.currentShape(localId(item), item)

    // What [mine]'s change to [item] arrives as, once checked that it is sent and that every operation applies to the pack's data, and
    // that what arrives, read back, is the editor's document.
    private fun sent(item: PackItemView, mine: RouteSet): JsonObject {
        val edit = PackEdit.composeEdit(
            uuid = item.uuid,
            base = EditVersion(item.name, shape(item)),
            mine = EditVersion(item.name, RoutePackKind.docOf(mine, item.data)),
            item = item,
            shared = { RoutePackKind.shared(item.data) },
            current = { shape(item) },
            describe = RoutePackKind::describe,
            actor = "Sam B.",
        )
        assertTrue(edit != null && edit.ops.isNotEmpty(), "the change is sent")
        val arrived = PackFixtures.applyAll("route", item.data, edit!!.ops)
        assertValue(RoutePackKind.docOf(mine, item.data), shape(item.copy(data = arrived)), "the change arrives whole")
        return arrived.jsonObject
    }

    private fun routes(data: JsonElement): JsonArray = data.jsonObject.getValue("routes").jsonArray

    private fun plan(data: JsonElement): JsonObject = routes(data)[0].jsonObject.getValue("plan").jsonObject

    // [item] with field [key] of its first route's plan set to [value].
    private fun withPlanField(item: PackItemView, key: String, value: JsonElement): PackItemView {
        val route = routes(item.data)[0].jsonObject
        val changed = JsonObject(route + ("plan" to JsonObject(route.getValue("plan").jsonObject + (key to value))))
        return item.copy(data = JsonObject(item.data.jsonObject + ("routes" to JsonArray(listOf(changed) + routes(item.data).drop(1)))))
    }

    @Test
    fun `a change to a plan value that is null is sent as the editor's whole value of it`() {
        val item = case("a plan's own values win whole")
        assertEquals(JsonNull, plan(item.data)["altitude"], "the case's altitude is null, which the types read as 50 ft AGL")
        val opened = RoutePackKind.fromItem(localId(item), item, null)
        val mine = opened.mapRoute("r-1") { it.copy(plan = it.plan.copy(altitude = AltitudeSetting(500.0, AltitudeSetting.REF_MSL))) }

        val arrived = sent(item, mine)
        assertValue(json("""{"value": 500, "ref": "msl"}"""), plan(arrived)["altitude"], "the altitude")
        // Nothing else of the plan is written from the types: the airspeed the pack has with no type still has none.
        assertValue(plan(item.data)["airspeed"], plan(arrived)["airspeed"], "the airspeed")
    }

    @Test
    fun `a clock set on a route whose per-point values are null is sent`() {
        val item = withPlanField(case("a plan's own values win whole"), "perPoint", JsonNull)
        val opened = RoutePackKind.fromItem(localId(item), item, null)
        val mine = opened.mapRoute("r-1") { it.copy(plan = it.plan.copy(perPoint = mapOf("p3" to PointOverride(clock = "10:00:00")))) }

        val arrived = sent(item, mine)
        assertValue(json("""{"p3": {"clock": "10:00:00"}}"""), plan(arrived)["perPoint"], "the clock")
        assertValue(JsonNull, plan(arrived)["altitude"], "the altitude, not changed, is still null")
    }

    @Test
    fun `a change to an airspeed that is null is sent`() {
        val item = withPlanField(case("a plan's own values win whole"), "airspeed", JsonNull)
        val opened = RoutePackKind.fromItem(localId(item), item, null)
        val mine = opened.mapRoute("r-1") { it.copy(plan = it.plan.copy(airspeed = it.plan.airspeed.copy(value = 120.0))) }

        val arrived = sent(item, mine)
        assertValue(json("""{"value": 120, "type": "ground"}"""), plan(arrived)["airspeed"], "the airspeed")
    }

    // RED 1 with the ground fetched for its two points, as the pack has it once an editor in step with the web sent it.
    private val grounded = PackItemView(
        "rt-1", "route", "OP DK INGRESS",
        PackRoutes.routeShape(
            json(
                """{"version": 1, "routes": [{"id": "r-1", "name": "RED 1", "color": "#0A84FF", "elevations": {"p-1": 1000, "p-2": 1100},
                    "points": [
                      {"id": "p-1", "lat": 34.5, "lon": -84.1, "kind": "amps", "ptType": "start", "name": ".SP", "role": "start"},
                      {"id": "p-2", "lat": 34.6, "lon": -84.2, "kind": "amps", "ptType": "ip", "name": ".RP", "role": "waypoint"}],
                    "plan": {"perPoint": {"p-1": {"clock": "12:00:00"}}}}]}""",
            ),
        ),
    )

    // [set] once the pack has [mine]'s change (applied as the server applies it), checked to be what the editor has: what an editor's next look measures from.
    private fun afterSend(set: PackItemView, mine: RouteSet): PackItemView = set.copy(data = sent(set, mine))

    @Test
    fun `a point moved off the ground fetched for it is still one route once the pack has the move, and nothing more is sent`() {
        val opened = RoutePackKind.fromItem(localId(grounded), grounded, null)
        // As the app moves a point: it forgets the ground fetched for the old place, which the pack is sent as null (no operation deletes a key).
        val mine = opened.mapRoute("r-1") { r ->
            r.copy(points = r.points.map { if (it.id == "p-2") it.copy(lat = 34.61, lon = -84.21) else it }, elevations = r.elevations - "p-2")
        }
        val now = afterSend(grounded, mine)
        assertEquals(JsonNull, routes(now.data)[0].jsonObject.getValue("elevations").jsonObject["p-2"], "the pack holds the ground that went as null")

        // The editor's next look measures from the pack's data as it is now: the same route, nothing more to send. It once read a route with a null in
        // it as one it could not read, took the editor's own for a new route beside it, and sent the set's routes whole with the route in them twice.
        val next = RoutePackKind.docOf(mine, now.data)
        assertValue(shape(now), next, "nothing more to send")
        assertEquals(listOf("r-1"), routes(next).map { it.jsonObject.getValue("id").jsonPrimitive.content })
        // Whoever opens the set has the route, at the new place, with no ground for the point that moved.
        val elsewhere = RoutePackKind.fromItem(localId(now), now, null)
        assertTrue(elsewhere.unreadable.isEmpty())
        val route = elsewhere.routes.single()
        assertEquals(34.61 to -84.21, route.points[1].let { it.lat to it.lon })
        assertEquals(mapOf("p-1" to 1000.0), route.elevations)
    }

    @Test
    fun `a clock moved to another point is still one route once the pack has it, and nothing more is sent`() {
        val opened = RoutePackKind.fromItem(localId(grounded), grounded, null)
        // A clock anchors one point at a time: p-1's goes, and p-1 had nothing else, so its entry goes too (SketchOps.withClock), sent as null.
        val mine = opened.mapRoute("r-1") { r -> r.copy(plan = r.plan.copy(perPoint = mapOf("p-2" to PointOverride(clock = "12:30:00")))) }
        val now = afterSend(grounded, mine)
        assertEquals(JsonNull, plan(now.data).getValue("perPoint").jsonObject["p-1"])

        val next = RoutePackKind.docOf(mine, now.data)
        assertValue(shape(now), next, "nothing more to send")
        assertEquals(1, routes(next).size)
        assertEquals(mapOf("p-2" to PointOverride(clock = "12:30:00")), RoutePackKind.fromItem(localId(now), now, null).routes.single().plan.perPoint)
    }

    @Test
    fun `a route the pack now holds in a form the types cannot read is measured as the pack has it, never written a second time`() {
        val opened = RoutePackKind.fromItem(localId(grounded), grounded, null)
        // Since the editor's document was made, the pack's RED 1 took a form this version cannot read (here a name of null): a document that was in step
        // with it then still has a version of it.
        val route = routes(grounded.data)[0].jsonObject
        val unreadable = grounded.copy(data = JsonObject(grounded.data.jsonObject + ("routes" to JsonArray(listOf(JsonObject(route + ("name" to JsonNull)))))))
        assertTrue(RoutePackKind.fromItem(localId(unreadable), unreadable, null).routes.isEmpty(), "the types cannot read it")
        val mine = opened.mapRoute("r-1") { r -> r.copy(points = r.points.map { if (it.id == "p-2") it.copy(lat = 34.61) else it }) }

        val doc = RoutePackKind.docOf(mine, unreadable.data)
        assertEquals(listOf("r-1"), routes(doc).map { it.jsonObject.getValue("id").jsonPrimitive.content }, "one route, where it was")
        assertValue(JsonPrimitive(34.61), routes(doc)[0].jsonObject.getValue("points").jsonArray[1].jsonObject["lat"], "the change")
        // The change goes as operations at the route, each of which applies to the pack's data.
        val edit = PackEdit.composeEdit(
            "rt-1", EditVersion("OP DK INGRESS", shape(unreadable)), EditVersion("OP DK INGRESS", doc), unreadable,
            { RoutePackKind.shared(unreadable.data) }, { shape(unreadable) }, RoutePackKind::describe, "Sam B.",
        )!!
        assertTrue(edit.ops.all { (it.getValue("path") as JsonArray).isNotEmpty() }, "never the set's routes whole: ${edit.ops}")
        assertEquals(1, routes(PackFixtures.applyAll("route", unreadable.data, edit.ops)).size)
    }

    @Test
    fun `of two routes with one id, the first of which the types cannot read, a change to the one the editor has goes to that one`() {
        // A set an earlier version wrote a route of twice (the copy this version could not read first): an operation addressed by the id would reach the
        // first copy, which the editor does not show, so the change goes whole, to the copy the editor has, and the other stays as it came.
        val route = routes(grounded.data)[0].jsonObject
        val hidden = JsonObject(route + ("name" to JsonNull))
        val twice = grounded.copy(data = JsonObject(grounded.data.jsonObject + ("routes" to JsonArray(listOf(hidden, route)))))
        val opened = RoutePackKind.fromItem(localId(twice), twice, null)
        assertEquals(listOf("RED 1"), opened.routes.map { it.name })
        val mine = opened.mapRoute("r-1") { r -> r.copy(points = r.points.map { if (it.id == "p-2") it.copy(lat = 34.61, lon = -84.21) else it }) }

        val arrived = sent(twice, mine)
        assertEquals(2, routes(arrived).size, "no third copy")
        assertValue(hidden, routes(arrived)[0], "the copy the editor does not show, as it came")
        assertValue(JsonPrimitive(34.61), routes(arrived)[1].jsonObject.getValue("points").jsonArray[1].jsonObject["lat"], "the change, on the copy the editor has")
    }

    @Test
    fun `a point moved one way only is sent with both its coordinates, so a move made elsewhere meanwhile never leaves it half of each`() {
        val local = localId(grounded)
        val opened = RoutePackKind.fromItem(local, grounded, null)
        val base = PackItemSync.settled(RoutePackKind, local, grounded)
        // The nudge pad's arrow north: the latitude changes, the longitude is exactly what it was.
        val north = opened.mapRoute("r-1") { r -> r.copy(points = r.points.map { if (it.id == "p-2") it.copy(lat = 34.6009) else it }) }
        val plan = checkNotNull(PackItemSync.flushPlan(RoutePackKind, local, base, grounded, north, "Sam B."))
        val move = plan.ops.single()
        assertValue(json("""{"lat": 34.6009, "lon": -84.2}"""), move["value"], "the point's place, whole")

        // Someone else put the point at a grid first: this later move stands whole, as the later of two moves does.
        val elsewhere = PackFixtures.applyAll(
            "route", grounded.data,
            listOf(json("""{"type": "patch", "path": ["routes", {"id": "r-1"}, "points", {"id": "p-2"}], "value": {"lat": 34.3, "lon": -84.4}}""")),
        )
        val point = routes(PackFixtures.applyAll("route", elsewhere, plan.ops.map { JsonObject(it - "summary") }))[0].jsonObject.getValue("points").jsonArray[1].jsonObject
        assertValue(json("""{"lat": 34.6009, "lon": -84.2}"""), JsonObject(point.filterKeys { it == "lat" || it == "lon" }), "where it is")
    }

    @Test
    fun `someone else's move of a point one way only is laid over an older version whole`() {
        val local = localId(grounded)
        val base = PackItemSync.settled(RoutePackKind, local, grounded)
        val opened = RoutePackKind.fromItem(local, grounded, null)
        // An older version of this person's, from before they moved p-2 (undo would bring it back).
        val older = opened.mapRoute("r-1") { r -> r.copy(points = r.points.map { if (it.id == "p-2") it.copy(lat = 34.4, lon = -84.4) else it }) }
        // Someone else moved p-2 north only.
        val theirs = grounded.copy(
            data = PackFixtures.applyAll(
                "route", grounded.data,
                listOf(json("""{"type": "patch", "path": ["routes", {"id": "r-1"}, "points", {"id": "p-2"}], "value": {"lat": 34.65, "lon": -84.2}}""")),
            ),
        )
        val taken = PackItemSync.takeTheirs(RoutePackKind, local, base, theirs)
        assertEquals(34.65 to -84.2, taken.apply(opened).routes.single().points[1].let { it.lat to it.lon }, "the version showing")
        assertEquals(34.65 to -84.2, taken.apply(older).routes.single().points[1].let { it.lat to it.lon }, "the older version: their place, not half of it")
    }

    // Two routes share an id, so the routes are not a list the operations can address; a null route and one with no id beside them,
    // which the types cannot read; and a field of the set's own.
    private val tangled = PackItemView(
        "rt-9", "route", "MISSION 9",
        json(
            """
            {"version": 1, "mission": "HAWK",
             "routes": [
               {"id": "dup", "name": "RED 1", "points": [{"id": "p1", "lat": 34.7, "lon": -84.1}, {"id": "p2", "lat": 34.71, "lon": -84.09}],
                "plan": {"altitude": null}},
               null,
               {"id": "dup", "name": "RED 2", "points": [{"id": "q1", "lat": 34.8, "lon": -84.2}], "plan": {"altitude": null}},
               {"name": "NO ID", "points": []}
             ]}
            """,
        ),
    )

    @Test
    fun `routes that share an id go whole, each the editor did not change as it came and each it could not read in its place`() {
        val item = tangled
        val opened = RoutePackKind.fromItem(localId(item), item, null)
        assertEquals(2, opened.routes.size)
        val mine = opened.copy(
            routes = opened.routes.mapIndexed { i, r -> if (i == 0) r.copy(points = r.points.mapIndexed { j, p -> if (j == 0) p.copy(lat = 34.705) else p }) else r },
        )

        val doc = RoutePackKind.docOf(mine, item.data).jsonObject
        val before = routes(shape(item))
        assertEquals(JsonPrimitive("HAWK"), doc["mission"], "the set's own field is kept")
        val now = routes(doc)
        assertEquals(4, now.size)
        assertValue(JsonPrimitive(34.705), now[0].jsonObject.getValue("points").jsonArray[0].jsonObject["lat"], "the change")
        assertEquals(JsonNull, now[1], "the null route stays where it stood")
        assertValue(before[2], now[2], "the route not changed goes back as it came, its altitude still null")
        assertValue(before[3], now[3], "the route with no id stays where it stood")
        sent(item, mine)
    }

    @Test
    fun `routes that share an id keep what was not changed when one is added or removed`() {
        val item = tangled
        val opened = RoutePackKind.fromItem(localId(item), item, null)
        val before = routes(shape(item))

        val added = SketchRoute(id = "sketch-new", name = "BLUE 1", color = "#0A84FF", points = listOf(RoutePoint(id = "n1", lat = 34.9, lon = -84.3)))
        val grown = routes(RoutePackKind.docOf(opened.plus(added), item.data))
        assertEquals(5, grown.size)
        (0 until 4).forEach { assertValue(before[it], grown[it], "route $it as it was") }
        assertEquals(JsonPrimitive("sketch-new"), grown[4].jsonObject["id"], "the new route follows")
        sent(item, opened.plus(added))

        // The first RED goes: the second is still as it came (its altitude null), now in the first place, the others where they stood.
        val shrunk = routes(RoutePackKind.docOf(opened.copy(routes = opened.routes.drop(1)), item.data))
        assertEquals(3, shrunk.size)
        assertValue(before[2], shrunk[0], "the route not changed, as it came")
        assertEquals(JsonNull, shrunk[1])
        assertValue(before[3], shrunk[2], "the route with no id")
        sent(item, opened.copy(routes = opened.routes.drop(1)))
    }
}
