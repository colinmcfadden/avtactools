package app.ezpztac.android.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The system's notifications as posted (docs/NOTIFICATIONS.md §4.4), and a tap's intent read back (§7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemNotifierTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifier get() = SystemNotifier(context)

    @Before
    fun allow() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun showing() = manager.activeNotifications.associateBy { it.tag }

    private fun post(user: Int, vararg posts: Post, takeDown: List<String> = emptyList()) = notifier.apply(user, Plan(post = posts.toList(), takeDown = takeDown))

    private fun post(note: app.ezpztac.network.AppNotification, alert: Boolean = true) = Post(note, NotificationWords.of(note)!!, alert)

    @Test
    fun `each is posted under its tag, on its group's channel, its names on the lock screen, and two showing have a summary`() {
        post(1, post(note(2)), post(note(1, type = "pack_finished"), alert = false))
        val shown = showing()
        val invite = shown.getValue("ezpz:pack_invite:2").notification
        assertEquals("invitations", invite.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, invite.visibility)
        assertEquals("Sam B. invited you to OP DK as an editor.", invite.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("pack_finished", shown.getValue("ezpz:pack_finished:1").notification.channelId)
        assertTrue(shown.getValue("ezpz-summary").notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(mapOf("ezpz:pack_invite:2" to 2L, "ezpz:pack_finished:1" to 1L), notifier.posted(1))
        assertEquals(emptyMap<String, Long>(), notifier.posted(2))                                  // another account's are not this one's
        assertEquals(Notification.VISIBILITY_PUBLIC, manager.getNotificationChannel("invitations").lockscreenVisibility)
    }

    @Test
    fun `a new notification under a tag still showing is posted afresh, to sound, and a repost over itself only alerts once`() {
        post(1, post(note(1, key = "pack_invite:1")))
        val first = showing().getValue("ezpz:pack_invite:1").postTime
        post(1, post(note(8, key = "pack_invite:1")))                                                    // the invitation alerting again a day on
        val again = showing().getValue("ezpz:pack_invite:1").notification
        assertEquals(8L, notifier.posted(1)["ezpz:pack_invite:1"])
        assertEquals(0, again.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertTrue(showing().getValue("ezpz:pack_invite:1").postTime >= first)
        post(1, post(note(8, key = "pack_invite:1"), alert = false))
        assertTrue(showing().getValue("ezpz:pack_invite:1").notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `taken down to one, the summary goes too`() {
        post(1, post(note(2)), post(note(1)))
        post(1, takeDown = listOf("ezpz:pack_invite:1"))
        assertEquals(setOf("ezpz:pack_invite:2"), showing().keys)
    }

    @Test
    fun `a tap opens the app with what it was, and is read back as it was posted`() {
        post(1, post(note(5, type = "pack_owner")))
        val intent = shadowOf(showing().getValue("ezpz:pack_owner:5").notification.contentIntent).savedIntent
        assertEquals(NotificationTap(5, "pack_owner", 1, "p-5"), NotificationTaps.parse(intent))
        assertNull(NotificationTaps.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(NotificationTaps.parse(Intent(NotificationTaps.ACTION)))                         // no account: nothing
    }

    @Test
    fun `access approved is posted on the account channel and taps to its own type`() {
        notifier.postAccessApproved(3)
        val approved = showing().getValue("ezpz:access").notification
        assertEquals("account", approved.channelId)
        assertEquals(NotificationTap(0, NotificationTap.ACCESS_APPROVED, 3), NotificationTaps.parse(shadowOf(approved.contentIntent).savedIntent))
    }

    @Test
    fun `without the permission nothing is posted, and a sign-out takes down what is`() {
        post(1, post(note(2)), post(note(1)))
        notifier.takeDownAll()
        assertEquals(emptyMap<String, Any>(), showing())
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        post(1, post(note(3)))
        assertEquals(emptyMap<String, Any>(), showing())
    }
}
