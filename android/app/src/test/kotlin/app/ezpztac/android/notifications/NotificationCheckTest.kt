package app.ezpztac.android.notifications

import app.ezpztac.android.AuthBackend
import app.ezpztac.android.MinimumVersion
import app.ezpztac.android.sync.SyncOutcome
import app.ezpztac.data.AccountScope
import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiException
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.NetworkException
import app.ezpztac.network.NotificationsAnswer
import app.ezpztac.network.SignedOutReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One look at the notifications (docs/NOTIFICATIONS.md §4.4): for whom, the gate, the tag, what is posted or toasted, and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCheckTest {
    private fun user(id: Int = 1, accessOk: Boolean = true, packs: Boolean = true, milVerified: Boolean = false) = ApiUser(
        id = id, email = "pilot$id@example.com", name = "Pilot $id", role = "user", isAdmin = false, isActive = true,
        features = mapOf("mission_packs" to packs), affiliationVerified = milVerified, accessOk = accessOk,
    )

    private class Auth(initial: AuthState) : AuthBackend {
        override val state = MutableStateFlow(initial)
        var stored: AuthState = initial
        var refreshed: ApiUser? = null
        val calls = mutableListOf<String>()
        override suspend fun restore(): AuthState {
            calls += "restore"
            if (state.value == AuthState.Unknown) state.value = stored
            return state.value
        }
        override suspend fun endSessionIfOfflineTooLong() = false
        override suspend fun refreshUser(): ApiUser {
            calls += "refreshUser"
            val user = refreshed ?: throw NetworkException("no signal", null, requestMayHaveBeenSent = false)
            state.value = AuthState.SignedIn(user)
            return user
        }
        override suspend fun config(): AppConfig = throw NetworkException("no signal", null, requestMayHaveBeenSent = false)
        override suspend fun logout() = true
    }

    private class Accounts(var owner: Int? = 1) : AccountScope {
        override suspend fun ownership(userId: Int): Ownership = when (owner) {
            null -> Ownership.Unclaimed
            userId -> Ownership.Yours
            else -> Ownership.SomeoneElses(0)
        }
        override suspend fun claim(userId: Int) = Unit
        override suspend fun wipe() = Unit
    }

    private class Minimum(kept: String? = null) : MinimumVersion {
        override val remembered = MutableStateFlow(kept)
        override val rememberedApi = MutableStateFlow<List<Int>?>(null)
        override fun remember(minimum: String?, apiMajors: List<Int>?) = Unit
    }

    private class Api : NotificationApi {
        var answer: suspend () -> NotificationsAnswer = { NotificationsAnswer.NotModified }
        val asked = mutableListOf<String?>()
        val seen = mutableListOf<List<Long>>()
        override suspend fun feed(etag: String?, user: Int): NotificationsAnswer {
            asked += etag
            return answer()
        }
        override suspend fun seen(ids: Collection<Long>, user: Int) {
            seen += ids.toList()
        }
    }

    private class Book : NotificationBook {
        val shown = mutableMapOf<Int, MutableList<Shown>>()
        val tags = mutableMapOf<Int, String?>()
        val gated = mutableSetOf<Int>()
        override fun shown(user: Int) = shown[user].orEmpty().map { it.id }.toSet()
        override fun entry(user: Int, id: Long) = shown[user]?.lastOrNull { it.id == id }
        override fun remember(user: Int, shown: Collection<Shown>) { this.shown.getOrPut(user) { mutableListOf() } += shown }
        override fun etag(user: Int) = tags[user]
        override fun keepEtag(user: Int, etag: String?) { tags[user] = etag }
        override fun sawGated(user: Int) = user in gated
        override fun markGated(user: Int, gated: Boolean) { if (gated) this.gated += user else this.gated -= user }
        override fun forget() { shown.clear(); tags.clear(); gated.clear() }
        override fun asks() = 0
        override fun noteAsked() = Unit
        override fun saidNotNow(user: Int) = false
        override fun notNow(user: Int) = Unit
    }

    private class Shade : Notifier {
        var allowed = true
        val showing = mutableMapOf<String, Long>()
        val posted = mutableListOf<Pair<Long, Boolean>>()
        val approved = mutableListOf<Int>()
        override fun mayPost() = allowed
        override fun hasPermission() = allowed
        override fun posted(user: Int) = showing.toMap()
        override fun apply(user: Int, plan: Plan) {
            plan.takeDown.forEach { showing.remove(it) }
            if (!allowed) return
            plan.post.forEach { showing[tagOf(it.note)] = it.note.id; posted += it.note.id to it.alert }
        }
        override fun postAccessApproved(user: Int) { approved += user }
        override fun takeDownAll() = showing.clear()
    }

    private class Rig(state: AuthState, owner: Int? = 1, minimum: String? = null, version: String = "0.1.0") {
        val auth = Auth(state)
        val accounts = Accounts(owner)
        val api = Api()
        val book = Book()
        val shade = Shade()
        val front = NotificationFront()
        val check = NotificationCheck(api, auth, accounts, Minimum(minimum), version, book, shade, front)

        fun tag(user: Int = 1) = book.etag(user)?.substringAfter('|')
    }

    private fun rig(user: ApiUser? = user(), owner: Int? = 1, minimum: String? = null) =
        Rig(user?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), owner, minimum)

    @Test
    fun `nobody signed in, another account's plans, no Mission Packs or a version held back ask nothing`() = runTest {
        for (r in listOf(rig(user = null), rig(owner = 2), rig(user = user(packs = false)), rig(minimum = "9.0.0"))) {
            assertEquals(SyncOutcome.Done, r.check.run())
            assertEquals(emptyList<String?>(), r.api.asked)
        }
    }

    @Test
    fun `a process WorkManager started reads the session first`() = runTest {
        val r = Rig(AuthState.Unknown)
        r.auth.stored = AuthState.SignedIn(user())
        r.check.run()
        assertEquals(listOf("restore"), r.auth.calls)
        assertEquals(listOf<String?>(null), r.api.asked)
    }

    @Test
    fun `what is new is posted and remembered, and the tag goes back next time`() = runTest {
        val r = rig()
        r.api.answer = { NotificationsAnswer.Changed(feed(note(2), note(1)), "\"t1\"") }
        r.check.run()
        assertEquals(listOf(2L to true, 1L to false), r.shade.posted)
        assertEquals(setOf(1L, 2L), r.book.shown(1))
        assertEquals("\"t1\"", r.tag())
        assertEquals(Shown(2, "pack_invite", "p-2"), r.book.entry(1, 2))                              // what a tap on it opens
        r.api.answer = { NotificationsAnswer.NotModified }
        r.check.run()
        assertEquals(listOf(null, "\"t1\""), r.api.asked)
        assertEquals(2, r.shade.posted.size)                                                       // nothing new: nothing posted again
    }

    @Test
    fun `with no permission nothing is posted, and the tag is not kept, so it comes once allowed`() = runTest {
        val r = rig()
        r.shade.allowed = false
        r.api.answer = { NotificationsAnswer.Changed(feed(note(1)), "\"t1\"") }
        r.check.run()
        assertEquals(null, r.tag())
        assertEquals(emptySet<Long>(), r.book.shown(1))
        r.shade.allowed = true
        r.check.run()
        assertEquals(listOf(1L to true), r.shade.posted)
    }

    @Test
    fun `in front and listening what is new is a toast, shown here and not marked seen elsewhere (it may be under a dialog)`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        val toasts = mutableListOf<NotificationToast>()
        val listening = launch { r.front.toasts.collect { toasts += it } }
        r.front.foreground(true)
        r.api.answer = { NotificationsAnswer.Changed(feed(note(1, type = "pack_finished")), "\"t1\"") }
        r.check.run()
        listening.cancel()
        assertEquals(listOf(NotificationToast("OP DK was finished by Sam B. It is now read-only.", NotificationTap(1, "pack_finished", 1, "p-1"))), toasts)
        assertEquals(emptyList<Pair<Long, Boolean>>(), r.shade.posted)
        assertEquals(emptyList<List<Long>>(), r.api.seen)
        assertEquals(setOf(1L), r.book.shown(1))
    }

    @Test
    fun `a look under way when the person signs out shows them nothing, and the sign-out takes all of it down`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.shade.showing["ezpz:pack_invite:3"] = 3L
        val answer = kotlinx.coroutines.CompletableDeferred<NotificationsAnswer>()
        r.api.answer = { answer.await() }
        val look = launch { r.check.run() }
        r.auth.state.value = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN)
        val signedOut = launch { r.check.signedOut() }
        answer.complete(NotificationsAnswer.Changed(feed(note(9)), "\"t\""))
        look.join()
        signedOut.join()
        assertEquals(emptyMap<String, Long>(), r.shade.showing)
        assertEquals(emptyList<Pair<Long, Boolean>>(), r.shade.posted)
        assertEquals(emptySet<Long>(), r.book.shown(1))
        assertEquals(null, r.book.etag(1))
    }

    @Test
    fun `a session ended where no shell hears of it is cleared at the next look`() = runTest {
        val r = rig(user = null)
        r.shade.showing["ezpz:pack_invite:3"] = 3L
        r.book.remember(1, listOf(Shown(3, "pack_invite", "p-3")))
        r.check.run()
        assertEquals(emptyMap<String, Long>(), r.shade.showing)
        assertEquals(emptySet<Long>(), r.book.shown(1))
    }

    @Test
    fun `a tag from before an update is not sent, since the update may show a kind the last version passed over`() = runTest {
        val r = rig()
        r.api.answer = { NotificationsAnswer.Changed(feed(note(1, type = "pack_party", group = "parties")), "\"t1\"") }
        r.check.run()
        assertEquals(listOf<String?>(null), r.api.asked)
        val updated = Rig(AuthState.SignedIn(user()), version = "0.2.0")
        updated.book.tags.putAll(r.book.tags)
        updated.check.run()
        assertEquals(listOf<String?>(null), updated.api.asked)
        r.check.run()
        assertEquals("\"t1\"", r.api.asked.last())
    }

    @Test
    fun `Mission Packs turned off take down what is showing`() = runTest {
        val r = rig(user = user(packs = false))
        r.shade.showing["ezpz:pack_invite:3"] = 3L
        r.check.run()
        assertEquals(emptyMap<String, Long>(), r.shade.showing)
        assertEquals(emptyList<String?>(), r.api.asked)
    }

    @Test
    fun `a gate cleared with the person's own mil code is not announced`() = runTest {
        val r = rig(user = user(accessOk = false))
        r.auth.refreshed = user(accessOk = true, milVerified = true)
        r.check.run()
        r.check.run()
        assertEquals(emptyList<Int>(), r.shade.approved)
        assertFalse(1 in r.book.gated)
    }

    @Test
    fun `in front with nothing listening (the gate's screens) it is posted`() = runTest {
        val r = rig()
        r.front.foreground(true)
        r.api.answer = { NotificationsAnswer.Changed(feed(note(1)), "\"t1\"") }
        r.check.run()
        assertEquals(listOf(1L to true), r.shade.posted)
    }

    @Test
    fun `held at the gate it asks who this is, and says once that access was approved`() = runTest {
        val r = rig(user = user(accessOk = false))
        r.check.run()                                                                               // no signal: still waiting
        assertTrue(1 in r.book.gated)
        r.auth.refreshed = user(accessOk = true)
        r.check.run()
        assertEquals(listOf(1), r.shade.approved)
        assertFalse(1 in r.book.gated)
        r.check.run()
        assertEquals(listOf(1), r.shade.approved)
        assertEquals(listOf("refreshUser", "refreshUser"), r.auth.calls)
    }

    @Test
    fun `the gate lifted by the app itself still says so once`() = runTest {
        val r = rig(user = user(accessOk = false))
        r.auth.refreshed = null
        r.check.run()
        r.auth.state.value = AuthState.SignedIn(user(accessOk = true))                             // the launch's own refresh found it
        r.check.run()
        assertEquals(listOf(1), r.shade.approved)
    }

    @Test
    fun `no signal or a failing server is tried again, a refusal is not`() = runTest {
        val r = rig()
        r.api.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        assertEquals(SyncOutcome.Retry, r.check.run())
        r.api.answer = { throw ApiException(502, null, "bad gateway") }
        assertEquals(SyncOutcome.Retry, r.check.run())
        r.api.answer = { throw ApiException(403, "affiliation_required", "gate") }
        assertEquals(SyncOutcome.Done, r.check.run())
    }
}
