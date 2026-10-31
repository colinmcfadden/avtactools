package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Signing up, recovering an account and clearing the `.mil` gate, with the server's recorded answers. */
class AccountCallsTest {
    private fun body(request: RecordedRequest): JsonObject = ApiClient.JSON.parseToJsonElement(request.body.readUtf8()).jsonObject

    // -- Before there is an account -------------------------------------------------------------

    @Test
    fun `a person who is not signed in sends no token, and says only what the route needs`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { request ->
                when (request.path) {
                    "/api/auth/register" -> Recorded.mock("register")
                    "/api/auth/verify-email" -> Recorded.mock("verify-email")
                    "/api/auth/resend-verification" -> Recorded.mock("resend-verification")
                    "/api/auth/forgot-password" -> Recorded.mock("forgot-password")
                    "/api/auth/reset-password" -> Recorded.mock("reset-password")
                    else -> error("unexpected ${request.path}")
                }
            }
            val registered = rig.client.register("New Pilot", "new.pilot@example.com")
            assertTrue(registered.requiresVerification)
            assertEquals("Email verified. You can now sign in.", rig.client.verifyEmail("the-token", "a secure flight password").message)
            assertEquals("success", rig.client.resendVerification("new.pilot@example.com").status)
            assertEquals("success", rig.client.forgotPassword("new.pilot@example.com").status)
            assertEquals("Password updated. You can now sign in.", rig.client.resetPassword("reset-token", "a new flight password").message)

