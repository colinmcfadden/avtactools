package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.FakePackServer.Call
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The engine that runs every pack this device is working on, on virtual time, against [FakePackServer], the device's copy
 * ([InMemoryPackStore]) and a [RecordingKeeper] library: one client per pack, the open one and those still sending, the account it
 * runs for, keeping what packs refused, and the background drain.
 *
 * OP DK (p-1, LZ HAWK, landing heading 270) and OP EAGLE (p-2, LZ CROW, 180) are Colin's, with Sam an editor in both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackEngineTest {
    private companion object {
        val COLIN = PackUser(1, "Colin")
        val SAM = PackUser(2, "Sam")
        const val LIVE = "ws://live.test/live"

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun createLz(item: String, name: String, heading: Int): JsonObject =
            json("""{"type": "item.create", "item": "$item", "kind": "lz", "name": "$name", "data": {"flightData": {"landingHeading": $heading}}}""")

        // Each pack has one LZ/PZ: lz-1 in p-1, lz-2 in p-2.
        fun setHeading(pack: String, value: Int): JsonObject =
            json("""{"type": "set", "item": "lz-${pack.removePrefix("p-")}", "path": ["flightData", "landingHeading"], "value": $value}""")

        fun headingOf(data: JsonElement?): Int = ((data as JsonObject)["flightData"] as JsonObject)["landingHeading"]!!.jsonPrimitive.int
    }

    /** The device's copy, whose writes can be made to fail as a full disk fails them: each of [failing] picks the next write it fails. */
    private class FailingStore(val inner: InMemoryPackStore = InMemoryPackStore()) : PackStore by inner {
        val failing = ArrayDeque<(PackSession?, PackSession) -> Boolean>()

        override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
            if (failing.firstOrNull()?.invoke(before, after) == true) {
                failing.removeFirst()
                throw IOException("The device is full.")
            }
            inner.write(before, after, me)
        }
    }

    private class Rig(val test: TestScope, live: String?) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server = FakePackServer(live).apply {
            person(COLIN.id, COLIN.name)
            person(SAM.id, SAM.name)
            createPack("p-1", "OP DK", owner = COLIN.id, members = mapOf(SAM.id to "editor"))
            elsewhere("p-1", COLIN.id, createLz("lz-1", "LZ HAWK", 270))
            createPack("p-2", "OP EAGLE", owner = COLIN.id, members = mapOf(SAM.id to "editor"))
            elsewhere("p-2", COLIN.id, createLz("lz-2", "LZ CROW", 180))
        }
        val store = FailingStore()
        val keeper = RecordingKeeper()
        val notices = mutableListOf<PackNotice>()
        var backgroundSyncs = 0

        /** The device's clock, which the engine notes the time a pack was opened by. */
        var clock = 1_000L
        private var ids = 0

        /** Holds the library while it keeps something, until released. */
        var keeperGate: CompletableDeferred<Unit>? = null
        private val library = object : PackKeeper {
            override suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome> {
                keeperGate?.await()
                return keeper.keep(pack, versions)
            }
        }

        /** An engine as the app makes one (once per process), over the device's copy and library. */
        fun engine(): PackEngine {
            val engine = PackEngine(server, store, library, scope, dispatcher, { backgroundSyncs++ }, PackTiming(), { "op-${++ids}" }, { clock })
            scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.notices.collect { notices += it } }
            return engine
        }

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        /** Holds the next [call] in flight, before the server looks at it, until released. */
        fun holdNext(call: Call): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            var waiting = true
            server.onCall = {
                if (it == call && waiting) {
                    waiting = false
                    gate.await()
                }
            }
            return gate
        }

        suspend fun stored(pack: String, user: PackUser = SAM): PackSession? = store.load(pack, user.id)

        fun opsCalls(pack: String): Int = server.calls.count { it.startsWith("ops $pack ") }

        fun packCalls(pack: String): Int = server.calls.count { it == "pack $pack" }

        /**
         * Sam's device holds both packs, with [edits] (pack to operation, each its own edit) made there with no signal, and then the app
         * was ended. One engine makes them all: another's enable would send the first pack's while the next is being set up.
         */
        suspend fun waiting(vararg edits: Pair<String, JsonObject>) {
            val engine = engine()
            engine.enable(SAM)
            listOf("p-1", "p-2").forEach {
                engine.openPack(it)                                     // loaded, so the device has it
                test.runCurrent()
            }
            server.offline = true
            edits.forEach { (pack, op) ->
                engine.openPack(pack)
                test.runCurrent()
                assertNull(engine.edit(pack, listOf(op)))
                test.runCurrent()
            }
            engine.disable()
            server.offline = false
            clock += 1_000
            backgroundSyncs = 0
        }
    }

    private fun rig(live: String? = null, body: suspend TestScope.(Rig) -> Unit) = runTest {
        val rig = Rig(this, live)
        try {
            body(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    private fun PackSession.pendingIds() = pending.map { it.op["client_op_id"]!!.jsonPrimitive.content to it.state }

    private fun PackEngine.heading(): Int = headingOf(open.value!!.items.single().data)

    @Nested
    inner class TheOpenPack {
        @Test
        fun `is what the screens see, from the moment it is opened until it is closed`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            assertEquals("p-1", engine.open.value?.uuid)
            runCurrent()
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
            assertEquals(listOf("LZ HAWK"), engine.open.value!!.items.map { it.name })
            engine.closePack()
            assertNull(engine.open.value)
            runCurrent()
            assertNull(engine.open.value)
        }

        @Test
        fun `shows an edit the moment it is on the device, before anything else runs`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            assertEquals(90, engine.heading())                          // what an editor reads straight after its edit returns
        }

        @Test
        fun `takes edits only for itself, and only while someone is signed in for packs`() = rig { r ->
            val engine = r.engine()
            engine.openPack("p-1")
            assertNull(engine.open.value)                               // nobody signed in for packs: nothing opens
            assertEquals("closed", engine.edit("p-1", listOf(setHeading("p-1", 90))))
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            assertEquals("closed", engine.edit("p-2", listOf(setHeading("p-2", 90))))
            engine.closePack()
            assertEquals("closed", engine.edit("p-1", listOf(setHeading("p-1", 90))))
            assertEquals(0, r.opsCalls("p-1") + r.opsCalls("p-2"))
        }

        @Test
        fun `opening another closes it, and it sends what waits, then stops following the pack`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            val answer = r.holdNext(Call.OPS)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 95))))   // queued behind the batch in flight
            runCurrent()
            engine.openPack("p-2")
            runCurrent()
            assertEquals("p-2", engine.open.value?.uuid)
            assertEquals("closed", engine.edit("p-1", listOf(setHeading("p-1", 99))))
            answer.complete(Unit)
            runCurrent()
            assertEquals(95, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(r.stored("p-1")!!.pending.isEmpty())
            assertEquals("p-2", engine.open.value?.uuid)                // what p-1 did since never shows over it
            assertEquals(listOf("LZ CROW"), engine.open.value!!.items.map { it.name })
            r.server.calls.clear()
            r.advance(60_000)
            assertTrue(r.server.calls.none { it.contains("p-1") }, "p-1 is no longer asked about: ${r.server.calls}")
            assertTrue(r.server.calls.any { it.startsWith("events p-2") })  // p-2 is
        }

        @Test
        fun `opened again while it still sends is the same client, with one batch in flight, and the pack is not loaded again`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            val answer = r.holdNext(Call.OPS)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            engine.closePack()
            runCurrent()
            engine.openPack("p-1")
            runCurrent()
            assertEquals(90, engine.heading())
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 95))))   // behind the batch still in flight, not beside it
            runCurrent()
            assertEquals(1, r.server.batches.size)
            answer.complete(Unit)
            runCurrent()
            assertEquals(2, r.server.batches.size)
            assertEquals(95, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(1, r.packCalls("p-1"))
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
            assertEquals(95, engine.heading())
            r.server.calls.clear()
            r.advance(3_000)                                            // and followed again: closing stopped its polls
            assertTrue(r.server.calls.any { it.startsWith("events p-1") && it.endsWith("background") }, "${r.server.calls}")
        }

        @Test
        fun `opened again while it still sends, its stream is opened again`() = rig(live = LIVE) { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.sockets.single().welcome(r.server.headSeq("p-1"))
            runCurrent()
            assertEquals(PackStatus.LIVE, engine.open.value?.status)
            val answer = r.holdNext(Call.OPS)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            engine.closePack()
            runCurrent()
            assertFalse(r.server.sockets.single().open)                 // closing closed it
            engine.openPack("p-1")
            runCurrent()
            assertEquals(2, r.server.sockets.size)
            r.server.sockets.last().welcome(r.server.headSeq("p-1"))
            runCurrent()
            assertEquals(PackStatus.LIVE, engine.open.value?.status)
            answer.complete(Unit)
            runCurrent()
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(1, r.packCalls("p-1"))
        }

        @Test
        fun `a drain the app started is opened as the same client, which then loads the pack as every open one is`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            r.server.calls.clear()
            val answer = r.holdNext(Call.OPS)
            val engine = r.engine()
            engine.enable(SAM)                                          // a drain starts for p-1, and its batch goes
            runCurrent()
            assertEquals(listOf("ops p-1 base=3 [op-1]"), r.server.calls)
            engine.openPack("p-1")
            runCurrent()
            assertEquals(1, r.packCalls("p-1"))                         // a drain never takes the pack; opened, it does
            assertEquals(90, engine.heading())
            answer.complete(Unit)
            runCurrent()
            assertEquals(1, r.server.batches.size)                      // never sent twice
            assertTrue(r.stored("p-1")!!.pending.isEmpty())
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
        }

        @Test
        fun `opened again while it is still keeping what the pack refused, it is opened afresh once that is done`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.finish("p-1", COLIN.id)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()                                                // refused: it waits on the device while the pack is open
            r.keeperGate = CompletableDeferred()
            engine.closePack()
            runCurrent()                                                // nothing left to send: keeping it, and the library is slow
            val opening = async { engine.openPack("p-1") }
            runCurrent()
            assertFalse(opening.isCompleted)                            // one client per pack: the next waits for this one to finish
            r.keeperGate!!.complete(Unit)
            opening.await()
            runCurrent()
            assertEquals(1, r.keeper.records.size)
            assertTrue(r.stored("p-1")!!.dropped.isEmpty())
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
            assertTrue(engine.open.value!!.readOnly)
            assertEquals(2, r.packCalls("p-1"))                         // loaded afresh
        }

        @Test
        fun `paused for the account's sake, it is asked again when it is opened again`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.setFeature(SAM.id, false)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            assertEquals(PackStatus.PAUSED, engine.open.value?.status)
            assertEquals("paused", engine.edit("p-1", listOf(setHeading("p-1", 95))))
            r.server.setFeature(SAM.id, true)
            engine.openPack("p-1")
            runCurrent()
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(r.stored("p-1")!!.pending.isEmpty())
        }

        @Test
        fun `in the back it is not followed, but edits still go, and a pack opened there starts in the back`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.foreground(false)
            engine.openPack("p-1")
            runCurrent()
            r.advance(10_000)
            assertTrue(r.server.calls.none { it.startsWith("events p-1") })
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            engine.foreground(true)
            runCurrent()
            assertTrue(r.server.calls.any { it.startsWith("events p-1") })
        }
    }

    @Nested
    inner class WhatAPackRefused {
        @Test
        fun `waits on the device while the pack is open, and is kept once it is closed, with a sync asked for the library's new record`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.finish("p-1", COLIN.id)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            assertTrue(r.notices.any { it is PackNotice.Dropped })
            assertTrue(r.keeper.records.isEmpty())
            assertEquals(0, r.backgroundSyncs)
            engine.closePack()
            runCurrent()
            val kept = r.keeper.records.single().version
            assertEquals(KeptVersion("p-1:lz-1:op-1", "lz", "LZ HAWK (my edits)", kept.data), kept)
            assertEquals(90, headingOf(kept.data))
            assertTrue(r.notices.last() is PackNotice.Kept)
            assertEquals(1, r.backgroundSyncs)
            assertTrue(r.stored("p-1")!!.dropped.isEmpty())
        }

        @Test
        fun `can be kept or let go while the pack is open, and only for the open pack`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.finish("p-1", COLIN.id)
            engine.edit("p-1", listOf(setHeading("p-1", 90)))
            runCurrent()
            engine.keepDropped("p-2")
            engine.discardDropped("p-2")
            assertEquals(1, r.stored("p-1")!!.dropped.size)
            engine.keepDropped("p-1")
            assertEquals(90, headingOf(r.keeper.records.single().version.data))
            assertTrue(r.stored("p-1")!!.dropped.isEmpty())

            r.server.reopen("p-1", COLIN.id)
            r.advance(3_000)                                            // the poll hears it is open again
            assertFalse(engine.open.value!!.readOnly)
            r.server.finish("p-1", COLIN.id)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 95))))
            runCurrent()
            assertEquals(1, r.stored("p-1")!!.dropped.size)
            engine.discardDropped("p-1")
            assertTrue(r.stored("p-1")!!.dropped.isEmpty())
            engine.closePack()
            runCurrent()
            assertEquals(1, r.keeper.records.size)                      // let go is let go: nothing kept at the close
        }

        @Test
        fun `left on the device when the app ended with the pack open is kept at the next enable, once, under one name, whatever fails on the way`() = rig { r ->
            val first = r.engine()
            first.enable(SAM)
            first.openPack("p-1")
            runCurrent()
            r.server.finish("p-1", COLIN.id)
            first.edit("p-1", listOf(setHeading("p-1", 90)))
            runCurrent()
            first.disable()                                             // ended with the pack open: still on the device
            assertEquals(listOf("p-1"), r.store.withDropped(SAM.id))

            r.keeper.failNext = 1                                       // the library cannot be written
            val second = r.engine()
            second.enable(SAM)
            runCurrent()
            assertTrue(r.keeper.records.isEmpty())
            assertEquals(listOf("p-1"), r.store.withDropped(SAM.id))
            second.disable()

            // Kept, then the app is ended before the device lets the edits go: kept again under the same key, one record.
            r.store.failing += { before, after -> before?.dropped?.isNotEmpty() == true && after.dropped.isEmpty() }
            val third = r.engine()
            third.enable(SAM)
            runCurrent()
            assertEquals(1, r.keeper.records.size)
            assertEquals(listOf("p-1"), r.store.withDropped(SAM.id))
            third.disable()

            val fourth = r.engine()
            fourth.enable(SAM)
            runCurrent()
            assertEquals(3, r.keeper.calls.size)                        // failed, kept, kept again: one record
            val kept = r.keeper.records.single().version
            assertEquals("p-1:lz-1:op-1", kept.key)
            assertEquals("LZ HAWK (my edits)", kept.name)
            assertTrue(r.store.withDropped(SAM.id).isEmpty())
            assertTrue(r.notices.any { it is PackNotice.Kept })
        }
    }

    @Nested
    inner class WhoItIsFor {
        @Test
        fun `disabled, it stops at once and gives nothing up, and only the same account's next enable sends what waits`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.offline = true
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            engine.disable()
            assertNull(engine.open.value)
            assertNull(engine.me.value)
            assertEquals(0, engine.unsentCount())
            r.server.offline = false
            r.advance(60_000)
            assertEquals(0, r.opsCalls("p-1"))
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored("p-1")!!.pendingIds())

            engine.enable(COLIN)                                        // someone else signs in here
            runCurrent()
            assertEquals(0, engine.unsentCount())
            r.advance(60_000)
            assertEquals(0, r.opsCalls("p-1"))
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored("p-1")!!.pendingIds())

            engine.enable(SAM)                                          // Sam again: what waits goes
            assertEquals(SAM, engine.me.value)
            runCurrent()
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(r.stored("p-1")!!.pending.isEmpty())
            assertEquals(0, engine.unsentCount())
        }

        @Test
        fun `enabled for another account, what the first was doing stops, and stays theirs`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            val held = r.holdNext(Call.OPS)                             // Sam's batch out, held before the server looks at it
            engine.edit("p-1", listOf(setHeading("p-1", 90)))
            runCurrent()
            assertEquals(1, engine.unsentCount())
            engine.enable(COLIN)
            runCurrent()
            assertNull(engine.open.value)
            assertEquals(COLIN, engine.me.value)
            held.complete(Unit)                                         // the call went with Sam's client: it changes nothing
            r.server.calls.clear()
            r.advance(60_000)
            assertTrue(r.server.calls.isEmpty(), "nothing more is asked or sent for Sam: ${r.server.calls}")
            assertEquals(270, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(listOf("op-1" to PendingState.SENT), r.stored("p-1")!!.pendingIds())
            assertNull(r.store.load("p-1", COLIN.id))
        }

        @Test
        fun `enabled again for the same account, a pack paused for its sake is asked again`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            r.server.setFeature(SAM.id, false)
            engine.edit("p-1", listOf(setHeading("p-1", 90)))
            runCurrent()
            assertEquals(PackStatus.PAUSED, engine.open.value?.status)
            r.server.setFeature(SAM.id, true)
            engine.enable(SAM)
            runCurrent()
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
            assertEquals("p-1", engine.open.value?.uuid)
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }

        @Test
        fun `enabled, it drains every pack with edits waiting on the device, one client each`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90), "p-1" to setHeading("p-1", 95), "p-2" to setHeading("p-2", 45))
            r.server.calls.clear()
            val engine = r.engine()
            engine.enable(SAM)
            runCurrent()
            assertEquals(listOf("ops p-1 base=3 [op-1, op-2]", "ops p-2 base=3 [op-3]"), r.server.calls.sorted())
            assertEquals(95, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(45, headingOf(r.server.data("p-2", "lz-2")))
            assertTrue(r.server.sockets.isEmpty())
            assertNull(engine.open.value)
        }

        @Test
        fun `enabled with nothing running, it forgets the packs opened longest ago that hold nothing of the person's`() = rig { r ->
            (1..12).forEach { n ->
                val body = json("""{"uuid": "q-$n", "name": "Q $n", "status": "active", "role": "editor", "head_seq": 0, "members": [], "items": []}""")
                val opened = PackSessions.open(body, SAM.id)
                val session = if (n == 1) PackSessions.edit(opened, listOf(createLz("lz-9", "LZ OWL", 1))) { "q-op" }.session else opened
                r.store.write(null, session, SAM.id)
                r.store.touch("q-$n", n * 1_000L)
            }
            r.server.offline = true                                     // q-1's edit is not sent, so it still holds it
            val engine = r.engine()
            engine.enable(SAM)
            runCurrent()
            assertNotNull(r.stored("q-1"))                              // among the oldest, but it holds an edit
            assertNull(r.stored("q-2"))
            (3..12).forEach { assertNotNull(r.stored("q-$it"), "q-$it") }
        }

        // A pack opened for the first time is on the device only once its first load is written, a round trip after openPack returns:
        // noted as opened before that, it would keep no time at all, and pruning would forget the newest packs first.
        @Test
        fun `notes a pack as opened once it is on the device, so pruning forgets the one opened longest ago`() = rig { r ->
            (3..11).forEach { n -> r.server.createPack("p-$n", "OP $n", owner = COLIN.id, members = mapOf(SAM.id to "editor")) }
            val engine = r.engine()
            engine.enable(SAM)
            (1..11).forEach { n ->
                r.clock += 1_000
                val loading = r.holdNext(Call.PACK)                     // the pack comes a moment later, as over a network
                engine.openPack("p-$n")                                 // each a first time, closing the one before
                runCurrent()
                loading.complete(Unit)
                runCurrent()
            }
            engine.disable()
            r.engine().enable(SAM)                                      // nothing running: the ten opened last are kept
            runCurrent()
            assertEquals((2..11).map { "p-$it" }, (1..11).map { "p-$it" }.filter { r.stored(it) != null })
        }

        @Test
        fun `notes the open pack as opened again when it is opened again`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")                                      // opened at 1 s
            runCurrent()
            (1..10).forEach { n ->                                      // ten more on the device, each opened since
                val body = json("""{"uuid": "q-$n", "name": "Q $n", "status": "active", "role": "editor", "head_seq": 0, "members": [], "items": []}""")
                r.store.write(null, PackSessions.open(body, SAM.id), SAM.id)
                r.store.touch("q-$n", 1_000L + n)
            }
            r.clock = 2_000
            engine.openPack("p-1")                                      // still open, opened again: the latest of them all
            runCurrent()
            engine.disable()
            r.engine().enable(SAM)
            runCurrent()
            assertNotNull(r.stored("p-1"))
            assertNull(r.stored("q-1"))
            (2..10).forEach { assertNotNull(r.stored("q-$it"), "q-$it") }
        }

        // A client writes only what changed (Room's store rewrites a row only when its instance is new), so a pack pruned under one
        // would come back in part. Pruning waits for an enable with nothing running.
        @Test
        fun `enabled again while a pack is open, it forgets nothing a client holds`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            (1..10).forEach { n ->
                val body = json("""{"uuid": "q-$n", "name": "Q $n", "status": "active", "role": "editor", "head_seq": 0, "members": [], "items": []}""")
                r.store.write(null, PackSessions.open(body, SAM.id), SAM.id)
                r.store.touch("q-$n", 1_000_000L + n)                  // each opened since p-1 was
            }
            engine.enable(SAM)
            runCurrent()
            assertNotNull(r.stored("p-1"))
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }
    }

    @Nested
    inner class WhatTheAppSays {
        @Test
        fun `the connection back reaches a pack still sending, not only the open one`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            r.server.offline = true
            val engine = r.engine()
            engine.enable(SAM)                                          // p-1's edit goes with a drain, which cannot get through
            runCurrent()
            r.advance(1_000)
            r.advance(2_000)                                            // tried again at 1 and 3 s: the next try is at 8 s
            r.server.offline = false
            engine.wake()
            runCurrent()
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))  // at once
        }

        @Test
        fun `coming to the front reaches a pack still sending, which is followed once it is opened again`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            val answer = r.holdNext(Call.OPS)
            val engine = r.engine()
            engine.foreground(false)
            engine.enable(SAM)                                          // in the back: p-1's drain starts there, its batch held in flight
            runCurrent()
            engine.foreground(true)
            runCurrent()
            engine.openPack("p-1")
            runCurrent()
            answer.complete(Unit)
            runCurrent()
            r.server.calls.clear()
            r.advance(3_000)
            assertTrue(r.server.calls.any { it.startsWith("events p-1") && it.endsWith("background") }, "${r.server.calls}")
        }
    }

    @Nested
    inner class TheBackgroundDrain {
        @Test
        fun `sends what waits in every pack, keeps what a pack refused, never opens a stream, and says DONE`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90), "p-2" to setHeading("p-2", 45))
            r.server.finish("p-2", COLIN.id)
            r.server.calls.clear()
            val engine = r.engine()                                     // a process the app did not start: nobody enabled
            assertEquals(DrainOutcome.DONE, engine.drainAll(SAM))
            assertEquals(listOf("ops p-1 base=3 [op-1]", "ops p-2 base=3 [op-2]"), r.server.calls.sorted())  // nothing loaded, nothing polled
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(180, headingOf(r.server.data("p-2", "lz-2")))
            val kept = r.keeper.records.single().version
            assertEquals("LZ CROW (my edits)", kept.name)
            assertEquals(45, headingOf(kept.data))
            assertTrue(r.store.owed(SAM.id).isEmpty())
            assertTrue(r.store.withDropped(SAM.id).isEmpty())
            assertTrue(r.server.sockets.isEmpty())
            assertNull(engine.me.value)
        }

        @Test
        fun `leaves the open pack to its own client`() = rig { r ->
            val engine = r.engine()
            engine.enable(SAM)
            engine.openPack("p-1")
            runCurrent()
            val answer = r.holdNext(Call.OPS)
            engine.edit("p-1", listOf(setHeading("p-1", 90)))
            runCurrent()
            val before = currentTime
            assertEquals(DrainOutcome.DONE, engine.drainAll(SAM))      // not waited for: the open client sends it
            assertEquals(before, currentTime, "the drain waited for the open pack")
            answer.complete(Unit)
            runCurrent()
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(PackStatus.POLLING, engine.open.value?.status)
        }

        @Test
        fun `waits for what it found sending, and for a pack closed while it runs`() = rig { r ->
            r.waiting("p-2" to setHeading("p-2", 45))
            val p2 = r.holdNext(Call.OPS)
            val engine = r.engine()
            engine.enable(SAM)                                          // p-2's drain starts, and its batch is held in flight
            runCurrent()
            engine.openPack("p-1")
            runCurrent()
            val p1 = r.holdNext(Call.OPS)
            assertNull(engine.edit("p-1", listOf(setHeading("p-1", 90))))   // the open client's batch, held in flight too
            runCurrent()
            val drain = async { engine.drainAll(SAM) }
            runCurrent()
            assertFalse(drain.isCompleted)                              // p-2 has had no answer
            engine.closePack()                                          // p-1 drains now too, and the drain waits for it
            runCurrent()
            p2.complete(Unit)
            runCurrent()
            assertEquals(45, headingOf(r.server.data("p-2", "lz-2")))
            assertFalse(drain.isCompleted)                              // p-2 is done; p-1 has had no answer
            p1.complete(Unit)
            runCurrent()
            assertEquals(DrainOutcome.DONE, drain.await())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(r.store.owed(SAM.id).isEmpty())
        }

        @Test
        fun `says RETRY when what a pack refused could not be kept, and keeps it at the next try`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            r.server.finish("p-1", COLIN.id)
            r.keeper.failNext = 1
            val engine = r.engine()
            assertEquals(DrainOutcome.RETRY, engine.drainAll(SAM))
            assertEquals(listOf("p-1"), r.store.withDropped(SAM.id))
            assertTrue(r.keeper.records.isEmpty())
            assertEquals(DrainOutcome.DONE, engine.drainAll(SAM))
            assertEquals("LZ HAWK (my edits)", r.keeper.records.single().version.name)
            assertTrue(r.store.withDropped(SAM.id).isEmpty())
        }

        // The design's rule: a try that cannot get through ends that pack's part with RETRY, and the next try is the sync's. Waiting out
        // the client's own pauses (1, 2, 5, 10, 30 s) would hold the background run for its whole minute, and the library's sync after it.
        @Test
        fun `says RETRY at the first try that cannot reach the server, and leaves everything on the device, its clients stopped`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            r.server.offline = true
            r.server.attempts.clear()
            val engine = r.engine()
            val before = currentTime
            assertEquals(DrainOutcome.RETRY, engine.drainAll(SAM))
            assertEquals(before, currentTime, "the drain waited for a pack that cannot get through")
            assertEquals(listOf("ops p-1 base=3 [op-1]"), r.server.attempts)
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored("p-1")!!.pendingIds())
            r.advance(60_000)
            assertEquals(1, r.server.attempts.size)                     // nobody follows them after the drain
            assertEquals(1, r.backgroundSyncs)                          // and the sync is asked to come back
        }

        @Test
        fun `says RETRY at once when the server is busy or down, and the next try sends it`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90), "p-2" to setHeading("p-2", 45))
            r.server.failNext(Call.OPS, r.server.refusal(503, "unavailable"))
            val engine = r.engine()
            val before = currentTime
            assertEquals(DrainOutcome.RETRY, engine.drainAll(SAM))      // one pack sent, the other met the server down
            assertEquals(before, currentTime)
            assertEquals(1, r.store.owed(SAM.id).size)
            assertEquals(DrainOutcome.DONE, engine.drainAll(SAM))
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertEquals(45, headingOf(r.server.data("p-2", "lz-2")))
        }

        @Test
        fun `waits for a pack whose next try is on its way, which may get through`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            r.server.offline = true
            val engine = r.engine()
            engine.enable(SAM)                                          // p-1's drain cannot get through, and tries again in 1 s
            runCurrent()
            r.server.offline = false
            val held = r.holdNext(Call.OPS)
            r.advance(1_000)                                            // tried again, and held in flight
            val drain = async { engine.drainAll(SAM) }
            runCurrent()
            assertFalse(drain.isCompleted)
            held.complete(Unit)
            runCurrent()
            assertEquals(DrainOutcome.DONE, drain.await())
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }

        @Test
        fun `says RETRY at once when the catch-up a batch out needs first cannot reach the server`() = rig { r ->
            val first = r.engine()
            first.enable(SAM)
            first.openPack("p-1")
            runCurrent()
            r.holdNext(Call.OPS)
            assertNull(first.edit("p-1", listOf(setHeading("p-1", 90))))
            runCurrent()
            first.disable()                                             // ended with the batch out: on the device as sent
            r.server.offline = true
            val engine = r.engine()
            val before = currentTime
            assertEquals(DrainOutcome.RETRY, engine.drainAll(SAM))
            assertEquals(before, currentTime)
            assertEquals(listOf("op-1" to PendingState.SENT), r.stored("p-1")!!.pendingIds())    // to be confirmed or sent again, later
            r.server.offline = false
            assertEquals(DrainOutcome.DONE, engine.drainAll(SAM))
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
            assertTrue(r.stored("p-1")!!.pending.isEmpty())
        }

        @Test
        fun `says PAUSED when only the account can let what waits go, and drops nothing`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            r.server.setFeature(SAM.id, false)
            val engine = r.engine()
            assertEquals(DrainOutcome.PAUSED, engine.drainAll(SAM))
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored("p-1")!!.pendingIds())
            assertTrue(r.stored("p-1")!!.dropped.isEmpty())
            assertTrue(r.keeper.calls.isEmpty())
        }

        @Test
        fun `with someone else signed in, it sends nothing of the person's`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            val engine = r.engine()
            engine.enable(COLIN)
            runCurrent()
            r.server.calls.clear()
            assertEquals(DrainOutcome.PAUSED, engine.drainAll(SAM))
            assertTrue(r.server.calls.isEmpty())
            assertEquals(listOf("op-1" to PendingState.QUEUED), r.stored("p-1")!!.pendingIds())
        }

        @Test
        fun `a store that cannot be written leaves a batch to the next try, never lost`() = rig { r ->
            r.waiting("p-1" to setHeading("p-1", 90))
            // Taking the batch out fails, as a full disk would: nothing is sent until it is written.
            r.store.failing += { before, after -> before?.pending?.any { it.state == PendingState.QUEUED } == true && after.pending.any { it.state == PendingState.SENT } }
            val engine = r.engine()
            val before = currentTime
            assertEquals(DrainOutcome.RETRY, engine.drainAll(SAM))
            assertEquals(before, currentTime)
            assertEquals(0, r.opsCalls("p-1"))
            assertEquals(DrainOutcome.DONE, engine.drainAll(SAM))
            assertEquals(90, headingOf(r.server.data("p-1", "lz-1")))
        }
    }
}
