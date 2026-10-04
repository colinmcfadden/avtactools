package app.ezpztac.data

import app.ezpztac.formats.MsnxMutator
import app.ezpztac.formats.MsnxReader
import app.ezpztac.model.MissionLink
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RouteSet
import app.ezpztac.model.RouteSets
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.MissionRoutes
import app.ezpztac.sync.FilePart
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

/** One saved set of routes, or one imported mission, in a list: enough to show and pick it, without opening it. */
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
    /** An imported AMPS mission (its file is what is saved), not a set drawn here. */
    val isMission: Boolean = false,
)

/**
 * The saved sets of routes on this device, and the AMPS missions the person imported: made, opened, changed and deleted offline, and synced when there is signal.
 *
 * **A set** is stored as the JSON the web saves in a route's `route_data` (`RouteSets`), whole, so one made here opens on the web and back, and a field a newer release adds
 * survives.
 *
 * **A mission** is stored as its `.msnx` file, which is the document, exactly as the web keeps one (`kind: mission`): the file is read into a [RouteSet] when it is opened, and
 * what the person changed (points moved, renamed or added; the plan) is written back into the file when it is saved ([MsnxMutator]), so everything AMPS keeps in it that this app
 * never reads survives. The record's own JSON is only the summary the server keeps for lists. Both are opened, saved and listed here, and an open mission is a [RouteSet] with
 * [RouteSet.mission] set.
 *
 * While a mission is open its file is held in memory, because that is what the edits are written onto, and because a record deleted on another device while it is open must still
 * be kept as a new one (the file went with the record). It is let go when the mission is closed ([release]) and at sign-out.
 */
