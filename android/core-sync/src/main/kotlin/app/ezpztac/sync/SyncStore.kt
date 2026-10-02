package app.ezpztac.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where records, the outbox and the pull cursor live: SQLite through Room in the app, memory in tests. Each [transaction]
 * is atomic and excludes every other, so a change made while a sync is running never sees half of it.
 */
public interface SyncStore {
    public suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T
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
}

/** A store that lives in memory: for tests, and for the first version of the app before Room is wired in. */
public class InMemorySyncStore : SyncStore {
    private val lock = Mutex()
    private val records = LinkedHashMap<Pair<RecordKind, String>, LocalRecord>()
    private val entries = LinkedHashMap<Long, OutboxEntry>()
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
    }

    override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T = lock.withLock {
        transactions++
        // All or nothing: a block that throws leaves the store as it was.
        val savedRecords = LinkedHashMap(records)
        val savedEntries = LinkedHashMap(entries)
        val savedSeq = nextSeq
        val savedCursor = cursor
        try {
            tx.block()
        } catch (e: Throwable) {
            records.clear(); records.putAll(savedRecords)
            entries.clear(); entries.putAll(savedEntries)
            nextSeq = savedSeq; cursor = savedCursor
            throw e
        }
    }
}
