package app.ezpztac.data

import app.cash.turbine.test
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSets
import app.ezpztac.model.SketchRoute
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved sets of routes as a person uses them: made on the device, changed, synced, and opened as the web would open them. */
class RouteRepositoryTest {
    private class Rig {
        val server = FakeServer()
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, scheduler)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private fun route(id: String = "sketch-1", name: String = "ROUTE 1") = SketchRoute(
        id = id, name = name, color = "#FF453A",
        points = listOf(
            RoutePoint(id = "p1", lat = 34.7, lon = -84.1, kind = RoutePoint.KIND_AMPS, ptType = "target", name = ".TGT"),
            RoutePoint(id = "p2", lat = 34.8, lon = -84.0, kind = RoutePoint.KIND_AMPS, ptType = "target", name = ".TGT"),
        ),
        plan = RoutePlan(),
    )

    @Test
    fun `a new set is queued for the server at once, under the identity it is known by`() = rig {
        val made = repository.create("MISSION 1", listOf(route()))
        assertEquals("MISSION 1", made.name)
        val entry = device.outbox().single()
        assertEquals(Operation.CREATE, entry.operation)
        assertEquals(made.id, entry.uuid)
        assertEquals(RecordKind.ROUTE, device.record(RecordKind.ROUTE, made.id)!!.kind)
        assertEquals(1, scheduler.requested)
    }

    @Test
    fun `a set made here reaches the server as the document the web saves`() = rig {
        val made = repository.create("MISSION 1", listOf(route()))
        device.sync()
        val body = server.live(RecordKind.ROUTE).single().data
        assertEquals(JsonPrimitive(1), body["version"])
        val routes = body.getValue("routes").jsonArray
        assertEquals(1, routes.size)
        assertEquals(JsonPrimitive("sketch-1"), routes.single().jsonObject["id"])
        assertEquals(made.id, device.record(RecordKind.ROUTE, made.id)!!.uuid)
        assertEquals(SyncStatus.SYNCED, device.record(RecordKind.ROUTE, made.id)!!.status)
    }

    @Test
    fun `a set that is opened has the routes it was saved with`() = rig {
        val made = repository.create("MISSION 1", listOf(route("a", "ONE"), route("b", "TWO")))
        val opened = repository.open(made.id)!!
        assertEquals(listOf("ONE", "TWO"), opened.routes.map { it.name })
        assertEquals(made.id, opened.id)
        assertEquals("MISSION 1", opened.name)
        assertNull(repository.open("not-here"))
    }

    @Test
    fun `saving writes the set's changes and asks for a sync`() = rig {
        val made = repository.create("MISSION 1", listOf(route()))
        val before = scheduler.requested
        repository.save(made.copy(name = "MISSION 2", routes = made.routes + route("b", "TWO")))
        val opened = repository.open(made.id)!!
        assertEquals("MISSION 2", opened.name)
        assertEquals(2, opened.routes.size)
        assertEquals(before + 1, scheduler.requested)
    }

    @Test
    fun `renaming changes the name and nothing else`() = rig {
        val made = repository.create("MISSION 1", listOf(route()))
        repository.rename(made.id, "NIGHT")
        val opened = repository.open(made.id)!!
        assertEquals("NIGHT", opened.name)
        assertEquals(made.routes, opened.routes)
        repository.rename("not-here", "X")                                                    // a rename of nothing is nothing
    }

    @Test
    fun `a set deleted elsewhere keeps what the person did next, as a new record`() = rig {
        val made = repository.create("MISSION 1", listOf(route()))
        device.sync()
        server.deleteElsewhere(RecordKind.ROUTE, made.id)
        device.sync()
        assertNull(repository.open(made.id))
        val saved = repository.save(made.copy(name = "KEPT"))
        assertNotEquals(made.id, saved.id)
        assertNull(saved.savedId)
        assertEquals("KEPT", repository.open(saved.id)!!.name)
        assertEquals(1, saved.routes.size)
        assertNull(repository.open(made.id))                                                  // the old one stays gone
    }

