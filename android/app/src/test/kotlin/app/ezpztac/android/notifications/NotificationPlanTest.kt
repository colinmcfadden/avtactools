package app.ezpztac.android.notifications

import app.ezpztac.network.NotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a look at the feed does on a device (docs/NOTIFICATIONS.md §2.3, §4.4), and what each notification says (§6). */
class NotificationPlanTest {
    private fun ids(posts: List<Post>) = posts.map { it.note.id to it.alert }

    @Test
    fun `what is new is posted, and of several at once only the newest that is not quiet sounds`() {
        val plan = plan(feed(note(3, quiet = true), note(2), note(1)), shown = emptySet(), posted = emptyMap(), front = false, mayPost = true)
        assertEquals(listOf(3L to false, 2L to true, 1L to false), ids(plan.post))
        assertEquals(listOf(3L, 2L, 1L), plan.shownNow)
        assertFalse(plan.heldBack)
    }

    @Test
    fun `what was shown here is never shown again, though posted over itself silently while it shows`() {
        val plan = plan(feed(note(2), note(1)), shown = setOf(1L, 2L), posted = mapOf("ezpz:pack_invite:2" to 2L), front = false, mayPost = true)
        assertEquals(listOf(2L to false), ids(plan.post))                                    // 1 was swiped away: it stays away
        assertEquals(emptyList<Long>(), plan.shownNow)
    }

    @Test
    fun `seen elsewhere, gone from the feed, a group turned off or a type not known are taken down and not posted`() {
        val posted = mapOf("ezpz:pack_invite:1" to 1L, "ezpz:pack_finished:9" to 9L, "ezpz:pack_owner:4" to 4L)
        val plan = plan(
            feed(note(4, type = "pack_owner"), note(1, seen = true), note(5, type = "pack_party", group = "parties"), settings = NotificationSettings(packOwner = false)),
            shown = setOf(1L, 4L, 9L), posted = posted, front = false, mayPost = true,
        )
        assertEquals(posted.keys.toSet(), plan.takeDown.toSet())
        assertEquals(emptyList<Post>(), plan.post)
    }

    @Test
    fun `an invitation sent again a day on replaces the one it alerted with, under its tag`() {
        val again = note(8, key = "pack_invite:1")
        val plan = plan(feed(again), shown = setOf(1L), posted = mapOf("ezpz:pack_invite:1" to 1L), front = false, mayPost = true)
        assertEquals(listOf(8L to true), ids(plan.post))
        assertEquals(emptyList<String>(), plan.takeDown)
        assertEquals("ezpz:pack_invite:1", tagOf(again))
    }

    @Test
    fun `in front what is new is said in toasts, oldest first, and nothing is posted`() {
        val plan = plan(feed(note(3), note(2)), shown = emptySet(), posted = emptyMap(), front = true, mayPost = true)
        assertEquals(listOf(2L, 3L), plan.toast.map { it.note.id })
        assertEquals(emptyList<Post>(), plan.post)
        assertEquals(listOf(3L, 2L), plan.shownNow)
    }

    @Test
    fun `with no permission nothing is posted or remembered, so it comes once the person allows it`() {
        val plan = plan(feed(note(1)), shown = emptySet(), posted = emptyMap(), front = false, mayPost = false)
        assertEquals(Plan(heldBack = true), plan)
        assertFalse(plan(feed(note(1)), shown = setOf(1L), posted = emptyMap(), front = false, mayPost = false).heldBack)
    }

    // -- The words ---------------------------------------------------------------------------------------------------------

    @Test
    fun `each says who did what with which pack or team, and only that`() {
        assertEquals(Words("Mission Pack invitation", "Sam B. invited you to OP DK as an editor."), NotificationWords.of(note(1)))
        assertEquals("You're invited to OP DK as a viewer.", NotificationWords.of(note(1, actor = null, role = "viewer"))?.text)
        assertEquals("Sam B. added you to OP DK as an editor.", NotificationWords.of(note(1, type = "pack_added"))?.text)
        assertEquals("Sam B. invited you to the team 1-171 AVN.", NotificationWords.of(note(1, type = "team_invite", pack = null, team = "1-171 AVN", role = "member"))?.text)
        assertEquals("Sam B. invited you to the team 1-171 AVN as an admin.", NotificationWords.of(note(1, type = "team_invite", pack = null, team = "1-171 AVN", role = "admin"))?.text)
        assertEquals("OP DK was finished by Sam B. It is now read-only.", NotificationWords.of(note(1, type = "pack_finished", role = null))?.text)
        assertEquals("OP DK was finished. It is now read-only.", NotificationWords.of(note(1, type = "pack_finished", actor = null))?.text)
        assertEquals("Sam B. made you the owner of OP DK.", NotificationWords.of(note(1, type = "pack_owner"))?.text)
        assertEquals("You are now the owner of OP DK.", NotificationWords.of(note(1, type = "pack_owner", actor = null))?.text)
    }

    @Test
    fun `a name ending in a full stop is not given a second`() {
        assertEquals("OP DK was finished by Sam B. It is now read-only.", NotificationWords.of(note(1, type = "pack_finished", actor = "Sam B."))?.text)
        assertEquals("Sam invited you to OP D.K.", NotificationWords.of(note(1, pack = "OP D.K.", actor = "Sam", role = "member"))?.text)
        assertEquals("OP DK was finished by Sam. It is now read-only.", NotificationWords.of(note(1, type = "pack_finished", actor = "Sam"))?.text)
    }

    @Test
    fun `a type not known, or one missing what it names, has no words`() {
        assertNull(NotificationWords.of(note(1, type = "pack_party")))
        assertNull(NotificationWords.of(note(1, pack = null)))
        assertNull(NotificationWords.of(note(1, type = "team_invite", pack = "OP DK", team = null)))
    }

    @Test
    fun `a name is one line, cut to 60 characters, never through an emoji`() {
        assertEquals("OP DK ALPHA", NotificationWords.name(" OP DK\nALPHA "))
        val long = "🚁".repeat(70)
        val cut = NotificationWords.name(long)
        assertEquals(NotificationWords.MAX_NAME + 1, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun `several showing say how many, as invitations when they all are`() {
        assertEquals("3 invitations", NotificationWords.summary(3, allInvitations = true))
        assertEquals("2 notifications", NotificationWords.summary(2, allInvitations = false))
    }
}
