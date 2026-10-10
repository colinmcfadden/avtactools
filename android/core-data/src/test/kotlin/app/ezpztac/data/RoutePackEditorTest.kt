package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.assertJson
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.data.PackEditorRig.Companion.packTest
import app.ezpztac.formats.MsnxReader
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackRoutes
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.MissionRoutes
import app.ezpztac.sync.RecordKind
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * A mission pack's route set edited in the route session every route tool uses (usePackRoutes on the web): its routes' changes go as
 * operations at the route or point, the routes this person hid stay hidden and are never sent, and closing the set lets go of nothing the library
 * holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePackEditorTest {
    // A regression that loops without end fails here rather than holding the build: runTest's own timeout cannot fire on a busy thread. A loop
    // of sends or edits is cut short sooner by the rig ([PackEditorRig.MAX_BATCHES], [PackEditorRig.MAX_WRITES]).
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        const val SET = "rt-1"
        val LOCAL_SET: String = PackRef.localId(PACK, SET)

        // RED 1 as the pack has it: today's shape, as anything a client in step with the web sends.
        val INGRESS: JsonObject = PackRoutes.routeShape(
            json(
                """{"version": 1, "routes": [{"id": "r-1", "name": "RED 1", "color": "#0A84FF",
                    "points": [
                      {"id": "p-1", "lat": 34.5, "lon": -84.1, "kind": "amps", "ptType": "start", "name": ".SP", "role": "start", "ele": null},
                      {"id": "p-2", "lat": 34.6, "lon": -84.2, "kind": "amps", "ptType": "ip", "name": ".RP", "role": "waypoint", "ele": null}]}]}""",
            ),
        )
    }

    private suspend fun PackEditorRig.openSet() = opened(SET, create(SET, "route", "OP DK INGRESS", INGRESS))

    private fun PackEditorRig.set(): RouteSet = checkNotNull(routes.active.value)

    private fun PackEditorRig.serverRoute(): JsonObject = serverData(SET).getValue("routes").jsonArray.single().jsonObject

    @Test
    fun `a set opens with its routes and their ids, and opening it sends nothing`() = packTest { r ->
        r.openSet()
        assertEquals(LOCAL_SET, r.set().id)
        assertEquals(listOf(Triple("r-1", "RED 1", true)), r.set().routes.map { Triple(it.id, it.name, it.visible) })
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    @Test
    fun `a moved point goes as one change at that point, and a point added as an insert after its neighbour`() = packTest { r ->
        r.openSet()
        r.routes.edit("Move point") { set -> set.mapRoute("r-1") { route -> route.copy(points = route.points.map { if (it.id == "p-2") it.copy(lat = 34.61, lon = -84.21) else it }) } }
        r.advance(STILL)
        val moved = r.sent().single().single()
        assertEquals("patch", moved.getValue("type").jsonPrimitive.content)
        assertJson(json("""{"x": ["routes", {"id": "r-1"}, "points", {"id": "p-2"}]}""").getValue("x"), moved["path"], "where the move went")
        assertEquals("Sam B. moved .RP on RED 1.", moved.getValue("summary").jsonPrimitive.content)

        val added = RoutePoint(id = "p-3", lat = 34.55, lon = -84.15, kind = RoutePoint.KIND_SHAPING)
        r.routes.edit("Add a shaping point") { set ->
            set.mapRoute("r-1") { route -> route.copy(points = listOf(route.points[0], added, route.points[1])) }
        }
        r.advance(STILL)
        val inserted = r.sent()[1].single()
        assertEquals(listOf("insert", "p-1"), listOf("type", "after").map { inserted.getValue(it).jsonPrimitive.content })
        assertEquals("Sam B. added a point to RED 1.", inserted.getValue("summary").jsonPrimitive.content)
        assertEquals(listOf("p-1", "p-3", "p-2"), r.serverRoute().getValue("points").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(34.61, r.serverRoute().getValue("points").jsonArray[2].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a route hidden here stays hidden and to this person, through someone else's change and the next opening`() = packTest { r ->
        r.openSet()
        r.routes.edit("Show or hide route") { set -> set.mapRoute("r-1") { it.copy(visible = false) } }
        r.advance(STILL)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertJson(json("""{"hidden": ["r-1"]}"""), r.packStore.own(PACK, SET), "what the device keeps")
        assertFalse("visible" in r.serverRoute())

        r.theirs(json("""{"type": "patch", "item": "rt-1", "path": ["routes", {"id": "r-1"}], "value": {"name": "RED 9"}}"""))
        assertEquals(listOf("RED 9" to false), r.set().routes.map { it.name to it.visible })
        r.advance(STILL)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())

        r.routes.close()
        r.openItem(SET)
        assertEquals(listOf("RED 9" to false), r.set().routes.map { it.name to it.visible })
    }

    @Test
    fun `someone else's new point is taken in, and nothing is sent back`() = packTest { r ->
        r.openSet()
        r.theirs(
            json(
                """{"type": "insert", "item": "rt-1", "path": ["routes", {"id": "r-1"}, "points"], "after": "p-1",
                    "value": {"id": "p-9", "lat": 34.55, "lon": -84.15, "kind": "shaping"}}""",
            ),
        )
        assertEquals(listOf("p-1", "p-9", "p-2"), r.set().routes.single().points.map { it.id })
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    /** The pack's store as the session sees it, noting each document it is told is closed. */
    private class Releases(private val inner: DocumentStore<RouteSet>) : DocumentStore<RouteSet> by inner {
        val released = mutableListOf<String>()

        override fun release(uuid: String) {
            released += uuid
            inner.release(uuid)
        }
    }

    @Test
    fun `closing a pack's set lets go of nothing the library holds, so an open mission keeps its file`() = packTest { r ->
        r.pack(PACK, "OP DK", create(SET, "route", "OP DK INGRESS", INGRESS))
        r.start()
        r.openPack()
        val releases = Releases(r.routeItems)
        val routes = RouteSession(r.routeRepository, r.scope, packs = releases)
        // A mission of the person's own, opened (its file held, so a save that changes nothing in it writes none).
        val bytes = Fixtures.bytes("msnx/template.msnx")
        var n = 0
        val read = MissionRoutes.toMissionSketchRoutes(MsnxReader.read(bytes), emptyList()) { "mission-${n++}" }
        val mission = r.routeRepository.createMission(RouteSet(id = "mission-1", name = "TEMPLATE", routes = read), "template.msnx", bytes)
        val held = checkNotNull(r.routeRepository.open(mission.id))
        val file = r.library.record(RecordKind.MISSION, mission.id)!!.file

        assertTrue(routes.open(LOCAL_SET))
        routes.close()
        assertEquals(listOf(LOCAL_SET), releases.released)

        r.routeRepository.save(held)
        assertEquals("the file was not written again, so it was still held", file, r.library.record(RecordKind.MISSION, mission.id)!!.file)
    }

    @Test
    fun `a route drawn into the set goes as an insert after the last, without what is the person's own`() = packTest { r ->
        r.openSet()
        val drawn = app.ezpztac.model.SketchRoute(
            id = "sketch-1-abc", name = "RED 2", color = "#32D74B",
            points = listOf(
                RoutePoint(id = "a", lat = 34.7, lon = -84.3, kind = RoutePoint.KIND_AMPS, ptType = "start", name = ".SP"),
                RoutePoint(id = "b", lat = 34.8, lon = -84.4, kind = RoutePoint.KIND_AMPS, ptType = "ip", name = ".RP"),
            ),
        )
        r.routes.edit("Draw a route") { it.plus(drawn) }
        r.advance(STILL)
        val op = r.sent().single().single()
        assertEquals(listOf("insert", "rt-1", "r-1"), listOf("type", "item", "after").map { op.getValue(it).jsonPrimitive.content })
        assertEquals("Sam B. added the route RED 2 to OP DK INGRESS.", op.getValue("summary").jsonPrimitive.content)
        val value = op.getValue("value").jsonObject
        assertFalse("visible" in value)
        assertFalse("setId" in value)
        assertEquals(listOf("r-1", "sketch-1-abc"), r.serverData(SET).getValue("routes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }
}
