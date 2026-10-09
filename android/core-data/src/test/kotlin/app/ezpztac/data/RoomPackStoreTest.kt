package app.ezpztac.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackSessions
import app.ezpztac.missionpacks.PackStoreContract
import app.ezpztac.missionpacks.PendingOp
import app.ezpztac.missionpacks.PendingState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room's pack store held to what every store must do ([PackStoreContract]), each case on a new, empty database, as the in-memory one is. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class RoomPackStoreContractTest(private val name: String, private val case: PackStoreContract.Case) {
    @Test
    fun contract() = runBlocking<Unit> {
        val database = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        try {
            case.run(RoomPackStore(database))
        } finally {
            database.close()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = PackStoreContract.cases.map { arrayOf<Any>(it.name, it) }
    }
}

/**
 * What only Room's store can get wrong: which rows a write touches (an item can be megabytes, and someone dragging on the web makes an
 * event every 400 ms), that a write is whole or not at all, and that what it is told came before cannot make it skip what is not stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomPackStoreTest {
    private lateinit var database: EzpzDatabase
    private lateinit var store: RoomPackStore
    private var ids = 0

    @Before
    fun open() {
        database = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        store = RoomPackStore(database)
        // Every row written, as it is written: an insert, or an update of anything but where an edit stands.
        val db = database.openHelper.writableDatabase
        db.execSQL("CREATE TEMP TABLE written (tbl TEXT, id TEXT)")
        db.execSQL("CREATE TEMP TRIGGER pack_in AFTER INSERT ON pack BEGIN INSERT INTO written VALUES ('pack', NEW.uuid); END")
        db.execSQL("CREATE TEMP TRIGGER pack_up AFTER UPDATE ON pack BEGIN INSERT INTO written VALUES ('pack', NEW.uuid); END")
        db.execSQL("CREATE TEMP TRIGGER item_in AFTER INSERT ON pack_item BEGIN INSERT INTO written VALUES ('item', NEW.uuid); END")
        db.execSQL("CREATE TEMP TRIGGER item_up AFTER UPDATE ON pack_item BEGIN INSERT INTO written VALUES ('item', NEW.uuid); END")
        db.execSQL("CREATE TEMP TRIGGER op_in AFTER INSERT ON pack_op_outbox BEGIN INSERT INTO written VALUES ('op', NEW.clientOpId); END")
        db.execSQL("CREATE TEMP TRIGGER op_text AFTER UPDATE OF op ON pack_op_outbox BEGIN INSERT INTO written VALUES ('op', NEW.clientOpId); END")
    }

    @After
    fun close() = database.close()

    private fun written(): List<String> {
        val rows = mutableListOf<String>()
        database.openHelper.writableDatabase.query("SELECT tbl, id FROM written ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) rows += "${cursor.getString(0)}:${cursor.getString(1)}"
        }
        database.openHelper.writableDatabase.execSQL("DELETE FROM written")
        return rows
    }

    @Test
    fun `an event someone else made rewrites the one item it changed, and nothing else`() = runBlocking<Unit> {
        val opened = PackSessions.open(body(items = 3), SAM)
        store.write(null, opened, SAM)
        assertEquals(listOf("pack:p-1", "item:lz-1", "item:lz-2", "item:lz-3"), written())

        val received = PackSessions.receive(opened, listOf(event(4, heading("lz-2", 90)))).session
        store.write(opened, received, SAM)
        // The pack's place in the log moved, so its row; of the items only LZ 2.
        assertEquals(listOf("pack:p-1", "item:lz-2"), written())
        PackStoreContract.assertComesBack(received, store.load("p-1", SAM))
    }

    @Test
    fun `an edit adds its own row only, and sending it, its answer and its confirmation never write the operation again`() = runBlocking<Unit> {
        val opened = PackSessions.open(body(items = 2), SAM)
        store.write(null, opened, SAM)
        written()

        val edited = edit(opened, heading("lz-1", 100))
        store.write(opened, edited, SAM)
        assertEquals(listOf("op:op-1"), written())                      // not the pack, not an item: only what was made

        val sent = PackSessions.nextBatch(edited)!!.session
        store.write(edited, sent, SAM)
        assertEquals(emptyList<String>(), written())                     // where it stands changed, nothing more

        val acked = PackSessions.batchAnswered(sent, answer("op-1", seq = 4, events = "")).session
        store.write(sent, acked, SAM)
        assertEquals(emptyList<String>(), written())
        assertEquals(PendingOp(edited.pending.single().op, PendingState.ACKED, 4), store.load("p-1", SAM)!!.pending.single())

        val confirmed = PackSessions.receive(acked, listOf(event(4, heading("lz-1", 100), clientOpId = "op-1"))).session
        store.write(acked, confirmed, SAM)
        assertEquals(listOf("pack:p-1", "item:lz-1"), written())
        assertTrue(store.load("p-1", SAM)!!.pending.isEmpty())
        assertEquals(0, store.unsentCount(SAM))
    }

    @Test
    fun `refused edits keep the order they were refused in, as each is let go`() = runBlocking<Unit> {
        var session = PackSessions.open(body(items = 1), SAM)
        store.write(null, session, SAM)
        suspend fun step(next: PackSession) {
            store.write(session, next, SAM)
            session = next
        }

        step(edit(session, heading("lz-1", 1)))
        step(PackSessions.nextBatch(session)!!.session)                                 // op-1 out
        step(edit(session, heading("lz-1", 2)))                                          // op-2 queued behind it
        step(PackSessions.batchFailed(session, PackFailure(400, "invalid_op", reason = "bad_value")))   // op-1 refused alone
        step(PackSessions.nextBatch(session)!!.session)
        step(PackSessions.batchFailed(session, PackFailure(413, "item_too_large", item = "lz-1")))       // then op-2
        step(edit(session, heading("lz-1", 3)))
        step(PackSessions.nextBatch(session)!!.session)
        step(PackSessions.batchFailed(session, PackFailure(404, "pack_not_found")))      // op-3 dropped as gone

        assertEquals(listOf("op-1", "op-2", "op-3"), droppedIds())
        // The refused order is the session's, whatever order the edits were made in (it is not always the same): kept as given.
        step(session.copy(dropped = session.dropped.reversed()))
        assertEquals(listOf("op-3", "op-2", "op-1"), droppedIds())
        step(PackSessions.withoutDropped(session, setOf("op-2")))
        PackStoreContract.assertComesBack(session, store.load("p-1", SAM))
        step(PackSessions.withoutDropped(session, setOf("op-3")))
        assertEquals(listOf("op-1"), droppedIds())
        assertEquals(listOf("bad_value"), store.load("p-1", SAM)!!.dropped.map { it.reason })
    }

    private suspend fun droppedIds(): List<String> = store.load("p-1", SAM)!!.dropped.map { (it.op.getValue("client_op_id") as JsonPrimitive).content }

    @Test
    fun `a write that cannot be made leaves the copy as it was`() = runBlocking<Unit> {
        val edited = edit(PackSessions.open(body(items = 1), SAM), heading("lz-1", 100))
        store.write(null, edited, SAM)
        // Someone else's change to LZ 1 (its row is written first), and two edits under one name: a bug the store refuses rather than
        // keep one of them. The item written before the refusal is taken back with it.
        val changed = PackSessions.receive(edited, listOf(event(4, heading("lz-1", 5)))).session
        val twice = PackSessions.withView(changed.copy(pending = changed.pending + changed.pending))
        assertThrows(IllegalStateException::class.java) { runBlocking { store.write(edited, twice, SAM) } }
        PackStoreContract.assertComesBack(edited, store.load("p-1", SAM))
    }

    @Test
    fun `what the caller says came before cannot make the store skip what it does not hold`() = runBlocking<Unit> {
        val opened = PackSessions.open(body(items = 2), SAM)
        val edited = edit(opened, heading("lz-1", 100))
        // Nothing stored, but told the pack was `opened` (forgotten in between, say): every item still written.
        store.write(opened, edited, SAM)
        PackStoreContract.assertComesBack(edited, store.load("p-1", SAM))

        // Colin's copy stored over it, with his own fields; then Sam's written as if over Sam's own, which is not there any more: Sam's
        // whole, and Colin's own fields gone with his copy.
        val colins = edit(PackSessions.open(body(items = 1, role = "owner"), COLIN), heading("lz-1", 5))
        store.write(null, colins, COLIN)
        PackStoreContract.assertComesBack(colins, store.load("p-1", COLIN))
        store.putOwn("p-1", "lz-1", json("""{"view": {"baseMap": "topo"}}"""))
        store.write(edited, opened, SAM)
        PackStoreContract.assertComesBack(opened, store.load("p-1", SAM))
        assertNull(store.load("p-1", COLIN))
        assertNull(store.own("p-1", "lz-1"))
        assertTrue(store.owed(COLIN).isEmpty())
    }

    @Test
    fun `an item of several megabytes is kept and comes back`() = runBlocking<Unit> {
        // Bigger than one cursor window (2 MB on a device): the server takes an item up to 5 MB.
        val big = "x".repeat(3 * 1024 * 1024)
        val opened = PackSessions.open(body(items = 1, notes = big), SAM)
        val edited = edit(opened, json("""{"type": "set", "item": "lz-1", "path": ["more"], "value": "$big"}"""))
        store.write(null, edited, SAM)
        PackStoreContract.assertComesBack(edited, store.load("p-1", SAM))
    }

    // -- A pack, OP DK, as the server would send it ----------------------------------------------------------------------------------

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun body(items: Int, role: String = "editor", notes: String = ""): JsonObject = json(
        """{"uuid": "p-1", "name": "OP DK", "description": "", "status": "active", "role": "$role", "head_seq": 3, "live_url": null,
            "members": [{"user_id": 1, "name": "Colin", "role": "owner"}, {"user_id": 2, "name": "Sam", "role": "editor"}],
            "items": [${(1..items).joinToString(", ") { n ->
            """{"uuid": "lz-$n", "kind": "lz", "name": "LZ $n", "revision": 1, "seq": $n, "source": null,
                "data": {"flightData": {"landingHeading": 270, "speed": 1.0}, "notes": "$notes"}}"""
        }}]}""",
    )

    private fun heading(item: String, value: Int): JsonObject =
        json("""{"type": "set", "item": "$item", "path": ["flightData", "landingHeading"], "value": $value}""")

    private fun edit(session: PackSession, op: JsonObject): PackSession =
        PackSessions.edit(session, listOf(op)) { "op-${++ids}" }.also { check(it.refused == null) { it.refused!! } }.session

    private fun event(seq: Long, op: JsonObject, clientOpId: String? = null): JsonObject = json(
        """{"seq": $seq, "type": "set", "item": "${(op["item"] as JsonPrimitive).content}",
            "actor": {"id": ${if (clientOpId == null) COLIN else SAM}, "name": "x"}, "summary": "", "status": "applied", "reason": null,
            "client_op_id": ${clientOpId?.let { "\"$it\"" } ?: "null"}, "op": $op, "created_at": "2026-10-09T12:00:00"}""",
    )

    private fun answer(clientOpId: String, seq: Long, events: String): JsonObject = json(
        """{"head_seq": $seq, "results": [{"client_op_id": "$clientOpId", "seq": $seq, "status": "applied", "reason": null}],
            "events": [$events], "has_more": true}""",
    )

    private companion object {
        const val COLIN = 1
        const val SAM = 2
    }
}
