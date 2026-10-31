package app.ezpztac.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the device keeps of notifications: each account's own, the newest 200 ids, all of it forgotten at sign-out but the asks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPreferencesTest {
    private val book get() = NotificationPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `each account keeps its own, and the newest 200 shown, with what a tap on each opens`() {
        book.remember(1, (1L..250L).map { Shown(it, "pack_invite", null) })
        book.remember(2, listOf(Shown(7L, "pack_finished", "6d1c2e8a-4b3f-4a51-9e07-2f8b1c5d7a90")))
        book.keepEtag(1, "\"t\"")
        book.markGated(2, true)
        assertEquals((51L..250L).toSet(), book.shown(1))
        assertEquals(setOf(7L), book.shown(2))
        assertEquals(Shown(7L, "pack_finished", "6d1c2e8a-4b3f-4a51-9e07-2f8b1c5d7a90"), book.entry(2, 7L))
        assertNull(book.entry(2, 8L))
        assertNull(book.entry(1, 7L))                                                    // another account's is not this one's
        assertEquals("\"t\"", book.etag(1))
        assertNull(book.etag(2))
        assertTrue(book.sawGated(2))
        assertFalse(book.sawGated(1))
    }

    @Test
    fun `a sign-out forgets it all but how often the permission was asked for, and a Not now`() {
        book.remember(1, listOf(Shown(1L, "pack_invite", null)))
        book.keepEtag(1, "\"t\"")
        book.noteAsked()
        book.notNow(1)
        book.forget()
        assertEquals(emptySet<Long>(), book.shown(1))
        assertNull(book.etag(1))
        assertEquals(1, book.asks())
        assertTrue(book.saidNotNow(1))
        assertFalse(book.saidNotNow(2))
    }
}
