package app.ezpztac.data

import app.ezpztac.network.PackItemDto
import app.ezpztac.network.PackItemReply
import app.ezpztac.network.PackSource
import app.ezpztac.network.Saved
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.StopReason
import app.ezpztac.sync.SyncRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copying a Library record into a pack: its last change written and uploaded first, then asked for by its own identity. */
class LibraryToPackTest {
    private val store = InMemorySyncStore()
    private val records = SyncRepository(store)
    private val log = mutableListOf<String>()
    private var writes: (RecordKind, String) -> String = { _, uuid -> uuid }
    private var uploads: suspend (Int) -> Uploaded = { Uploaded(ran = true) }
    private var created = true

    private val copier = LibraryToPack(
        records,
        open = { kind, uuid -> log += "write $kind $uuid"; writes(kind, uuid) },
        upload = { user -> log += "upload as $user"; uploads(user) },
        copy = { pack, source, item, user ->
            log += "copy ${(source as PackSource.ClientUuid).kind} ${source.uuid} into $pack as $item for $user"
            Saved(PackItemReply(PackItemDto(item, source.kind, "LZ HAWK", 1, 9, createdAt = "x", updatedAt = "x"), headSeq = 9), created)
        },
    )

    private suspend fun record(kind: RecordKind = RecordKind.LZ, uuid: String = "u1", up: Boolean, conflictOf: String? = null) {
        records.create(kind, "LZ HAWK", JsonObject(emptyMap()), uuid)
        if (up) markUp(kind, uuid, conflictOf)
    }

    private suspend fun markUp(kind: RecordKind, uuid: String, conflictOf: String? = null) {
        store.transaction {
            val r = requireNotNull(record(kind, uuid))
            put(r.copy(serverId = 5, baseRevision = 1, dirty = false, conflictOf = conflictOf))
        }
    }

