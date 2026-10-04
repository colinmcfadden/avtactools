package app.ezpztac.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.sync.FileHash
import app.ezpztac.sync.FileRef
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A database made by an earlier version of the app opens in this one with everything in it, and can then carry files.
 *
 * The earlier database is built from the schema Room exported for it (`schemas/.../1.json`, committed): the same tables, indexes and identity hash an installed app has. That
 * is what a person's phone holds when the app updates, so this is the upgrade they get, tried against SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/app.ezpztac.data.EzpzDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    /** The database an installed version-1 app has, with a record, an unsent change and a point set's worth of kinds in it. */
    private fun makeVersionOne(): File {
        val v1 = schema(1)
        val path = context.getDatabasePath(EzpzDatabase.FILE_NAME)
        path.parentFile!!.mkdirs()
        path.delete()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            for (entity in v1.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in (entity["indices"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                    db.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (query in v1.getValue("setupQueries").jsonArray) db.execSQL(query.jsonPrimitive.content)
            db.execSQL(
                "INSERT INTO record (kind, uuid, serverId, baseRevision, name, data, dirty, deleted, localVersion, conflictOf) " +
                    "VALUES ('LZ', 'lz-1', 7, 3, 'HAWK', '{\"schema\":2}', 0, 0, 4, NULL)",
            )
            db.execSQL(
                "INSERT INTO outbox (seq, kind, uuid, operation, sentKey, sentVersion, sentBaseRevision, sentName, sentData, attempts, lastError, blocked) " +
                    "VALUES (5, 'LZ', 'lz-1', 'UPDATE', 'key-1', 4, 3, 'HAWK', '{\"schema\":2}', 1, NULL, 0)",
            )
            db.execSQL("INSERT INTO sync_state (`key`, value) VALUES ('cursor', 42)")
            db.version = 1
        }
        return path
    }

    @Test
    fun `an installed version 1 database opens with its records, its unsent changes and its cursor`() = runBlocking<Unit> {
        makeVersionOne()
        val database = EzpzDatabase.open(context)
        try {
            val store = RoomSyncStore(database)
            val record = store.transaction { record(RecordKind.LZ, "lz-1") }!!
            assertEquals("HAWK", record.name)
            assertEquals(7, record.serverId)
            assertEquals(3, record.baseRevision)
            assertEquals(4, record.localVersion)
            assertNull(record.file)                                          // nothing had a file before

            val entry = store.transaction { outbox() }.single()
            assertEquals(Operation.UPDATE, entry.operation)
            assertEquals("key-1", entry.sent!!.key)                           // a send in doubt is still repeated as it was
            assertNull(entry.sent!!.file)
            assertEquals(42, store.transaction { cursor() })
        } finally {
            database.close()
        }
    }

    @Test
    fun `after the upgrade a mission can be kept with its file, and the old kinds still work`() = runBlocking<Unit> {
        makeVersionOne()
        val database = EzpzDatabase.open(context)
        try {
            val store = RoomSyncStore(database)
            val bytes = ByteArray(5000) { (it % 251).toByte() }
            val id = FileHash.of(bytes)
            store.transaction {
                putBlob(id, bytes)
                put(LocalRecord(RecordKind.MISSION, "m-1", null, null, "GOAT", JsonObject(emptyMap()), dirty = true, localVersion = 1, file = FileRef(id, "GOAT.msnx")))
            }
            assertTrue(bytes.contentEquals(store.transaction { blob(id) }!!))
            assertNotNull(store.transaction { record(RecordKind.LZ, "lz-1") })
        } finally {
            database.close()
        }
    }

    @Test
    fun `the exported schemas say what the migration adds, and nothing else`() {
        fun columns(version: Int, table: String) = schema(version).getValue("entities").jsonArray.map { it.jsonObject }
            .first { it.getValue("tableName").jsonPrimitive.content == table }.getValue("fields").jsonArray
            .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }.toSet()

        assertEquals(setOf("fileId", "fileName"), columns(2, "record") - columns(1, "record"))
        assertEquals(setOf("sentFileId", "sentFileName"), columns(2, "outbox") - columns(1, "outbox"))
        assertEquals(emptySet<String>(), columns(1, "record") - columns(2, "record"))
        val tables = schema(2).getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }.toSet()
        assertEquals(setOf("record", "outbox", "sync_state", "blob"), tables)
    }
}
