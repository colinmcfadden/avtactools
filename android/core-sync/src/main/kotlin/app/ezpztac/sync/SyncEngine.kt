package app.ezpztac.sync

import app.ezpztac.network.AffiliationRequiredException
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.RevisionConflictException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SyncChange
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Why a sync stopped before it finished. The changes it had not sent are still queued. */
public sealed interface StopReason {
    /** No signal, or a dropped connection. Try again when there is. */
    public data object Offline : StopReason

    /** The session is over: the user must sign in. */
    public data class SessionEnded(val code: String?) : StopReason

    public data class RateLimited(val retryAfterSeconds: Long?) : StopReason

    /** Signed in, but not through the `.mil` / approval gate: nothing can sync yet. */
    public data object AffiliationRequired : StopReason

    /** The server failed (5xx). Try again later. */
    public data class ServerError(val status: Int) : StopReason
}

/** A record that conflicted: the server's copy stays in [original], and what was changed here is kept in [copy]. */
public data class Conflict(val kind: RecordKind, val original: String, val copy: String)

/** What a sync did. */
public data class SyncReport(
    val pushed: Int = 0,
    /** Changes read from the server and applied (or deliberately left alone) this time. */
    val pulled: Int = 0,
    val conflicts: List<Conflict> = emptyList(),
    /** Records deleted here that someone had edited elsewhere: the server's copy was restored. */
    val restored: List<String> = emptyList(),
    /** Records edited here that were deleted elsewhere: kept, under a new identity so the server can take them. old uuid → new uuid. */
    val recreated: Map<String, String> = emptyMap(),
    /** Entries the server refused for good, which the user has to look at. */
    val blocked: List<OutboxEntry> = emptyList(),
    val stopped: StopReason? = null,
    /** True when another sync was already running, so nothing was done. */
    val skipped: Boolean = false,
)

/**
 * Keeps the local records in step with the server (docs/NATIVE_APPS_PLAN.md, "Sync and conflicts").
 *
 * **Push** sends the outbox in order. A write is persisted *before* it is sent, with its idempotency key, so a crash or a
 * dropped connection leaves a repeat the server recognises rather than a second write. An update carries the revision it was
 * based on, and when the server has moved on, **nothing is overwritten**: the server's copy becomes the record and what was
 * changed here is kept beside it, as a record of its own, for the user to resolve. A planning document is hours of work.
 * **Pull** asks for everything after a cursor, deletions included, and never overwrites a record with changes of its own.
 *
 * Only one sync runs at a time; edits made while it runs are safe (each step is its own store transaction).
 */
