package app.ezpztac.network

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PriorityGateTest {
    @Test
    fun `it is idle until heavy work begins and idle again when it ends`() = runTest {
        val gate = PriorityGate()
        assertFalse(gate.isBusy)
        gate.whenIdle()                                                              // returns at once
        val ticket = gate.begin()
        assertTrue(gate.isBusy)
        ticket.close()
        assertFalse(gate.isBusy)
    }

    @Test
    fun `it waits for every piece of heavy work, not just the first to finish`() = runBlocking<Unit> {
        val gate = PriorityGate()
        val a = gate.begin()
        val b = gate.begin()
        val waiter = async { gate.whenIdle(); "went" }
        a.close()
        assertNull(withTimeoutOrNull(150) { waiter.await() }, "one piece of heavy work is still running")
        b.close()
        assertEquals("went", withTimeout(5_000) { waiter.await() })
    }

    @Test
    fun `closing a ticket twice is the same as once`() {
        val gate = PriorityGate()
        val first = gate.begin()
        val second = gate.begin()
        first.close()
        first.close()
        assertTrue(gate.isBusy, "the second piece of work is still running")
        second.close()
        assertFalse(gate.isBusy)
    }

    @Test
    fun `waiters are all released together`() = runBlocking<Unit> {
        val gate = PriorityGate()
        val ticket = gate.begin()
        val waiting = (1..5).map { async { gate.whenIdle(); it } }
        launch { ticket.close() }
        assertEquals(listOf(1, 2, 3, 4, 5), withTimeout(5_000) { waiting.map { it.await() } })
    }

    @Test
    fun `a ticket is closed by use`() {
        val gate = PriorityGate()
        gate.begin().use { assertTrue(gate.isBusy) }
        assertFalse(gate.isBusy)
    }

    // -- Which requests are heavy: the web's rule ---------------------------------------

    @TestFactory
    fun `a request is heavy exactly when the web says it is`(): List<DynamicTest> {
        val recorded = Fixtures.load("network/priority.json")
        assertEquals(
            recorded.getValue("heavyPaths").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            setOf("/analyze-field", "/terrain-analysis", "/threat-mask", "/export-package", "/generate-excel"),
        )
        return recorded.getValue("cases").jsonArray.map { case ->
            val url = case.jsonObject.getValue("url").jsonPrimitive.content
            val heavy = case.jsonObject.getValue("heavy").jsonPrimitive.content.toBoolean()
            DynamicTest.dynamicTest("\"$url\" is ${if (heavy) "heavy" else "not heavy"}") { assertEquals(heavy, PriorityPaths.isHeavy(url)) }
        }
    }

    // -- Which failures could have reached the server ------------------------------------

    @Test
    fun `a failure before the request left is told from one after it`() {
        val before: List<IOException> = listOf(
            UnknownHostException("no such host"), ConnectException("Connection refused"), NoRouteToHostException("no route"),
            SSLHandshakeException("handshake failed"), SocketTimeoutException("connect timed out"),
        )
        val after: List<IOException> = listOf(
            SocketTimeoutException("timeout"), SocketTimeoutException("Read timed out"), SocketException("Connection reset"),
            EOFException("\\n not found: size=0"), IOException("unexpected end of stream"), IOException(),
        )
        for (e in before) assertFalse(ApiClient.classify(e).requestMayHaveBeenSent, e.toString())
        for (e in after) assertTrue(ApiClient.classify(e).requestMayHaveBeenSent, e.toString())
        assertEquals("The server could not be reached.", ApiClient.classify(IOException()).message)
    }

    @Suppress("unused")
    private val json = Json
}
