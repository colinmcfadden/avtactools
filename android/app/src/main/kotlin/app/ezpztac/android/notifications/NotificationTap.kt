package app.ezpztac.android.notifications

import android.content.Context
import android.content.Intent
import app.ezpztac.android.MainActivity

/**
 * A notification tapped, or a toast's action pressed (docs/NOTIFICATIONS.md §7): what it was ([type], its server [id]), for whom ([user]), and the
 * pack it is about. An invitation names its pack only for its words: a tap on one opens the switcher, where it is answered by id, never by its
 * link's token, which a notification never carries.
 */
data class NotificationTap(val id: Long, val type: String, val user: Int, val pack: String? = null) {
    /** An invitation, to a pack or a team: answered in the switcher. */
    val invitation: Boolean get() = type == "pack_invite" || type == "team_invite"

    /** About a pack the person is in: added, finished, handed over. A tap opens it. */
    val opensPack: Boolean get() = pack != null && (type == "pack_added" || type == "pack_finished" || type == "pack_owner")

    companion object {
        /** The access approved notification, which has no row on the server (a gated person can reach only the auth routes). */
        const val ACCESS_APPROVED = "access_approved"
    }
}

/**
 * How a tap reaches the app: an intent for [MainActivity] alone, read as the other intents it takes are (`isNews`). Any app can start the activity
 * with any extras, so what is read is checked: a tap only does anything for the account signed in, and for an id this device showed it
 * ([NotificationShell.tapped]).
 */
internal object NotificationTaps {
    const val ACTION = "app.ezpztac.android.NOTIFICATION"
    private const val ID = "ezpz.notification.id"
    private const val TYPE = "ezpz.notification.type"
    private const val USER = "ezpz.notification.user"
    private const val PACK = "ezpz.notification.pack"

    fun intent(context: Context, tap: NotificationTap): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        .putExtra(ID, tap.id).putExtra(TYPE, tap.type).putExtra(USER, tap.user).putExtra(PACK, tap.pack)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun parse(intent: Intent?): NotificationTap? {
        if (intent?.action != ACTION) return null
        val type = intent.getStringExtra(TYPE)?.takeIf { it.length <= 32 } ?: return null
        val user = intent.getIntExtra(USER, -1).takeIf { it >= 0 } ?: return null
        val pack = intent.getStringExtra(PACK)?.takeIf { it.length <= 36 }
        return NotificationTap(intent.getLongExtra(ID, -1), type, user, pack)
    }
}
