package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The account flows against the **real** server: someone with no account signs up, verifies by the emailed link, signs in, is held at the
 * `.mil` gate, clears it, and later forgets and resets the password. The routes are the production ones; only the mail call is replaced
 * (by one that keeps what it was asked to send), which is how a test reads the link token without an inbox. Skipped without
 * `EZPZ_LIVE_PYTHON`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveAccountTest {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 3600)
    }

    @AfterAll
    fun stop() { server?.close() }

    private fun live(): LiveServer = server ?: error("no live server")

    private fun client(store: SessionStore = InMemorySessionStore()): ApiClient {
        live().clearRateLimits()
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        return ApiClient(live().baseUrl, http, ClientInfo.android("1.4.0", 212), store)
    }

    private fun address() = "new-${UUID.randomUUID()}@example.com"

    @Test
    fun `sign up, verify by the emailed link, sign in, and be held at the gate until a mil address clears it`() = runBlocking<Unit> {
        val email = address()
        val api = client()

        val accepted = api.register("New Pilot", email)
        assertTrue(accepted.requiresVerification)

        // Nobody can sign in before verifying, whatever they guess.
        assertThrows<ApiException> { api.login(email, "a guessed flight password") }
        assertThrows<ApiException> { api.verifyEmail("not-the-link", LiveServer.PASSWORD) }.also { assertEquals("invalid_token", it.code) }
        assertThrows<ApiException> { api.verifyEmail(live().emailed("verify", email), "short") }.also { assertTrue(it.message!!.contains("at least")) }

        assertEquals("success", api.verifyEmail(live().emailed("verify", email), LiveServer.PASSWORD).status)
        assertThrows<ApiException> { api.verifyEmail(live().emailed("verify", email), LiveServer.PASSWORD) }       // a link works once

        val user = api.login(email, LiveServer.PASSWORD)
        assertEquals(email, user.email)
        assertFalse(user.accessOk, "a new account is outside the gate until it proves it is military or is approved")

        // The gate holds: the saved-record routes refuse, in the typed way.
        assertThrows<AffiliationRequiredException> { api.listLzs() }

        // The gate's own routes are open to this account.
        assertThrows<ApiException> { api.requestMilCode("pilot@example.com") }.also { assertTrue(it.message!!.contains(".mil")) }
        assertEquals("success", api.requestMilCode("new.pilot@example.mil").status)
        assertEquals("invalid_code", assertThrows<ApiException> { api.verifyMilCode("00000000") }.code)
        assertFalse((api.state.value as AuthState.SignedIn).user.accessOk)

        val cleared = api.verifyMilCode(live().emailed("mil"))
        assertTrue(cleared.accessOk)
        assertTrue((api.state.value as AuthState.SignedIn).user.accessOk)
        assertEquals(0, api.listLzs().size)                                                     // through the gate, with the same token
    }

    @Test
    fun `registering an address twice looks the same, and the second link replaces the first`() = runBlocking<Unit> {
        val email = address()
        val api = client()
        val first = api.register("A", email)
        val firstLink = live().emailed("verify", email)
        val second = api.register("A", email)
        assertEquals(first, second)                                              // the server never says an address is in use
        val secondLink = live().emailed("verify", email)
        assertTrue(firstLink != secondLink)
        assertThrows<ApiException> { api.verifyEmail(firstLink, LiveServer.PASSWORD) }.also { assertEquals("invalid_token", it.code) }
        assertEquals("success", api.verifyEmail(secondLink, LiveServer.PASSWORD).status)
    }

    @Test
    fun `a forgotten password is reset by the emailed link, which ends the sessions that were open`() = runBlocking<Unit> {
        val email = address().also { live().makeAccount(it) }
        val oldDevice = client().also { it.login(email, LiveServer.PASSWORD) }
        assertNotNull(oldDevice.me())

        val api = client()
        // An address nobody has is answered exactly as one that exists.
        assertEquals(api.forgotPassword("nobody-${UUID.randomUUID()}@example.com"), api.forgotPassword(email))
        val link = live().emailed("reset", email)

        assertThrows<ApiException> { api.resetPassword(link, "short") }
        assertEquals("success", api.resetPassword(link, "a brand new flight password").status)
        assertEquals("invalid_token", assertThrows<ApiException> { api.resetPassword(link, "another new flight password") }.code)

        assertThrows<ApiException> { client().login(email, LiveServer.PASSWORD) }                // the old password is gone
        assertEquals(email, client().login(email, "a brand new flight password").email)
        // The device that was signed in before the reset is signed out by it.
        assertThrows<SessionEndedException> { oldDevice.me() }
    }

    @Test
    fun `asking who this is now sees an approval made on the server`() = runBlocking<Unit> {
        val email = address().also { live().makeAccount(it, approved = false) }
        val api = client()
        assertFalse(api.login(email, LiveServer.PASSWORD).accessOk)
        // Nothing changed on the server, so asking changes nothing; the point is that asking works at the gate.
        val asked = api.refreshUser()
        assertEquals(email, asked.email)
        assertFalse((api.state.value as AuthState.SignedIn).user.accessOk)
    }
}
