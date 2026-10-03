package app.ezpztac.network

import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/*
 * The routes an app calls, as functions. Each is one entry in `contracts/openapi.yaml`.
 *
 * A write carries an `Idempotency-Key`: the same key on a repeat tells the server it is the same
 * write, so a retry after a lost response is answered rather than refused as a conflict with
 * itself. A caller that queues a write for later (the sync engine) passes the key it stored with
 * it; otherwise one is made for this call and kept across the automatic retries inside it.
 */

/** The result of saving something that may already exist. */
public data class Saved<T>(val value: T, val created: Boolean)

private fun newKey(): String = UUID.randomUUID().toString()

private fun revisionHeader(revision: Int?): Map<String, String> =
    if (revision == null) emptyMap() else mapOf("If-Match" to "\"$revision\"")

/** What the app needs before anyone signs in. */
public suspend fun ApiClient.config(): AppConfig =
    decode(execute(ApiClient.Call("GET", "/api/config", auth = false)))

// -- Account and sessions ---------------------------------------------------------

public suspend fun ApiClient.me(): ApiUser = decode(execute(ApiClient.Call("GET", "/api/auth/me")))

/**
 * Asks the server who this is *now* and keeps the answer, in the store and on [ApiClient.state]. What the user may do changes on the
 * server (an admin approves access, entitlements are switched off), and the app learns of it here, at launch and after a step that
 * changes it.
 */
public suspend fun ApiClient.refreshUser(): ApiUser = me().also { updateUser(it) }

// -- Signing up and recovering an account -------------------------------------------
//
// These are the routes a person meets before they are signed in, so none of them carries a token (except the `.mil` ones, which are for
// an account that is signed in and has not yet cleared the affiliation gate). Registration is email-first: a name and an address, a link
// in the email, and only then a password.

/** Starts an account. The server's answer is the same whether or not the address is already in use. */
public suspend fun ApiClient.register(name: String, email: String): Accepted =
    decode(execute(ApiClient.Call("POST", "/api/auth/register", body = buildJsonObject { put("name", name); put("email", email) }, auth = false)))

/** Confirms the address from the emailed link's [token], and sets the first password. Wrong or spent token: `ApiException` with code `invalid_token`. */
public suspend fun ApiClient.verifyEmail(token: String, password: String): Done =
    decode(execute(ApiClient.Call("POST", "/api/auth/verify-email", body = buildJsonObject { put("token", token); put("password", password) }, auth = false)))

/** Sends the verification link again (at most one a minute, three an hour, per address). */
public suspend fun ApiClient.resendVerification(email: String): Accepted =
    decode(execute(ApiClient.Call("POST", "/api/auth/resend-verification", body = buildJsonObject { put("email", email) }, auth = false)))

public suspend fun ApiClient.forgotPassword(email: String): Accepted =
    decode(execute(ApiClient.Call("POST", "/api/auth/forgot-password", body = buildJsonObject { put("email", email) }, auth = false)))

/** Sets a new password from the emailed link's [token]. Every other session of the account ends. */
public suspend fun ApiClient.resetPassword(token: String, password: String): Done =
    decode(execute(ApiClient.Call("POST", "/api/auth/reset-password", body = buildJsonObject { put("token", token); put("password", password) }, auth = false)))

/** Emails a code to a `.mil` address, to clear the affiliation gate. Any `.mil` address will do; each clears one account. */
public suspend fun ApiClient.requestMilCode(email: String): Done =
    decode(execute(ApiClient.Call("POST", "/api/auth/mil/request", body = buildJsonObject { put("email", email) })))

/** Confirms the emailed code. The account is now cleared, and the returned user (also kept) says so. */
public suspend fun ApiClient.verifyMilCode(code: String): ApiUser {
    val body: MilVerifyBody = decode(execute(ApiClient.Call("POST", "/api/auth/mil/verify", body = buildJsonObject { put("code", code) })))
    updateUser(body.user)
    return body.user
}

public suspend fun ApiClient.deviceSessions(): List<DeviceSession> =
    decode<SessionsBody>(execute(ApiClient.Call("GET", "/api/auth/sessions"))).sessions

/** Signs another of the user's devices out. The server answers 404 for a session that is not theirs. */
public suspend fun ApiClient.revokeSession(id: String) {
    execute(ApiClient.Call("DELETE", "/api/auth/sessions/$id"))
}

