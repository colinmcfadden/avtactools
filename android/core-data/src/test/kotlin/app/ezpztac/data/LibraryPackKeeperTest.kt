package app.ezpztac.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.KeptVersion
import app.ezpztac.model.PointSets
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryPackKeeperTest {
    private lateinit var database: EzpzDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var keeper: LibraryPackKeeper

    @Before
    fun open() {
        database = inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        store = RoomSyncStore(database)
        keeper = LibraryPackKeeper(store)
    }

    @After
    fun close() = database.close()

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val lz = KeptVersion(
        "p-1:lz-1:op-3", "lz", "LZ HAWK (my edits)",
        json("""{"schemaVersion": 2, "target": {"lat": 34.5, "lon": -84.25}, "flightData": {"landing_hdg": "270°"}, "newer": {"kept": true}}"""),
    )
    private val routes = KeptVersion(
        "p-1:rt-1:op-4", "route", "MISSION 1 (my edits)",
        json("""{"version": 2, "routes": [{"id": "sketch-1", "name": "ROUTE 1", "points": []}], "theirs": true}"""),
    )
    private val points = KeptVersion(
        "p-1:ps-1:op-5", "pointset", "TRAINING AREA (my edits)",
        json("""[{"id": "lps-0-a1b2c3", "name": "RP1", "lat": 34.5, "lon": -84.25, "elevationFt": 1730}]"""),
    )

    @Test
    fun `each kind is saved as the person's own record, in the form the library keeps it, and queued for the server`() = runBlocking<Unit> {
        val outcomes = keeper.keep("p-1", listOf(lz, routes, points))

        assertEquals(
            listOf(
                KeptOutcome.Saved(lz.key, "lz", LibraryPackKeeper.uuidFor(lz.key), "LZ HAWK (my edits)"),
                KeptOutcome.Saved(routes.key, "route", LibraryPackKeeper.uuidFor(routes.key), "MISSION 1 (my edits)"),
                KeptOutcome.Saved(points.key, "pointset", LibraryPackKeeper.uuidFor(points.key), "TRAINING AREA (my edits)"),
            ),
            outcomes,
        )
        val diagram = store.transaction { record(RecordKind.LZ, LibraryPackKeeper.uuidFor(lz.key)) }!!
        assertEquals("LZ HAWK (my edits)", diagram.name)
        assertEquals(lz.data.toString(), diagram.data.toString())                 // whole, a field this version does not know included
        assertTrue(diagram.dirty)
        assertNull(diagram.serverId)

        // A route set as the library keeps every set: its sketched routes as {version: 1, routes}.
        val set = store.transaction { record(RecordKind.ROUTE, LibraryPackKeeper.uuidFor(routes.key)) }!!
        assertEquals("""{"version":1,"routes":[{"id":"sketch-1","name":"ROUTE 1","points":[]}]}""", set.data.toString())

        // A point set's points in the object the library holds every set in, which reads back as the points.
        val pointSet = store.transaction { record(RecordKind.POINT_SET, LibraryPackKeeper.uuidFor(points.key)) }!!
        assertEquals("""{"points":${points.data}}""", pointSet.data.toString())
        assertEquals(listOf("RP1"), PointSets.parse(pointSet.uuid, null, pointSet.name, pointSet.data).points.map { it.name })

        assertEquals(
            listOf(RecordKind.LZ to Operation.CREATE, RecordKind.ROUTE to Operation.CREATE, RecordKind.POINT_SET to Operation.CREATE),
            store.transaction { outbox() }.map { it.kind to it.operation },
        )
    }

    @Test
    fun `keeping a version again makes no second record, and answers as the first time`() = runBlocking<Unit> {
        val first = keeper.keep("p-1", listOf(lz))
        // The app stopped between keeping and letting the edits go: asked again, under the same key, with whatever name it has now.
        val again = keeper.keep("p-1", listOf(lz.copy(name = "LZ HAWK II (my edits)")))
        assertEquals(first, again)
        assertEquals(1, store.transaction { records(RecordKind.LZ) }.size)
        assertEquals(1, store.transaction { outbox() }.size)
    }

    @Test
    fun `a kept record deleted since is not made again`() = runBlocking<Unit> {
        keeper.keep("p-1", listOf(lz))
        val uuid = LibraryPackKeeper.uuidFor(lz.key)
        // It reached the server, then the person deleted it: a deletion still waiting to go.
        store.transaction {
            put(record(RecordKind.LZ, uuid)!!.copy(serverId = 7, baseRevision = 1, dirty = false))
            outbox().forEach { dequeue(it.seq) }
        }
        SyncRepository(store).delete(RecordKind.LZ, uuid)

        assertEquals(listOf(KeptOutcome.Saved(lz.key, "lz", uuid, "LZ HAWK (my edits)")), keeper.keep("p-1", listOf(lz)))
        assertTrue(store.transaction { record(RecordKind.LZ, uuid) }!!.deleted)
        assertEquals(listOf(Operation.DELETE), store.transaction { outbox() }.map { it.operation })
    }

    @Test
    fun `what the library cannot hold is nothing to keep, and the rest is still kept`() = runBlocking<Unit> {
        val empty = points.copy(key = "p-1:ps-2:op-6", data = json("[]"))
        val unknown = KeptVersion("p-1:x-1:op-7", "threat", "SA-8 (my edits)", json("""{"lat": 1}"""))
        val outcomes = keeper.keep("p-1", listOf(empty, lz, unknown))

        assertEquals(KeptOutcome.NothingToKeep(empty.key, "empty_point_set"), outcomes[0])
        assertTrue(outcomes[1] is KeptOutcome.Saved)
        assertEquals(KeptOutcome.NothingToKeep(unknown.key, "unknown_kind"), outcomes[2])
        assertTrue(store.transaction { records(RecordKind.POINT_SET) }.isEmpty())
        assertEquals(1, store.transaction { outbox() }.size)
    }

    @Test
    fun `a version is named by its key alone`() {
        assertEquals(LibraryPackKeeper.uuidFor("p-1:lz-1:op-3"), LibraryPackKeeper.uuidFor("p-1:lz-1:op-3"))
        assertTrue(LibraryPackKeeper.uuidFor("p-1:lz-1:op-3") != LibraryPackKeeper.uuidFor("p-1:lz-1:op-4"))
        // A uuid the server takes as a client_uuid (backend/sync_support.py).
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(LibraryPackKeeper.uuidFor("p-1:lz-1:op-3")))
    }
}
