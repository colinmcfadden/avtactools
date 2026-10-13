package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.assertJson
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.DragTarget
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Units
import app.ezpztac.planning.Designation
import app.ezpztac.planning.DraftPoint
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.SketchOps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * A Mission Pack's route set open on two devices at once: Colin's (A, the pack's owner) and Sam's (B), each with its own route session, pack engine and
 * copy, over one pack server and one clock, as the owner tried it (2026-10-13: a checkpoint moved on one device did not move on the other, or showed
 * somewhere else, and the web then drew the route twice). Each move is made the way the app makes it (the Routes panel's edit, [MapDrag]'s drag) and
 * checked where the map draws it from, the other device's open set, and in what the pack holds: the route once, under its id, where it was put last.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePackTwoDevicesTest {
    // A regression that loops without end fails here rather than holding the build (PackEditorRig.MAX_BATCHES cuts a loop of sends short sooner).
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        const val ROUTE = "sketch-1760000000000-ab12cd34"

        /** Longer than a poll (3 s), and the sends either side of it. */
        const val POLL = 4_000L
    }

    private fun twoDevices(body: suspend TestScope.(a: PackEditorRig, b: PackEditorRig) -> Unit) = runTest {
        val b = PackEditorRig(this)
        val a = b.other(COLIN)
        try {
            body(a, b)
        } finally {
            a.close()
            b.close()
        }
    }

    private fun PackEditorRig.set(): RouteSet = checkNotNull(routes.active.value)

    private fun PackEditorRig.point(id: String, route: String = ROUTE): RoutePoint = checkNotNull(set().route(route)).points.single { it.id == id }

    private fun PackEditorRig.at(id: String, route: String = ROUTE): LatLon = point(id, route).let { LatLon(it.lat, it.lon) }

    // The routes of [item] on the server, each as its id.
    private fun PackEditorRig.serverRouteIds(item: String): List<String> =
        serverData(item).getValue("routes").jsonArray.map { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.toString() }

    private fun PackEditorRig.serverPoint(item: String, pointId: String, route: String = ROUTE): JsonObject =
        serverData(item).getValue("routes").jsonArray.single { it.jsonObject["id"] == JsonPrimitive(route) }.jsonObject
            .getValue("points").jsonArray.single { it.jsonObject["id"] == JsonPrimitive(pointId) }.jsonObject

    // -- Moving a point as the app moves one ----------------------------------------------------------------------------------------------------

    // RoutesViewModel's move (its nudge, "put at the crosshair" and a typed grid): one edit, the point where [to] puts it, the ground fetched for the
    // old place forgotten.
    private fun PackEditorRig.movePoint(pointId: String, route: String = ROUTE, to: (LatLon) -> LatLon) {
        routes.edit("Move point") { set -> set.withPointMoved(route, pointId, to) }
    }

    private fun PackEditorRig.nudge(pointId: String, northFt: Double, eastFt: Double) =
        movePoint(pointId) { GraphicEdits.offset(it, northFt / Units.METERS_TO_FEET, eastFt / Units.METERS_TO_FEET) }

    private fun PackEditorRig.putAt(pointId: String, at: LatLon) = movePoint(pointId) { at }

    private fun PackEditorRig.typeGrid(pointId: String, text: String): LatLon {
        val found = PlaceSearch.resolve(text) as PlaceResult.Found
        movePoint(pointId) { found.at }
        return found.at
    }

    // A long press on the point, the finger moved in [frames] steps, and lifted [northM] north and [eastM] east of where it picked the point up.
    private fun PackEditorRig.drag(pointId: String, northM: Double, eastM: Double, frames: Int = 10) {
        val threats = ThreatStore(NoThreatVault, scope, dispatcher) { 1_000L }
        val drag = MapDrag(diagrams, routes, threats)
        val start = at(pointId)
        assertTrue("picked up", drag.start(DragTarget.RoutePoint(ROUTE, pointId), start))
        for (i in 1..frames) {
            drag.move(GraphicEdits.offset(start, northM * i / (frames + 1), eastM * i / (frames + 1)))
            advance(16)
        }
        drag.end(GraphicEdits.offset(start, northM, eastM))
    }

    private object NoThreatVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) = Unit
        override fun wipe() = Unit
    }

    // -- The set ------------------------------------------------------------------------------------------------------------------------------------

    // RED 1 as the app draws it, each point named from the draw menu: the SP, three checkpoints and the RP.
    private fun red1(): SketchRoute {
        var n = 0
        return checkNotNull(
            SketchOps.build(
                listOf(
                    DraftPoint(34.50, -84.10, Designation("start", ".SP")),
                    DraftPoint(34.52, -84.12, Designation("turn", ".CP1")),
                    DraftPoint(34.55, -84.15, Designation("turn", ".CP2")),
                    DraftPoint(34.58, -84.18, Designation("turn", ".CP3")),
                    DraftPoint(34.60, -84.20, Designation("ip", ".RP")),
                ),
                "RED 1", ROUTE, "#FF453A", RoutePlan.forAircraft(),
            ) { "pt-${++n}" },
        )
    }

    /** OP DK with a route set A made and drew RED 1 in, open on both devices ([bFirst]: B opened it before RED 1 was drawn). Its item's uuid. */
    private suspend fun drawnOnA(a: PackEditorRig, b: PackEditorRig, bFirst: Boolean = false): String {
        b.pack(PACK, "OP DK")
        a.start(); a.openPack()
        b.start(); b.openPack()
        val item = checkNotNull(PackRef.parse(checkNotNull(a.workspace.createRouteSet("ROUTES 1")))).item
        a.advance(POLL)
        if (bFirst) b.openItem(item)
        a.routes.edit("Draw route") { it.plus(red1()) }
        a.advance(STILL)
        a.advance(POLL)
        if (!bFirst) b.openItem(item)
        assertEquals(listOf(ROUTE), b.set().routes.map { it.id })
        return item
    }

    // The ground fetched for each named point of RED 1 on [rig], as RoutePlanning.fetchElevations lands it (fetched data, through the session's update).
    private suspend fun PackEditorRig.fetchedGround() {
        routes.update(set().id) { set -> set.mapRoute(ROUTE) { r -> r.copy(elevations = r.points.withIndex().associate { (i, p) -> p.id!! to 1_000.0 + 10 * i }) } }
        advance(STILL)
        advance(POLL)
    }

    // Both devices show [pointId] at [where] and every other point of RED 1 where [before] had it; the pack holds RED 1 once, the point there.
    private fun assertMoved(a: PackEditorRig, b: PackEditorRig, item: String, pointId: String, where: LatLon, before: RouteSet) {
        for ((label, rig) in listOf("Colin's device" to a, "Sam's device" to b)) {
            assertEquals("$label: RED 1 once", listOf(ROUTE), rig.set().routes.map { it.id })
            assertEquals("$label: the point where it was put", where, rig.at(pointId))
            for (p in checkNotNull(before.route(ROUTE)).points.filter { it.id != pointId }) {
                assertEquals("$label: ${p.id} did not move", LatLon(p.lat, p.lon), rig.at(p.id!!))
            }
        }
        assertEquals("the pack holds RED 1 once", listOf(ROUTE), a.serverRouteIds(item))
        val held = a.serverPoint(item, pointId)
        assertEquals(where.lat, held.getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(where.lon, held.getValue("lon").jsonPrimitive.double, 0.0)
    }

    // -- One device moves, the other follows --------------------------------------------------------------------------------------------------------

    @Test
    fun `a checkpoint moved off its fetched ground reaches the other device in place, and the pack holds the route once`() = twoDevices { a, b ->
        val item = drawnOnA(a, b)
        a.fetchedGround()
        assertEquals(1_010.0, b.set().routes.single().elevations["pt-2"])
        val before = b.set()
        val sentBefore = a.sent().size

        a.nudge("pt-2", northFt = 200.0, eastFt = -50.0)
        val where = a.at("pt-2")
        a.advance(STILL)
        // One change, at the point and at the ground that went with the old place, the way the web would address it.
        val move = a.sent().drop(sentBefore).single()
        assertEquals(
            listOf(
                listOf("routes", ROUTE, "points", "pt-2"),
                listOf("routes", ROUTE, "elevations"),
            ),
            move.map { op -> op.getValue("path").jsonArray.map { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.jsonPrimitive.content } },
        )
        assertEquals(listOf("patch", "patch"), move.map { it.getValue("type").jsonPrimitive.content })
        assertJson(json("""{"pt-2": null}"""), move[1]["value"], "the ground that went")
        a.advance(POLL)
        a.advance(POLL)

        assertMoved(a, b, item, "pt-2", where, before)
        assertEquals("nothing sent after the move: the set never went whole", sentBefore + 1, a.sent().size)
        assertEquals(emptyList<List<JsonObject>>(), b.sent())
        assertEquals("the ground for the moved point is gone on both devices", null to null, a.set().routes.single().elevations["pt-2"] to b.set().routes.single().elevations["pt-2"])
        assertEquals(1_000.0, b.set().routes.single().elevations["pt-1"])
    }

    @Test
    fun `a checkpoint put at the crosshair, at a typed grid and dragged each reaches the other device where it was put`() = twoDevices { a, b ->
        val item = drawnOnA(a, b, bFirst = true)
        a.fetchedGround()

        val crosshair = LatLon(34.5312, -84.1287)
        var before = b.set()
        a.putAt("pt-3", crosshair)
        a.advance(STILL); a.advance(POLL)
        assertMoved(a, b, item, "pt-3", crosshair, before)

        before = b.set()
        val grid = a.typeGrid("pt-4", "16S GC 18000 28000")
        a.advance(STILL); a.advance(POLL)
        assertMoved(a, b, item, "pt-4", grid, before)

        before = b.set()
        val sent = a.sent().size
        a.drag("pt-2", northM = 120.0, eastM = 80.0)
        val dropped = a.at("pt-2")
        a.advance(STILL)
        assertEquals("a drag is one change", sent + 1, a.sent().size)
        a.advance(POLL); a.advance(POLL)
        assertMoved(a, b, item, "pt-2", dropped, before)
        assertEquals(emptyList<List<JsonObject>>(), b.sent())
    }

    @Test
    fun `a clock moved from one checkpoint to another, then a checkpoint moved, leaves the route once on both devices and in the pack`() = twoDevices { a, b ->
        val item = drawnOnA(a, b)
        a.routes.edit("Change point") { set -> set.mapRoute(ROUTE) { SketchOps.withClock(it, "pt-1", "12:00:00") } }
        a.advance(STILL)
        // The RP takes the clock: the SP's entry, which held only its clock, goes, and the pack is sent it as null.
        a.routes.edit("Change point") { set -> set.mapRoute(ROUTE) { SketchOps.withClock(it, "pt-5", "12:30:00") } }
        a.advance(STILL); a.advance(POLL)
        assertEquals(setOf("pt-5"), b.set().routes.single().plan.perPoint.keys)

        val before = b.set()
        a.nudge("pt-2", northFt = 100.0, eastFt = 100.0)
        val where = a.at("pt-2")
        a.advance(STILL); a.advance(POLL); a.advance(POLL)
        assertMoved(a, b, item, "pt-2", where, before)
        assertEquals("12:30:00", b.set().routes.single().plan.perPoint.getValue("pt-5").clock)
    }

    // -- Both at once ------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `both move the same checkpoint, and the move that reaches the pack later stands on both devices, and the pack holds the route once`() = twoDevices { a, b ->
        val item = drawnOnA(a, b)
        a.fetchedGround()
        val before = b.set()

        a.nudge("pt-4", northFt = 300.0, eastFt = 0.0)
        a.advance(STILL)
        b.drag("pt-4", northM = -60.0, eastM = 40.0)
        b.advance(STILL)
        val bWhere = b.at("pt-4")
        a.advance(POLL); a.advance(POLL)
        assertMoved(a, b, item, "pt-4", bWhere, before)

        // And again the other way, the RP this time, as the owner did.
        val again = b.set()
        b.typeGrid("pt-5", "16S GC 20000 30000")
        b.advance(STILL)
        a.nudge("pt-5", northFt = 0.0, eastFt = 500.0)
        val aWhere = a.at("pt-5")
        a.advance(STILL)
        a.advance(POLL); a.advance(POLL)
        assertMoved(a, b, item, "pt-5", aWhere, again)
    }

    @Test
    fun `each moves a different checkpoint, one while the other's change waits, and both stand everywhere`() = twoDevices { a, b ->
        val item = drawnOnA(a, b)
        a.fetchedGround()

        b.nudge("pt-3", northFt = 0.0, eastFt = 250.0)            // waits on B for the session's pause
        a.nudge("pt-2", northFt = 250.0, eastFt = 0.0)
        a.advance(STILL)                                           // A's goes; B's goes too, and each device hears the other's at its next poll
        a.advance(POLL); a.advance(POLL)
        val two = a.at("pt-2")
        val three = b.at("pt-3")
        for (rig in listOf(a, b)) {
            assertEquals(two, rig.at("pt-2"))
            assertEquals(three, rig.at("pt-3"))
        }
        assertEquals(listOf(ROUTE), a.serverRouteIds(item))
        assertEquals(two.lat, a.serverPoint(item, "pt-2").getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(three.lon, a.serverPoint(item, "pt-3").getValue("lon").jsonPrimitive.double, 0.0)

        // B's own undo takes back only B's move; A's stays.
        b.routes.undo()
        b.advance(STILL); a.advance(POLL); a.advance(POLL)
        for (rig in listOf(a, b)) {
            assertEquals(two, rig.at("pt-2"))
            assertEquals(LatLon(34.55, -84.15), rig.at("pt-3"))
        }
        assertEquals(listOf(ROUTE), a.serverRouteIds(item))
    }

    @Test
    fun `a device that opens the set after the move has the checkpoint where it was put`() = twoDevices { a, b ->
        b.pack(PACK, "OP DK")
        a.start(); a.openPack()
        b.start(); b.openPack()
        val item = checkNotNull(PackRef.parse(checkNotNull(a.workspace.createRouteSet("ROUTES 1")))).item
        a.routes.edit("Draw route") { it.plus(red1()) }
        a.advance(STILL)
        a.fetchedGround()
        a.nudge("pt-4", northFt = -120.0, eastFt = 60.0)
        val where = a.at("pt-4")
        a.advance(STILL); a.advance(POLL); a.advance(POLL)

        b.openItem(item)
        assertEquals(listOf(ROUTE), b.set().routes.map { it.id })
        assertEquals(where, b.at("pt-4"))
        assertEquals(listOf(ROUTE), a.serverRouteIds(item))
        assertEquals(emptyList<List<JsonObject>>(), b.sent())
    }

    // -- Sets the web made, and what the types cannot hold --------------------------------------------------------------------------------------

    // A plan as the web's defaultRoutePlan writes it, numbers as JavaScript writes them.
    private val webPlan = """{"aircraft": "UH-60L Black Hawk", "aircraftProfile": "uh60l", "airspeed": {"value": 100, "type": "ground"},
        "altitude": {"value": 50, "ref": "agl"}, "wind": {"dirTrue": 0, "speedKts": 0}, "tempC": 15, "fuelFlowLbHr": 960, "date": "", "perPoint": {}}"""

    // RED 1 as the web's sketch writes it into a pack (usePackRoutes): its own fields gone, `ele: null` on each point, keys in the web's order, the
    // ground fetched for two points and a clock on the RP; and beside it a route whose points have no ids, and one this version cannot read.
    private val webSet = json(
        """{"version": 1, "routes": [
              {"id": "$ROUTE", "name": "RED 1", "color": "#FF453A",
               "plan": ${webPlan.replace("\"perPoint\": {}", "\"perPoint\": {\"pt-5\": {\"clock\": \"12:30:00\"}}")},
               "elevations": {"pt-1": 1000, "pt-2": 1010},
               "points": [
                 {"id": "pt-1", "lat": 34.5, "lon": -84.1, "ele": null, "role": "start", "kind": "amps", "ptType": "target", "name": ".SP"},
                 {"id": "pt-2", "lat": 34.52, "lon": -84.12, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "turn", "name": ".CP1", "chartElevationFt": 1730},
                 {"id": "pt-3", "lat": 34.55, "lon": -84.15, "ele": null, "role": "waypoint", "kind": "shaping", "ptType": null, "name": ""},
                 {"id": "pt-5", "lat": 34.6, "lon": -84.2, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "target", "name": ".RP"}]},
              {"id": "sketch-old", "name": "BLUE 1", "color": "#0A84FF", "plan": $webPlan, "elevations": {},
               "points": [{"lat": 34.7, "lon": -84.3, "kind": "amps", "name": ".SP"}, {"lat": 34.8, "lon": -84.4, "kind": "amps", "name": ".RP"}]},
              {"id": "sketch-odd", "name": "GREEN 1", "color": "#32D74B", "plan": $webPlan, "elevations": {}, "points": "not a list"}
           ]}""",
    )

    @Test
    fun `in a set the web made, a checkpoint moved on Android lands in place, and what Android cannot read stays as it was`() = twoDevices { a, b ->
        b.pack(PACK, "OP DK", create("rt-1", "route", "ROUTES 1", webSet))
        a.start(); a.openPack(); a.openItem("rt-1")
        b.start(); b.openPack(); b.openItem("rt-1")
        assertEquals(listOf(ROUTE, "sketch-old"), b.set().routes.map { it.id })
        val before = b.set()

        a.nudge("pt-2", northFt = 150.0, eastFt = 150.0)
        val where = a.at("pt-2")
        a.advance(STILL)
        // A patch at the point: its place, and the charted elevation it had snapped to, which a move forgets; and the ground that went with it.
        // In the order of the set's own keys (the web's: the plan, the ground, then the points), as the web would send them.
        val move = a.sent().single()
        assertEquals(listOf("patch", "patch"), move.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(listOf("elevations", "pt-2"), move.map { op -> op.getValue("path").jsonArray.last().let { (it as? JsonObject)?.get("id") ?: it }.jsonPrimitive.content })
        assertJson(json("""{"pt-2": null}"""), move[0]["value"], "the ground that went")
        val value = move[1].getValue("value").jsonObject
        assertEquals(listOf("lat", "lon", "chartElevationFt"), value.keys.toList())
        assertEquals(JsonNull, value["chartElevationFt"])
        a.advance(POLL); a.advance(POLL)

        for (rig in listOf(a, b)) {
            assertEquals(where, rig.at("pt-2"))
            assertEquals(LatLon(34.6, -84.2), rig.at("pt-5"))
            assertEquals(before.route("sketch-old"), rig.set().route("sketch-old"))
        }
        assertEquals(listOf(ROUTE, "sketch-old", "sketch-odd"), a.serverRouteIds("rt-1"))
        assertJson(webSet.getValue("routes").jsonArray[1], a.serverData("rt-1").getValue("routes").jsonArray[1], "the route with no point ids, as it came")
        assertJson(webSet.getValue("routes").jsonArray[2], a.serverData("rt-1").getValue("routes").jsonArray[2], "the route this version cannot read, as it came")
        assertEquals("the move, and nothing after it", 1, a.sent().size)
        assertEquals(emptyList<List<JsonObject>>(), b.sent())
    }

    @Test
    fun `the web's own move of a checkpoint lands in place on Android, its snapped elevation and fetched ground as the web left them`() = twoDevices { _, b ->
        b.pack(PACK, "OP DK", create("rt-1", "route", "ROUTES 1", webSet))
        b.start(); b.openPack(); b.openItem("rt-1")
        // What the web's sketch sends for a drag of .CP1 (movePoint: the place, the charted elevation left undefined, so gone; the fetched ground kept).
        b.theirs(
            json(
                """{"type": "patch", "item": "rt-1", "path": ["routes", {"id": "$ROUTE"}, "points", {"id": "pt-2"}],
                    "value": {"lat": 34.5234567, "lon": -84.1265432, "chartElevationFt": null}}""",
            ),
        )
        assertEquals(LatLon(34.5234567, -84.1265432), b.at("pt-2"))
        assertEquals(null, b.point("pt-2").chartElevationFt)
        assertEquals(1_010.0, b.set().route(ROUTE)!!.elevations["pt-2"])
        b.advance(POLL)
        assertEquals(emptyList<List<JsonObject>>(), b.sent())
    }

    // -- A set an earlier version already wrote a route of twice --------------------------------------------------------------------------------------

    @Test
    fun `a set that holds a route twice shows both, as the web does, and deleting the route takes both away for everyone`() = twoDevices { a, b ->
        // What the owner's pack holds (2026-10-13): RED 1 twice under one id, the RP where each person last put it, one copy with the ground of a moved point null.
        val route = webSet.getValue("routes").jsonArray[0].jsonObject
        fun withRp(lat: Double, lon: Double, ground: JsonObject) = JsonObject(
            route + mapOf(
                "elevations" to ground,
                "points" to JsonArray(route.getValue("points").jsonArray.map { p -> if (p.jsonObject["id"] == JsonPrimitive("pt-5")) JsonObject(p.jsonObject + mapOf("lat" to JsonPrimitive(lat), "lon" to JsonPrimitive(lon))) else p }),
            ),
        )
        val twice = JsonObject(
            webSet + ("routes" to JsonArray(listOf(withRp(34.61, -84.21, json("""{"pt-1": 1000, "pt-5": null}""")), withRp(34.62, -84.19, json("""{"pt-1": 1000}"""))))),
        )
        b.pack(PACK, "OP DK", create("rt-1", "route", "ROUTES 1", twice))
        a.start(); a.openPack(); a.openItem("rt-1")
        b.start(); b.openPack(); b.openItem("rt-1")
        for (rig in listOf(a, b)) {
            assertEquals(listOf(ROUTE, ROUTE), rig.set().routes.map { it.id })
            assertEquals(listOf(34.61, 34.62), rig.set().routes.map { r -> r.points.single { it.id == "pt-5" }.lat })
        }
        a.advance(POLL)
        assertEquals("opening it sends nothing", emptyList<List<JsonObject>>(), a.sent())

        // Until it is repaired, a move reaches both copies, the set goes whole, and no third copy is ever made.
        a.drag("pt-5", northM = 50.0, eastM = 0.0)
        a.advance(STILL); a.advance(POLL); a.advance(POLL)
        val rp = a.set().routes.first().points.single { it.id == "pt-5" }.let { LatLon(it.lat, it.lon) }
        assertEquals(listOf(ROUTE, ROUTE), a.serverRouteIds("rt-1"))
        for (rig in listOf(a, b)) assertEquals(listOf(rp, rp), rig.set().routes.map { r -> r.points.single { it.id == "pt-5" }.let { LatLon(it.lat, it.lon) } })

        // The repair: the route deleted (the Routes panel's delete takes every route with its id), then drawn again.
        a.routes.edit("Delete route") { it.without(ROUTE) }
        a.advance(STILL); a.advance(POLL); a.advance(POLL)
        assertEquals(emptyList<String>(), a.serverRouteIds("rt-1"))
        assertEquals(emptyList<SketchRoute>(), b.set().routes)
        a.routes.edit("Draw route") { it.plus(red1()) }
        a.advance(STILL); a.advance(POLL); a.advance(POLL)
        assertEquals(listOf(ROUTE), a.serverRouteIds("rt-1"))
        assertEquals(listOf(ROUTE), b.set().routes.map { it.id })
    }
}
