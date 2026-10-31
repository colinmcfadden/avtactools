package app.ezpztac.android.notifications

import app.ezpztac.android.AuthBackend
import app.ezpztac.android.MinimumVersion
import app.ezpztac.android.holdsBack
import app.ezpztac.android.packs.MISSION_PACKS
import app.ezpztac.android.sync.SyncOutcome
import app.ezpztac.data.AccountScope
import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AuthState
import app.ezpztac.network.NetworkException
import app.ezpztac.network.NotificationFeed
import app.ezpztac.network.NotificationsAnswer
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.markNotificationsSeen
import app.ezpztac.network.notifications
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The two notification calls, for one account: a seam, so the check is tried without a server. */
interface NotificationApi {
    suspend fun feed(etag: String?, user: Int): NotificationsAnswer

    suspend fun seen(ids: Collection<Long>, user: Int)
}

class ApiNotificationApi @Inject constructor(private val client: ApiClient) : NotificationApi {
    override suspend fun feed(etag: String?, user: Int): NotificationsAnswer = client.notifications(etag, user)

    override suspend fun seen(ids: Collection<Long>, user: Int) {
        client.markNotificationsSeen(ids, user)
    }
}

/** What a notification says while the app is in front: the frame's toast, with the way to what it is about. */
data class NotificationToast(val text: String, val tap: NotificationTap?)

/**
 * Whether the app is in front and its frame listening, and the toasts it says: while it is, a notification is a toast, not the system's (§4.4). A frame
 * that is not composed (the gate's screens, the sign-in) is not listening, so what comes then is posted.
 */
@Singleton
class NotificationFront @Inject constructor() {
    private val inFront = MutableStateFlow(false)
    private val said = MutableSharedFlow<NotificationToast>(extraBufferCapacity = 16)

    val toasts: SharedFlow<NotificationToast> = said.asSharedFlow()

    fun foreground(front: Boolean) {
        inFront.value = front
    }

    fun listening(): Boolean = inFront.value && said.subscriptionCount.value > 0

    fun say(toast: NotificationToast): Boolean = said.tryEmit(toast)
}

/**
 * One look at the notifications (docs/NOTIFICATIONS.md §4.4): what the periodic worker does every 15 minutes, and the app as it comes to the front. One at
 * a time in the process, so the two never post the same thing.
 *
 * - Only for the account signed in, not while this version is held back by the server ([holdsBack]): what the sync runner keeps to. Signed out (a sign-out,
 *   or a session ended in a process WorkManager started, where no shell hears of it), what is posted goes and what the device kept is forgotten.
 * - **Held at the gate**, it asks `/api/auth/me` (the only thing a gated account may ask) and notes that it saw the account there; once an administrator has
 *   let them in, it says access was approved, once. The refresh also lifts the gate in the app, if it is open. A gate the person cleared themselves (a
 *   `.mil` code) is not news to them, and nothing is said.
 * - **Past it**, for an account with Mission Packs whose plans are on this device, it asks the feed with the last tag (a 304 is nothing new) and does what
 *   [plan] says: posts, takes down, or says in a toast. Whoever is signed in is asked again once the answer is in, so a look under way when someone signs out
 *   shows them nothing. Without Mission Packs any it posted are taken down.
 */
