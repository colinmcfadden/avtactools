package app.ezpztac.android

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.android.packs.MISSION_PACKS
import app.ezpztac.android.packs.PackInvites
import app.ezpztac.android.packs.PackRuntime
import app.ezpztac.missionpacks.InviteAcceptance
import app.ezpztac.missionpacks.InviteState
import app.ezpztac.missionpacks.PackUser
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.auth.AuthLinks
import app.ezpztac.auth.AuthRoute
import app.ezpztac.auth.InviteLinks
import app.ezpztac.android.export.ExportCleaner
import app.ezpztac.data.AccountScope
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.IncomingFiles
import app.ezpztac.data.RouteSession
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.WeatherService
import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiException
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * The shell: reads the session, asks the server what it will allow, works out whose plans are on this device, and says which [Gate]
 * the person is at. Everything it does against the network is allowed to fail: with no signal the app still starts, still shows the
 * plans on the device, and tries again.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val backend: AuthBackend,
    private val accounts: AccountScope,
    private val sync: SyncScheduler,
    private val session: DiagramSession,
    private val routeSession: RouteSession,
    private val weather: WeatherService,
    private val threats: ThreatStore,
    private val exports: ExportCleaner,
    private val incoming: IncomingFiles,
    private val mapTokens: MapTokenSink,
    private val packs: PackRuntime,
    invites: PackInvites,
    private val saved: SavedStateHandle,
    @Named("appVersion") private val version: String,
) : ViewModel() {
    private val config = MutableStateFlow<AppConfig?>(null)
    private val link = MutableStateFlow<AuthRoute?>(null)
    private val ownership = MutableStateFlow<Ownership?>(null)

    /** A link from an email (verify, reset) that has been opened and not yet dealt with. It outranks everything but an update. */
    val pendingLink: StateFlow<AuthRoute?> = link.asStateFlow()

    val gate: StateFlow<Gate> = combine(config, backend.state, ownership) { config, auth, ownership ->
        gateFor(config, auth, ownership, version)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Gate.Starting)

    // An invitation link the app was opened with, until it is accepted or refused for good: kept through a sign-in, a turn of the phone and
    // the process being ended in the back, as the web keeps it for the tab, and so for whoever signs in next, as there.
    private val inviteToken: StateFlow<String?> = saved.getStateFlow(INVITE_TOKEN, null)

    // What became of it is for the account it was accepted as, and asked as that account: nobody who signs in after sees it (it names a pack
    // and a role), and a sign-out forgets it, as the web's goes with its screen.
    private val invitation = InviteAcceptance(invites::accept, viewModelScope)

    /** Where an invitation link the app was opened with stands: waiting for Mission Packs, being accepted, joined, or why not. */
    val inviteState: StateFlow<InviteState> = invitation.state

    /** One more for every try at the link, so the same answer to a new try (a retry that failed as the last one did) is told again. */
    val inviteAttempt: StateFlow<Int> = invitation.attempts

    init {
        viewModelScope.launch {
            backend.restore()
            backend.endSessionIfOfflineTooLong()
            if (backend.state.value is AuthState.SignedIn) refreshUserQuietly()
        }
        viewModelScope.launch { loadConfig() }
        viewModelScope.launch {
            // Whose plans these are is worked out once for each account that signs in here, and again if the gate changes.
            backend.state
                .distinctUntilChangedBy { (it as? AuthState.SignedIn)?.let { s -> s.user.id to s.user.accessOk } ?: it }
                .collect { auth -> onAuth(auth) }
        }
        viewModelScope.launch {
            // Mission packs run for the account while it may have them: apart from the collector above, which does not hear a feature
            // turned on or off by an admin (a refreshed user with the same id and access). A failure (the device's database) is tried
            // again at the next change, or the next launch: it must not end the app.
            combine(backend.state, ownership, ::packGateFor).distinctUntilChanged().collect { gate ->
                when (gate) {
                    is PackGate.Run -> quietly {
                        packs.enable(gate.user)
                        packs.start()
                    }
                    PackGate.Stop -> quietly { packs.disable() }
                    PackGate.Leave -> Unit
                }
            }
        }
        viewModelScope.launch {
            // An invitation is accepted once the person is in (signed in, past the gate, the plans here theirs), and asked about again when
            // Mission Packs are turned on or off for them, or another link comes.
            combine(gate, inviteToken) { gate, token -> (gate as? Gate.Ready)?.user to token }
                .distinctUntilChanged()
                .collect { (user, token) -> acceptInvite(user, token) }
        }
    }

    // Only the link still kept is asked about: the collector works out its next pair while a request is out, and a pair from before the link
    // was let go (a user refreshed meanwhile) must neither send it again nor say it waits. It is let go once it is done with (accepted, or
    // refused for good) and still the one kept: a link that came meanwhile stays. One that met no connection, a busy or failing server, or a
    // refusal about the account (signed out meanwhile, Mission Packs off) is kept for a retry.
    private suspend fun acceptInvite(user: ApiUser?, token: String?) {
        if (user == null || token == null || token != saved.get<String>(INVITE_TOKEN)) return
        if (invitation.run(token, user.hasFeature(MISSION_PACKS), user.id) && saved.get<String>(INVITE_TOKEN) == token) saved[INVITE_TOKEN] = null
    }

    /** Asks again about the invitation link that could not get through. */
    fun retryInvite() {
        viewModelScope.launch { acceptInvite((gate.value as? Gate.Ready)?.user, inviteToken.value) }
    }

    /** Puts away what became of the invitation link, once the person has seen it. */
    fun dismissInvite() {
        invitation.dismiss()
    }

    /**
     * The app has come to the front. Nothing runs while it is not, so a threat picture that went 48 hours unchanged is forgotten now, and
     * an open pack is followed again, with whatever could not get through tried now.
     */
    fun appStarted() {
        threats.expireIfOld()
        packs.foreground(true)
        packs.wake()
    }

    /**
     * The app is going out of sight, and the system may end the process without warning: the open diagram and set of routes are written
     * now rather than after the usual pause, each whatever becomes of the other. An open pack stops being followed (what waits still
     * goes), and while edits to a pack wait a sync is asked for, so they can go if the process is ended. (One asked for while a sync runs
     * is not queued again, and that sync leaves the open pack to its own client: the next launch or the periodic sync sends what is left.)
     */
    fun appStopped() {
        // At once, not after the writes: a quick return to the front (a turn of the phone) must not find packs sent to the back after it.
        packs.foreground(false)
        // Not this screen's to cut short: for an activity that is finishing (Back, swiped from recents) this view model is cleared at once,
        // and a write cancelled half way, having cancelled the session's own delayed one, would leave the change in memory only.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                quietly { session.flush() }
                quietly { routeSession.flush() }
                quietly { if (packs.unsentCount() > 0) sync.requestSync() }
            }
        }
    }

    // A failure here must not end the app. A write that failed is still owed: the session keeps the change and says so
    // ([app.ezpztac.data.DocumentSession.saveFailed]), and the next save tries again.
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun onAuth(auth: AuthState) {
        ownership.value = null
        if (auth !is AuthState.SignedIn) {
            if (auth is AuthState.SignedOut) {
                invitation.forget()                                              // what became of a link names a pack: it does not stay for the next person
                sync.cancelAll()
                closeOpenDocuments()
                // Only once the open documents are written: the last change to a pack's item reaches the pack's queue first. What waits
                // stays on the device for this account's next sign-in, and is never sent as anyone else. A failure must not keep what
                // follows from being cleared.
                quietly { packs.disable() }
                weather.clear()                                                  // where this account's landing zones are does not stay for the next person
                threats.wipe()                                                   // nor does the threat picture: it is the most sensitive thing on the device
                exports.clear()                                                  // nor a .ths or mission that was shared: they are in the clear in the cache
                incoming.clear()                                                 // nor a file another app handed over and nobody has answered: it is held in memory
            }
            return
        }
        if (!auth.user.accessOk) return                                   // held at the gate: nothing of this account is touched yet
        val owner = accounts.ownership(auth.user.id)
        if (owner == Ownership.Unclaimed) {
            accounts.claim(auth.user.id)
            ownership.value = Ownership.Yours
        } else {
            ownership.value = owner
        }
        if (ownership.value == Ownership.Yours) startSyncing()
    }

    /**
     * Whoever signs in next must not find this account's open diagram, or open set of routes, on the map. Each is saved first; if that fails the person is
     * signed out anyway, and one that will not save does not keep the other from closing.
     */
    private suspend fun closeOpenDocuments() {
        try {
            session.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The diagram is closed regardless ([DocumentSession.close]); a change that could not be written is lost with the sign-out.
        }
        try {
            routeSession.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Likewise the set of routes.
        }
    }

    private fun startSyncing() {
        sync.schedulePeriodic()
        sync.requestSync()
    }

    private suspend fun refreshUserQuietly() {
        try {
            backend.refreshUser()
        } catch (_: ApiException) {
            // No signal, or the server is busy: what this device last knew stands. A refused session is announced on the state.
        }
    }

    /** Asks the server for its config. Called at launch and from the "try again" on a banner. */
    suspend fun loadConfig() {
        try {
            val fetched = backend.config()
            config.value = fetched
            mapTokens.update(fetched.mapbox.publicToken)                  // remembered, so the next start with no signal can draw imagery
        } catch (_: ApiException) {
            // Offline: nothing is blocked for want of a config.
        }
    }

    /** The person chose to clear the other account's plans from this device and carry on with their own. */
    fun clearOtherAccountsPlans() {
        val auth = backend.state.value as? AuthState.SignedIn ?: return
        viewModelScope.launch {
            accounts.wipe()
            accounts.claim(auth.user.id)
            ownership.value = Ownership.Yours
            startSyncing()
        }
    }

    /**
     * An address the app was opened with: one of the two emailed sign-in links, which opens its screen; an invitation, which is kept, never
     * shown on the sign-in screens, until the person can open packs; or, as the web reads them apart, both. Anything else is ignored.
     */
    fun onLink(url: String?) {
        AuthLinks.parse(url)?.let { link.value = it }
        InviteLinks.parse(url)?.let { token ->
            // The link kept, opened again: the person is asking again, perhaps after closing what it said, as a reload asks on the web.
            if (token == saved.get<String>(INVITE_TOKEN)) retryInvite() else saved[INVITE_TOKEN] = token
        }
    }

    fun linkHandled() {
        link.value = null
    }

    fun signOut() {
        viewModelScope.launch { backend.logout() }
    }

    /** After the gate is cleared or an admin approves access: learn it, then the account's plans can be looked at. */
    fun recheckAccess() {
        viewModelScope.launch { refreshUserQuietly() }
    }

    private companion object {
        // The invitation link waiting to be accepted, in the saved state (the web's sessionStorage key).
        const val INVITE_TOKEN = "ezpz.packInvite"
    }
}

