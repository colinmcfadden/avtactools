package app.ezpztac.android.sync

import app.ezpztac.android.AuthBackend
import app.ezpztac.network.AuthState
import app.ezpztac.sync.StopReason
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncReport
import javax.inject.Inject
import javax.inject.Singleton

/** What a scheduled sync should tell WorkManager. */
enum class SyncOutcome {
    /** Done, or nothing to be done: do not run it again for this. */
    Done,

    /** Try again later, with backoff: no signal, a busy or failing server. */
    Retry,
}

/** The scheduler's one question: did it work, or should it be tried again. */
fun outcomeOf(report: SyncReport): SyncOutcome = when (report.stopped) {
    null -> SyncOutcome.Done
    StopReason.Offline, is StopReason.ServerError, is StopReason.RateLimited -> SyncOutcome.Retry
    // Retrying cannot help until the person does something: sign in again, or clear the `.mil` gate. The app shows both.
    is StopReason.SessionEnded, StopReason.AffiliationRequired -> SyncOutcome.Done
}

/** One sync, run from WorkManager or from the app. */
interface SyncRunner {
    suspend fun runOnce(): SyncOutcome
}

@Singleton
class EngineSyncRunner @Inject constructor(
    private val engine: SyncEngine,
    private val auth: AuthBackend,
) : SyncRunner {
    override suspend fun runOnce(): SyncOutcome {
        if (auth.state.value !is AuthState.SignedIn) return SyncOutcome.Done                      // nobody to sync for
        return outcomeOf(engine.sync())
    }
}
