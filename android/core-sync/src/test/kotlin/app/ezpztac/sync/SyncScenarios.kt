package app.ezpztac.sync

import app.ezpztac.network.SyncChange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A server (the fake, or the real one) and the devices that sync with it. */
internal interface Env : AutoCloseable {
    fun device(label: String): Device

    /** Everything the server holds, deletions included, as the change feed shows it. */
    suspend fun serverView(): List<SyncChange>
}

/** What a record of each kind carries, and how to read a tag back out of it. */
internal fun content(kind: RecordKind, tag: String): JsonObject = when (kind) {
    RecordKind.LZ -> doc("schema" to 2, "note" to tag)
    RecordKind.AIRCRAFT -> doc("designation" to tag, "rotor_diameter_m" to 14.0)
}

internal fun tagOf(kind: RecordKind, record: LocalRecord): String? =
    (record.data[if (kind == RecordKind.LZ) "note" else "designation"] as? JsonPrimitive)?.contentOrNull

internal suspend fun Env.live(kind: RecordKind): List<SyncChange> =
    serverView().filter { it.type == (if (kind == RecordKind.LZ) "lz" else "aircraft") && !it.deleted }

/**
 * The rules of sync, as scenarios between devices. They run against the fake server (fast, and with failures on demand) and against
 * the real one, so the fake cannot drift from what the server does. Each runs for both kinds of record that sync.
 */
internal abstract class SyncScenarios {
    abstract fun env(): Env

    private fun each(block: suspend (Env, RecordKind) -> Unit) = runBlocking<Unit> {
        for (kind in RecordKind.entries) env().use { block(it, kind) }
    }

    @Test
    fun `a record made here reaches the server, and another device`() = each { env, kind ->
        val a = env.device("A")
        val b = env.device("B")
        val made = a.repository.create(kind, "HAWK", content(kind, "one"))
        assertEquals(SyncStatus.PENDING, made.status)

        val report = a.sync()
        assertEquals(1, report.pushed)
        assertNull(report.stopped)
        assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)
        assertEquals(listOf("HAWK"), env.live(kind).map { it.name })

