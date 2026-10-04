package app.ezpztac.sync

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a mission's file adds to sync, beyond the scenarios every kind runs: the file is sent as it was when the attempt was made, a lost answer repeats the same file,
 * a replaced file does not stay on the device, a file is fetched only when it is wanted, and a failed download never leaves a record holding the wrong file.
 */
internal class MissionSyncTest {
    private val kind = RecordKind.MISSION

    private class Rig {
        val server = FakeServer()
        val store = InMemorySyncStore()
        val a = Device("A", server, store = store)
        val b = Device("B", server)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private suspend fun Device.blobExists(id: String) = store.transaction { blob(id) } != null

    @Test
    fun `a mission goes up with its file and the server holds it under the name it was sent as`() = rig {
        a.make(kind, "GOAT", "one")
        a.sync()
        val held = server.live(kind).single()
        assertEquals("msnx:one", held.file!!.toString(Charsets.UTF_8))
        assertEquals("one.msnx", held.fileName)
    }

    @Test
    fun `a create whose answer was lost is repeated with the same file and makes one record`() = rig {
        a.make(kind, "GOAT", "one")
        server.loseAnswers = 1
        a.sync()
        a.sync()
        assertEquals(1, server.live(kind).size)
        assertEquals("msnx:one", server.live(kind).single().file!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `an edit made while an earlier send is unanswered does not change what is repeated, and goes on top afterwards`() = rig {
        val made = a.make(kind, "GOAT", "one")
        a.sync()
        a.change(kind, made.uuid, tag = "two")
        server.loseAnswers = 1
        a.sync()                                                              // applied by the server, answer lost
        a.change(kind, made.uuid, tag = "three")                              // changed again while that is in doubt
        a.sync()
        assertEquals("msnx:three", server.live(kind).single().file!!.toString(Charsets.UTF_8))
        assertEquals(1, server.live(kind).size)
        assertTrue(a.outbox().isEmpty())
        // The server's file is the last one, not the one that was in doubt.
        assertEquals("three", a.fileTag(kind, made.uuid))
    }

    @Test
    fun `a file nothing refers to any more is let go once the change is sent`() = rig {
        val made = a.make(kind, "GOAT", "one")
        val first = a.record(kind, made.uuid)!!.file!!
        a.sync()
        a.change(kind, made.uuid, tag = "two")
        a.sync()
        assertFalse(a.blobExists(first.id))
        assertEquals(1, store.blobCount)
    }

    @Test
    fun `deleting a mission lets its file go`() = rig {
        val made = a.make(kind, "GOAT", "one")
        a.sync()
        a.repository.delete(kind, made.uuid)
        a.sync()
        assertEquals(0, store.blobCount)
    }

    @Test
    fun `a mission this device already has at that revision is not downloaded again`() = rig {
        a.make(kind, "GOAT", "one")
        a.sync()
        b.sync()
        assertEquals(1, server.downloads)
        b.sync()
        a.sync()                                                              // A pulls its own record back: the same revision, so no file
        assertEquals(1, server.downloads)
    }

    @Test
    fun `a record with changes here is not overwritten by a pull, and its newer file is not fetched`() = rig {
        val made = a.make(kind, "GOAT", "one")
        a.sync(); b.sync()
        val before = server.downloads
        b.change(kind, made.uuid, tag = "mine")
        server.editElsewhere(kind, made.uuid, data = content(kind, "theirs"), file = "msnx:theirs".toByteArray())
        // The server refuses B's push for good, so the pull runs with the record still carrying B's edit: it is left alone, and the other file is not fetched for it.
        server.failName = "GOAT" to ApiException(400, "refused", "refused")
        b.sync()
        assertEquals(before, server.downloads)
        assertEquals("mine", b.fileTag(kind, made.uuid))
    }

    @Test
    fun `a conflict brings the server's file onto the record and keeps mine on the copy`() = rig {
        val made = a.make(kind, "GOAT", "start")
        a.sync(); b.sync()
        a.change(kind, made.uuid, tag = "from A"); a.sync()
        b.change(kind, made.uuid, tag = "from B")
        val report = b.sync()
        val copy = report.conflicts.single().copy
        assertEquals("from A", b.fileTag(kind, made.uuid))
        assertEquals("from B", b.fileTag(kind, copy))
        assertNotNull(b.record(kind, copy)!!.file)
        a.sync()
        assertEquals("from B", a.fileTag(kind, copy))                          // the copy's file reached the server and the other device
    }

    @Test
    fun `a download that fails offline stops the pull with the cursor where it was, and the next sync tries again`() = rig {
        a.make(kind, "GOAT", "one")
        a.sync()
        // A server that answers the feed and then drops the connection on the file.
        val flaky = object : SyncApi by server {
            var fail = true
            override suspend fun fetchFile(kind: RecordKind, serverId: Int): ByteArray {
                if (fail) throw NetworkException("dropped", null, requestMayHaveBeenSent = true)
                return server.fetchFile(kind, serverId)
            }
        }
        val c = Device("C", flaky)
        assertEquals(StopReason.Offline, c.sync().stopped)
        assertNull(c.repository.records(kind).firstOrNull(), "the record is not held without its file")
        flaky.fail = false
        assertNull(c.sync().stopped)
        assertEquals("one", c.fileTag(kind, c.repository.records(kind).single().uuid))
    }

    @Test
    fun `a mission that is gone from the server by the time its file is asked for is skipped, and the pull goes on`() = rig {
        a.make(kind, "GOAT", "one")
        a.make(RecordKind.LZ, "HAWK", "x")
        a.sync()
        val gone = object : SyncApi by server {
            override suspend fun fetchFile(kind: RecordKind, serverId: Int): ByteArray = throw ApiException(404, null, "Not found")
        }
        val c = Device("C", gone)
        assertNull(c.sync().stopped)
        assertTrue(c.repository.records(kind).isEmpty())
        assertEquals(listOf("HAWK"), c.names(RecordKind.LZ))
    }

    @Test
    fun `the server's file for a mission with none sent is a refusal that waits for the user, not a loop`() = rig {
        // A mission queued with no file cannot be made through the repository's helpers; this is the engine's own guard against a record whose file went missing.
        val made = a.repository.create(kind, "GOAT", content(kind, "x"), file = FilePart("x.msnx", "msnx:x".toByteArray()))
        a.store.transaction {
            val record = record(kind, made.uuid)!!
            put(record.copy(file = FileRef("0".repeat(64), "x.msnx")))              // a file the store does not hold
        }
        val report = a.sync()
        assertEquals(1, report.blocked.size)
        assertTrue(report.blocked.single().lastError!!.contains("not on the device"))
    }
}
