package app.ezpztac.android.notifications

import app.ezpztac.network.AppNotification
import app.ezpztac.sync.cutToCharacters

/** What a notification says: its heading, and the sentence (the toast's words too). */
data class Words(val title: String, val text: String)

/**
 * The words of each notification (docs/NOTIFICATIONS.md §6), made here from its type and the names the server sent: a pack's, a team's, a person's,
 * a role. Never anything else, because they show on a lock screen with the names in them (the owner's choice, 2026-10-28), and the server sends
 * nothing else to make them from. A type this version does not know has no words, and is passed over.
 */
internal object NotificationWords {
    /** The most of a name shown, by characters (never through an emoji); a longer one ends in an ellipsis. */
    const val MAX_NAME = 60

    val ACCESS_APPROVED = Words("Access approved", "Your EZ/PZ access was approved.")

    fun of(note: AppNotification): Words? {
        val pack = note.pack?.name?.let(::name)?.ifBlank { null }
        val team = note.team?.name?.let(::name)?.ifBlank { null }
        val who = note.actor?.name?.let(::name)?.ifBlank { null }
        return when (note.type) {
            "pack_invite" -> pack?.let { Words("Mission Pack invitation", said("${who?.let { "$it invited you" } ?: "You're invited"} to $it${asRole(note.role)}")) }
            "pack_added" -> pack?.let { Words("Added to a Mission Pack", said("${who?.let { "$it added you" } ?: "You were added"} to $it${asRole(note.role)}")) }
            "team_invite" -> team?.let { Words("Team invitation", said("${who?.let { "$it invited you" } ?: "You're invited"} to the team $it${asRole(note.role)}")) }
            "pack_finished" -> pack?.let { Words("Mission Pack finished", said("$it was finished${who?.let { by -> " by $by" }.orEmpty()}") + " It is now read-only.") }
            "pack_owner" -> pack?.let { Words("Mission Pack handed to you", said(who?.let { by -> "$by made you the owner of $it" } ?: "You are now the owner of $it")) }
            else -> null
        }
    }

    /** One line for several showing at once: "3 invitations" when they all are, else "3 notifications". */
    fun summary(count: Int, allInvitations: Boolean): String = if (allInvitations) "$count invitations" else "$count notifications"

    /** A name as it is shown: on one line, cut to [MAX_NAME] characters. */
    fun name(raw: String): String {
        val line = raw.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        val cut = cutToCharacters(line, MAX_NAME)
        return if (cut.length < line.length) cut.trimEnd() + "…" else cut
    }

    /** A sentence ended once: a name that ends in a full stop ("Sam B.", "OP DK.") or an ellipsis is not given another. */
    private fun said(text: String): String = if (text.endsWith('.') || text.endsWith('…')) text else "$text."

    /** The role an invitation offers, or a person was given, as the sentence's end. A team's plain member, and an unknown role, add nothing. */
    private fun asRole(role: String?): String = when (role) {
        "editor" -> " as an editor"
        "viewer" -> " as a viewer"
        "admin" -> " as an admin"
        else -> ""
    }

    const val INVITATIONS = "invitations"
}
