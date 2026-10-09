package app.ezpztac.android.sync

import app.ezpztac.android.AuthBackend
import app.ezpztac.android.packs.MISSION_PACKS
import app.ezpztac.android.packs.PackRuntime
import app.ezpztac.data.AccountScope
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.Ownership
import app.ezpztac.data.RouteSession
import app.ezpztac.missionpacks.DrainOutcome
import app.ezpztac.missionpacks.PackUser
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AuthState
import kotlinx.coroutines.CancellationException
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
    private val accounts: AccountScope,
    private val packs: PackRuntime,
    private val diagrams: DiagramSession,
    private val routes: RouteSession,
) : SyncRunner {
    override suspend fun runOnce(): SyncOutcome {
        if (auth.state.value is AuthState.Unknown) {
            // WorkManager can start the process for this alone, with no shell to have read the stored session: this is the process's launch, so do
            // what the shell does at one. The 14-day rule comes before any call, because a refresh would stamp the session as confirmed; a process
            // already launched is left alone, so nobody is signed out in the middle of their work.
            auth.restore()
            if (auth.endSessionIfOfflineTooLong()) return SyncOutcome.Done
        }
        val user = (auth.state.value as? AuthState.SignedIn)?.user ?: return SyncOutcome.Done     // nobody to sync for
        // Only this account's own plans: another account's are never uploaded under this one, and a device nobody has claimed is left to the
        // shell, which claims it once the person is past the gate and then asks for a sync itself.
        if (accounts.ownership(user.id) != Ownership.Yours) return SyncOutcome.Done
        // Mission packs first: what a pack refused is kept in the library as a record of the person's, which then goes up with the rest.
        val drained = drainPacks(user)
        val report = engine.sync()
        // A record the engine gave a new identity while it was open: the open document follows it, or its next save would fork it.
        report.recreated.forEach { (old, new) ->
            diagrams.follow(old, new)
            routes.follow(old, new)
        }
        return if (drained == DrainOutcome.RETRY) SyncOutcome.Retry else outcomeOf(report)
    }

    // Only for an account that may have packs: past the gate, the feature on. What only the account can unblock (PAUSED) is not tried again
    // by WorkManager; it goes at the next sign-in. A failure here never stops the library's own sync.
    private suspend fun drainPacks(user: ApiUser): DrainOutcome {
        if (!user.accessOk || !user.hasFeature(MISSION_PACKS)) return DrainOutcome.DONE
        return try {
            packs.drainAll(PackUser(user.id, user.name))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DrainOutcome.RETRY
        }
    }
}
