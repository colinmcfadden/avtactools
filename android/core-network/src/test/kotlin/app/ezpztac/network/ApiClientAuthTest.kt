package app.ezpztac.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sign-in, token refresh and sign-out against a mock server that answers with the **recorded real responses**
 * (`Recorded`). The server spends a refresh token on use, so most of what is tested here is the ways that
 * can go wrong: a refresh nobody waits for, a response that never arrives, many calls refused at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientAuthTest {
    // -- Headers and sign-in -----------------------------------------------------------

    @Test
    fun `every request says which app it is, and a signed-in one carries its token`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lz: list") }
            rig.client.listLzs()
            val request = rig.requests.single()
            assertEquals("android/1.4.0 (212)", request.getHeader("X-EZPZ-Client"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Accept"))
        }
    }

    @Test
    fun `a call that needs no token sends none`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { Recorded.mock("config") }
            rig.client.config()
            val request = rig.requests.single()
            assertEquals("android/1.4.0 (212)", request.getHeader("X-EZPZ-Client"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun `signing in stores the session, refresh token included, and announces it`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { Recorded.mock("login: android", mapOf("access-token" to "access-A", "refresh-token" to "refresh-A")) }
            val user = rig.client.login("pilot@example.com", "a secure flight password")

            assertEquals("pilot@example.com", user.email)
            assertEquals(listOf("write access-A/refresh-A"), rig.store.events)
            assertEquals(AuthState.SignedIn(user), rig.client.state.value)
            val sent = rig.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("""{"email":"pilot@example.com","password":"a secure flight password"}""", sent.body.readUtf8())
            assertNull(sent.getHeader("Authorization"))
            // The refresh token's life: 30 days from now, as the server said.
            val expires = rig.store.current!!.refreshExpiresAtEpochSeconds!!
            assertTrue(expires > System.currentTimeMillis() / 1000 + 29 * 24 * 3600)
        }
    }

    @Test
    fun `a wrong password is an error, not a refresh`() = runBlocking<Unit> {
        Rig(initial = session()).use { rig ->
            rig.serve { Recorded.mock("login: wrong password") }
            val e = assertThrows<ApiException> { rig.client.login("pilot@example.com", "wrong") }
            assertEquals(401, e.status)
            assertEquals("invalid_credentials", e.code)
            assertEquals("Invalid email or password.", e.message)
            assertEquals(1, rig.requests.size)                                       // no refresh attempt
            assertTrue(rig.store.events.isEmpty())                                   // and the stored session is untouched
        }
    }

    @Test
    fun `restoring reads the store`() = runBlocking<Unit> {
        Rig(initial = session()).use { rig ->
            assertEquals(AuthState.Unknown, rig.client.state.value)
            assertTrue(rig.client.restore() is AuthState.SignedIn)
        }
        Rig(initial = null).use { rig ->
            assertEquals(AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), rig.client.restore())
        }
    }

    @Test
    fun `a call with no session says so without touching the network`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { error("nothing should be sent") }
            val e = assertThrows<SessionEndedException> { rig.client.listLzs() }
            assertEquals(SignedOutReason.NOT_SIGNED_IN, e.reason)
            assertTrue(rig.requests.isEmpty())
        }
    }

    // -- Refreshing ------------------------------------------------------------------

    @Test
    fun `a lapsed token is refreshed once and the call carries on`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            val lzs = rig.client.listLzs()
            assertEquals(1, lzs.size)

            assertEquals(listOf("/api/lz", "/api/auth/refresh", "/api/lz"), rig.requests.map { it.requestUrl!!.encodedPath })
            assertEquals("""{"refresh_token":"refresh-1"}""", rig.requests[1].body.readUtf8())
            assertNull(rig.requests[1].getHeader("Authorization"))                      // a refresh is not signed with the lapsed token
            assertEquals("Bearer access-2", rig.requests[2].getHeader("Authorization"))
            assertEquals(listOf("write access-2/refresh-2"), rig.store.events)
        }
    }

    @Test
    fun `the new refresh token is stored before anything relies on it`() = runBlocking<Unit> {
        Rig().use { rig ->
            val storedWhenRepeated = CompletableDeferred<String?>()
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> {
                        // The repeated call has arrived: the replacement for the token the server just spent must already be safe.
                        storedWhenRepeated.complete(rig.store.current?.refreshToken)
                        Recorded.mock("lz: list")
                    }
                }
            }
            rig.client.listLzs()
            assertEquals("refresh-2", storedWhenRepeated.await())
        }
    }

    @Test
    fun `many calls refused at once cause one refresh, and all of them succeed`() = runBlocking<Unit> {
        Rig().use { rig ->
            val refreshes = AtomicInteger()
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        Rig.refreshed("access-2", "refresh-2").setBodyDelay(200, TimeUnit.MILLISECONDS)
                    }
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            val results = coroutineScope { (1..10).map { async(Dispatchers.Default) { rig.client.listLzs() } }.awaitAll() }
            assertEquals(10, results.size)
            assertEquals(1, refreshes.get())
            assertEquals(listOf("write access-2/refresh-2"), rig.store.events)
        }
    }

    @Test
    fun `a call that started after another's refresh simply uses the new token`() = runBlocking<Unit> {
        Rig(initial = session("access-2", "refresh-2")).use { rig ->
            rig.serve { Recorded.mock("lz: list") }
            rig.client.listLzs()
            assertEquals("Bearer access-2", rig.requests.single().getHeader("Authorization"))
            assertTrue(rig.requestsTo("/api/auth/refresh").isEmpty())
        }
    }

    @Test
    fun `a token the library cannot read (422 with a msg) is refreshed like a lapsed one`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(422, """{"msg": "Not enough segments"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            assertEquals(1, rig.client.listLzs().size)
            assertEquals(1, rig.requestsTo("/api/auth/refresh").size)
        }
    }

    @Test
    fun `a 422 that is a validation answer is not a refused token`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Rig.json(422, """{"error": "Unprocessable", "msg": "also here"}""") }
            val e = assertThrows<ApiException> { rig.client.listLzs() }
            assertEquals(422, e.status)
            assertTrue(rig.requestsTo("/api/auth/refresh").isEmpty())
        }
    }

    // -- A session the server ends -------------------------------------------------------

    private fun refreshRefused(rig: Rig, name: String) = rig.serve { request ->
        when {
            request.path == "/api/auth/refresh" -> Recorded.mock(name)
            else -> Rig.json(401, """{"msg": "Token has been revoked"}""")
        }
    }

    @Test
    fun `a refresh the server refuses ends the session and the call is not repeated`() = runBlocking<Unit> {
        for ((name, code) in listOf(
            "refresh: not a token" to "invalid_refresh_token",
            "refresh: a spent token after the grace period" to "refresh_reuse_detected",
        )) {
            Rig().use { rig ->
                refreshRefused(rig, name)
                val e = assertThrows<SessionEndedException>(name) { rig.client.listLzs() }
                assertEquals(code, e.code)
                assertEquals(SignedOutReason.SESSION_ENDED, e.reason)
                assertEquals("This session has expired. Sign in again.", e.message)
                assertEquals(listOf("end $code"), rig.store.events)                      // the session goes, and why it went is kept
                assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_ENDED, code), rig.client.state.value)
                assertEquals(1, rig.requestsTo("/api/lz").size)                         // not repeated
                // The next call does not even try the network, and does not turn "your session ended" into "not signed in".
                assertEquals(code, assertThrows<SessionEndedException> { rig.client.listLzs() }.code)
                assertEquals(1, rig.requestsTo("/api/lz").size)
                assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_ENDED, code), rig.client.state.value)
            }
        }
    }

    @Test
    fun `a suspended account's refresh (403) ends the session too`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.path == "/api/auth/refresh") Rig.json(403, """{"status":"error","code":"account_unavailable","error":"This account is unavailable.","message":"This account is unavailable."}""")
                else Rig.json(401, """{"msg": "Token has expired"}""")
            }
            val e = assertThrows<SessionEndedException> { rig.client.listLzs() }
            assertEquals("account_unavailable", e.code)
            assertEquals("This account is unavailable.", e.message)
            assertNull(rig.store.current)
        }
    }

    @Test
    fun `a token refused straight after it was issued ends the session`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.path == "/api/auth/refresh") Rig.refreshed("access-2", "refresh-2") else Rig.json(401, """{"msg": "Token has been revoked"}""")
            }
            val e = assertThrows<SessionEndedException> { rig.client.listLzs() }
            assertEquals("unauthorized_after_refresh", e.code)
            assertNull(rig.store.current)
            assertEquals(1, rig.requestsTo("/api/auth/refresh").size)                   // no loop
        }
    }

    @Test
    fun `a session with no refresh token ends when its access token is refused`() = runBlocking<Unit> {
        Rig(initial = session(refresh = null)).use { rig ->
            rig.serve { Rig.json(401, """{"msg": "Token has expired"}""") }
            val e = assertThrows<SessionEndedException> { rig.client.listLzs() }
            assertEquals("no_refresh_token", e.code)
            assertTrue(rig.requestsTo("/api/auth/refresh").isEmpty())
        }
    }

    @Test
    fun `a refresh answer with no refresh token cannot be carried on`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.path == "/api/auth/refresh") Recorded.mock("login: web, which gets no refresh token", mapOf("access-token" to "access-2"))
                else Rig.json(401, """{"msg": "Token has expired"}""")
            }
            val e = assertThrows<SessionEndedException> { rig.client.listLzs() }
            assertEquals("no_refresh_token", e.code)
            assertNull(rig.store.current)
        }
    }

    // -- A refresh whose answer is lost ---------------------------------------------------

    @Test
    fun `a refresh whose answer was lost is repeated with the same token, which the server accepts`() = runBlocking<Unit> {
        val loser = LoseAnswers({ it == "/api/auth/refresh" }, times = 1)
        Rig(interceptor = loser).use { rig ->
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            assertEquals(1, rig.client.listLzs().size)
            val refreshes = rig.requestsTo("/api/auth/refresh")
            assertEquals(2, refreshes.size)
            assertEquals(listOf("""{"refresh_token":"refresh-1"}""", """{"refresh_token":"refresh-1"}"""), refreshes.map { it.body.readUtf8() })
            assertEquals(listOf("write access-2/refresh-2"), rig.store.events)
        }
    }

    @Test
    fun `a server error on a refresh is repeated, since it may have rotated the token first`() = runBlocking<Unit> {
        Rig().use { rig ->
            val refreshes = AtomicInteger()
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" ->
                        if (refreshes.incrementAndGet() == 1) Rig.json(500, """{"error": "Internal Server Error"}""") else Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            assertEquals(1, rig.client.listLzs().size)
            assertEquals(2, refreshes.get())
        }
    }

    @Test
    fun `repeats stop well inside the server's thirty-second grace period, and the session is kept`() = runTest {
        val loser = LoseAnswers({ it == "/api/auth/refresh" })
        Rig(interceptor = loser, time = { currentTime }).use { rig ->
            rig.serve { request ->
                if (request.path == "/api/auth/refresh") Rig.refreshed("access-2", "refresh-2") else Rig.json(401, """{"msg": "Token has expired"}""")
            }
            val e = assertThrows<NetworkException> { rig.client.listLzs() }
            assertTrue(e.requestMayHaveBeenSent)

            val refreshes = rig.requestsTo("/api/auth/refresh")
            assertEquals(6, refreshes.size)                                              // at 0, 0.5, 1.5, 3.5, 7.5 and 15.5 s
            assertEquals(1, refreshes.map { it.body.readUtf8() }.toSet().size)           // always the one token
            assertTrue(currentTime in 15_000..20_000, "gave up after $currentTime ms")
            assertEquals(session(), rig.store.current)                                   // untouched: try again later
            assertTrue(rig.store.events.isEmpty())
            assertTrue(rig.client.state.value !is AuthState.SignedOut)
        }
    }

    @Test
    fun `a connection that was never made is not repeated, since nothing can have happened`() = runBlocking<Unit> {
        Rig(interceptor = RefuseConnection { it == "/api/auth/refresh" }).use { rig ->
            rig.serve { Rig.json(401, """{"msg": "Token has expired"}""") }
            val e = assertThrows<NetworkException> { rig.client.listLzs() }
            assertFalse(e.requestMayHaveBeenSent)
            assertEquals(1, rig.requests.size)                                           // the call itself; the refresh never left
            assertEquals(session(), rig.store.current)
        }
    }

    @Test
    fun `too many refreshes keeps the session and says to wait`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.path == "/api/auth/refresh") Rig.json(429, """{"status":"error","code":"rate_limited","error":"Slow down.","message":"Slow down."}""").setHeader("Retry-After", "30")
                else Rig.json(401, """{"msg": "Token has expired"}""")
            }
            val e = assertThrows<RateLimitedException> { rig.client.listLzs() }
            assertEquals(30L, e.retryAfterSeconds)
            assertEquals(1, rig.requestsTo("/api/auth/refresh").size)
            assertEquals(session(), rig.store.current)
        }
    }

    @Test
    fun `a caller that goes away does not abandon a refresh that has begun`() = runBlocking<Unit> {
        Rig().use { rig ->
            val refreshArrived = CompletableDeferred<Unit>()
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> {
                        refreshArrived.complete(Unit)
                        Rig.refreshed("access-2", "refresh-2").setBodyDelay(400, TimeUnit.MILLISECONDS)
                    }
                    else -> Rig.json(401, """{"msg": "Token has expired"}""")
                }
            }
            val caller = launch(Dispatchers.Default) { runCatching { rig.client.listLzs() } }
            refreshArrived.await()
            caller.cancel()                                                              // the screen is closed mid-refresh
            // The server has spent the token. The answer must still be taken and kept.
            withTimeout(5_000) { while (rig.store.current?.accessToken != "access-2") delay(20) }
            assertEquals("refresh-2", rig.store.current?.refreshToken)
        }
    }

    // -- Writes are the same write when repeated ------------------------------------------

    @Test
    fun `a write keeps its idempotency key across the repeat after a refresh`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: create")
                }
            }
            val saved = rig.client.createLz("LZ HAWK", kotlinx.serialization.json.JsonObject(emptyMap()), "7f1f0a52-6c4e-4b2a-9d3e-0a1b2c3d4e5f")
            assertTrue(saved.created)
            val keys = rig.requestsTo("/api/lz").map { it.getHeader("Idempotency-Key") }
            assertEquals(2, keys.size)
            assertNotNull(keys[0])
            assertEquals(keys[0], keys[1])
        }
    }

    // -- Signing out ---------------------------------------------------------------------

    @Test
    fun `signing out clears the session and says the server confirmed`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("logout") }
            assertTrue(rig.client.logout())
            assertEquals(listOf("clear"), rig.store.events)
            assertEquals(AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), rig.client.state.value)
            assertEquals("Bearer access-1", rig.requestsTo("/api/auth/logout").single().getHeader("Authorization"))
        }
    }

    @Test
    fun `signing out works with no signal, and says the server was not told`() = runBlocking<Unit> {
        Rig(interceptor = RefuseConnection { true }).use { rig ->
            assertFalse(rig.client.logout())
            assertNull(rig.store.current)                                                // signed out here, whatever the network does
            assertEquals(AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), rig.client.state.value)
        }
    }

    @Test
    fun `deleting the account sends the proof and leaves nothing signed in`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.method == "DELETE") Recorded.mock("account deletion") else Recorded.mock("logout")
            }
            rig.client.deleteAccount(password = "a secure flight password")
            val sent = rig.requests.first { it.method == "DELETE" }
            assertEquals("/api/auth/me", sent.requestUrl!!.encodedPath)
            assertEquals("""{"confirm":"DELETE","password":"a secure flight password"}""", sent.body.readUtf8())
            assertNull(rig.store.current)
        }
    }

    @Test
    fun `account deletion refused for want of proof leaves the session`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("account deletion: refused without proof") }
            val e = assertThrows<ApiException> { rig.client.deleteAccount() }
            assertEquals("reauthentication_required", e.code)
            assertEquals(401, e.status)
            assertEquals(session(), rig.store.current)
            // A 401 with a code is the server's own answer, not a refused token: refreshing would not help and must not be tried.
            assertTrue(rig.requestsTo("/api/auth/refresh").isEmpty())
        }
    }
}
