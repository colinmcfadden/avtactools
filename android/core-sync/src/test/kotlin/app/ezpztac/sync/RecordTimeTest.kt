package app.ezpztac.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a record last changed ([LocalRecord.updatedAt], what the Library sorts by): this device's clock for a change made here, the server's time for
 * one a pull brings, and never a time made up for a record nobody noted.
 */
internal class RecordTimeTest {
    private val kind = RecordKind.ROUTE

    private class Rig {
        val server = FakeServer()
        var now = "2026-10-12T09:00:00Z"
        val a = Device("A", server)
        val b = Device("B", server)
        val repository = SyncRepository(a.store, SequentialIds("A-here"), clock = { now })          // ids of its own: the engine numbers the copies it makes
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    @Test
    fun `a record made or changed here notes the time it was, and a delete does not need one`() = rig {
        val made = repository.create(kind, "HAWK", content(kind, "one"))
        assertEquals("2026-10-12T09:00:00Z", made.updatedAt)
        now = "2026-10-12T09:05:00Z"
        assertEquals("2026-10-12T09:05:00Z", repository.edit(kind, made.uuid, name = "HAWK 2").updatedAt)
        assertEquals("2026-10-12T09:05:00Z", a.record(kind, made.uuid)!!.updatedAt)
    }

    @Test
    fun `a pull takes the server's time, for a record new here and for one changed there`() = rig {
        val made = b.repository.create(kind, "HAWK", content(kind, "one"))
        b.sync()
        a.sync()
        val first = server.records.single { it.uuid == made.uuid }
        assertEquals(server.updatedAt(first.seq), a.record(kind, made.uuid)!!.updatedAt)

        b.repository.edit(kind, made.uuid, data = content(kind, "two"))
        b.sync()
        a.sync()
        val second = server.records.single { it.uuid == made.uuid }
        assertEquals(server.updatedAt(second.seq), a.record(kind, made.uuid)!!.updatedAt)
    }

    @Test
    fun `a conflict gives the record the server's time and the copy kept beside it this device's`() = rig {
        val made = repository.create(kind, "HAWK", content(kind, "one"))
        a.sync()
        b.sync()
        b.repository.edit(kind, made.uuid, data = content(kind, "theirs"))
        b.sync()
        now = "2026-10-12T09:30:00Z"
        repository.edit(kind, made.uuid, data = content(kind, "mine"))
        val report = a.sync()
        val copy = report.conflicts.single().copy
        val theirs = server.records.single { it.uuid == made.uuid }
        assertEquals(server.updatedAt(theirs.seq), a.record(kind, made.uuid)!!.updatedAt)
        assertEquals("2026-10-12T09:30:00Z", a.record(kind, copy)!!.updatedAt)
    }

    @Test
    fun `keeping mine puts this device's time back with this device's work`() = rig {
        val made = repository.create(kind, "HAWK", content(kind, "one"))
        a.sync()
        b.sync()
        b.repository.edit(kind, made.uuid, data = content(kind, "theirs"))
        b.sync()
        now = "2026-10-12T09:30:00Z"
        repository.edit(kind, made.uuid, data = content(kind, "mine"))
        val copy = a.sync().conflicts.single().copy
        a.engine.resolve(kind, copy, SyncEngine.Resolution.KEEP_MINE)
        assertEquals("2026-10-12T09:30:00Z", a.record(kind, made.uuid)!!.updatedAt)
    }

    @Test
    fun `a conflict copy's name fits the server's 100 characters, cut in the name and never through a character`() = rig {
        val long = "\uD83D\uDE81" + "H".repeat(98)                                                 // a helicopter emoji (two UTF-16 units) and 98 letters: 99 characters
        val made = repository.create(kind, long, content(kind, "one"))
        a.sync()
        b.sync()
        b.repository.edit(kind, made.uuid, data = content(kind, "theirs"))
        b.sync()
        repository.edit(kind, made.uuid, data = content(kind, "mine"))
        val copy = a.record(kind, a.sync().conflicts.single().copy)!!.name
        assertEquals(MAX_NAME, copy.codePointCount(0, copy.length))
        assertTrue(copy.startsWith("\uD83D\uDE81HHH"), copy)
        assertTrue(copy.endsWith(" (from A, 14:32)"), copy)
        assertEquals("ab", cutToCharacters("abc", 2))
        assertEquals("\uD83D\uDE81", cutToCharacters("\uD83D\uDE81x", 1))                       // the emoji whole, not half of it
    }

    @Test
    fun `a record nobody noted says no time, rather than one made up`() = rig {
        a.store.transaction { put(LocalRecord(kind, "old", 3, 1, "OLD", content(kind, "one"))) }
        assertNull(a.record(kind, "old")!!.updatedAt)
    }
}
