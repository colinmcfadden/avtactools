package app.ezpztac.sync

import app.ezpztac.network.ApiException
import app.ezpztac.network.ChangeFeed
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RevisionConflictException
import app.ezpztac.network.SyncChange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An in-memory server with the rules of the real one (`backend/sync_support.py`): an identity chosen by the device, a revision
 * bumped by every change, `If-Match` that refuses a stale edit with the server's copy, an idempotency key that makes a repeated
 * write the same write, a deletion that leaves a tombstone, and a change feed ordered by a counter.
 *
 * It exists so the engine can be tried against failures the real server will not produce on demand (an answer lost on the way,
 * a refusal, an edit that lands mid-push). The scenarios the two share also run against the real server
 * (`LiveSyncTest`), which is what keeps this one honest.
 */
public class FakeServer : SyncApi {
    class Rec(
        val serverId: Int, val kind: RecordKind, val uuid: String,
        var revision: Int, var name: String, var data: JsonObject, var deleted: Boolean, var seq: Int, var lastKey: String?,
    )

    val records = mutableListOf<Rec>()
    private var seq = 0
    private var nextId = 1

    /** Everything sent, for tests to look at: `"create lz uuid key"`, `"update lz 3 base=1 key"`. */
    val log = mutableListOf<String>()

    /** Apply the next N writes, then lose the answer: the server did it and the device never heard. */
    var loseAnswers = 0

    /** Refuse the next N writes with a network failure before the server sees them. */
    var offlineWrites = 0

    /** Fail every call with this. */
    var failAll: ApiException? = null

    /** Fail writes to this record name (any kind) with this. */
    var failName: Pair<String, ApiException>? = null

    /** Runs when a write arrives, before it is applied: a hook to change things mid-push. */
    var onWrite: (suspend (String) -> Unit)? = null

    var pageSize = 100

    /** Rewrites the changes the feed returns, for tests that need it to say something out of date. */
    var feedTransform: ((List<SyncChange>) -> List<SyncChange>)? = null

    /** Changes of a kind this engine does not handle (a mission route, a collection a newer server adds), placed in the feed's order. */
    private val foreign = mutableListOf<SyncChange>()

    fun addForeignChange(type: String, kind: String? = null) {
        foreign += SyncChange(type = type, id = nextId++, clientUuid = "foreign-$seq", revision = 1, deleted = false, name = "a $type", seq = ++seq,
            kind = kind, data = JsonObject(emptyMap()))
    }

    private fun check(op: String) {
        failAll?.let { throw it }
        if (offlineWrites > 0 && op != "changes") { offlineWrites--; throw NetworkException("offline", null, requestMayHaveBeenSent = false) }
    }

    private fun lose() {
        if (loseAnswers > 0) { loseAnswers--; throw NetworkException("connection reset", null, requestMayHaveBeenSent = true) }
    }

    private fun find(kind: RecordKind, id: Int) = records.firstOrNull { it.kind == kind && it.serverId == id }

    private fun snapshot(r: Rec): JsonObject = when (r.kind) {
        RecordKind.LZ -> JsonObject(mapOf("id" to JsonPrimitive(r.serverId), "name" to JsonPrimitive(r.name), "client_uuid" to JsonPrimitive(r.uuid),
            "revision" to JsonPrimitive(r.revision), "lz_data" to r.data))
        RecordKind.AIRCRAFT -> JsonObject(r.data + mapOf("id" to JsonPrimitive(r.serverId), "name" to JsonPrimitive(r.name),
            "client_uuid" to JsonPrimitive(r.uuid), "revision" to JsonPrimitive(r.revision)))
        RecordKind.ROUTE -> JsonObject(mapOf("id" to JsonPrimitive(r.serverId), "name" to JsonPrimitive(r.name), "kind" to JsonPrimitive("sketch"),
            "client_uuid" to JsonPrimitive(r.uuid), "revision" to JsonPrimitive(r.revision), "route_data" to r.data))
        RecordKind.POINT_SET -> JsonObject(mapOf("id" to JsonPrimitive(r.serverId), "name" to JsonPrimitive(r.name), "client_uuid" to JsonPrimitive(r.uuid),
            "revision" to JsonPrimitive(r.revision), "points" to (r.data["points"] ?: JsonArray(emptyList()))))
    }

    private fun conflict(r: Rec) = RevisionConflictException("The record changed on the server. Nothing was overwritten.", snapshot(r))

