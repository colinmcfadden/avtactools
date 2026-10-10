package app.ezpztac.android.sync

import app.ezpztac.android.ApiClientBackend
import app.ezpztac.android.AuthBackend
import app.ezpztac.android.MinimumVersion
import app.ezpztac.android.packs.PackRuntime
import app.ezpztac.missionpacks.DrainOutcome
import app.ezpztac.missionpacks.PackUser
import app.ezpztac.data.AccountScope
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.Ownership
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSession
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.InMemorySessionStore
import app.ezpztac.network.NetworkException
import app.ezpztac.network.SignedOutReason
import app.ezpztac.network.StoredSession
import app.ezpztac.sync.ApiSyncApi
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SequentialIds
import app.ezpztac.sync.SyncApi
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.doc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The scheduled sync. WorkManager can start the app's process just to run it, with no activity and so no shell to read the stored session
 * first: the runner has to do what the shell does at launch itself, and has to keep to the shell's rule that only the plans of the account
 * signed in are synced.
 */
class EngineSyncRunnerTest {
    private fun config(minimum: String?) = AppConfig(
        1, "1.7.6", AppConfig.MinAppVersion(android = minimum), AppConfig.Maintenance(false, null),
        AppConfig.Services(false, false), AppConfig.MapboxConfig("pk.x"),
    )

    private fun user(id: Int) = ApiUser(
        id = id, email = "pilot$id@example.com", name = "Pilot $id", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = true,
    )

    /** The session as a process sees it: [AuthState.Unknown] until something reads the store, as [ApiClient] has it. */
    private class FakeAuth(
        private val stored: AuthState,
        initial: AuthState = AuthState.Unknown,
        /** Whether the stored session is past the 14 days without the server ([app.ezpztac.network.OfflineGrace]). */
        private val offlineTooLong: Boolean = false,
    ) : AuthBackend {
        override val state = MutableStateFlow(initial)
        var restores = 0
        var graceChecks = 0

        /** As [ApiClient.restore]: the store is read once, and after that the state is what it is. */
        override suspend fun restore(): AuthState {
            if (state.value != AuthState.Unknown) return state.value
            restores++
            state.value = stored
            return stored
        }

        override suspend fun endSessionIfOfflineTooLong(): Boolean {
            graceChecks++
            if (!offlineTooLong || state.value !is AuthState.SignedIn) return false
            state.value = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long")
            return true
        }

        override suspend fun refreshUser(): ApiUser = error("not the runner's to ask")
        /** The server's config, or no answer (the default): the runner asks for the minimum before it sends. */
        var config: Result<AppConfig> = Result.failure(NetworkException("no signal", null, requestMayHaveBeenSent = false))
        var configLooks = 0
        override suspend fun config(): AppConfig { configLooks++; return config.getOrThrow() }
        override suspend fun logout(): Boolean = error("not the runner's to ask")
    }

    /** Whose plans are on the device, and nothing else: the runner reads it and never claims or wipes. */
    private class FakeAccounts(private val owner: Int?) : AccountScope {
        val asked = mutableListOf<Int>()
        override suspend fun ownership(userId: Int): Ownership {
            asked += userId
            return when (owner) {
                null -> Ownership.Unclaimed
                userId -> Ownership.Yours
                else -> Ownership.SomeoneElses(unsyncedChanges = 1)
            }
        }
        override suspend fun claim(userId: Int): Unit = error("the shell claims a device, not a background sync")
        override suspend fun wipe(): Unit = error("the shell wipes a device, not a background sync")
    }

    /**
     * Mission packs as the runner drives them: who it drained for, in order. [drain] is what the drain does on the device (keeping what a
     * pack refused writes a library record), and [outcome] what it says; [fails] makes it throw.
     */
    private class FakePacks(var outcome: DrainOutcome = DrainOutcome.DONE) : PackRuntime {
        val drained = mutableListOf<PackUser>()
        var drain: suspend () -> Unit = {}
        var fails = false
        override suspend fun drainAll(user: PackUser): DrainOutcome {
            drained += user
            if (fails) error("the pack store could not be read")
            drain()
            return outcome
        }
        override suspend fun enable(user: PackUser) = error("the shell's, not the runner's")
        override suspend fun disable() = error("the shell's, not the runner's")
        override fun start() = error("the shell's, not the runner's")
        override fun foreground(visible: Boolean) = error("the shell's, not the runner's")
        override fun wake() = error("the shell's, not the runner's")
        override suspend fun unsentCount(): Int = error("the shell's, not the runner's")
    }

