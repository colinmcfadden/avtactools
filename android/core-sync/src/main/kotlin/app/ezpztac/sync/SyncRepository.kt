package app.ezpztac.sync

import kotlinx.serialization.json.JsonObject

/**
 * What the app does to its saved records, offline or not: make one, edit one, delete one. Each change is written to the local
 * store and, if the server has to be told, queued in the outbox. Nothing here waits for the network.
 */
public class SyncRepository(
    private val store: SyncStore,
    private val ids: IdSource = IdSource.Random,
) {
    /** The records of a kind that are not deleted, in the order the store holds them. */
    public suspend fun records(kind: RecordKind): List<LocalRecord> =
        store.transaction { records(kind) }.filterNot { it.deleted }

    public suspend fun record(kind: RecordKind, uuid: String): LocalRecord? =
        store.transaction { record(kind, uuid) }?.takeUnless { it.deleted }

    /** The bytes of the file a record carries, or null if it has none. */
    public suspend fun file(kind: RecordKind, uuid: String): ByteArray? = store.transaction {
        record(kind, uuid)?.takeUnless { it.deleted }?.file?.let { blob(it.id) }
    }

    /** Makes a record. It has its identity at once; the server hears of it at the next sync. A [file] is kept with it (a mission needs one). */
    public suspend fun create(kind: RecordKind, name: String, data: JsonObject, uuid: String = ids.newUuid(), file: FilePart? = null): LocalRecord =
        store.transaction {
            val ref = file?.let { keep(it) }
            val record = LocalRecord(kind, uuid, serverId = null, baseRevision = null, name = name, data = data, dirty = true, localVersion = 1, file = ref)
            put(record)
            enqueue(OutboxEntry(0, kind, uuid, Operation.CREATE))
            record
        }

    /** Changes a record. Only what is given changes. A record the server has not seen yet is simply updated in place. A [file] given replaces the record's. */
    public suspend fun edit(kind: RecordKind, uuid: String, name: String? = null, data: JsonObject? = null, file: FilePart? = null): LocalRecord =
        store.transaction {
            val current = requireNotNull(record(kind, uuid)?.takeUnless { it.deleted }) { "no such record" }
            val edited = current.copy(
                name = name ?: current.name,
                data = data ?: current.data,
                file = file?.let { keep(it) } ?: current.file,
                dirty = true,
                localVersion = current.localVersion + 1,
            )
            put(edited)
            // A record the server has never heard of is carried by its CREATE, which reads the record as it is when it is sent.
            if (current.serverId != null && entryFor(kind, uuid, Operation.UPDATE) == null) {
                enqueue(OutboxEntry(0, kind, uuid, Operation.UPDATE))
            }
            edited
        }

    /**
     * Deletes a record. If the server never saw it, it is simply gone. If it may have (a send was attempted and no answer
     * came back), the deletion is queued behind the create, so a record that did reach the server does not come back.
     */
    public suspend fun delete(kind: RecordKind, uuid: String) {
        store.transaction {
            val current = record(kind, uuid) ?: return@transaction
            val create = entryFor(kind, uuid, Operation.CREATE)
            // Never sent: no create, or one that was not sent, or one the server refused for good (it does not have the record).
            val neverSent = current.serverId == null && (create == null || create.attempts == 0 || create.blocked)
            if (neverSent) {
                outbox().filter { it.kind == kind && it.uuid == uuid }.forEach { dequeue(it.seq) }
                remove(kind, uuid)
                return@transaction
            }
            // An edit waiting to be sent is moot once the record is going away. One that was sent, and not answered, is not: it may have
            // been applied, and the delete has to go on top of what the server has, so the question is settled first.
            entryFor(kind, uuid, Operation.UPDATE)?.let { if (it.sent == null) dequeue(it.seq) }
            put(current.copy(deleted = true, dirty = true, localVersion = current.localVersion + 1))
            if (entryFor(kind, uuid, Operation.DELETE) == null) {
                enqueue(OutboxEntry(0, kind, uuid, Operation.DELETE))
            }
        }
    }

    /** Stores a file's bytes and says what to call it. */
    private suspend fun SyncTransaction.keep(file: FilePart): FileRef {
        val id = FileHash.of(file.bytes)
        putBlob(id, file.bytes)
        return FileRef(id, file.name)
    }

    /** How many changes are waiting to go. */
    public suspend fun pending(): Int = store.transaction { outbox().size }
}