/**
 * Deletes the account and everything saved under it, which the stores require. Irreversible, so the server
 * wants proof it is the owner *now*: the [password], or for an account with none a fresh Google ID token.
 * (A signed-in token alone is not proof.) The super-admin account cannot be deleted.
 */
public suspend fun ApiClient.deleteAccount(password: String? = null, googleToken: String? = null) {
    execute(
        ApiClient.Call(
            "DELETE", "/api/auth/me",
            body = buildJsonObject {
                put("confirm", "DELETE")
                if (password != null) put("password", password)
                if (googleToken != null) put("google_token", googleToken)
            },
        ),
    )
    // The account is gone, and so is this session: say so rather than leave a token that will be refused.
    logout()
}

// -- Saved LZs --------------------------------------------------------------------

public suspend fun ApiClient.listLzs(): List<LzSummary> =
    decode(execute(ApiClient.Call("GET", "/api/lz")))

public suspend fun ApiClient.getLz(id: Int): LzFull =
    decode(execute(ApiClient.Call("GET", "/api/lz/$id")))

/**
 * Saves a new LZ. Pass the [clientUuid] the device chose and a retry after a lost response returns the first
 * record ([Saved.created] false) instead of making a second.
 */
public suspend fun ApiClient.createLz(
    name: String,
    lzData: JsonObject,
    clientUuid: String,
    idempotencyKey: String = newKey(),
): Saved<LzSummary> {
    val response = execute(
        ApiClient.Call(
            "POST", "/api/lz",
            body = buildJsonObject { put("name", name); put("lz_data", lzData); put("client_uuid", clientUuid) },
            headers = mapOf("Idempotency-Key" to idempotencyKey),
        ),
    )
    return Saved(decode(response), created = response.status == 201)
}

/**
 * Edits an LZ on top of [baseRevision], the revision the edit was made against. If the server has moved on,
 * nothing is overwritten and [RevisionConflictException] carries the server's copy. Without a revision the
 * last writer wins, as on the web.
 */