    @Test
    fun `a record the server has is copied by its own identity, the open one written first and nothing uploaded`() = runBlocking {
        record(up = true)
        val outcome = copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6)
        assertEquals(CopyIn.Copied("LZ HAWK", again = false), outcome)
        assertEquals(listOf("write LZ u1", "copy lz u1 into p1 as lz-a for 6"), log)
    }

    @Test
    fun `a record with changes the server has not had is uploaded first, then copied`() = runBlocking {
        record(kind = RecordKind.ROUTE, up = false)
        uploads = { markUp(RecordKind.ROUTE, "u1"); Uploaded(ran = true) }
        assertEquals(CopyIn.Copied("LZ HAWK", again = false), copier.copyIn("p1", RecordKind.ROUTE, "u1", "rt-a", 6))
        assertEquals(listOf("write ROUTE u1", "upload as 6", "copy route u1 into p1 as rt-a for 6"), log)
    }

    @Test
    fun `a record given a new identity by the upload, or by its own write, is copied under it`() = runBlocking {
        record(kind = RecordKind.POINT_SET, uuid = "u1", up = false)
        uploads = {
            // The server held u1 as deleted: the work went up again as u2.
            record(kind = RecordKind.POINT_SET, uuid = "u2", up = true)
            Uploaded(ran = true, recreated = mapOf("u1" to "u2"))
        }
        copier.copyIn("p1", RecordKind.POINT_SET, "u1", "ps-a", 6)
        assertEquals("copy pointset u2 into p1 as ps-a for 6", log.last())

        log.clear()
        record(uuid = "u3", up = true)
        writes = { _, _ -> "u3" }                                                                    // the open one was kept under a new id as it was written
        copier.copyIn("p1", RecordKind.LZ, "u0", "lz-b", 6)
        assertEquals(listOf("write LZ u0", "copy lz u3 into p1 as lz-b for 6"), log)
    }

    @Test
    fun `a change that could not be uploaded is never copied in an older version, and says why`() = runBlocking {
        record(up = false)
        uploads = { Uploaded(ran = true, stopped = StopReason.Offline) }
        assertEquals(CopyIn.NotUploaded(StopReason.Offline, ran = true), copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        uploads = { Uploaded(ran = false) }                                                           // another account's plans here
        assertEquals(CopyIn.NotUploaded(null, ran = false), copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        uploads = { Uploaded(ran = true) }                                                            // ran, and this one still did not go
        assertEquals(CopyIn.NotUploaded(null, ran = true), copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        assertTrue(log.none { it.startsWith("copy") })
    }

    @Test
    fun `a record no longer in the Library, before or after the upload, is gone`() = runBlocking {
        assertEquals(CopyIn.Gone, copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        record(up = false)
        uploads = { records.delete(RecordKind.LZ, "u1"); Uploaded(ran = true) }
        assertEquals(CopyIn.Gone, copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        assertTrue(log.none { it.startsWith("copy") })
    }

    @Test
    fun `an open one whose last change cannot be written is not copied`() = runBlocking {
        record(up = true)
        writes = { _, _ -> error("disk full") }
        assertEquals(CopyIn.Unsaved, copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        assertTrue(log.none { it.startsWith("copy") || it.startsWith("upload") })
    }

    @Test
    fun `the same copy asked again is said so, and a conflict copy the server has goes as it is`() = runBlocking {
        record(up = true, conflictOf = "u0")
        created = false
        assertEquals(CopyIn.Copied("LZ HAWK", again = true), copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        assertTrue(log.none { it.startsWith("upload") })
    }

    @Test
    fun `an imported mission cannot go in a pack`() = runBlocking {
        val refused = runCatching { copier.copyIn("p1", RecordKind.MISSION, "u1", "rt-a", 6) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a record the server holds with a change made here since is uploaded first`() = runBlocking {
        record(up = true)
        records.edit(RecordKind.LZ, "u1", name = "LZ HAWK 2")                                    // the server has the record, not this change
        uploads = { markUp(RecordKind.LZ, "u1"); Uploaded(ran = true) }
        copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6)
        assertEquals(listOf("write LZ u1", "upload as 6", "copy lz u1 into p1 as lz-a for 6"), log)
    }

    @Test
    fun `a record another device changed too is not copied, the record being that device version now`() = runBlocking {
        record(up = false)
        uploads = { markUp(RecordKind.LZ, "u1"); Uploaded(ran = true, conflicts = setOf("u1")) }
        assertEquals(CopyIn.Conflicted, copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        assertTrue(log.none { it.startsWith("copy") })
    }

    @Test
    fun `a record whose change the server refused for good says so, never to try again in a moment`() = runBlocking {
        record(up = false)
        uploads = { Uploaded(ran = true, blocked = setOf("u1")) }
        assertEquals(CopyIn.Refused, copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        uploads = { Uploaded(ran = true, blocked = setOf("other")) }
        assertEquals(CopyIn.NotUploaded(null, ran = true), copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
    }

    @Test
    fun `a record seen moving to a new uuid is found again by the uuid it was asked for`() = runBlocking {
        record(uuid = "u2", up = false)
        writes = { _, uuid -> if (uuid == "u1") "u2" else uuid }                                   // the open one was kept as u2 as it was written
        uploads = { Uploaded(ran = true, stopped = StopReason.Offline) }
        assertEquals(CopyIn.NotUploaded(StopReason.Offline, ran = true), copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6))
        assertEquals("u2", copier.currentId("u1"))
        writes = { _, uuid -> uuid }
        uploads = { markUp(RecordKind.LZ, "u2"); Uploaded(ran = true, recreated = mapOf("u2" to "u3")) }
        record(uuid = "u3", up = true)
        copier.copyIn("p1", RecordKind.LZ, "u1", "lz-a", 6)                                         // asked again by the first uuid
        assertEquals("copy lz u3 into p1 as lz-a for 6", log.last())
        assertEquals("u3", copier.currentId("u1"))
        assertEquals("u9", copier.currentId("u9"))
    }

    @Test
    fun `the copy is made for the account asked for, never under another's session`() = runBlocking {
        val session = app.ezpztac.network.InMemorySessionStore(
            app.ezpztac.network.StoredSession(
                "access", "refresh", 4_000_000_000,
                app.ezpztac.network.ApiUser(id = 6, email = "colin@example.com", name = "Colin", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = true),
            ),
        )
        val api = app.ezpztac.network.ApiClient("http://127.0.0.1:9/", okhttp3.OkHttpClient(), app.ezpztac.network.ClientInfo.android("1.4.0", 212), session)
        api.restore()
        val refused = runCatching { copyCall(api)("p1", PackSource.ClientUuid("lz", "u1"), "lz-a", 7) }.exceptionOrNull()
        assertTrue("$refused", refused is app.ezpztac.network.OtherAccountException)
    }

    @Test
    fun `an original is made ready for an update as a copy would be, and one not on this device is left to the server`() = runBlocking {
        record(up = true)
        assertNull(copier.readyOriginal(RecordKind.LZ, "u1", 6))
        assertTrue(log.none { it.startsWith("upload") || it.startsWith("copy") })
        records.edit(RecordKind.LZ, "u1", name = "LZ HAWK 2")
        uploads = { Uploaded(ran = true, stopped = StopReason.Offline) }
        assertEquals(CopyIn.NotUploaded(StopReason.Offline, ran = true), copier.readyOriginal(RecordKind.LZ, "u1", 6))
        uploads = { markUp(RecordKind.LZ, "u1"); Uploaded(ran = true, conflicts = setOf("u1")) }
        assertEquals(CopyIn.Conflicted, copier.readyOriginal(RecordKind.LZ, "u1", 6))
        assertNull(copier.readyOriginal(RecordKind.LZ, "elsewhere", 6))
        writes = { _, _ -> error("disk full") }
        assertEquals(CopyIn.Unsaved, copier.readyOriginal(RecordKind.LZ, "u1", 6))
        assertTrue(log.none { it.startsWith("copy") })
    }
}
