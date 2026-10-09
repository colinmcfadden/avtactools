package app.ezpztac.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A mission pack's live stream against a stand-in for the live service, on MockWebServer's own WebSocket support: what the app sends
 * and in what order, and what it makes of what the service says (docs/MISSION_PACKS.md §6). The real service needs Postgres, so this is
 * the app's half of the protocol; `backend/tests/test_realtime.py` holds the service to the same.
 */
class PackLiveTest {
    private val pack = "6d1c2e8a-4b3f-4a51-9e07-2f8b1c5d7a90"
    private val me = Recorded.user().id

    /** The service's end of one socket: what the app sent, in order, and the socket to answer on. */
    private class Service : WebSocketListener() {
        val frames = LinkedBlockingQueue<String>()
        private val opened = CompletableFuture<WebSocket>()

        /** The code the app closed the socket with. */
        val closedBy = CompletableFuture<Int>()

        val socket: WebSocket get() = opened.get(5, TimeUnit.SECONDS)

        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) { frames += text }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closedBy.complete(code)
            webSocket.close(1000, null)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closedBy.completeExceptionally(t) }

        fun say(vararg texts: String) = texts.forEach { check(socket.send(it)) { "The socket would not take \"$it\"" } }

        /** The next frame the app sent. */
        fun next(): JsonObject = (frames.poll(5, TimeUnit.SECONDS) ?: error("The app sent nothing")).let { ApiClient.JSON.parseToJsonElement(it).jsonObject }

        /** That the app sends nothing more for a moment. */
        fun quiet() = assertNull(frames.poll(300, TimeUnit.MILLISECONDS))

        fun closedWith(): Int = closedBy.get(5, TimeUnit.SECONDS)
    }

    /** A mock server that upgrades `/live` to [service], a client signed in as [initial], and the scope a stream is opened in. */
    private inner class Live(initial: StoredSession? = session(), time: TimeSource = TimeSource.System) : AutoCloseable {
        val rig = Rig(initial, time)
        val service = Service()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = rig.server.url("/live").toString().replaceFirst("http://", "ws://")

        init { serve { Rig.json(404, "{}") } }

        /** The socket on `/live`, and everything else answered by [other]. */
        fun serve(other: (RecordedRequest) -> MockResponse) =
            rig.serve { if (it.path == "/live") MockResponse().withWebSocketUpgrade(service) else other(it) }

        suspend fun open(asUser: Int = me): PackLiveConnection =
            checkNotNull(rig.client.openPackLive(url, pack, scope, asUser)) { "No stream was opened" }

        override fun close() {
            scope.cancel()
            rig.close()
        }
    }

    private fun json(text: String): JsonObject = ApiClient.JSON.parseToJsonElement(text).jsonObject
    private fun hello(token: String) = json("""{"type": "hello", "pack": "$pack", "token": "$token"}""")
    private fun head(seq: Long) = """{"type": "head", "pack": "$pack", "seq": $seq}"""
    private fun focus(item: String) = buildJsonObject { put("item", item) }

    private val welcome = """{"type": "welcome", "pack": "$pack", "head_seq": 7, "role": "editor", "status": "active",
        "you": {"session": "5f2c9a1e0b3d4c67", "user_id": 1, "name": "Test Pilot"}}"""

    /** Collects the whole stream, to its end. */
    private suspend fun PackLiveConnection.all(): List<LiveMessage> = withTimeout(5_000) { messages.toList() }

    // -- Opening -------------------------------------------------------------------------------

    @Test
    fun `the hello is the first frame, with the token stored now, and never in a header`() = runBlocking<Unit> {
        Live().use { live ->
            live.open()
            assertEquals(hello("access-1"), live.service.next())
            live.service.quiet()

            val upgrade = live.rig.requests.single()
            assertEquals("/live", upgrade.path)
            assertEquals("websocket", upgrade.getHeader("Upgrade"))
            assertEquals("android/1.4.0 (212)", upgrade.getHeader(ClientInfo.HEADER))         // which app, as every call says
            assertNull(upgrade.getHeader("Authorization"))
        }
    }

    @Test
    fun `there is no stream, and nothing is opened, for an address that is not a live one, an id that is not a pack's, or another account`() =
        runBlocking<Unit> {
            Live().use { live ->
                val client = live.rig.client
                for (url in listOf("http://127.0.0.1/live", "https://example.com/live", "ftp://example.com/live", "", "live", "ws://", "wss://")) {
                    assertNull(client.openPackLive(url, pack, live.scope, me), url)
                }
                for (bad in listOf("..", "a/b", "", "a".repeat(37), "$pack/../teams", "has space")) {
                    assertNull(client.openPackLive(live.url, bad, live.scope, me), bad)
                }
                assertNull(client.openPackLive(live.url, pack, live.scope, asUser = me + 1))
                assertTrue(live.rig.requests.isEmpty())
            }
            Live(initial = null).use { live ->
                assertNull(live.rig.client.openPackLive(live.url, pack, live.scope, me))              // nobody signed in
                assertTrue(live.rig.requests.isEmpty())
            }
        }

    @Test
    fun `the socket pings every 25 seconds, so a connection that died without a word is noticed`() {
        Rig().use { rig -> assertEquals(25_000, rig.client.liveHttp.pingIntervalMillis) }
    }

    // -- Tokens --------------------------------------------------------------------------------

    @Test
    fun `a refreshed token is passed on, once, and the one in the hello is not sent again`() = runBlocking<Unit> {
        Live().use { live ->
            val issued = AtomicInteger(1)
            val lapsed = AtomicBoolean(false)
            live.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> {
                        lapsed.set(false)
                        issued.incrementAndGet().let { Rig.refreshed("access-$it", "refresh-$it") }
                    }
                    lapsed.get() -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("pack: get")
                }
            }
            val client = live.rig.client
            lapsed.set(true)
            client.packDocument(pack)                                            // refused, refreshed and carried on, before the stream opens
            live.open()
            assertEquals(hello("access-2"), live.service.next())
            live.service.quiet()                                                 // that refresh was before the hello, which had its token

            lapsed.set(true)
            client.packDocument(pack)
            assertEquals(json("""{"type": "token", "token": "access-3"}"""), live.service.next())
            live.service.quiet()
            assertEquals(listOf("access-1", "access-2", "access-2", "access-3"), live.rig.requests.filter { it.path == "/api/packs/$pack" }.map { Rig.bearer(it) })
        }
    }

    @Test
    fun `a refresh between reading the hello's token and listening for the next is still passed on`() = runBlocking<Unit> {
        Live().use { live ->
            val lapsed = AtomicBoolean(false)
            live.serve { request ->
                when {
                    request.path == "/api/auth/refresh" -> {
                        lapsed.set(false)
                        Rig.refreshed("access-2", "refresh-2")
                    }
                    lapsed.get() -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("pack: get")
                }
            }
            val client = live.rig.client
            // The stream's read of the store already has its answer (access-1) when another call refreshes the token.
            live.rig.store.beforeNextRead = {
                lapsed.set(true)
                client.packDocument(pack)
            }
            live.open()
            assertEquals(hello("access-1"), live.service.next())
            assertEquals(json("""{"type": "token", "token": "access-2"}"""), live.service.next())
            live.service.quiet()
        }
    }

    /** How a stream ends once its account is no longer the one signed in here: once, closed from here, and not to be opened again by itself. */
    private suspend fun Live.endsSignedOut(stream: PackLiveConnection) {
        val all = stream.all()
        assertEquals(listOf(LiveMessage.Closed(4401, LiveMessage.Closed.SIGNED_OUT)), all)
        assertFalse((all.single() as LiveMessage.Closed).reconnect)
        assertEquals(1000, service.closedWith())
    }

    @Test
    fun `signing out ends the stream`() = runBlocking<Unit> {
        Live().use { live ->
            live.serve { Recorded.mock("logout") }
            val stream = live.open()
            live.service.next()

            live.rig.client.logout()
            live.endsSignedOut(stream)
        }
    }

    @Test
    fun `a session the server ends ends the stream`() = runBlocking<Unit> {
        Live().use { live ->
            live.serve { request ->
                if (request.path == "/api/auth/refresh") Recorded.mock("refresh: a spent token after the grace period")
                else Rig.json(401, """{"msg": "Token has expired"}""")
            }
            val stream = live.open()
            live.service.next()

            // Signed out from another device, or the token was spent: the refresh is refused.
            assertThrows<SessionEndedException> { live.rig.client.packDocument(pack) }
            live.endsSignedOut(stream)
        }
    }

    @Test
    fun `a session ended for being offline too long ends the stream`() = runBlocking<Unit> {
        val clock = AtomicLong(1_800_000_000L)
        Live(initial = session().copy(verifiedAtEpochSeconds = clock.get()), time = TimeSource { clock.get() * 1000 }).use { live ->
            val stream = live.open()
            live.service.next()

            clock.addAndGet(OfflineGrace.DAYS * 24 * 60 * 60 + 1)
            assertTrue(live.rig.client.endSessionIfOfflineTooLong())
            live.endsSignedOut(stream)
        }
    }

    @Test
    fun `someone else signing in ends the stream, and their token never goes on it`() = runBlocking<Unit> {
        val recorded = Recorded["login: android"].body!!.jsonObject
        val someoneElse = JsonObject(
            recorded + mapOf(
                "access_token" to JsonPrimitive("access-B"),
                "refresh_token" to JsonPrimitive("refresh-B"),
                "user" to JsonObject(recorded.getValue("user").jsonObject + mapOf("id" to JsonPrimitive(me + 1), "email" to JsonPrimitive("someone.else@example.com"))),
            ),
        )
        Live().use { live ->
            live.serve { Rig.json(200, someoneElse.toString()) }
            val stream = live.open()
            assertEquals(hello("access-1"), live.service.next())

            live.rig.client.login("someone.else@example.com", "a password")
            live.endsSignedOut(stream)
            assertFalse(live.service.frames.any { "access-B" in it }, live.service.frames.toString())
        }
    }

    // -- What the service says ---------------------------------------------------------------------

    @Test
    fun `every message the service sends arrives as the protocol says`() = runBlocking<Unit> {
        // An event exactly as `GET …/events` gives it, recorded from the real server.
        val event = Recorded["events: a page"].body!!.jsonObject.getValue("events").jsonArray.first().jsonObject
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            live.service.say(
                welcome,
                """{"type": "event", "pack": "$pack", "seq": 1, "event": $event}""",
                head(9),
                """{"type": "resync", "pack": "$pack"}""",
                """{"type": "presence", "pack": "$pack", "people": [
                    {"session": "5f2c9a1e0b3d4c67", "user_id": 1, "name": "Test Pilot", "focus": {"item": "lz-1"}},
                    {"session": "9e8d7c6b5a493827", "user_id": 7, "name": "Sam", "focus": null},
                    "not a person"]}""",
                """{"type": "welcome", "pack": "$pack", "head_seq": 0}""",
            )
            assertEquals(
                listOf(
                    LiveMessage.Welcome(7, "editor", "active", json("""{"session": "5f2c9a1e0b3d4c67", "user_id": 1, "name": "Test Pilot"}""")),
                    LiveMessage.Event(1, event),
                    LiveMessage.Head(9),
                    LiveMessage.Resync,
                    LiveMessage.Presence(
                        listOf(
                            json("""{"session": "5f2c9a1e0b3d4c67", "user_id": 1, "name": "Test Pilot", "focus": {"item": "lz-1"}}"""),
                            json("""{"session": "9e8d7c6b5a493827", "user_id": 7, "name": "Sam", "focus": null}"""),
                        ),
                    ),
                    LiveMessage.Welcome(0, null, null, null),
                ),
                withTimeout(5_000) { stream.messages.take(6).toList() },
            )
        }
    }

    @Test
    fun `frames for another pack, the closed notice, types this version does not know and anything unreadable are passed over`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            live.service.say(
                """{"type": "event", "pack": "another-pack", "seq": 2, "event": {}}""",
                """{"type": "head", "seq": 2}""",                                                     // no pack
                """{"type": "closed", "reason": "forbidden"}""",                                       // its close frame says the same
                """{"type": "typing", "pack": "$pack", "who": "Sam"}""",                               // newer than this version
                """{"pack": "$pack", "seq": 2}""",
                "not json", "[1, 2]", "\"text\"", "",
                """{"type": "event", "pack": "$pack", "event": {}}""",                                 // no number
                """{"type": "event", "pack": "$pack", "seq": "2", "event": {}}""",                      // a number as text
                """{"type": "event", "pack": "$pack", "seq": 2, "event": "an event"}""",
                """{"type": "head", "pack": "$pack", "seq": 2.5}""",
                """{"type": "welcome", "pack": "$pack", "role": "editor"}""",                          // no head_seq
                """{"type": "presence", "pack": "$pack", "people": "everyone"}""",
            )
            check(live.service.socket.send("binary".encodeUtf8()))
            live.service.say(head(3))

            assertEquals(LiveMessage.Head(3), withTimeout(5_000) { stream.messages.first() })
            assertFalse(stream.presence(null))                                     // no welcome came that could be read
        }
    }

    @Test
    fun `presence goes only after the welcome, and never a focus the service would drop`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            assertFalse(stream.presence(focus("lz-1")))                             // before the welcome

            live.service.say(welcome)
            assertTrue(withTimeout(5_000) { stream.messages.first() } is LiveMessage.Welcome)
            assertTrue(stream.presence(focus("lz-1")))
            assertEquals(json("""{"type": "presence", "focus": {"item": "lz-1"}}"""), live.service.next())
            assertTrue(stream.presence(null))
            assertEquals(json("""{"type": "presence", "focus": null}"""), live.service.next())

            // 1,024 at most, measured as the service's Python writes it: every character outside ASCII a six-character escape.
            assertTrue(stream.presence(focus("x".repeat(1013))))                    // {"item":"…"} is 1,024
            assertFalse(stream.presence(focus("x".repeat(1014))))
            assertTrue(stream.presence(focus("é".repeat(168))))                     // 179 characters here, 1,019 to the service
            assertFalse(stream.presence(focus("é".repeat(169))))                    // 180 here, 1,025 there
            assertTrue(stream.presence(focus("🚁".repeat(84))))           // a helicopter is two escapes: 1,019
            assertFalse(stream.presence(focus("🚁".repeat(85))))
            assertEquals(focus("x".repeat(1013)), live.service.next()["focus"])
            assertEquals(focus("é".repeat(168)), live.service.next()["focus"])
            assertEquals(focus("🚁".repeat(84)), live.service.next()["focus"])
            live.service.quiet()

            stream.close()
            assertFalse(stream.presence(null))                                     // after the end
        }
    }

    // -- How it ends -------------------------------------------------------------------------------

    /** A close code, why the service sends it, and what the protocol says a client does after it. */
    private class Ending(val code: Int, val reason: String, val packGone: Boolean, val reconnect: Boolean)

    @TestFactory
    fun `each way the service closes arrives once, as the protocol says to take it`(): List<DynamicTest> = listOf(
        Ending(4401, "unauthorized", packGone = false, reconnect = true),           // poll, which refreshes the token if it lapsed
        Ending(4403, "forbidden", packGone = false, reconnect = false),             // the account, never the pack: the API says to pause
        Ending(4404, "not_in_pack", packGone = true, reconnect = false),
        Ending(4410, "pack_gone", packGone = true, reconnect = false),
        Ending(4400, "bad_hello", packGone = false, reconnect = false),             // a fault here: trying again would repeat it
        Ending(4408, "hello_timeout", packGone = false, reconnect = true),          // a link that stalled for 10 s
        Ending(1013, "unavailable", packGone = false, reconnect = true),
        Ending(1013, "too_slow", packGone = false, reconnect = true),
        Ending(1009, "", packGone = false, reconnect = true),
        Ending(1011, "", packGone = false, reconnect = true),                       // anything else: open it again later
    ).map { ending ->
        DynamicTest.dynamicTest("${ending.code} ${ending.reason.ifEmpty { "(no reason)" }}") {
            runBlocking<Unit> {
                Live().use { live ->
                    val stream = live.open()
                    live.service.next()
                    live.service.say("""{"type": "closed", "reason": "${ending.reason}"}""")
                    live.service.socket.close(ending.code, ending.reason)

                    val closed = LiveMessage.Closed(ending.code, ending.reason.ifEmpty { null })
                    assertEquals(listOf(closed), stream.all())
                    assertEquals(1000, live.service.closedWith())                       // the close handshake is finished from here
                    assertEquals(ending.packGone, closed.packGone)
                    assertEquals(ending.reconnect, closed.reconnect)
                    assertEquals(listOf("/live"), live.rig.requests.map { it.path })     // a refused token is not refreshed from here
                }
            }
        }
    }

    @Test
    fun `a dropped connection ends the stream once, with 1006, and is opened again later`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            live.rig.server.shutdown()                                             // the connection goes with it, and no close frame

            assertEquals(listOf(LiveMessage.Closed(1006, null)), stream.all())
            stream.close()                                                         // too late to say anything more
            assertEquals(emptyList<LiveMessage>(), stream.all())
            assertTrue(LiveMessage.Closed(1006, null).reconnect)
        }
    }

    @Test
    fun `a socket that never opens ends the stream the same way`() = runBlocking<Unit> {
        Live().use { live ->
            live.rig.serve { Rig.json(404, "{}") }                                  // no upgrade: not the live service at that address
            val stream = live.open()
            assertEquals(listOf(LiveMessage.Closed(1006, null)), stream.all())
            assertFalse(stream.presence(null))
        }
    }

    @Test
    fun `closing it here sends 1000, and the stream ends with it`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            stream.close()
            assertEquals(1000, live.service.closedWith())
            assertEquals(listOf(LiveMessage.Closed(1000, null)), stream.all())
        }
    }

    @Test
    fun `the stream closes with the scope it was opened in`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            live.scope.cancel()
            assertEquals(1000, live.service.closedWith())
            assertEquals(listOf(LiveMessage.Closed(1000, null)), stream.all())
        }
    }

    @Test
    fun `a collector 1,000 messages behind ends the stream with 1013, after everything that was waiting`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            live.service.say(*Array(1001) { head(it + 1L) })
            assertEquals(1000, live.service.closedWith())                          // the app hung up at the one too many

            val all = stream.all()
            assertEquals((1..1000L).map { LiveMessage.Head(it) }, all.dropLast(1))
            assertEquals(LiveMessage.Closed(1013, "too_slow"), all.last())
            assertTrue(all.last().let { it is LiveMessage.Closed && it.reconnect })
        }
    }

    @Test
    fun `a collector that keeps up is never cut off, however many messages come`() = runBlocking<Unit> {
        Live().use { live ->
            val stream = live.open()
            live.service.next()
            val got = Channel<LiveMessage>(Channel.UNLIMITED)
            live.scope.launch { stream.messages.collect { got.send(it) } }

            var seq = 0L
            repeat(3) {                                                              // 1,800 in all, never more than 600 waiting
                val from = seq + 1
                repeat(600) { live.service.say(head(++seq)) }
                withTimeout(5_000) { for (n in from..seq) assertEquals(LiveMessage.Head(n), got.receive()) }
            }
            assertTrue(got.tryReceive().isFailure)
            assertFalse(live.service.closedBy.isDone)
        }
    }
}
