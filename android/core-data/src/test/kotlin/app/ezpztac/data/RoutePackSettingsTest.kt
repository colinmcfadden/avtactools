package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.missionpacks.PackDiff
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.DragTarget
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.RouteSets
import app.ezpztac.model.Units
import app.ezpztac.network.PointWindDto
import app.ezpztac.network.WindQuestion
import app.ezpztac.planning.Designation
import app.ezpztac.planning.DraftPoint
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.planning.PointSpec
import app.ezpztac.planning.RouteColors
import app.ezpztac.planning.SketchOps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Every setting of a route in a Mission Pack's set of routes, made on one device as the app makes it (the Routes panel and the route's plan, the map's drag and
 * long press, a sketch finished, winds and ground elevations fetched) and found, identical, on the other device's open set and in what the pack holds; on the
 * other device's copy while it has the set closed, and once it opens it; undone, as a change of its own; and made by two people at once, where two settings of
 * one point both stand and one setting made twice stands as the later made it, whole. The owner's rule (2026-10-13): everything syncs on change but which
 * routes each person has hidden. Colin's device is A, Sam's is B, over one pack server and one clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePackSettingsTest {
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(300, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        const val POLL = 4_000L
        const val ITEM = "rt-1"
        const val ROUTE = "sketch-1760000000000-ab12cd34"
        const val BLUE = "sketch-1760000000900-cd34ef56"
        val CROSSHAIR = LatLon(34.5433, -84.1312)
        const val GRID = "16S GC 20000 30000"

        // A plan as the web's defaultRoutePlan writes it.
        const val PLAN = """{"aircraft": "UH-60L Black Hawk", "aircraftProfile": "uh60l", "airspeed": {"value": 100, "type": "ground"},
            "altitude": {"value": 50, "ref": "agl"}, "wind": {"dirTrue": 0, "speedKts": 0}, "tempC": 15, "fuelFlowLbHr": 960, "date": "2026-10-13",
            "perPoint": {"pt-4": {"altitude": {"value": 300, "ref": "msl"}, "wind": {"dirTrue": 270, "speedKts": 15}}, "pt-5": {"clock": "12:30:00"}}}"""

        /** ROUTES 1: RED 1 as the web writes it into a pack, with its ground fetched and a plan of its own, and BLUE 1, which Sam has hidden. */
        val SET: JsonObject = json(
            """{"version": 1, "routes": [
                  {"id": "$ROUTE", "name": "RED 1", "color": "#FF453A", "plan": $PLAN,
                   "elevations": {"pt-1": 1000, "pt-2": 1010, "pt-4": 1030, "pt-5": 1040},
                   "points": [
                     {"id": "pt-1", "lat": 34.5, "lon": -84.1, "ele": null, "role": "start", "kind": "amps", "ptType": "target", "name": ".SP"},
                     {"id": "pt-2", "lat": 34.52, "lon": -84.12, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "turn", "name": ".CP1"},
                     {"id": "pt-3", "lat": 34.54, "lon": -84.14, "ele": null, "role": "waypoint", "kind": "shaping", "ptType": null, "name": ""},
                     {"id": "pt-4", "lat": 34.56, "lon": -84.16, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "turn", "name": ".CP2"},
                     {"id": "pt-5", "lat": 34.6, "lon": -84.2, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "ip", "name": ".RP"}]},
                  {"id": "$BLUE", "name": "BLUE 1", "color": "#0A84FF", "plan": $PLAN, "elevations": {},
                   "points": [
                     {"id": "q-1", "lat": 34.7, "lon": -84.3, "ele": null, "role": "start", "kind": "amps", "ptType": "target", "name": ".SP"},
                     {"id": "q-2", "lat": 34.8, "lon": -84.4, "ele": null, "role": "waypoint", "kind": "amps", "ptType": "ip", "name": ".RP"}]}
               ]}""",
        )
    }

    // -- What a setting is ---------------------------------------------------------------------------------------------------------------------------

    /** A set as the pack holds it, or a device's set as its item's data would be: its name and its data. */
    private class Item(val name: String, val data: JsonObject)

    private class Setting(val name: String, val read: (Item) -> JsonElement?, val undos: Int = 1, val change: suspend PackEditorRig.() -> Unit) {
        override fun toString() = name
    }

    private fun routeOf(item: Item, id: String = ROUTE): JsonObject? =
        item.data["routes"]?.jsonArray?.firstOrNull { (it as? JsonObject)?.get("id") == JsonPrimitive(id) } as? JsonObject

    private fun route(id: String = ROUTE): (Item) -> JsonElement? = { routeOf(it, id) ?: JsonNull }

    private fun routeFields(vararg names: String, id: String = ROUTE): (Item) -> JsonElement? = { item ->
        routeOf(item, id)?.let { r -> JsonObject(names.associateWith { r[it] ?: JsonNull }) } ?: JsonNull
    }

    private fun points(): (Item) -> JsonElement? = { routeOf(it)?.get("points") ?: JsonNull }

    private fun point(id: String, vararg names: String): (Item) -> JsonElement? = { item ->
        val p = routeOf(item)?.get("points")?.jsonArray?.firstOrNull { (it as? JsonObject)?.get("id") == JsonPrimitive(id) } as? JsonObject
        p?.let { o -> JsonObject(names.associateWith { o[it] ?: JsonNull }) } ?: JsonNull
    }

    private fun plan(vararg names: String): (Item) -> JsonElement? = { item ->
        val p = routeOf(item)?.get("plan") as? JsonObject
        JsonObject(names.associateWith { p?.get(it) ?: JsonNull })
    }

    // A point's own values, as the planner reads them: an entry the pack left null is none.
    private fun perPoint(id: String, vararg names: String): (Item) -> JsonElement? = { item ->
        val entry = ((routeOf(item)?.get("plan") as? JsonObject)?.get("perPoint") as? JsonObject)?.get(id) as? JsonObject
        JsonObject(names.associateWith { entry?.get(it) ?: JsonNull })
    }

    // Which point holds the clock, and what it says: the one anchor, whatever entries a pack left null.
    private val clocks: (Item) -> JsonElement? = { item ->
        val perPoint = (routeOf(item)?.get("plan") as? JsonObject)?.get("perPoint") as? JsonObject
        JsonObject(perPoint.orEmpty().mapNotNull { (id, entry) -> ((entry as? JsonObject)?.get("clock") as? JsonPrimitive)?.takeIf { it.isString }?.let { id to it } }.toMap())
    }

    private fun ground(): (Item) -> JsonElement? = { item ->
        JsonObject(((routeOf(item)?.get("elevations") as? JsonObject).orEmpty()).filterValues { it !is JsonNull })
    }

    // -- How the app makes each, on a device -------------------------------------------------------------------------------------------------------------

    private fun PackEditorRig.set(): RouteSet = checkNotNull(routes.active.value)

    private fun PackEditorRig.red() = checkNotNull(set().route(ROUTE))

    /** RoutesViewModel.applyPlan: what was typed into the whole route's form, checked against what the form showed, as one step. */
    private fun PackEditorRig.typePlan(typed: (PlanDraft) -> PlanDraft) {
        val before = PlanDraft.of(red().plan)
        val checked = typed(before).check(before) as PlanDraft.Checked.Valid
        routes.edit("Change plan") { s -> s.mapRoute(ROUTE) { SketchOps.withPlan(it, checked.patch) } }
    }

    /** RoutesViewModel.applyPoint: what was typed for a point, checked against what its card showed, as one step. */
    private fun PackEditorRig.typePoint(pointId: String, typed: (PointDraft) -> PointDraft) {
        val before = PointDraft.of(red().plan, pointId)
        val first = red().points.firstOrNull { it.kind == RoutePoint.KIND_AMPS }?.id == pointId
        val checked = typed(before).check(before, first) as PointDraft.Checked.Valid
        routes.edit("Change point") { s ->
            s.mapRoute(ROUTE) { r ->
                var next = r
                val patch = checked.patch
                if (patch.altitude != null || patch.airspeed != null || patch.wind != null) next = SketchOps.withOverride(next, pointId, patch)
                if (checked.clock != null) next = SketchOps.withClock(next, pointId, checked.clock)
                next
            }
        }
    }

    private fun PackEditorRig.movePoint(pointId: String, to: (LatLon) -> LatLon) = routes.edit("Move point") { it.withPointMoved(ROUTE, pointId, to) }

    private fun PackEditorRig.drag(pointId: String, northM: Double, eastM: Double) {
        val drag = MapDrag(diagrams, routes, ThreatStore(NoThreatVault, scope, dispatcher) { 1_000L })
        val from = red().points.single { it.id == pointId }.let { LatLon(it.lat, it.lon) }
        assertTrue("picked up", drag.start(DragTarget.RoutePoint(ROUTE, pointId), from))
        for (i in 1..5) {
            drag.move(GraphicEdits.offset(from, northM * i / 6, eastM * i / 6))
            advance(16)
        }
        drag.end(GraphicEdits.offset(from, northM, eastM))
    }

    private object NoThreatVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) = Unit
        override fun wipe() = Unit
    }

    /** The planning service with answers of [winds] and [feet] (one for each named point asked about). */
    private fun PackEditorRig.planning(feet: List<Double?> = emptyList(), winds: Map<String, PointWindDto> = emptyMap()) = RoutePlanning(
        object : PlanningApi {
            override suspend fun elevations(points: List<LatLon>): List<Double?> = feet
            override suspend fun routeWinds(points: List<WindQuestion>): Map<String, PointWindDto> = winds
        },
        routes,
    )

    private fun wind(dir: Int, speed: Double, temp: Double? = null) = PointWindDto(dir, speed, variable = false, tempC = temp, station = "KXXX", source = "METAR", distanceMiles = 3.0)

    private val SETTINGS: List<Setting> = listOf(
        Setting("a route drawn: a sketch finished, its colour the first the set is not using", route("sketch-1760000000800-ef56ab12")) {
            var n = 0
            val drawn = checkNotNull(
                SketchOps.build(
                    listOf(DraftPoint(34.45, -84.05, null), DraftPoint(34.46, -84.06, Designation("turn", ".CP")), DraftPoint(34.47, -84.07, null)),
                    "WHITE 1", "sketch-1760000000800-ef56ab12", RouteColors.next(set().routes.map { it.color }), RoutePlan.forAircraft(),
                ) { "w-${++n}" },
            )
            routes.edit("Draw route") { it.plus(drawn) }
        },
        Setting("a named point added at the end, from the map's long press", points()) {
            routes.edit("Add point") { s -> s.mapRoute(ROUTE) { SketchOps.appendAmps(it, 34.62, -84.22, name = ".CP", ptType = "turn", newPointId = { "pt-6" }) } }
        },
        Setting("a shaping point put on the line at the crosshair", points()) {
            routes.edit("Add shaping point") { s -> s.mapRoute(ROUTE) { SketchOps.insertShaping(it, CROSSHAIR.lat, CROSSHAIR.lon) { "pt-7" } } }
        },
        Setting("a point renamed", point("pt-2", "name")) { routes.edit("Rename point") { s -> s.mapRoute(ROUTE) { SketchOps.rename(it, "pt-2", ".HAWK") } } },
        Setting("a point renamed onto a local point, its place and charted elevation with it", point("pt-2", "name", "lat", "lon", "chartElevationFt")) {
            routes.edit("Rename point") { s -> s.mapRoute(ROUTE) { SketchOps.rename(it, "pt-2", "ALPHA", snapTo = 34.7023 to -84.2011, chartElevationFt = 1730.0) } }
        },
        Setting("a shaping point made a named one", point("pt-3", "kind", "ptType", "name", "role")) {
            routes.edit("Make route point") { s -> s.mapRoute(ROUTE) { SketchOps.designate(it, "pt-3", PointSpec(RoutePoint.KIND_AMPS, ptType = "turn")) } }
        },
        Setting("a named point's kind changed", point("pt-2", "kind", "ptType", "name", "role")) {
            routes.edit("Change point type") { s -> s.mapRoute(ROUTE) { SketchOps.designate(it, "pt-2", PointSpec(RoutePoint.KIND_AMPS, ptType = "ip")) } }
        },
        Setting("a named point made to only shape the line", point("pt-4", "kind", "ptType", "name", "role")) {
            routes.edit("Make shaping point") { s -> s.mapRoute(ROUTE) { SketchOps.designate(it, "pt-4", PointSpec(RoutePoint.KIND_SHAPING)) } }
        },
        Setting("a point nudged north", point("pt-2", "lat", "lon")) { movePoint("pt-2") { GraphicEdits.offset(it, 200.0 / Units.METERS_TO_FEET, 0.0) } },
        Setting("a point put at the crosshair", point("pt-2", "lat", "lon")) { movePoint("pt-2") { CROSSHAIR } },
        Setting("a point put at a typed grid", point("pt-2", "lat", "lon")) { movePoint("pt-2") { (PlaceSearch.resolve(GRID) as PlaceResult.Found).at } },
        Setting("a point dragged", point("pt-2", "lat", "lon")) { drag("pt-2", northM = 60.0, eastM = -40.0) },
        Setting("the route's date", plan("date")) { typePlan { it.copy(date = "2026-10-20") } },
        Setting("the route's temperature", plan("tempC")) { typePlan { it.copy(tempC = "5") } },
        Setting("the route's fuel flow", plan("fuelFlowLbHr")) { typePlan { it.copy(fuelFlowLbHr = "1100") } },
        Setting("a point's altitude", perPoint("pt-2", "altitude")) { typePoint("pt-2") { it.copy(altitudeValue = "500") } },
        Setting("a point's altitude reference", perPoint("pt-4", "altitude")) { typePoint("pt-4") { it.copy(altitudeRef = "agl") } },
        Setting("a point's speed and its reference", perPoint("pt-2", "airspeed")) { typePoint("pt-2") { it.copy(speedValue = "80", speedType = "indicated") } },
        Setting("a point's wind", perPoint("pt-2", "wind")) { typePoint("pt-2") { it.copy(windDir = "180", windSpeed = "12") } },
        Setting("the clock set at a point", clocks) { typePoint("pt-2") { it.copy(clock = "11:45:00") } },
        Setting("the clock moved to another point", clocks) { typePoint("pt-1") { it.copy(clock = "12:05:00") } },
        Setting("the clock cleared", clocks) { typePoint("pt-5") { it.copy(clock = "") } },
        Setting("winds fetched", perPoint("pt-2", "wind"), undos = 0) {
            val outcome = planning(winds = mapOf("pt-1" to wind(90, 8.0, 12.0), "pt-2" to wind(100, 9.0), "pt-4" to wind(110, 10.0), "pt-5" to wind(120, 11.0)))
                .fetchWinds(set().id, ROUTE)
            assertTrue("$outcome", outcome is PlanningOutcome.Done)
        },
        Setting("ground elevations fetched", ground(), undos = 0) {
            val outcome = planning(feet = listOf(1100.0, 1110.0, 1130.0, 1140.0)).fetchElevations(set().id, ROUTE)
            assertTrue("$outcome", outcome is PlanningOutcome.Done)
        },
        Setting("a route's name", routeFields("name")) { routes.edit("Rename route") { s -> s.mapRoute(ROUTE) { it.copy(name = "RED 2") } } },
        Setting("a route deleted", route()) { routes.edit("Delete route") { it.without(ROUTE) } },
        Setting("the set's name", { JsonPrimitive(it.name) }) { routes.edit("Rename") { it.copy(name = "ROUTES 9") } },
    )

    // -- Two devices --------------------------------------------------------------------------------------------------------------------------------------

    private fun PackEditorRig.item(): Item = Item(set().name, stripOwn(RouteSets.serialize(set())))

    // A set's data without each person's own fields of its routes (whether one is shown), as its item holds it.
    private fun stripOwn(data: JsonObject): JsonObject =
        JsonObject(data + ("routes" to JsonArray(data.getValue("routes").jsonArray.map { r -> (r as? JsonObject)?.let { JsonObject(it - "visible") } ?: r })))

    private fun PackEditorRig.serverItem(): Item = Item(checkNotNull(server.name(PACK, ITEM)), serverData(ITEM))

    private fun PackEditorRig.copyItem(): Item = checkNotNull(engine.open.value?.item(ITEM)) { "${me.name}'s copy has no $ITEM" }.let { Item(it.name, it.data.jsonObject) }

    private fun assertSameValue(setting: Setting, where: String, expected: JsonElement?, actual: JsonElement?) =
        assertTrue("$setting, $where: expected $expected, was $actual", PackDiff.sameData(expected, actual))

    private fun eachOnTwoDevices(settings: List<Setting> = SETTINGS, open: Boolean = true, body: suspend TestScope.(Setting, PackEditorRig, PackEditorRig) -> Unit) = runTest {
        for (setting in settings) {
            val b = PackEditorRig(this)
            val a = b.other(COLIN)
            try {
                b.pack(PACK, "OP DK", create(ITEM, "route", "ROUTES 1", SET))
                a.start(); a.openPack(); a.openItem(ITEM)
                b.start(); b.openPack()
                if (open) {
                    b.openItem(ITEM)
                    // Sam hides BLUE 1: his own, never sent.
                    b.routes.edit("Show or hide route") { s -> s.mapRoute(BLUE) { it.copy(visible = false) } }
                    b.advance(STILL)
                }
                body(setting, a, b)
            } finally {
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun `every setting made on one device is on the other device's open set and in the pack, identical, and the routes it hid stay hidden`() =
        eachOnTwoDevices { setting, a, b ->
            val before = setting.read(a.serverItem())
            setting.change(a)
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL)
            val now = setting.read(a.serverItem())
            assertFalse("$setting changed nothing in the pack", PackDiff.sameData(before, now))
            assertSameValue(setting, "Colin's device", now, setting.read(a.item()))
            assertSameValue(setting, "Sam's device", now, setting.read(b.item()))
            assertEquals("$setting: BLUE 1 is hidden for Sam alone", false, b.set().route(BLUE)?.visible)
            assertEquals("$setting: and shown for Colin", true, a.set().route(BLUE)?.visible)
            assertEquals("$setting: Sam sent nothing", emptyList<List<JsonObject>>(), b.sent())
            assertTrue("$setting: the whole set", PackDiff.sameData(a.item().data, b.item().data))
        }

    @Test
    fun `every setting made on one device reaches the other device's copy while it has the set closed, and opens there identical`() =
        eachOnTwoDevices(open = false) { setting, a, b ->
            setting.change(a)
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL)
            val now = setting.read(a.serverItem())
            assertSameValue(setting, "Sam's copy", now, setting.read(b.copyItem()))
            b.openItem(ITEM)
            assertSameValue(setting, "Sam's device, opened after", now, setting.read(b.item()))
            b.advance(STILL); b.advance(POLL)
            assertEquals("$setting: opening it sent nothing", emptyList<List<JsonObject>>(), b.sent())
        }

    @Test
    fun `undoing a setting is a change too, and the other device shows it undone`() =
        eachOnTwoDevices(SETTINGS.filter { it.undos > 0 }) { setting, a, b ->
            val before = setting.read(a.serverItem())
            setting.change(a)
            a.advance(STILL)
            b.advance(POLL)
            repeat(setting.undos) { assertNotNull("$setting: something to undo", a.routes.undo()) }
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL)
            assertSameValue(setting, "undone in the pack", before, setting.read(a.serverItem()))
            assertSameValue(setting, "undone on Colin's device", before, setting.read(a.item()))
            assertSameValue(setting, "undone on Sam's device", before, setting.read(b.item()))
        }

    @Test
    fun `winds and ground fetched for a set that is not open by the time they answer reach the pack and the other device all the same`() =
        eachOnTwoDevices(SETTINGS.filter { it.name == "winds fetched" || it.name == "ground elevations fetched" }) { setting, a, b ->
            // Another set is opened on Colin's device while the service answers: what comes back lands in the set it was asked for, closed or not.
            a.server.elsewhere(PACK, COLIN.id, create("rt-2", "route", "ROUTES 2", json("""{"version": 1, "routes": []}""")))
            a.advance(POLL)
            val asked = a.set().id
            val held = CompletableDeferred<Unit>()
            val api = object : PlanningApi {
                override suspend fun elevations(points: List<LatLon>): List<Double?> { held.await(); return listOf(1100.0, 1110.0, 1130.0, 1140.0) }
                override suspend fun routeWinds(points: List<WindQuestion>): Map<String, PointWindDto> { held.await(); return mapOf("pt-2" to wind(100, 9.0)) }
            }
            val planning = RoutePlanning(api, a.routes)
            val fetch = async {
                if (setting.name == "winds fetched") planning.fetchWinds(asked, ROUTE) else planning.fetchElevations(asked, ROUTE)
            }
            a.advance(1)
            a.openItem("rt-2")
            held.complete(Unit)
            assertTrue("${fetch.await()}", fetch.await() is PlanningOutcome.Done)
            a.advance(STILL); b.advance(POLL); b.advance(POLL)
            val now = setting.read(a.serverItem())
            assertSameValue(setting, "Sam's device", now, setting.read(b.item()))
            assertFalse("$setting reached the pack", PackDiff.sameData(setting.read(Item("ROUTES 1", SET)), now))
        }

    // -- Both at once -------------------------------------------------------------------------------------------------------------------------------------

    private fun atOnce(first: Setting, second: Setting, both: Boolean) = eachOnTwoDevices(listOf(first)) { _, a, b ->
        first.change(a)
        a.advance(STILL)
        second.change(b)
        val sams = second.read(b.item())
        b.advance(STILL)
        a.advance(POLL); a.advance(POLL); b.advance(POLL)
        val colins = first.read(a.serverItem())
        for ((who, item) in listOf("the pack" to a.serverItem(), "Colin's device" to a.item(), "Sam's device" to b.item())) {
            assertSameValue(second, "$who, beside $first", sams, second.read(item))
            if (both) assertSameValue(first, "$who, beside $second", colins, first.read(item))
        }
        if (both) assertFalse("$first did not stand", PackDiff.sameData(first.read(Item("ROUTES 1", SET)), colins))
        assertTrue("the whole set is the same on both devices", PackDiff.sameData(a.item().data, b.item().data))
    }

    private fun setting(name: String): Setting = SETTINGS.single { it.name == name }

    @Test
    fun `two settings of one point or route made at once both stand`() {
        for ((first, second) in listOf(
            "a point nudged north" to "a point renamed",
            "a point's altitude" to "a point's wind",
            "the route's temperature" to "the route's fuel flow",
            "a route's name" to "a point dragged",
            "a point's speed and its reference" to "a named point's kind changed",
        )) atOnce(setting(first), setting(second), both = true)
    }

    @Test
    fun `one setting made on two devices at once stands as the later made it, whole`() {
        atOnce(setting("the route's temperature"), Setting("the route's temperature typed elsewhere", plan("tempC")) { typePlan { it.copy(tempC = "-3") } }, both = false)
        // A point's altitude is its number and what it is measured from, together: one person's 500 AGL here, the other's MSL there, never 500 MSL.
        atOnce(
            Setting("a point's altitude, 500", perPoint("pt-4", "altitude")) { typePoint("pt-4") { it.copy(altitudeValue = "500") } },
            Setting("the same point's altitude, measured from the ground", perPoint("pt-4", "altitude")) { typePoint("pt-4") { it.copy(altitudeRef = "agl") } },
            both = false,
        )
        // A wind is a direction and a speed, together: never one person's direction with the other's speed.
        atOnce(
            Setting("a point's wind direction", perPoint("pt-4", "wind")) { typePoint("pt-4") { it.copy(windDir = "200") } },
            Setting("the same point's wind speed", perPoint("pt-4", "wind")) { typePoint("pt-4") { it.copy(windSpeed = "25") } },
            both = false,
        )
        // And a speed is its number and what kind of speed it is.
        atOnce(
            Setting("a point's speed", perPoint("pt-2", "airspeed")) { typePoint("pt-2") { it.copy(speedValue = "120") } },
            Setting("the same point's kind of speed", perPoint("pt-2", "airspeed")) { typePoint("pt-2") { it.copy(speedType = "true") } },
            both = false,
        )
        atOnce(setting("a point nudged north"), setting("a point put at the crosshair"), both = false)
    }

    @Test
    fun `a route drawn on one device is the other's to plan, its points, colour and plan as they were made`() = runTest {
        val b = PackEditorRig(this)
        val a = b.other(COLIN)
        try {
            b.pack(PACK, "OP DK")
            a.start(); a.openPack()
            b.start(); b.openPack()
            val local = checkNotNull(a.workspace.createRouteSet(null))
            val item = checkNotNull(PackRef.parse(local)).item
            var n = 0
            val drawn = checkNotNull(
                SketchOps.build(
                    listOf(DraftPoint(34.45, -84.05, null), DraftPoint(34.46, -84.06, null), DraftPoint(34.47, -84.07, null)),
                    "ROUTE 1", "sketch-1760000000800-ef56ab12", RouteColors.next(emptyList()), RoutePlan.forAircraft(),
                ) { "w-${++n}" },
            )
            a.routes.edit("Draw route") { it.plus(drawn) }
            a.advance(STILL); b.advance(POLL)
            b.openItem(item)
            assertTrue(PackDiff.sameData(a.item().data, b.item().data))
            assertEquals(listOf("sketch-1760000000800-ef56ab12"), b.set().routes.map { it.id })
            assertEquals(RouteColors.next(emptyList()), b.set().routes.single().color)
        } finally {
            a.close()
            b.close()
        }
    }
}