    @Test
    fun `delete removes the set and queues the deletion`() = rig {
        val made = repository.create("MISSION 1", listOf(route()))
        device.sync()
        val before = scheduler.requested
        repository.delete(made.id)
        assertEquals(before + 1, scheduler.requested)
        assertNull(repository.open(made.id))
        device.sync()
        assertTrue(server.live(RecordKind.ROUTE).isEmpty())
    }

    @Test
    fun `a set the web saved opens, and what a newer release added is still there after an edit here`() = rig {
        val webRoute = buildJsonObject {
            put("id", "sketch-9"); put("name", "FROM WEB"); put("color", "#0A84FF"); put("visible", true)
            put("shinyNewField", "keep me")
            put("elevations", buildJsonObject {})
            put("plan", buildJsonObject { put("aircraftProfile", "uh60l") })
            put("points", JsonArray(listOf(
                buildJsonObject { put("id", "w1"); put("lat", 34.7); put("lon", -84.1); put("kind", "amps"); put("ptType", "target"); put("name", ".TGT") },
                buildJsonObject { put("id", "w2"); put("lat", 34.8); put("lon", -84.0); put("kind", "amps"); put("ptType", "target"); put("name", ".TGT") },
            )))
        }
        val record = server.createElsewhere(
            RecordKind.ROUTE, "web-1", "WEB SET", JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to JsonArray(listOf(webRoute)))),
        )
        device.sync()
        val opened = repository.open("web-1")!!
        assertEquals("FROM WEB", opened.routes.single().name)
        assertEquals(record.serverId, opened.savedId)

        repository.save(opened.mapRoute("sketch-9") { it.copy(name = "RENAMED HERE") })
        val written = device.record(RecordKind.ROUTE, "web-1")!!.data.getValue("routes").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("RENAMED HERE"), written["name"])
        assertEquals(JsonPrimitive("keep me"), written["shinyNewField"])
    }

    @Test
    fun `the list shows each set with its routes counted, and follows every change`() = rig {
        repository.observe().test {
            assertEquals(emptyList<RouteSetSummary>(), awaitItem())
            val made = repository.create("MISSION 1", listOf(route("a"), route("b")))
            val first = awaitItem().single()
            assertEquals(RouteSetSummary(made.id, "MISSION 1", 2, SyncStatus.PENDING, null), first)
            repository.rename(made.id, "NIGHT")
            assertEquals("NIGHT", awaitItem().single().name)
            repository.delete(made.id)
            assertEquals(emptyList<RouteSetSummary>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a route this version cannot read still counts, and is not lost when the set is saved`() = rig {
        val broken = buildJsonObject { put("id", "sketch-x"); put("name", "odd") }
        server.createElsewhere(RecordKind.ROUTE, "w", "ODD", JsonObject(mapOf("routes" to JsonArray(listOf(broken)))))
        device.sync()
        assertEquals(1, repository.observe().first().single().routeCount)
        val opened = repository.open("w")!!
        repository.save(opened.plus(route("mine")))
        val routes = device.record(RecordKind.ROUTE, "w")!!.data.getValue("routes").jsonArray
        assertEquals(listOf(JsonPrimitive("mine"), JsonPrimitive("sketch-x")), routes.map { it.jsonObject["id"] })
        assertEquals(RouteSets.serialize(opened.plus(route("mine"))).getValue("routes").jsonArray.size, routes.size)
    }

    @Test
    fun `a conflict copy is in the list, marked, beside the set it is a copy of`() = rig {
        val b = Device("B", server)
        val made = repository.create("MISSION 1", listOf(route()))
        device.sync(); b.sync()
        repository.save(repository.open(made.id)!!.copy(name = "from A"))
        device.sync()
        b.repository.edit(RecordKind.ROUTE, made.id, name = "from B")
        val conflict = b.sync().conflicts.single()
        val listing = RouteRepository(b.repository, b.store as InMemorySyncStore, RecordingScheduler())
        val summaries = listing.observe().first()
        assertEquals(2, summaries.size)
        val copy = summaries.single { it.uuid == conflict.copy }
        assertEquals(SyncStatus.CONFLICT, copy.sync)
        assertEquals(made.id, copy.conflictOf)
        assertNull(summaries.single { it.uuid == made.id }.conflictOf)
    }
}
