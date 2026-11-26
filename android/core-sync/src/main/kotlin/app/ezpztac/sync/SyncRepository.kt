package app.ezpztac.sync

import kotlinx.serialization.json.JsonObject

/**
 * What the app does to its saved records, offline or not: make one, edit one, delete one. Each change is written to the local
 * store and, if the server has to be told, queued in the outbox. Nothing here waits for the network.
 */
public class SyncRepository(
    private val store: SyncStore,
    private val ids: IdSource = IdSource.Random,
    /** The time a change made here is noted at ([LocalRecord.updatedAt]). */
    private val clock: () -> String = { java.time.Instant.now().toString() },
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
            requireQueued(kind)
            val ref = file?.let { keep(it) }
            val record = LocalRecord(
                kind, uuid, serverId = null, baseRevision = null, name = name, data = data, dirty = true, localVersion = 1, file = ref, updatedAt = clock(),
            )
            put(record)
            enqueue(OutboxEntry(0, kind, uuid, Operation.CREATE))
            record
        }

    /** Changes a record. Only what is given changes. A record the server has not seen yet is simply updated in place. A [file] given replaces the record's. */
    public suspend fun edit(kind: RecordKind, uuid: String, name: String? = null, data: JsonObject? = null, file: FilePart? = null): LocalRecord =
        store.transaction {
            requireQueued(kind)
            val current = requireNotNull(record(kind, uuid)?.takeUnless { it.deleted }) { "no such record" }
            val edited = current.copy(
                name = name ?: current.name,
                data = data ?: current.data,
                file = file?.let { keep(it) } ?: current.file,
                dirty = true,
                localVersion = current.localVersion + 1,
                updatedAt = clock(),
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
            requireQueued(kind)
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

    /**
     * Holds what the server answered for a record changed online ([RecordKind.DOCUMENT]: an upload, a rename, a new version), as the next pull would bring
     * it: the record at the server's [revision], with nothing waiting. [data] null keeps the document held (a rename's answer has none); a record not held yet
     * needs one, and without it is left for the pull. An answer older than what is held changes nothing, so a pull that came first is never undone.
     */
    public suspend fun take(
        kind: RecordKind,
        uuid: String,
        serverId: Int,
        revision: Int,
        name: String,
        data: JsonObject?,
        updatedAt: String? = null,
    ) {
        require(kind == RecordKind.DOCUMENT) { "only a record changed online is taken from an answer" }
        store.transaction {
            val current = record(kind, uuid)
            when {
                current == null -> if (data != null) {
                    put(LocalRecord(kind, uuid, serverId, revision, name, data, updatedAt = updatedAt ?: clock()))
                }
                (current.baseRevision ?: -1) < revision -> put(
                    current.copy(serverId = serverId, baseRevision = revision, name = name, data = data ?: current.data, updatedAt = updatedAt ?: clock()),
                )
            }
        }
    }

    /** Lets go of a record the server has deleted at our asking ([RecordKind.DOCUMENT]), as its tombstone will when it comes. */
    public suspend fun forget(kind: RecordKind, uuid: String) {
        require(kind == RecordKind.DOCUMENT) { "only a record changed online is let go of without the outbox" }
        store.transaction { remove(kind, uuid) }
    }

    /** A document is never queued (docs/DOCUMENTS.md §6): one made or changed here would wait in the outbox for good, since no route takes its content. */
    private fun requireQueued(kind: RecordKind) = require(kind != RecordKind.DOCUMENT) { "a document is changed online, never queued" }

    /** Stores a file's bytes and says what to call it. */
    private suspend fun SyncTransaction.keep(file: FilePart): FileRef {
        val id = FileHash.of(file.bytes)
        putBlob(id, file.bytes)
        return FileRef(id, file.name)
    }

    /** How many changes are waiting to go. */
    public suspend fun pending(): Int = store.transaction { outbox().size }
}
