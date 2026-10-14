package app.ezpztac.network

import kotlinx.serialization.json.JsonObject

/**
 * A call that did not succeed. [status] is the HTTP status (0 when there was none), [code] the server's
 * stable machine-readable code if it sent one, and the message is the server's own words, meant for
 * the person (or a plain description when the server said nothing).
 *
 * [body] is the refusal as the server wrote it, when it was a JSON object: some carry more than a code, and a
 * caller needs it (a finished pack's `finished_by` and `finished_at`, a malformed operation's `index` and `reason`).
 */
public open class ApiException(
    public val status: Int,
    public val code: String?,
    message: String,
    cause: Throwable? = null,
    public val body: JsonObject? = null,
) : Exception(message, cause)

/**
 * The call never got an answer: no signal, a timeout, a dropped connection. The session is untouched.
 *
 * [requestMayHaveBeenSent] is the part the retry logic needs. A failure before the request left the
 * device (no route, no DNS, a refused connection) cannot have changed anything on the server; a
 * failure after (a read timeout, a reset) might have.
 */
public class NetworkException(
    message: String,
    cause: Throwable? = null,
    public val requestMayHaveBeenSent: Boolean,
) : ApiException(0, "network", message, cause)

/**
 * There is no session any more and the user must sign in. Raised after the stored session has been
 * cleared and [AuthState.SignedOut] announced, so a screen only has to show the sign-in.
 */
public class SessionEndedException(public val reason: SignedOutReason, code: String?, message: String) :
    ApiException(401, code, message)

/** `403 affiliation_required`: signed in, but not through the `.mil` / approval gate yet. */
public class AffiliationRequiredException(message: String) : ApiException(403, "affiliation_required", message)

/** `409 revision_conflict`: the record changed on the server. Nothing was overwritten; [server] is its current copy. */
public class RevisionConflictException(message: String, public val server: JsonObject) :
    ApiException(409, "revision_conflict", message)

/**
 * The call was made for one account and the device is now signed in as another (a sign-out and a sign-in while it waited).
 * Nothing was sent: one person's queued edits must never go up under someone else's session.
 */
public class OtherAccountException(message: String) : ApiException(0, "other_account", message)

/** `429`: too many requests. [retryAfterSeconds] is what the server asked for, if it said. */
public class RateLimitedException(message: String, public val retryAfterSeconds: Long?) :
    ApiException(429, "rate_limited", message)
