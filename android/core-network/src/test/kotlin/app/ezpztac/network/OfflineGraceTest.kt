package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A device that has not heard from the server for 14 days has to sign in again; what it saved stays. */
class OfflineGraceTest {
    private val day = 24 * 60 * 60L
    private val now = 1_800_000_000L

    private fun stamped(daysAgo: Double) = session().copy(verifiedAtEpochSeconds = now - (daysAgo * day).toLong())

    @Test
    fun `fourteen days is allowed, and a second more is not`() {
        assertFalse(OfflineGrace.expired(stamped(13.9), now))
        assertFalse(OfflineGrace.expired(session().copy(verifiedAtEpochSeconds = now - 14 * day), now))
        assertTrue(OfflineGrace.expired(session().copy(verifiedAtEpochSeconds = now - 14 * day - 1), now))
        assertTrue(OfflineGrace.expired(stamped(60.0), now))
    }

    @Test
    fun `a session with no stamp is not locked out for the want of one`() {
        assertFalse(OfflineGrace.expired(session().copy(verifiedAtEpochSeconds = null), now))
    }

    @Test
    fun `a clock set backwards does not end a session`() {
        assertFalse(OfflineGrace.expired(session().copy(verifiedAtEpochSeconds = now + 5 * day), now))
    }

    // -- What stamps it ------------------------------------------------------------------------

    private class Clock(var seconds: Long) : TimeSource { override fun nowMillis() = seconds * 1000 }

    @Test
    fun `signing in, refreshing and asking who this is each stamp the session`() = runBlocking<Unit> {
        val clock = Clock(now)
        Rig(initial = null, time = clock).use { rig ->
            rig.serve { request ->
                when (request.path) {
                    "/api/auth/login" -> Recorded.mock("login: android", mapOf("access-token" to "a1", "refresh-token" to "r1"))
                    "/api/auth/refresh" -> Rig.refreshed("a2", "r2")
                    "/api/auth/me" -> if (Rig.bearer(request) == "a1") Rig.json(401, """{"msg": "Token has expired"}""") else Recorded.mock("me")
                    else -> error(request.path.orEmpty())
                }
            }
            rig.client.login("pilot@example.com", "x")
            assertEquals(now, rig.store.current!!.verifiedAtEpochSeconds)

            clock.seconds = now + 3 * day
            rig.client.me()                                                                  // refused, refreshed, repeated
            assertEquals(now + 3 * day, rig.store.current!!.verifiedAtEpochSeconds)

            clock.seconds = now + 5 * day
            rig.client.refreshUser()
            assertEquals(now + 5 * day, rig.store.current!!.verifiedAtEpochSeconds)
        }
    }

    @Test
    fun `a device offline too long ends its session on the device, and says why`() = runBlocking<Unit> {
        val clock = Clock(now)
        Rig(initial = stamped(15.0), time = clock).use { rig ->
            rig.client.restore()
            assertTrue(rig.client.endSessionIfOfflineTooLong())
            assertNull(rig.store.current)
            assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long"), rig.client.state.value)
            assertTrue(rig.requests.isEmpty(), "it cannot ask the server: it is offline")
        }
    }

    @Test
    fun `a device inside the grace period is left alone`() = runBlocking<Unit> {
        Rig(initial = stamped(3.0), time = Clock(now)).use { rig ->
            rig.client.restore()
            assertFalse(rig.client.endSessionIfOfflineTooLong())
            assertNotNull(rig.store.current)
            assertTrue(rig.client.state.value is AuthState.SignedIn)
        }
    }

    @Test
    fun `nobody signed in has nothing to end`() = runBlocking<Unit> {
        Rig(initial = null, time = Clock(now)).use { rig ->
            assertFalse(rig.client.endSessionIfOfflineTooLong())
        }
    }
}