        b.sync()
        val there = b.record(kind, made.uuid)!!                                   // the same identity on both devices
        assertEquals("HAWK", there.name)
        assertEquals("one", tagOf(kind, there))
        assertEquals(SyncStatus.SYNCED, there.status)
        assertEquals(a.record(kind, made.uuid)!!.serverId, there.serverId)
        assertTrue(a.outbox().isEmpty() && b.outbox().isEmpty())
    }

    @Test
    fun `an edit travels, and so does a deletion`() = each { env, kind ->
        val a = env.device("A")
        val b = env.device("B")
        val made = a.repository.create(kind, "HAWK", content(kind, "one"))
        a.sync(); b.sync()

        a.repository.edit(kind, made.uuid, name = "HAWK 2", data = content(kind, "two"))
        a.sync(); b.sync()
        assertEquals("HAWK 2", b.record(kind, made.uuid)!!.name)
        assertEquals("two", tagOf(kind, b.record(kind, made.uuid)!!))
        assertEquals(2, b.record(kind, made.uuid)!!.baseRevision)

        a.repository.delete(kind, made.uuid)
        assertNull(a.record(kind, made.uuid))                                     // gone from lists at once
        a.sync(); b.sync()
        assertNull(b.record(kind, made.uuid))
        assertTrue(env.live(kind).isEmpty())
        assertTrue(a.store.transaction { record(kind, made.uuid) } == null)       // and gone from the store, once the server agreed
    }

    @Test
    fun `edits made before a sync go as one write`() = each { env, kind ->
        val a = env.device("A")
        val made = a.repository.create(kind, "HAWK", content(kind, "one"))
        a.sync()
        repeat(3) { a.repository.edit(kind, made.uuid, data = content(kind, "edit $it")) }
        assertEquals(1, a.outbox().size)
        a.sync()
        assertEquals(2, env.live(kind).single().revision)                         // one bump, not three
        assertEquals("edit 2", (env.live(kind).single().data as JsonObject).let { (it["note"] ?: it["designation"]) as JsonPrimitive }.content)
    }

    @Test
    fun `a record made and deleted before any sync is never sent`() = each { env, kind ->
        val a = env.device("A")
        val made = a.repository.create(kind, "SCRATCH", content(kind, "x"))
        a.repository.delete(kind, made.uuid)
        assertTrue(a.outbox().isEmpty())
        val report = a.sync()
        assertEquals(0, report.pushed)
        assertTrue(env.serverView().isEmpty())
    }

    @Test
    fun `a sync with nothing to do does nothing`() = each { env, kind ->
        val a = env.device("A")
        a.repository.create(kind, "HAWK", content(kind, "one"))
        a.sync()
        val again = a.sync()
        assertEquals(0, again.pushed)
        assertTrue(again.conflicts.isEmpty() && again.blocked.isEmpty())
        assertNull(again.stopped)
    }

    // -- Two devices, one record ----------------------------------------------------------

    /** Both devices have the record at revision 1; A has changed it and synced; B changed it too, offline. */
    private suspend fun diverged(env: Env, kind: RecordKind): Triple<Device, Device, String> {
        val a = env.device("A")
        val b = env.device("B")
        val made = a.repository.create(kind, "HAWK", content(kind, "start"))
        a.sync(); b.sync()
        a.repository.edit(kind, made.uuid, name = "HAWK (A)", data = content(kind, "from A"))
        a.sync()
        b.repository.edit(kind, made.uuid, name = "HAWK (B)", data = content(kind, "from B"))
        return Triple(a, b, made.uuid)
    }

    @Test
    fun `two devices that change the same record keep both, and nothing is overwritten`() = each { env, kind ->
        val (a, b, uuid) = diverged(env, kind)
        val report = b.sync()

        val conflict = report.conflicts.single()
        assertEquals(uuid, conflict.original)
        val original = b.record(kind, uuid)!!
        val copy = b.record(kind, conflict.copy)!!
        // The server's copy is the record, as everyone else has it; what B changed is kept beside it.
        assertEquals("HAWK (A)", original.name)
        assertEquals("from A", tagOf(kind, original))
        assertEquals("HAWK (B) (from B, 14:32)", copy.name)
        assertEquals("from B", tagOf(kind, copy))
        assertEquals(SyncStatus.CONFLICT, copy.status)
        assertEquals(uuid, copy.conflictOf)

        // The copy is a record of its own on the server too, so the work is not only on B's phone.
        assertEquals(setOf("HAWK (A)", "HAWK (B) (from B, 14:32)"), env.live(kind).map { it.name }.toSet())
        a.sync()
        assertEquals(setOf("HAWK (A)", "HAWK (B) (from B, 14:32)"), a.names(kind).toSet())
    }

    @Test
    fun `keep mine puts my version on the record and drops the copy`() = each { env, kind ->
        val (a, b, uuid) = diverged(env, kind)
        val copy = b.sync().conflicts.single().copy
        b.engine.resolve(kind, copy, SyncEngine.Resolution.KEEP_MINE)
        b.sync(); a.sync()

        assertEquals(listOf("HAWK (A)"), env.live(kind).map { it.name })          // one record
        assertEquals("from B", tagOf(kind, a.record(kind, uuid)!!))                // with B's content
        assertEquals(1, a.names(kind).size)
        assertEquals(1, b.names(kind).size)
        assertNull(b.record(kind, copy))
    }

    @Test
    fun `keep theirs drops my version`() = each { env, kind ->
        val (a, b, uuid) = diverged(env, kind)
        val copy = b.sync().conflicts.single().copy
        b.engine.resolve(kind, copy, SyncEngine.Resolution.KEEP_THEIRS)
        b.sync(); a.sync()

        assertEquals(listOf("HAWK (A)"), env.live(kind).map { it.name })
        assertEquals("from A", tagOf(kind, b.record(kind, uuid)!!))
        assertEquals(1, a.names(kind).size)
        assertEquals(1, b.names(kind).size)
    }

    @Test
    fun `keep both leaves two ordinary records`() = each { env, kind ->
        val (a, b, uuid) = diverged(env, kind)
        val copy = b.sync().conflicts.single().copy
        b.engine.resolve(kind, copy, SyncEngine.Resolution.KEEP_BOTH)
        b.sync(); a.sync()

        assertEquals(2, env.live(kind).size)
        assertEquals(SyncStatus.SYNCED, b.record(kind, copy)!!.status)
        assertNull(b.record(kind, copy)!!.conflictOf)
        assertEquals(2, a.names(kind).size)
        assertEquals("from A", tagOf(kind, a.record(kind, uuid)!!))
    }

    @Test
    fun `deleted here and edited there, the edit wins`() = each { env, kind ->
        val a = env.device("A")
        val b = env.device("B")
        val made = a.repository.create(kind, "HAWK", content(kind, "start"))
        a.sync(); b.sync()
        a.repository.edit(kind, made.uuid, name = "HAWK edited", data = content(kind, "edited"))
        a.sync()
        b.repository.delete(kind, made.uuid)                                       // B did not know

        val report = b.sync()
        assertEquals(listOf(made.uuid), report.restored)
        val back = b.record(kind, made.uuid)!!
        assertEquals("HAWK edited", back.name)
        assertEquals("edited", tagOf(kind, back))
        assertEquals(SyncStatus.SYNCED, back.status)
        assertEquals(1, env.live(kind).size)
    }

    @Test
    fun `edited here and deleted there, the work is kept as a new record`() = each { env, kind ->
        val a = env.device("A")
        val b = env.device("B")
        val made = a.repository.create(kind, "HAWK", content(kind, "start"))
        a.sync(); b.sync()
        a.repository.delete(kind, made.uuid)
        a.sync()
        b.repository.edit(kind, made.uuid, name = "HAWK mine", data = content(kind, "mine"))

        val report = b.sync()
        val fresh = report.recreated.getValue(made.uuid)
        assertNotEquals(made.uuid, fresh)
        assertNull(b.record(kind, made.uuid))
        assertEquals("HAWK mine", b.record(kind, fresh)!!.name)
        assertEquals(SyncStatus.SYNCED, b.record(kind, fresh)!!.status)
        assertEquals(listOf("HAWK mine"), env.live(kind).map { it.name })
        a.sync()
        assertEquals(listOf("HAWK mine"), a.names(kind))
    }

    @Test
    fun `a field a newer web release adds to a document survives a round trip through this app`() = runBlocking<Unit> {
        env().use { env ->
            val a = env.device("A")
            val b = env.device("B")
            val future = JsonObject(content(RecordKind.LZ, "x") + mapOf("fieldFromANewerRelease" to JsonObject(mapOf("nested" to JsonPrimitive(1)))))
            val made = a.repository.create(RecordKind.LZ, "HAWK", future)
            a.sync(); b.sync()
            assertEquals(future, b.record(RecordKind.LZ, made.uuid)!!.data)
            b.repository.edit(RecordKind.LZ, made.uuid, name = "renamed")             // an edit that does not touch the document
            b.sync(); a.sync()
            assertEquals(future, a.record(RecordKind.LZ, made.uuid)!!.data)
            assertNotNull(env.live(RecordKind.LZ).single().data)
        }
    }

    @Test
    fun `a device that has never synced pulls everything, and then only what is new`() = runBlocking<Unit> {
        env().use { env ->
            val a = env.device("A")
            repeat(3) { a.repository.create(RecordKind.LZ, "LZ $it", content(RecordKind.LZ, "$it")) }
            a.repository.create(RecordKind.AIRCRAFT, "My Hawk", content(RecordKind.AIRCRAFT, "MH-60"))
            a.sync()

            val fresh = env.device("fresh")
            assertEquals(4, fresh.sync().pulled)
            assertEquals(listOf("LZ 0", "LZ 1", "LZ 2"), fresh.names(RecordKind.LZ))
            assertEquals(listOf("My Hawk"), fresh.names(RecordKind.AIRCRAFT))
            assertEquals(0, fresh.sync().pulled)                                       // nothing new
            a.repository.create(RecordKind.LZ, "LZ 3", content(RecordKind.LZ, "3")); a.sync()
            assertEquals(1, fresh.sync().pulled)
            assertFalse(fresh.names(RecordKind.LZ).isEmpty())
        }
    }
}
