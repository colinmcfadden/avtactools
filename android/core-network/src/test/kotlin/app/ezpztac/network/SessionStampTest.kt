package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * When the server last vouched for the account is kept with the session. Nothing ends a session by it: a device offline for any length of
 * time stays signed in (the owner, 2026-10-28), and the server ends one unused for 30 days at the next contact (RestoreTest has the year).
 */
class SessionStampTest {
    private val day = 24 * 60 * 60L
    private val now = 1_800_000_000L

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
}
