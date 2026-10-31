package app.ezpztac.data

import app.cash.turbine.test
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGraphics
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagrams as a person uses them: made on the device, changed, synced to the server and opened as the web would open them. */
class DiagramRepositoryTest {
    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    private class Rig {
        val server = FakeServer()
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, scheduler)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    // -- Making one -----------------------------------------------------------------------------------

    @Test
    fun `a new diagram is blank, bound to its target, and queued for the server at once`() = rig {
        val made = repository.create(target, "LZ HAWK")
        assertEquals("LZ HAWK", made.name)
        assertEquals(DiagramStatus.TARGETED, made.status)
        assertEquals(target, made.target)
        assertEquals(DiagramGraphics(), made.graphics)                                        // defaults come only after analysis
        val entry = device.outbox().single()
        assertEquals(Operation.CREATE, entry.operation)
        assertEquals(made.id, entry.uuid)                                                     // one identity for the diagram everywhere
        assertEquals(made.id, device.record(RecordKind.LZ, made.id)!!.uuid)
    }

    @Test
    fun `a diagram made here reaches the server as the web's schema 2 document`() = rig {
        val made = repository.create(target, "LZ HAWK")
        device.sync()
        val saved = server.live(RecordKind.LZ).single()
        assertEquals("LZ HAWK", saved.name)
        val document = saved.data
        assertEquals("2", (document["schemaVersion"] as JsonPrimitive).content)
        assertEquals("targeted", (document["status"] as JsonPrimitive).content)
        assertEquals(made.id, (document["id"] as JsonPrimitive).content)
        assertEquals("false", (document["dirty"] as JsonPrimitive).content)                  // saved clean
        assertEquals("16S GD 66993 52949", ((document["target"] as JsonObject)["mgrs"] as JsonPrimitive).content)
        assertTrue(document["graphics"] is JsonObject)
        // ... and the web's own reader makes the same diagram of it.
        val readBack = DiagramNormalizer.normalize(document)
        assertEquals(made.target, readBack.target)
        assertEquals(made.status, readBack.status)
    }

    @Test
    fun `a target that is not a position makes no diagram`() = rig {
        val failure = runCatching { repository.create(DiagramTarget(Double.NaN, 0.0, ""), "x") }
        assertTrue(failure.isFailure)
        assertTrue(device.outbox().isEmpty())
    }

    @Test
    fun `every change asks for a sync, so it is on its way as soon as there is signal`() = rig {
        val made = repository.create(target, "x")
        assertEquals(1, scheduler.requested)
        repository.save(repository.open(made.id)!!.copy(name = "y"))
        assertEquals(2, scheduler.requested)
        repository.rename(made.id, "z")
        assertEquals(3, scheduler.requested)
        repository.delete(made.id)
        assertEquals(4, scheduler.requested)
        repository.open(made.id)
        assertEquals(4, scheduler.requested)                                                  // looking asks for nothing
    }

    // -- Opening and changing it ---------------------------------------------------------------------

    @Test
    fun `opening gives the diagram with the record's identity and the server's id once it has one`() = rig {
        val made = repository.create(target, "LZ HAWK")
        assertEquals(made.id, repository.open(made.id)!!.id)
        assertEquals(kotlinx.serialization.json.JsonNull, repository.open(made.id)!!.savedId)
        device.sync()
        val opened = repository.open(made.id)!!
        assertNotNull(device.record(RecordKind.LZ, made.id)!!.serverId)
        assertEquals(JsonPrimitive(device.record(RecordKind.LZ, made.id)!!.serverId), opened.savedId)
    }

    @Test
    fun `opening what is not there is nothing`() = rig {
        assertNull(repository.open("never-made"))
        val made = repository.create(target, "x")
        repository.delete(made.id)
        assertNull(repository.open(made.id))
    }

    @Test
    fun `changes are saved into the same record and go as one write`() = rig {
        val made = repository.create(target, "LZ HAWK")
        device.sync()
        val opened = repository.open(made.id)!!
        val helicopter = buildJsonObject { put("id", "h1"); put("lat", 34.78); put("lon", -84.08); put("rotation", 90) }
        repository.save(opened.copy(graphics = opened.graphics.copy(helicopters = listOf(helicopter))))
        repository.save(repository.open(made.id)!!.copy(name = "LZ HAWK 2"))
        assertEquals(1, device.outbox().size)                                                 // two saves, one update
        device.sync()
        assertEquals("LZ HAWK 2", server.live(RecordKind.LZ).single().name)
        val reopened = repository.open(made.id)!!
        assertEquals(listOf<kotlinx.serialization.json.JsonElement>(helicopter), reopened.graphics.helicopters)
        assertEquals("LZ HAWK 2", reopened.name)
    }