@Singleton
class RouteRepository(
    private val sync: SyncRepository,
    private val feed: RecordFeed,
    private val scheduler: SyncScheduler,
    /** Where a mission's file is read and written: the work of the whole file, so not the main thread. A test gives it its own, so it can wait for the work. */
    private val heavy: CoroutineDispatcher,
) {
    @Inject
    constructor(sync: SyncRepository, feed: RecordFeed, scheduler: SyncScheduler) : this(sync, feed, scheduler, Dispatchers.Default)

    /**
     * What is held of the one open mission: its file as stored, and the routes the file was last written for, so a save that changes nothing in the file writes none
     * ([written] is null when that is not known, and the file is then rewritten).
     */
    private class OpenMission(val uuid: String, val bytes: ByteArray, val written: List<MissionRoute>?)

    @Volatile
    private var openMission: OpenMission? = null

    /** The sets and missions, as a list that updates itself (a sync that brings one in, an edit, a delete). */
    fun observe(): Flow<List<RouteSetSummary>> =
        combine(feed.observe(RecordKind.ROUTE), feed.observe(RecordKind.MISSION)) { sets, missions -> sets.map(::summarize) + missions.map(::summarizeMission) }

    /** A new set of [routes] named [name], queued for the server at once. */
    suspend fun create(name: String, routes: List<SketchRoute> = emptyList()): RouteSet {
        val uuid = java.util.UUID.randomUUID().toString()
        val set = RouteSet(id = uuid, name = name, routes = routes)
        sync.create(RecordKind.ROUTE, name, RouteSets.serialize(set), uuid = uuid)
        scheduler.requestSync()
        return set
    }

    /**
     * Keeps an imported mission: [bytes] is its file and [set] the routes read out of it (so the file is not read twice). The mission is queued for the server with its file at
     * once, as the web saves one. The file is held as it came; nothing is written into it until the person changes something.
     */
    suspend fun createMission(set: RouteSet, fileName: String, bytes: ByteArray): RouteSet {
        val kept = set.copy(mission = MissionLink(fileName))
        sync.create(RecordKind.MISSION, kept.name, RouteSets.missionSummary(kept.routes), uuid = kept.id, file = FilePart(fileName, bytes))
        openMission = OpenMission(kept.id, bytes, MissionRoutes.toMissionRoutes(kept.routes))
        scheduler.requestSync()
        return kept
    }

    /** The set or mission, or null if there is none (deleted, or never here). A mission whose file cannot be read is a [app.ezpztac.formats.MsnxException]. */
    suspend fun open(uuid: String): RouteSet? {
        sync.record(RecordKind.ROUTE, uuid)?.let { return toSet(it) }
        val record = sync.record(RecordKind.MISSION, uuid) ?: return null
        return toMission(record)
    }

    /**
     * Writes the set's changes to its record (the one named by [RouteSet.id]).
     *
     * Returns the set as it is now stored: the same one, unless its record was deleted (on another device, say) while it was open. Then the work is
     * kept as a new record under a new id and that is returned, because the old id is held by the server as a deletion and cannot be written again.
     * That an edit outlives a delete is the sync engine's own rule for records, and it applies here too.
     */
    suspend fun save(set: RouteSet): RouteSet {
        if (set.mission != null) return saveMission(set)
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
        if (sync.record(RecordKind.MISSION, uuid) != null) {
            // Only the name: the file is not read, and not rewritten.
            sync.edit(RecordKind.MISSION, uuid, name = name)
            scheduler.requestSync()
            return
        }
        val set = open(uuid) ?: return
        save(set.copy(name = name))
    }

    suspend fun delete(uuid: String) {
        val kind = if (sync.record(RecordKind.MISSION, uuid) != null) RecordKind.MISSION else RecordKind.ROUTE
        sync.delete(kind, uuid)
        if (openMission?.uuid == uuid) openMission = null
        scheduler.requestSync()
    }

    /** The `.msnx` a mission is, as it is stored now (the edits already saved written into it), or null if the mission or its file is not here. */
    suspend fun missionFile(uuid: String): ByteArray? = sync.file(RecordKind.MISSION, uuid)

    /** Lets go of the file held for the open mission (the mission was closed, or the person signed out). */
    fun release() {
        openMission = null
    }

    // -- A mission's file ------------------------------------------------------------------------------------------------

    private suspend fun toMission(record: LocalRecord): RouteSet {
        val file = record.file ?: throw app.ezpztac.formats.MsnxException("This mission has no file on this device yet.")
        val bytes = sync.file(RecordKind.MISSION, record.uuid) ?: throw app.ezpztac.formats.MsnxException("This mission's file is not on this device.")
        // Reading it is the work of the whole file (the part that is most of it is scanned, not built): off the main thread.
        val mission = withContext(heavy) { MsnxReader.read(bytes) }
        var n = 0
        val routes = MissionRoutes.toMissionSketchRoutes(mission, RouteSets.missionColors(record.data)) { "mission-${record.uuid.take(8)}-${n++}" }
        openMission = OpenMission(record.uuid, bytes, MissionRoutes.toMissionRoutes(routes))
        return RouteSet(id = record.uuid, savedId = record.serverId, name = record.name, routes = routes, mission = MissionLink(file.name))
    }

    /**
     * Saves an open mission: what the person changed is written into the file it came from, and the new file is the record's. A mission whose routes would write the file it
     * already is (a rename, a colour, hiding a route) is saved without touching the file.
     */
    private suspend fun saveMission(set: RouteSet): RouteSet {
        val link = requireNotNull(set.mission)
        val record = sync.record(RecordKind.MISSION, set.id)
        val held = openMission?.takeIf { it.uuid == set.id }
            ?: (record?.let { sync.file(RecordKind.MISSION, set.id) }?.let { OpenMission(set.id, it, written = null) })
            ?: throw app.ezpztac.formats.MsnxException("This mission's file is not on this device, so the changes cannot be saved.")
        val current = MissionRoutes.toMissionRoutes(set.routes)
        val changed = held.written == null || !MissionRoutes.sameFileContent(held.written, current)
        val bytes = if (changed) withContext(heavy) { MsnxMutator.rewrite(held.bytes, current) } else held.bytes
        val summary = RouteSets.missionSummary(set.routes)

        if (record == null) {
            // Deleted on another device while it was open: the work is kept as a new mission, under a new identity, with the file as it is now.
            val uuid = java.util.UUID.randomUUID().toString()
            sync.create(RecordKind.MISSION, set.name, summary, uuid = uuid, file = FilePart(link.fileName, bytes))
            openMission = OpenMission(uuid, bytes, current)
            scheduler.requestSync()
            return set.copy(id = uuid, savedId = null)
        }
        sync.edit(RecordKind.MISSION, set.id, name = set.name, data = summary, file = if (changed) FilePart(record.file?.name ?: link.fileName, bytes) else null)
        openMission = OpenMission(set.id, bytes, current)
        scheduler.requestSync()
        return set
    }

    // -- Lists -----------------------------------------------------------------------------------------------------------

    private fun toSet(record: LocalRecord): RouteSet = RouteSets.parse(record.uuid, record.serverId, record.name, record.data)

    private fun summarize(record: LocalRecord): RouteSetSummary {
        val set = toSet(record)
        return RouteSetSummary(
            uuid = record.uuid, name = record.name, routeCount = set.routes.size + set.unreadable.size, sync = record.status, conflictOf = record.conflictOf,
        )
    }

    /** A mission's count comes from the summary kept beside its file: the file is not read to draw a list. */
    private fun summarizeMission(record: LocalRecord): RouteSetSummary = RouteSetSummary(
        uuid = record.uuid, name = record.name, routeCount = (record.data["routes"] as? JsonArray)?.size ?: 0, sync = record.status, conflictOf = record.conflictOf,
        isMission = true,
    )
}
