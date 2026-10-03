package app.ezpztac.data

import app.ezpztac.model.RouteSet
import app.ezpztac.model.RouteSets
import app.ezpztac.model.SketchRoute
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One saved set of routes in a list: enough to show and pick it, without opening it. */
data class RouteSetSummary(
    /** The record's identity (its `client_uuid`): how the set is found. */
    val uuid: String,
    val name: String,
    /** How many routes it holds, the ones this version cannot read included (they are kept, so they count). */
    val routeCount: Int,
    /** Whether the server has this version, is yet to be told, or the record is the copy kept beside a conflict. */
    val sync: SyncStatus,
    /** The record this is a conflict copy of, if it is one. */
    val conflictOf: String?,
)

/**
 * The saved sets of sketched routes on this device: made, opened, changed and deleted offline, and synced when there is signal. A set is stored as the
 * JSON the web saves in a route's `route_data` (`RouteSets`), whole, so one made here opens on the web and back, and a field a newer release adds
 * survives.
 *
 * Only sketched routes are here. A saved *mission* (an imported `.msnx`, with its file) is not a record this device holds yet: the sync engine passes
 * over it, so it is never listed and never sent back without its file.
 */
@Singleton
class RouteRepository @Inject constructor(
    private val sync: SyncRepository,
    private val feed: RecordFeed,
    private val scheduler: SyncScheduler,
) {
    /** The sets, as a list that updates itself (a sync that brings one in, an edit, a delete). */
    fun observe(): Flow<List<RouteSetSummary>> = feed.observe(RecordKind.ROUTE).map { records -> records.map(::summarize) }

    /** A new set of [routes] named [name], queued for the server at once. */
    suspend fun create(name: String, routes: List<SketchRoute> = emptyList()): RouteSet {
        val uuid = java.util.UUID.randomUUID().toString()
        val set = RouteSet(id = uuid, name = name, routes = routes)
        sync.create(RecordKind.ROUTE, name, RouteSets.serialize(set), uuid = uuid)
        scheduler.requestSync()
        return set
    }

    /** The set, or null if there is none (deleted, or never here). */
    suspend fun open(uuid: String): RouteSet? = sync.record(RecordKind.ROUTE, uuid)?.let(::toSet)

    /**
     * Writes the set's changes to its record (the one named by [RouteSet.id]).
     *
     * Returns the set as it is now stored: the same one, unless its record was deleted (on another device, say) while it was open. Then the work is
     * kept as a new record under a new id and that is returned, because the old id is held by the server as a deletion and cannot be written again.
     * That an edit outlives a delete is the sync engine's own rule for records, and it applies here too.
     */
    suspend fun save(set: RouteSet): RouteSet {
        // A record that is deleted reads as absent here, whether the deletion came from another device or was made on this one and is yet to be sent.
        if (sync.record(RecordKind.ROUTE, set.id) == null) {
            val uuid = java.util.UUID.randomUUID().toString()
            val restored = set.copy(id = uuid, savedId = null)
            sync.create(RecordKind.ROUTE, restored.name, RouteSets.serialize(restored), uuid = uuid)
            scheduler.requestSync()
            return restored
        }
        sync.edit(RecordKind.ROUTE, set.id, name = set.name, data = RouteSets.serialize(set))
        scheduler.requestSync()
        return set
    }

    suspend fun rename(uuid: String, name: String) {
        val set = open(uuid) ?: return
        save(set.copy(name = name))
    }

    suspend fun delete(uuid: String) {
        sync.delete(RecordKind.ROUTE, uuid)
        scheduler.requestSync()
    }

    private fun toSet(record: LocalRecord): RouteSet = RouteSets.parse(record.uuid, record.serverId, record.name, record.data)

    private fun summarize(record: LocalRecord): RouteSetSummary {
        val set = toSet(record)
        return RouteSetSummary(
            uuid = record.uuid, name = record.name, routeCount = set.routes.size + set.unreadable.size, sync = record.status, conflictOf = record.conflictOf,
        )
    }
}