@Singleton
class NotificationCheck @Inject constructor(
    private val api: NotificationApi,
    private val auth: AuthBackend,
    private val accounts: AccountScope,
    private val minimum: MinimumVersion,
    @Named("appVersion") private val version: String,
    private val book: NotificationBook,
    private val notifier: Notifier,
    private val front: NotificationFront,
) {
    private val lock = Mutex()

    suspend fun run(): SyncOutcome = lock.withLock {
        try {
            look()
        } catch (e: CancellationException) {
            throw e
        } catch (_: NetworkException) {
            SyncOutcome.Retry
        } catch (_: RateLimitedException) {
            SyncOutcome.Retry
        } catch (e: ApiException) {
            // The server busy or failing is tried again later; anything else (a session ended, another account's) waits for the person.
            if (e.status >= 500) SyncOutcome.Retry else SyncOutcome.Done
        }
    }

    /**
     * A sign-out: everything posted goes and what the device kept is forgotten, now and again once a look under way has finished (its answer may have come
     * just before the sign-out did, and it asks who is signed in only before it shows anything).
     */
    suspend fun signedOut() {
        clear()
        lock.withLock { clear() }
    }

    private fun clear() {
        notifier.takeDownAll()
        book.forget()
    }

    private fun stillFor(user: Int): Boolean = (auth.state.value as? AuthState.SignedIn)?.user?.id == user

    private suspend fun look(): SyncOutcome {
        if (auth.state.value is AuthState.Unknown) {
            // WorkManager can start the process for this alone, as for a sync: do what the shell does at a launch.
            auth.restore()
        }
        val state = auth.state.value
        if (state is AuthState.SignedOut) return SyncOutcome.Done.also { clear() }
        val user = (state as? AuthState.SignedIn)?.user ?: return SyncOutcome.Done
        if (holdsBack(version, minimum.remembered.value, minimum.rememberedApi.value)) return SyncOutcome.Done
        if (!user.accessOk) return awaitAccess(user)
        if (book.sawGated(user.id)) gateLifted(user)
        if (!user.hasFeature(MISSION_PACKS)) {
            notifier.apply(user.id, Plan(takeDown = notifier.posted(user.id).keys.toList()))
            return SyncOutcome.Done
        }
        if (accounts.ownership(user.id) != Ownership.Yours) return SyncOutcome.Done
        val answer = api.feed(etagOf(user.id), user.id)
        if (!stillFor(user.id)) return SyncOutcome.Done                                      // signed out (or another account in) while it answered
        // A tag is kept only once everything new in the answer has been shown: one held back must come again, not as a 304.
        if (answer is NotificationsAnswer.Changed) keepEtag(user.id, answer.etag.takeIf { show(user.id, answer.feed) })
        return SyncOutcome.Done
    }

    // The tag is this version's: one that came before an update would answer 304 to a kind of notification the update has learnt to show.
    private fun etagOf(user: Int): String? = book.etag(user)?.takeIf { it.startsWith("$version|") }?.substringAfter('|')

    private fun keepEtag(user: Int, etag: String?) = book.keepEtag(user, etag?.let { "$version|$it" })

    private suspend fun awaitAccess(user: ApiUser): SyncOutcome {
        book.markGated(user.id, true)
        val now = auth.refreshUser()
        if (now.id == user.id && now.accessOk) gateLifted(now)
        return SyncOutcome.Done
    }

    private fun gateLifted(user: ApiUser) {
        book.markGated(user.id, false)
        if (user.affiliationVerified) return                                                // their own .mil code: they know
        if (!(front.listening() && front.say(NotificationToast(NotificationWords.ACCESS_APPROVED.text, null)))) notifier.postAccessApproved(user.id)
    }

    /**
     * Shows what [feed] says is new; whether all of it was (none held back for want of permission, every toast said). A toast is not marked seen on the person's
     * other devices: the frame's toasts can be under a dialog, and nobody may have read it. Its action, pressed, is a tap, which marks it.
     */
    private fun show(user: Int, feed: NotificationFeed): Boolean {
        val plan = plan(feed, book.shown(user), notifier.posted(user), front = front.listening(), mayPost = notifier.mayPost())
        notifier.apply(user, plan)
        val said = plan.toast.filter { toast ->
            front.say(NotificationToast(toast.words.text, NotificationTap(toast.note.id, toast.note.type, user, toast.note.pack?.uuid)))
        }.map { it.note.id }.toSet()
        val unsaid = plan.toast.map { it.note.id }.toSet() - said
        val shownNow = plan.shownNow.toSet() - unsaid
        book.remember(user, feed.notifications.filter { it.id in shownNow }.map { Shown(it.id, it.type, it.pack?.uuid) })
        return !plan.heldBack && unsaid.isEmpty()
    }

    /** A notification tapped (or its toast's action pressed) for [user]: seen everywhere. A failure leaves it to be seen at the next tap. */
    suspend fun seen(user: Int, id: Long) {
        try {
            api.seen(listOf(id), user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
        }
    }
}