public suspend fun ApiClient.updateLz(
    id: Int,
    baseRevision: Int?,
    name: String? = null,
    lzData: JsonObject? = null,
    idempotencyKey: String = newKey(),
): LzSummary = decode(
    execute(
        ApiClient.Call(
            "PUT", "/api/lz/$id",
            body = buildJsonObject { if (name != null) put("name", name); if (lzData != null) put("lz_data", lzData) },
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    ),
)

/** Deletes an LZ. The server keeps a tombstone so other devices learn of it. Deleting twice is not an error. */
public suspend fun ApiClient.deleteLz(id: Int, baseRevision: Int? = null, idempotencyKey: String = newKey()) {
    execute(
        ApiClient.Call(
            "DELETE", "/api/lz/$id",
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    )
}

// -- Saved routes -----------------------------------------------------------------
//
// A saved route is a *set* of sketched routes under one name. The server takes it as `multipart/form-data` (the web's form), with the routes as a
// JSON document in a text field. Only `sketch` sets are made here: a `mission` save also carries the AMPS file, which the apps do not send yet.

public suspend fun ApiClient.listRoutes(): List<RouteSummary> =
    decode(execute(ApiClient.Call("GET", "/api/routes")))

public suspend fun ApiClient.getRoute(id: Int): RouteFull =
    decode(execute(ApiClient.Call("GET", "/api/routes/$id")))

/**
 * Saves a new set of sketched routes. Pass the [clientUuid] the device chose and a retry after a lost response returns the first
 * record ([Saved.created] false) instead of making a second.
 */
public suspend fun ApiClient.createRoute(
    name: String,
    routeData: JsonObject,
    clientUuid: String,
    idempotencyKey: String = newKey(),
): Saved<RouteSummary> {
    val response = execute(
        ApiClient.Call(
            "POST", "/api/routes",
            form = mapOf("name" to name, "kind" to "sketch", "route_data" to routeData.toString(), "client_uuid" to clientUuid),
            headers = mapOf("Idempotency-Key" to idempotencyKey),
        ),
    )
    return Saved(decode(response), created = response.status == 201)
}

/**
 * Edits a saved set on top of [baseRevision], the revision the edit was made against. Only what is given is sent and changed. If the server
 * has moved on, nothing is overwritten and [RevisionConflictException] carries the server's copy.
 */
public suspend fun ApiClient.updateRoute(
    id: Int,
    baseRevision: Int?,
    name: String? = null,
    routeData: JsonObject? = null,
    idempotencyKey: String = newKey(),
): RouteSummary = decode(
    execute(
        ApiClient.Call(
            "PUT", "/api/routes/$id",
            form = buildMap {
                if (name != null) put("name", name)
                if (routeData != null) put("route_data", routeData.toString())
            },
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    ),
)

/** Deletes a saved set. The server keeps a tombstone (and drops the content) so other devices learn of it. Deleting twice is not an error. */
public suspend fun ApiClient.deleteRoute(id: Int, baseRevision: Int? = null, idempotencyKey: String = newKey()) {
    execute(
        ApiClient.Call(
            "DELETE", "/api/routes/$id",
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    )
}

// -- Saved point sets -------------------------------------------------------------
//
// A saved point set is the points of one `.LPS` import under a name. The server takes JSON, never looks inside the points, and refuses a set with
// none: a set with no points is deleted, not saved.

public suspend fun ApiClient.listPointSets(): List<PointSetSummary> =
    decode(execute(ApiClient.Call("GET", "/api/pointsets")))

public suspend fun ApiClient.getPointSet(id: Int): PointSetFull =
    decode(execute(ApiClient.Call("GET", "/api/pointsets/$id")))

/**
 * Saves a new set of points. Pass the [clientUuid] the device chose and a retry after a lost response returns the first
 * record ([Saved.created] false) instead of making a second.
 */
public suspend fun ApiClient.createPointSet(
    name: String,
    points: JsonArray,
    clientUuid: String,
    idempotencyKey: String = newKey(),
): Saved<PointSetSummary> {
    val response = execute(
        ApiClient.Call(
            "POST", "/api/pointsets",
            body = buildJsonObject { put("name", name); put("points", points); put("client_uuid", clientUuid) },
            headers = mapOf("Idempotency-Key" to idempotencyKey),
        ),
    )
    return Saved(decode(response), created = response.status == 201)
}

/**
 * Edits a saved set on top of [baseRevision], the revision the edit was made against. Only what is given is sent and changed (an empty list of points
 * is ignored by the server). If the server has moved on, nothing is overwritten and [RevisionConflictException] carries the server's copy.
 */
public suspend fun ApiClient.updatePointSet(
    id: Int,
    baseRevision: Int?,
    name: String? = null,
    points: JsonArray? = null,
    idempotencyKey: String = newKey(),
): PointSetSummary = decode(
    execute(
        ApiClient.Call(
            "PUT", "/api/pointsets/$id",
            body = buildJsonObject { if (name != null) put("name", name); if (points != null) put("points", points) },
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    ),
)

/** Deletes a saved set. The server keeps a tombstone (and drops the points) so other devices learn of it. Deleting twice is not an error. */
public suspend fun ApiClient.deletePointSet(id: Int, baseRevision: Int? = null, idempotencyKey: String = newKey()) {
    execute(
        ApiClient.Call(
            "DELETE", "/api/pointsets/$id",
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    )
}

// -- Aircraft profiles ------------------------------------------------------------

/** The master list, then the caller's own profiles. */
public suspend fun ApiClient.aircraftProfiles(): List<AircraftProfileDto> =
    decode(execute(ApiClient.Call("GET", "/api/aircraft-profiles")))

public suspend fun ApiClient.createAircraftProfile(
    input: AircraftProfileInput,
    idempotencyKey: String = newKey(),
): Saved<AircraftProfileDto> {
    val response = execute(
        ApiClient.Call(
            "POST", "/api/aircraft-profiles",
            body = ApiClient.JSON.encodeToJsonElement(AircraftProfileInput.serializer(), input),
            headers = mapOf("Idempotency-Key" to idempotencyKey),
        ),
    )
    return Saved(decode(response), created = response.status == 201)
}

public suspend fun ApiClient.updateAircraftProfile(
    id: Int,
    baseRevision: Int?,
    input: AircraftProfileInput,
    idempotencyKey: String = newKey(),
): AircraftProfileDto = decode(
    execute(
        ApiClient.Call(
            "PUT", "/api/aircraft-profiles/$id",
            body = ApiClient.JSON.encodeToJsonElement(AircraftProfileInput.serializer(), input),
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    ),
)

public suspend fun ApiClient.deleteAircraftProfile(id: Int, baseRevision: Int? = null, idempotencyKey: String = newKey()) {
    execute(
        ApiClient.Call(
            "DELETE", "/api/aircraft-profiles/$id",
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey),
        ),
    )
}

// -- Sync -------------------------------------------------------------------------

/**
 * Everything of the caller's that changed after [since], deletions included. Keep the returned cursor and send it
 * next time; while [ChangeFeed.hasMore] is true, ask again at once. A pull is background work: it waits while
 * heavy server work is running.
 */
public suspend fun ApiClient.changes(since: Int = 0, limit: Int? = null): ChangeFeed =
    decode(
        execute(
            ApiClient.Call(
                "GET", "/api/sync/changes",
                query = buildMap { put("since", since.toString()); if (limit != null) put("limit", limit.toString()) },
                callPriority = CallPriority.BACKGROUND,
            ),
        ),
    )

// -- Terrain analysis ------------------------------------------------------------------

/**
 * Finds the landing area around a target (the segmentation model on the satellite tile) and the ground elevation there. The server runs
 * one analysis at a time and it can take many seconds, so this is heavy work (background calls wait for it, and it has a long read timeout);
 * cancelling the coroutine abandons the request. A 400 means no area was found at the point, anything else is a failure to try again.
 */
public suspend fun ApiClient.analyzeField(at: LatLon): FieldAnalysis = decode(
    execute(
        ApiClient.Call(
            "POST", "/api/analyze-field",
            body = buildJsonObject { put("lat", at.lat); put("lon", at.lon) },
        ),
    ),
)

/**
 * Slope over a landing-zone boundary: a banded raster, statistics, and, with a [landingHeadingDeg], the nose-high, nose-low and cross-slope
 * summary for it. [polygon] needs at least three points (the server answers 400 otherwise). Heavy work, as [analyzeField].
 */
public suspend fun ApiClient.terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double? = null): TerrainAnalysis = decode(
    execute(
        ApiClient.Call(
            "POST", "/api/terrain-analysis",
            body = buildJsonObject {
                put("polygon", JsonArray(polygon.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) }))
                if (landingHeadingDeg != null) put("landingHeading", landingHeadingDeg)
            },
        ),
    ),
)

/**
 * Ground elevation in feet at each of [points], in the same order, `null` where the server could not read the ground there. A failure while
 * sampling comes back as a 200 with every elevation null, which reads here as no answer, and an empty list asks nothing.
 */
public suspend fun ApiClient.elevations(points: List<LatLon>): List<Double?> {
    if (points.isEmpty()) return emptyList()
    val answer: ElevationsResponse = decode(
        execute(
            ApiClient.Call(
                "POST", "/api/elevations",
                body = buildJsonObject {
                    put("points", JsonArray(points.map { buildJsonObject { put("lat", it.lat); put("lon", it.lon) } }))
                },
            ),
        ),
    )
    return answer.elevationsFt.map { it?.toDouble() }
}

/** A point of a route, asked about for its wind. [time] is the instant the wind is wanted for, as `2026-10-03T16:30:00.000Z`, or null for now. */
public data class WindQuestion(val id: String, val lat: Double, val lon: Double, val time: String?)

/**
 * The wind at each of [points], from the nearest weather station: the latest observation, or the forecast for a point wanted more than about half an
 * hour ahead. A point the server could find no station for is not in the answer.
 */
public suspend fun ApiClient.routeWinds(points: List<WindQuestion>): Map<String, PointWindDto> {
    if (points.isEmpty()) return emptyMap()
    val answer: WindsResponse = decode(
        execute(
            ApiClient.Call(
                "POST", "/api/route-winds",
                body = buildJsonObject {
                    put(
                        "points",
                        JsonArray(
                            points.map {
                                buildJsonObject {
                                    put("id", it.id); put("lat", it.lat); put("lon", it.lon)
                                    if (it.time != null) put("time", it.time)
                                }
                            },
                        ),
                    )
                },
            ),
        ),
    )
    return answer.winds
}
