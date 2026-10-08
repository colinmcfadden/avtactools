package app.ezpztac.android

import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SequentialIds
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.data.AccountScope
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.IncomingFiles
import app.ezpztac.data.Ownership
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSession
import app.ezpztac.model.DiagramTarget
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.NetworkException
import app.ezpztac.network.SignedOutReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import app.ezpztac.data.WeatherApi
import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.WeatherCache
import app.ezpztac.data.WeatherService
import app.ezpztac.model.LatLon
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.model.WeatherSnapshot
import app.ezpztac.network.WeatherReportDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private fun user(id: Int = 1, accessOk: Boolean = true) = ApiUser(
        id = id, email = "pilot$id@example.com", name = "Pilot $id", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = accessOk,
    )

    private fun config(minimum: String? = null, maintenance: Boolean = false) = AppConfig(
        1, "1.7.6", AppConfig.MinAppVersion(android = minimum), AppConfig.Maintenance(maintenance, if (maintenance) "Back soon." else null),
        AppConfig.Services(false, false), AppConfig.MapboxConfig("pk.x"),
    )

    private class FakeBackend(
        var stored: AuthState = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN),
    ) : AuthBackend {
        override val state = MutableStateFlow<AuthState>(AuthState.Unknown)
        var config: Result<AppConfig> = Result.failure(NetworkException("no signal", null, requestMayHaveBeenSent = false))
        var refreshed: Result<ApiUser>? = null
        var offlineTooLong = false
        val calls = mutableListOf<String>()

        /** As [app.ezpztac.network.ApiClient.restore]: the store is read once per process, and after that the state is what it is. */
        override suspend fun restore(): AuthState {
            calls += "restore"
            if (state.value == AuthState.Unknown) state.value = stored
            return state.value
        }
        override suspend fun endSessionIfOfflineTooLong(): Boolean {
            calls += "grace"
            if (!offlineTooLong) return false
            state.value = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long")
            return true
        }
        override suspend fun refreshUser(): ApiUser {
            calls += "refreshUser"
            val result = refreshed ?: Result.failure(NetworkException("no signal", null, requestMayHaveBeenSent = false))
            val user = result.getOrThrow()
            state.value = AuthState.SignedIn(user)
            return user
        }
        override suspend fun config(): AppConfig { calls += "config"; return config.getOrThrow() }
        override suspend fun logout(): Boolean { calls += "logout"; state.value = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN); return true }
    }

    private class FakeAccounts(var owner: Int? = null, var unsynced: Int = 0) : AccountScope {
        val log = mutableListOf<String>()
        override suspend fun ownership(userId: Int): Ownership = when (owner) {
            null -> Ownership.Unclaimed
            userId -> Ownership.Yours
            else -> Ownership.SomeoneElses(unsynced)
        }
        override suspend fun claim(userId: Int) { check(owner == null || owner == userId); owner = userId; log += "claim $userId" }
        override suspend fun wipe() { owner = null; unsynced = 0; log += "wipe" }
    }

    private class FakeScheduler : SyncScheduler {
        var requested = 0
        var periodic = 0
        var cancelled = 0
        override fun requestSync() { requested++ }
        override fun schedulePeriodic() { periodic++ }
        override fun cancelAll() { cancelled++ }
    }

    private class FakeTokens : MapTokenSink {
        val written = mutableListOf<String?>()
        override fun update(token: String?) { written += token }
    }

    private class FakeWeather : WeatherApi {
        override suspend fun weather(at: LatLon) = WeatherReportDto("KRYY", "Cobb County Airport", windSpeedKts = JsonPrimitive(10), notams = JsonObject(emptyMap()))
    }

    private class KeptWeather : WeatherCache {
        var kept: Map<String, WeatherSnapshot> = emptyMap()
        override fun load() = kept
        override fun save(snapshots: Map<String, WeatherSnapshot>) { kept = snapshots }
    }

    private class KeptThreats : ThreatVault {
        var kept: ThreatPicture? = null
        override fun load() = kept
        override fun save(picture: ThreatPicture) { kept = picture }
        override fun wipe() { kept = null }
    }

    private class MutableClock(var now: Long = 1_000_000L)

    private class Rig(
        val backend: FakeBackend, val accounts: FakeAccounts, val scheduler: FakeScheduler, val model: AppViewModel, val tokens: FakeTokens = FakeTokens(),
        val repository: DiagramRepository, val session: DiagramSession, val routes: RouteRepository, val routeSession: RouteSession,
        val weather: WeatherService, val weatherCache: KeptWeather, val threats: ThreatStore, val threatVault: KeptThreats, val clock: MutableClock, val incoming: IncomingFiles, val exportsCleared: () -> Int,
    )

    private fun TestScope.rig(
        stored: AuthState = AuthState.SignedIn(user()),
        owner: Int? = null,
        unsynced: Int = 0,
        configure: FakeBackend.() -> Unit = {},
        version: String = "1.7.6",
    ): Rig {
        val backend = FakeBackend(stored).apply(configure)
        val accounts = FakeAccounts(owner, unsynced)
        val scheduler = FakeScheduler()
        val tokens = FakeTokens()
        val store = InMemorySyncStore()
        val repository = DiagramRepository(SyncRepository(store, SequentialIds("t")), store, RecordingScheduler())
        val session = DiagramSession(repository, backgroundScope)
        val routes = RouteRepository(SyncRepository(store, SequentialIds("r")), store, RecordingScheduler())
        val routeSession = RouteSession(routes, backgroundScope)
        val weatherCache = KeptWeather()
        val weather = WeatherService(FakeWeather(), weatherCache, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val threatVault = KeptThreats()
        val clock = MutableClock()
        val threats = ThreatStore(threatVault, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler)) { clock.now }
        var cleared = 0
        val incoming = IncomingFiles()
        val model = AppViewModel(backend, accounts, scheduler, session, routeSession, weather, threats, { cleared++ }, incoming, tokens, version)
        advanceUntilIdle()
        return Rig(backend, accounts, scheduler, model, tokens, repository, session, routes, routeSession, weather, weatherCache, threats, threatVault, clock, incoming) { cleared }
    }

    // -- Launch ------------------------------------------------------------------------------------

    @Test
    fun `nobody signed in goes to the sign-in, and no sync is asked for`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN))
        assertEquals(Gate.SignedOut(SignedOutReason.NOT_SIGNED_IN, null), r.model.gate.value)
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `a stored session starts the app with no signal at all, on the plans that are here`() = runTest(dispatcher) {
        val r = rig(owner = 1)                                                              // the server cannot be reached: config and refresh fail
        assertEquals(Gate.Ready(user(), null), r.model.gate.value)
        assertEquals(1, r.scheduler.requested)                                              // ... and a sync is queued for when there is signal
        assertEquals(1, r.scheduler.periodic)
        assertEquals(emptyList<String>(), r.accounts.log)                                   // nothing was claimed again or wiped
    }

    @Test
    fun `the first account to sign in on a device claims it`() = runTest(dispatcher) {
        val r = rig(owner = null)
        assertEquals(listOf("claim 1"), r.accounts.log)
        assertTrue(r.model.gate.value is Gate.Ready)
    }

    @Test
    fun `at launch it asks the server who this is, and keeps what changed`() = runTest(dispatcher) {
        val r = rig(owner = 1, stored = AuthState.SignedIn(user(accessOk = false)), configure = { refreshed = Result.success(user(accessOk = true)) })
        assertTrue("refreshUser" in r.backend.calls)
        assertEquals(Gate.Ready(user(), null), r.model.gate.value)                          // an admin approved access while the app was closed
    }

    @Test
    fun `a device offline for too long is signed out on launch, and says why`() = runTest(dispatcher) {
        val r = rig(owner = 1, configure = { offlineTooLong = true })
        assertEquals(Gate.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long"), r.model.gate.value)
        assertEquals(0, r.scheduler.requested)
        assertTrue("its plans were not wiped", "wipe" !in r.accounts.log)
        assertTrue("refreshUser" !in r.backend.calls)                                       // no point asking a server that cannot be reached
    }

    @Test
    fun `a session a background sync found ended before the app was opened still says why at launch`() = runTest(dispatcher) {
        // WorkManager ran a sync in this process before the activity started, and the server had ended the session: the launch must not
        // turn that into a plain sign-in screen.
        val ended = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "session_revoked")
        val r = rig(owner = 1, configure = { state.value = ended })
        assertEquals(Gate.SignedOut(SignedOutReason.SESSION_ENDED, "session_revoked"), r.model.gate.value)   // which AppRoot shows with its notice
        assertTrue("refreshUser" !in r.backend.calls)
        assertEquals(0, r.scheduler.requested)
    }

    // -- What the server says ------------------------------------------------------------------------------

    @Test
    fun `an app the server no longer supports is stopped`() = runTest(dispatcher) {
        val r = rig(owner = 1, configure = { config = Result.success(config(minimum = "1.8.0")) })
        assertEquals(Gate.UpdateRequired("1.8.0"), r.model.gate.value)
    }

    @Test
    fun `maintenance is a banner over the plans, not a wall`() = runTest(dispatcher) {
        val r = rig(owner = 1, configure = { config = Result.success(config(maintenance = true)) })
        assertEquals(Gate.Ready(user(), "Back soon."), r.model.gate.value)
    }

    @Test
    fun `the Mapbox token the server gives is remembered for the next start`() = runTest(dispatcher) {
        val r = rig(owner = 1, configure = { config = Result.success(config(minimum = null)) })
        assertEquals(listOf<String?>("pk.x"), r.tokens.written)
    }

    @Test
    fun `no config means no token is written`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        assertEquals(emptyList<String?>(), r.tokens.written)
    }

    @Test
    fun `a config that could not be fetched at launch can be fetched again`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        assertEquals(Gate.Ready(user(), null), r.model.gate.value)
        r.backend.config = Result.success(config(minimum = "9.0.0"))
        r.model.loadConfig()
        advanceUntilIdle()
        assertEquals(Gate.UpdateRequired("9.0.0"), r.model.gate.value)
    }

    // -- The .mil / approval gate -----------------------------------------------------------------------------------

    @Test
    fun `an account outside the gate is held there, and nothing of it is touched`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedIn(user(accessOk = false)), owner = null)
        assertEquals(Gate.NeedsAffiliation(user(accessOk = false)), r.model.gate.value)
        assertEquals(emptyList<String>(), r.accounts.log)
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `clearing the gate lets the account in and starts its sync`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedIn(user(accessOk = false)), owner = null)
        r.backend.refreshed = Result.success(user(accessOk = true))                         // the code was accepted
        r.model.recheckAccess()
        advanceUntilIdle()
        assertEquals(Gate.Ready(user(), null), r.model.gate.value)
        assertEquals(listOf("claim 1"), r.accounts.log)
        assertEquals(1, r.scheduler.requested)
    }

    // -- Whose plans are here ---------------------------------------------------------------------------------------------

    @Test
    fun `another person's plans are not shown, not uploaded, and not wiped without asking`() = runTest(dispatcher) {
        val r = rig(owner = 2, unsynced = 3)
        assertEquals(Gate.DataBelongsToSomeoneElse(user(), 3), r.model.gate.value)
        assertEquals(0, r.scheduler.requested)
        assertEquals(emptyList<String>(), r.accounts.log)
    }

    @Test
    fun `clearing the other account's plans claims the device and starts the sync`() = runTest(dispatcher) {
        val r = rig(owner = 2, unsynced = 3)
        r.model.clearOtherAccountsPlans()
        advanceUntilIdle()
        assertEquals(listOf("wipe", "claim 1"), r.accounts.log)
        assertEquals(Gate.Ready(user(), null), r.model.gate.value)
        assertEquals(1, r.scheduler.requested)
    }

    @Test
    fun `signing in during the session as someone else is caught the same way`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), owner = 2, unsynced = 1)
        r.backend.state.value = AuthState.SignedIn(user(id = 1))                            // signs in on the sign-in screen
        advanceUntilIdle()
        assertEquals(Gate.DataBelongsToSomeoneElse(user(id = 1), 1), r.model.gate.value)
    }

    @Test
    fun `signing out stops the sync and keeps the plans for when they come back`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.model.signOut()
        advanceUntilIdle()
        assertEquals(Gate.SignedOut(SignedOutReason.NOT_SIGNED_IN, null), r.model.gate.value)
        assertEquals(1, r.scheduler.cancelled)
        assertTrue("wipe" !in r.accounts.log)
        // The same person signing back in finds them.
        r.backend.state.value = AuthState.SignedIn(user())
        advanceUntilIdle()
        assertTrue(r.model.gate.value is Gate.Ready)
    }

    @Test
    fun `signing out saves and closes the open diagram, so the next account does not see it on the map`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val made = r.repository.create(DiagramTarget(34.78, -84.08, "16S GD 66993 52949"), "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }                              // not yet saved: the pause has not passed
        r.model.signOut()
        advanceUntilIdle()
        assertNull(r.session.active.value)
        assertEquals("LZ CROW", r.repository.open(made.id)!!.name)                           // saved first, for when this person comes back
    }

    @Test
    fun `signing out forgets the weather, here and on disk, so where this account's landing zones are does not stay for the next`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.weather.refresh("d1", LatLon(34.78, -84.08))
        advanceUntilIdle()
        assertNotNull(r.weather.stateOf("d1").snapshot)
        assertEquals(setOf("d1"), r.weatherCache.kept.keys)
        r.model.signOut()
        advanceUntilIdle()
        assertNull(r.weather.stateOf("d1").snapshot)
        assertTrue(r.weatherCache.kept.isEmpty())
    }

    @Test
    fun `signing out forgets the threats, here and in their file, so a crew's threat picture does not stay for the next person`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.threats.add(Threat("SA-6", "SHGPEWRR------", 34.5, -84.5, "", "SOF", radars = Radars.defaultPair()))
        advanceUntilIdle()
        assertEquals(1, r.threats.entries.value.size)
        assertNotNull(r.threatVault.kept)
        r.model.signOut()
        advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertNull(r.threatVault.kept)
    }

    @Test
    fun `signing out clears what was exported, and being signed in does not`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        assertEquals(0, r.exportsCleared())
        r.model.signOut()
        advanceUntilIdle()
        assertEquals(1, r.exportsCleared())
    }

    @Test
    fun `signing out forgets a file another app handed over and nobody has answered, and being signed in keeps it`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.incoming.offer("SA-6 site.ths", byteArrayOf(1))
        r.incoming.refuse("gone.lps", "That file could not be read.")
        advanceUntilIdle()
        assertEquals(2, r.incoming.files.value.size)                                          // being signed in leaves them for the person to answer
        r.model.signOut()
        advanceUntilIdle()
        assertTrue(r.incoming.files.value.isEmpty())
    }

    @Test
    fun `the app coming to the front forgets a threat picture that has not been changed in 48 hours, and keeps a newer one`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.threats.add(Threat("SA-6", "SHGPEWRR------", 34.5, -84.5, "", "SOF", radars = Radars.defaultPair()))
        advanceUntilIdle()
        r.clock.now += ThreatStore.RETAIN_MS
        r.model.appStarted()
        assertEquals(1, r.threats.entries.value.size)                                         // exactly 48 hours: still kept
        r.clock.now += 1
        r.model.appStarted()
        advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertNull(r.threatVault.kept)
    }

    @Test
    fun `being signed in does not touch the threats`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.threats.add(Threat("SA-6", "SHGPEWRR------", 34.5, -84.5, "", "SOF", radars = Radars.defaultPair()))
        advanceUntilIdle()
        assertEquals(1, r.threats.entries.value.size)
        assertNotNull(r.threatVault.kept)
    }

    @Test
    fun `signing out saves and closes the open set of routes too`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val made = r.routes.create("MISSION 1")
        r.routeSession.open(made.id)
        r.routeSession.edit("Rename set") { it.copy(name = "MISSION 2") }                   // not yet saved: the pause has not passed
        r.model.signOut()
        advanceUntilIdle()
        assertNull(r.routeSession.active.value)
        assertEquals("MISSION 2", r.routes.open(made.id)!!.name)
    }

    @Test
    fun `a session that ends on its own closes the set of routes too`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val made = r.routes.create("MISSION 1")
        r.routeSession.open(made.id)
        r.backend.state.value = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long")
        advanceUntilIdle()
        assertNull(r.session.active.value)
        assertNull(r.routeSession.active.value)
    }

    @Test
    fun `a session that ends on its own closes the diagram too`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val made = r.repository.create(DiagramTarget(34.78, -84.08, "16S GD 66993 52949"), "LZ HAWK")
        r.session.open(made.id)
        r.backend.state.value = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long")
        advanceUntilIdle()
        assertNull(r.session.active.value)
    }

    @Test
    fun `being signed in leaves an open diagram alone`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val made = r.repository.create(DiagramTarget(34.78, -84.08, "16S GD 66993 52949"), "LZ HAWK")
        r.session.open(made.id)
        r.backend.state.value = AuthState.SignedIn(user().copy(name = "Renamed"))
        advanceUntilIdle()
        assertNotNull(r.session.active.value)
    }

    @Test
    fun `a refreshed answer about the same account does not start the sync again`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val before = r.scheduler.requested
        // The server's answer to "who is this" differs in a detail (a renamed account, an entitlement), but it is the same person at the same gate.
        r.backend.state.value = AuthState.SignedIn(user().copy(name = "Renamed", features = mapOf("threats" to false)))
        advanceUntilIdle()
        assertEquals(before, r.scheduler.requested)
        assertEquals(emptyList<String>(), r.accounts.log)
    }

    // -- Links from emails ------------------------------------------------------------------------------------------------

    @Test
    fun `a verification link is held until it has been dealt with`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.model.onLink("https://ezpztac.app/?auth=verify&token=abc")
        assertEquals(app.ezpztac.auth.AuthRoute.Verify("abc"), r.model.pendingLink.value)
        r.model.linkHandled()
        assertEquals(null, r.model.pendingLink.value)
    }

    @Test
    fun `a link that is not one of the two emailed ones is ignored`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.model.onLink("https://ezpztac.app/")
        r.model.onLink("https://example.com/?auth=verify&token=x")                           // a stranger's address with the right words
        r.model.onLink(null)
        assertEquals(null, r.model.pendingLink.value)
    }

    @Test
    fun `opening a link does not change who is signed in or whose plans are here`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.model.onLink("https://ezpztac.app/?auth=reset&token=abc")
        advanceUntilIdle()
        assertTrue(r.model.gate.value is Gate.Ready)
        assertEquals(emptyList<String>(), r.accounts.log)
    }
}
