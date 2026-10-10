package app.ezpztac.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reading the stored session. The shell reads it at launch, and so does a sync WorkManager runs, possibly in a process the app did not
 * start: the read happens once per process, and a session the server ended leaves a note so the next process can still say why.
 */
class RestoreTest {
    private val day = 24 * 60 * 60L
    private val now = 1_800_000_000L

    private class Clock(var seconds: Long) : TimeSource { override fun nowMillis() = seconds * 1000 }

    private fun refreshRefused(rig: Rig) = rig.serve { request ->
        if (request.path == "/api/auth/refresh") Recorded.mock("refresh: a spent token after the grace period")
        else Rig.json(401, """{"msg": "Token has been revoked"}""")
    }

    private val revoked = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "refresh_reuse_detected")
    private val offlineTooLong = AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long")

    @Test
    fun `the store is read once, and a second restore says what has happened since`() = runBlocking<Unit> {
        Rig().use { rig ->
            refreshRefused(rig)
            assertTrue(rig.client.restore() is AuthState.SignedIn)
            assertThrows<SessionEndedException> { rig.client.listLzs() }

            rig.store.beforeNextRead = { error("the store was read again") }
            assertEquals(revoked, rig.client.restore())
            assertEquals(revoked, rig.client.state.value)
        }
    }

    @Test
    fun `a session the server ended is still ended, and why, in the next process, until someone signs in or out`() = runBlocking<Unit> {
        Rig().use { rig ->
            refreshRefused(rig)
            rig.client.restore()
            assertThrows<SessionEndedException> { rig.client.listLzs() }
            assertNull(rig.store.current)

            val next = rig.nextProcess()                                                    // a sync ended it with the app closed; now it is opened
            assertEquals(revoked, next.restore())

            rig.serve { Recorded.mock("login: android", mapOf("access-token" to "access-A", "refresh-token" to "refresh-A")) }
            val user = next.login("pilot@example.com", "a secure flight password")
            assertEquals(AuthState.SignedIn(user), rig.nextProcess().restore())            // signing in again takes the note away

            rig.serve { Recorded.mock("logout") }
            rig.nextProcess().apply { restore(); logout() }
            assertEquals(AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), rig.nextProcess().restore())   // and a sign-out leaves none
        }
    }

    @Test
    fun `a session the offline rule ended says so in the next process too`() = runBlocking<Unit> {
        Rig(initial = session().copy(verifiedAtEpochSeconds = now - 15 * day), time = Clock(now)).use { rig ->
            rig.client.restore()
            assertTrue(rig.client.endSessionIfOfflineTooLong())
            assertEquals(offlineTooLong, rig.nextProcess().restore())
        }
    }

    @Test
    fun `a restore still reading when the offline rule ends the session does not bring the session back`() = runBlocking<Unit> {
        Rig(initial = session().copy(verifiedAtEpochSeconds = now - 15 * day), time = Clock(now)).use { rig ->
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rig.store.beforeNextRead = { reading.complete(Unit); release.await() }
            val restoring = async { rig.client.restore() }
            reading.await()                                                                 // it has the session in hand and has not said so yet
            val ending = async { rig.client.endSessionIfOfflineTooLong() }
            yield()                                                                         // as far as the rule can get while the read is open
            release.complete(Unit)
            restoring.await()

            assertTrue(ending.await())
            assertEquals(offlineTooLong, rig.client.state.value)
            assertNull(rig.store.current)
        }
    }

    @Test
    fun `a launch does not wait behind a refresh another call has in flight`() = runBlocking<Unit> {
        // A sync WorkManager started can be part-way through a refresh when the person opens the app, and on a poor signal that takes up to 20 s.
        val answer = CountDownLatch(1)
        Rig().use { rig ->
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> { answer.await(10, TimeUnit.SECONDS); Rig.refreshed("access-2", "refresh-2") }
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            try {
                val call = async(Dispatchers.IO) { rig.client.listLzs() }
                withTimeout(5_000) { while (rig.requestsTo("/api/auth/refresh").isEmpty()) delay(10) }   // the refresh is out, its answer held back
                assertTrue(withTimeout(2_000) { rig.client.restore() } is AuthState.SignedIn)
                answer.countDown()
                call.await()
                assertEquals(AuthState.SignedIn(Recorded.user()), rig.client.state.value)
            } finally {
                answer.countDown()
            }
        }
    }

    @Test
    fun `a sign-in that lands while a restore is reading is not overwritten by what it read`() = runBlocking<Unit> {
        Rig(initial = null).use { rig ->
            rig.serve { Recorded.mock("login: android", mapOf("access-token" to "access-A", "refresh-token" to "refresh-A")) }
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rig.store.beforeNextRead = { reading.complete(Unit); release.await() }
            val restoring = async { rig.client.restore() }
            reading.await()                                                                 // it found nobody signed in and has not said so yet
            val user = rig.client.login("pilot@example.com", "a secure flight password")
            release.complete(Unit)

            assertEquals(AuthState.SignedIn(user), restoring.await())
            assertEquals(AuthState.SignedIn(user), rig.client.state.value)
        }
    }
}
