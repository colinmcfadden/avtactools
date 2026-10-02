package app.ezpztac.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SyncDao {
    @Query("SELECT * FROM record WHERE kind = :kind AND uuid = :uuid")
    suspend fun record(kind: String, uuid: String): RecordEntity?

    @Query("SELECT * FROM record WHERE kind = :kind ORDER BY rowid")
    suspend fun records(kind: String): List<RecordEntity>

    /** What a list on screen shows: not deleted, and changing whenever any of it does. */
    @Query("SELECT * FROM record WHERE kind = :kind AND deleted = 0 ORDER BY rowid")
    fun observe(kind: String): Flow<List<RecordEntity>>

    /**
     * Insert or update **in place**. `REPLACE` would delete the row and insert a new one, which moves it to the end of the list
     * every time it is edited.
     */
    @Upsert
    suspend fun put(record: RecordEntity)

    @Query("DELETE FROM record WHERE kind = :kind AND uuid = :uuid")
    suspend fun remove(kind: String, uuid: String)

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun outbox(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE kind = :kind AND uuid = :uuid AND operation = :operation")
    suspend fun entryFor(kind: String, uuid: String, operation: String): OutboxEntity?

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
}
