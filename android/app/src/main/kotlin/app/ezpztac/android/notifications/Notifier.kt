package app.ezpztac.android.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ezpztac.android.MainActivity
import app.ezpztac.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Where notifications are shown on this device: a seam, so what is decided ([plan]) is tried without the system's. */
interface Notifier {
    /** Whether a notification can be shown at all: the system's permission (Android 13 and later) and the app's switch in its settings. */
    fun mayPost(): Boolean

    /** Whether the app holds the system's permission (always, below Android 13): asking for it again would show nothing. */
    fun hasPermission(): Boolean

    /** What this app has showing for [user]: each one's tag, and the id it was posted for. */
    fun posted(user: Int): Map<String, Long>

    fun apply(user: Int, plan: Plan)

    fun postAccessApproved(user: Int)

    /** Takes down everything this app has showing (a sign-out). */
    fun takeDownAll()
}

/**
 * The system's notifications (docs/NOTIFICATIONS.md §4.4): one channel for each group the person can turn off in the system's settings too, the names shown
 * on the lock screen (`VISIBILITY_PUBLIC`, the owner's choice of 2026-10-28: the words hold no place, §6), several at once gathered under a silent summary, and
 * a tap opening the app with what it was ([NotificationTaps]).
 */
@Singleton
class SystemNotifier @Inject constructor(@ApplicationContext private val context: Context) : Notifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun mayPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() && hasPermission()

    override fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun posted(user: Int): Map<String, Long> = ours()
        .filter { it.tag != SUMMARY_TAG && it.notification.extras.getInt(EXTRA_USER, -1) == user && it.notification.extras.containsKey(EXTRA_ID) }
        .associate { it.tag to it.notification.extras.getLong(EXTRA_ID) }

    @SuppressLint("MissingPermission")                                                   // asked first: mayPost
    override fun apply(user: Int, plan: Plan) {
        plan.takeDown.forEach { manager.cancel(it, ID) }
        if (plan.post.isEmpty() && plan.takeDown.isEmpty()) return
        if (!mayPost()) return
        channels()
        val compat = NotificationManagerCompat.from(context)
        val showing = posted(user)
        plan.post.forEach { post ->
            val channel = CHANNELS[post.note.group] ?: return@forEach
            val tag = tagOf(post.note)
            // A new notification under the tag of one showing (an invitation alerting again, a pack handed back): posted afresh, so it can sound. Over the old
            // one it would be an update, which the system does not sound.
            if (showing[tag].let { it != null && it != post.note.id }) manager.cancel(tag, ID)
            val tap = NotificationTap(post.note.id, post.note.type, user, post.note.pack?.uuid)
            val extras = android.os.Bundle().apply {
                putLong(EXTRA_ID, post.note.id)
                putInt(EXTRA_USER, user)
                putString(EXTRA_GROUP, post.note.group)
            }
            val built = base(channel.id, post.words, tap, tag).setWhen(epochMillis(post.note.createdAt)).setSilent(!post.alert).setOnlyAlertOnce(!post.alert)
                .addExtras(extras).build()
            compat.notify(tag, ID, built)
        }
        // Several showing at once: one summary over them, silent (the newest new one has sounded already), taken away when one is left.
        val groups = ours().filter { it.tag != SUMMARY_TAG && it.notification.extras.getInt(EXTRA_USER, -1) == user && it.tag !in plan.takeDown }
            .associate { it.tag to it.notification.extras.getString(EXTRA_GROUP) } + plan.post.associate { tagOf(it.note) to it.note.group }
        if (groups.size >= 2) {
            val words = Words("EZ/PZ", NotificationWords.summary(groups.size, groups.values.all { it == NotificationWords.INVITATIONS }))
            compat.notify(SUMMARY_TAG, ID, base(CHANNELS.getValue(NotificationWords.INVITATIONS).id, words, tap = null, tag = SUMMARY_TAG).setGroupSummary(true).setSilent(true).build())
        } else {
            manager.cancel(SUMMARY_TAG, ID)
        }
    }

    @SuppressLint("MissingPermission")                                                   // asked first: mayPost
    override fun postAccessApproved(user: Int) {
        if (!mayPost()) return
        channels()
        val tap = NotificationTap(0, NotificationTap.ACCESS_APPROVED, user)
        NotificationManagerCompat.from(context).notify(ACCESS_TAG, ID, base(ACCOUNT.id, NotificationWords.ACCESS_APPROVED, tap, ACCESS_TAG).setGroup(null).build())
    }

    override fun takeDownAll() {
        ours().forEach { manager.cancel(it.tag, it.id) }
    }

    private fun ours() = manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true || it.tag == SUMMARY_TAG }

    private fun base(channel: String, words: Words, tap: NotificationTap?, tag: String): NotificationCompat.Builder {
        val intent = tap?.let { NotificationTaps.intent(context, it) }
            ?: Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opens = PendingIntent.getActivity(context, tag.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_ezpz)
            .setContentTitle(words.title)
            .setContentText(words.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words.text))
            .setContentIntent(opens)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(GROUP)
    }

    /** Made each time something is shown: cheap, and a channel the person deleted in the system's settings comes back as they left it. */
    private fun channels() {
        (CHANNELS.values.toSet() + ACCOUNT).forEach { spec ->
            manager.createNotificationChannel(
                NotificationChannel(spec.id, spec.name, spec.importance).apply {
                    description = spec.description
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    /** A channel: the system shows [name] and [description] in the app's notification settings, where each can be turned off. */
    private data class Channel(val id: String, val name: String, val description: String, val importance: Int)

    private companion object {
        const val ID = 1
        const val GROUP = "app.ezpztac.android.NOTIFICATIONS"
        const val SUMMARY_TAG = "ezpz-summary"
        const val ACCESS_TAG = "${TAG_PREFIX}access"
        const val EXTRA_ID = "ezpz.id"
        const val EXTRA_USER = "ezpz.user"
        const val EXTRA_GROUP = "ezpz.group"

        val ACCOUNT = Channel("account", "Account", "When your access is approved.", NotificationManager.IMPORTANCE_HIGH)

        /** By the group a notification falls under (§5): Invitations, Finished packs, Packs handed to you. */
        val CHANNELS = mapOf(
            "invitations" to Channel("invitations", "Invitations", "Invited to a Mission Pack or a team, or added to a pack.", NotificationManager.IMPORTANCE_HIGH),
            "pack_finished" to Channel("pack_finished", "Finished packs", "A Mission Pack you are in was finished.", NotificationManager.IMPORTANCE_DEFAULT),
            "pack_owner" to Channel("pack_owner", "Packs handed to you", "A Mission Pack was handed to you.", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

/** When it happened, from the server's time (UTC, written with no offset), for the notification's own time; now if it cannot be read. */
internal fun epochMillis(serverTime: String): Long =
    runCatching { LocalDateTime.parse(serverTime).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
