package app.ezpztac.data

import app.ezpztac.formats.FormatException
import app.ezpztac.formats.LpsReader
import app.ezpztac.model.PointSet
import app.ezpztac.model.PointSets
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** One saved set of local points in a list: enough to show and pick it, without parsing its points. */
data class PointSetRow(
    /** The record's identity (its `client_uuid`): how the set is found. */
    val uuid: String,
    val name: String,
    /** How many points it holds, the ones this version cannot read included (they are kept, so they count). */
    val pointCount: Int,
    /** Whether the server has this version, is yet to be told, or the record is the copy kept beside a conflict. */
    val sync: SyncStatus,
    /** The record this is a conflict copy of, if it is one. */
    val conflictOf: String?,
)

/** A saved set with how it stands: synced or not, or the copy kept beside a conflict. */
data class StoredPointSet(val set: PointSet, val sync: SyncStatus, val conflictOf: String?)

/** What came of importing a file. */
sealed interface ImportOutcome {
    data class Imported(val set: PointSet) : ImportOutcome

    /** The file could not be read as local points; [message] is written for the person who chose it. */
    data class Refused(val message: String) : ImportOutcome
}

/**
 * The saved sets of local points on this device: made by importing an AMPS `.LPS` file, kept offline, synced when there is signal and shared with the
 * web. A set is stored as the list of points the web saves (`PointSets`), whole, so one made here opens on the web and back, and a field a newer release
 * adds to a point survives.
 *
 * A set with no points is never saved: the server refuses one, so it would wait in the outbox for good. The `.LPS` reader refuses a file with none, and a
 * set is deleted rather than emptied.
 */
@Singleton
class PointSetRepository @Inject constructor(
    private val sync: SyncRepository,
    private val feed: RecordFeed,
    private val scheduler: SyncScheduler,
) {
    /** The sets, as a list that updates itself (an import, a sync that brings one in, a delete). */
    fun observe(): Flow<List<PointSetRow>> = observeSets().map { stored ->
        stored.map { PointSetRow(it.set.id, it.set.name, it.set.pointCount, it.sync, it.conflictOf) }
    }

    /** The sets with their points, as they change. A record is parsed once for as long as its document does not change, not for every change to the list. */
    fun observeSets(): Flow<List<StoredPointSet>> = feed.observe(RecordKind.POINT_SET).map { records ->
        val stored = records.map { StoredPointSet(toSet(it), it.status, it.conflictOf) }
        synchronized(parsed) { parsed.keys.retainAll(records.mapTo(HashSet()) { it.uuid }) }                   // what is gone is not kept
        stored
    }

    /**
     * Reads [bytes], the `.LPS` file called [fileName], and saves what it holds as a new set named for the file. A file that is not local points is
     * refused with words for the person, and nothing is saved.
     */
    suspend fun import(bytes: ByteArray, fileName: String): ImportOutcome {
        val parsed = try {
            LpsReader.read(bytes, fileName)
        } catch (e: FormatException) {
            return ImportOutcome.Refused(e.message ?: "This doesn't look like an .LPS local points file.")
        }
        // The server refuses a set with no name; a file called ".lps" has none.
        val named = parsed.copy(name = parsed.name.trim().ifEmpty { DEFAULT_NAME })
        val uuid = java.util.UUID.randomUUID().toString()
        val set = PointSets.fromLps(uuid, named) { index -> "lps-$index-${randomSuffix()}" }
        sync.create(RecordKind.POINT_SET, set.name, PointSets.serialize(set), uuid = uuid)
        scheduler.requestSync()
        return ImportOutcome.Imported(set)
    }

    /** The set, or null if there is none (deleted, or never here). */
    suspend fun open(uuid: String): PointSet? = sync.record(RecordKind.POINT_SET, uuid)?.let(::toSet)

    /** Renames a set. A blank name is refused by the server, so it is not sent: false then. */
    suspend fun rename(uuid: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || sync.record(RecordKind.POINT_SET, uuid) == null) return false
        sync.edit(RecordKind.POINT_SET, uuid, name = trimmed)
        scheduler.requestSync()
        return true
    }

    suspend fun delete(uuid: String) {
        sync.delete(RecordKind.POINT_SET, uuid)
        scheduler.requestSync()
    }

    private class Parsed(val data: JsonObject, val set: PointSet)

    private val parsed = HashMap<String, Parsed>()

    /** A big set is thousands of points, and the list is rebuilt for every change to any record: the points are read again only when the document is not the one read last. */
    private fun toSet(record: LocalRecord): PointSet {
        val cached = synchronized(parsed) { parsed[record.uuid] }
        if (cached != null && cached.data == record.data) return cached.set.copy(savedId = record.serverId, name = record.name)
        val set = PointSets.parse(record.uuid, record.serverId, record.name, record.data)
        synchronized(parsed) { parsed[record.uuid] = Parsed(record.data, set) }
        return set
    }

    private fun randomSuffix(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(6)

    companion object {
        const val DEFAULT_NAME = "LOCAL POINTS"
    }
}
