package app.ezpztac.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One mission pack as this device holds it for one account ([userId]): what the server has confirmed of the pack itself, and how far
 * through its log the copy is. The items, the edits and each person's own fields are in the tables beside it. Every JSON column is the
 * text of what the session holds, written and read with a plain `Json`, so numbers and the order of keys come back as they were.
 *
 * Packs are kept apart from the library's records (docs/MISSION_PACKS.md): nothing here is in `record`, `outbox` or the sync cursor.
 */
@Entity(tableName = "pack")
internal data class PackEntity(
    @PrimaryKey val uuid: String,
    /** Whose copy this is. A copy is never read back for anyone else. */
    val userId: Int,
    /** The pack's GET body less its items and members, kept current by pack events (`PackSession.pack`). */
    val meta: String,
    /** A JSON array: the members as the server listed them. */
    val members: String,
    /** A JSON array: the live confirmed items' uuids, in the order they were added (`PackSession.order`). */
    val itemOrder: String,
    val seq: Long,
    val readOnly: Boolean,
    val gone: String?,
    val diverged: Boolean,
    /** When it was last opened (milliseconds), which pruning keeps the latest of. */
    val openedAt: Long,
)

/**
 * One confirmed item of a pack. A deleted one stays (as the session keeps it), so a uuid used again is refused as the server refuses it.
 * Can be megabytes: it is rewritten only when the session says it changed.
 */
@Entity(tableName = "pack_item", primaryKeys = ["packUuid", "uuid"])
internal data class PackItemEntity(
    val packUuid: String,
    val uuid: String,
    val kind: String,
    val name: String,
    /** The confirmed data, as JSON text (`null` for a deleted item, whose data the session no longer holds). */
    val data: String,
    val deleted: Boolean,
    /** The item's body less its data (`PackSession.info`); null for an item that is not live. */
    val info: String?,
)

/**
 * An edit of the account's to a pack, from the moment it is made until its event confirms it, or, if the pack would not take it, until it
 * is kept in the library or let go. [localSeq] is the order the edits were made in; [dropOrder] the order they were refused in, which is not
 * always the same (and the order the person's own version is rebuilt in).
 */
@Entity(
    tableName = "pack_op_outbox",
    indices = [Index(value = ["packUuid", "clientOpId"], unique = true), Index(value = ["packUuid", "state"])],
)
internal data class PackOpEntity(
    @PrimaryKey(autoGenerate = true) val localSeq: Long,
    val packUuid: String,
    val userId: Int,
    val clientOpId: String,
    /** The operation as it is sent: with its `client_op_id` and summary. Never changes once made. */
    val op: String,
    /** [PackOpState]'s name. */
    val state: String,
    /** Why the pack refused it; only a dropped one has one. */
    val reason: String?,
    val dropOrder: Long?,
    /** An acked edit's event number, as its result named it; null if it named none, or the edit is not acked. */
    val seq: Long?,
)

/** Where an edit stands, as [PackOpEntity.state] names it. */
internal enum class PackOpState { QUEUED, SENT, ACKED, DROPPED }

/** An item as it is read back: its data only if it is short enough to read in one go ([size] characters in all; [PackDao.items]). */
internal data class PackItemRead(
    val uuid: String,
    val kind: String,
    val name: String,
    val deleted: Boolean,
    val info: String?,
    val size: Int,
    val data: String?,
)

/** An edit as it is read back: its operation only if it is short enough to read in one go ([size] characters in all; [PackDao.ops]). */
internal data class PackOpRead(
    val clientOpId: String,
    val state: String,
    val reason: String?,
    val dropOrder: Long?,
    val seq: Long?,
    val size: Int,
    val op: String?,
)

/** An edit's place, without the operation itself (which can carry a whole item): what a write compares against. */
internal data class PackOpPlace(
    val clientOpId: String,
    val state: String,
    val reason: String?,
    val dropOrder: Long?,
    val seq: Long?,
)

/**
 * This person's own fields for one item of a pack, never sent: an LZ/PZ's view, the routes they hid. Kept for as long as the pack is, and
 * gone with it.
 */
@Entity(tableName = "pack_own", primaryKeys = ["packUuid", "itemUuid"])
internal data class PackOwnEntity(
    val packUuid: String,
    val itemUuid: String,
    /** A JSON object. */
    val own: String,
)