public class SyncEngine(
    private val api: SyncApi,
    private val store: SyncStore,
    private val ids: IdSource = IdSource.Random,
    /** How a conflict copy says where it came from: "LZ HAWK (from Pixel 8, 14:32)". */
    private val deviceLabel: String = "this device",
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ConflictResolver {
    private val running = Mutex()

    private companion object {
        /** What a mission's file is called when the server does not say. */
        const val DEFAULT_MISSION_FILE = "mission.msnx"

        /** A page's files are held at most this much at once before the changes that bring them are applied. */
        const val FILE_GROUP_BYTES = 24L * 1024 * 1024
    }

    public suspend fun sync(): SyncReport {
        if (!running.tryLock()) return SyncReport(skipped = true)
        try {
            val pushed = push()
            // Offline or signed out: nothing more to learn, and the pull would fail the same way.
            if (pushed.stopped != null && pushed.stopped !is StopReason.ServerError) return pushed
            val pulled = pull()
            return pushed.copy(
                pulled = pulled.pulled,
                restored = pushed.restored + pulled.restored,
                recreated = pushed.recreated + pulled.recreated,
                stopped = pulled.stopped ?: pushed.stopped,
            )
        } finally {
            running.unlock()
        }
    }

    // -- Push ----------------------------------------------------------------------------

    private suspend fun push(): SyncReport {
        var report = SyncReport()
        val blockedRecords = HashSet<Pair<RecordKind, String>>()
        while (true) {
            val entry = store.transaction { outbox() }
                .firstOrNull { !it.blocked && (it.kind to it.uuid) !in blockedRecords } ?: break
            when (val outcome = pushOne(entry)) {
                is Pushed -> report = report.merge(outcome.report).copy(pushed = report.pushed + 1)
                is Failed -> {
                    return report.copy(stopped = outcome.reason, blocked = store.transaction { outbox() }.filter { it.blocked })
                }
                is Blocked -> blockedRecords += entry.kind to entry.uuid
            }
        }
        return report.copy(blocked = store.transaction { outbox() }.filter { it.blocked })
    }

    private fun SyncReport.merge(other: SyncReport) = copy(
        conflicts = conflicts + other.conflicts,
        restored = restored + other.restored,
        recreated = recreated + other.recreated,
    )

    private sealed interface Outcome
    private class Pushed(val report: SyncReport = SyncReport()) : Outcome
    private class Failed(val reason: StopReason) : Outcome
    private data object Blocked : Outcome

    private suspend fun pushOne(initial: OutboxEntry): Outcome {
        // Written down before it is sent. The attempt is a snapshot, repeated exactly until the server answers (see [Attempt]).
        val prepared = store.transaction {
            val record = record(initial.kind, initial.uuid)
            if (record == null) { dequeue(initial.seq); return@transaction null }
            val attempt = initial.sent ?: Attempt(ids.newKey(), record.localVersion, record.baseRevision, record.name, record.data, record.file)
            val counted = initial.copy(sent = attempt, attempts = initial.attempts + 1)
            update(counted)
            Triple(counted, record, attempt)
        } ?: return Pushed()
        val (entry, record, attempt) = prepared
        val payload = record.copy(name = attempt.name, data = attempt.data, file = attempt.file)
        // The file goes as the attempt had it, which the store still holds (nothing drops a file a send in progress refers to).
        val part = attempt.file?.let { ref ->
            val bytes = store.transaction { blob(ref.id) }
                ?: return block(entry, ApiException(400, "file_missing", "The file of this record is not on the device."))
            FilePart(ref.name, bytes)
        }

        // The request itself can be cancelled (the key and the attempt are already written, so a repeat is recognised). What is done
        // with the answer cannot: an answer taken and not recorded would leave this device and the server disagreeing.
        return try {
            when (entry.operation) {
                Operation.CREATE -> {
                    val remote = api.create(payload, attempt.key, part)
                    withContext(NonCancellable) { afterCreate(entry, attempt, remote) }
                }
                Operation.UPDATE -> {
                    val base = attempt.baseRevision
                    if (base == null) withContext(NonCancellable) { afterCreateFallback(entry, record) } else {
                        val remote = api.update(payload, base, attempt.key, part)
                        withContext(NonCancellable) { afterUpdate(entry, attempt, remote) }
                    }
                }
                Operation.DELETE -> {
                    api.delete(payload, attempt.baseRevision, attempt.key)
                    withContext(NonCancellable) { afterDelete(entry, record) }
                }
            }
        } catch (e: RevisionConflictException) {
            withContext(NonCancellable) { onConflict(entry, record, e) }
        } catch (e: NetworkException) {
            // A request that never left cannot have been applied, so it is not counted as an attempt: a record whose create has
            // not been sent can still be thrown away without a trace when it is deleted.
            if (!e.requestMayHaveBeenSent) withContext(NonCancellable) { uncount(entry) }
            Failed(StopReason.Offline)
        } catch (e: SessionEndedException) {
            withContext(NonCancellable) { uncount(entry) }
            Failed(StopReason.SessionEnded(e.code))
        } catch (e: RateLimitedException) {
            withContext(NonCancellable) { uncount(entry) }
            Failed(StopReason.RateLimited(e.retryAfterSeconds))
        } catch (e: AffiliationRequiredException) {
            withContext(NonCancellable) { uncount(entry) }
            Failed(StopReason.AffiliationRequired)
        } catch (e: ApiException) {
            when {
                // A create has no record on the server to be gone: a 404 there is a server (or address) that does not know the route, and re-creating the record under a
                // new identity would only be answered the same way, again and again, leaving a copy each time.
                e.status == 404 && entry.operation == Operation.CREATE -> {
                    withContext(NonCancellable) { uncount(entry) }
                    Failed(StopReason.ServerError(404))
                }
                e.status == 404 -> withContext(NonCancellable) { onGone(entry, record) }
                e.status >= 500 -> Failed(StopReason.ServerError(e.status))
                else -> withContext(NonCancellable) { block(entry, e) }
            }
        }
    }

    /**
     * Takes back the attempt counted before a send that turned out never to have been applied (it never left, or the server refused it
     * before looking at it). If no earlier send is still in doubt, the snapshot goes too, so the next try sends what the record is now.
     */
    private suspend fun uncount(entry: OutboxEntry) {
        store.transaction {
            val current = outbox().firstOrNull { it.seq == entry.seq } ?: return@transaction
            val remaining = maxOf(0, current.attempts - 1)
            update(current.copy(attempts = remaining, sent = if (remaining == 0) null else current.sent))
        }
    }

    private suspend fun afterCreate(entry: OutboxEntry, sent: Attempt, remote: Remote): Outcome {
        store.transaction {
            val current = record(entry.kind, entry.uuid)
            dequeue(entry.seq)
            if (current == null) return@transaction
            // What was sent is revision 1. A server that already had the record under this identity and is at revision 1 holds exactly
            // what was sent (this was a repeat of a send whose answer was lost). One further on has been edited since: the record
            // stays based on revision 1 and is sent over it, which the server answers with a conflict (kept side by side) rather than
            // an overwrite.
            val ahead = remote.revision != 1
            val changed = current.localVersion != sent.version
            put(current.copy(serverId = remote.serverId, baseRevision = 1, dirty = ahead || changed || current.deleted))
            if ((ahead || changed) && !current.deleted && entryFor(current.kind, current.uuid, Operation.UPDATE) == null) {
                enqueue(OutboxEntry(0, current.kind, current.uuid, Operation.UPDATE))
            }
        }
        return Pushed()
    }

    private suspend fun afterUpdate(entry: OutboxEntry, sent: Attempt, remote: Remote): Outcome {
        store.transaction {
            val current = record(entry.kind, entry.uuid)
            if (current == null) { dequeue(entry.seq); return@transaction }
            val changedSince = current.localVersion != sent.version
            put(current.copy(serverId = remote.serverId, baseRevision = remote.revision, dirty = changedSince || current.deleted))
            // Answered. If the record has changed since, what it has become is the next send, fresh: its own key, on the new revision.
            if (changedSince && !current.deleted) update(entry.copy(sent = null, attempts = 0)) else dequeue(entry.seq)
        }
        return Pushed()
    }

    /** An update for a record with no revision to base it on is really a create. */
    private suspend fun afterCreateFallback(entry: OutboxEntry, record: LocalRecord): Outcome {
        store.transaction {
            dequeue(entry.seq)
            if (entryFor(record.kind, record.uuid, Operation.CREATE) == null) enqueue(OutboxEntry(0, record.kind, record.uuid, Operation.CREATE))
        }
        return Pushed()
    }

    private suspend fun afterDelete(entry: OutboxEntry, sent: LocalRecord): Outcome {
        store.transaction {
            dequeue(entry.seq)
            record(sent.kind, sent.uuid)?.let { if (it.deleted) remove(it.kind, it.uuid) }
        }
        return Pushed()
    }

    /** The server refused an update or a delete because the record has changed there. Nothing was overwritten. */
    private suspend fun onConflict(entry: OutboxEntry, sent: LocalRecord, error: RevisionConflictException): Outcome {
        val theirs = api.copyFromConflict(sent.kind, error.server)
            ?: return block(entry, error)                                  // a conflict that does not say what the server has: leave it to the user
        // The server's copy of a mission is its file, which the 409 does not carry: it is fetched before anything is recorded, so a record is never left
        // holding the server's revision and our file. A failure here leaves the attempt as it is, and the next sync meets the same conflict again.
        var theirFile: FileRef? = null
        var theirBytes: ByteArray? = null
        if (sent.kind == RecordKind.MISSION && theirs.hasFile) {
            try {
                theirBytes = api.fetchFile(sent.kind, theirs.serverId)
                theirFile = FileRef(FileHash.of(theirBytes), theirs.fileName ?: sent.file?.name ?: DEFAULT_MISSION_FILE)
            } catch (e: NetworkException) {
                return Failed(StopReason.Offline)
            } catch (e: SessionEndedException) {
                return Failed(StopReason.SessionEnded(e.code))
            } catch (e: RateLimitedException) {
                return Failed(StopReason.RateLimited(e.retryAfterSeconds))
            } catch (e: AffiliationRequiredException) {
                return Failed(StopReason.AffiliationRequired)
            } catch (e: ApiException) {
                return if (e.status >= 500) Failed(StopReason.ServerError(e.status)) else block(entry, e)
            }
        }
        var report = SyncReport()
        store.transaction {
            val current = record(sent.kind, sent.uuid) ?: run { dequeue(entry.seq); return@transaction }
            if (theirBytes != null && theirFile != null) putBlob(theirFile.id, theirBytes)
            when (entry.operation) {
                Operation.DELETE -> {
                    // Deleted here, edited there: the edit wins, since a deletion is not worth more than somebody's work.
                    dequeue(entry.seq)
                    put(current.copy(serverId = theirs.serverId, baseRevision = theirs.revision, name = theirs.name, data = theirs.data, file = theirFile ?: current.file, deleted = false, dirty = false))
                    report = report.copy(restored = listOf(current.uuid))
                }
                else -> {
                    val copy = LocalRecord(
                        kind = current.kind, uuid = ids.newUuid(), serverId = null, baseRevision = null,
                        name = "${current.name} (from $deviceLabel, ${clock()})", data = current.data,
                        dirty = true, localVersion = 1, conflictOf = current.uuid, file = current.file,
                    )
                    put(copy)
                    enqueue(OutboxEntry(0, copy.kind, copy.uuid, Operation.CREATE))
                    // The record itself takes the server's copy: that is what everyone else has.
                    put(current.copy(serverId = theirs.serverId, baseRevision = theirs.revision, name = theirs.name, data = theirs.data, file = theirFile ?: current.file, dirty = false))
                    dequeue(entry.seq)
                    report = report.copy(conflicts = listOf(Conflict(current.kind, current.uuid, copy.uuid)))
                }
            }
        }
        return Pushed(report)
    }

    /**
     * 404: the record is not on the server. A delete is then already done. An edit is of a record someone deleted: the work is kept
     * and goes up again as a new record, under a new identity, because the server holds the old one as a deletion.
     */
    private suspend fun onGone(entry: OutboxEntry, sent: LocalRecord): Outcome {
        var report = SyncReport()
        store.transaction {
            val current = record(sent.kind, sent.uuid)
            dequeue(entry.seq)
            if (current == null) return@transaction
            if (entry.operation == Operation.DELETE || current.deleted) {
                remove(current.kind, current.uuid)
                return@transaction
            }
            val fresh = current.copy(uuid = ids.newUuid(), serverId = null, baseRevision = null, dirty = true, localVersion = 1)
            outbox().filter { it.kind == current.kind && it.uuid == current.uuid }.forEach { dequeue(it.seq) }
            remove(current.kind, current.uuid)
            put(fresh)
            enqueue(OutboxEntry(0, fresh.kind, fresh.uuid, Operation.CREATE))
            report = report.copy(recreated = mapOf(current.uuid to fresh.uuid))
        }
        return Pushed(report)
    }

    /** A refusal for good (not a conflict, not the network): this entry waits for the user, and the rest of the queue goes on. */
    private suspend fun block(entry: OutboxEntry, error: ApiException): Outcome {
        store.transaction {
            val current = outbox().firstOrNull { it.seq == entry.seq } ?: return@transaction
            update(current.copy(blocked = true, lastError = error.message, sent = null))
        }
        return Blocked
    }

    /**
     * Lets the entries the server refused for good be tried again (after the user has changed what it objected to, or the server
     * has been fixed). Returns how many were released.
     */
    public suspend fun retryBlocked(): Int = store.transaction {
        val blocked = outbox().filter { it.blocked }
        blocked.forEach { update(it.copy(blocked = false, lastError = null, sent = null)) }
        blocked.size
    }

    private fun clock(): String = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(now())

    // -- Pull ----------------------------------------------------------------------------

    private suspend fun pull(): SyncReport {
        var report = SyncReport()
        try {
            while (true) {
                val since = store.transaction { cursor() }
                val feed = api.changes(since)
                val applied = applyPage(feed.changes, feed.cursor)
                report = report.merge(applied).copy(pulled = report.pulled + feed.changes.size)
                if (!feed.hasMore) break
            }
        } catch (e: NetworkException) {
            return report.copy(stopped = StopReason.Offline)
        } catch (e: SessionEndedException) {
            return report.copy(stopped = StopReason.SessionEnded(e.code))
        } catch (e: RateLimitedException) {
            return report.copy(stopped = StopReason.RateLimited(e.retryAfterSeconds))
        } catch (e: AffiliationRequiredException) {
            return report.copy(stopped = StopReason.AffiliationRequired)
        } catch (e: ApiException) {
            if (e.status >= 500) return report.copy(stopped = StopReason.ServerError(e.status))
            throw e
        }
        return report
    }

    /**
     * Applies one page of the feed and moves the cursor past it. A mission's file is fetched (outside any transaction: a download is not something to hold the
     * database for) before the change that brings it is applied, and a page is applied in groups, so a first sync of many missions never holds all their files at
     * once. A crash between groups replays the page, which is harmless: a change already applied is at its revision and is neither fetched nor applied again.
     * The cursor moves only when the whole page is done.
     */
    private suspend fun applyPage(changes: List<SyncChange>, cursor: Int): SyncReport {
        var report = SyncReport()
        var group = ArrayList<SyncChange>()
        var files = HashMap<String, ByteArray>()
        var held = 0L
        suspend fun flush() {
            if (group.isEmpty()) return
            val batch = group
            val withFiles = files
            group = ArrayList(); files = HashMap(); held = 0
            report = report.merge(store.transaction {
                var step = SyncReport()
                for (change in batch) step = step.merge(apply(change, withFiles[change.clientUuid]))
                step
            })
        }
        for (change in changes) {
            if (needsFile(change)) {
                try {
                    val bytes = api.fetchFile(RecordKind.MISSION, change.id)
                    files[change.clientUuid] = bytes
                    held += bytes.size
                } catch (e: ApiException) {
                    // Gone since the feed was read (a later change will say so): there is nothing to apply. Anything else stops the pull, with the cursor where it was.
                    if (e.status != 404 || e is NetworkException) throw e
                }
            }
            group += change
            if (held >= FILE_GROUP_BYTES) flush()
        }
        flush()
        store.transaction { setCursor(cursor) }
        return report
    }

    /** True for a mission this device does not hold at this revision and would take: its file has to come with it. */
    private suspend fun needsFile(change: SyncChange): Boolean {
        if (change.type != "route" || change.kind != "mission" || change.deleted || !change.hasFile) return false
        val local = store.transaction { record(RecordKind.MISSION, change.clientUuid) } ?: return true
        return !local.dirty && !local.deleted && (local.baseRevision ?: -1) < change.revision
    }

    private suspend fun SyncTransaction.apply(change: SyncChange, file: ByteArray?): SyncReport {
        val kind = when (change.type) {
            "lz" -> RecordKind.LZ
            "aircraft" -> RecordKind.AIRCRAFT
            // A saved route is a set of sketched routes, or an AMPS mission with its file: the same table on the server, two kinds here.
            "route" -> if (change.kind == "mission") RecordKind.MISSION else RecordKind.ROUTE
            "pointset" -> RecordKind.POINT_SET
            else -> return SyncReport()                                    // a collection a newer server adds: not ours to read
        }
        val local = record(kind, change.clientUuid)

        if (change.deleted) {
            when {
                local == null -> Unit
                // Edited here and deleted there: the work is kept, as a new record the server can take.
                local.dirty && !local.deleted && local.serverId != null -> {
                    val fresh = local.copy(uuid = ids.newUuid(), serverId = null, baseRevision = null, dirty = true, localVersion = 1)
                    outbox().filter { it.kind == kind && it.uuid == local.uuid }.forEach { dequeue(it.seq) }
                    remove(kind, local.uuid)
                    put(fresh)
                    enqueue(OutboxEntry(0, kind, fresh.uuid, Operation.CREATE))
                    return SyncReport(recreated = mapOf(local.uuid to fresh.uuid))
                }
                // Not told yet of a record that was never sent, or already deleted here: nothing to do.
                local.serverId == null -> Unit
                else -> {
                    outbox().filter { it.kind == kind && it.uuid == local.uuid }.forEach { dequeue(it.seq) }
                    remove(kind, local.uuid)
                }
            }
            return SyncReport()
        }

        // A point set's points arrive as a bare list; every other document is an object already.
        val data = (if (kind == RecordKind.POINT_SET) pointSetDocument(change.data) else change.data as? JsonObject) ?: return SyncReport()
        // A mission is its file: without the file in hand (it was gone by the time it was fetched) there is nothing to hold.
        val ref = file?.let { bytes -> FileHash.of(bytes).also { id -> putBlob(id, bytes) }.let { id -> FileRef(id, change.fileName ?: DEFAULT_MISSION_FILE) } }
        val holdable = kind != RecordKind.MISSION || ref != null
        when {
            local == null -> if (holdable) put(LocalRecord(kind, change.clientUuid, change.id, change.revision, change.name, data, file = ref))
            // Changes here that the server has not seen: never overwritten by a pull. The push will find out (409) and keep both.
            local.dirty || local.deleted -> Unit
            (local.baseRevision ?: -1) < change.revision ->
                if (holdable) put(local.copy(serverId = change.id, baseRevision = change.revision, name = change.name, data = data, file = ref ?: local.file))
        }
        return SyncReport()
    }

    // -- Conflicts the user resolves ---------------------------------------------------------

    public enum class Resolution {
        /** Keep what was changed here: it replaces the server's copy (as an edit on top of it). */
        KEEP_MINE,

        /** Keep the server's copy and let what was changed here go. */
        KEEP_THEIRS,

        /** Keep both: the copy becomes an ordinary record beside the other. */
        KEEP_BOTH,
    }

    /** Settles a conflict kept beside a record, as the user chose. */
    override suspend fun resolve(kind: RecordKind, copyUuid: String, resolution: Resolution) {
        val repository = SyncRepository(store, ids)
        store.transaction {
            val copy = record(kind, copyUuid) ?: return@transaction
            val originalUuid = copy.conflictOf ?: return@transaction
            when (resolution) {
                Resolution.KEEP_BOTH -> put(copy.copy(conflictOf = null))
                Resolution.KEEP_THEIRS -> Unit
                Resolution.KEEP_MINE -> {
                    val original = record(kind, originalUuid)
                    if (original == null) put(copy.copy(conflictOf = null)) else {
                        // The copy's name carries "(from this device, 14:32)": mine means the original's own name with my content.
                        val edited = original.copy(data = copy.data, file = copy.file ?: original.file, dirty = true, localVersion = original.localVersion + 1)
                        put(edited)
                        if (original.serverId != null && entryFor(kind, originalUuid, Operation.UPDATE) == null) {
                            enqueue(OutboxEntry(0, kind, originalUuid, Operation.UPDATE))
                        }
                    }
                }
            }
        }
        if (resolution == Resolution.KEEP_THEIRS || resolution == Resolution.KEEP_MINE) {
            if (store.transaction { record(kind, copyUuid) }?.conflictOf != null) repository.delete(kind, copyUuid)
        }
    }
}
