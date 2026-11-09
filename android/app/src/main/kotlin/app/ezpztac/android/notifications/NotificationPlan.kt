package app.ezpztac.android.notifications

import app.ezpztac.network.AppNotification
import app.ezpztac.network.NotificationFeed
import app.ezpztac.network.NotificationSettings

/** One notification to post, or to post again over itself (its words may have changed: a pack renamed, an invitation's role). [alert] sounds it. */
data class Post(val note: AppNotification, val words: Words, val alert: Boolean)

/** One said in a toast instead, the app being in front. */
data class Toast(val note: AppNotification, val words: Words)

/**
 * What a look at the feed does on this device: what to [post], what to take down (posted notifications by tag), what to say in a toast, and the
 * ids to remember as shown here, so they are never shown on this device again.
 */
data class Plan(
    val post: List<Post> = emptyList(),
    val takeDown: List<String> = emptyList(),
    val toast: List<Toast> = emptyList(),
    val shownNow: List<Long> = emptyList(),
    /** Something new was not shown (no permission to post): the feed must be read whole next time, not answered as nothing new. */
    val heldBack: Boolean = false,
)

/** The tag a notification is posted under: one thing, one notification, so an invitation alerting again replaces the one it alerted with before. */
internal fun tagOf(note: AppNotification): String = "$TAG_PREFIX${note.key}"

internal const val TAG_PREFIX = "ezpz:"

/**
 * Decides what the feed means for this device (docs/NOTIFICATIONS.md §2.3, §4.4).
 *
 * - **Wanted** is what is not seen, in a group the person has on, and has words in this version. Anything posted that is not wanted any more (seen
 *   elsewhere, withdrawn, no longer so, its group turned off) is taken down.
 * - **New** is wanted and never shown on this device ([shown]). With the app in front and listening ([front]) it is said in a toast; otherwise it is
 *   posted, and of several at once only the newest that is not [AppNotification.quiet] sounds (a poll's worth is one alert). What is already posted
 *   is posted again over itself, silently, in case its words changed.
 * - With no permission to post ([mayPost] false) nothing new is posted, and nothing is remembered: once the person allows it, what is still unseen
 *   comes then.
 */
internal fun plan(feed: NotificationFeed, shown: Set<Long>, posted: Map<String, Long>, front: Boolean, mayPost: Boolean): Plan {
    val wanted = feed.notifications.filter { !it.seen && feed.settings.allows(it.group) }.mapNotNull { note -> NotificationWords.of(note)?.let { note to it } }
    val wantedTags = wanted.map { (note, _) -> tagOf(note) }.toSet()
    val takeDown = posted.keys.filter { it !in wantedTags }
    val fresh = wanted.filter { (note, _) -> note.id !in shown }
    if (front) return Plan(takeDown = takeDown, toast = fresh.reversed().map { (note, words) -> Toast(note, words) }, shownNow = fresh.map { it.first.id })
    if (!mayPost) return Plan(takeDown = takeDown, heldBack = fresh.isNotEmpty())
    val sounding = fresh.firstOrNull { (note, _) -> !note.quiet }?.first?.id                            // the feed is newest first
    val again = wanted.filter { (note, _) -> note.id in shown && posted[tagOf(note)] == note.id }
    return Plan(
        post = fresh.map { (note, words) -> Post(note, words, alert = note.id == sounding) } + again.map { (note, words) -> Post(note, words, alert = false) },
        takeDown = takeDown,
        shownNow = fresh.map { it.first.id },
    )
}

/** Whether the person has [group] on. A group this version does not know is passed over, as its notifications are. */
internal fun NotificationSettings.allows(group: String): Boolean = when (group) {
    "invitations" -> invitations
    "pack_finished" -> packFinished
    "pack_owner" -> packOwner
    else -> false
}
