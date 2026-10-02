package app.ezpztac.android.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs a sync in the background, whether or not the app is open. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val runner: SyncRunner,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = when (runner.runOnce()) {
        SyncOutcome.Done -> Result.success()
        SyncOutcome.Retry -> Result.retry()
    }
}
