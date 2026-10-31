package app.ezpztac.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.ezpztac.android.sync.SyncOutcome
import app.ezpztac.android.sync.SyncRunner
import app.ezpztac.android.sync.SyncWorker
import app.ezpztac.android.sync.outcomeOf
import app.ezpztac.sync.StopReason
import app.ezpztac.sync.SyncReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncOutcomeTest {
    @Test
    fun `a sync that finished is done, and so is one that has nothing to do`() {
        assertEquals(SyncOutcome.Done, outcomeOf(SyncReport()))
        assertEquals(SyncOutcome.Done, outcomeOf(SyncReport(pushed = 3, pulled = 2)))
        assertEquals(SyncOutcome.Done, outcomeOf(SyncReport(skipped = true)))                // another run had it in hand
    }

    @Test
    fun `no signal, a failing server and a busy one are tried again later`() {
        assertEquals(SyncOutcome.Retry, outcomeOf(SyncReport(stopped = StopReason.Offline)))
        assertEquals(SyncOutcome.Retry, outcomeOf(SyncReport(stopped = StopReason.ServerError(503))))
        assertEquals(SyncOutcome.Retry, outcomeOf(SyncReport(stopped = StopReason.RateLimited(60))))
    }

    @Test
    fun `what only the person can fix is not retried, because retrying cannot help`() {
        assertEquals(SyncOutcome.Done, outcomeOf(SyncReport(stopped = StopReason.SessionEnded("session_revoked"))))
        assertEquals(SyncOutcome.Done, outcomeOf(SyncReport(stopped = StopReason.AffiliationRequired)))
    }

    // -- The worker --------------------------------------------------------------------------------------

    private fun resultFor(outcome: SyncOutcome): ListenableWorker.Result = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = object : SyncRunner { override suspend fun runOnce() = outcome }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                SyncWorker(appContext, workerParameters, runner)
        }
        TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build().doWork()
    }

    @Test
    fun `the worker succeeds when the sync is done, and asks to be retried when it is not`() {
        assertEquals(ListenableWorker.Result.success(), resultFor(SyncOutcome.Done))
        assertEquals(ListenableWorker.Result.retry(), resultFor(SyncOutcome.Retry))
    }
}
