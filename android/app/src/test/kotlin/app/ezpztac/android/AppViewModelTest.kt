package app.ezpztac.android

import androidx.lifecycle.SavedStateHandle
import app.ezpztac.android.packs.PackInvites
import app.ezpztac.android.packs.PackRuntime
import app.ezpztac.auth.AuthRoute
import app.ezpztac.missionpacks.DrainOutcome
import app.ezpztac.missionpacks.InviteState
import app.ezpztac.missionpacks.PackUser
import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import app.ezpztac.network.RateLimitedException
import kotlinx.coroutines.CompletableDeferred
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SequentialIds
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

        /** Whose plans these are takes a moment to find out, as a query of Room's does. */
        var slow = false
        override suspend fun ownership(userId: Int): Ownership {
            if (slow) delay(10)
            return when (owner) {
                null -> Ownership.Unclaimed
                userId -> Ownership.Yours
                else -> Ownership.SomeoneElses(unsynced)
            }
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

    /**
     * Mission packs as the shell drives them: every call, in order. [onDisable] runs as packs are stopped, to look at what is done by then.
     * [failing] makes every call that reaches the device's database throw, as a broken one would.
     */
    private class RecordingPacks : PackRuntime {
        val log = mutableListOf<String>()
        var unsent = 0
        var failing = false
        var onDisable: suspend () -> Unit = {}
        override suspend fun enable(user: PackUser) { broken(); log += "enable ${user.id} ${user.name}" }
        override suspend fun disable() { onDisable(); broken(); log += "disable" }
        override fun start() { log += "start" }
        override fun foreground(visible: Boolean) { log += "foreground $visible" }
        override fun wake() { log += "wake" }
        override suspend fun unsentCount(): Int { broken(); return unsent }
        override suspend fun drainAll(user: PackUser): DrainOutcome = error("the background sync's, not the shell's")
        private fun broken() { if (failing) error("the device's database could not be read") }
    }

    /**
     * The device's store, which can be made to fail every write, as a busy or full database would, or to suspend on every one ([slow]), as
     * Room's do: a write is then a moment in which something else can run.
     */
    private class Flaky(private val inner: InMemorySyncStore = InMemorySyncStore()) : SyncStore, RecordFeed by inner {
        var failing = false
        var slow = false
        override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T {
            if (failing) error("the database was busy")
            if (slow) delay(10)
            return inner.transaction(block)
        }
    }

    /**
     * The server's answer to an invitation link: OP DK joined as an editor, unless [answer] says otherwise. Every token asked about, in order,
     * and the account each was asked as.
     */
    private class FakeInvites : PackInvites {
        val asked = mutableListOf<String>()
        val askedAs = mutableListOf<Int>()
        var answer: suspend (String) -> InviteAccepted = { joinedPack() }
        override suspend fun accept(token: String, asUser: Int): InviteAccepted {
            asked += token
            askedAs += asUser
            return answer(token)
        }
    }

    private class Rig(
        val backend: FakeBackend, val accounts: FakeAccounts, val scheduler: FakeScheduler, val model: AppViewModel, val tokens: FakeTokens = FakeTokens(),
        val repository: DiagramRepository, val session: DiagramSession, val routes: RouteRepository, val routeSession: RouteSession,
        val weather: WeatherService, val weatherCache: KeptWeather, val threats: ThreatStore, val threatVault: KeptThreats, val clock: MutableClock, val incoming: IncomingFiles,
        val packs: RecordingPacks, val store: Flaky, val invites: FakeInvites,
        /** The shell's saved state, as the activity keeps it through a turn of the phone and the process being ended. */
        val saved: SavedStateHandle,
        private val makeShell: (SavedStateHandle) -> AppViewModel,
        val exportsCleared: () -> Int,
    ) {
        /** Another shell over the same process (the activity finished and opened again, or recreated), with [saved] as the state it comes back with. */
        fun newShell(saved: SavedStateHandle = SavedStateHandle()): AppViewModel = makeShell(saved)
    }

    private fun TestScope.rig(
        stored: AuthState = AuthState.SignedIn(user()),
        owner: Int? = null,
        unsynced: Int = 0,
        configure: FakeBackend.() -> Unit = {},
        version: String = "1.7.6",
        setUpPacks: RecordingPacks.() -> Unit = {},
    ): Rig {
        val backend = FakeBackend(stored).apply(configure)
        val accounts = FakeAccounts(owner, unsynced)
        val scheduler = FakeScheduler()
        val tokens = FakeTokens()
        val store = Flaky()
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
        val packs = RecordingPacks().apply(setUpPacks)
        val invites = FakeInvites()
        val shell = { saved: SavedStateHandle ->
            AppViewModel(backend, accounts, scheduler, session, routeSession, weather, threats, { cleared++ }, incoming, tokens, packs, invites, saved, version)
        }
        val saved = SavedStateHandle()
        val model = shell(saved)
        advanceUntilIdle()
        return Rig(
            backend, accounts, scheduler, model, tokens, repository, session, routes, routeSession, weather, weatherCache, threats, threatVault, clock,
            incoming, packs, store, invites, saved, shell,
        ) { cleared }
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

    // -- Mission packs -----------------------------------------------------------------------------

    private fun withoutPacks(id: Int = 1) = user(id).copy(features = mapOf("mission_packs" to false))

    @Test
    fun `packs run for the account signed in, past the gate, with Mission Packs on, whose plans the device holds`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        assertEquals(listOf("enable 1 Pilot 1", "start"), r.packs.log.filter { it.startsWith("enable") || it == "start" })
    }

    @Test
    fun `packs never run for an account held at the gate, without Mission Packs, or on another account's plans`() = runTest(dispatcher) {
        listOf(
            rig(stored = AuthState.SignedIn(user(accessOk = false)), owner = 1),
            rig(stored = AuthState.SignedIn(withoutPacks()), owner = 1),
            rig(owner = 2, unsynced = 3),                                                       // nothing of account 2's may go as account 1
        ).forEach { r -> assertTrue(r.packs.log.toString(), r.packs.log.none { it.startsWith("enable") }) }
    }

    @Test
    fun `nobody signed in runs no packs`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN))
        assertTrue(r.packs.log.toString(), r.packs.log.none { it.startsWith("enable") || it == "start" })
    }

    @Test
    fun `an admin turning Mission Packs off stops them, and on again runs them, with no sign-in between`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.packs.log.clear()
        r.backend.state.value = AuthState.SignedIn(withoutPacks())                             // a refreshed user: same id, same access
        advanceUntilIdle()
        assertEquals(listOf("disable"), r.packs.log)
        r.backend.state.value = AuthState.SignedIn(user())
        advanceUntilIdle()
        assertEquals(listOf("disable", "enable 1 Pilot 1", "start"), r.packs.log)
    }

    @Test
    fun `clearing another account's plans runs packs for the person who cleared them`() = runTest(dispatcher) {
        val r = rig(owner = 2, unsynced = 3)
        r.model.clearOtherAccountsPlans()
        advanceUntilIdle()
        assertEquals("enable 1 Pilot 1", r.packs.log.last { it.startsWith("enable") })
    }

    @Test
    fun `signing out writes and closes the open documents before packs stop, so their last change reaches the pack`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val made = r.routes.create("MISSION 1")
        r.routeSession.open(made.id)
        r.routeSession.edit("Rename set") { it.copy(name = "MISSION 2") }                    // not yet saved: the pause has not passed
        var closedFirst = false
        r.packs.onDisable = { closedFirst = r.routeSession.active.value == null && r.routes.open(made.id)?.name == "MISSION 2" }
        r.packs.log.clear()
        r.model.signOut()
        advanceUntilIdle()
        assertEquals(listOf("disable"), r.packs.log)                                          // once, and only after
        assertTrue(closedFirst)
    }

    @Test
    fun `coming to the front follows an open pack again and tries now what could not get through`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.packs.log.clear()
        r.model.appStarted()
        assertEquals(listOf("foreground true", "wake"), r.packs.log)
    }

    @Test
    fun `going to the back writes the open diagram and set of routes at once, then sends packs to the back`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val diagram = r.repository.create(DiagramTarget(34.78, -84.08, "16S GD 66993 52949"), "LZ HAWK")
        r.session.open(diagram.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        val set = r.routes.create("MISSION 1")
        r.routeSession.open(set.id)
        r.routeSession.edit("Rename set") { it.copy(name = "MISSION 2") }
        r.packs.log.clear()
        val asked = r.scheduler.requested

        r.model.appStopped()
        advanceUntilIdle()

        assertEquals("LZ CROW", r.repository.open(diagram.id)!!.name)
        assertEquals("MISSION 2", r.routes.open(set.id)!!.name)                               // the set of routes was never written at once before
        assertEquals(listOf("foreground false"), r.packs.log)
        assertEquals(asked, r.scheduler.requested)                                            // no pack edit waits: nothing more to ask for
    }

    @Test
    fun `going to the back with pack edits waiting asks for a sync, so they go even if the process is ended`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.packs.unsent = 2
        val asked = r.scheduler.requested
        r.model.appStopped()
        advanceUntilIdle()
        assertEquals(asked + 1, r.scheduler.requested)
    }

    @Test
    fun `a write that fails on the way to the back keeps the change, and the rest still happens`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val diagram = r.repository.create(DiagramTarget(34.78, -84.08, "16S GD 66993 52949"), "LZ HAWK")
        r.session.open(diagram.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.store.failing = true
        r.packs.log.clear()
        r.model.appStopped()
        advanceUntilIdle()
        assertEquals(true, r.session.saveFailed.value)
        assertEquals("LZ CROW", r.session.active.value!!.name)                                // kept, and owed
        assertEquals(listOf("foreground false"), r.packs.log)                                 // the rest still happened
    }

    // As on a device: the main thread runs work resumed on it at once (Dispatchers.Main.immediate), where the queued test dispatcher runs it in
    // turn, which can hide a collector seeing a state another has half changed. And the database's writes suspend, as Room's do.

    @Test
    fun `a session that ends writes and closes the open documents before packs stop, with work on the main thread run at once`() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val r = rig(owner = 1)
        val made = r.routes.create("MISSION 1")
        r.routeSession.open(made.id)
        r.routeSession.edit("Rename set") { it.copy(name = "MISSION 2") }                    // not yet saved: the pause has not passed
        r.store.slow = true
        val closedWhenStopped = mutableListOf<Boolean>()
        r.packs.onDisable = { closedWhenStopped += r.routeSession.active.value == null }
        r.packs.log.clear()

        r.backend.state.value = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "session_revoked")
        advanceUntilIdle()

        assertEquals(listOf("disable"), r.packs.log)                                          // once: never while whose plans these are was being worked out
        assertEquals(listOf(true), closedWhenStopped)
        assertEquals("MISSION 2", r.routes.open(made.id)!!.name)
    }

    @Test
    fun `a new shell in a process where packs run leaves them running while it works out whose plans these are`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.accounts.slow = true
        r.packs.log.clear()
        r.newShell()                                                                            // the activity finished and opened again, the process alive
        advanceUntilIdle()
        assertEquals(listOf("enable 1 Pilot 1", "start"), r.packs.log)                        // asked again, which changes nothing; never stopped
    }

    @Test
    fun `the writes and the sync on the way to the back are not cut short when the shell is cleared at once`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val set = r.routes.create("MISSION 1")
        r.routeSession.open(set.id)
        r.routeSession.edit("Rename set") { it.copy(name = "MISSION 2") }
        r.packs.unsent = 2
        r.store.slow = true
        val asked = r.scheduler.requested

        r.model.appStopped()
        r.model.viewModelScope.cancel()                                                        // Back, or swiped from recents: ON_DESTROY clears the shell at once
        advanceUntilIdle()

        assertEquals("MISSION 2", r.routes.open(set.id)!!.name)
        assertEquals(asked + 1, r.scheduler.requested)
    }

    @Test
    fun `a quick return to the front leaves packs in the front, however long the writes on the way out take`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val diagram = r.repository.create(DiagramTarget(34.78, -84.08, "16S GD 66993 52949"), "LZ HAWK")
        r.session.open(diagram.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.store.slow = true
        r.packs.log.clear()

        r.model.appStopped()
        r.model.appStarted()                                                                    // a turn of the phone, while the write is still going
        advanceUntilIdle()

        assertEquals(listOf("foreground false", "foreground true", "wake"), r.packs.log)
        assertEquals("LZ CROW", r.repository.open(diagram.id)!!.name)
    }

    @Test
    fun `packs that cannot be started do not end the app, and are asked again at the next change`() = runTest(dispatcher) {
        val r = rig(owner = 1, setUpPacks = { failing = true })                                // the device's database could not be read
        r.model.appStopped()
        advanceUntilIdle()
        r.packs.failing = false
        r.backend.state.value = AuthState.SignedIn(withoutPacks())
        advanceUntilIdle()
        r.backend.state.value = AuthState.SignedIn(user())
        advanceUntilIdle()
        assertEquals("enable 1 Pilot 1", r.packs.log.last { it.startsWith("enable") })
    }

    @Test
    fun `packs that cannot be stopped at sign-out do not keep the threat picture and the rest from being cleared`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.threats.add(Threat("SA-6", "SHGPEWRR------", 34.5, -84.5, "", "SOF", radars = Radars.defaultPair()))
        advanceUntilIdle()
        r.packs.failing = true
        r.model.signOut()
        advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertNull(r.threatVault.kept)
        assertEquals(1, r.exportsCleared())
    }

    // -- Invitation links --------------------------------------------------------------------------

    /** The shell's saved state as the activity gives it back after the process was ended in the back. */
    private fun Rig.restored() = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

    @Test
    fun `an invitation link is kept, never opens the sign-in screens, and is accepted once the person has signed in`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), owner = 1)
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertNull(r.model.pendingLink.value)                                                  // not a screen of the sign-in's
        assertEquals(emptyList<String>(), r.invites.asked)                                     // nobody to accept it for yet

        r.backend.state.value = AuthState.SignedIn(user())                                     // signed in, the device's plans theirs
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
        assertEquals(listOf(1), r.invites.askedAs)                                             // as the account signed in, and no other
        assertEquals("You joined OP DK as an editor.", (r.model.inviteState.value as InviteState.Joined).message)
        assertNull(r.saved.get<String>(SAVED_INVITE))                                          // done with: let go

        r.model.retryInvite()
        r.newShell(r.restored())
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)                                    // never asked about again
    }

    @Test
    fun `an invitation waits at the gate, and while the device holds another account's plans`() = runTest(dispatcher) {
        val held = rig(stored = AuthState.SignedIn(user(accessOk = false)), owner = 1)
        held.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), held.invites.asked)

        val shared = rig(owner = 2, unsynced = 3)
        shared.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), shared.invites.asked)
        shared.model.clearOtherAccountsPlans()
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), shared.invites.asked)
    }

    @Test
    fun `an invitation waits while Mission Packs are off, and is accepted when they are turned on`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedIn(withoutPacks()), owner = 1)
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.invites.asked)
        assertTrue(r.model.inviteState.value is InviteState.Waiting)

        r.backend.state.value = AuthState.SignedIn(user())                                     // an admin ticked them; the refreshed user says so
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
        assertTrue(r.model.inviteState.value is InviteState.Joined)
    }

    @Test
    fun `an invitation that met no connection or a busy server is kept for a retry, and one refused for good is let go`() = runTest(dispatcher) {
        listOf(
            NetworkException("no signal", null, requestMayHaveBeenSent = false) to true,
            RateLimitedException("Too many.", retryAfterSeconds = 30) to true,
            ApiException(503, "unavailable", "Down for a moment.") to true,
            ApiException(410, "invite_gone", "That invitation was withdrawn.") to false,
            ApiException(404, "invite_not_found", "No such invitation.") to false,
        ).forEach { (failure, kept) ->
            val r = rig(owner = 1)
            r.invites.answer = { throw failure }
            r.model.onLink(INVITE_LINK)
            advanceUntilIdle()
            assertEquals("$failure", kept, (r.model.inviteState.value as InviteState.Failed).retryable)

            // A retry asks again only while the link is kept; then the process is ended and the next shell finds what was left to do.
            r.invites.answer = { joinedPack() }
            r.model.retryInvite()
            advanceUntilIdle()
            val restored = r.restored()
            r.model.viewModelScope.cancel()
            r.newShell(restored)
            advanceUntilIdle()
            if (kept) {
                assertEquals("$failure", listOf(INVITE_TOKEN, INVITE_TOKEN), r.invites.asked)  // the retry joined it, and it is done with
                assertTrue("$failure", r.model.inviteState.value is InviteState.Joined)
            } else {
                assertEquals("$failure", listOf(INVITE_TOKEN), r.invites.asked)                // let go: never sent again
                assertTrue("$failure", r.model.inviteState.value is InviteState.Failed)
            }
        }
    }

    @Test
    fun `an invitation that met no connection is still there for the next shell if the process was ended first`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.invites.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        val restored = r.restored()
        r.model.viewModelScope.cancel()
        r.invites.answer = { joinedPack() }                                                    // the signal is back
        r.newShell(restored)
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN, INVITE_TOKEN), r.invites.asked)
    }

    @Test
    fun `an invitation is asked about once, however often the shell looks again meanwhile`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val answer = CompletableDeferred<InviteAccepted>()
        r.invites.answer = { answer.await() }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(InviteState.Accepting, r.model.inviteState.value)

        r.model.retryInvite()
        r.backend.state.value = AuthState.SignedIn(user().copy(name = "Pilot One"))            // a refreshed user: the shell looks again
        r.model.onLink(INVITE_LINK)                                                            // the link opened again
        advanceUntilIdle()
        answer.complete(joinedPack())
        advanceUntilIdle()

        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)                                    // a second accept would be refused as used
        assertTrue(r.model.inviteState.value is InviteState.Joined)
    }

    @Test
    fun `an invitation survives the process being ended in the back`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedIn(withoutPacks()), owner = 1)
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        val restored = r.restored()
        r.model.viewModelScope.cancel()                                                         // the process was ended, the shell with it
        r.backend.state.value = AuthState.SignedIn(user())                                     // and Mission Packs were turned on meanwhile

        r.newShell(restored)
        advanceUntilIdle()

        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
    }

    @Test
    fun `an invitation is accepted once, after the sign-in, with work on the main thread run at once`() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val r = rig(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), owner = 1)
        r.accounts.slow = true
        r.model.onLink(INVITE_LINK)
        r.backend.state.value = AuthState.SignedIn(user())
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
        assertTrue(r.model.inviteState.value is InviteState.Joined)
        r.backend.state.value = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN)              // and a sign-out and back in asks nothing more
        advanceUntilIdle()
        r.backend.state.value = AuthState.SignedIn(user())
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
    }

    @Test
    fun `what became of an invitation is put away once the person has seen it`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertTrue(r.model.inviteState.value is InviteState.Joined)
        r.model.dismissInvite()
        assertEquals(InviteState.None, r.model.inviteState.value)
    }

    @Test
    fun `a sign-in link still opens its screen, an address carrying both is read for both, and nothing else is an invitation`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), owner = 1)
        r.model.onLink("https://ezpztac.app/?auth=verify&token=abc123&invite=$INVITE_TOKEN")
        assertEquals(AuthRoute.Verify("abc123"), r.model.pendingLink.value)
        r.model.onLink("https://evil.example/?invite=$OTHER_TOKEN")                            // not the site's: a token of its own, never kept
        r.model.onLink("http://ezpztac.app/?invite=$OTHER_TOKEN")                              // not encrypted
        r.model.onLink("https://ezpztac.app/r/abc")                                            // a shared route: another feature's

        r.backend.state.value = AuthState.SignedIn(user())
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
    }

    // -- Invitation links and accounts ---------------------------------------------------------------

    /** Signs the first account out and the second in, clearing the first's plans so the device is the second's. */
    private fun TestScope.switchAccounts(r: Rig) {
        r.backend.state.value = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN)
        advanceUntilIdle()
        r.backend.state.value = AuthState.SignedIn(user(2))
        advanceUntilIdle()
        r.model.clearOtherAccountsPlans()
        advanceUntilIdle()
        assertEquals(Gate.Ready(user(2), null), r.model.gate.value)
    }

    @Test
    fun `what became of one account's invitation is never shown to the next account that signs in`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertTrue(r.model.inviteState.value is InviteState.Joined)                            // not yet put away when the first signs out

        switchAccounts(r)
        assertEquals(InviteState.None, r.model.inviteState.value)                              // nothing of the first's pack and role

        // The second opens the same link: it is asked about again, as the second, never answered from the first's.
        r.invites.answer = { throw ApiException(410, "invite_gone", "Used.") }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), r.invites.askedAs)
        assertEquals("That invitation has already been used or was withdrawn.", (r.model.inviteState.value as InviteState.Failed).message)
    }

    @Test
    fun `an answer that comes after its account signed out is not shown to the next, and the link it used is let go`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        val answer = CompletableDeferred<InviteAccepted>()
        r.invites.answer = { answer.await() }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(InviteState.Accepting, r.model.inviteState.value)

        switchAccounts(r)
        answer.complete(joinedPack())                                                          // the first's answer, once the second is in
        advanceUntilIdle()

        assertEquals(InviteState.None, r.model.inviteState.value)
        assertNull(r.saved.get<String>(SAVED_INVITE))                                          // the first joined with it: done with
        assertEquals(listOf(1), r.invites.askedAs)
    }

    @Test
    fun `a link one account could not take yet waits for whoever signs in next, as the web keeps it for the tab`() = runTest(dispatcher) {
        val r = rig(stored = AuthState.SignedIn(withoutPacks()), owner = 1)
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertTrue(r.model.inviteState.value is InviteState.Waiting)

        switchAccounts(r)
        assertEquals(listOf(INVITE_TOKEN), r.invites.asked)
        assertEquals(listOf(2), r.invites.askedAs)
        assertTrue(r.model.inviteState.value is InviteState.Joined)
    }

    @Test
    fun `a refusal about the account keeps the link, which is accepted once the account can take it`() = runTest(dispatcher) {
        val r = rig(owner = 1)                                                                 // what this device last knew: Mission Packs on
        r.invites.answer = { throw ApiException(403, "feature_disabled", "Not for this account.") }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertTrue(r.model.inviteState.value is InviteState.Waiting)
        assertEquals(INVITE_TOKEN, r.saved.get<String>(SAVED_INVITE))

        r.invites.answer = { joinedPack() }
        r.backend.state.value = AuthState.SignedIn(withoutPacks())                             // the refreshed user says so...
        advanceUntilIdle()
        r.backend.state.value = AuthState.SignedIn(user())                                     // ...until an admin ticks them
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN, INVITE_TOKEN), r.invites.asked)
        assertTrue(r.model.inviteState.value is InviteState.Joined)
    }

    // The collector works out its next pair while a request is out. A pair from before the link was let go must not send it again (the server
    // would answer that it was used) or say it waits for Mission Packs when nothing waits any more.
    @Test
    fun `a user refreshed while the link is out asks nothing more once it is let go`() = runTest(dispatcher) {
        listOf(user().copy(name = "Pilot One"), withoutPacks()).forEach { refreshed ->
            val r = rig(owner = 1)
            val answer = CompletableDeferred<InviteAccepted>()
            r.invites.answer = { answer.await() }
            r.model.onLink(INVITE_LINK)
            advanceUntilIdle()
            r.backend.state.value = AuthState.SignedIn(refreshed)
            advanceUntilIdle()
            answer.completeExceptionally(ApiException(410, "invite_expired", "Expired."))
            advanceUntilIdle()
            assertEquals("$refreshed", listOf(INVITE_TOKEN), r.invites.asked)
            assertTrue("$refreshed", r.model.inviteState.value is InviteState.Failed)
        }
    }

    @Test
    fun `a retry that fails as the last one did is a new try, so it is told again`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.invites.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        val failed = r.model.inviteState.value
        val tries = r.model.inviteAttempt.value
        r.model.retryInvite()
        advanceUntilIdle()
        assertEquals(failed, r.model.inviteState.value)                                        // the same answer...
        assertEquals(tries + 1, r.model.inviteAttempt.value)                                   // ...to a new try
    }

    @Test
    fun `opening the link kept again asks again, after its notice was closed`() = runTest(dispatcher) {
        val r = rig(owner = 1)
        r.invites.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        r.model.dismissInvite()                                                                // closed, and nothing on the screen to try again with
        r.invites.answer = { joinedPack() }                                                    // the signal is back
        r.model.onLink(INVITE_LINK)
        advanceUntilIdle()
        assertEquals(listOf(INVITE_TOKEN, INVITE_TOKEN), r.invites.asked)
        assertTrue(r.model.inviteState.value is InviteState.Joined)
    }
}

/** Where the shell keeps a link waiting to be accepted (the web's sessionStorage key). */
private const val SAVED_INVITE = "ezpz.packInvite"
private const val OTHER_TOKEN = "Zz9_another-token-0001"

private const val INVITE_TOKEN = "Xq3v_8yQm2LZk9-WbT4sPa7Rr1Nd5Cf6Hg0Jj2Kk3Ll"
private const val INVITE_LINK = "https://ezpztac.app/?invite=$INVITE_TOKEN"

/** What the server answers an accepted pack invitation with: OP DK, Colin's, joined as an editor. */
private fun joinedPack(): InviteAccepted = InviteAccepted(
    Invite(id = 1, role = "editor", status = "accepted", expiresAt = "2026-10-15T00:00:00Z", createdAt = "2026-10-08T00:00:00Z"),
    pack = PackSummary(
        uuid = "p-1", name = "OP DK", description = "", status = "active", role = "editor", owner = PackPerson(1, "Colin M."), headSeq = 0,
        seenSeq = 0, memberCount = 2, audienceCount = 2, itemCount = 0, itemCounts = PackItemCounts(0, 0, 0),
        createdAt = "2026-10-08T00:00:00Z", updatedAt = "2026-10-08T00:00:00Z",
    ),
)
