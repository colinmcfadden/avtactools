package app.ezpztac.data

import app.cash.turbine.test
import app.ezpztac.model.PointSets
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncStatus
import app.ezpztac.testing.Fixtures
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved sets of local points as a person uses them: imported from an AMPS file, kept offline, synced, and opened as the web would open them. */
class PointSetRepositoryTest {
    private class Rig {
        val server = FakeServer()
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val repository = PointSetRepository(device.repository, device.store as InMemorySyncStore, scheduler)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private val lps = Fixtures.bytes("sqlite/local-points.lps")

    private suspend fun Rig.imported(name: String = "NORTH GA.LPS") = (repository.import(lps, name) as ImportOutcome.Imported).set

    // -- Importing ---------------------------------------------------------------------------------------------

    @Test
    fun `an lps file becomes a set named for the file, queued for the server at once`() = rig {
        val set = imported()
        assertEquals("NORTH GA", set.name)
        assertEquals(9, set.points.size)                                                      // every point the reader found, the blank-named and the odd ones too
        val entry = device.outbox().single()
        assertEquals(Operation.CREATE, entry.operation)
        assertEquals(set.id, entry.uuid)
        assertEquals(RecordKind.POINT_SET, entry.kind)
        assertEquals(1, scheduler.requested)
    }

    @Test
    fun `each point gets an id of its own in the shape the web makes`() = rig {
        val ids = imported().points.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for ((i, id) in ids.withIndex()) assertTrue(id, id.matches(Regex("lps-$i-[0-9a-f]{6}")))
    }

    @Test
    fun `it reaches the server as the list of points the web saves, with the elevations and groups the file had`() = rig {
        val set = imported()
        device.sync()
        val body = server.live(RecordKind.POINT_SET).single()
        assertEquals("NORTH GA", body.name)
        val points = body.data.getValue("points").jsonArray.map { it.jsonObject }
        assertEquals(set.points.map { it.name }, points.map { (it["name"] as JsonPrimitive).content })
        assertEquals(setOf("id", "name", "description", "group", "icon", "elevationFt", "lat", "lon"), points.first().keys)
        assertEquals(SyncStatus.SYNCED, device.record(RecordKind.POINT_SET, set.id)!!.status)
    }

