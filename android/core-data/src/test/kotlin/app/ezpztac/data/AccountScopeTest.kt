package app.ezpztac.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.Operation
import app.ezpztac.sync.OutboxEntry
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
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
