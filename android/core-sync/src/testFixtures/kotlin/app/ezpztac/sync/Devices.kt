package app.ezpztac.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset

/** Predictable identities and keys, so a test can say which one it means. */
public class SequentialIds(private val prefix: String) : IdSource {
    private var uuids = 0
    private var keys = 0
    override fun newUuid(): String = "$prefix-uuid-${++uuids}"
    override fun newKey(): String = "$prefix-key-${++keys}"
}

/** One device: its own store, its repository, and an engine talking to some server. */
public class Device(
    public val label: String,
    api: SyncApi,
    ids: IdSource = SequentialIds(label),
    /** Where this device keeps its records: memory by default, Room when the Room store is the one under test. */
    public val store: SyncStore = InMemorySyncStore(),
) {
    public val repository: SyncRepository = SyncRepository(store, ids)
    public val engine: SyncEngine = SyncEngine(api, store, ids, deviceLabel = label, now = { Instant.parse("2026-10-02T14:32:00Z") }, zone = ZoneOffset.UTC)

    public suspend fun sync(): SyncReport = engine.sync()

    /** Every record of a kind, including those hidden as deleted, by name. */
    public suspend fun names(kind: RecordKind): List<String> = repository.records(kind).map { it.name }.sorted()
    public suspend fun record(kind: RecordKind, uuid: String): LocalRecord? = repository.record(kind, uuid)
    public suspend fun outbox(): List<OutboxEntry> = store.transaction { outbox() }
}

public fun doc(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
    pairs.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) },
)
