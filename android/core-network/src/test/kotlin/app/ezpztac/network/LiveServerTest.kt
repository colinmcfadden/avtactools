package app.ezpztac.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The API client against the **real** server (`backend/tests/live_server.py`): the real routes and the real database
 * logic, behind a real socket. The mock-server tests prove the client handles what the server was recorded saying;
 * these prove the two work together, which is where a refresh token (spent on use) can actually be lost.
 *
 * Access tokens live two seconds here, so a lapse and the refresh that follows are real. Skipped without
 * `EZPZ_LIVE_PYTHON`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveServerTest {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 2)
    }

    @AfterAll
    fun stop() { server?.close() }

    private fun live(): LiveServer = server ?: error("no live server")

    private fun newAccount(approved: Boolean = true): String =
        "pilot-${UUID.randomUUID()}@example.com".also { live().makeAccount(it, approved = approved) }

    private fun clientFor(store: SessionStore, vararg interceptors: Interceptor): ApiClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .apply { interceptors.forEach { addInterceptor(it) } }
            .build()
        return ApiClient(live().baseUrl, http, ClientInfo.android("1.4.0", 212), store)
    }

    private fun lapse() = Thread.sleep(2_600)               // past the two-second access token

    private val diagram: JsonObject = buildJsonObject { put("schema", 2); put("note", "live") }

    // -- The ordinary round trip -------------------------------------------------------

    @Test
    fun `sign in, save, edit, conflict, delete and the change feed`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val client = clientFor(store)
        val user = client.login(email, LiveServer.PASSWORD)
        assertEquals(email, user.email)
        assertTrue(user.accessOk)
        assertTrue(store.read()!!.refreshToken != null)                                      // a native app is given one

        val uuid = UUID.randomUUID().toString()
        val first = client.createLz("LZ LIVE", diagram, uuid)
        assertTrue(first.created)
        assertEquals(1, first.value.revision)
        assertEquals(uuid, first.value.clientUuid)
        assertFalse(client.createLz("LZ LIVE", diagram, uuid).created)                       // the same identity again: the first

        val lz = client.getLz(first.value.id)
        assertEquals("live", (lz.lzData["note"] as JsonPrimitive).content)

        val edited = client.updateLz(lz.id, baseRevision = 1, name = "LZ LIVE 2")
        assertEquals(2, edited.revision)
        val stale = assertThrows<RevisionConflictException> { client.updateLz(lz.id, baseRevision = 1, name = "mine") }
        val server = ApiClient.JSON.decodeFromJsonElement(LzSummary.serializer(), stale.server.let { JsonObject(it - "lz_data") })
        assertEquals("LZ LIVE 2", server.name)
        assertEquals(2, server.revision)

        client.deleteLz(lz.id, baseRevision = 2)
        val feed = client.changes(0)
        val change = feed.changes.single { it.clientUuid == uuid }
        assertTrue(change.deleted)
        assertEquals(3, change.revision)
        assertEquals(emptyList<LzSummary>(), client.listLzs())
        assertEquals(feed.cursor, client.changes(feed.cursor).cursor)                        // nothing new after the cursor
    }

    @Test
    fun `the public config needs no token`() = runBlocking<Unit> {
        val client = clientFor(InMemorySessionStore())
        val config = client.config()
        assertEquals(1, config.configVersion)
        assertFalse(config.updateRequired("android", "1.4.0"))
    }

    // -- Token refresh, for real ------------------------------------------------------------

    @Test
    fun `a lapsed access token is refreshed and the call carries on`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val client = clientFor(store)
        client.login(email, LiveServer.PASSWORD)
        val before = store.read()!!

        lapse()
        assertEquals(emptyList<LzSummary>(), client.listLzs())

        val after = store.read()!!
        assertNotEquals(before.accessToken, after.accessToken)
        assertNotEquals(before.refreshToken, after.refreshToken)                             // rotated
        assertEquals(1, client.deviceSessions().size)                                       // still the same device session
    }

    @Test
    fun `many calls on a lapsed token share one refresh with the real server`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val refreshes = AtomicInteger()
        val client = clientFor(store, Interceptor { chain ->
            if (chain.request().url.encodedPath == "/api/auth/refresh") refreshes.incrementAndGet()
            chain.proceed(chain.request())
        })
        client.login(email, LiveServer.PASSWORD)
        lapse()
        coroutineScope {
            (1..8).map { async(Dispatchers.Default) { client.listLzs() } }.forEach { it.await() }
        }
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `a refresh whose answer was lost is repeated, and the real server accepts the repeat`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val loser = LoseAnswers({ it == "/api/auth/refresh" }, times = 1)
        val client = clientFor(store, loser)
        client.login(email, LiveServer.PASSWORD)
        val before = store.read()!!

        lapse()
        assertEquals(emptyList<LzSummary>(), client.listLzs())                              // the answer was lost once; the repeat worked
        assertEquals(1, loser.lost)

        val after = store.read()!!
        assertNotEquals(before.refreshToken, after.refreshToken)
        assertEquals(1, client.deviceSessions().size)

        // ... and the new token is a real one: it rotates again.
        lapse()
        assertEquals(emptyList<LzSummary>(), client.listLzs())
        assertNotEquals(after.refreshToken, store.read()!!.refreshToken)
    }

    @Test
    fun `a refresh repeated after the grace period is read as theft, and the session ends`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val loser = LoseAnswers({ it == "/api/auth/refresh" }, times = 1, onLost = { live().ageSpentRefreshTokens(120) })
        val client = clientFor(store, loser)
        client.login(email, LiveServer.PASSWORD)

        lapse()
        val e = assertThrows<SessionEndedException> { client.listLzs() }
        assertEquals("refresh_reuse_detected", e.code)
        assertNull(store.read())
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "refresh_reuse_detected"), client.state.value)

        // The account is fine: signing in again starts a new session.
        client.login(email, LiveServer.PASSWORD)
        assertEquals(emptyList<LzSummary>(), client.listLzs())
    }

    // -- Devices -----------------------------------------------------------------------------

    @Test
    fun `signing a device out from another ends it at once`() = runBlocking<Unit> {
        val email = newAccount()
        val storeA = InMemorySessionStore()
        val storeB = InMemorySessionStore()
        val a = clientFor(storeA)
        val b = clientFor(storeB)
        a.login(email, LiveServer.PASSWORD)
        b.login(email, LiveServer.PASSWORD)

        val devices = a.deviceSessions()
        assertEquals(2, devices.size)
        assertEquals(1, devices.count { it.current })
        a.revokeSession(devices.single { !it.current }.id)

        // B's token has not even lapsed, and is refused all the same; its refresh is refused too.
        assertThrows<SessionEndedException> { b.listLzs() }
        assertNull(storeB.read())
        assertEquals(emptyList<LzSummary>(), a.listLzs())                                    // A is unaffected
    }

    @Test
    fun `signing out stops the token working`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val client = clientFor(store)
        client.login(email, LiveServer.PASSWORD)
        val token = store.read()!!.accessToken
        assertTrue(client.logout())
        assertNull(store.read())

        // The server has ended it, not just the app: the old token is refused, and there is no way to refresh it.
        val other = clientFor(InMemorySessionStore(StoredSession(token, null, null, Recorded.user())))
        assertThrows<SessionEndedException> { other.me() }
    }

    // -- Two devices, one record ---------------------------------------------------------------

    @Test
    fun `the same LZ edited on two devices is a conflict, never a silent overwrite`() = runBlocking<Unit> {
        val email = newAccount()
        val a = clientFor(InMemorySessionStore()).also { it.login(email, LiveServer.PASSWORD) }
        val b = clientFor(InMemorySessionStore()).also { it.login(email, LiveServer.PASSWORD) }

        val made = a.createLz("LZ SHARED", diagram, UUID.randomUUID().toString()).value
        b.updateLz(made.id, baseRevision = 1, name = "edited on B")

        val conflict = assertThrows<RevisionConflictException> { a.updateLz(made.id, baseRevision = 1, name = "edited on A") }
        assertEquals("edited on B", (conflict.server["name"] as JsonPrimitive).content)
        // A resolves it against revision 2 and goes on.
        assertEquals(3, a.updateLz(made.id, baseRevision = 2, name = "edited on A, after B").revision)
        assertEquals("edited on A, after B", b.getLz(made.id).name)
    }

    @Test
    fun `a write repeated with the same key is answered, not refused as a conflict with itself`() = runBlocking<Unit> {
        val email = newAccount()
        val client = clientFor(InMemorySessionStore()).also { it.login(email, LiveServer.PASSWORD) }
        val made = client.createLz("LZ ONCE", diagram, UUID.randomUUID().toString()).value
        val first = client.updateLz(made.id, baseRevision = 1, name = "once", idempotencyKey = "key-once")
        val repeat = client.updateLz(made.id, baseRevision = 1, name = "once", idempotencyKey = "key-once")    // the lost response, resent
        assertEquals(first.revision, repeat.revision)
        assertEquals(2, client.getLz(made.id).revision)                                                           // applied once
    }

    // -- Aircraft profiles --------------------------------------------------------------------

    @Test
    fun `a user's own aircraft profile syncs like a saved record`() = runBlocking<Unit> {
        val email = newAccount()
        val client = clientFor(InMemorySessionStore()).also { it.login(email, LiveServer.PASSWORD) }
        val uuid = UUID.randomUUID().toString()
        val made = client.createAircraftProfile(AircraftProfileInput(clientUuid = uuid, name = "My Hawk", designation = "MH-60")).value
        assertEquals(uuid, made.clientUuid)
        assertEquals(1, made.revision)
        assertFalse(made.isSystem)

        val renamed = client.updateAircraftProfile(made.id, baseRevision = 1, input = AircraftProfileInput(name = "Renamed"))
        assertEquals("Renamed", renamed.name)
        assertThrows<RevisionConflictException> { client.updateAircraftProfile(made.id, 1, AircraftProfileInput(name = "stale")) }

        client.deleteAircraftProfile(made.id, baseRevision = 2)
        val tombstone = client.changes(0).changes.single { it.type == "aircraft" }
        assertTrue(tombstone.deleted)
        assertEquals(emptyList<AircraftProfileDto>(), client.aircraftProfiles().filter { !it.isSystem })
    }

    // -- The gate, and account deletion -------------------------------------------------------

    @Test
    fun `an account outside the approval gate can sign in but nothing else`() = runBlocking<Unit> {
        val email = newAccount(approved = false)
        val client = clientFor(InMemorySessionStore())
        val user = client.login(email, LiveServer.PASSWORD)
        assertFalse(user.accessOk)
        assertEquals(email, client.me().email)                                               // the auth flows stay open
        assertThrows<AffiliationRequiredException> { client.listLzs() }
        assertThrows<AffiliationRequiredException> { client.changes(0) }
    }

    @Test
    fun `a wrong password is refused and does not touch a signed-in session`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val client = clientFor(store)
        client.login(email, LiveServer.PASSWORD)
        val before = store.read()
        val e = assertThrows<ApiException> { client.login(email, "not the password") }
        assertEquals("invalid_credentials", e.code)
        assertEquals(before, store.read())
    }

    @Test
    fun `deleting the account needs proof, and afterwards the account is gone`() = runBlocking<Unit> {
        val email = newAccount()
        val store = InMemorySessionStore()
        val client = clientFor(store)
        client.login(email, LiveServer.PASSWORD)
        client.createLz("LZ GONE", diagram, UUID.randomUUID().toString())

        val refused = assertThrows<ApiException> { client.deleteAccount() }                  // no proof
        assertEquals("reauthentication_required", refused.code)
        assertTrue(store.read() != null)                                                     // the session is kept: it was not a refused token

        client.deleteAccount(password = LiveServer.PASSWORD)
        assertNull(store.read())
        assertEquals("invalid_credentials", assertThrows<ApiException> { client.login(email, LiveServer.PASSWORD) }.code)
    }
}