/** What mission packs should do for the session and the device's plans as they are now. */
internal sealed interface PackGate {
    /** Run for [user]. */
    data class Run(val user: PackUser) : PackGate

    /** Stop: signed in, but not someone packs may run for here now. */
    data object Stop : PackGate

    /**
     * Nothing: the session is not known yet; or the person signed out, where the open documents are written before packs stop; or whose
     * plans these are is being worked out.
     */
    data object Leave : PackGate
}

/**
 * Packs run only for a signed-in account past the `.mil` gate, with Mission Packs on, whose plans the device holds, and stop for another
 * account's plans, so nothing of one person's is sent as another's.
 *
 * Not knowing yet whose plans they are changes nothing. It is so at every launch, a new shell in a live process included (stopping then would
 * stop packs running for this very account), and while each change of session is worked out, which begins by forgetting it: a sign-out's
 * collector can do that before this one has heard of the sign-out, so a stop then would come before the open documents were written. A
 * different account always passes through a sign-out, which stops packs itself; and the engine, enabled for this one, stops any client of
 * another's ([app.ezpztac.missionpacks.PackEngine.enable]).
 */
internal fun packGateFor(auth: AuthState, ownership: Ownership?): PackGate {
    if (auth !is AuthState.SignedIn) return PackGate.Leave
    val user = auth.user
    if (!user.accessOk || !user.hasFeature(MISSION_PACKS)) return PackGate.Stop
    return when (ownership) {
        null -> PackGate.Leave
        Ownership.Yours -> PackGate.Run(PackUser(user.id, user.name))
        is Ownership.SomeoneElses, Ownership.Unclaimed -> PackGate.Stop
    }
}
