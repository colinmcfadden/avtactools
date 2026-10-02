package app.ezpztac.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * What the server sends and receives, one type per body in `contracts/openapi.yaml`.
 * Field names are the server's (snake_case where it is), so a body reads straight into
 * its type. Every one is held to the real responses recorded in
 * `contracts/fixtures/network/responses.json`, strictly: a test fails if the server sends
 * a field a type here does not have.
 *
 * Reading ignores a field a *newer* server adds (an installed app must keep working), and
 * a field the app does not need is simply left out of the type.
 */

@Serializable
public data class ApiUser(
    val id: Int,
    val email: String,
    val name: String,
    val picture: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean = false,
    @SerialName("account_status") val accountStatus: String? = null,
    @SerialName("has_password") val hasPassword: Boolean = false,
    val role: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_active") val isActive: Boolean,
    /** Entitlements. A missing key means enabled. */
    val features: Map<String, Boolean>,
    @SerialName("mil_email") val milEmail: String? = null,
    @SerialName("affiliation_verified") val affiliationVerified: Boolean = false,
    /** Whether the `.mil` / approval gate is cleared. */
    @SerialName("access_ok") val accessOk: Boolean,
) {
    /** A feature is on unless the server says it is off. */
    public fun hasFeature(key: String): Boolean = features[key] != false
}

@Serializable
public data class TokenResponse(
    val status: String,
    @SerialName("access_token") val accessToken: String,
    /** Native clients only. Spent on use: the response to a refresh carries the next one. */
    @SerialName("refresh_token") val refreshToken: String? = null,
    /** Seconds a refresh token lives; each refresh extends it. */
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    val user: ApiUser,
)

/**
 * A request the server accepted without saying what it did. For sign-up, resending a link and a password reset it never says whether an
 * address has an account, so the answer is the same either way (and a screen must not claim more than "if the address is eligible...").
 */
@Serializable
public data class Accepted(
    val status: String,
    val message: String,
    /** Present on sign-up: the account cannot be used until the emailed link is followed. */
    @SerialName("requires_verification") val requiresVerification: Boolean = false,
)

/** A request that was carried out. [message] is for the person. */
@Serializable
public data class Done(val status: String, val message: String)

@Serializable
internal data class MilVerifyBody(val status: String, val user: ApiUser)

/** One signed-in device. */
@Serializable
public data class DeviceSession(
    val id: String,
    val client: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_active_at") val lastActiveAt: String,
    /** This device. */
    val current: Boolean,
)

@Serializable
internal data class SessionsBody(val sessions: List<DeviceSession>)

@Serializable
public data class AppConfig(
    val configVersion: Int,
    val serverVersion: String,
    val minAppVersion: MinAppVersion,
    val maintenance: Maintenance,
    val services: Services,
    val mapbox: MapboxConfig,
) {
    @Serializable public data class MinAppVersion(val android: String? = null, val ios: String? = null)
    @Serializable public data class Maintenance(val active: Boolean, val message: String? = null)
    @Serializable public data class Services(val lidarBuilds: Boolean, val packs: Boolean)
    @Serializable public data class MapboxConfig(val publicToken: String? = null)
}

/** A saved LZ without its diagram. */
@Serializable
public data class LzSummary(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** The record's identity: the device's own if it sent one. */
    @SerialName("client_uuid") val clientUuid: String,
    /** Bumped by every change. */
    val revision: Int,
)

@Serializable
public data class LzFull(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("client_uuid") val clientUuid: String,
    val revision: Int,
    /** The diagram document, schema 2. Opaque here; `core-model` reads it. */
    @SerialName("lz_data") val lzData: JsonObject,
)

/** An airframe profile: the admin's master list, or one of the caller's own. */
@Serializable
public data class AircraftProfileDto(
    val id: Int,
    val slug: String,
    val name: String,
    val designation: String,
    @SerialName("icon_key") val iconKey: String,
    @SerialName("is_system") val isSystem: Boolean,
    @SerialName("rotor_diameter_m") val rotorDiameterM: Double,
    @SerialName("rotor_tip_clearance_m") val rotorTipClearanceM: Double,
    @SerialName("default_airspeed_kts") val defaultAirspeedKts: Double,
    @SerialName("default_airspeed_type") val defaultAirspeedType: String,
    @SerialName("max_indicated_kts") val maxIndicatedKts: Double,
    @SerialName("default_altitude_ft") val defaultAltitudeFt: Double,
    @SerialName("default_altitude_ref") val defaultAltitudeRef: String,
    @SerialName("min_altitude_ft_msl") val minAltitudeFtMsl: Double,
    @SerialName("max_altitude_ft_msl") val maxAltitudeFtMsl: Double,
    @SerialName("default_fuel_flow_lb_hr") val defaultFuelFlowLbHr: Double,
    @SerialName("default_gross_weight_lb") val defaultGrossWeightLb: Double,
    @SerialName("perf_source") val perfSource: String,
    @SerialName("amps_vehicle_description") val ampsVehicleDescription: String? = null,
    @SerialName("has_template") val hasTemplate: Boolean,
    @SerialName("template_kind") val templateKind: String? = null,
    @SerialName("template_name") val templateName: String? = null,
    @SerialName("sort_order") val sortOrder: Int,
    /** The caller's own profiles only: the master list does not sync. */
    @SerialName("client_uuid") val clientUuid: String? = null,
    val revision: Int? = null,
)

