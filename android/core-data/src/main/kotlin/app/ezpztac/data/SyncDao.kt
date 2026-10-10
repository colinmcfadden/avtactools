package app.ezpztac.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every query that reads a document, or a send's document, gives it only when it is at most `:inline` characters long, and its length
 * beside it: a longer one is read in parts ([recordDataPart], [sentDataPart]), because a row over the 2 MB cursor window cannot be read at
 * all ([LongValues]). The same for a file's bytes ([blobSize], [blobPart]).
 */
@Dao
internal interface SyncDao {
    @Query("SELECT $RECORD_COLUMNS FROM record WHERE kind = :kind AND uuid = :uuid")
    suspend fun record(kind: String, uuid: String, inline: Int): RecordRead?

    @Query("SELECT $RECORD_COLUMNS FROM record WHERE kind = :kind ORDER BY rowid")
    suspend fun records(kind: String, inline: Int): List<RecordRead>

    /** What a list on screen shows: not deleted, and changing whenever any of it does. */
    @Query("SELECT $RECORD_COLUMNS FROM record WHERE kind = :kind AND deleted = 0 ORDER BY rowid")
    fun observe(kind: String, inline: Int): Flow<List<RecordRead>>

    /** [count] characters of a record's document from character [from] (counted from 1). */
    @Query("SELECT substr(data, :from, :count) FROM record WHERE kind = :kind AND uuid = :uuid")
    suspend fun recordDataPart(kind: String, uuid: String, from: Int, count: Int): String?

    /**
     * Insert or update **in place**. `REPLACE` would delete the row and insert a new one, which moves it to the end of the list
     * every time it is edited.
     */
    @Upsert
    suspend fun put(record: RecordEntity)

    @Query("DELETE FROM record WHERE kind = :kind AND uuid = :uuid")
    suspend fun remove(kind: String, uuid: String)

    @Query("SELECT $OUTBOX_COLUMNS FROM outbox ORDER BY seq")
    suspend fun outbox(inline: Int): List<OutboxRead>

    @Query("SELECT $OUTBOX_COLUMNS FROM outbox WHERE kind = :kind AND uuid = :uuid AND operation = :operation")
    suspend fun entryFor(kind: String, uuid: String, operation: String, inline: Int): OutboxRead?

    /** [count] characters of a send's document from character [from] (counted from 1). */
    @Query("SELECT substr(sentData, :from, :count) FROM outbox WHERE seq = :seq")
    suspend fun sentDataPart(seq: Long, from: Int, count: Int): String?

    /** Returns the sequence number the entry was given. */
    @Insert
    suspend fun enqueue(entry: OutboxEntity): Long

    @Update
    suspend fun update(entry: OutboxEntity): Int

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun dequeue(seq: Long)

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun state(key: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun clearState(key: String)

    @Query("SELECT bytes FROM blob WHERE id = :id")
    suspend fun blob(id: String): ByteArray?

    /** A file's length in bytes, or null if there is no such file. */
    @Query("SELECT length(bytes) FROM blob WHERE id = :id")
    suspend fun blobSize(id: String): Int?

    /** [count] bytes of a file from byte [from] (counted from 1). */
    @Query("SELECT substr(bytes, :from, :count) FROM blob WHERE id = :id")
    suspend fun blobPart(id: String, from: Int, count: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putBlob(blob: BlobEntity)

    /** A file nothing refers to: not a record's, and not one a send in progress is carrying. */
    @Query(
        "DELETE FROM blob WHERE id NOT IN (SELECT fileId FROM record WHERE fileId IS NOT NULL) " +
            "AND id NOT IN (SELECT sentFileId FROM outbox WHERE sentFileId IS NOT NULL)",
    )
    suspend fun dropUnreferencedBlobs()

    @Query("DELETE FROM blob")
    suspend fun wipeBlobs()

    @Query("DELETE FROM record")
    suspend fun wipeRecords()

    @Query("DELETE FROM outbox")
    suspend fun wipeOutbox()

    @Query("DELETE FROM sync_state")
    suspend fun wipeState()

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun outboxSize(): Int
}

// A record's columns as a read gives them: the document empty when it is longer than `:inline`, with its length in `dataSize`.
private const val RECORD_COLUMNS =
    "kind, uuid, serverId, baseRevision, name, CASE WHEN length(data) <= :inline THEN data ELSE '' END AS data, dirty, deleted, " +
        "localVersion, conflictOf, fileId, fileName, length(data) AS dataSize"

// An outbox entry's, the same for the send's document (null when there is none, or it is longer than `:inline`; its length in `sentDataSize`).
private const val OUTBOX_COLUMNS =
    "seq, kind, uuid, operation, sentKey, sentVersion, sentBaseRevision, sentName, " +
        "CASE WHEN length(sentData) <= :inline THEN sentData END AS sentData, sentFileId, sentFileName, attempts, lastError, blocked, " +
        "length(sentData) AS sentDataSize"

/** A record as it is read: [record]'s document is empty when it is [dataSize] characters, longer than a read gives whole. */
internal data class RecordRead(@Embedded val record: RecordEntity, val dataSize: Int)

/** An outbox entry as it is read: [entry]'s send has no document when it is [sentDataSize] characters, longer than a read gives whole. */
internal data class OutboxRead(@Embedded val entry: OutboxEntity, val sentDataSize: Int?)
