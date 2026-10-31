package app.ezpztac.android.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ezpztac.android.sync.SyncOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Looks at the notifications in the background, whether or not the app is open (docs/NOTIFICATIONS.md §4.4). */
@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val check: NotificationCheck,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = when (check.run()) {
        SyncOutcome.Done -> Result.success()
        SyncOutcome.Retry -> Result.retry()
    }
}

/** When notifications are looked at: every 15 minutes while someone is signed in, and at once when the app comes to the front. */
interface NotificationScheduler {
    fun schedule()

    fun checkNow()

    fun cancel()
}

@Singleton
class WorkManagerNotificationScheduler @Inject constructor(@ApplicationContext private val context: Context) : NotificationScheduler {
    private val work get() = WorkManager.getInstance(context)
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedule() {
        // The least WorkManager allows, the owner's choice (2026-10-28): the system stretches it on a phone left still, and further for an app rarely opened.
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun checkNow() {
        val request = OneTimeWorkRequestBuilder<NotificationWorker>().setConstraints(needsNetwork).build()
        work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        work.cancelUniqueWork(ONCE)
        work.cancelUniqueWork(PERIODIC)
    }

    companion object {
        const val ONCE = "ezpz-notifications"
        const val PERIODIC = "ezpz-notifications-periodic"
    }
}

/** What the shell does with notifications, as one seam: its tests stand in for all of it. */
interface NotificationShell {
    /** What is said in a toast while the frame listens. */
    val toasts: Flow<NotificationToast>

    /** Someone signed in, held at the gate or past it: notifications are looked at from now on. */
    fun signedIn()

    /** Signed out: nothing more is looked at, everything posted is taken down, and what this device kept is forgotten. */
    suspend fun signedOut()

    /** The app came to the front (looked at once, now) or went out of sight. */
    fun foreground(front: Boolean)

    /**
     * A tap from a notification, for [user] signed in, as this device showed it: what it opens is read from what was shown, never from the intent, which any
     * app can make up. Null for another account's, or an id never shown here. The access notification has no id, and opens nothing but the app.
     */
    fun tapped(tap: NotificationTap, user: Int): NotificationTap?

    /** Marks a notification tapped seen on every device. Sent apart from acting on the tap, which waits for no network. */
    suspend fun seen(user: Int, id: Long)

    /** Whether the app holds the system's permission to post (always, below Android 13). The person may still have switched notifications off. */
    fun hasPermission(): Boolean

    /** How often the system's dialog has been asked for here; after two it shows no more. */
    fun asks(): Int

    fun noteAsked()

    fun saidNotNow(user: Int): Boolean

    fun notNow(user: Int)
}

@Singleton
class AppNotifications @Inject constructor(
    private val scheduler: NotificationScheduler,
    private val book: NotificationBook,
    private val notifier: Notifier,
    private val front: NotificationFront,
    private val check: NotificationCheck,
) : NotificationShell {
    override val toasts: Flow<NotificationToast> = front.toasts

    override fun signedIn() = scheduler.schedule()

    override suspend fun signedOut() {
        scheduler.cancel()
        check.signedOut()
    }

    override fun foreground(front: Boolean) {
        this.front.foreground(front)
        if (front) scheduler.checkNow()
    }

    override fun tapped(tap: NotificationTap, user: Int): NotificationTap? {
        if (tap.user != user) return null
        if (tap.type == NotificationTap.ACCESS_APPROVED) return NotificationTap(0, NotificationTap.ACCESS_APPROVED, user)
        val shown = book.entry(user, tap.id) ?: return null
        return NotificationTap(shown.id, shown.type, user, shown.pack)
    }

    override suspend fun seen(user: Int, id: Long) = check.seen(user, id)

    override fun hasPermission(): Boolean = notifier.hasPermission()

    override fun asks(): Int = book.asks()

    override fun noteAsked() = book.noteAsked()

    override fun saidNotNow(user: Int): Boolean = book.saidNotNow(user)

    override fun notNow(user: Int) = book.notNow(user)
}
