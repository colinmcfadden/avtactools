package app.ezpztac.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A document only ever comes down (docs/DOCUMENTS.md §6): the server makes it from a file, and a device that changes one does it online and holds the answer
 * ([SyncRepository.take]), never through the outbox. So the scenarios every kind runs, which write records on a device, leave it out, and these say what is true of it.
 */
internal class DocumentSyncTest {
    private val kind = RecordKind.DOCUMENT

    private class Rig {
        val server = FakeServer()
        val a = Device("A", server)
        val b = Device("B", server)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private fun files(pdf: String) = JsonObject(
        mapOf(
            "version" to JsonPrimitive(1),
            "pdf" to JsonObject(mapOf("sha256" to JsonPrimitive(pdf), "bytes" to JsonPrimitive(909), "pages" to JsonPrimitive(2))),
            "added_at" to JsonPrimitive("2026-11-22T12:00:00"),
        ),
    )

    @Test
    fun `a document uploaded anywhere comes to every device as the server holds it, with nothing to send`() = rig {
        server.createElsewhere(kind, "doc-1", "OPORD 24-03", files("aa"))
        a.sync(); b.sync()
        for (device in listOf(a, b)) {
            val held = device.repository.record(kind, "doc-1")!!
            assertEquals("OPORD 24-03", held.name)
            assertEquals(files("aa"), held.data)
            assertEquals(1, held.baseRevision)
            assertFalse(held.dirty)
            assertEquals(0, device.repository.pending())
        }
    }

    @Test
    fun `a rename, a new version and a deletion made elsewhere follow`() = rig {
        server.createElsewhere(kind, "doc-1", "OPORD", files("aa"))
        a.sync()
        server.editElsewhere(kind, "doc-1", name = "OPORD (FINAL)", data = files("bb"))
        a.sync()
        assertEquals("OPORD (FINAL)", a.repository.record(kind, "doc-1")!!.name)
        assertEquals(files("bb"), a.repository.record(kind, "doc-1")!!.data)
        server.deleteElsewhere(kind, "doc-1")
        a.sync()
        assertNull(a.repository.record(kind, "doc-1"))
    }

    @Test
    fun `a device never queues one, and a sync sends nothing for it`() = rig {
        server.createElsewhere(kind, "doc-1", "OPORD", files("aa"))
        a.sync()
        assertThrows<IllegalArgumentException> { a.repository.create(kind, "MINE", files("cc")) }
        assertThrows<IllegalArgumentException> { a.repository.edit(kind, "doc-1", name = "RENAMED") }
        assertThrows<IllegalArgumentException> { a.repository.delete(kind, "doc-1") }
        assertEquals(0, a.repository.pending())
        server.log.clear()
        a.sync()
        assertTrue(server.log.all { it.startsWith("changes") }, "only the feed was asked: ${server.log}")
    }

    @Test
    fun `an answer is held as the next pull would bring it, and a pull after it changes nothing`() = rig {
        a.repository.take(kind, "doc-1", serverId = 7, revision = 1, name = "OPORD", data = files("aa"))
        val held = a.repository.record(kind, "doc-1")!!
        assertEquals(7, held.serverId)
        assertFalse(held.dirty)
        // A rename's answer has no document: the one held stays.
        a.repository.take(kind, "doc-1", serverId = 7, revision = 2, name = "OPORD (FINAL)", data = null)
        assertEquals("OPORD (FINAL)", a.repository.record(kind, "doc-1")!!.name)
        assertEquals(files("aa"), a.repository.record(kind, "doc-1")!!.data)
        assertEquals(0, a.repository.pending())
    }

    @Test
    fun `an answer older than what a pull brought is not taken, and one with nothing to hold waits for the pull`() = rig {
        server.createElsewhere(kind, "doc-1", "OPORD", files("aa"))
        server.editElsewhere(kind, "doc-1", name = "OPORD (FINAL)")
        a.sync()                                                                  // revision 2 is here first
        a.repository.take(kind, "doc-1", serverId = 1, revision = 1, name = "OPORD", data = files("zz"))
        assertEquals("OPORD (FINAL)", a.repository.record(kind, "doc-1")!!.name)
        assertEquals(files("aa"), a.repository.record(kind, "doc-1")!!.data)

        a.repository.take(kind, "doc-2", serverId = 9, revision = 1, name = "NEW", data = null)
        assertNull(a.repository.record(kind, "doc-2"), "a record is never held without its document")
    }

    @Test
    fun `a deletion made here is let go of at once, and its tombstone then changes nothing`() = rig {
        server.createElsewhere(kind, "doc-1", "OPORD", files("aa"))
        a.sync()
        server.deleteElsewhere(kind, "doc-1")                                     // what the deletion route did
        a.repository.forget(kind, "doc-1")
        assertNull(a.repository.record(kind, "doc-1"))
        a.sync()
        assertNull(a.repository.record(kind, "doc-1"))
        assertEquals(0, a.repository.pending())
    }

    @Test
    fun `take and forget are for documents alone, and every other kind keeps to the outbox`() = rig {
        assertThrows<IllegalArgumentException> { a.repository.take(RecordKind.LZ, "x", 1, 1, "X", files("aa")) }
        assertThrows<IllegalArgumentException> { a.repository.forget(RecordKind.LZ, "x") }
    }
}
