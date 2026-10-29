package app.ezpztac.android.notifications

import app.ezpztac.network.AppNotification
import app.ezpztac.network.NotificationActor
import app.ezpztac.network.NotificationFeed
import app.ezpztac.network.NotificationPack
import app.ezpztac.network.NotificationSettings
import app.ezpztac.network.NotificationTeam

/** Notifications as the feed sends them, for the tests. */
internal fun note(
    id: Long,
    type: String = "pack_invite",
    pack: String? = "OP DK",
    team: String? = null,
    actor: String? = "Sam B.",
    role: String? = "editor",
    seen: Boolean = false,
    quiet: Boolean = false,
    key: String = "$type:$id",
    group: String = when (type) {
        "pack_finished" -> "pack_finished"
        "pack_owner" -> "pack_owner"
        else -> "invitations"
    },
) = AppNotification(
    id = id, type = type, group = group, key = key, pack = pack?.let { NotificationPack("p-$id", it) }, team = team?.let { NotificationTeam(7, it) },
    actor = actor?.let { NotificationActor(it) }, inviteId = if (type.endsWith("invite")) id.toInt() else null, role = role,
    createdAt = "2026-10-28T14:02:11.123456", updatedAt = "2026-10-28T14:02:11.123456", seen = seen, quiet = quiet,
)

/** A feed, newest first as the server sends it. */
internal fun feed(vararg notes: AppNotification, settings: NotificationSettings = NotificationSettings()) = NotificationFeed(notes.toList(), settings)
