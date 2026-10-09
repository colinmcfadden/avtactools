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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An editor's change to a route set always reaches the pack ([RoutePackKind.docOf]). The editor works on types and the pack holds
 * JSON, and they disagree where the types fill in what the JSON lacks (a plan's altitude, airspeed or per-point values that are null
 * are read as the defaults) and where the routes cannot be addressed by id (two share one): a change there is sent coarser, never
 * dropped. Android only, so held here.
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
