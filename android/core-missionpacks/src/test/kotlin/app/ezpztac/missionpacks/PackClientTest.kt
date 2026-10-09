package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.FakePackServer.Call
import app.ezpztac.network.ApiException
import app.ezpztac.network.LiveMessage
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The client that keeps one pack in step with the server, on virtual time, against [FakePackServer], a [FakeLive] stream, the
 * device's copy ([InMemoryPackStore]) and a [RecordingKeeper] library.
 *
 * The first groups are the web's packClient.test.js, case for case, with the same pack: LZ HAWK, landing heading 270, at event 3.
 * Where Android differs on purpose (docs in [PackClient]), the case says so. The rest is what only the device does: its own copy
 * read back after the app was ended, edits made with no signal, pausing for the account's sake, and keeping what a pack refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackClientTest {
    private companion object {
        val COLIN = PackUser(1, "Colin")
        val SAM = PackUser(2, "Sam")
        const val LIVE = "ws://live.test/live"

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun setHeading(value: Int) = json("""{"type": "set", "item": "lz-1", "path": ["flightData", "landingHeading"], "value": $value}""")

        val CREATE_LZ = json("""{"type": "item.create", "item": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": {"flightData": {"landingHeading": 270}}}""")

        fun headingOf(data: JsonElement?): Int = ((data as JsonObject)["flightData"] as JsonObject)["landingHeading"]!!.jsonPrimitive.int
    }

    /**
     * The device's copy, with a gate in front of its writes and one in front of forgetting a pack (Room's suspend; this one's do
     * not), and writes that fail as a full disk fails them: each of [failing] picks the next write it fails, in turn.
     */
    private class GatedStore(val inner: InMemoryPackStore = InMemoryPackStore()) : PackStore by inner {
        var gate: CompletableDeferred<Unit>? = null
        var forgetGate: CompletableDeferred<Unit>? = null
        val failing = ArrayDeque<(PackSession?, PackSession) -> Boolean>()
        var failed = 0

        override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
            gate?.await()
            if (failing.firstOrNull()?.invoke(before, after) == true) {
                failing.removeFirst()
                failed += 1
                throw IOException("The device is full.")
            }
            inner.write(before, after, me)
        }

        override suspend fun forget(pack: String) {
            forgetGate?.await()
            inner.forget(pack)
        }
    }

    private class Rig(val test: TestScope, live: String?, val store: PackStore) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        // OP DK: Colin's, Sam an editor in it, and LZ HAWK made at event 3, as the web's tests have it.
        val server = FakePackServer(live).apply {
            person(COLIN.id, COLIN.name)
            person(SAM.id, SAM.name)
            createPack("p-1", "OP DK", owner = COLIN.id, members = mapOf(SAM.id to "editor"))
            elsewhere("p-1", COLIN.id, CREATE_LZ)
        }
        val keeper = RecordingKeeper()

        /** Holds the library while it keeps something, until released. */
        var keeperGate: CompletableDeferred<Unit>? = null
        private val library = object : PackKeeper {
            override suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome> {
                keeperGate?.await()
                return keeper.keep(pack, versions)
            }
        }
        val notices = mutableListOf<PackNotice>()
        var drains = 0
        private var ids = 0

        fun client(uuid: String = "p-1", open: Boolean = true, user: PackUser = SAM, foreground: Boolean = true): PackClient = PackClient(
            uuid, user, server, store, library, scope, dispatcher,
            newId = { "op-${++ids}" }, notify = { notices += it }, requestBackgroundDrain = { drains++ }, open = open, foreground = foreground,
        )

        fun start(uuid: String = "p-1", open: Boolean = true): PackClient = client(uuid, open).also {
            it.start()
            test.runCurrent()
        }

        val socket: FakeLive get() = server.sockets.last()

        fun welcome(socket: FakeLive = this.socket) {
            socket.welcome(server.headSeq("p-1"))
            test.runCurrent()
        }

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        /** Holds the next [call] in flight, before the server looks at it (or, [after], once it has done the work), until released. */
        fun holdNext(call: Call, after: Boolean = false): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            var waiting = true
            val hook: suspend (Call) -> Unit = {
                if (it == call && waiting) {
                    waiting = false
                    gate.await()
                }
            }
            if (after) server.onAnswer = hook else server.onCall = hook
            return gate
        }

        suspend fun stored(): PackSession? = store.load("p-1", SAM.id)

        fun opsCalls(): Int = server.calls.count { it.startsWith("ops ") }

        fun eventCalls(): List<String> = server.calls.filter { it.startsWith("events ") }
    }

    private fun rig(live: String? = LIVE, store: PackStore = InMemoryPackStore(), body: suspend TestScope.(Rig) -> Unit) = runTest {
        val rig = Rig(this, live, store)
        try {
            body(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    private val PackClient.session: PackSession get() = state.value.session!!

    private val PackClient.status: PackStatus get() = state.value.status

    private fun PackClient.heading(): Int = headingOf(state.value.item("lz-1")!!.data)

    private fun PackSession.pendingIds() = pending.map { it.op["client_op_id"]!!.jsonPrimitive.content to it.state }

    private fun PackSession.droppedIds() = dropped.map { it.op["client_op_id"]!!.jsonPrimitive.content to it.reason }

    // -- The web's cases ---------------------------------------------------------------------------------------------------

    @Nested
    inner class AskingForWhatIsNew {
        @Test
        fun `fetches at once when asked, as after a copy from the library, which does not come through the operations`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.announce = false
            r.server.elsewhere("p-1", COLIN.id, json("""{"type": "item.create", "item": "ps-1", "kind": "pointset", "name": "PTS", "data": [{"id": "a"}]}"""))
            client.refresh()
            runCurrent()
            assertEquals("events p-1 since=3", r.server.calls.last())
            assertEquals(listOf("lz-1", "ps-1"), client.state.value.items.map { it.uuid })
        }
    }

    @Nested
    inner class OpeningAPack {
        @Test
        fun `loads it, and opens the stream for the person signed in`() = rig { r ->
            val client = r.start()
            assertEquals(listOf("pack p-1"), r.server.calls)
            assertEquals(LIVE, r.socket.url)
            assertEquals(SAM.id, r.socket.user)                                 // the hello and its token are core-network's (PackLiveTest)
            assertEquals(PackStatus.POLLING, client.status)                     // until the stream says welcome
            r.welcome()
            assertEquals(PackStatus.LIVE, client.status)
            assertEquals(listOf("LZ HAWK"), client.state.value.items.map { it.name })
            r.advance(10_000)
            assertEquals(listOf("pack p-1"), r.server.calls)                    // no polling while live
        }

        @Test
        fun `catches up when the pack moved on between loading and the welcome`() = rig { r ->
            val client = r.start()
            r.server.elsewhere("p-1", COLIN.id, setHeading(90))
            r.server.elsewhere("p-1", COLIN.id, setHeading(95))
            r.welcome()
            assertEquals(listOf("events p-1 since=3"), r.eventCalls())
            assertEquals(95, client.heading())
        }

        @Test
        fun `polls every few seconds where there is no live stream, and not while the app is in the back`() = rig(live = null) { r ->
            val client = r.start()
            assertTrue(r.server.sockets.isEmpty())
            r.advance(3_000)
            assertEquals(listOf("events p-1 since=3 background"), r.eventCalls())  // waits behind an analysis on the server
            client.foreground(false)
            r.advance(6_000)
            assertEquals(1, r.eventCalls().size)
        }

        @Test
        fun `says when the pack is not there to open`() = rig { r ->
            val client = r.start(uuid = "p-2")
            assertEquals(PackStatus.GONE, client.status)
            assertEquals(listOf<PackNotice>(PackNotice.Gone("p-2", "not_found")), r.notices)
        }
    }

    @Nested
    inner class APackThatCouldNotBeLoaded {
        @Test
        fun `is tried again, less often the longer it fails, until it opens`() = rig { r ->
            r.server.failNext(Call.PACK, r.server.refusal(503, "unavailable"))
            r.server.failNext(Call.PACK, NetworkException("timeout", requestMayHaveBeenSent = true))
            val client = r.start()
            fun loads() = r.server.calls.count { it == "pack p-1" }
            assertEquals(PackStatus.ERROR, client.status)
            r.advance(999)
            assertEquals(1, loads())
            r.advance(1)
            assertEquals(2, loads())
            assertEquals(PackStatus.ERROR, client.status)
            r.advance(1_999)
            assertEquals(2, loads())
            r.advance(1)
            assertEquals(3, loads())
            assertEquals(PackStatus.POLLING, client.status)
            assertEquals(listOf("LZ HAWK"), client.state.value.items.map { it.name })
            assertEquals(1, r.server.sockets.size)
        }

        @Test
        fun `waits while the app is in the back, and is tried at once when it is shown or the connection is back`() = rig { r ->
            r.server.offline = true
            val client = r.start()
            assertEquals(PackStatus.ERROR, client.status)
            client.foreground(false)
            r.advance(60_000)
            assertEquals(1, r.server.attempts.size)
            r.server.offline = false
            client.wake()
            runCurrent()
            assertEquals(2, r.server.attempts.size)
            assertEquals(1, client.state.value.items.size)
            client.wake()                                                       // open now: nothing more to try
            runCurrent()
            assertEquals(2, r.server.attempts.size)
        }

        @Test
        fun `is not tried again once it is gone, or closed`() = rig { r ->
            r.server.failNext(Call.PACK, r.server.refusal(403, "forbidden"))
            val gone = r.start()
            assertEquals(PackStatus.GONE, gone.status)
            gone.wake()
            r.advance(60_000)
            assertEquals(1, r.server.calls.count { it == "pack p-1" })

            r.server.failNext(Call.PACK, r.server.refusal(500, "server_error"))
            val closed = r.start()
            assertEquals(PackStatus.ERROR, closed.status)
            closed.stop()
            closed.wake()
            r.advance(60_000)
            assertEquals(2, r.server.calls.count { it == "pack p-1" })
            assertTrue(closed.halted)
        }
    }

    @Nested
    inner class TheLiveStream {
        @Test
        fun `applies the next event as it comes, without asking the server`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.elsewhere("p-1", COLIN.id, setHeading(90))                 // the service passes it on
            runCurrent()
            assertEquals(90, client.heading())
            assertTrue(r.eventCalls().isEmpty())
        }

        @Test
        fun `fetches what it missed when an event skips ahead, or only its number came`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.announce = false
            r.server.elsewhere("p-1", COLIN.id, setHeading(90))
            r.server.elsewhere("p-1", COLIN.id, setHeading(95))
            r.socket.event(5, r.server.log("p-1")[4])
            runCurrent()
            assertEquals(listOf("events p-1 since=3"), r.eventCalls())
            assertEquals(95, client.heading())
            r.server.elsewhere("p-1", COLIN.id, setHeading(100))
            r.socket.head(6)
            runCurrent()
            assertEquals(100, client.heading())
            r.socket.resync()
            runCurrent()
            assertEquals("events p-1 since=6", r.eventCalls().last())
        }

        @Test
        fun `catches up page by page until the log has no more`() = rig { r ->
            val client = r.start()
            r.server.pageSize = 2
            repeat(5) { r.server.elsewhere("p-1", COLIN.id, setHeading(100 + it)) }   // events 4 to 8
            r.welcome()
            assertEquals(listOf("events p-1 since=3", "events p-1 since=5", "events p-1 since=7"), r.eventCalls())
            assertEquals(104, client.heading())
            assertEquals(8, client.session.seq)
        }

        @Test
        fun `a copy that disagrees with the server takes the server's again, keeping what is pending`() = rig { r ->
            val client = r.start()
            r.welcome()
            val answer = r.holdNext(Call.OPS)                                   // a batch out, held before the server looks at it
            client.edit(listOf(setHeading(90)))
            runCurrent()
            r.server.calls.clear()
            // An event this copy cannot apply: it changes an item the copy does not have. The server never sends one; were this
            // copy ever wrong, it would look like this.
            r.socket.event(
                4,
                json(
                    """{"seq": 4, "type": "set", "item": "lz-9", "actor": {"id": 1, "name": "Colin"}, "summary": "Colin edited \"LZ OWL\".",
                        "status": "applied", "reason": null, "client_op_id": null, "created_at": "2026-10-05T12:00:59",
                        "op": {"type": "set", "item": "lz-9", "path": ["notes"], "value": "x"}}""",
                ),
            )
            runCurrent()
            assertEquals(listOf("pack p-1"), r.server.calls)
            assertFalse(client.session.diverged)
            assertEquals(3, client.session.seq)                                 // the server's copy, as of its head
            assertEquals(listOf("op-1" to PendingState.SENT), client.session.pendingIds())
            assertEquals(90, client.heading())
            answer.complete(Unit)
            runCurrent()
            assertTrue(client.session.pending.isEmpty())
            assertEquals(90, client.heading())
        }

        @Test
        fun `falls back to polling when the stream drops, and opens it again`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.socket.drop(1006)
            runCurrent()
            assertEquals(PackStatus.POLLING, client.status)
            r.advance(1_000)
            assertEquals(2, r.server.sockets.size)                              // reconnecting
            r.advance(2_000)
            assertEquals(1, r.eventCalls().size)
            r.welcome(r.server.sockets[1])
            assertEquals(PackStatus.LIVE, client.status)
        }

        @Test
        fun `once let in, a stream that drops is tried again after the shortest pause, not after earlier failures' longer ones`() = rig { r ->
            r.start()
            r.welcome()
            r.socket.drop(1006)
            r.advance(1_000)
            assertEquals(2, r.server.sockets.size)
            r.socket.drop(1006)                                                 // never let in: the next pause is longer
            r.advance(1_999)
            assertEquals(2, r.server.sockets.size)
            r.advance(1)
            assertEquals(3, r.server.sockets.size)
            r.welcome()
            r.socket.drop(1006)
            r.advance(1_000)
            assertEquals(4, r.server.sockets.size)
        }

        @Test
        fun `stops for good when the pack is deleted, keeping what it had not taken`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.offline = true                                             // the edit cannot go
            client.edit(listOf(setHeading(90)))
            runCurrent()
            r.server.deletePack("p-1")                                          // the stream closes with 4410
            runCurrent()
            assertEquals(PackStatus.GONE, client.status)
            assertTrue(PackNotice.Gone("p-1", "not_found") in r.notices)
            r.advance(60_000)
            assertEquals(1, r.server.sockets.size)
            // Not like the web, which would lose the edit: the person's version is in their library, and the device forgets the pack.
            val kept = r.keeper.records.single()
            assertEquals("LZ HAWK (my edits)", kept.version.name)
            assertEquals(90, headingOf(kept.version.data))
            assertNull(r.stored())
            assertEquals("gone", client.edit(listOf(setHeading(95))))
        }

        @Test
        fun `shows who else is there, and tells them what this person has open, at most every 100 ms`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.socket.people(listOf(json("""{"session": "s-1", "user_id": 1, "name": "Colin", "focus": {"item": "lz-1"}}""")))
            runCurrent()
            assertEquals("Colin", client.state.value.people.single()["name"]!!.jsonPrimitive.content)
            client.setFocus(json("""{"item": "lz-1"}"""))
            client.setFocus(json("""{"item": "lz-1", "graphic": "h-1"}"""))
            client.setFocus(json("""{"item": "lz-1", "graphic": "h-2"}"""))
            runCurrent()
            assertEquals(listOf(json("""{"item": "lz-1"}""")), r.socket.presenceSent)
            r.advance(100)
            assertEquals(listOf(json("""{"item": "lz-1"}"""), json("""{"item": "lz-1", "graphic": "h-2"}""")), r.socket.presenceSent)
        }
    }

    @Nested
    inner class EditsMadeHere {
        @Test
        fun `show at once, are sent with base_seq, and the answer confirms them`() = rig { r ->
            val client = r.start()
            r.welcome()
            val answer = r.holdNext(Call.OPS)
            assertNull(client.edit(listOf(setHeading(90))))
            assertEquals(90, client.heading())
            runCurrent()
            val op1 = JsonObject(setHeading(90) + ("client_op_id" to JsonPrimitive("op-1")))
            assertEquals(listOf(json("""{"ops": [$op1], "base_seq": 3}""")), r.server.batches)
            client.edit(listOf(setHeading(95)))                                 // waits for the batch in flight
            runCurrent()
            assertEquals(1, r.server.batches.size)
            answer.complete(Unit)
            runCurrent()
            val op2 = JsonObject(setHeading(95) + ("client_op_id" to JsonPrimitive("op-2")))
            assertEquals(json("""{"ops": [$op2], "base_seq": 4}"""), r.server.batches.last())
            assertEquals(95, client.heading())
            assertTrue(client.session.pending.isEmpty())
        }

        @Test
        fun `are sent again, unchanged, when no answer came`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.failNext(Call.OPS, NetworkException("reset", requestMayHaveBeenSent = true))
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(90, client.heading())                                  // still shown
            assertEquals(1, r.opsCalls())
            r.advance(1_000)
            assertEquals(2, r.opsCalls())
            assertEquals(r.server.calls.first { it.startsWith("ops ") }, r.server.calls.last { it.startsWith("ops ") })
            assertTrue(client.session.pending.isEmpty())
        }

        @Test
        fun `waiting to be sent again go at once when the connection is back, without waiting out the pause`() = rig(live = null) { r ->
            val client = r.start()
            r.server.failNext(Call.OPS, NetworkException("reset", requestMayHaveBeenSent = true))
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(1, r.opsCalls())
            client.wake()
            runCurrent()
            assertEquals(2, r.opsCalls())                                       // no time has passed
            assertTrue(client.session.pending.isEmpty())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }

        @Test
        fun `refused because the pack was finished are dropped and further edits refused`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.announce = false
            r.server.finish("p-1", COLIN.id)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertTrue(client.session.readOnly)
            assertEquals(listOf("op-1" to "pack_finished"), client.session.droppedIds())
            assertEquals(270, client.heading())
            assertEquals("read_only", client.edit(listOf(setHeading(95))))
            assertEquals(listOf<PackNotice>(PackNotice.Dropped("p-1", 1, setOf("pack_finished"))), r.notices)
            assertTrue(r.keeper.calls.isEmpty())                                // the pack is open: they wait for the person
        }

        @Test
        fun `sent again after a lost answer and refused keep what the refusal says the pack took, and fetch its events`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.announce = false
            r.server.loseAnswers = 1
            client.edit(listOf(setHeading(90)))                                 // taken at event 4; the answer is lost
            runCurrent()
            r.server.finish("p-1", COLIN.id)
            client.edit(listOf(setHeading(91)))                                 // made while the first send's answer was lost: both go
            runCurrent()
            assertEquals(listOf("op-1", "op-2"), r.server.batches.last()["ops"]!!.jsonArray.map { it.jsonObject["client_op_id"]!!.jsonPrimitive.content })
            assertEquals(listOf("events p-1 since=3"), r.eventCalls())
            assertTrue(client.session.pending.isEmpty())
            assertEquals(listOf("op-2" to "pack_finished"), client.session.droppedIds())
            assertEquals(90, client.heading())
            assertEquals(1, r.server.log("p-1").count { it["client_op_id"] == JsonPrimitive("op-1") })
        }

        @Test
        fun `whose events did not all come with the answer are fetched`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.announce = false
            r.server.pageSize = 1
            r.server.elsewhere("p-1", COLIN.id, setHeading(80))                 // event 4, not seen here yet
            client.edit(listOf(setHeading(90)))                                 // event 5; the answer carries only 4
            runCurrent()
            assertEquals(listOf("events p-1 since=4"), r.eventCalls())
            assertTrue(client.session.pending.isEmpty())
            assertEquals(90, client.heading())
        }
    }

    @Nested
    inner class Stopping {
        @Test
        fun `closes the stream and asks nothing more`() = rig { r ->
            val client = r.start()
            r.welcome()
            client.stop()
            runCurrent()
            assertEquals(1000, r.socket.closedWith)
            r.advance(60_000)
            assertEquals(1, r.server.sockets.size)
            assertTrue(r.eventCalls().isEmpty())
            assertTrue(client.halted)
        }

        @Test
        fun `still sends the edits waiting behind a batch in flight`() = rig { r ->
            val client = r.start()
            r.welcome()
            val answer = r.holdNext(Call.OPS)
            client.edit(listOf(setHeading(100)))
            client.edit(listOf(setHeading(110)))                                // queued behind the first
            runCurrent()
            client.stop()
            runCurrent()
            assertEquals(1000, r.socket.closedWith)
            answer.complete(Unit)
            runCurrent()
            assertEquals(2, r.server.batches.size)
            val op2 = JsonObject(setHeading(110) + ("client_op_id" to JsonPrimitive("op-2")))
            assertEquals(json("""{"ops": [$op2], "base_seq": 4}"""), r.server.batches.last())
            assertTrue(r.stored()!!.pending.isEmpty())
            r.advance(60_000)
            assertTrue(r.eventCalls().isEmpty())
            assertTrue(client.halted)
        }

        @Test
        fun `a pack closed before it could be loaded still sends what was waiting on the device`() = rig(live = null) { r ->
            val first = r.start()
            r.server.offline = true
            first.edit(listOf(setHeading(90)))
            runCurrent()
            first.halt()
            r.server.offline = false
            r.server.failNext(Call.PACK, r.server.refusal(500, "server_error"))
            val client = r.start()
            assertEquals(PackStatus.OFFLINE, client.status)
            client.stop()
            runCurrent()
            assertTrue(client.halted)
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(r.stored()!!.pending.isEmpty())
        }

        @Test
        fun `keeps sending again a send that failed until it is taken`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.failNext(Call.OPS, NetworkException("reset", requestMayHaveBeenSent = true))
            r.server.failNext(Call.OPS, r.server.refusal(503, "unavailable"))
            client.edit(listOf(setHeading(100)))
            runCurrent()
            client.stop()
            r.advance(1_000)
            assertEquals(2, r.opsCalls())
            r.advance(2_000)
            assertEquals(3, r.opsCalls())
            assertTrue(r.stored()!!.pending.isEmpty())
            assertTrue(client.halted)
            // Draining, a failed send asks the background sync to take over should the app be ended before the next try.
            assertEquals(1, r.drains)
        }

        // The web gives up what waits ("signed_out") once someone else signs in. Here it waits for its own account, and is never
        // sent as anyone else (core-network refuses a call made for another account before sending it).
        @Test
        fun `never sends as someone who signed in since, and gives nothing up - the edits wait for their own account`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.failNext(Call.OPS, NetworkException("reset", requestMayHaveBeenSent = false))
            client.edit(listOf(setHeading(100)))
            runCurrent()
            client.stop()
            r.server.signedIn = COLIN.id
            r.advance(30_000)
            assertEquals(1, r.opsCalls())
            assertTrue(client.halted)
            assertTrue(PackNotice.Paused("p-1", "other_account") in r.notices)
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
            assertTrue(r.stored()!!.dropped.isEmpty())
            assertTrue(r.keeper.calls.isEmpty())
            assertEquals("closed", client.edit(listOf(setHeading(120))))
            assertNull(r.store.load("p-1", COLIN.id))                           // never another account's

            r.server.signedIn = SAM.id                                          // Sam again: what waits goes
            val again = r.start(open = false)
            assertEquals(100, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(1, r.server.log("p-1").count { it["client_op_id"] == JsonPrimitive("op-1") })
            assertTrue(r.stored()!!.pending.isEmpty())
            assertTrue(again.halted)
        }

        @Test
        fun `keeps what waits when the person has signed out, rather than send it with no session`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.failNext(Call.OPS, NetworkException("reset", requestMayHaveBeenSent = false))
            client.edit(listOf(setHeading(100)))
            runCurrent()
            r.server.signedOut = true
            client.stop()
            r.advance(60_000)
            assertEquals(1, r.opsCalls())
            assertTrue(client.halted)
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
            assertTrue(r.stored()!!.dropped.isEmpty())
        }

        @Test
        fun `still hears the answer to a batch sent before signing out, and sends nothing after it`() = rig { r ->
            val client = r.start()
            r.welcome()
            val answer = r.holdNext(Call.OPS)
            client.edit(listOf(setHeading(100)))
            client.edit(listOf(setHeading(110)))                                // queued behind the first
            runCurrent()
            r.server.signedOut = true
            client.stop()
            answer.complete(Unit)
            runCurrent()
            assertEquals(1, r.server.batches.size)
            assertEquals(100, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(client.halted)
            assertEquals(listOf("op-2" to PendingState.QUEUED), r.stored()!!.pendingIds())
            assertTrue(r.stored()!!.dropped.isEmpty())
        }

        @Test
        fun `keeps a batch sent before signing out that got no answer, without sending it again`() = rig { r ->
            val client = r.start()
            r.welcome()
            val answer = CompletableDeferred<Unit>()
            var first = true
            r.server.onCall = {
                if (it == Call.OPS && first) {
                    first = false
                    answer.await()
                    throw NetworkException("reset", requestMayHaveBeenSent = true)
                }
            }
            client.edit(listOf(setHeading(100)))
            runCurrent()
            r.server.signedOut = true
            client.stop()
            answer.complete(Unit)
            runCurrent()
            r.advance(60_000)
            assertEquals(1, r.opsCalls())
            assertTrue(client.halted)
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
        }
    }

    // -- What only the device does -----------------------------------------------------------------------------------------

    @Nested
    inner class TheDevicesCopy {
        @Test
        fun `an edit returns only once it is on the device, and is shown only then`() = rig(live = null, store = GatedStore()) { r ->
            val store = r.store as GatedStore
            val client = r.start()
            val written = CompletableDeferred<Unit>()
            store.gate = written
            val edit = async { client.edit(listOf(setHeading(90))) }
            runCurrent()
            assertFalse(edit.isCompleted)
            assertEquals(270, client.heading())
            assertTrue(store.inner.load("p-1", SAM.id)!!.pending.isEmpty())
            assertEquals(0, r.opsCalls())
            written.complete(Unit)
            runCurrent()
            assertNull(edit.await())
            assertEquals(90, client.heading())
            assertEquals(1, r.opsCalls())
        }

        @Test
        fun `a pack read back with no signal is shown at once and can be edited, and what is made then goes once it can`() = rig(live = null) { r ->
            r.start().halt()                                                    // opened once, so the device has it
            r.server.offline = true
            val client = r.start()
            assertEquals(PackStatus.OFFLINE, client.status)
            assertEquals(listOf("LZ HAWK"), client.state.value.items.map { it.name })
            assertNull(client.edit(listOf(setHeading(90))))
            assertEquals(90, client.heading())
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
            r.advance(1_000)                                                    // tried again: still no signal
            assertEquals(PackStatus.OFFLINE, client.status)
            r.server.offline = false
            client.wake()
            runCurrent()
            assertEquals(PackStatus.POLLING, client.status)
            assertTrue(r.stored()!!.pending.isEmpty())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }

        @Test
        fun `a batch out when the app was ended is not sent again until a catch-up from the stored seq confirms what the server took`() = rig(live = null) { r ->
            val first = r.start()
            r.holdNext(Call.OPS, after = true)                                  // the server takes it; the answer never reaches the device
            first.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(listOf("op-1" to PendingState.SENT), r.stored()!!.pendingIds())  // written as sent before it went
            first.halt()                                                        // the system ends the app
            r.server.calls.clear()

            val second = r.start()
            assertEquals(listOf("events p-1 since=3", "pack p-1"), r.server.calls)
            assertEquals(1, r.server.batches.size)
            assertTrue(second.session.pending.isEmpty())
            assertEquals(90, second.heading())
            assertTrue(r.stored()!!.pending.isEmpty())
        }

        @Test
        fun `a batch out when the app was ended that never reached the server goes again, unchanged, after the catch-up`() = rig(live = null) { r ->
            val first = r.start()
            r.holdNext(Call.OPS)                                                // held before the server looks at it, and never let go
            first.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(listOf("op-1" to PendingState.SENT), r.stored()!!.pendingIds())
            first.halt()
            r.server.calls.clear()

            val second = r.start()
            assertEquals(listOf("events p-1 since=3", "pack p-1", "ops p-1 base=3 [op-1]"), r.server.calls)
            assertEquals(r.server.batches.first(), r.server.batches.last())
            assertTrue(second.session.pending.isEmpty())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }

        @Test
        fun `edits the server took but whose events had not come are confirmed by the catch-up, and nothing stays acked`() = rig(live = null) { r ->
            val first = r.start()
            r.server.pageSize = 1
            r.server.elsewhere("p-1", COLIN.id, setHeading(80))                 // event 4, not seen here yet
            r.holdNext(Call.EVENTS)                                              // the catch-up the answer asks for never comes back
            first.edit(listOf(setHeading(90)))                                  // event 5: the answer carries only 4
            runCurrent()
            assertEquals(listOf("op-1" to PendingState.ACKED), r.stored()!!.pendingIds())
            first.halt()
            r.server.calls.clear()

            val second = r.start()
            assertEquals(listOf("events p-1 since=4", "pack p-1"), r.server.calls)
            assertTrue(second.session.pending.isEmpty())
            assertEquals(90, second.heading())
            assertEquals(1, r.server.batches.size)
        }

        @Test
        fun `a copy read back with only queued edits needs no catch-up first`() = rig(live = null) { r ->
            val first = r.start()
            r.server.offline = true
            first.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
            first.halt()
            r.server.offline = false
            r.server.calls.clear()

            r.start()
            assertEquals(listOf("pack p-1", "ops p-1 base=3 [op-1]"), r.server.calls)
            assertTrue(r.stored()!!.pending.isEmpty())
        }

        @Test
        fun `a pack finished while the device was offline drops what was made then, and keeps it once the pack is closed`() = rig(live = null) { r ->
            val first = r.start()
            r.server.offline = true
            first.edit(listOf(setHeading(90)))
            first.edit(listOf(setHeading(95)))
            runCurrent()
            first.halt()
            r.server.offline = false
            r.server.finish("p-1", COLIN.id)

            val second = r.start()
            assertTrue(second.state.value.readOnly)
            assertEquals(listOf("op-1" to "pack_finished", "op-2" to "pack_finished"), second.session.droppedIds())
            assertEquals(270, second.heading())
            assertEquals(listOf<PackNotice>(PackNotice.Dropped("p-1", 2, setOf("pack_finished"))), r.notices)
            assertEquals("read_only", second.edit(listOf(setHeading(100))))
            assertTrue(r.keeper.records.isEmpty())                              // open: they wait for the person

            second.stop()
            runCurrent()
            assertTrue(second.halted)
            val kept = r.keeper.records.single()
            assertEquals(KeptVersion("p-1:lz-1:op-1", "lz", "LZ HAWK (my edits)", kept.version.data), kept.version)
            assertEquals(95, headingOf(kept.version.data))
            assertEquals(
                PackNotice.Kept("p-1", "OP DK", listOf(KeptOutcome.Saved("p-1:lz-1:op-1", "lz", kept.libraryUuid, "LZ HAWK (my edits)")), setOf("pack_finished")),
                r.notices.last(),
            )
            assertTrue(r.stored()!!.dropped.isEmpty())
            assertTrue(r.server.batches.isEmpty())                              // none of it ever reached the pack
        }

        @Test
        fun `a batch out when the app was ended is confirmed ahead of a finish, and only what was queued behind it is dropped`() = rig(live = null) { r ->
            val first = r.start()
            r.holdNext(Call.OPS, after = true)
            first.edit(listOf(setHeading(90)))                                  // taken at event 4; the answer never comes
            runCurrent()
            first.edit(listOf(setHeading(95)))                                  // queued behind it
            runCurrent()
            first.halt()
            r.server.finish("p-1", COLIN.id)
            r.server.calls.clear()

            val second = r.start()
            assertEquals(listOf("events p-1 since=3", "pack p-1"), r.server.calls)
            assertTrue(second.session.pending.isEmpty())
            assertEquals(listOf("op-2" to "pack_finished"), second.session.droppedIds())
            assertEquals(90, second.heading())
        }

        @Test
        fun `a copy read back that disagrees with the server takes the server's again in its catch-up, keeping what is pending`() = rig(live = null) { r ->
            val first = r.start()
            r.holdNext(Call.OPS)                                                // a batch out, held before the server looks at it
            first.edit(listOf(setHeading(90)))
            runCurrent()
            first.halt()
            // The copy lost LZ HAWK (it never should), and someone else has changed it since: the catch-up cannot apply that.
            val stored = r.stored()!!
            val wrong = PackSessions.withView(stored.copy(confirmed = stored.confirmed - "lz-1", info = stored.info - "lz-1", order = emptyList()))
            r.store.write(stored, wrong, SAM.id)
            r.server.elsewhere("p-1", COLIN.id, setHeading(80))                 // event 4
            r.server.calls.clear()

            val second = r.start()
            // Taken again at once, then loaded as every open pack is, and the batch goes on top of what the server has.
            assertEquals(listOf("events p-1 since=3", "pack p-1", "pack p-1", "ops p-1 base=4 [op-1]"), r.server.calls)
            assertFalse(second.session.diverged)
            assertTrue(second.session.pending.isEmpty())
            assertEquals(90, second.heading())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }

        @Test
        fun `a batch read back whose resend could not be written goes when it is tried again, and what is made after it goes too`() =
            rig(live = null, store = GatedStore()) { r ->
                val store = r.store as GatedStore
                val first = r.start()
                r.holdNext(Call.OPS)                                            // held before the server looks at it, and never let go
                first.edit(listOf(setHeading(90)))
                runCurrent()
                first.halt()
                r.server.calls.clear()
                // The disk is full when that batch is put back to go again (the write that changes what is pending).
                store.failing += { before, after -> before?.pending !== after.pending }

                val second = r.start()
                assertEquals(1, store.failed)
                assertEquals(listOf("op-1" to PendingState.SENT), r.stored()!!.pendingIds())
                assertEquals(0, r.opsCalls())
                r.advance(1_000)                                                // tried again, and written this time
                assertEquals(listOf("events p-1 since=3", "pack p-1", "ops p-1 base=3 [op-1]"), r.server.calls)
                assertTrue(r.stored()!!.pending.isEmpty())
                assertNull(second.edit(listOf(setHeading(95))))
                runCurrent()
                assertEquals(95, headingOf(r.server.data("p-1", "lz-1")))
                assertTrue(r.stored()!!.pending.isEmpty())
            }

        @Test
        fun `a drain of a batch read back whose resend could not be written sends it when it is tried again, and halts`() =
            rig(live = null, store = GatedStore()) { r ->
                val store = r.store as GatedStore
                val first = r.start()
                r.holdNext(Call.OPS)
                first.edit(listOf(setHeading(90)))
                runCurrent()
                first.halt()
                r.server.calls.clear()
                store.failing += { before, after -> before?.pending !== after.pending }

                val drain = r.start(open = false)
                assertEquals(1, store.failed)
                assertFalse(drain.halted)
                r.advance(1_000)
                assertEquals(listOf("events p-1 since=3 background", "ops p-1 base=3 [op-1]"), r.server.calls)
                assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
                assertTrue(r.stored()!!.pending.isEmpty())
                assertTrue(drain.halted)
            }

        // A drain asks for nothing new, so only sending the batch again can settle it: its events never come by themselves.
        @Test
        fun `a drain whose answer and then whose resend could not be written sends the batch again once it can, and halts`() =
            rig(live = null, store = GatedStore()) { r ->
                val store = r.store as GatedStore
                val client = r.start()
                val answer = r.holdNext(Call.OPS, after = true)                 // the server takes it; the answer is held
                client.edit(listOf(setHeading(90)))
                runCurrent()
                client.stop()                                                   // closed: it only sends now
                runCurrent()
                repeat(2) { store.failing += { _, _ -> true } }                 // the disk is full: the answer's write, then the resend's
                answer.complete(Unit)
                runCurrent()
                r.advance(1_000)
                assertEquals(2, store.failed)
                assertFalse(client.halted)
                assertEquals(listOf("op-1" to PendingState.SENT), r.stored()!!.pendingIds())
                r.advance(2_000)                                                // written now: it goes again, and the server answers from its log
                assertEquals(2, r.opsCalls())
                assertEquals(1, r.server.log("p-1").count { it["client_op_id"] == JsonPrimitive("op-1") })
                assertTrue(r.stored()!!.pending.isEmpty())
                assertTrue(client.halted)
            }
    }

    @Nested
    inner class ForTheAccountsSake {
        @Test
        fun `Mission Packs turned off pauses, and nothing is dropped`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.setFeature(SAM.id, false)
            assertNull(client.edit(listOf(setHeading(90))))
            runCurrent()
            assertEquals(PackStatus.PAUSED, client.status)
            assertTrue(PackNotice.Paused("p-1", "feature_disabled") in r.notices)
            assertEquals(1000, r.socket.closedWith)
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
            assertTrue(r.stored()!!.dropped.isEmpty())
            assertEquals("paused", client.edit(listOf(setHeading(95))))
            val attempts = r.server.attempts.size
            r.advance(60_000)
            assertEquals(attempts, r.server.attempts.size)                      // nothing until the engine starts it again
        }

        @Test
        fun `a paused pack that is closed halts at once, and what waits stays on the device`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.setFeature(SAM.id, false)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(PackStatus.PAUSED, client.status)
            client.stop()
            runCurrent()
            assertTrue(client.halted)
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored()!!.pendingIds())
            assertTrue(r.keeper.calls.isEmpty())
        }

        // A copy on the device with an edit waiting (queued, or a batch that was out), and a refusal about the account when it is
        // opened again: of the pack itself, or of the catch-up a batch read back asks for first. The web reads such a 403 as the pack
        // gone; here nothing is dropped, kept or tried again until the engine starts the client again.
        private fun pausedWhenOpened(refuse: (FakePackServer) -> Unit, code: String, batchOut: Boolean) = rig(live = null) { r ->
            val first = r.start()
            if (batchOut) r.holdNext(Call.OPS) else r.server.offline = true
            first.edit(listOf(setHeading(90)))
            runCurrent()
            first.halt()
            r.server.offline = false
            val waiting = r.stored()!!.pendingIds()
            assertEquals(listOf("op-1" to if (batchOut) PendingState.SENT else PendingState.QUEUED), waiting)
            refuse(r.server)
            r.server.attempts.clear()

            val second = r.start()
            assertEquals(PackStatus.PAUSED, second.status)
            assertEquals(listOf<PackNotice>(PackNotice.Paused("p-1", code)), r.notices)
            assertEquals(listOf(if (batchOut) "events p-1 since=3" else "pack p-1"), r.server.attempts)
            assertEquals("paused", second.edit(listOf(setHeading(95))))
            r.advance(60_000)
            assertEquals(1, r.server.attempts.size)
            assertEquals(waiting, r.stored()!!.pendingIds())
            assertTrue(r.stored()!!.dropped.isEmpty())
            assertTrue(r.keeper.calls.isEmpty())
        }

        @Test
        fun `a pack opened after Mission Packs were turned off pauses, and the edits waiting stay as they were`() =
            pausedWhenOpened({ it.setFeature(SAM.id, false) }, "feature_disabled", batchOut = false)

        @Test
        fun `a pack opened once the person has signed out pauses, and the edits waiting stay as they were`() =
            pausedWhenOpened({ it.signedOut = true }, "http_401", batchOut = false)

        @Test
        fun `a batch read back whose catch-up is refused for the account pauses, and stays out on the device`() =
            pausedWhenOpened({ it.setFeature(SAM.id, false) }, "feature_disabled", batchOut = true)

        // The service sends 4403 when the API's access check refuses the account. The web takes it as the pack gone; here the
        // client asks the API, whose own answer decides.
        @Test
        fun `a 4403 from the live service is asked about, not taken as the pack gone`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.setFeature(SAM.id, false)
            r.socket.drop(4403)
            runCurrent()
            assertEquals(PackStatus.PAUSED, client.status)
            assertTrue(PackNotice.Paused("p-1", "feature_disabled") in r.notices)
            r.advance(60_000)
            assertEquals(1, r.server.sockets.size)
        }

        @Test
        fun `a 4403 the API does not bear out leaves the pack polled, and the stream is not opened again`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.socket.drop(4403)
            runCurrent()
            assertEquals(listOf("events p-1 since=3"), r.eventCalls())
            assertEquals(PackStatus.POLLING, client.status)
            r.advance(60_000)
            assertEquals(1, r.server.sockets.size)
            assertTrue(r.eventCalls().size > 1)
        }

        @Test
        fun `a token the service refused polls and tries the stream again later, and a stream ended for the account is not reopened`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.socket.drop(4401)                                                 // the service's 4401: worth another try
            runCurrent()
            assertEquals(PackStatus.POLLING, client.status)
            r.advance(1_000)
            assertEquals(2, r.server.sockets.size)
            r.socket.drop(4401, LiveMessage.Closed.SIGNED_OUT)                  // this side's: someone else is signed in now
            r.advance(60_000)
            assertEquals(2, r.server.sockets.size)
            assertTrue(r.eventCalls().isNotEmpty())                             // the polls, whose answer pauses it if the account is why
        }
    }

    @Nested
    inner class WhatThePackRefused {
        @Test
        fun `refused because this person may now only view are dropped, and further edits refused`() = rig(live = null) { r ->
            val client = r.start()
            r.server.setRole("p-1", SAM.id, "viewer", COLIN.id)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(listOf("op-1" to "read_only"), client.session.droppedIds())
            assertEquals("viewer", client.session.role)
            assertEquals(270, client.heading())
            assertEquals("read_only", client.edit(listOf(setHeading(95))))
        }

        @Test
        fun `refused because this person is not in the pack any more are kept, and the pack is gone`() = rig(live = null) { r ->
            val client = r.start()
            r.server.remove("p-1", SAM.id, COLIN.id)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(PackStatus.GONE, client.status)
            assertTrue(PackNotice.Gone("p-1", "not_found") in r.notices)
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
            assertNull(r.stored())
        }

        @Test
        fun `a batch the server says is malformed is dropped alone, under the reason it gave, and the next edit goes`() = rig(live = null) { r ->
            val client = r.start()
            r.server.failNext(Call.OPS, r.server.refusal(400, "invalid_op", "index" to JsonPrimitive(0), "reason" to JsonPrimitive("bad_value")))
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(listOf("op-1" to "bad_value"), client.session.droppedIds())
            client.edit(listOf(setHeading(95)))
            runCurrent()
            assertEquals(95, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(client.session.pending.isEmpty())
        }

        @Test
        fun `a pack that is not this person's any more when it is loaded keeps the edits that were waiting`() = rig(live = null) { r ->
            val first = r.start()
            r.server.offline = true
            first.edit(listOf(setHeading(90)))
            runCurrent()
            first.halt()
            r.server.offline = false
            r.server.remove("p-1", SAM.id, COLIN.id)

            val second = r.start()
            assertEquals(PackStatus.GONE, second.status)
            assertTrue(PackNotice.Gone("p-1", "not_found") in r.notices)
            // Not like the web, which shuts down and loses them.
            val kept = r.keeper.records.single()
            assertEquals("LZ HAWK (my edits)", kept.version.name)
            assertEquals(90, headingOf(kept.version.data))
            assertNull(r.stored())
        }

        @Test
        fun `a pack that is not this person's any more when it is polled keeps the edits that were waiting`() = rig(live = null) { r ->
            val client = r.start()
            r.server.failNext(Call.OPS, RateLimitedException("Too many requests.", 10))
            client.edit(listOf(setHeading(90)))                                 // tried again in 10 s
            runCurrent()
            r.server.remove("p-1", SAM.id, COLIN.id)
            r.advance(3_000)                                                    // the poll is refused
            assertEquals(PackStatus.GONE, client.status)
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
            r.advance(60_000)
            assertEquals(1, r.opsCalls())
        }

        @Test
        fun `a copy that went gone and whose edits could not be kept then is gone again when it is opened, and they are kept`() = rig(live = null) { r ->
            val first = r.start()
            r.server.failNext(Call.OPS, RateLimitedException("Too many requests.", 10))
            first.edit(listOf(setHeading(90)))
            runCurrent()
            r.server.remove("p-1", SAM.id, COLIN.id)
            r.keeper.failNext = 1
            r.advance(3_000)                                                    // the poll is refused, and the library cannot be written
            assertEquals(PackStatus.GONE, first.status)
            assertEquals(listOf("op-1" to "gone"), r.stored()!!.droppedIds())   // still on the device

            val second = r.start()
            assertEquals(PackStatus.GONE, second.status)
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
            assertNull(r.stored())
        }

        @Test
        fun `once the pack is gone, nothing still on its way is written to the device`() = rig(store = GatedStore()) { r ->
            val store = r.store as GatedStore
            val client = r.start()
            r.welcome()
            r.server.failNext(Call.OPS, RateLimitedException("Too many requests.", 10))
            client.edit(listOf(setHeading(90)))                                 // waits to be tried again
            runCurrent()
            val events = r.holdNext(Call.EVENTS)
            r.socket.head(10)                                                   // a catch-up, held on the server
            runCurrent()
            r.keeperGate = CompletableDeferred()
            val forgetting = CompletableDeferred<Unit>()
            store.forgetGate = forgetting
            r.socket.drop(4404)                                                 // gone: what waits is dropped, and being kept
            runCurrent()
            assertEquals(PackStatus.GONE, client.status)
            val writes = store.inner.writes
            events.complete(Unit)                                               // the catch-up's answer arrives meanwhile
            runCurrent()
            r.keeperGate!!.complete(Unit)
            runCurrent()
            forgetting.complete(Unit)
            runCurrent()
            assertEquals(writes + 1, store.inner.writes)                        // the kept edits let go, and nothing after
            assertNull(r.stored())
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
        }

        @Test
        fun `a send asked to wait is tried again no sooner than the server asked`() = rig(live = null) { r ->
            val client = r.start()
            r.server.failNext(Call.OPS, RateLimitedException("Too many requests.", 10))
            client.edit(listOf(setHeading(90)))
            runCurrent()
            client.wake()                                                       // the connection coming back does not cut it short
            client.edit(listOf(setHeading(95)))                                 // nor does another edit
            r.advance(9_999)
            assertEquals(1, r.opsCalls())
            r.advance(1)
            assertEquals(2, r.opsCalls())
            assertTrue(client.session.pending.isEmpty())
        }

        @Test
        fun `an answer that could not be read is sent again, and the server answers it from its log`() = rig(live = null) { r ->
            val client = r.start()
            var once = true
            r.server.onAnswer = {
                if (it == Call.OPS && once) {
                    once = false
                    throw ApiException(200, "unreadable_response", "The server's answer could not be read.")
                }
            }
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(listOf("op-1" to PendingState.QUEUED), client.session.pendingIds())
            r.advance(1_000)
            assertEquals(2, r.opsCalls())
            assertTrue(client.session.pending.isEmpty())
            assertEquals(1, r.server.log("p-1").count { it["client_op_id"] == JsonPrimitive("op-1") })
        }

        @Test
        fun `an edit too large drops its batch only, and what was queued behind it goes`() = rig { r ->
            val client = r.start()
            r.welcome()
            r.server.maxItemBytes = 200
            val answer = r.holdNext(Call.OPS)
            client.edit(listOf(json("""{"type": "set", "item": "lz-1", "path": ["notes"], "value": "${"x".repeat(300)}"}""")))
            client.edit(listOf(setHeading(90)))
            runCurrent()
            answer.complete(Unit)
            runCurrent()
            assertEquals(listOf("op-1" to "item_too_large"), client.session.droppedIds())
            assertTrue(client.session.pending.isEmpty())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(PackNotice.Dropped("p-1", 1, setOf("item_too_large")) in r.notices)
        }

        @Test
        fun `an edit that reached the pack after what it changed was deleted is said to have changed nothing`() = rig(live = null) { r ->
            val client = r.start()
            val answer = r.holdNext(Call.OPS)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            r.server.elsewhere("p-1", COLIN.id, json("""{"type": "item.delete", "item": "lz-1"}"""))
            answer.complete(Unit)
            runCurrent()
            assertEquals(listOf<PackNotice>(PackNotice.OwnSkipped("p-1", 1)), r.notices)
            assertTrue(client.session.pending.isEmpty())
            assertTrue(client.state.value.items.isEmpty())
        }

        @Test
        fun `while the pack is open, what it refused waits on the device until it is kept`() = rig(live = null) { r ->
            val client = r.start()
            r.server.finish("p-1", COLIN.id)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(1, r.stored()!!.dropped.size)
            client.keepDropped()
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
            assertTrue(client.session.dropped.isEmpty())
            assertTrue(r.stored()!!.dropped.isEmpty())
            assertTrue(r.notices.last() is PackNotice.Kept)
        }

        @Test
        fun `once halted, what the pack refused is left on the device for the next client to keep`() = rig(live = null) { r ->
            val client = r.start()
            r.server.finish("p-1", COLIN.id)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            client.halt()
            client.keepDropped()
            assertTrue(r.keeper.calls.isEmpty())
            assertEquals(listOf("op-1" to "pack_finished"), r.stored()!!.droppedIds())
        }

        @Test
        fun `or let go`() = rig(live = null) { r ->
            val client = r.start()
            r.server.finish("p-1", COLIN.id)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            client.discardDropped()
            assertTrue(r.stored()!!.dropped.isEmpty())
            client.stop()
            runCurrent()
            assertTrue(client.halted)
            assertTrue(r.keeper.calls.isEmpty())
        }

        @Test
        fun `a drain started for edits left on the device sends them without loading the pack, and keeps what the pack refused`() = rig(live = null) { r ->
            val first = r.start()
            r.server.offline = true
            first.edit(listOf(setHeading(90)))
            runCurrent()
            first.halt()
            r.server.offline = false
            r.server.finish("p-1", COLIN.id)
            r.server.calls.clear()

            val drain = r.start(open = false)
            assertEquals(listOf("ops p-1 base=3 [op-1]"), r.server.calls)
            assertTrue(drain.halted)
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
            assertTrue(r.notices.none { it is PackNotice.Dropped })              // not open: kept, not offered
            assertTrue(r.notices.single() is PackNotice.Kept)
            assertTrue(r.stored()!!.dropped.isEmpty())
            assertTrue(r.server.sockets.isEmpty())
        }

        @Test
        fun `what could not be kept stays on the device, and is kept once, under the same name, by the next client`() = rig(live = null) { r ->
            val first = r.start()
            r.server.finish("p-1", COLIN.id)
            first.edit(listOf(setHeading(90)))
            runCurrent()
            r.keeper.failNext = 1
            first.stop()
            runCurrent()
            assertTrue(first.halted)
            assertEquals(listOf("p-1"), r.store.withDropped(SAM.id))

            val next = r.start(open = false)
            assertTrue(next.halted)
            assertEquals(2, r.keeper.calls.size)
            assertEquals("p-1:lz-1:op-1", r.keeper.records.single().version.key)
            assertTrue(r.store.withDropped(SAM.id).isEmpty())
        }
    }

    @Nested
    inner class FollowingAndShowing {
        @Test
        fun `in the back, the stream is closed and nothing is asked, but edits still go`() = rig { r ->
            val client = r.start()
            r.welcome()
            client.foreground(false)
            runCurrent()
            assertEquals(1000, r.socket.closedWith)
            assertEquals(PackStatus.POLLING, client.status)
            r.advance(10_000)
            assertTrue(r.eventCalls().isEmpty())
            client.edit(listOf(setHeading(90)))
            runCurrent()
            assertEquals(1, r.opsCalls())
            assertTrue(r.stored()!!.pending.isEmpty())
            client.foreground(true)
            runCurrent()
            assertEquals(2, r.server.sockets.size)
            assertEquals(listOf("events p-1 since=4"), r.eventCalls())
        }

        @Test
        fun `a focus over 1 KB is not sent, and the next one is`() = rig { r ->
            val client = r.start()
            r.welcome()
            client.setFocus(JsonObject(mapOf("item" to JsonPrimitive("x".repeat(2_000)))))
            runCurrent()
            assertTrue(r.socket.presenceSent.isEmpty())
            client.setFocus(json("""{"item": "lz-1"}"""))
            runCurrent()
            r.advance(100)
            assertEquals(listOf(json("""{"item": "lz-1"}""")), r.socket.presenceSent)
        }

        @Test
        fun `halting writes nothing more, and gives nothing up`() = rig(live = null) { r ->
            val client = r.start()
            val answer = r.holdNext(Call.OPS)
            client.edit(listOf(setHeading(90)))
            runCurrent()
            val store = r.store as InMemoryPackStore
            val writes = store.writes
            client.halt()
            answer.complete(Unit)
            r.advance(60_000)
            assertEquals(writes, store.writes)
            assertEquals(listOf("op-1" to PendingState.SENT), r.stored()!!.pendingIds())
            assertEquals("closed", client.edit(listOf(setHeading(95))))
        }
    }
}
