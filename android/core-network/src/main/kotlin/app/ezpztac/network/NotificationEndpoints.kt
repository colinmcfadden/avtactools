package app.ezpztac.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Notifications (docs/NOTIFICATIONS.md §3.3, `contracts/openapi.yaml`): the feed a device polls, what it has seen, and the person's settings.
 * Each call is for one account ([asUser]): under another's session it is refused before it is sent, so one person's notifications are never
 * asked for, or marked seen, as another's.
 */

/** What `GET /api/notifications` answered: the feed and its tag, or nothing changed since the tag sent. */
public sealed interface NotificationsAnswer {
    public data class Changed(val feed: NotificationFeed, val etag: String?) : NotificationsAnswer
    public data object NotModified : NotificationsAnswer
}

/** A tag as the server writes one (`"…"`, or weak, `W/"…"` behind a proxy that compresses): anything else is not sent back. */
private val ETAG = Regex("""^(W/)?"[\x21\x23-\x7e]{0,128}"$""")

/** Whether [tag] may be sent back in `If-None-Match`: a header holds nothing else. */
public fun isEtag(tag: String?): Boolean = tag != null && ETAG.matches(tag)

/**
 * The caller's notifications that are still so, and their settings, unless nothing changed since [etag] (the last answer's). A poll, so it waits
 * behind heavy work ([CallPriority.BACKGROUND]).
 */
public suspend fun ApiClient.notifications(etag: String?, asUser: Int): NotificationsAnswer {
    val headers = if (isEtag(etag)) mapOf("If-None-Match" to etag!!) else emptyMap()
    val response = try {
        execute(ApiClient.Call("GET", "/api/notifications", headers = headers, callPriority = CallPriority.BACKGROUND, asUser = asUser))
    } catch (e: ApiException) {
        if (e.status == 304) return NotificationsAnswer.NotModified
        throw e
    }
    return NotificationsAnswer.Changed(decode(response), response.header("ETag")?.takeIf(::isEtag))
}

/** Marks [ids] seen on every device. Answers those that are the caller's; ids not theirs are passed over. At most 200 at a time. */
public suspend fun ApiClient.markNotificationsSeen(ids: Collection<Long>, asUser: Int): List<Long> {
    if (ids.isEmpty()) return emptyList()
    val body = buildJsonObject { put("ids", JsonArray(ids.distinct().take(MAX_SEEN).map(::JsonPrimitive))) }
    return decode<NotificationsSeen>(execute(ApiClient.Call("POST", "/api/notifications/seen", body = body, asUser = asUser))).ids
}

/** Turns the groups given on or off for every device (null leaves one as it is), and answers the settings as they now are. */
public suspend fun ApiClient.changeNotificationSettings(
    asUser: Int, invitations: Boolean? = null, packFinished: Boolean? = null, packOwner: Boolean? = null,
): NotificationSettings {
    val body = buildJsonObject {
        invitations?.let { put("invitations", it) }
        packFinished?.let { put("pack_finished", it) }
        packOwner?.let { put("pack_owner", it) }
    }
    return decode<NotificationSettingsReply>(execute(ApiClient.Call("PUT", "/api/notifications/settings", body = body, asUser = asUser))).settings
}

private const val MAX_SEEN = 200