    override suspend fun create(record: LocalRecord, key: String): Remote {
        log += "create ${record.kind.name.lowercase()} ${record.uuid} $key"
        check("create"); onWrite?.invoke("create")
        failName?.let { (name, error) -> if (record.name == name) throw error }
        val existing = records.firstOrNull { it.kind == record.kind && it.uuid == record.uuid }
        if (existing != null) { lose(); return Remote(existing.serverId, existing.revision, created = false) }
        val r = Rec(nextId++, record.kind, record.uuid, 1, record.name, record.data, false, ++seq, key)
        records += r
        lose()
        return Remote(r.serverId, r.revision, created = true)
    }

    override suspend fun update(record: LocalRecord, baseRevision: Int, key: String): Remote {
        log += "update ${record.kind.name.lowercase()} ${record.serverId} base=$baseRevision $key"
        check("update"); onWrite?.invoke("update")
        failName?.let { (name, error) -> if (record.name == name) throw error }
        val r = find(record.kind, record.serverId!!)?.takeUnless { it.deleted } ?: throw ApiException(404, null, "Not found")
        if (r.lastKey == key) { lose(); return Remote(r.serverId, r.revision, false) }              // the same write again
        if (baseRevision != r.revision) throw conflict(r)
        r.name = record.name; r.data = record.data; r.revision++; r.seq = ++seq; r.lastKey = key
        lose()
        return Remote(r.serverId, r.revision, false)
    }

    override suspend fun delete(record: LocalRecord, baseRevision: Int?, key: String) {
        log += "delete ${record.kind.name.lowercase()} ${record.serverId} base=$baseRevision $key"
        check("delete"); onWrite?.invoke("delete")
        val r = find(record.kind, record.serverId!!) ?: throw ApiException(404, null, "Not found")
        if (r.deleted) { lose(); return }
        if (r.lastKey != key && baseRevision != null && baseRevision != r.revision) throw conflict(r)
        r.deleted = true; r.name = ""; r.data = JsonObject(emptyMap()); r.revision++; r.seq = ++seq; r.lastKey = key
        lose()
    }

    override suspend fun changes(since: Int): ChangeFeed {
        log += "changes $since"
        failAll?.let { throw it }
        val ours = records.filter { it.seq > since }.map {
            SyncChange(
                type = when (it.kind) { RecordKind.LZ -> "lz"; RecordKind.AIRCRAFT -> "aircraft"; RecordKind.ROUTE -> "route"; RecordKind.POINT_SET -> "pointset" },
                id = it.serverId, clientUuid = it.uuid, revision = it.revision, deleted = it.deleted, name = it.name,
                kind = if (it.kind == RecordKind.ROUTE) "sketch" else null,
                // The feed carries an LZ's diagram and a route's routes as they are, a profile as the whole record, and a point set's points as a bare list.
                seq = it.seq, data = feedData(it),
            )
        }
        val after = (ours + foreign.filter { it.seq > since }).sortedBy { it.seq }
        val page = after.take(pageSize)
        return ChangeFeed(cursor = page.lastOrNull()?.seq ?: since, hasMore = after.size > page.size, changes = feedTransform?.invoke(page) ?: page)
    }

    private fun feedData(r: Rec): JsonElement = when {
        r.kind == RecordKind.POINT_SET -> if (r.deleted) JsonArray(emptyList()) else r.data["points"] ?: JsonArray(emptyList())
        r.deleted -> JsonObject(emptyMap())
        r.kind == RecordKind.AIRCRAFT -> snapshot(r)
        else -> r.data
    }

    override fun copyFromConflict(kind: RecordKind, server: JsonObject): ServerCopy? = serverCopyOf(kind, server)

    // -- Somebody else (the web, another device) changing things --------------------------

    fun byUuid(kind: RecordKind, uuid: String): Rec = records.single { it.kind == kind && it.uuid == uuid }

    fun editElsewhere(kind: RecordKind, uuid: String, name: String? = null, data: JsonObject? = null) {
        val r = byUuid(kind, uuid)
        if (name != null) r.name = name
        if (data != null) r.data = data
        r.revision++; r.seq = ++seq; r.lastKey = null
    }

    fun deleteElsewhere(kind: RecordKind, uuid: String) {
        val r = byUuid(kind, uuid)
        r.deleted = true; r.name = ""; r.data = JsonObject(emptyMap()); r.revision++; r.seq = ++seq; r.lastKey = null
    }

    fun createElsewhere(kind: RecordKind, uuid: String, name: String, data: JsonObject): Rec {
        val r = Rec(nextId++, kind, uuid, 1, name, data, false, ++seq, null)
        records += r
        return r
    }

    fun live(kind: RecordKind): List<Rec> = records.filter { it.kind == kind && !it.deleted }
}
