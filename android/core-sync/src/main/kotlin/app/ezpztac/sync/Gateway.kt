package app.ezpztac.sync

import app.ezpztac.network.AircraftProfileInput
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiJson
import app.ezpztac.network.ChangeFeed
import app.ezpztac.network.changes
import app.ezpztac.network.createAircraftProfile
import app.ezpztac.network.createLz
import app.ezpztac.network.createPointSet
import app.ezpztac.network.createRoute
import app.ezpztac.network.deleteAircraftProfile
import app.ezpztac.network.deleteLz
import app.ezpztac.network.deletePointSet
import app.ezpztac.network.deleteRoute
import app.ezpztac.network.updateAircraftProfile
import app.ezpztac.network.updateLz
import app.ezpztac.network.updatePointSet
import app.ezpztac.network.updateRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** What the server says a record is now. */
public data class ServerCopy(val serverId: Int, val revision: Int, val name: String, val data: JsonObject)

/** The server's answer to saving a record. [created] is false when it already had one with this identity. */
public data class Remote(val serverId: Int, val revision: Int, val created: Boolean)

/**
 * The server, as the sync engine needs it. [ApiSyncApi] is the real one; tests script their own.
 */
public interface SyncApi {
    public suspend fun create(record: LocalRecord, key: String): Remote
    public suspend fun update(record: LocalRecord, baseRevision: Int, key: String): Remote
    public suspend fun delete(record: LocalRecord, baseRevision: Int?, key: String)
    public suspend fun changes(since: Int): ChangeFeed

    /** Reads the copy a conflict (409) carries. */
    public fun copyFromConflict(kind: RecordKind, server: JsonObject): ServerCopy?
}

/** Names for what the engine makes up: identities and idempotency keys. */
public interface IdSource {
    public fun newUuid(): String
    public fun newKey(): String

    public companion object {
        public val Random: IdSource = object : IdSource {
            override fun newUuid(): String = UUID.randomUUID().toString()
            override fun newKey(): String = UUID.randomUUID().toString()
        }
    }
}

/** [SyncApi] over the real client. */
public class ApiSyncApi(private val client: ApiClient) : SyncApi {
    override suspend fun create(record: LocalRecord, key: String): Remote = when (record.kind) {
        RecordKind.LZ -> client.createLz(record.name, record.data, record.uuid, key).let { Remote(it.value.id, it.value.revision, it.created) }
        RecordKind.AIRCRAFT -> client.createAircraftProfile(aircraftInput(record, includeIdentity = true), key)
            .let { Remote(it.value.id, it.value.revision ?: 1, it.created) }
        RecordKind.ROUTE -> client.createRoute(record.name, record.data, record.uuid, key).let { Remote(it.value.id, it.value.revision, it.created) }
        RecordKind.POINT_SET -> client.createPointSet(record.name, pointsOf(record), record.uuid, key).let { Remote(it.value.id, it.value.revision, it.created) }
    }

    override suspend fun update(record: LocalRecord, baseRevision: Int, key: String): Remote {
        val id = requireNotNull(record.serverId) { "an update needs the server's id" }
        return when (record.kind) {
            RecordKind.LZ -> client.updateLz(id, baseRevision, record.name, record.data, key).let { Remote(it.id, it.revision, created = false) }
            RecordKind.AIRCRAFT -> client.updateAircraftProfile(id, baseRevision, aircraftInput(record, includeIdentity = false), key)
                .let { Remote(it.id, it.revision ?: baseRevision + 1, created = false) }
            RecordKind.ROUTE -> client.updateRoute(id, baseRevision, record.name, record.data, key).let { Remote(it.id, it.revision, created = false) }
            RecordKind.POINT_SET -> client.updatePointSet(id, baseRevision, record.name, pointsOf(record), key).let { Remote(it.id, it.revision, created = false) }
        }
    }

    override suspend fun delete(record: LocalRecord, baseRevision: Int?, key: String) {
        val id = requireNotNull(record.serverId) { "a delete needs the server's id" }
        when (record.kind) {
            RecordKind.LZ -> client.deleteLz(id, baseRevision, key)
            RecordKind.AIRCRAFT -> client.deleteAircraftProfile(id, baseRevision, key)
            RecordKind.ROUTE -> client.deleteRoute(id, baseRevision, key)
            RecordKind.POINT_SET -> client.deletePointSet(id, baseRevision, key)
        }
    }

    override suspend fun changes(since: Int): ChangeFeed = client.changes(since)

    override fun copyFromConflict(kind: RecordKind, server: JsonObject): ServerCopy? = serverCopyOf(kind, server)

    private fun pointsOf(record: LocalRecord): JsonArray = record.data["points"] as? JsonArray ?: JsonArray(emptyList())

    private fun aircraftInput(record: LocalRecord, includeIdentity: Boolean): AircraftProfileInput {
        val fields = JsonObject(record.data.filterKeys { it in AIRCRAFT_SETTABLE } + ("name" to JsonPrimitive(record.name)))
        val input = ApiJson.decodeFromJsonElement(AircraftProfileInput.serializer(), fields as JsonElement)
        return if (includeIdentity) input.copy(clientUuid = record.uuid) else input
    }

    private companion object {
        /** What a user may set on their own profile; the rest of the document is the server's. */
        val AIRCRAFT_SETTABLE = setOf(
            "designation", "icon_key", "rotor_diameter_m", "rotor_tip_clearance_m", "default_airspeed_kts", "default_airspeed_type",
            "max_indicated_kts", "default_altitude_ft", "default_altitude_ref", "min_altitude_ft_msl", "max_altitude_ft_msl",
            "default_fuel_flow_lb_hr", "default_gross_weight_lb", "amps_vehicle_description",
        )
    }
}

/** Reads the copy of a record the server sent (in a 409 conflict): its id, revision, name and document. */
public fun serverCopyOf(kind: RecordKind, server: JsonObject): ServerCopy? {
    val id = (server["id"] as? JsonPrimitive)?.intOrNull ?: return null
    val revision = (server["revision"] as? JsonPrimitive)?.intOrNull ?: return null
    val name = (server["name"] as? JsonPrimitive)?.contentOrNull ?: ""
    val data = when (kind) {
        RecordKind.LZ -> server["lz_data"] as? JsonObject ?: return null
        RecordKind.AIRCRAFT -> server
        RecordKind.ROUTE -> server["route_data"] as? JsonObject ?: return null
        RecordKind.POINT_SET -> pointSetDocument(server["points"] ?: return null) ?: return null
    }
    return ServerCopy(id, revision, name, data)
}

/** A point set's document from the server's list of points: the list in an object, so it is held like every other record's document. */
public fun pointSetDocument(points: JsonElement): JsonObject? = (points as? JsonArray)?.let { JsonObject(mapOf("points" to it)) }
