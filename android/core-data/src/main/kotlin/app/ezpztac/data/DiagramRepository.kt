package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** One diagram in a list: enough to show and pick it, without opening it. */
data class DiagramSummary(
    /** The record's identity (its `client_uuid`): how the diagram is found, whatever the document inside calls itself. */
    val uuid: String,
    val name: String,
    val status: DiagramStatus,
    val target: DiagramTarget?,
    /** Whether the server has this version, is yet to be told, or the record is the copy kept beside a conflict. */
    val sync: SyncStatus,
    /** The record this is a conflict copy of, if it is one. */
    val conflictOf: String?,
    val updatedAt: String?,
)

/**
 * The LZ/PZ diagrams on this device: made, opened, changed and deleted offline, and synced when there is signal. A diagram is stored as the
 * JSON the web saves (schema 2), whole, so one made here opens on the web and back, and a field a newer release adds survives (the
 * graphics are copied without being looked into).
 *
 * The record's identity is [LocalRecord.uuid]. The document carries its own `id` from wherever it was made; here it is made the same as
 * the uuid when opened, so a diagram has one name in the app.
 */
@Singleton
class DiagramRepository @Inject constructor(
    private val sync: SyncRepository,
    private val feed: RecordFeed,
    private val scheduler: SyncScheduler,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val env = DiagramNormalizer.Environment()

    /** The diagrams, as a list that updates itself (a sync that brings one in, an edit, a delete). */
    fun observe(): Flow<List<DiagramSummary>> = feed.observe(RecordKind.LZ).map { records -> records.map(::summarize) }

    /**
     * A new diagram bound to [target]: blank, with no graphics (defaults are made only once analysis has succeeded for this diagram).
     * It is queued for the server at once.
     */
    suspend fun create(target: DiagramTarget, name: String): Diagram {
        val uuid = java.util.UUID.randomUUID().toString()
        val diagram = checkNotNull(
            DiagramNormalizer.fromTarget(
                target = json.encodeToJsonElement(DiagramTarget.serializer(), target), mgrs = target.mgrs, id = uuid, name = name, env = env,
            ),
        ) { "the target is not a position" }
        sync.create(RecordKind.LZ, name, serialize(diagram), uuid = uuid)
        scheduler.requestSync()
        return diagram
    }

    /** The diagram, normalized as the web would, or null if there is none (deleted, or never here). */
    suspend fun open(uuid: String): Diagram? = sync.record(RecordKind.LZ, uuid)?.let(::toDiagram)

    /**
     * Writes the diagram's changes to its record (the one named by [Diagram.id], which [open] set to the record's uuid). The document is
     * saved clean (not dirty, no terrain raster), as the web saves it.
     *
     * Returns the diagram as it is now stored: the same one, unless its record was deleted (on another device, say) while it was open. Then
     * the work is kept as a new record under a new id and that is returned, because the old id is held by the server as a deletion and
     * cannot be written again. The edit that outlives a delete is the sync engine's own rule for records, and applies here too.
     */
    suspend fun save(diagram: Diagram): Diagram {
        val stamped = diagram.copy(updatedAt = env.now())
        // A record that is deleted reads as absent here, whether the deletion came from another device or was made on this one and is yet to be sent.
        if (sync.record(RecordKind.LZ, diagram.id) == null) {
            val uuid = java.util.UUID.randomUUID().toString()
            val restored = stamped.copy(id = uuid, savedId = JsonNull)
            sync.create(RecordKind.LZ, restored.name, serialize(restored), uuid = uuid)
            scheduler.requestSync()
            return restored
        }
        sync.edit(RecordKind.LZ, diagram.id, name = stamped.name, data = serialize(stamped))
        scheduler.requestSync()
        return stamped
    }

    suspend fun rename(uuid: String, name: String) {
        val diagram = open(uuid) ?: return
        save(diagram.copy(name = name))
    }

    suspend fun delete(uuid: String) {
        sync.delete(RecordKind.LZ, uuid)
        scheduler.requestSync()
    }

    private fun toDiagram(record: LocalRecord): Diagram = DiagramNormalizer.normalize(
        record.data,
        DiagramNormalizer.Options(
            id = record.uuid,
            savedId = record.serverId?.let { JsonPrimitive(it) },
            name = record.name,
            dirty = false,
        ),
        env,
    )

    private fun summarize(record: LocalRecord): DiagramSummary {
        val diagram = toDiagram(record)
        return DiagramSummary(
            uuid = record.uuid, name = record.name, status = diagram.status, target = diagram.target, sync = record.status,
            conflictOf = record.conflictOf, updatedAt = diagram.updatedAt,
        )
    }

    private fun serialize(diagram: Diagram): JsonObject =
        json.encodeToJsonElement(Diagram.serializer(), DiagramNormalizer.serialize(diagram)) as JsonObject
}
