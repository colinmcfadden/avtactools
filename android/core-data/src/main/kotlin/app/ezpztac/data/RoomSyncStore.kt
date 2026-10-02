package app.ezpztac.data

import androidx.room.withTransaction
import app.ezpztac.sync.Attempt
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.Operation
import app.ezpztac.sync.OutboxEntry
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [SyncStore] on Room. Each [transaction] is one SQLite transaction: it commits whole or, if the block throws, not at all, and
 * Room runs writers one at a time, so a change made while a sync is in the middle of one never sees half of it.
 */
public class RoomSyncStore internal constructor(private val database: EzpzDatabase) : SyncStore, RecordFeed {
    private val dao get() = database.syncDao()

    override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T =
        database.withTransaction { RoomTransaction(dao).block() }

    /**
     * The records of a kind that are not deleted, as a list that updates itself when any of them changes. Room re-runs the query when
     * *any* row of the table changes (another kind's, or a queued change), so equal lists are dropped: a screen only redraws for a change
     * it can see.
     */
    override fun observe(kind: RecordKind): Flow<List<LocalRecord>> =
        dao.observe(kind.name).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()
}

private class RoomTransaction(private val dao: SyncDao) : SyncTransaction {
    override suspend fun record(kind: RecordKind, uuid: String) = dao.record(kind.name, uuid)?.toModel()
    override suspend fun records(kind: RecordKind) = dao.records(kind.name).map { it.toModel() }
    override suspend fun put(record: LocalRecord) = dao.put(record.toEntity())
    override suspend fun remove(kind: RecordKind, uuid: String) = dao.remove(kind.name, uuid)

    override suspend fun outbox() = dao.outbox().map { it.toModel() }
    override suspend fun entryFor(kind: RecordKind, uuid: String, operation: Operation) =
        dao.entryFor(kind.name, uuid, operation.name)?.toModel()

    override suspend fun enqueue(entry: OutboxEntry): OutboxEntry {
        // 0 asks Room for the next number; it is never reused, even after the entry that held it is removed.
        val seq = dao.enqueue(entry.toEntity().copy(seq = 0))
        return entry.copy(seq = seq)
    }

    override suspend fun update(entry: OutboxEntry) {
        check(dao.update(entry.toEntity()) == 1) { "no such entry" }
    }

    override suspend fun dequeue(seq: Long) = dao.dequeue(seq)

    override suspend fun cursor(): Int = dao.state(CURSOR) ?: 0
    override suspend fun setCursor(cursor: Int) = dao.setState(SyncStateEntity(CURSOR, cursor))

    private companion object {
        const val CURSOR = "cursor"
    }
}

// The document is stored as text and read back with Json, which keeps a number exactly as it was written (14.0 stays 14.0).
private val json = Json

private fun JsonObject.toText(): String = json.encodeToString(JsonObject.serializer(), this)
private fun String.toObject(): JsonObject = json.decodeFromString(JsonObject.serializer(), this)

internal fun RecordEntity.toModel() = LocalRecord(
    kind = RecordKind.valueOf(kind),
    uuid = uuid,
    serverId = serverId,
    baseRevision = baseRevision,
    name = name,
    data = data.toObject(),
    dirty = dirty,
    deleted = deleted,
    localVersion = localVersion,
    conflictOf = conflictOf,
)

internal fun LocalRecord.toEntity() = RecordEntity(
    kind = kind.name,
    uuid = uuid,
    serverId = serverId,
    baseRevision = baseRevision,
    name = name,
    data = data.toText(),
    dirty = dirty,
    deleted = deleted,
    localVersion = localVersion,
    conflictOf = conflictOf,
)

internal fun OutboxEntity.toModel() = OutboxEntry(
    seq = seq,
    kind = RecordKind.valueOf(kind),
    uuid = uuid,
    operation = Operation.valueOf(operation),
    sent = if (sentKey == null) null else Attempt(
        key = sentKey,
        version = checkNotNull(sentVersion) { "an attempt without a version" },
        baseRevision = sentBaseRevision,
        name = checkNotNull(sentName) { "an attempt without a name" },
        data = checkNotNull(sentData) { "an attempt without a document" }.toObject(),
    ),
    attempts = attempts,
    lastError = lastError,
    blocked = blocked,
)

internal fun OutboxEntry.toEntity() = OutboxEntity(
    seq = seq,
    kind = kind.name,
    uuid = uuid,
    operation = operation.name,
    sentKey = sent?.key,
    sentVersion = sent?.version,
    sentBaseRevision = sent?.baseRevision,
    sentName = sent?.name,
    sentData = sent?.data?.toText(),
    attempts = attempts,
    lastError = lastError,
    blocked = blocked,
)