            assertEquals(5, rig.requests.size)
            assertTrue(rig.requests.all { it.getHeader("Authorization") == null }, "no token on a call made before signing in")
            assertTrue(rig.requests.all { it.getHeader(ClientInfo.HEADER) == "android/1.4.0 (212)" })
            assertEquals(mapOf("name" to "New Pilot", "email" to "new.pilot@example.com"), body(rig.requests[0]).mapValues { it.value.jsonPrimitive.content })
            assertEquals(mapOf("token" to "the-token", "password" to "a secure flight password"), body(rig.requests[1]).mapValues { it.value.jsonPrimitive.content })
            assertEquals(mapOf("email" to "new.pilot@example.com"), body(rig.requests[2]).mapValues { it.value.jsonPrimitive.content })
            assertEquals(mapOf("email" to "new.pilot@example.com"), body(rig.requests[3]).mapValues { it.value.jsonPrimitive.content })
            assertEquals(mapOf("token" to "reset-token", "password" to "a new flight password"), body(rig.requests[4]).mapValues { it.value.jsonPrimitive.content })
        }
    }

    @Test
    fun `the answer is the same whether or not the address has an account`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { Recorded.mock("forgot-password: an address nobody has looks the same") }
            val unknown = rig.client.forgotPassword("nobody@example.com")
            rig.serve { Recorded.mock("forgot-password") }
            val known = rig.client.forgotPassword("new.pilot@example.com")
            assertEquals(known, unknown)                       // a screen can say no more than this, and neither can a client
            rig.serve { Recorded.mock("register") }
            val first = rig.client.register("A", "a@example.com")
            rig.serve { Recorded.mock("register: the same address again looks the same") }
            assertEquals(first, rig.client.register("A", "a@example.com"))
        }
    }

    @Test
    fun `a link that is not valid, and a password that is too weak, come back with the server's words`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { Recorded.mock("verify-email: a link that is not valid") }
            val link = assertThrows<ApiException> { rig.client.verifyEmail("nonsense", "a secure flight password") }
            assertEquals(400, link.status)
            assertEquals("invalid_token", link.code)
            assertEquals("This verification link is invalid or has expired.", link.message)

            rig.serve { Recorded.mock("verify-email: a password that is too weak") }
            val weak = assertThrows<ApiException> { rig.client.verifyEmail("the-token", "short") }
            assertEquals(400, weak.status)
            assertNull(weak.code)                                                           // no code: the words are the message
            assertEquals("Password must be at least 15 characters.", weak.message)

            rig.serve { Recorded.mock("reset-password: a link that is not valid") }
            assertEquals("invalid_token", assertThrows<ApiException> { rig.client.resetPassword("x", "a secure flight password") }.code)
            rig.serve { Recorded.mock("register: not an email address") }
            assertEquals("Enter a valid email address.", assertThrows<ApiException> { rig.client.register("A", "nope") }.message)
        }
    }

    @Test
    fun `too many attempts says how long to wait`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { Recorded.mock("too many attempts") }
            val e = assertThrows<RateLimitedException> { rig.client.resendVerification("new.pilot@example.com") }
            assertEquals(3600L, e.retryAfterSeconds)
        }
    }

    // -- The .mil gate ----------------------------------------------------------------------------

    @Test
    fun `clearing the gate is for a signed-in account, and the account then knows it is cleared`() = runBlocking<Unit> {
        val before = Recorded.user().copy(accessOk = false, affiliationVerified = false)
        Rig(initial = session().copy(user = before)).use { rig ->
            rig.serve { request ->
                when (request.path) {
                    "/api/auth/mil/request" -> Recorded.mock("mil/request")
                    "/api/auth/mil/verify" -> Recorded.mock("mil/verify", mapOf("access-token" to "x"))
                    else -> error("unexpected ${request.path}")
                }
            }
            rig.client.restore()
            assertFalse((rig.client.state.value as AuthState.SignedIn).user.accessOk)

            assertEquals("A verification code was sent to your .mil address.", rig.client.requestMilCode("new.pilot@example.mil").message)
            val user = rig.client.verifyMilCode("ABCD2345")

            assertTrue(rig.requests.all { it.getHeader("Authorization") == "Bearer access-1" })
            assertEquals("new.pilot@example.mil", body(rig.requests[0])["email"]!!.jsonPrimitive.content)
            assertEquals("ABCD2345", body(rig.requests[1])["code"]!!.jsonPrimitive.content)
            assertTrue(user.accessOk)
            assertEquals(AuthState.SignedIn(user), rig.client.state.value)                  // the screens that gate on it see it at once
            assertTrue(rig.store.current!!.user.accessOk)                                   // and so does the next launch
            assertEquals("access-1", rig.store.current!!.accessToken)                       // nothing else about the session changed
            assertEquals("refresh-1", rig.store.current!!.refreshToken)
        }
    }

    @Test
    fun `a wrong code changes nothing`() = runBlocking<Unit> {
        val before = Recorded.user().copy(accessOk = false)
        Rig(initial = session().copy(user = before)).use { rig ->
            rig.serve { Recorded.mock("mil/verify: the wrong code") }
            rig.client.restore()
            val e = assertThrows<ApiException> { rig.client.verifyMilCode("000000") }
            assertEquals("invalid_code", e.code)
            assertFalse((rig.client.state.value as AuthState.SignedIn).user.accessOk)
            assertFalse(rig.store.current!!.user.accessOk)
            assertEquals(0, rig.store.events.count { it.startsWith("write") })
        }
    }

    @Test
    fun `a request for a code to an address that is not military is refused with the reason`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("mil/request: not a .mil address") }
            assertEquals("Enter a valid .mil email address.", assertThrows<ApiException> { rig.client.requestMilCode("pilot@example.com") }.message)
        }
    }

    // -- Keeping the user current -------------------------------------------------------------------

    @Test
    fun `asking who this is now keeps the answer, so a change on the server is seen without signing in again`() = runBlocking<Unit> {
        // What this device remembers is out of date: the server (an admin approving access, a feature switched off) has moved on.
        val remembered = Recorded.user().copy(accessOk = !Recorded.user().accessOk, features = mapOf("threats" to false))
        Rig(initial = session().copy(user = remembered)).use { rig ->
            rig.serve { Recorded.mock("me") }
            rig.client.restore()
            val user = rig.client.refreshUser()
            assertEquals(Recorded.user(), user)
            assertEquals(AuthState.SignedIn(Recorded.user()), rig.client.state.value)
            assertEquals(Recorded.user(), rig.store.current!!.user)
            assertEquals("access-1", rig.store.current!!.accessToken)
        }
    }

    @Test
    fun `a user refreshed after signing out does not bring the session back`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("me") }
            rig.client.restore()
            val user = rig.client.me()
            rig.client.logout()
            rig.client.updateUser(user)                                                    // a late answer, landing after sign-out
            assertNull(rig.store.current)
            assertTrue(rig.client.state.value is AuthState.SignedOut)
            assertNotNull(user)
        }
    }
}
