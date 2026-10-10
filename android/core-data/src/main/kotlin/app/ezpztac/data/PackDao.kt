package app.ezpztac.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/**
 * The mission-pack tables. Order is always read from what the session keeps ([PackEntity.itemOrder], [PackOpEntity.localSeq],
 * [PackOpEntity.dropOrder]), never assumed from how rows happen to come back, except for the confirmed items themselves (by rowid),
 * whose order nothing reads.
 *
 * Rows are written with [Upsert], never `REPLACE`, which would delete a row and insert it again (SyncDao's note).
 */
@Dao
internal interface PackDao {
    @Query("SELECT * FROM pack WHERE uuid = :uuid")
    suspend fun pack(uuid: String): PackEntity?

    /**
     * A pack's items, each with its data only when that is at most [inline] characters long. A row is read through a cursor window of
     * 2 MB on a device, and one that does not fit cannot be read at all (`SQLiteBlobTooBigException`), while an item may be 5 MB: a
     * longer one's data is read in parts ([itemDataPart]).
     */
    @Query(
        "SELECT uuid, kind, name, deleted, info, length(data) AS size, CASE WHEN length(data) <= :inline THEN data END AS data " +
            "FROM pack_item WHERE packUuid = :pack ORDER BY rowid",
    )
    suspend fun items(pack: String, inline: Int): List<PackItemRead>

    /** [count] characters of an item's data from character [from] (counted from 1). */
    @Query("SELECT substr(data, :from, :count) FROM pack_item WHERE packUuid = :pack AND uuid = :uuid")
    suspend fun itemDataPart(pack: String, uuid: String, from: Int, count: Int): String?

    @Query("SELECT uuid FROM pack_item WHERE packUuid = :pack")
    suspend fun itemUuids(pack: String): List<String>

    /** A pack's edits in the order they were made, each operation read as [items] reads data: an `item.create` carries a whole item. */
    @Query(
        "SELECT clientOpId, state, reason, dropOrder, seq, length(op) AS size, CASE WHEN length(op) <= :inline THEN op END AS op " +
            "FROM pack_op_outbox WHERE packUuid = :pack ORDER BY localSeq",
    )
    suspend fun ops(pack: String, inline: Int): List<PackOpRead>

    @Query("SELECT substr(op, :from, :count) FROM pack_op_outbox WHERE packUuid = :pack AND clientOpId = :clientOpId")
    suspend fun opPart(pack: String, clientOpId: String, from: Int, count: Int): String?

    @Query("SELECT clientOpId, state, reason, dropOrder, seq FROM pack_op_outbox WHERE packUuid = :pack")
    suspend fun opPlaces(pack: String): List<PackOpPlace>

    @Query("SELECT own FROM pack_own WHERE packUuid = :pack AND itemUuid = :item")
    suspend fun own(pack: String, item: String): String?

    @Upsert
    suspend fun upsertPack(pack: PackEntity)

    @Upsert
    suspend fun upsertItem(item: PackItemEntity)

    @Query("DELETE FROM pack_item WHERE packUuid = :pack AND uuid IN (:uuids)")
    suspend fun deleteItems(pack: String, uuids: List<String>)

    @Insert
    suspend fun insertOp(op: PackOpEntity)

    @Query(
        "UPDATE pack_op_outbox SET state = :state, reason = :reason, dropOrder = :dropOrder, seq = :seq " +
            "WHERE packUuid = :pack AND clientOpId = :clientOpId",
    )
    suspend fun moveOp(pack: String, clientOpId: String, state: String, reason: String?, dropOrder: Long?, seq: Long?): Int

    @Query("DELETE FROM pack_op_outbox WHERE packUuid = :pack AND clientOpId IN (:clientOpIds)")
    suspend fun deleteOps(pack: String, clientOpIds: List<String>)

    @Upsert
    suspend fun upsertOwn(own: PackOwnEntity)

    @Query("UPDATE pack SET openedAt = :at WHERE uuid = :pack")
    suspend fun touch(pack: String, at: Long)

    /** The packs with edits of [me]'s not sent, or sent and not answered. */
    @Query("SELECT DISTINCT packUuid FROM pack_op_outbox WHERE userId = :me AND state IN ('QUEUED', 'SENT')")
    suspend fun owed(me: Int): List<String>

    @Query("SELECT DISTINCT packUuid FROM pack_op_outbox WHERE userId = :me AND state = 'DROPPED'")
    suspend fun withDropped(me: Int): List<String>

    /** [me]'s edits not taken by a pack: queued, sent, and refused ones not yet kept. A taken (acked) one is the pack's. */
    @Query("SELECT COUNT(*) FROM pack_op_outbox WHERE userId = :me AND state != 'ACKED'")
    suspend fun unsentCount(me: Int): Int

    /** The same for every account on the device: what would be lost by clearing it. */
    @Query("SELECT COUNT(*) FROM pack_op_outbox WHERE state != 'ACKED'")
    suspend fun unsentOrDropped(): Int

    /** [me]'s packs, the one opened last first; packs opened at the same moment in the order they were first stored. */
    @Query("SELECT uuid FROM pack WHERE userId = :me ORDER BY openedAt DESC, rowid")
    suspend fun byLastOpened(me: Int): List<String>

    @Query("SELECT COUNT(*) FROM pack_op_outbox WHERE packUuid = :pack")
    suspend fun opCount(pack: String): Int

    @Query("DELETE FROM pack WHERE uuid = :pack")
    suspend fun deletePack(pack: String)

    @Query("DELETE FROM pack_item WHERE packUuid = :pack")
    suspend fun deleteAllItems(pack: String)

    @Query("DELETE FROM pack_op_outbox WHERE packUuid = :pack")
    suspend fun deleteAllOps(pack: String)

    @Query("DELETE FROM pack_own WHERE packUuid = :pack")
    suspend fun deleteAllOwn(pack: String)

    /** Own fields of packs no longer held: written after their pack was forgotten. */
    @Query("DELETE FROM pack_own WHERE packUuid NOT IN (SELECT uuid FROM pack)")
    suspend fun deleteOwnWithoutPack()

    @Query("DELETE FROM pack")
    suspend fun wipePacks()

    @Query("DELETE FROM pack_item")
    suspend fun wipeItems()

    @Query("DELETE FROM pack_op_outbox")
    suspend fun wipeOps()

    @Query("DELETE FROM pack_own")
    suspend fun wipeOwn()
}
