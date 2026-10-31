package app.ezpztac.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackSessions
import app.ezpztac.missionpacks.PendingState
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.Operation
import app.ezpztac.sync.OutboxEntry
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountScopeTest {
    private lateinit var database: EzpzDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var scope: AccountScope

    @Before
    fun open() {
        database = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        store = RoomSyncStore(database)
        scope = RoomAccountScope(database)
    }

    @After
    fun close() = database.close()

    private suspend fun savePlan(uuid: String, queued: Boolean = true) = store.transaction {
        put(LocalRecord(RecordKind.LZ, uuid, null, null, uuid, JsonObject(emptyMap()), dirty = queued, localVersion = 1))
        if (queued) enqueue(OutboxEntry(0, RecordKind.LZ, uuid, Operation.CREATE))
        setCursor(9)
    }

    @Test
    fun `a device nobody has used is unclaimed`() = runBlocking<Unit> {
        assertEquals(Ownership.Unclaimed, scope.ownership(1))
    }

    @Test
    fun `the person who claimed it finds it theirs, however often they sign out and in`() = runBlocking<Unit> {
        scope.claim(1)
        assertEquals(Ownership.Yours, scope.ownership(1))
        scope.claim(1)                                                                      // claiming it again is fine
        assertEquals(Ownership.Yours, scope.ownership(1))
    }

    @Test
    fun `someone else signing in is told it is not theirs, and how much work would go`() = runBlocking<Unit> {
        scope.claim(1)
        savePlan("a"); savePlan("b"); savePlan("c", queued = false)
        assertEquals(Ownership.SomeoneElses(unsyncedChanges = 2), scope.ownership(2))
    }

    @Test
    fun `what someone else would lose counts mission-pack edits not taken by their pack, and those a pack refused`() = runBlocking<Unit> {
        scope.claim(1)
        savePlan("a")
        val packs = RoomPackStore(database)
        var ids = 0
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        fun opened(uuid: String) = PackSessions.open(
            json("""{"uuid": "$uuid", "role": "editor", "status": "active", "head_seq": 1, "members": [], "items": [{"uuid": "lz-1", "kind": "lz", "name": "HAWK", "data": {}}]}"""),
            1,
        )
        fun edit(session: PackSession) =
            PackSessions.edit(session, listOf(json("""{"type": "set", "item": "lz-1", "path": ["notes"], "value": "x"}"""))) { "op-${++ids}" }.session

        packs.write(null, edit(edit(opened("p-queued"))), 1)                                          // two queued
        packs.write(null, edit(PackSessions.nextBatch(edit(opened("p-sent")))!!.session), 1)        // one out, one behind it
        val out = PackSessions.nextBatch(edit(opened("p-taken")))!!.session
        val id = out.pending.single().op.getValue("client_op_id")
        val taken = PackSessions.batchAnswered(
            out,
            json("""{"head_seq": 2, "results": [{"client_op_id": $id, "seq": 2, "status": "applied"}], "events": [], "has_more": true}"""),
        ).session
        check(taken.pending.single().state == PendingState.ACKED)
        packs.write(null, taken, 1)                                                                   // the pack's already: not counted
        packs.write(null, PackSessions.batchFailed(PackSessions.nextBatch(edit(opened("p-refused")))!!.session, PackFailure(404)), 1)

        assertEquals(Ownership.SomeoneElses(unsyncedChanges = 1 + 2 + 2 + 1), scope.ownership(2))
    }

    @Test
    fun `wiping removes every mission pack, the edits to them and their own fields`() = runBlocking<Unit> {
        scope.claim(1)
        val packs = RoomPackStore(database)
        val body = Json.parseToJsonElement(
            """{"uuid": "p-1", "role": "editor", "status": "active", "head_seq": 1, "members": [], "items": [{"uuid": "lz-1", "kind": "lz", "name": "HAWK", "data": {}}]}""",
        ).jsonObject
        val edited = PackSessions.edit(
            PackSessions.open(body, 1),
            listOf(Json.parseToJsonElement("""{"type": "set", "item": "lz-1", "path": ["notes"], "value": "x"}""")),
        ) { "op-1" }.session
        packs.write(null, edited, 1)
        packs.putOwn("p-1", "lz-1", Json.parseToJsonElement("""{"view": {"baseMap": "topo"}}""").jsonObject)

        scope.wipe()

        assertNull(packs.load("p-1", 1))
        assertNull(packs.own("p-1", "lz-1"))
        assertTrue(packs.owed(1).isEmpty())
        assertEquals(0, packs.unsentCount(1))
        assertEquals(Ownership.Unclaimed, scope.ownership(2))
    }

    @Test
    fun `claiming another person's data without wiping it is refused`() = runBlocking<Unit> {
        scope.claim(1)
        assertThrows(IllegalStateException::class.java) { runBlocking { scope.claim(2) } }
        assertEquals(Ownership.Yours, scope.ownership(1))
    }

    @Test
    fun `wiping removes plans, queued changes, the cursor and the claim, and nothing is left to leak`() = runBlocking<Unit> {
        scope.claim(1)
        savePlan("a")
        scope.wipe()
        assertTrue(store.transaction { records(RecordKind.LZ) }.isEmpty())
        assertTrue(store.transaction { outbox() }.isEmpty())
        assertEquals(0, store.transaction { cursor() })                                      // the next account reads the feed from the start
        assertEquals(Ownership.Unclaimed, scope.ownership(2))
        scope.claim(2)
        assertEquals(Ownership.Yours, scope.ownership(2))
    }
}
