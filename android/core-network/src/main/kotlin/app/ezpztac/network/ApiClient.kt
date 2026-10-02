package app.ezpztac.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** Tolerant on the way in: a field a newer server adds must not break an installed app. Nothing absent is written as null. */
public val ApiJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

/** How the client reads the time, so tests can drive it. */
public fun interface TimeSource {
    public fun nowMillis(): Long

    public companion object {
        public val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}

/** How important a call is. Background work waits while heavy server work is in flight (see [PriorityGate]). */
public enum class CallPriority { NORMAL, BACKGROUND }

/** The response to a call that succeeded (2xx). */
public class ApiResponse internal constructor(
    public val status: Int,
    private val headers: okhttp3.Headers,
    public val body: String,
) {
    public fun header(name: String): String? = headers[name]
}

/**
 * The transport for every call to the server, and the session behind it.
 *
 * - Every request carries `X-EZPZ-Client` and, where it is needed, the access token.
 * - A request refused because the access token has lapsed or been revoked is retried once
 *   with a new one. Many requests failing at once cause **one** refresh, not many.
 * - The server spends a refresh token on use. So the new one is **stored before** anything relies on
 *   it, a refresh is never abandoned half-way when the caller goes away, and a refresh whose answer
 *   may have been lost is repeated with the same token for a short while, which the server accepts
 *   as a lost response (`refresh_reuse_detected` otherwise: a copy of a token).
 * - A session the server ended is cleared, announced on [state], and raised as [SessionEndedException].
 * - Errors become typed exceptions ([ApiException] and its subclasses); a body the server did not
 *   write (a proxy's error page) is still an [ApiException] with the status.
 */
public class ApiClient(
    baseUrl: String,
    private val http: OkHttpClient,
    private val clientInfo: ClientInfo,
    private val sessions: SessionStore,
    public val priority: PriorityGate = PriorityGate(),
    private val time: TimeSource = TimeSource.System,
    /** How long a refresh waits for an answer before it asks again; see [REFRESH_READ_TIMEOUT_SECONDS]. */
    private val refreshReadTimeoutSeconds: Long = REFRESH_READ_TIMEOUT_SECONDS,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val holder = AuthStateHolder()
    private val refreshLock = Mutex()

    /** Who is signed in. [AuthState.Unknown] until [restore] has read the store. */
    public val state: StateFlow<AuthState> get() = holder.state

    /** Reads the stored session at launch. */
    public suspend fun restore(): AuthState {
        val stored = sessions.read()
        val restored = if (stored != null) AuthState.SignedIn(stored.user)
        else AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN)
        holder.set(restored)
        return restored
    }

    // -- Sign-in, refresh and sign-out ------------------------------------------------

    public suspend fun login(email: String, password: String): ApiUser = signIn(
        "/api/auth/login",
        buildJsonObject { put("email", email); put("password", password) },
    )

    public suspend fun signInWithGoogle(idToken: String): ApiUser = signIn(
        "/api/auth/google",
        buildJsonObject { put("token", idToken) },
    )

    private suspend fun signIn(path: String, body: JsonObject): ApiUser {
        val response = execute(Call(method = "POST", path = path, body = body, auth = false))
        val tokens = decode<TokenResponse>(response)
        store(tokens, previousRefresh = null)
        return tokens.user
    }

    /**
     * Signs out. The device is signed out whatever the network does: the stored session is cleared even
     * if the server cannot be reached (then its copy of this session lives on until it lapses, and
     * the result says so).
     *
     * @return true if the server confirmed.
     */
    public suspend fun logout(): Boolean {
        val acknowledged = if (sessions.read() == null) true else try {
            execute(Call("POST", "/api/auth/logout"))
            true
        } catch (_: ApiException) {
            false
        }
        sessions.clear()
        holder.set(AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN))
        return acknowledged
    }

    /**
     * Swaps the refresh token for a new access token. [failedAccessToken] is the token whose use was
     * refused: if the stored one has already changed, another call has done the refresh and that
     * result is used.
     */
    private suspend fun refreshAfterRefusal(failedAccessToken: String): StoredSession = refreshLock.withLock {
        val current = sessions.read() ?: throw endedWithoutStoring(SignedOutReason.NOT_SIGNED_IN, null, "You are signed out.")
        if (current.accessToken != failedAccessToken) return@withLock current
        val refreshToken = current.refreshToken ?: throw end(SignedOutReason.SESSION_ENDED, "no_refresh_token", "This session has expired. Sign in again.")
        // Once the request is on its way it is carried through to the end, even if whoever asked has
        // gone away: a response that arrives with nowhere to be stored would spend the token for nothing.
        withContext(NonCancellable) { rotate(current, refreshToken) }
    }

    private suspend fun rotate(current: StoredSession, refreshToken: String): StoredSession {
        val started = time.nowMillis()
        var attempt = 0
        while (true) {
            val outcome: ApiException = try {
                val response = send(
                    Call(
                        "POST", "/api/auth/refresh", body = buildJsonObject { put("refresh_token", refreshToken) }, auth = false,
                        readTimeoutSeconds = refreshReadTimeoutSeconds,
                    ),
                    accessToken = null,
                )
                when {
                    response.status == 200 -> {
                        val tokens = decode<TokenResponse>(ApiResponse(response.status, response.headers, response.body))
                        return store(tokens, previousRefresh = current)
                    }
                    response.status == 401 || response.status == 403 -> {
                        val error = parseError(response)
                        throw end(SignedOutReason.SESSION_ENDED, error.code, error.message)
                    }
                    else -> throw map(response)                                       // a 5xx is repeated below: it may have rotated first
                }
            } catch (e: NetworkException) {
                if (!e.requestMayHaveBeenSent) throw e
                e
            } catch (e: ApiException) {
                // A server error may have rotated the token before failing, so it is asked again like a lost answer.
                // Anything else (the session ended, too many requests, a refusal) is final.
                if (e is SessionEndedException || e.status < 500) throw e
                e
            }

            // The answer may have been lost with the token already spent. Asking again with the same token
            // is safe for a short while (the server's grace period); later it would look like theft.
            val wait = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
            attempt++
            if (time.nowMillis() - started + wait > RETRY_WINDOW_MS) throw outcome
            delay(wait)
        }
    }

    private suspend fun store(tokens: TokenResponse, previousRefresh: StoredSession?): StoredSession {
        val refresh = tokens.refreshToken
        if (previousRefresh != null && refresh == null) {
            // A refresh that returns no refresh token cannot be carried on: the old one is spent.
            throw end(SignedOutReason.SESSION_ENDED, "no_refresh_token", "This session has expired. Sign in again.")
        }
        val session = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = refresh,
            refreshExpiresAtEpochSeconds = tokens.refreshExpiresIn?.let { time.nowMillis() / 1000 + it },
            user = tokens.user,
        )
        sessions.write(session)                                                         // before anything relies on it
        holder.set(AuthState.SignedIn(tokens.user))
        return session
    }

    private suspend fun end(reason: SignedOutReason, code: String?, message: String): SessionEndedException {
        sessions.clear()
        return endedWithoutStoring(reason, code, message)
    }

    private fun endedWithoutStoring(reason: SignedOutReason, code: String?, message: String): SessionEndedException {
        holder.set(AuthState.SignedOut(reason, code))
        return SessionEndedException(reason, code, message)
    }

    // -- One call --------------------------------------------------------------------

    /** What to ask for. Internal: the typed endpoints build these. */
    internal class Call(
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val body: JsonElement? = null,
        /** Whether the call needs the access token. */
        val auth: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val callPriority: CallPriority = CallPriority.NORMAL,
        /** A different read timeout from the client's. Heavy work gets a long one on its own. */
        val readTimeoutSeconds: Long? = null,
    )

    internal suspend fun execute(call: Call): ApiResponse {
        if (call.callPriority == CallPriority.BACKGROUND) priority.whenIdle()
        val ticket = if (PriorityPaths.isHeavy(call.path)) priority.begin() else null
        try {
            val raw = if (call.auth) executeSigned(call) else send(call, accessToken = null)
            if (raw.status !in 200..299) throw map(raw)
            return ApiResponse(raw.status, raw.headers, raw.body)
        } finally {
            ticket?.close()
        }
    }

    private suspend fun executeSigned(call: Call): Raw {
        val session = sessions.read() ?: throw endedWithoutStoring(SignedOutReason.NOT_SIGNED_IN, null, "You are signed out.")
        val first = send(call, session.accessToken)
        if (!isAuthRefusal(first)) return first

        val renewed = refreshAfterRefusal(session.accessToken)
        val second = send(call, renewed.accessToken)
        if (isAuthRefusal(second)) {
            // A token the server has just issued is refused: there is nothing more to try.
            throw end(SignedOutReason.SESSION_ENDED, "unauthorized_after_refresh", "This session has expired. Sign in again.")
        }
        return second
    }

    /**
     * 401 is "this token is no good" (expired or revoked), and a 422 carrying `msg` is the token library's way of
     * saying the token could not be read at all; the cure for both is a new one. But the server also answers 401
     * for its own reasons, always with a `code` (a wrong password, or "confirm it's you" on a deletion), and
     * refreshing the token would not change those: they go to the caller as they are.
     */
    private fun isAuthRefusal(raw: Raw): Boolean {
        if (raw.status != 401 && raw.status != 422) return false
        val json = parseJsonObject(raw.body)
        if (raw.status == 401) return json?.get("code") == null
        return json != null && json["msg"] != null && json["error"] == null
    }

    internal class Raw(val status: Int, val headers: okhttp3.Headers, val body: String)

    private suspend fun send(call: Call, accessToken: String?): Raw {
        val url = base.newBuilder().apply {
            call.path.trimStart('/').split('/').forEach { addPathSegment(it) }
            call.query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = call.body?.let { JSON.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON_TYPE) }
            ?: if (call.method == "GET" || call.method == "HEAD") null else EMPTY_BODY
        val request = Request.Builder().url(url).method(call.method, body)
            .header(ClientInfo.HEADER, clientInfo.header)
            .header("Accept", "application/json")
            .apply {
                call.headers.forEach { (k, v) -> header(k, v) }
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
            }
            .build()
        val timeout = call.readTimeoutSeconds ?: if (PriorityPaths.isHeavy(call.path)) HEAVY_READ_TIMEOUT_SECONDS else null
        val client = timeout?.let { http.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: http
        try {
            client.newCall(request).await().use { response ->
                return Raw(response.code, response.headers, response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw networkFailure(e)
        }
    }

    private fun networkFailure(e: IOException): NetworkException = classify(e)

    // -- Reading answers ---------------------------------------------------------------

    internal inline fun <reified T> decode(response: ApiResponse): T = try {
        JSON.decodeFromString(response.body)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiException(response.status, "unreadable_response", "The server's answer could not be read.", e)
    } catch (e: IllegalArgumentException) {
        throw ApiException(response.status, "unreadable_response", "The server's answer could not be read.", e)
    }

    private class ErrorBody(val code: String?, val message: String, val json: JsonObject?)

    private fun parseError(raw: Raw): ErrorBody {
        val json = parseJsonObject(raw.body)
        val message = listOf("message", "error", "msg").firstNotNullOfOrNull { json?.get(it)?.let { v -> (v as? JsonPrimitive)?.contentOrNull } }
        return ErrorBody(
            code = (json?.get("code") as? JsonPrimitive)?.contentOrNull,
            message = message ?: "The server answered ${raw.status}.",
            json = json,
        )
    }

    private fun map(raw: Raw): ApiException {
        val error = parseError(raw)
        return when {
            raw.status == 403 && error.code == "affiliation_required" -> AffiliationRequiredException(error.message)
            raw.status == 409 && error.code == "revision_conflict" ->
                RevisionConflictException(error.message, (error.json?.get("server") as? JsonObject) ?: JsonObject(emptyMap()))
            raw.status == 429 -> RateLimitedException(error.message, raw.headers["Retry-After"]?.trim()?.toLongOrNull())
            else -> ApiException(raw.status, error.code, error.message)
        }
    }

    private fun parseJsonObject(text: String): JsonObject? =
        if (text.isBlank()) null else runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject

    internal companion object {
        /**
         * Whether the server could have seen the request that failed. A failure before it was sent (no route, no DNS, a
         * refused connection, a failed handshake, a connect timeout) cannot have changed anything there; one after it
         * (a read timeout, a reset, a closed stream) might have.
         */
        fun classify(e: IOException): NetworkException {
            val notSent = e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
                e is SSLHandshakeException || (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
            return NetworkException(e.message ?: "The server could not be reached.", e, requestMayHaveBeenSent = !notSent)
        }

        val JSON: Json get() = ApiJson

        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /**
         * When a refresh's answer may have been lost, ask again with the same token after these pauses. The
         * server treats a repeat within 30 s as a lost response; past that it would look like a copy of the
         * token and end the session, so the window stops well inside it.
         */
        val RETRY_DELAYS_MS: List<Long> = listOf(500, 1_000, 2_000, 4_000, 8_000)
        const val RETRY_WINDOW_MS: Long = 20_000

        /**
         * A refresh gives up on a silent server quickly. The grace period runs from when the server spent the
         * token, so waiting out a long read timeout before asking again would arrive after it.
         */
        const val REFRESH_READ_TIMEOUT_SECONDS: Long = 8

        /** An analysis, viewshed or export can take the server a long while; its own timeout is 180 s. */
        const val HEAVY_READ_TIMEOUT_SECONDS: Long = 190
    }
}

/** Waits for a call to finish, and cancels it if the coroutine is cancelled. */
private suspend fun okhttp3.Call.await(): Response = suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: okhttp3.Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
