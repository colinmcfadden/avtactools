package app.ezpztac.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.missionpacks.PackSessions
import app.ezpztac.missionpacks.PackStoreContract
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
 * A database made by an earlier version of the app opens in this one with everything in it, and can then carry files (version 2) and mission
 * packs (version 3).
 *
 * The earlier database is built from the schema Room exported for it (`schemas/.../1.json` or `2.json`, committed): the same tables, indexes and identity hash an installed
 * app has. That is what a person's phone holds when the app updates, so this is the upgrade they get, tried against SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/app.ezpztac.data.EzpzDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    /** The database an installed app of [version] has: its tables, indexes and identity hash as Room exported them, and [rows] in them. */
    private fun makeVersion(version: Int, rows: (SQLiteDatabase) -> Unit): File {
        val exported = schema(version)
        val path = context.getDatabasePath(EzpzDatabase.FILE_NAME)
        path.parentFile!!.mkdirs()
        path.delete()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            for (entity in exported.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in (entity["indices"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                    db.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (query in exported.getValue("setupQueries").jsonArray) db.execSQL(query.jsonPrimitive.content)
            rows(db)
            db.version = version
        }
        return path
    }

    /** The database an installed version-1 app has, with a record, an unsent change and the sync cursor in it. */
    private fun makeVersionOne(): File = makeVersion(1) { db ->
        db.execSQL(
            "INSERT INTO record (kind, uuid, serverId, baseRevision, name, data, dirty, deleted, localVersion, conflictOf) " +
                "VALUES ('LZ', 'lz-1', 7, 3, 'HAWK', '{\"schema\":2}', 0, 0, 4, NULL)",
        )
        db.execSQL(
            "INSERT INTO outbox (seq, kind, uuid, operation, sentKey, sentVersion, sentBaseRevision, sentName, sentData, attempts, lastError, blocked) " +
                "VALUES (5, 'LZ', 'lz-1', 'UPDATE', 'key-1', 4, 3, 'HAWK', '{\"schema\":2}', 1, NULL, 0)",
        )
        db.execSQL("INSERT INTO sync_state (`key`, value) VALUES ('cursor', 42)")
    }

    private val missionBytes = ByteArray(5000) { (it % 251).toByte() }

    /** The database an installed version-2 app has: a mission with its file, a send of it in doubt, and the sync cursor. */
    private fun makeVersionTwo(): File = makeVersion(2) { db ->
        val id = FileHash.of(missionBytes)
        db.execSQL(
            "INSERT INTO record (kind, uuid, serverId, baseRevision, name, data, dirty, deleted, localVersion, conflictOf, fileId, fileName) " +
                "VALUES ('MISSION', 'm-1', 9, 2, 'GOAT', '{\"version\":1}', 1, 0, 3, NULL, '$id', 'GOAT.msnx')",
        )
        db.execSQL(
            "INSERT INTO outbox (seq, kind, uuid, operation, sentKey, sentVersion, sentBaseRevision, sentName, sentData, attempts, lastError, blocked, sentFileId, sentFileName) " +
                "VALUES (8, 'MISSION', 'm-1', 'UPDATE', 'key-2', 3, 2, 'GOAT', '{\"version\":1}', 1, NULL, 0, '$id', 'GOAT.msnx')",
        )
        db.execSQL("INSERT INTO blob (id, bytes) VALUES (?, ?)", arrayOf<Any>(id, missionBytes))
        db.execSQL("INSERT INTO sync_state (`key`, value) VALUES ('cursor', 77)")
    }

    /** The pack tables hold nothing: an upgrade brings no pack, and nothing of the library is taken for one. */
    private suspend fun assertNoPacks(database: EzpzDatabase) {
        val packs = RoomPackStore(database)
        assertTrue(packs.owed(1).isEmpty())
        assertTrue(packs.withDropped(1).isEmpty())
        assertEquals(0, database.packDao().unsentOrDropped())
        for (table in listOf("pack", "pack_item", "pack_op_outbox", "pack_own")) {
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(table, 0, cursor.getInt(0))
            }
        }
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
            assertNoPacks(database)                                           // through version 2 to 3
        } finally {
            database.close()
        }
    }

    @Test
    fun `an installed version 2 database opens with its missions, their files, a send in doubt and its cursor, and empty pack tables`() = runBlocking<Unit> {
        makeVersionTwo()
        val database = EzpzDatabase.open(context)
        try {
            val store = RoomSyncStore(database)
            val mission = store.transaction { record(RecordKind.MISSION, "m-1") }!!
            assertEquals("GOAT", mission.name)
            assertEquals(9, mission.serverId)
            assertEquals(FileRef(FileHash.of(missionBytes), "GOAT.msnx"), mission.file)
            assertTrue(missionBytes.contentEquals(store.transaction { blob(mission.file!!.id) }!!))
            val entry = store.transaction { outbox() }.single()
            assertEquals("key-2", entry.sent!!.key)
            assertEquals(mission.file, entry.sent!!.file)
            assertEquals(77, store.transaction { cursor() })
            assertNoPacks(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun `after the upgrade a mission pack is kept beside the library, and the library is as it was`() = runBlocking<Unit> {
        makeVersionTwo()
        val database = EzpzDatabase.open(context)
        try {
            val body = Json.parseToJsonElement(
                """{"uuid": "p-1", "name": "OP DK", "role": "editor", "status": "active", "head_seq": 2, "members": [],
                    "items": [{"uuid": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": {"notes": ""}}]}""",
            ).jsonObject
            val session = PackSessions.edit(
                PackSessions.open(body, 2),
                listOf(Json.parseToJsonElement("""{"type": "set", "item": "lz-1", "path": ["notes"], "value": "dry"}""")),
            ) { "op-1" }.session
            val packs = RoomPackStore(database)
            packs.write(null, session, 2)
            PackStoreContract.assertComesBack(session, packs.load("p-1", 2))
            assertEquals(listOf("p-1"), packs.owed(2))
            assertEquals(listOf("m-1"), RoomSyncStore(database).transaction { records(RecordKind.MISSION) }.map { it.uuid })
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
        fun tables(version: Int) = schema(version).getValue("entities").jsonArray.associateBy { it.jsonObject.getValue("tableName").jsonPrimitive.content }
        assertEquals(setOf("record", "outbox", "sync_state", "blob"), tables(2).keys)

        // 3 adds the four mission-pack tables and leaves every table of 2 exactly as it was: columns, keys and indexes.
        assertEquals(setOf("pack", "pack_item", "pack_op_outbox", "pack_own"), tables(3).keys - tables(2).keys)
        tables(2).forEach { (table, entity) -> assertEquals(table, entity, tables(3).getValue(table)) }
        assertEquals(3, schema(3).getValue("version").jsonPrimitive.content.toInt())
    }
}
