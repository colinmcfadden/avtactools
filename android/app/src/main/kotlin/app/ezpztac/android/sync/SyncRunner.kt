package app.ezpztac.android.sync

import app.ezpztac.android.AuthBackend
import app.ezpztac.android.MinimumVersion
import app.ezpztac.android.packs.MISSION_PACKS
import app.ezpztac.android.packs.PackRuntime
import app.ezpztac.data.AccountScope
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.Ownership
import app.ezpztac.data.RouteSession
import app.ezpztac.missionpacks.DrainOutcome
import app.ezpztac.missionpacks.PackUser
import app.ezpztac.network.ApiException
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AuthState
import app.ezpztac.network.isBelowMinimum
import kotlinx.coroutines.CancellationException
import app.ezpztac.sync.StopReason
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncReport
import javax.inject.Inject
import javax.inject.Named
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
    private val minimum: MinimumVersion,
    @Named("appVersion") private val version: String,
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
        // An app the server no longer supports sends nothing, packs or library, until it is updated (the owner's decision): what waits stays on
        // the device, and asking again cannot help (the updated app's launch asks for a sync of its own). A device nobody opens must still hear
        // that the server has raised its minimum, or lowered it again, so it is asked first: the config is public, needs no session and confirms
        // none (the 14-day rule above is untouched), and the server keeps it for a minute. With no answer, the minimum last heard stands.
        lookAtMinimum()
        if (tooOld()) return SyncOutcome.Done
        // Mission packs first: what a pack refused is kept in the library as a record of the person's, which then goes up with the rest.
        val drained = drainPacks(user)
        if (tooOld()) return SyncOutcome.Done                             // the app heard of a raised minimum while the packs went
        val report = engine.sync()
        // A record the engine gave a new identity while it was open: the open document follows it, or its next save would fork it.
        report.recreated.forEach { (old, new) ->
            diagrams.follow(old, new)
            routes.follow(old, new)
        }
        return if (drained == DrainOutcome.RETRY) SyncOutcome.Retry else outcomeOf(report)
    }

    private suspend fun lookAtMinimum() {
        try {
            minimum.remember(auth.config().minAppVersion.android)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
            // No answer: what the device last heard stands.
        }
    }

    private fun tooOld(): Boolean = isBelowMinimum(version, minimum.remembered.value)

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
