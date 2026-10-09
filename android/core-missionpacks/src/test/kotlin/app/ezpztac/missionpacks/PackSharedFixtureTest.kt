package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.item
import app.ezpztac.missionpacks.PackFixtures.localId
import app.ezpztac.missionpacks.PackFixtures.name
import app.ezpztac.missionpacks.PackFixtures.webBug
import app.ezpztac.model.DiagramView
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every section of `contracts/fixtures/packs/shared.json`, which the web's feature/missionPacks wrote: what of an item is each person's
 * own, how a kept version is named and saved, and the shape an editor gives an item it opens. The shapes are held through the kinds as
 * the editors use them ([LzPackKind.currentShape], [RoutePackKind.currentShape], with the item's own data to carry from), not through a
 * shortcut that could hide what the carrying puts back.
 *
 * The cases marked `webBug` pin what the web gets wrong (it drops fields a newer version wrote, its route shape leaves out routes its
 * editor keeps, it cuts an emoji in half). The port does not copy them: they are left out of the replays, and the tests after them say
 * what the port does instead, each from the case's own data.
 */
class PackSharedFixtureTest {
    private val fixture = Fixtures.load("packs/shared.json")
    private val lzKind = LzPackKind(PackFixtures.env())

    private fun cases(section: String): List<JsonObject> = fixture.getValue(section).jsonArray.map { it.jsonObject }

    private fun replay(section: String, check: (JsonObject) -> Unit): List<DynamicTest> =
        cases(section).filterNot(::webBug).map { case -> DynamicTest.dynamicTest(name(case)) { check(case) } }

    // -- Each person's own fields --------------------------------------------------------------------------------------------------

    @TestFactory
    fun `an LZ's shared data loses each person's own fields`() = replay("lz") { case ->
        assertValue(case.getValue("shared"), PackLz.sharedLzData(case["data"]), name(case))
    }

    @TestFactory
    fun `a route set's shared data loses each route's own fields`() = replay("routes") { case ->
        assertValue(case.getValue("shared"), PackRoutes.sharedRouteData(case["data"]), name(case))
    }

    @TestFactory
    fun `an editor's routes become a version-1 set without their own fields`() = replay("routeSet") { case ->
        assertValue(case.getValue("data"), PackRoutes.routeSetData(case["routes"]), name(case))
    }

    @TestFactory
    fun `points are made ready for a pack, each with an id no other has`() = replay("points") { case ->
        assertValue(case.getValue("forPack"), PackPoints.pointsForPack(case["points"]), name(case))
    }

    @TestFactory
    fun `a kept version is saved in the library's form`() = replay("libraryData") { case ->
        val kind = case.getValue("kind").jsonPrimitive.content
        assertValue(case.getValue("saved"), PackActions.libraryData(kind, case["data"]), name(case))
    }

    // -- The name a kept version is saved under -------------------------------------------------------------------------------------

    @TestFactory
    fun `a kept version is named for whose edits they were`() = replay("myEditsName") { case ->
        assertEquals(case.getValue("output").jsonPrimitive.content, PackSentences.myEditsName(case.getValue("input").jsonPrimitive.content), name(case))
    }

    @Test
    fun `an emoji across the cut goes whole, where the web leaves half of it`() {
        val marked = cases("myEditsName").filter(::webBug)
        assertEquals(1, marked.size)
        for (case in marked) {
            val web = case.getValue("output").jsonPrimitive.content
            assertTrue(PackFixtures.hasLoneSurrogate(web), "the case is the web's lone surrogate")
            val name = PackSentences.myEditsName(case.getValue("input").jsonPrimitive.content)
            // The web's name less the half it left: 99 units, every character whole.
            assertEquals(web.dropLast(1), name)
            assertFalse(PackFixtures.hasLoneSurrogate(name))
        }
        for (case in cases("myEditsName")) {
            val name = PackSentences.myEditsName(case.getValue("input").jsonPrimitive.content)
            assertTrue(name.length <= PackSentences.MAX_KEPT_NAME, name(case))
            assertFalse(PackFixtures.hasLoneSurrogate(name), name(case))
        }
    }

    // -- An LZ/PZ's shape -----------------------------------------------------------------------------------------------------------

    private fun lzShape(case: JsonObject): JsonElement {
        val item = item(case.getValue("item"))
        return lzKind.currentShape(localId(item), item)
    }

    @TestFactory
    fun `an LZ's shape is the web's editor's`() = replay("lzShape") { case ->
        assertValue(case.getValue("shape"), lzShape(case), name(case))
    }

    // [level, key] for each field of [data] the web does not read: one a newer version wrote, which the web drops and the port keeps.
    private fun unknownLzFields(data: JsonElement?): List<Pair<String, String>> {
        if (data !is JsonObject) return emptyList()
        fun unknown(level: String, value: JsonElement?) =
            (value as? JsonObject)?.keys.orEmpty().filter { it !in PackFixtures.lzReadNames.getValue(level) }.map { level to it }
        return unknown("top", data) + unknown("target", data["target"]) + unknown("analysis", data["analysis"]) + unknown("graphics", data["graphics"])
    }

    private fun valueAt(data: JsonObject, field: Pair<String, String>): JsonElement? =
        if (field.first == "top") data[field.second] else (data[field.first] as JsonObject)[field.second]

    @Test
    fun `the cases the web marks are exactly those with fields it does not know`() {
        cases("lzShape").forEach { case ->
            assertEquals(webBug(case), unknownLzFields(case.getValue("item").jsonObject["data"]).isNotEmpty(), name(case))
        }
        assertEquals(1, cases("lzShape").count(::webBug))
    }

    @Test
    fun `an LZ keeps the fields a newer version wrote, where the web drops them`() {
        for (case in cases("lzShape").filter(::webBug)) {
            val data = case.getValue("item").jsonObject.getValue("data").jsonObject
            val unknown = unknownLzFields(data)
            assertEquals(4, unknown.size, "weather, target.elevationFt, analysis.futureAnalysis and graphics.futureGraphics")

            // The web's shape with each of them put back where it was, as it was.
            val fields = LinkedHashMap(case.getValue("shape").jsonObject)
            for (field in unknown) {
                val value = valueAt(data, field)!!
                if (field.first == "top") {
                    fields[field.second] = value
                } else {
                    fields[field.first] = JsonObject((fields.getValue(field.first) as JsonObject) + (field.second to value))
                }
            }
            assertValue(JsonObject(fields), lzShape(case), name(case))
        }
    }

    @Test
    fun `an LZ's first change sends no null for a field a newer version wrote`() {
        for (case in cases("lzShape").filter(::webBug)) {
            val item = item(case.getValue("item"))
            val data = item.data.jsonObject
            val opened = lzKind.fromItem(localId(item), item, null)
            val changed = opened.copy(flightData = JsonObject(opened.flightData + ("callSign" to JsonPrimitive("HAWK 7"))))
            val sent = PackEdit.composeEdit(
                uuid = item.uuid,
                base = EditVersion(item.name, lzKind.currentShape(localId(item), item)),
                mine = EditVersion(item.name, lzKind.docOf(changed, item.data)),
                item = item,
                shared = { lzKind.shared(item.data) },
                current = { lzKind.currentShape(localId(item), item) },
                describe = lzKind::describe,
                actor = "Sam B.",
            )
            assertTrue(sent != null && sent.ops.isNotEmpty(), "the change is sent")
            val after = PackFixtures.applyAll("lz", data, sent!!.ops).jsonObject
            for (field in unknownLzFields(data)) {
                assertEquals(valueAt(data, field), valueAt(after, field), "$field arrives as it was")
            }
            // And what arrives is the editor's version: read back, its shape is the document that was sent.
            assertValue(lzKind.docOf(changed, item.data), lzKind.currentShape(localId(item), item.copy(data = after)), "the change arrives whole")
        }
    }

    // -- A route set's shape --------------------------------------------------------------------------------------------------------

    private fun routeShape(item: PackItemView): JsonElement = RoutePackKind.currentShape(localId(item), item)

    @TestFactory
    fun `a route set's shape is the web's editor's`() = replay("routeShape") { case ->
        assertValue(case.getValue("shape"), routeShape(item(case.getValue("item"))), name(case))
    }

    // What the port's shape is where the web's is marked: the set's own fields and version kept, and every route that is not an object
    // with an id kept where it stands, as the set's shared data has it; the routes with ids are the web's.
    private fun portShape(case: JsonObject): JsonObject {
        val data = case.getValue("item").jsonObject.getValue("data").jsonObject
        val webRoutes = case.getValue("shape").jsonObject.getValue("routes").jsonArray.iterator()
        val shared = PackRoutes.sharedRouteData(data).jsonObject.getValue("routes").jsonArray
        val routes = data.getValue("routes").jsonArray.mapIndexed { i, route ->
            if (route is JsonObject && "id" in route) webRoutes.next() else shared[i]
        }
        assertFalse(webRoutes.hasNext())
        return JsonObject(LinkedHashMap(data).apply { put("routes", JsonArray(routes)) })
    }

    @Test
    fun `a set keeps its own fields, its version and routes without ids, where the web's shape loses them`() {
        val marked = cases("routeShape").filter(::webBug)
        assertEquals(3, marked.size)
        for (case in marked) {
            val item = item(case.getValue("item"))
            val shape = routeShape(item)
            assertValue(portShape(case), shape, name(case))
            assertNotEquals(case.getValue("shape"), shape, "${name(case)}: the web's shape loses something here")
        }
    }

    @TestFactory
    fun `a set holding what the web's shape loses sends nothing on open, and takes theirs once`() = cases("routeShape").filter(::webBug).map { case ->
        DynamicTest.dynamicTest(name(case)) {
            val item = item(case.getValue("item"))
            val opened = RoutePackKind.fromItem(localId(item), item, null)
            val current = routeShape(item)
            // The editor's document is the shape: opening it sends nothing.
            assertValue(current, RoutePackKind.docOf(opened, item.data), "the editor's document is the shape")
            assertNull(compose(item, current, RoutePackKind.docOf(opened, item.data)), "nothing is sent on open")

            // Someone else moves a point: the editor takes theirs, and has nothing more to send.
            val theirs = item.copy(data = movedFirstPoint(item.data, 34.705))
            val taken = RoutePackKind.withShared(opened, routeShape(theirs), theirs.name)
            assertValue(routeShape(theirs), RoutePackKind.docOf(taken, theirs.data), "theirs taken")
            assertNull(compose(theirs, routeShape(theirs), RoutePackKind.docOf(taken, theirs.data)), "nothing is sent after theirs")

            // A change here is sent, and arrives as the editor has it, with what the editor could not read still where it was.
            val route = taken.routes.single()
            val mine = taken.mapRoute(route.id) { it.copy(points = it.points.mapIndexed { i, p -> if (i == 0) p.copy(lat = 34.71) else p }) }
            val sent = compose(theirs, routeShape(theirs), RoutePackKind.docOf(mine, theirs.data))
            assertTrue(sent != null && sent.ops.isNotEmpty(), "the change is sent")
            val arrived = PackFixtures.applyAll("route", theirs.data, sent!!.ops)
            assertValue(RoutePackKind.docOf(mine, theirs.data), routeShape(theirs.copy(data = arrived)), "the change arrives whole")
            val before = theirs.data.jsonObject
            before.keys.filter { it != "routes" }.forEach { assertEquals(before[it], arrived.jsonObject[it], "the set's $it is kept") }
            val unreadable = before.getValue("routes").jsonArray.withIndex().filter { (_, r) -> r !is JsonObject || "id" !in r }
            unreadable.forEach { (i, r) ->
                assertValue(PackRoutes.sharedRouteData(JsonObject(mapOf("routes" to JsonArray(listOf(r))))).jsonObject.getValue("routes").jsonArray[0],
                    arrived.jsonObject.getValue("routes").jsonArray[i], "route $i stays where it stood")
            }
        }
    }

    private fun compose(item: PackItemView, base: JsonElement, mine: JsonElement): ComposedEdit? = PackEdit.composeEdit(
        uuid = item.uuid,
        base = EditVersion(item.name, base),
        mine = EditVersion(item.name, mine),
        item = item,
        shared = { RoutePackKind.shared(item.data) },
        current = { RoutePackKind.currentShape(localId(item), item) },
        describe = RoutePackKind::describe,
        actor = "Sam B.",
    )

    // The data with the first point of its route with an id moved to [lat].
    private fun movedFirstPoint(data: JsonElement, lat: Double): JsonElement {
        val set = data.jsonObject
        var done = false
        val routes = set.getValue("routes").jsonArray.map { route ->
            if (done || route !is JsonObject || "id" !in route) return@map route
            done = true
            val points = route.getValue("points").jsonArray
            val first = JsonObject(points[0].jsonObject + ("lat" to JsonPrimitive(lat)))
            JsonObject(route + ("points" to JsonArray(listOf(first) + points.drop(1))))
        }
        return JsonObject(set + ("routes" to JsonArray(routes)))
    }

    // -- Both shapes ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a shape read back is itself, so an editor's own document never asks for a reshape`() {
        for (case in cases("lzShape")) {
            val item = item(case.getValue("item"))
            val shape = lzKind.currentShape(localId(item), item)
            assertValue(shape, lzKind.currentShape(localId(item), item.copy(data = shape)), name(case))
        }
        for (case in cases("routeShape")) {
            val item = item(case.getValue("item"))
            val shape = routeShape(item)
            assertValue(shape, routeShape(item.copy(data = shape)), name(case))
        }
    }

    @Test
    fun `a shape is the same whenever it is made`() {
        val later = LzPackKind(PackFixtures.env("2031-01-01T00:00:00.000Z"))
        for (case in cases("lzShape")) {
            val item = item(case.getValue("item"))
            assertEquals(lzKind.currentShape(localId(item), item), later.currentShape(localId(item), item), name(case))
            assertEquals(lzKind.currentShape(localId(item), item), lzKind.currentShape(PackRef.localId("another-pack", "lz-9"), item), name(case))
        }
    }

    @Test
    fun `each person's own fields stay on the device and come back to the editor`() {
        val case = cases("lzShape").first()
        val item = item(case.getValue("item"))
        val opened = lzKind.fromItem(localId(item), item, null)
        val own = lzKind.ownOf(opened.copy(view = opened.view.copy(mapStyle = "vfr-sectional", showHeatmap = true)))
        val reopened = lzKind.fromItem(localId(item), item, own)
        assertEquals("vfr-sectional", reopened.view.mapStyle)
        assertTrue(reopened.view.showHeatmap)
        // The view is never in the pack: the shape is the same whatever it is.
        assertEquals(lzKind.docOf(opened, item.data), lzKind.docOf(reopened, item.data))

        val routes = item(cases("routeShape").first().getValue("item"))
        val set = RoutePackKind.fromItem(localId(routes), routes, null)
        val hidden = set.mapRoute(set.routes.first().id) { it.copy(visible = false) }
        val own2 = RoutePackKind.ownOf(hidden)
        assertEquals(listOf(set.routes.first().id), own2.getValue("hidden").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(RoutePackKind.fromItem(localId(routes), routes, own2).routes.first().visible)
        assertEquals(RoutePackKind.docOf(set, routes.data), RoutePackKind.docOf(hidden, routes.data), "hiding a route changes nothing in the pack")
        // Someone else's change keeps what this person hid.
        assertFalse(RoutePackKind.withShared(hidden, routeShape(routes), "MISSION 2").routes.first().visible)
    }

    @Test
    fun `an item opens as nobody's record, under the editor's id and the item's name`() {
        // One case's data names a record of its own and unsaved changes: they are someone's, never the pack's.
        assertTrue(cases("lzShape").any { (it.getValue("item").jsonObject["data"] as? JsonObject)?.get("savedId") is JsonPrimitive })
        for (case in cases("lzShape")) {
            val item = item(case.getValue("item"))
            val opened = lzKind.fromItem(localId(item), item, null)
            assertEquals(localId(item), opened.id, name(case))
            assertEquals(item.name, opened.name, name(case))
            assertEquals(JsonNull, opened.savedId, name(case))
            assertFalse(opened.dirty, name(case))
        }
        for (case in cases("routeShape")) {
            val item = item(case.getValue("item"))
            val opened = RoutePackKind.fromItem(localId(item), item, null)
            assertEquals(localId(item), opened.id, name(case))
            assertEquals(item.name, opened.name, name(case))
            assertNull(opened.savedId, name(case))
        }
    }

    @Test
    fun `an LZ takes someone else's change once, and keeps what is its own`() {
        val item = item(cases("lzShape").first().getValue("item"))
        val opened = lzKind.fromItem(localId(item), item, null)
        val view = DiagramView(mapStyle = "vfr-sectional", showLZOutline = false, showHeatmap = true)
        // The document's own fields, whatever they are: someone else's change never touches them.
        val mine = lzKind.fromItem(localId(item), item, lzKind.ownOf(opened.copy(view = view))).copy(
            savedId = JsonPrimitive(12), dirty = true, createdAt = "2026-01-02T03:04:05.000Z", updatedAt = "2026-01-03T03:04:05.000Z",
        )
        assertEquals(view, mine.view)

        val data = item.data.jsonObject
        val theirs = item.copy(data = JsonObject(data + ("flightData" to JsonObject(mapOf("callSign" to JsonPrimitive("HAWK 9"))))))
        val taken = lzKind.withShared(mine, lzKind.currentShape(localId(theirs), theirs), "NEW")
        assertEquals(mine.id, taken.id)
        assertEquals("NEW", taken.name)
        assertEquals(view, taken.view)
        assertEquals(JsonPrimitive(12), taken.savedId)
        assertTrue(taken.dirty)
        assertEquals(mine.createdAt, taken.createdAt)
        assertEquals(mine.updatedAt, taken.updatedAt)
        assertValue(lzKind.currentShape(localId(theirs), theirs), lzKind.docOf(taken, theirs.data), "theirs taken")
        assertNotEquals(lzKind.docOf(mine, item.data), lzKind.docOf(taken, theirs.data), "theirs is a change")
    }

    @Test
    fun `every section has its cases, and the shapes cover what the description says`() {
        listOf("lz", "routes", "routeSet", "points", "myEditsName", "libraryData", "lzShape", "routeShape").forEach {
            assertTrue(cases(it).isNotEmpty(), it)
        }
        assertEquals(setOf("draft", "targeted", "analyzed"), cases("lzShape").map { it.getValue("shape").jsonObject.getValue("status").jsonPrimitive.content }.toSet())
        assertTrue(cases("routeShape").any { (it.getValue("item").jsonObject["data"] as? JsonObject)?.get("routes") is JsonArray })
    }
}
