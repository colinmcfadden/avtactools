package app.ezpztac.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** When the app's saved records are sent to and read from the server. The work itself is [SyncRunner]. */
interface SyncScheduler {
    /** Sync as soon as there is a connection: after an edit, at launch, after signing in. Asking while one is queued does nothing more. */
    fun requestSync()

    /** Also sync now and then in the background, whether or not the app is open. */
    fun schedulePeriodic()

    /** Stop everything (signed out). */
    fun cancelAll()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {
    private val work get() = WorkManager.getInstance(context)
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(needsNetwork)
            // WorkManager's own backoff, from the same five seconds the engine's RetryPolicy starts at.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .build()
        // KEEP: one waiting is enough. An edit made while a sync runs is picked up by that sync's own loop.
        work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        work.cancelUniqueWork(ONCE)
        work.cancelUniqueWork(PERIODIC)
    }

    companion object {
        const val ONCE = "ezpz-sync"
        const val PERIODIC = "ezpz-sync-periodic"
    }
}
