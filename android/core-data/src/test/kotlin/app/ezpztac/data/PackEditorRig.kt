package app.ezpztac.data

import app.ezpztac.missionpacks.FakePackServer
import app.ezpztac.missionpacks.InMemoryPackStore
import app.ezpztac.missionpacks.LzPackKind
import app.ezpztac.missionpacks.PackApi
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackKind
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackStore
import app.ezpztac.missionpacks.PackUser
import app.ezpztac.missionpacks.RecordingKeeper
import app.ezpztac.missionpacks.RoutePackKind
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.RouteSet
import app.ezpztac.network.NetworkException
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.JsonCompare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.IOException

/**
 * Sam B.'s device with OP DK, Colin M.'s mission pack in which Sam is an editor, open in the editors as the app has them: the one
 * [DiagramSession] and [RouteSession], each joined to the pack ([PackItemStore], [PackEditorSync] through [PackWorkspace]) and to a library of
 * Sam's own, and a real [PackEngine] over [FakePackServer] (the server's rules) and the device's copy in memory. Everything runs on one test
 * dispatcher, the main thread's stand-in, on virtual time: a change made here is sent once it has been still for the session's pause
 * ([advance]), and Colin's changes from his own device ([theirs]) are heard when the pack is next asked what is new.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PackEditorRig(
    val test: TestScope,
    val packStore: PackStore = InMemoryPackStore(),
    live: String? = null,
    lzKind: ((DiagramNormalizer.Environment) -> PackKind<Diagram>)? = null,
) {
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val server = FakePackServer(live).apply {
        person(COLIN.id, COLIN.name)
        person(SAM.id, SAM.name)
    }

    /** Set once this device has sent more batches, or written its copy more often, than any test does: the editor and the pack never settled. */
    var runaway = false
        private set

    // A runaway editor and pack go on at one virtual moment for ever, so the test would hang rather than fail: the server refuses its batches
    // past a number (as no connection, so a send waits to be tried again), and the device's copy its writes (so an edit fails, and the editor's
    // next try waits for the session's pause). Either way the moment ends, and the test goes on to fail.
    private val api = object : PackApi by server {
        override suspend fun sendOps(uuid: String, batch: JsonObject, asUser: Int): JsonObject {
            if (server.batches.size >= MAX_BATCHES) {
                runaway = true
                throw NetworkException("More than $MAX_BATCHES batches: the editor and the pack never settle.", requestMayHaveBeenSent = false)
            }
            return server.sendOps(uuid, batch, asUser)
        }
    }
    private val copy = object : PackStore by packStore {
        private var writes = 0

        override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
            if (++writes > MAX_WRITES) {
                runaway = true
                throw IOException("More than $MAX_WRITES writes: the editor and the pack never settle.")
            }
            packStore.write(before, after, me)
        }
    }
    val keeper = RecordingKeeper()
    private var ids = 0
    val engine = PackEngine(api, copy, keeper, scope, dispatcher, newId = { "op-${++ids}" })
    val env = DiagramNormalizer.Environment(now = { NOW }, newId = { "made-${++ids}" })
    val lzItems = PackItemStore(engine, lzKind?.invoke(env) ?: LzPackKind(env), copy, { it.id }, dispatcher, dispatcher)
    val routeItems = PackItemStore<RouteSet>(engine, RoutePackKind, copy, { it.id }, dispatcher, dispatcher)
    val library = Device("A", FakeServer())
    val scheduler = RecordingScheduler()
    val diagramRepository = DiagramRepository(library.repository, library.store as InMemorySyncStore, scheduler)
    val routeRepository = RouteRepository(library.repository, library.store as InMemorySyncStore, scheduler)
    val diagrams = DiagramSession(diagramRepository, scope, packs = lzItems)
    val routes = RouteSession(routeRepository, scope, packs = routeItems)
    val workspace = PackWorkspace(engine, diagrams, routes, lzItems, routeItems, keeper)

    /** Everything the person was told, in order: the engine's notices and the editors'. */
    val notices = mutableListOf<PackNotice>()

    /** A pack of Colin's, Sam an editor in it, with [items] in it ([create]s Colin made). */
    fun pack(uuid: String = PACK, name: String = "OP DK", vararg items: JsonObject) {
        server.createPack(uuid, name, owner = COLIN.id, members = mapOf(SAM.id to "editor"))
        if (items.isNotEmpty()) server.elsewhere(uuid, COLIN.id, *items)
    }

    /** Sam is signed in with Mission Packs on, and the editors are joined to whatever pack is open. */
    suspend fun start() {
        engine.enable(SAM)
        workspace.start()
        scope.launch { workspace.notices.collect { notices += it } }
        test.runCurrent()
    }

    suspend fun openPack(uuid: String = PACK) {
        workspace.openPack(uuid)
        test.runCurrent()
    }

    /** [item] of the open pack opened in its editor. */
    suspend fun openItem(item: String) {
        assertEquals("opened $item", true, workspace.openItem(item))
        test.runCurrent()
    }

    /** Started, OP DK open (made with [items], LZ HAWK by default), and [open] opened in its editor. */
    suspend fun opened(open: String = ITEM, vararg items: JsonObject = arrayOf(create(ITEM, "lz", "LZ HAWK", HAWK))) {
        pack(PACK, "OP DK", *items)
        start()
        openPack()
        openItem(open)
    }

    /** Virtual time passes, and whatever was due in it is done. */
    fun advance(ms: Long) {
        test.advanceTimeBy(ms)
        test.runCurrent()
        settled()
    }

    /** [ops] made by Colin on his own device, then the pack asked at once what is new (as its next poll would). */
    fun theirs(vararg ops: JsonObject, pack: String = PACK) {
        server.elsewhere(pack, COLIN.id, *ops)
        engine.refresh()
        test.runCurrent()
        settled()
    }

    /** Fails a test whose device sent without end. */
    fun settled() {
        assertFalse("More than $MAX_BATCHES batches: the editor and the pack never settle", runaway)
    }

    /** Every batch this device sent, each its operations without their ids. */
    fun sent(): List<List<JsonObject>> =
        server.batches.map { batch -> batch.getValue("ops").jsonArray.map { JsonObject(it.jsonObject - "client_op_id") } }

    /** Item [item]'s data as the server holds it. */
    fun serverData(item: String = ITEM, pack: String = PACK): JsonObject = server.data(pack, item) as JsonObject

    fun close() {
        scope.cancel()
        settled()
    }

    companion object {
        val COLIN = PackUser(1, "Colin M.")
        val SAM = PackUser(2, "Sam B.")
        const val PACK = "p-1"
        const val ITEM = "lz-1"
        val LOCAL: String = PackRef.localId(PACK, ITEM)
        const val NOW = "2026-10-09T12:00:00.000Z"

        /** The session's pause before a save, and a moment more. */
        const val STILL = DocumentSession.SAVE_AFTER_STILL_MS + 1

        /** More batches than any of these tests sends. */
        const val MAX_BATCHES = 50

        /** More writes of the device's copy than any of these tests makes. */
        const val MAX_WRITES = 2_000

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun array(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

        /** LZ HAWK as a client in step with today's web writes it: analysed, two aircraft, landing heading 090. */
        val HAWK: JsonObject = json(
            """{"schemaVersion": 2, "status": "analyzed",
                "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"},
                "mapData": {"mgrs": "16S GC 28864 55349"},
                "flightData": {"landing_hdg": "090°"},
                "analysis": {"customLZ": null, "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "results": {"area": 1},
                             "gridElevation": "1200", "latLong": ""},
                "graphics": {
                  "helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "heading": 90}, {"id": 102, "lat": 34.501, "lon": -84.1, "heading": 90}],
                  "doghouses": [], "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "units": [], "measurements": [], "exportBox": null}}""",
        )

        /** The `item.create` of [item], as Colin's client sends it. */
        fun create(item: String, kind: String, name: String, data: JsonElement): JsonObject = JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("item.create"), "item" to JsonPrimitive(item), "kind" to JsonPrimitive(kind),
                "name" to JsonPrimitive(name), "data" to data,
            ),
        )

        fun assertJson(expected: JsonElement, actual: JsonElement?, what: String) {
            assertEquals(what, emptyList<String>(), JsonCompare.differences(expected, actual ?: kotlinx.serialization.json.JsonNull))
        }

        /** A test on a rig, which is let go of however it ends (the engine's clients poll for as long as they are let). */
        fun packTest(body: suspend TestScope.(PackEditorRig) -> Unit) = runTest {
            val rig = PackEditorRig(this)
            try {
                body(rig)
            } finally {
                rig.close()
            }
        }
    }
}
