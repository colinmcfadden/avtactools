package app.ezpztac.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.ezpztac.sync.Attempt
import app.ezpztac.sync.FileHash
import app.ezpztac.sync.FileRef
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.Operation
import app.ezpztac.sync.OutboxEntry
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the engine relies on from a store, tried on the real SQLite: where the scenarios say the stores agree, these say why. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomSyncStoreTest {
    private lateinit var context: Context
    private lateinit var database: EzpzDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun open() {
        context = ApplicationProvider.getApplicationContext()
        database = inMemoryDatabase(context)
        store = RoomSyncStore(database)
    }

    @After
    fun close() = database.close()

    private fun lz(uuid: String, name: String = uuid, data: JsonObject = JsonObject(emptyMap())) = LocalRecord(
        RecordKind.LZ, uuid, serverId = null, baseRevision = null, name = name, data = data, dirty = true, localVersion = 1,
    )

    // -- Records ------------------------------------------------------------------------------

    @Test
    fun `a record comes back as it went in, in every field`() = runBlocking<Unit> {
        val record = LocalRecord(
            RecordKind.AIRCRAFT, "u-1", serverId = 7, baseRevision = 3, name = "My Hawk", data = buildJsonObject { put("a", 1) },
            dirty = true, deleted = true, localVersion = 9, conflictOf = "u-0",
        )
        store.transaction { put(record) }
        assertEquals(record, store.transaction { record(RecordKind.AIRCRAFT, "u-1") })
        assertNull(store.transaction { record(RecordKind.LZ, "u-1") })              // the kind is part of the identity
    }

    @Test
    fun `nulls stay null and are not turned into zero`() = runBlocking<Unit> {
        store.transaction { put(lz("u-1")) }
        val back = store.transaction { record(RecordKind.LZ, "u-1") }!!
        assertNull(back.serverId)
        assertNull(back.baseRevision)
        assertNull(back.conflictOf)
    }

    @Test
    fun `editing a record leaves it where it was in the list`() = runBlocking<Unit> {
        // Made in an order that is not alphabetical, so a list sorted by identity would show.
        store.transaction { put(lz("m")); put(lz("c")); put(lz("x")) }
        store.transaction { put(lz("m", name = "edited")) }                          // an edit must not send it to the end
        assertEquals(listOf("m", "c", "x"), store.transaction { records(RecordKind.LZ) }.map { it.uuid })
        assertEquals("edited", store.transaction { record(RecordKind.LZ, "m") }!!.name)
    }

    @Test
    fun `records of one kind are not records of another`() = runBlocking<Unit> {
        store.transaction { put(lz("a")); put(lz("a").copy(kind = RecordKind.AIRCRAFT, name = "plane")) }
        assertEquals(listOf("a"), store.transaction { records(RecordKind.LZ) }.map { it.name })
        assertEquals(listOf("plane"), store.transaction { records(RecordKind.AIRCRAFT) }.map { it.name })
        store.transaction { remove(RecordKind.LZ, "a") }
        assertNull(store.transaction { record(RecordKind.LZ, "a") })
        assertNotNull(store.transaction { record(RecordKind.AIRCRAFT, "a") })
    }

    @Test
    fun `a document comes back exactly, including what this app has never heard of`() = runBlocking<Unit> {
        val document = buildJsonObject {
            put("schemaVersion", 2)
            put("elevationFt", 14.0)                                                 // a whole number written as a decimal stays one
            put("big", 9007199254740993L)                                           // beyond a double's exact range
            put("emoji", "LZ 🚁 é 中")
            put("empty", "")
            put("nothing", kotlinx.serialization.json.JsonNull)
            putJsonArray("points") { add(JsonPrimitive(1)); add(JsonPrimitive("two")); add(JsonPrimitive(3.5)) }
            putJsonObject("fieldFromANewerRelease") { put("nested", true) }
        }
        store.transaction { put(lz("u-1", data = document)) }
        val back = store.transaction { record(RecordKind.LZ, "u-1") }!!.data
        assertEquals(document, back)
        assertEquals("14.0", (back["elevationFt"] as JsonPrimitive).content)
        assertEquals("9007199254740993", (back["big"] as JsonPrimitive).content)
    }

    @Test
    fun `a list observes the store and hides what is deleted`() = runBlocking<Unit> {
        store.observe(RecordKind.LZ).test {
            assertEquals(emptyList<String>(), awaitItem().map { it.uuid })
            store.transaction { put(lz("b")); put(lz("a")) }
            assertEquals(listOf("b", "a"), awaitItem().map { it.uuid })
            store.transaction { put(lz("a").copy(deleted = true)) }                  // deleted here, not yet told to the server
            assertEquals(listOf("b"), awaitItem().map { it.uuid })
            store.transaction { put(lz("c").copy(kind = RecordKind.AIRCRAFT)) }     // another kind changes nothing here
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -- Outbox --------------------------------------------------------------------------------

    @Test
    fun `an entry gets a sequence number, and the outbox is in the order it was made`() = runBlocking<Unit> {
        val first = store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "z", Operation.CREATE)) }
        val second = store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.CREATE)) }
        assertTrue(first.seq > 0 && second.seq > first.seq)
        assertEquals(listOf("z", "a"), store.transaction { outbox() }.map { it.uuid })       // not sorted by what they are about
    }

    @Test
    fun `a sequence number is never used twice, even after the last entry is removed`() = runBlocking<Unit> {
        val first = store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.CREATE)) }
        store.transaction { dequeue(first.seq) }
        val second = store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "b", Operation.CREATE)) }
        assertTrue("sequence ${second.seq} reuses ${first.seq}", second.seq > first.seq)
    }

    @Test
    fun `a change is queued once per record per kind of change`() = runBlocking<Unit> {
        store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.UPDATE)) }
        store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.DELETE)) }             // another operation: fine
        store.transaction { enqueue(OutboxEntry(0, RecordKind.AIRCRAFT, "a", Operation.UPDATE)) }       // another kind: fine
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.UPDATE)) } }
        }
        assertEquals(3, store.transaction { outbox() }.size)                                             // and the refusal left no trace
    }

    @Test
    fun `an entry can be found by what it is for`() = runBlocking<Unit> {
        store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.UPDATE)) }
        assertNotNull(store.transaction { entryFor(RecordKind.LZ, "a", Operation.UPDATE) })
        assertNull(store.transaction { entryFor(RecordKind.LZ, "a", Operation.DELETE) })
        assertNull(store.transaction { entryFor(RecordKind.AIRCRAFT, "a", Operation.UPDATE) })
    }

    @Test
    fun `a send in progress is kept exactly, so it can be repeated as it was`() = runBlocking<Unit> {
        val attempt = Attempt("key-1", version = 4, baseRevision = 2, name = "HAWK", data = buildJsonObject { put("note", "x") })
        val entry = store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.UPDATE)) }
        store.transaction { update(entry.copy(sent = attempt, attempts = 2, lastError = "timeout", blocked = false)) }
        val back = store.transaction { outbox() }.single()
        assertEquals(attempt, back.sent)
        assertEquals(2, back.attempts)
        assertEquals("timeout", back.lastError)

        store.transaction { update(back.copy(sent = null, attempts = 0, lastError = null, blocked = true)) }
        val cleared = store.transaction { outbox() }.single()
        assertNull(cleared.sent)
        assertTrue(cleared.blocked)
    }

    @Test
    fun `an attempt with no base revision stays that way`() = runBlocking<Unit> {
        val attempt = Attempt("key-1", version = 1, baseRevision = null, name = "HAWK", data = JsonObject(emptyMap()))
        val entry = store.transaction { enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.CREATE)) }
        store.transaction { update(entry.copy(sent = attempt)) }
        assertNull(store.transaction { outbox() }.single().sent!!.baseRevision)
    }

    @Test
    fun `updating an entry that is not there is an error, not a silent insert`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.transaction { update(OutboxEntry(99, RecordKind.LZ, "a", Operation.CREATE)) } }
        }
    }

    // -- Cursor --------------------------------------------------------------------------------

    @Test
    fun `the cursor starts at zero and is kept`() = runBlocking<Unit> {
        assertEquals(0, store.transaction { cursor() })
        store.transaction { setCursor(41) }
        assertEquals(41, store.transaction { cursor() })
        store.transaction { setCursor(42) }
        assertEquals(42, store.transaction { cursor() })
    }

    // -- Transactions --------------------------------------------------------------------------

    @Test
    fun `a transaction that fails leaves nothing behind`() {
        runBlocking { store.transaction { put(lz("keep")); setCursor(5) } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.transaction {
                    put(lz("new"))
                    put(lz("keep", name = "changed"))
                    enqueue(OutboxEntry(0, RecordKind.LZ, "new", Operation.CREATE))
                    setCursor(99)
                    error("the push failed half-way")
                }
            }
        }
        runBlocking {
            assertEquals(listOf("keep"), store.transaction { records(RecordKind.LZ) }.map { it.uuid })
            assertEquals("keep", store.transaction { record(RecordKind.LZ, "keep") }!!.name)
            assertTrue(store.transaction { outbox() }.isEmpty())
            assertEquals(5, store.transaction { cursor() })
        }
    }

    @Test
    fun `a transaction returns what its block returns, and sees its own writes`() = runBlocking<Unit> {
        val seen = store.transaction {
            put(lz("a"))
            record(RecordKind.LZ, "a")?.name
        }
        assertEquals("a", seen)
    }

    // -- On disk -------------------------------------------------------------------------------

    @Test
    fun `what was written is there after the database is closed and opened again`() = runBlocking<Unit> {
        val file = File(context.cacheDir, "persist-${System.nanoTime()}.db")
        fun open() = Room.databaseBuilder(context, EzpzDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val first = open()
            RoomSyncStore(first).transaction {
                put(lz("a", data = buildJsonObject { put("n", 1) }))
                enqueue(OutboxEntry(0, RecordKind.LZ, "a", Operation.CREATE, sent = Attempt("k", 1, null, "a", JsonObject(emptyMap())), attempts = 1))
                setCursor(12)
            }
            first.close()

            val second = open()
            val reopened = RoomSyncStore(second)
            assertEquals("a", reopened.transaction { record(RecordKind.LZ, "a") }!!.name)
            assertEquals(Json.parseToJsonElement("""{"n":1}"""), reopened.transaction { record(RecordKind.LZ, "a") }!!.data)
            assertEquals("k", reopened.transaction { outbox() }.single().sent!!.key)
            assertEquals(12, reopened.transaction { cursor() })
            second.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the database is a file in the app's own storage`() {
        val path = context.getDatabasePath(EzpzDatabase.FILE_NAME).path
        assertTrue(path, path.startsWith(context.dataDir.path))
        assertFalse("must not be on shared storage", path.contains("/sdcard") || path.contains("/storage/emulated"))
    }

    // -- Files ---------------------------------------------------------------------------------

    private fun mission(uuid: String, file: FileRef?) = LocalRecord(
        RecordKind.MISSION, uuid, serverId = null, baseRevision = null, name = uuid, data = JsonObject(emptyMap()), dirty = true, localVersion = 1, file = file,
    )

    private val everyByte = ByteArray(256) { it.toByte() } + ByteArray(70_000) { (it * 31).toByte() }

    @Test
    fun `a file comes back byte for byte, and its reference with it`() = runBlocking<Unit> {
        val id = FileHash.of(everyByte)
        store.transaction {
            putBlob(id, everyByte)
            put(mission("m", FileRef(id, "GOAT SUCKER.msnx")))
        }
        val back = store.transaction { record(RecordKind.MISSION, "m") }!!
        assertEquals(FileRef(id, "GOAT SUCKER.msnx"), back.file)
        assertTrue(everyByte.contentEquals(store.transaction { blob(id) }!!))
    }

    @Test
    fun `a file nothing refers to is let go when the transaction ends`() = runBlocking<Unit> {
        val id = FileHash.of(everyByte)
        store.transaction { putBlob(id, everyByte) }
        assertNull(store.transaction { blob(id) })
    }

    @Test
    fun `a file a record refers to stays until the record lets go of it`() = runBlocking<Unit> {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val firstId = FileHash.of(first)
        val secondId = FileHash.of(second)
        store.transaction { putBlob(firstId, first); put(mission("m", FileRef(firstId, "a.msnx"))) }
        assertNotNull(store.transaction { blob(firstId) })
        store.transaction { putBlob(secondId, second); put(mission("m", FileRef(secondId, "b.msnx"))) }
        assertNull(store.transaction { blob(firstId) })
        assertNotNull(store.transaction { blob(secondId) })
        store.transaction { remove(RecordKind.MISSION, "m") }
        assertNull(store.transaction { blob(secondId) })
    }

    @Test
    fun `a file a send in progress carries stays even after the record has moved on`() = runBlocking<Unit> {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val firstId = FileHash.of(first)
        val secondId = FileHash.of(second)
        store.transaction {
            putBlob(firstId, first)
            put(mission("m", FileRef(firstId, "a.msnx")))
            val entry = enqueue(OutboxEntry(0, RecordKind.MISSION, "m", Operation.CREATE))
            update(entry.copy(sent = Attempt("key", 1, null, "m", JsonObject(emptyMap()), FileRef(firstId, "a.msnx"))))
        }
        store.transaction { putBlob(secondId, second); put(mission("m", FileRef(secondId, "b.msnx"))) }
        // The record has a newer file, but the send in doubt must be repeated as it was, so its file is kept.
        assertNotNull(store.transaction { blob(firstId) })
        assertEquals(FileRef(firstId, "a.msnx"), store.transaction { outbox() }.single().sent!!.file)
        store.transaction { dequeue(outbox().single().seq) }
        assertNull(store.transaction { blob(firstId) })
    }

    @Test
    fun `a transaction that fails keeps the files it found, and drops none`() = runBlocking<Unit> {
        val bytes = byteArrayOf(9, 9)
        val id = FileHash.of(bytes)
        store.transaction { putBlob(id, bytes); put(mission("m", FileRef(id, "a.msnx"))) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.transaction { remove(RecordKind.MISSION, "m"); error("rolled back") } }
        }
        assertNotNull(store.transaction { blob(id) })
        assertNotNull(store.transaction { record(RecordKind.MISSION, "m") })
    }
}