    /** The oldest version the server last said it supports, as the device remembers it. */
    private class KeptMinimum(kept: String? = null) : MinimumVersion {
        override val remembered = MutableStateFlow(kept)
        override fun remember(minimum: String?) { remembered.value = minimum }
    }

    /** The plans on the device: one change made here that nobody has sent. */
    private class Device {
        val store = InMemorySyncStore()
        val ids = SequentialIds("d")
        val records = SyncRepository(store, ids)

        suspend fun withAChange() = apply { records.create(RecordKind.LZ, "LZ HAWK", doc("v" to 1), uuid = "made-here") }
    }

    /** One process's runner, wired as Hilt wires it. */
    private fun TestScope.runner(
        api: SyncApi, device: Device, auth: AuthBackend, accounts: AccountScope, packs: PackRuntime = FakePacks(), minimum: KeptMinimum = KeptMinimum(),
    ) = EngineSyncRunner(
        SyncEngine(api, device.store, device.ids),
        auth,
        accounts,
        packs,
        DiagramSession(DiagramRepository(device.records, device.store, RecordingScheduler()), backgroundScope),
        RouteSession(RouteRepository(device.records, device.store, RecordingScheduler()), backgroundScope),
        minimum,
        VERSION,
    )

    private class Rig(
        val runner: EngineSyncRunner, val auth: FakeAuth, val accounts: FakeAccounts, val server: FakeServer, val device: SyncRepository, val packs: FakePacks,
        val minimum: KeptMinimum,
    )

    /** A runner over a device with a change to send, and a server that holds one record this device has not seen. */
    private suspend fun TestScope.rig(auth: FakeAuth, owner: Int?, minimum: String? = null): Rig {
        val device = Device().withAChange()
        val server = FakeServer()
        server.createElsewhere(RecordKind.LZ, "made-elsewhere", "LZ CROW", doc("v" to 1))
        val accounts = FakeAccounts(owner)
        val packs = FakePacks()
        val kept = KeptMinimum(minimum)
        return Rig(runner(server, device, auth, accounts, packs, kept), auth, accounts, server, device.records, packs, kept)
    }

