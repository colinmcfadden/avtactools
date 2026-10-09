package app.ezpztac.data

import androidx.room.withTransaction
import app.ezpztac.sync.Attempt
import app.ezpztac.sync.FileRef
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
        database.withTransaction {
            RoomTransaction(dao).block().also {
                // A file that a replaced record or a finished send used to hold is let go with the change that dropped it.
                dao.dropUnreferencedBlobs()
            }
        }

    /**
     * The records of a kind that are not deleted, as a list that updates itself when any of them changes. Room re-runs the query when
     * *any* row of the table changes (another kind's, or a queued change), so equal lists are dropped: a screen only redraws for a change
     * it can see.
     */
    override fun observe(kind: RecordKind): Flow<List<LocalRecord>> =
        dao.observe(kind.name, LongValues.TEXT_PART).map { rows ->
            if (rows.none { it.dataSize > LongValues.TEXT_PART }) {
                rows.map { it.record.toModel() }
            } else {
                // A document too long for one read is read again, in parts, in a transaction, so its parts are of one version. It may
                // have changed (or gone) since the list was read; the list that follows that change shows it.
                database.withTransaction {
                    val reading = RoomTransaction(dao)
                    rows.mapNotNull { row ->
                        if (row.dataSize > LongValues.TEXT_PART) reading.record(kind, row.record.uuid)?.takeUnless { it.deleted } else row.record.toModel()
                    }
                }
            }
        }.distinctUntilChanged()
}

private class RoomTransaction(private val dao: SyncDao) : SyncTransaction {
    override suspend fun record(kind: RecordKind, uuid: String) = dao.record(kind.name, uuid, LongValues.TEXT_PART)?.let { whole(it) }?.toModel()
    override suspend fun records(kind: RecordKind) = dao.records(kind.name, LongValues.TEXT_PART).map { whole(it).toModel() }
    override suspend fun put(record: LocalRecord) = dao.put(record.toEntity())
    override suspend fun remove(kind: RecordKind, uuid: String) = dao.remove(kind.name, uuid)

    override suspend fun outbox() = dao.outbox(LongValues.TEXT_PART).map { whole(it).toModel() }
    override suspend fun entryFor(kind: RecordKind, uuid: String, operation: Operation) =
        dao.entryFor(kind.name, uuid, operation.name, LongValues.TEXT_PART)?.let { whole(it) }?.toModel()

    // A record or a send whose document was too long to read with the row, with the document read in parts.
    private suspend fun whole(read: RecordRead): RecordEntity =
        if (read.dataSize <= LongValues.TEXT_PART) {
            read.record
        } else {
            read.record.copy(data = LongValues.text(read.dataSize) { from, count -> dao.recordDataPart(read.record.kind, read.record.uuid, from, count) })
        }

    private suspend fun whole(read: OutboxRead): OutboxEntity {
        val size = read.sentDataSize ?: return read.entry
        if (size <= LongValues.TEXT_PART) return read.entry
        return read.entry.copy(sentData = LongValues.text(size) { from, count -> dao.sentDataPart(read.entry.seq, from, count) })
    }

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

    override suspend fun blob(id: String): ByteArray? {
        val size = dao.blobSize(id) ?: return null
        // A mission's file can be larger than a cursor window: read in parts then.
        return if (size <= LongValues.BYTES_PART) dao.blob(id) else LongValues.bytes(size) { from, count -> dao.blobPart(id, from, count) }
    }
    override suspend fun putBlob(id: String, bytes: ByteArray) = dao.putBlob(BlobEntity(id, bytes))

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
    file = fileId?.let { FileRef(it, fileName ?: "") },
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
    fileId = file?.id,
    fileName = file?.name,
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
        file = sentFileId?.let { FileRef(it, sentFileName ?: "") },
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
    sentFileId = sent?.file?.id,
    sentFileName = sent?.file?.name,
    attempts = attempts,
    lastError = lastError,
    blocked = blocked,
)