/** What a user may set on their own profile. A field left null is not sent and is left as it is. */
@Serializable
public data class AircraftProfileInput(
    @SerialName("client_uuid") val clientUuid: String? = null,
    val name: String? = null,
    val designation: String? = null,
    @SerialName("icon_key") val iconKey: String? = null,
    @SerialName("rotor_diameter_m") val rotorDiameterM: Double? = null,
    @SerialName("rotor_tip_clearance_m") val rotorTipClearanceM: Double? = null,
    @SerialName("default_airspeed_kts") val defaultAirspeedKts: Double? = null,
    @SerialName("default_airspeed_type") val defaultAirspeedType: String? = null,
    @SerialName("max_indicated_kts") val maxIndicatedKts: Double? = null,
    @SerialName("default_altitude_ft") val defaultAltitudeFt: Double? = null,
    @SerialName("default_altitude_ref") val defaultAltitudeRef: String? = null,
    @SerialName("min_altitude_ft_msl") val minAltitudeFtMsl: Double? = null,
    @SerialName("max_altitude_ft_msl") val maxAltitudeFtMsl: Double? = null,
    @SerialName("default_fuel_flow_lb_hr") val defaultFuelFlowLbHr: Double? = null,
    @SerialName("default_gross_weight_lb") val defaultGrossWeightLb: Double? = null,
    @SerialName("amps_vehicle_description") val ampsVehicleDescription: String? = null,
)

/** One changed record in the sync feed. `type` is `lz`, `route`, `pointset` or `aircraft`. */
@Serializable
public data class SyncChange(
    val type: String,
    val id: Int,
    @SerialName("client_uuid") val clientUuid: String,
    val revision: Int,
    /** A tombstone: the record is gone. Its `name` is empty and its `data` is empty. */
    val deleted: Boolean,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** This change's place in the user's order. */
    val seq: Int,
    /** Routes only: `sketch` or `mission`. */
    val kind: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("has_file") val hasFile: Boolean = false,
    /** The LZ's diagram, the route's data, the point set's points or the profile, per [type]. */
    val data: JsonElement = JsonNull,
)

@Serializable
public data class ChangeFeed(
    /** Send this as `since` next time. Equals `since` when nothing changed. */
    val cursor: Int,
    /** Ask again at once. */
    @SerialName("has_more") val hasMore: Boolean,
    val changes: List<SyncChange>,
)

// -- Terrain analysis ---------------------------------------------------------------

/** What `/api/analyze-field` found: the polygon of the landing area and the ground elevation there. */
@Serializable
public data class FieldAnalysis(
    val status: String,
    /** The area as `[lat, lon]` pairs, in the order the model traced it. */
    @SerialName("suggested_lz") val suggestedLz: List<List<Double>>,
    /** Feet, as the server writes it: whole feet as a string, or `TBD` when the elevation service could not be reached. */
    val elevation: String,
    val message: String,
)

@Serializable
public data class SlopeStats(
    val maxDeg: Double,
    val p95Deg: Double,
    val areaOver6Pct: Double,
    val areaOver10Pct: Double,
    val areaOver15Pct: Double,
    val sampleCount: Int,
    val sampleAreaM2: Double,
)

/** Slope along and across a landing heading, against the UH-60 limits in [SlopeThresholds]. */
@Serializable
public data class DirectionalSlope(
    val headingDeg: Double,
    val noseHighMaxDeg: Double,
    val noseLowMaxDeg: Double,
    val crossSlopeMaxDeg: Double,
    val noseHighOverLimitPct: Double,
    val noseLowOverLimitPct: Double,
    val crossSlopeOverLimitPct: Double,
)

@Serializable
public data class Uh60Limits(val noseHigh: Double, val noseLow: Double, val crossSlope: Double)

@Serializable
public data class SlopeThresholds(
    /** The band edges the raster is coloured by, degrees. */
    val bands: List<Double>,
    val uh60: Uh60Limits,
)

/** What `/api/terrain-analysis` returns: a banded slope raster over [bounds] and the numbers behind it. */
@Serializable
public data class TerrainAnalysis(
    val status: String,
    /** A `data:image/png;base64,` raster, transparent outside the polygon. */
    val overlay: String,
    /** `[[south, west], [north, east]]` of the raster. */
    val bounds: List<List<Double>>,
    /** Which terrain source answered (`local_highres_cog`, `terrarium` …), shown with the numbers so two sources are never mixed silently. */
    val source: String,
    val resolutionM: Double,
    val verticalDatum: String,
    val stats: SlopeStats,
    /** Null when no landing heading was sent. */
    val directional: DirectionalSlope? = null,
    val thresholds: SlopeThresholds,
)
