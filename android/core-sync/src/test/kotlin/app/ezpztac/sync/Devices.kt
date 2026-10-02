package app.ezpztac.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset

/** Predictable identities and keys, so a test can say which one it means. */
internal class SequentialIds(private val prefix: String) : IdSource {
    private var uuids = 0
    private var keys = 0
    override fun newUuid(): String = "$prefix-uuid-${++uuids}"
    override fun newKey(): String = "$prefix-key-${++keys}"
}

/** One device: its own store, its repository, and an engine talking to some server. */
internal class Device(val label: String, api: SyncApi, ids: IdSource = SequentialIds(label)) {
    val store = InMemorySyncStore()
    val repository = SyncRepository(store, ids)
    val engine = SyncEngine(api, store, ids, deviceLabel = label, now = { Instant.parse("2026-10-02T14:32:00Z") }, zone = ZoneOffset.UTC)

    suspend fun sync(): SyncReport = engine.sync()

    /** Every record of a kind, including those hidden as deleted, by name. */
    suspend fun names(kind: RecordKind): List<String> = repository.records(kind).map { it.name }.sorted()
    suspend fun record(kind: RecordKind, uuid: String): LocalRecord? = repository.record(kind, uuid)
    suspend fun outbox(): List<OutboxEntry> = store.transaction { outbox() }
}

internal fun doc(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
    pairs.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) },
)
