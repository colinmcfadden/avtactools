package app.ezpztac.sync

import kotlinx.serialization.json.JsonObject

/**
 * The kinds of saved record that sync. A [ROUTE] record is a *set* of sketched routes under one name (the server's `kind: sketch` saved route). A
 * [POINT_SET] record is the points of one `.LPS` import; its document is `{"points": [...]}`, the server's list of points in an object so it is held
 * like every other record's document. A [MISSION] record is an imported AMPS mission (the server's `kind: mission` saved route): **its file is the document**
 * ([LocalRecord.file]), and the record's own `data` is only the display summary the server keeps beside it.
 */
public enum class RecordKind { LZ, AIRCRAFT, ROUTE, POINT_SET, MISSION }

/**
 * A file a record carries beside its document: an AMPS mission's `.msnx`. [id] is the SHA-256 of the file's bytes, so a file is kept once and can never change under
 * its name, which is what lets a send in progress and the record itself refer to the same bytes without copying them. The bytes are in the store ([SyncTransaction.blob]).
 */
public data class FileRef(val id: String, val name: String)

/**
 * One saved record on this device. Its identity is [uuid], chosen here when it is made (so it has one before the
 * server has seen it); the server's own id and revision arrive with its first answer.
 */
public data class LocalRecord(
    val kind: RecordKind,
    val uuid: String,
    /** The server's id, once it has one. */
    val serverId: Int?,
    /** The server revision this copy is based on: sent as `If-Match`, so an edit never overwrites a newer one. */
    val baseRevision: Int?,
    val name: String,
    /**
     * The document, as the server holds it (the LZ's diagram, the profile's fields). Kept whole and edited by merging, so a
     * field a newer web release adds survives an older app.
     */
    val data: JsonObject,
    /** Edits not yet confirmed by the server. */
    val dirty: Boolean = false,
    /** Deleted here, and the server not told yet (or not yet confirmed). Hidden from lists. */
    val deleted: Boolean = false,
    /** Counts local edits, so a push can tell whether the record changed while it was in flight. */
    val localVersion: Int = 0,
    /** Set on the copy kept beside a record after a conflict: the uuid of the record it was made from. */
    val conflictOf: String? = null,
    /** The file the record carries ([RecordKind.MISSION] only). */
    val file: FileRef? = null,
) {
    /** What to show beside the record. */
    val status: SyncStatus
        get() = when {
            conflictOf != null -> SyncStatus.CONFLICT
            dirty || deleted || serverId == null -> SyncStatus.PENDING
            else -> SyncStatus.SYNCED
        }
}

public enum class SyncStatus { SYNCED, PENDING, CONFLICT }

public enum class Operation { CREATE, UPDATE, DELETE }

/**
 * What was sent for an entry, exactly, kept until the server gives a definitive answer.
 *
 * A send whose answer is lost may have been applied. The only safe thing to do next is to send the same thing again, under the same
 * idempotency [key], which the server recognises as the same write and answers. Sending *newer* content under a new key on top of the
 * old revision would be refused as a conflict with the device's own earlier write. So the attempt is a snapshot: it is repeated as it
 * was, whatever the record has become since, and only once it is answered does the record's later content go.
 */
public data class Attempt(
    val key: String,
    /** The record's local version when this was made. A record at a later version has changed since. */
    val version: Int,
    val baseRevision: Int?,
    val name: String,
    val data: JsonObject,
    /** The file as it was when this was made: a snapshot like the rest, and immutable (it names bytes by their hash). */
    val file: FileRef? = null,
)

/**
 * A change waiting to go to the server. One per record per kind of change, kept in the order the changes were made.
 */
public data class OutboxEntry(
    val seq: Long,
    val kind: RecordKind,
    val uuid: String,
    val operation: Operation,
    /** The send in progress, if any: repeated as it is until the server answers. */
    val sent: Attempt? = null,
    /** Times this was sent without a final answer. Past zero, a CREATE may already have reached the server. */
    val attempts: Int = 0,
    val lastError: String? = null,
    /** The server refused this for good (not a conflict, not a network failure): the user has to look at it. */
    val blocked: Boolean = false,
)
