package app.ezpztac.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Notifications (docs/NOTIFICATIONS.md §3.3): what `/api/notifications` answers, one type per body in
 * `contracts/openapi.yaml`, held strictly to the real responses as the rest of Dto.kt is. A notification names
 * people, packs and teams only, never what a pack holds; the words a device shows are made from [AppNotification.type]
 * and these names, and a type or group this version does not know is passed over (new ones come within the API major).
 * The calls and the worker that polls them are N1's.
 */

/** The caller's notifications that are still so, newest first, and which groups they want. */
@Serializable
public data class NotificationFeed(
    val notifications: List<AppNotification>,
    val settings: NotificationSettings,
)

/** One thing that happened to the caller. [key] is the same for one thing: a notification with the key of one shown replaces it. */
@Serializable
public data class AppNotification(
    val id: Long,
    /** `pack_invite`, `pack_added`, `team_invite`, `pack_finished` or `pack_owner`; another is passed over. */
    val type: String,
    /** Which of [NotificationSettings] it falls under. */
    val group: String,
    val key: String,
    val pack: NotificationPack? = null,
    val team: NotificationTeam? = null,
    /** Who did it, by the name they had then; null for a pack that came to the caller from a deleted account. */
    val actor: NotificationActor? = null,
    /** An invitation's id, to accept or decline it; never its link's token. */
    @SerialName("invite_id") val inviteId: Int? = null,
    val role: String? = null,
    @SerialName("created_at") val createdAt: String,
    /** Moves when an invitation is sent again: shown again without an alert. */
    @SerialName("updated_at") val updatedAt: String,
    /** Seen on one of the caller's devices: shown on none. */
    val seen: Boolean,
    /** Past ten in an hour: shown without a sound or a banner. */
    val quiet: Boolean,
)

@Serializable
public data class NotificationPack(val uuid: String, val name: String)

@Serializable
public data class NotificationTeam(val id: Int, val name: String)

@Serializable
public data class NotificationActor(val name: String)

/** Each group on or off for the caller, on every device. */
@Serializable
public data class NotificationSettings(
    val invitations: Boolean = true,
    @SerialName("pack_finished") val packFinished: Boolean = true,
    @SerialName("pack_owner") val packOwner: Boolean = true,
)

/** What `PUT /api/notifications/settings` answers: the settings as they now are. */
@Serializable
public data class NotificationSettingsReply(val settings: NotificationSettings)

/** What `POST /api/notifications/seen` answers: the ids asked about that are the caller's, all now seen. */
@Serializable
public data class NotificationsSeen(val ids: List<Long>)
