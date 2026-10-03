package app.ezpztac.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.auth.AuthLinks
import app.ezpztac.auth.AuthRoute
import app.ezpztac.data.AccountScope
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.RouteSession
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.WeatherService
import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiException
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val mapTokens: MapTokenSink,
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
    }

    /** The app has come to the front. Nothing runs while it is not, so a threat picture that went 48 hours unchanged is forgotten now. */
    fun appStarted() {
        threats.expireIfOld()
    }

    private suspend fun onAuth(auth: AuthState) {
        ownership.value = null
        if (auth !is AuthState.SignedIn) {
            if (auth is AuthState.SignedOut) {
                sync.cancelAll()
                closeOpenDocuments()
                weather.clear()                                                  // where this account's landing zones are does not stay for the next person
                threats.wipe()                                                   // nor does the threat picture: it is the most sensitive thing on the device
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

    /** An address the app was opened with. Anything that is not one of the two emailed links is ignored. */
    fun onLink(url: String?) {
        AuthLinks.parse(url)?.let { link.value = it }
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
}
