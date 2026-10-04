package app.ezpztac.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved record of any synced kind (an LZ diagram, a custom aircraft profile; routes and point sets join them).
 *
 * The plan names a table per domain; the sync engine treats every kind the same way, so they share one table keyed by
 * (kind, uuid) until a screen needs a column of its own. The document is kept as the JSON the web saves, whole, so a field a
 * newer release adds is never dropped.
 */
@Entity(
    tableName = "record",
    primaryKeys = ["kind", "uuid"],
    indices = [Index(value = ["kind", "deleted"])],
)
internal data class RecordEntity(
    val kind: String,
    val uuid: String,
    val serverId: Int?,
    val baseRevision: Int?,
    val name: String,
    /** The document: a JSON object, as text. */
    val data: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val localVersion: Int,
    val conflictOf: String?,
    /** The file the record carries (a mission's `.msnx`): its hash, which names its bytes in [BlobEntity], and the name it is sent under. */
    val fileId: String? = null,
    val fileName: String? = null,
)

/**
 * A change waiting to go to the server. One per record per kind of change (the unique index), kept in the order the changes were made
 * (the sequence number only ever grows, so it is never reused even after an entry is removed).
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["kind", "uuid", "operation"], unique = true)],
)
internal data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long,
    val kind: String,
    val uuid: String,
    val operation: String,
    // The send in progress, if there is one, exactly as it went (see Attempt): repeated as it is until the server answers.
    val sentKey: String?,
    val sentVersion: Int?,
    val sentBaseRevision: Int?,
    val sentName: String?,
    val sentData: String?,
    val sentFileId: String? = null,
    val sentFileName: String? = null,
    val attempts: Int,
    val lastError: String?,
    val blocked: Boolean,
)

/**
 * The bytes of a file a record carries, named by their SHA-256. Nothing is ever updated in place (the name is the content), and a row no record and no send in progress
 * refers to is deleted at the end of every transaction ([SyncDao.dropUnreferencedBlobs]).
 */
@Entity(tableName = "blob")
internal data class BlobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val bytes: ByteArray,
)

/** Small facts the sync engine keeps between runs: today, how far it has read the server's change feed. */
@Entity(tableName = "sync_state")
internal data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: Int,
)