    @Test
    fun `a file that is not local points is refused in the web's words, and nothing is saved`() = rig {
        val junk = repository.import("not a database".toByteArray(), "junk.lps")
        assertEquals(ImportOutcome.Refused("This doesn't look like an .LPS local points file."), junk)
        val none = repository.import(Fixtures.bytes("sqlite/local-points-unreadable.lps"), "none.lps")
        assertEquals(ImportOutcome.Refused("This .LPS file contains no readable points."), none)
        val notLps = repository.import(Fixtures.bytes("sqlite/deep-tree.db"), "other.db")
        assertEquals(ImportOutcome.Refused("No Points table found — is this an .LPS local points file?"), notLps)
        assertTrue(device.outbox().isEmpty())
        assertEquals(0, scheduler.requested)
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test
    fun `a file with no name in its name is called something, because the server refuses a set with none`() = rig {
        assertEquals(PointSetRepository.DEFAULT_NAME, imported(".lps").name)
        assertEquals(PointSetRepository.DEFAULT_NAME, imported("   .LPS").name)
        assertEquals("A B", imported("  A B  .lps").name)
    }

    // -- Opening, renaming, deleting ------------------------------------------------------------------------

    @Test
    fun `a set that is opened has the points it was saved with`() = rig {
        val set = imported()
        val opened = repository.open(set.id)!!
        assertEquals(set, opened)
        assertNull(repository.open("not-here"))
    }

    @Test
    fun `renaming changes the name and nothing else, and a blank name is refused`() = rig {
        val set = imported()
        val before = scheduler.requested
        assertTrue(repository.rename(set.id, "  NIGHT  "))
        val opened = repository.open(set.id)!!
        assertEquals("NIGHT", opened.name)
        assertEquals(set.points, opened.points)
        assertEquals(before + 1, scheduler.requested)

        assertFalse(repository.rename(set.id, "   "))                                         // the server would refuse it, and the outbox would hold it for good
        assertEquals("NIGHT", repository.open(set.id)!!.name)
        assertFalse(repository.rename("not-here", "X"))
        assertEquals(before + 1, scheduler.requested)
    }

    @Test
    fun `delete removes the set and queues the deletion`() = rig {
        val set = imported()
        device.sync()
        val before = scheduler.requested
        repository.delete(set.id)
        assertEquals(before + 1, scheduler.requested)
        assertNull(repository.open(set.id))
        device.sync()
        assertTrue(server.live(RecordKind.POINT_SET).isEmpty())
    }

    // -- What the web saved -------------------------------------------------------------------------------------

    @Test
    fun `a set the web saved opens, and what a newer release added to a point is still there after an edit here`() = rig {
        val webPoints = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "lps-0-ab12cd"); put("name", "BLUE 1"); put("description", ""); put("group", "LZ"); put("icon", ""); put("elevationFt", 1730.0)
                    put("lat", 34.5); put("lon", -84.2); put("shinyNewField", "keep me")
                },
            ),
        )
        val record = server.createElsewhere(RecordKind.POINT_SET, "web-1", "FROM WEB", JsonObject(mapOf("points" to webPoints)))
        device.sync()
        val opened = repository.open("web-1")!!
        assertEquals("BLUE 1", opened.points.single().name)
        assertEquals(record.serverId, opened.savedId)

        repository.rename("web-1", "RENAMED HERE")
        val written = device.record(RecordKind.POINT_SET, "web-1")!!.data.getValue("points").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("keep me"), written["shinyNewField"])
        assertEquals(PointSets.serialize(opened), device.record(RecordKind.POINT_SET, "web-1")!!.data)
    }

    // -- The list ------------------------------------------------------------------------------------------------

    @Test
    fun `the list shows each set with its points counted, and follows every change`() = rig {
        repository.observe().test {
            assertEquals(emptyList<PointSetRow>(), awaitItem())
            val made = imported()
            assertEquals(PointSetRow(made.id, "NORTH GA", 9, SyncStatus.PENDING, null), awaitItem().single())
            repository.rename(made.id, "NIGHT")
            assertEquals("NIGHT", awaitItem().single().name)
            repository.delete(made.id)
            assertEquals(emptyList<PointSetRow>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an entry this version cannot read still counts, and is not lost when the set is renamed`() = rig {
        val broken = buildJsonObject { put("id", "x"); put("name", "no position") }
        val fine = buildJsonObject { put("id", "y"); put("name", "ok"); put("lat", 1.0); put("lon", 2.0) }
        server.createElsewhere(RecordKind.POINT_SET, "w", "ODD", JsonObject(mapOf("points" to JsonArray(listOf(broken, fine)))))
        device.sync()
        assertEquals(2, repository.observe().first().single().pointCount)
        repository.rename("w", "STILL ODD")
        assertEquals(JsonArray(listOf(broken, fine)), device.record(RecordKind.POINT_SET, "w")!!.data["points"])
    }

    @Test
    fun `a conflict copy is in the list, marked, beside the set it is a copy of`() = rig {
        val set = imported()
        val original = device.record(RecordKind.POINT_SET, set.id)!!                          // read before the transaction: its lock is not re-entrant
        device.store.transaction { put(original.copy(uuid = "copy-1", conflictOf = set.id, name = "NORTH GA (from this device, 14:32)")) }
        val rows = repository.observe().first()
        assertEquals(2, rows.size)
        assertEquals(SyncStatus.CONFLICT, rows.single { it.uuid == "copy-1" }.sync)
        assertEquals(set.id, rows.single { it.uuid == "copy-1" }.conflictOf)
        assertNull(rows.single { it.uuid == set.id }.conflictOf)
    }

    // -- Reading a big set again and again -------------------------------------------------------------------

    @Test
    fun `a set is not read again when only another record changed, but is when its own document does`() = rig {
        val set = imported()
        repository.observeSets().test {
            val first = awaitItem().single().set
            imported("OTHER.lps")                                                             // a change to the list, not to this set
            val second = awaitItem().single { it.set.id == set.id }.set
            assertSame(first.points, second.points)                                           // the same list, not read again

            repository.rename(set.id, "RENAMED")                                              // a rename leaves the document alone
            val third = awaitItem().single { it.set.id == set.id }.set
            assertEquals("RENAMED", third.name)
            assertSame(first.points, third.points)

            cancelAndIgnoreRemainingEvents()
        }
        // a change to the document itself is read afresh
        val moved = PointSets.serialize(set.copy(points = set.points.map { it.copy(lat = it.lat + 1) }))
        device.repository.edit(RecordKind.POINT_SET, set.id, data = moved)
        val now = repository.observeSets().first().single { it.set.id == set.id }.set
        assertNotSame(set.points, now.points)
        assertEquals(set.points.first().lat + 1, now.points.first().lat, 1e-9)
    }
}