    @Test
    fun `a field a newer release puts on a graphic survives opening and saving`() = rig {
        val made = repository.create(target, "LZ HAWK")
        val future = buildJsonObject { put("id", "h1"); put("fieldFromANewerRelease", buildJsonObject { put("nested", 1) }) }
        repository.save(repository.open(made.id)!!.let { it.copy(graphics = it.graphics.copy(helicopters = listOf(future))) })
        repository.save(repository.open(made.id)!!.copy(name = "renamed"))                    // an edit that does not touch the graphics
        assertEquals(listOf<kotlinx.serialization.json.JsonElement>(future), repository.open(made.id)!!.graphics.helicopters)
    }

    @Test
    fun `renaming changes the name on the record and in the document`() = rig {
        val made = repository.create(target, "old")
        repository.rename(made.id, "new")
        assertEquals("new", device.record(RecordKind.LZ, made.id)!!.name)
        assertEquals("new", repository.open(made.id)!!.name)
        repository.rename("not-there", "x")                                                   // nothing to rename: nothing happens
    }

    @Test
    fun `saving stamps the time, so the newest is known`() = rig {
        val made = repository.create(target, "x")
        val before = repository.open(made.id)!!.updatedAt
        Thread.sleep(5)
        repository.save(repository.open(made.id)!!)
        assertTrue(repository.open(made.id)!!.updatedAt > before)
    }

    // -- A diagram the web made ------------------------------------------------------------------------------

    @Test
    fun `a diagram the web saved opens, whatever it contained`() = rig {
        val webSaved = Json.parseToJsonElement(
            """{"schemaVersion":2,"id":"lz-web-1","name":"WEB LZ","status":"analyzed","createdAt":"2026-09-01T10:00:00.000Z","updatedAt":"2026-09-02T10:00:00.000Z",
               "target":{"lat":34.5,"lon":-84.1,"mgrs":"16S GD 63385 32037"},"mapData":{"mgrs":"16S GD 63385 32037"},"flightData":{"windSpeed":"5"},
               "graphics":{"helicopters":[{"id":"h1","lat":34.5,"lon":-84.1,"rotation":10,"profileId":"uh60l","extra":{"x":1}}],"doghouses":[],"pzMarkers":[],
               "sectorsOfFire":[],"goArounds":[],"units":[],"measurements":[],"exportBox":null},"view":{"mapStyle":"topo","showLZOutline":true,"showHeatmap":false}}""",
        ) as JsonObject
        server.createElsewhere(RecordKind.LZ, "web-uuid", "WEB LZ", webSaved)
        device.sync()
        val opened = repository.open("web-uuid")!!
        assertEquals("web-uuid", opened.id)                                                   // the record's identity, not the web's local id
        assertEquals(DiagramStatus.ANALYZED, opened.status)
        assertEquals("topo", opened.view.mapStyle)
        assertTrue(opened.canEditGraphics)
        val helicopter = opened.graphics.helicopters.single() as JsonObject
        assertEquals("uh60l", (helicopter["profileId"] as JsonPrimitive).content)
        assertNotNull(helicopter["extra"])
    }

    // -- The list ---------------------------------------------------------------------------------------------

    @Test
    fun `the list follows what is made, synced and deleted, with each one's sync status`() = rig {
        repository.observe().test {
            assertEquals(emptyList<DiagramSummary>(), awaitItem())
            val made = repository.create(target, "LZ HAWK")
            val first = awaitItem().single()
            assertEquals(made.id, first.uuid)
            assertEquals("LZ HAWK", first.name)
            assertEquals(DiagramStatus.TARGETED, first.status)
            assertEquals(target, first.target)
            assertEquals(SyncStatus.PENDING, first.sync)

            device.sync()
            assertEquals(SyncStatus.SYNCED, awaitItem().single().sync)

            repository.delete(made.id)
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a conflict copy is in the list, marked`() = rig {
        val b = Device("B", server)
        val made = repository.create(target, "LZ HAWK")
        device.sync(); b.sync()
        repository.save(repository.open(made.id)!!.copy(name = "from A"))
        device.sync()
        b.repository.edit(RecordKind.LZ, made.id, name = "from B")
        val conflict = b.sync().conflicts.single()
        val listing = DiagramRepository(b.repository, b.store as InMemorySyncStore, RecordingScheduler())
        val summaries = listing.observe().let { flow -> var out: List<DiagramSummary> = emptyList(); flow.test { out = awaitItem(); cancelAndIgnoreRemainingEvents() }; out }
        assertEquals(2, summaries.size)
        val copy = summaries.single { it.uuid == conflict.copy }
        assertEquals(SyncStatus.CONFLICT, copy.sync)
        assertEquals(made.id, copy.conflictOf)
        assertFalse(summaries.single { it.uuid == made.id }.sync == SyncStatus.CONFLICT)
    }

    @Test
    fun `a list entry for a document the app cannot make sense of still shows, with what is known`() = rig {
        device.repository.create(RecordKind.LZ, "From the future", buildJsonObject { put("schemaVersion", 99); put("status", "teleported") })
        val summary = repository.observe().let { flow -> var out: List<DiagramSummary> = emptyList(); flow.test { out = awaitItem(); cancelAndIgnoreRemainingEvents() }; out }.single()
        assertEquals("From the future", summary.name)
        assertNull(summary.target)
    }
}
