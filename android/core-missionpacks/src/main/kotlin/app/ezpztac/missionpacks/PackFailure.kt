package app.ezpztac.missionpacks

import app.ezpztac.network.AffiliationRequiredException
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.OtherAccountException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a call about a pack did not succeed, as the session reads it: the web's `failureOf` (packApi.js), which gives
 * `{status, code, reason, finished_by, finished_at, taken}` from a refusal's status and body, and `{status: 0}` when no
 * answer came.
 *
 * [finishedBy], [finishedAt] and [taken] are the body's own JSON, and Kotlin's null is a key the body did not have: a 423
 * may say `finished_by: null` (whoever finished the pack has since deleted their account), which is not the same as not
 * saying. [item] is the item a 413 names, and [retryAfterSeconds] what a 429 asked for.
 */
public data class PackFailure(
    val status: Int,
    val code: String? = null,
    val reason: String? = null,
    val finishedBy: JsonElement? = null,
    val finishedAt: JsonElement? = null,
    val taken: JsonElement? = null,
    val item: String? = null,
    val retryAfterSeconds: Long? = null,
) {
    /** Worth asking again later, unchanged: no answer came (the server may have taken it), too many requests, or a server fault. */
    public val retryable: Boolean get() = status == 0 || status == 429 || status >= 500

    /**
     * About the account, not the pack: signed out (or never sent, because someone else is signed in now), not through the
     * `.mil` gate, or Mission Packs turned off for this person. Nothing about the pack is decided by it: the edits wait,
     * whole, for the account to be able to send them (owner decision: paused, never dropped). Asked before anything else,
     * since the web's batchFailed reads a 403 of these as the pack being gone.
     */
    public val pauses: Boolean get() = status == 401 || code in ACCOUNT_CODES

    public companion object {
        private val ACCOUNT_CODES = setOf("other_account", "affiliation_required", "feature_disabled")
        private const val UNREADABLE = "unreadable_response"

        /** A failure written out as JSON, as the fixtures give one (packs/session.json): a status that is not there is 0. */
        public fun fromJson(json: JsonObject): PackFailure = PackFailure(
            status = Js.numberOf(json["status"])?.toInt() ?: 0,
            code = text(json["code"]),
            reason = text(json["reason"]),
            finishedBy = json["finished_by"],
            finishedAt = json["finished_at"],
            taken = json["taken"],
            item = text(json["item"]),
        )

        /** What a pack call's exception says. Anything that is not the server's answer is "no answer" (status 0). */
        public fun of(error: Throwable): PackFailure = when (error) {
            is NetworkException -> PackFailure(0)
            // Nothing was sent: the device is signed in as someone else now.
            is OtherAccountException -> PackFailure(0, error.code)
            is RateLimitedException -> PackFailure(429, error.code, retryAfterSeconds = error.retryAfterSeconds)
            is SessionEndedException, is AffiliationRequiredException -> PackFailure(error.status, error.code)
            // A 2xx that could not be read was an answer: the server may have taken the batch, so it goes again, unchanged, and
            // the server answers that from its log.
            is ApiException -> if (error.code == UNREADABLE && error.status in 200..299) {
                PackFailure(0, error.code)
            } else {
                val body = error.body
                PackFailure(
                    status = error.status,
                    code = error.code,
                    reason = text(body?.get("reason")),
                    finishedBy = body?.get("finished_by"),
                    finishedAt = body?.get("finished_at"),
                    taken = body?.get("taken"),
                    item = text(body?.get("item")),
                )
            }
            else -> PackFailure(0)
        }

        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