    // The owner's decision: an app the server no longer supports must not go on writing to shared packs, or the library, behind the "Update
    // required" screen. WorkManager's process never asks for the config, so it goes by the minimum the app last heard.
    @Test
    fun `an app below the minimum the server last gave sends nothing, packs or library, and what waits stays`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1, minimum = "9.0.0")

        assertEquals(SyncOutcome.Done, r.runner.runOnce())                       // asking again cannot help: the updated app asks for its own

        assertEquals(emptyList<String>(), r.server.log)
        assertEquals(emptyList<PackUser>(), r.packs.drained)
        assertEquals(1, r.device.pending())                                       // the change made here, kept for the update
    }

    @Test
    fun `a device nobody opens hears a raised minimum from the server, before it sends anything`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)                       // nothing heard before
        r.auth.config = Result.success(config(minimum = "9.0.0"))

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals("9.0.0", r.minimum.remembered.value)                                 // and the shell, in this process, goes by it too
        assertEquals(emptyList<String>(), r.server.log)
        assertEquals(emptyList<PackUser>(), r.packs.drained)
        assertEquals(1, r.device.pending())
    }

    @Test
    fun `a minimum the server has lowered again lets it sync`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1, minimum = "9.0.0")
        r.auth.config = Result.success(config(minimum = null))

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertNull(r.minimum.remembered.value)
        assertEquals(0, r.device.pending())
        assertEquals(1, r.packs.drained.size)
    }

    @Test
    fun `a raised minimum heard while the packs went keeps the library's sync from going`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)
        r.packs.drain = { r.minimum.remember("9.0.0") }                                    // the app, in this process, heard it meanwhile

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(1, r.device.pending())
        assertEquals(emptyList<String>(), r.server.log)
    }

    @Test
    fun `nobody signed in, or another account's plans, asks nothing about the minimum`() = runTest {
        val nobody = rig(FakeAuth(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN)), owner = 1)
        assertEquals(SyncOutcome.Done, nobody.runner.runOnce())
        assertEquals(0, nobody.auth.configLooks)
        val other = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 2)
        assertEquals(SyncOutcome.Done, other.runner.runOnce())
        assertEquals(0, other.auth.configLooks)
    }

    @Test
    fun `at the minimum, or with none, it syncs as ever`() = runTest {
        listOf(VERSION, "1.0.0", null, "not a version").forEach { minimum ->
            val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1, minimum = minimum)
            assertEquals("$minimum", SyncOutcome.Done, r.runner.runOnce())
            assertEquals("$minimum", 0, r.device.pending())
            assertEquals("$minimum", 1, r.packs.drained.size)
        }
    }

    @Test
    fun `a process the app did not start reads the stored session and syncs`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(1, r.auth.restores)
        assertEquals(1, r.auth.graceChecks)                                       // and asked about the 14 days, as the shell does at launch
        assertEquals(listOf("LZ CROW", "LZ HAWK"), r.server.live(RecordKind.LZ).map { it.name }.sorted())      // what was made here went up
        assertEquals(listOf("LZ CROW", "LZ HAWK"), r.device.records(RecordKind.LZ).map { it.name }.sorted())   // and what was made elsewhere came down
        assertEquals(0, r.device.pending())
    }

    @Test
    fun `a process the app did not start, with the session past the 14 days, ends it and asks nothing of the server`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1)), offlineTooLong = true), owner = 1)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())                       // the person has to sign in: trying again cannot help

        // A refresh would have stamped the session as confirmed, and the person would never have been asked to sign in again.
        assertEquals(emptyList<String>(), r.server.log)
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long"), r.auth.state.value)
        assertEquals(emptyList<Int>(), r.accounts.asked)
        assertEquals(1, r.device.pending())                                      // the change waits for them
    }

    @Test
    fun `another account's plans on the device are neither sent nor pulled over, and that is not retried`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 2)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())                       // only the person can settle whose they are, in the app

        assertEquals(listOf(1), r.accounts.asked)
        assertEquals(emptyList<String>(), r.server.log)                          // not one call: nothing of account 2's went up under account 1
        assertEquals(listOf("LZ HAWK"), r.device.records(RecordKind.LZ).map { it.name })
        assertEquals(1, r.device.pending())                                      // still owed, for account 2 to send when they are back
    }

    @Test
    fun `a device nobody has claimed is left for the app to claim`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = null)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(emptyList<String>(), r.server.log)                          // the shell claims it once the person is past the gate, then asks for a sync
        assertEquals(1, r.device.pending())
    }

    @Test
    fun `with no stored session there is nothing to do, and nothing is asked of the server`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN)), owner = 1)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(1, r.auth.restores)
        assertEquals(emptyList<Int>(), r.accounts.asked)
        assertEquals(emptyList<String>(), r.server.log)
        assertEquals(1, r.device.pending())
    }

    @Test
    fun `a session the process already knows is not read again`() = runTest {
        // The app read it at launch and the server has since ended it: nothing here may turn "your session ended" into anything else.
        val ended = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "session_revoked")
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1)), initial = ended), owner = 1)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(0, r.auth.restores)
        assertEquals(0, r.auth.graceChecks)
        assertEquals(ended, r.auth.state.value)
        assertEquals(emptyList<String>(), r.server.log)
    }

    @Test
    fun `in the app's own process, signed in already, it syncs without reading the store or signing anyone out`() = runTest {
        // The 14 days are the shell's to apply, at launch: a sync must not sign out someone who is in the middle of using the app.
        val r = rig(FakeAuth(stored = AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), initial = AuthState.SignedIn(user(1)), offlineTooLong = true), owner = 1)

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(0, r.auth.restores)
        assertEquals(0, r.auth.graceChecks)
        assertEquals(AuthState.SignedIn(user(1)), r.auth.state.value)
        assertEquals(0, r.device.pending())
    }

    @Test
    fun `a cold process with no signal asks to be tried again`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)
        r.server.failAll = NetworkException("no signal", null, requestMayHaveBeenSent = false)

        assertEquals(SyncOutcome.Retry, r.runner.runOnce())

        assertEquals(1, r.auth.restores)
        assertTrue(r.server.log.isNotEmpty())                                     // it got as far as trying
        assertEquals(1, r.device.pending())
    }

    // -- Mission packs -------------------------------------------------------------------------------

    @Test
    fun `packs are drained before the library's sync, so what a pack refused and kept goes up in the same run`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)
        // Keeping what a pack refused writes a record of the person's to the library.
        r.packs.drain = { r.device.create(RecordKind.LZ, "LZ HAWK (my edits)", doc("v" to 2), uuid = "kept") }

        assertEquals(SyncOutcome.Done, r.runner.runOnce())

        assertEquals(listOf(PackUser(1, "Pilot 1")), r.packs.drained)
        assertTrue(r.server.live(RecordKind.LZ).any { it.name == "LZ HAWK (my edits)" })
        assertEquals(0, r.device.pending())
    }

    @Test
    fun `a pack that could not get through has the run tried again, and the library still syncs`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)
        r.packs.outcome = DrainOutcome.RETRY

        assertEquals(SyncOutcome.Retry, r.runner.runOnce())

        assertEquals(0, r.device.pending())
    }

    @Test
    fun `what only the account can unblock is not tried again by the scheduler`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)
        r.packs.outcome = DrainOutcome.PAUSED                                     // signed out, the feature off: it goes at the next sign-in

        assertEquals(SyncOutcome.Done, r.runner.runOnce())
    }

    @Test
    fun `a drain that fails does not keep the library from syncing, and the run is tried again`() = runTest {
        val r = rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 1)
        r.packs.fails = true

        assertEquals(SyncOutcome.Retry, r.runner.runOnce())

        assertEquals(0, r.device.pending())
    }

    @Test
    fun `an account without Mission Packs, held at the gate, or on another account's plans is not drained`() = runTest {
        listOf(
            rig(FakeAuth(stored = AuthState.SignedIn(user(1).copy(features = mapOf("mission_packs" to false)))), owner = 1),
            rig(FakeAuth(stored = AuthState.SignedIn(user(1).copy(accessOk = false))), owner = 1),
            rig(FakeAuth(stored = AuthState.SignedIn(user(1))), owner = 2),
        ).forEach { r ->
            r.runner.runOnce()
            assertEquals(emptyList<PackUser>(), r.packs.drained)
        }
    }

    /** A server that has ended the session: every token is refused, and so is the refresh. Answered here, so nothing goes over the network. */
    private fun serverThatEndedTheSession(asked: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        asked += path
        val body = if (path == "/api/auth/refresh") """{"code": "refresh_reuse_detected", "error": "This session has expired. Sign in again."}"""
        else """{"msg": "Token has been revoked"}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test
    fun `a session the server ended in a process the app did not start still says why when the app is opened`() = runTest {
        // The real client and store: the reason has to outlive the process that found it, which is gone by the time anyone opens the app.
        val sessions = InMemorySessionStore(StoredSession("access-1", "refresh-1", refreshExpiresAtEpochSeconds = null, user = user(1)))
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val http = serverThatEndedTheSession(asked)
        fun process() = ApiClient("https://ezpz.invalid/", http, ClientInfo.android("1.4.0", 212), sessions)
        val cold = process()
        val device = Device().withAChange()

        assertEquals(SyncOutcome.Done, runner(ApiSyncApi(cold), device, ApiClientBackend(cold), FakeAccounts(owner = 1)).runOnce())

        assertTrue(asked.contains("/api/auth/refresh"))
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "refresh_reuse_detected"), process().restore())
        assertEquals(1, device.records.pending())                                 // the change waits for the person to sign in again
    }
}

private const val VERSION = "1.7.6"
