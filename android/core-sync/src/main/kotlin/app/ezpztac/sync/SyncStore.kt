package app.ezpztac.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where records, the outbox and the pull cursor live: SQLite through Room in the app, memory in tests. Each [transaction]
 * is atomic and excludes every other, so a change made while a sync is running never sees half of it.
 */
public interface SyncStore {
    public suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T
}

/**
 * A list of records that updates itself: what a screen shows. Separate from [SyncStore] because only a screen wants it, and a store
 * that cannot offer it (a test double) still works for the sync engine.
 */
public interface RecordFeed {
    /** The records of [kind] that are not deleted, in the order the store holds them, emitted again whenever any of them changes. */
    public fun observe(kind: RecordKind): Flow<List<LocalRecord>>
}

public interface SyncTransaction {
    public suspend fun record(kind: RecordKind, uuid: String): LocalRecord?
    public suspend fun records(kind: RecordKind): List<LocalRecord>
    public suspend fun put(record: LocalRecord)
    public suspend fun remove(kind: RecordKind, uuid: String)

    /** Every entry, oldest first. */
    public suspend fun outbox(): List<OutboxEntry>
    public suspend fun entryFor(kind: RecordKind, uuid: String, operation: Operation): OutboxEntry?
    /** Adds the entry, assigning its sequence number. */
    public suspend fun enqueue(entry: OutboxEntry): OutboxEntry
    public suspend fun update(entry: OutboxEntry)
    public suspend fun dequeue(seq: Long)

    public suspend fun cursor(): Int
    public suspend fun setCursor(cursor: Int)

    /** The bytes of a file, by [FileRef.id]; null if the store has none by that name. */
    public suspend fun blob(id: String): ByteArray?

    /**
     * Keeps [bytes] under [id], which must be [FileHash.of] the bytes. Putting what is already there is harmless. A file that no record and no send in progress refers to
     * is dropped when the transaction ends, so a replaced file does not stay on the device.
     */
    public suspend fun putBlob(id: String, bytes: ByteArray)
}

/** A store that lives in memory: for tests, and for the first version of the app before Room is wired in. */
public class InMemorySyncStore : SyncStore, RecordFeed {
    private val lock = Mutex()
    private val version = MutableStateFlow(0L)
    private val records = LinkedHashMap<Pair<RecordKind, String>, LocalRecord>()
    private val entries = LinkedHashMap<Long, OutboxEntry>()
    private val blobs = LinkedHashMap<String, ByteArray>()
    private var nextSeq = 1L
    private var cursor = 0

    /** How many transactions have run, for tests that want to see what was batched. */
    public var transactions: Int = 0
        private set

    private val tx = object : SyncTransaction {
        override suspend fun record(kind: RecordKind, uuid: String) = records[kind to uuid]
        override suspend fun records(kind: RecordKind) = records.values.filter { it.kind == kind }
        override suspend fun put(record: LocalRecord) { records[record.kind to record.uuid] = record }
        override suspend fun remove(kind: RecordKind, uuid: String) { records.remove(kind to uuid) }
        override suspend fun outbox() = entries.values.sortedBy { it.seq }
        override suspend fun entryFor(kind: RecordKind, uuid: String, operation: Operation) =
            entries.values.firstOrNull { it.kind == kind && it.uuid == uuid && it.operation == operation }
        override suspend fun enqueue(entry: OutboxEntry): OutboxEntry = entry.copy(seq = nextSeq++).also { entries[it.seq] = it }
        override suspend fun update(entry: OutboxEntry) { check(entry.seq in entries) { "no such entry" }; entries[entry.seq] = entry }
        override suspend fun dequeue(seq: Long) { entries.remove(seq) }
        override suspend fun cursor() = cursor
        override suspend fun setCursor(cursor: Int) { this@InMemorySyncStore.cursor = cursor }
        override suspend fun blob(id: String) = blobs[id]
        override suspend fun putBlob(id: String, bytes: ByteArray) { blobs[id] = bytes }
    }

    /** How many files the store holds, for tests of what is kept and what is let go. */
    public val blobCount: Int get() = blobs.size

    private fun dropUnreferencedBlobs() {
        val used = HashSet<String>()
        records.values.forEach { r -> r.file?.let { used += it.id } }
        entries.values.forEach { e -> e.sent?.file?.let { used += it.id } }
        blobs.keys.retainAll(used)
    }

    override fun observe(kind: RecordKind): Flow<List<LocalRecord>> = version
        .map { lock.withLock { records.values.filter { it.kind == kind && !it.deleted } } }
        .distinctUntilChanged()

    override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T = lock.withLock {
        transactions++
        // All or nothing: a block that throws leaves the store as it was.
        val savedRecords = LinkedHashMap(records)
        val savedEntries = LinkedHashMap(entries)
        val savedBlobs = LinkedHashMap(blobs)
        val savedSeq = nextSeq
        val savedCursor = cursor
        try {
            tx.block().also { dropUnreferencedBlobs(); version.value++ }
        } catch (e: Throwable) {
            records.clear(); records.putAll(savedRecords)
            entries.clear(); entries.putAll(savedEntries)
            blobs.clear(); blobs.putAll(savedBlobs)
            nextSeq = savedSeq; cursor = savedCursor
            throw e
        }
    }
}
