package app.ezpztac.data

import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.KeptVersion
import app.ezpztac.missionpacks.PackActions
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.Operation
import app.ezpztac.sync.OutboxEntry
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * [PackKeeper] on the library: the person's own version of an item a pack would not take their edits to, saved as a new record of their
 * own (already named `NAME (my edits)`), in the form the library keeps that kind, and queued for the server like any record made here.
 * Nothing a person made is lost silently (owner decision, 2026-10-08).
 *
 * A version's record is named for good by its key ([uuidFor]), so keeping it again (the app stopped between keeping it and letting the
 * edits go) finds the record and makes no second one, even if it has been deleted since and the deletion is still waiting to go. A record
 * gone for good (deleted before it was ever sent, or its deletion confirmed) cannot be found, and is made again: that takes a failure in
 * the moment between keeping and letting go, and the person deleting the copy before the next keep, which an app start does first.
 * Each is kept in a transaction of its own; the engine never calls this inside a pack transaction, so the two stores' transactions never
 * nest. A version can be as large as a pack item (5 MB, or more for edits refused as too large); the library reads it in parts.
 */
internal class LibraryPackKeeper(private val store: SyncStore) : PackKeeper {
    override suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome> = versions.map { keepOne(it) }

    private suspend fun keepOne(version: KeptVersion): KeptOutcome {
        val kind = KINDS[version.kind] ?: return KeptOutcome.NothingToKeep(version.key, "unknown_kind")
        val document = documentOf(kind, version)
            ?: return KeptOutcome.NothingToKeep(version.key, if (kind == RecordKind.POINT_SET) "empty_point_set" else "unreadable")
        val uuid = uuidFor(version.key)
        return store.transaction {
            val kept = record(kind, uuid)
            if (kept == null) {
                // SyncRepository.create's record, under the uuid the key names.
                put(LocalRecord(kind, uuid, serverId = null, baseRevision = null, name = version.name, data = document, dirty = true, localVersion = 1))
                enqueue(OutboxEntry(0, kind, uuid, Operation.CREATE))
            }
            KeptOutcome.Saved(version.key, version.kind, uuid, kept?.name ?: version.name)
        }
    }

    // The library's document for each kind: an LZ/PZ's as the pack holds it, a route set's sketched routes as {version: 1, routes}, and a
    // point set's points as {"points": [...]}, which is how the library holds every set. A set with no points cannot be saved (the server
    // refuses one), so there is nothing to keep. The pack holds an LZ/PZ or a route set as an object and a point set as a list, always
    // (PackOps refuses anything else).
    private fun documentOf(kind: RecordKind, version: KeptVersion): JsonObject? = when (kind) {
        RecordKind.POINT_SET -> (version.data as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { JsonObject(mapOf("points" to it)) }
        else -> PackActions.libraryData(version.kind, version.data) as? JsonObject
    }

    companion object {
        private val KINDS = mapOf("lz" to RecordKind.LZ, "route" to RecordKind.ROUTE, "pointset" to RecordKind.POINT_SET)

        /** The library record a kept version is saved as, named by its key so that keeping it twice makes one record. */
        fun uuidFor(key: String): String = UUID.nameUUIDFromBytes("ezpz-pack-kept:$key".toByteArray(Charsets.UTF_8)).toString()
    }
}
