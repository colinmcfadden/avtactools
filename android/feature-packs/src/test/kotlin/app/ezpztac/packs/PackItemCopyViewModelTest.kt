package app.ezpztac.packs

import app.ezpztac.data.CopyIn
import app.ezpztac.data.LibraryCopier
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PendingOp
import app.ezpztac.missionpacks.PendingState
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackChange
import app.ezpztac.network.PackItemSource
import app.ezpztac.network.PackPerson
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A copy updated from its Library original, and a pack's item saved to the Library: the pack open, one at a time, the account asked for. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackItemCopyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val me = 6

    private fun item(uuid: String = "lz-1", name: String = "LZ HAWK", kind: String = "lz", copied: Boolean = true, data: JsonElement = buildJsonObject { put("name", JsonPrimitive(name)) }) =
        PackItemView(
            uuid, kind, name, data,
            info = buildJsonObject {
                if (copied) put("source", buildJsonObject { put("kind", JsonPrimitive(kind)); put("uuid", JsonPrimitive("orig-1")); put("original", JsonPrimitive("changed")) })
            },
            pendingCreate = false,
        )

    private fun dk(items: List<PackItemView> = listOf(item()), pending: Int = 0, readOnly: Boolean = false, uuid: String = "dk", name: String = "OP DK"): PackState {
        val pack = buildJsonObject { put("uuid", JsonPrimitive(uuid)); put("name", JsonPrimitive(name)) }
        val ops = List(pending) { PendingOp(buildJsonObject { put("client_op_id", JsonPrimitive("op-$it")) }, PendingState.SENT) }
        val session = PackSession(me, pack, JsonArray(emptyList()), emptyMap(), emptyMap(), emptyList(), 10, ops, emptyList(), readOnly, null, false, emptyMap())
        return PackState(uuid, PackStatus.LIVE, session, items, emptyList(), null)
    }

    // One log for all three fakes, so the order of what each did is held too.
    private class FakeDoor(user: Int?, val log: MutableList<String>) : PackDoor {
        override val user = MutableStateFlow(user)
        override val open = MutableStateFlow<PackState?>(null)
        var flushFails = false

        /** What the editors show of an item, once a flush has written it: null for the pack's own. */
        var seen: JsonElement? = null
        var onFlush: () -> Unit = {}
        override suspend fun openPack(uuid: String) = Unit
        override suspend fun closePack() = Unit
        override fun refresh() {
            log += "refresh"
        }
        override suspend fun flushOpen() {
            log += "flush"
            if (flushFails) error("disk full")
            onFlush()
        }
        override suspend fun seenData(item: String): JsonElement? {
            log += "seen $item"
            return open.value?.item(item)?.let { seen ?: it.data }
        }
    }

    private class FakeCalls(val log: MutableList<String>) : PackItemCalls {
        var source: PackItemSource? = PackItemSource("lz", "orig-1", 1, "changed", "2026-10-03T09:12:00", 3, PackChange(PackPerson(8, "Jess Reyes"), "Jess Reyes set the landing heading of LZ HAWK to 270°.", "x"))
        var sourceFails = false

        /** Answers held for the next asks, in turn (completed exceptionally for a failure); none: answered at once. */
        val sourceAnswers = ArrayDeque<CompletableDeferred<PackItemSource?>>()
        var updateFails: Exception? = null
        var updateHold: CompletableDeferred<Unit>? = null
        var saveHold: CompletableDeferred<Unit>? = null
        var kept: KeptOutcome? = null
        override suspend fun source(pack: String, item: String, user: Int): PackItemSource? {
            log += "source $item as $user"
            sourceAnswers.removeFirstOrNull()?.let { return it.await() }
            if (sourceFails) error("no")
            return source
        }
        override suspend fun updateFromOriginal(pack: String, item: String, user: Int) {
            log += "update $item as $user"
            updateHold?.await()
            updateFails?.let { throw it }
        }
        override suspend fun saveToLibrary(pack: String, kind: String, name: String, data: JsonElement): KeptOutcome {
            log += "save $kind $name $data"
            saveHold?.await()
            return kept ?: KeptOutcome.Saved("k", kind, "lib-1", name)
        }
    }

    private class FakeCopier(val log: MutableList<String>) : LibraryCopier {
        var ready: CopyIn? = null
        var readyFails = false
        override suspend fun copyIn(pack: String, kind: RecordKind, uuid: String, item: String, user: Int): CopyIn = error("not here")
        override suspend fun readyOriginal(kind: RecordKind, uuid: String, user: Int): CopyIn? {
            log += "ready $kind $uuid as $user"
            if (readyFails) error("database is locked")
            return ready
        }
    }

    private inner class Rig(scope: TestScope, user: Int? = me) {
        val log = mutableListOf<String>()
        val door = FakeDoor(user, log).also { it.open.value = dk() }
        val calls = FakeCalls(log)
        val copier = FakeCopier(log)
        val model = PackItemCopyViewModel(door, calls, copier).also { it.settleMillis = 1_000 }
        val notices = mutableListOf<CopyNotice>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
        }

        fun updates() = log.count { it.startsWith("update") }
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    private fun TestScope.asked(r: Rig) {
        r.model.askUpdate("lz-1")
        advanceUntilIdle()
        r.log.clear()
    }

    // -- Update from the original -----------------------------------------------------------------------------------------

    @Test
    fun `an update asks the server what it replaces, then goes once the person's changes and the original's are in`() = runTest(dispatcher) {
        val r = rig()
        r.model.askUpdate("lz-1")
        assertTrue(r.model.update.value!!.loading)
        advanceUntilIdle()
        val asked = r.model.update.value!!
        assertFalse(asked.loading)
        assertEquals(3, asked.source!!.packChanges)
        assertEquals(me, asked.me)                                                                   // so the person's own last change is "your last"
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertEquals(listOf("source lz-1 as $me", "flush", "ready LZ orig-1 as $me", "update lz-1 as $me", "refresh"), r.log)
        assertNull(r.model.update.value)
        assertEquals("Updated “LZ HAWK” from your Library", r.notices.single().words)
    }

    @Test
    fun `an item made in the pack, or one no longer there, is not asked about`() = runTest(dispatcher) {
        val r = rig()
        r.door.open.value = dk(listOf(item(copied = false)))
        r.model.askUpdate("lz-1")
        r.model.askUpdate("lz-9")
        assertNull(r.model.update.value)
    }

    @Test
    fun `what the update replaces that could not be had leaves the plain warning`() = runTest(dispatcher) {
        val r = rig()
        r.calls.sourceFails = true
        r.model.askUpdate("lz-1")
        advanceUntilIdle()
        assertTrue(r.model.update.value!!.failed)
        assertFalse(r.model.update.value!!.loading)
    }

    @Test
    fun `an earlier ask's answer never lands on a later one for the same item`() = runTest(dispatcher) {
        val r = rig()
        val first = CompletableDeferred<PackItemSource?>()
        val second = CompletableDeferred<PackItemSource?>()
        val third = CompletableDeferred<PackItemSource?>()
        r.calls.sourceAnswers += listOf(first, second, third)
        r.model.askUpdate("lz-1")
        advanceUntilIdle()
        r.model.dismissUpdate()
        r.model.askUpdate("lz-1")
        advanceUntilIdle()
        first.complete(r.calls.source!!.copy(packChanges = 9))                                       // late, and an answer to the first ask only
        advanceUntilIdle()
        assertTrue(r.model.update.value!!.loading)
        assertNull(r.model.update.value!!.source)
        r.model.dismissUpdate()
        r.model.askUpdate("lz-1")
        advanceUntilIdle()
        second.completeExceptionally(IllegalStateException("no"))
        advanceUntilIdle()
        assertTrue(r.model.update.value!!.loading)
        assertFalse(r.model.update.value!!.failed)
        third.complete(r.calls.source)
        advanceUntilIdle()
        assertEquals(3, r.model.update.value!!.source!!.packChanges)
        assertFalse(r.model.update.value!!.failed)
    }

    @Test
    fun `an update waits for the person's changes to reach the pack, then goes`() = runTest(dispatcher) {
        val r = rig()
        r.door.open.value = dk(pending = 1)
        asked(r)
        r.model.confirmUpdate()
        advanceTimeBy(500)
        assertEquals(0, r.updates())
        r.door.open.value = dk()
        advanceUntilIdle()
        assertEquals(1, r.updates())
        assertNull(r.model.update.value)
    }

    @Test
    fun `an update does not go while the person's changes are still on their way, nor after a save that failed`() = runTest(dispatcher) {
        val r = rig()
        r.door.open.value = dk(pending = 1)
        asked(r)
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertEquals("Your last changes are still on their way to OP DK. Update once they are sent.", r.model.update.value!!.error)
        assertEquals(0, r.updates())
        r.door.flushFails = true
        r.door.open.value = dk()
        r.log.clear()
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertEquals("The last change to it could not be saved, so it was not updated.", r.model.update.value!!.error)
        assertEquals(listOf("flush"), r.log)                                                         // neither the original nor the update
    }

    @Test
    fun `an original not ready says why in the update's own words, and nothing is sent`() = runTest(dispatcher) {
        val cases = listOf(
            CopyIn.NotUploaded(StopReason.Offline, ran = true) to PackCopyViewModel.NOT_UP_OFFLINE,
            CopyIn.Conflicted to PackItemCopyViewModel.CONFLICTED,
            CopyIn.Refused to PackItemCopyViewModel.REFUSED,
            CopyIn.Unsaved to PackItemCopyViewModel.NOT_SAVED,
        )
        val r = rig()
        asked(r)
        for ((outcome, words) in cases) {
            r.copier.ready = outcome
            r.model.confirmUpdate()
            advanceUntilIdle()
            assertEquals(words, r.model.update.value!!.error)
            assertFalse(words, words.contains(" add"))
        }
        assertEquals(0, r.updates())
    }

    @Test
    fun `a store that fails while the original is made ready is said, never the app's end`() = runTest(dispatcher) {
        val r = rig()
        asked(r)
        r.copier.readyFails = true
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertEquals(PackItemCopyViewModel.NOT_SENT, r.model.update.value!!.error)
        assertFalse(r.model.update.value!!.sending)
        assertEquals(0, r.updates())
    }

    @Test
    fun `a refusal is said in the dialog, and an answer lost, or lost behind the proxy, may have updated it`() = runTest(dispatcher) {
        val r = rig()
        asked(r)
        r.calls.updateFails = ApiException(409, "original_gone", "x")
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertEquals("The original is no longer in your Library.", r.model.update.value!!.error)
        assertFalse(r.log.contains("refresh"))
        for (lost in listOf(NetworkException("lost", requestMayHaveBeenSent = true), ApiException(502, null, "x"), ApiException(504, null, "x"), ApiException(524, null, "x"))) {
            r.log.clear()
            r.calls.updateFails = lost
            r.model.confirmUpdate()
            advanceUntilIdle()
            assertEquals(lost.toString(), PackItemCopyViewModel.LOST_UPDATE, r.model.update.value!!.error)
            assertTrue(r.log.contains("refresh"))
        }
        r.calls.updateFails = ApiException(500, null, "x")
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertNotEquals(PackItemCopyViewModel.LOST_UPDATE, r.model.update.value!!.error)
        r.calls.updateFails = ApiException(200, "unreadable_response", "x")
        r.model.confirmUpdate()
        advanceUntilIdle()
        assertNull(r.model.update.value)
    }

    @Test
    fun `while an update is sent it cannot be put away or sent twice, and another pack opened puts an idle one away`() = runTest(dispatcher) {
        val r = rig()
        asked(r)
        r.calls.updateHold = CompletableDeferred()
        r.model.confirmUpdate()
        advanceUntilIdle()
        r.model.confirmUpdate()
        r.model.dismissUpdate()
        assertTrue(r.model.update.value!!.sending)
        r.calls.updateHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, r.updates())
        r.model.askUpdate("lz-1")
        r.door.open.value = null
        advanceUntilIdle()
        assertNull(r.model.update.value)
    }

    @Test
    fun `an update on its way keeps its dialog when the pack changes, and what it came to is then said in a toast`() = runTest(dispatcher) {
        val r = rig()
        asked(r)
        r.calls.updateHold = CompletableDeferred()
        r.calls.updateFails = ApiException(404, "pack_not_found", "x")
        r.model.confirmUpdate()
        advanceUntilIdle()
        r.door.open.value = dk(uuid = "rv", name = "OP RAVEN")
        advanceUntilIdle()
        assertTrue(r.model.update.value!!.sending)
        r.calls.updateHold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(r.model.update.value)
        assertTrue(r.notices.single().words, r.notices.single().words.startsWith("“LZ HAWK” was not updated. "))
    }

    @Test
    fun `a refusal stays in sight while the pack changes only who is here`() = runTest(dispatcher) {
        val r = rig()
        asked(r)
        r.calls.updateFails = ApiException(409, "original_gone", "x")
        r.model.confirmUpdate()
        advanceUntilIdle()
        r.door.open.value = dk()                                                                     // a presence frame: the same pack, a new state
        advanceUntilIdle()
        assertEquals("The original is no longer in your Library.", r.model.update.value!!.error)
        assertTrue(r.notices.isEmpty())
    }

    @Test
    fun `another account ends an update on its way, puts its dialog away, and is told nothing`() = runTest(dispatcher) {
        val r = rig()
        asked(r)
        r.calls.updateHold = CompletableDeferred()
        r.model.confirmUpdate()
        advanceUntilIdle()
        r.door.user.value = 8
        advanceUntilIdle()
        assertNull(r.model.update.value)
        r.calls.updateHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.notices.isEmpty())
    }

    // -- Save a copy to the Library -----------------------------------------------------------------------------------------

    @Test
    fun `a copy is saved to the Library as the person sees it once their last change is written, named NAME copy to start with`() = runTest(dispatcher) {
        val r = rig()
        r.door.onFlush = { r.door.seen = buildJsonObject { put("name", JsonPrimitive("LZ HAWK")); put("edited", JsonPrimitive(true)) } }
        r.model.askSave("lz-1")
        assertEquals("LZ HAWK copy", r.model.save.value!!.name)
        r.model.saveName("  LZ HAWK (MINE) ")
        r.model.confirmSave()
        advanceUntilIdle()
        assertEquals(listOf("flush", "seen lz-1", "save lz LZ HAWK (MINE) {\"name\":\"LZ HAWK\",\"edited\":true}"), r.log)
        assertNull(r.model.save.value)
        assertEquals(CopyNotice("Saved “LZ HAWK (MINE)” to your Library", library = "lz"), r.notices.single())
    }

    @Test
    fun `no copy is made when the last change could not be written`() = runTest(dispatcher) {
        val r = rig()
        r.door.flushFails = true
        r.model.askSave("lz-1")
        r.model.confirmSave()
        advanceUntilIdle()
        assertEquals("The last change to it could not be saved, so no copy was made.", r.model.save.value!!.error)
        assertEquals(listOf("flush"), r.log)
    }

    @Test
    fun `a name that will not do is said at the field, and a point set with no points is said in the dialog`() = runTest(dispatcher) {
        val r = rig()
        r.model.askSave("lz-1")
        r.model.saveName("  ")
        r.model.confirmSave()
        assertEquals("Give it a name.", r.model.save.value!!.nameError)
        r.model.saveName("A" + Char(0x07))
        r.model.confirmSave()
        assertEquals("Give it a name of 1 to 100 characters.", r.model.save.value!!.nameError)
        r.model.saveName("A".repeat(101))
        assertEquals("A" + Char(0x07), r.model.save.value!!.name)                                   // never typed past 100
        r.model.saveName("POINTS")
        r.calls.kept = KeptOutcome.NothingToKeep("k", "empty_point_set")
        r.model.confirmSave()
        advanceUntilIdle()
        assertEquals("A point set with no points cannot be saved.", r.model.save.value!!.error)
    }

    @Test
    fun `a copy's name fits the server whole, counted in characters`() = runTest(dispatcher) {
        assertEquals("A".repeat(95) + " copy", PackItemCopyViewModel.copyName("A".repeat(120)))
        assertEquals("LZ HAWK copy", PackItemCopyViewModel.copyName("LZ HAWK"))
        val heli = "🚁"
        assertEquals("A".repeat(94) + heli + " copy", PackItemCopyViewModel.copyName("A".repeat(94) + heli + "BBB"))
        val r = rig()
        r.model.askSave("lz-1")
        r.model.saveName(heli.repeat(100))                                                           // 200 UTF-16 units, 100 characters
        assertEquals(heli.repeat(100), r.model.save.value!!.name)
        r.model.saveName(heli.repeat(101))
        assertEquals(heli.repeat(100), r.model.save.value!!.name)
        r.model.confirmSave()
        advanceUntilIdle()
        assertNull(r.model.save.value)
    }

    @Test
    fun `an item gone meanwhile is said, and another account puts the dialog away`() = runTest(dispatcher) {
        val r = rig()
        r.model.askSave("lz-1")
        r.door.open.value = dk(emptyList())
        r.model.confirmSave()
        advanceUntilIdle()
        assertEquals("That item is no longer in the pack.", r.model.save.value!!.error)
        r.door.user.value = 8
        advanceUntilIdle()
        assertNull(r.model.save.value)
    }

    @Test
    fun `a copy asked for once its pack is no longer open is said so in a toast, not as an item gone`() = runTest(dispatcher) {
        val r = rig()
        r.model.askSave("lz-1")
        r.model.confirmSave()
        r.door.open.value = dk(uuid = "rv", name = "OP RAVEN")
        advanceUntilIdle()
        assertNull(r.model.save.value)
        assertEquals("“LZ HAWK copy” was not saved to your Library. OP DK is not open here now.", r.notices.single().words)
        assertTrue(r.log.none { it.startsWith("save") })
    }

    @Test
    fun `a copy on its way keeps its dialog when the pack changes, and what it came to is then said`() = runTest(dispatcher) {
        val r = rig()
        r.calls.saveHold = CompletableDeferred()
        r.model.askSave("lz-1")
        r.model.confirmSave()
        advanceUntilIdle()
        r.door.open.value = dk(uuid = "rv", name = "OP RAVEN")
        advanceUntilIdle()
        assertTrue(r.model.save.value!!.sending)
        r.calls.saveHold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(r.model.save.value)
        assertEquals("Saved “LZ HAWK copy” to your Library", r.notices.single().words)
    }

    @Test
    fun `another account ends a copy on its way, puts its dialog away, and is told nothing`() = runTest(dispatcher) {
        val r = rig()
        r.calls.saveHold = CompletableDeferred()
        r.model.askSave("lz-1")
        r.model.confirmSave()
        advanceUntilIdle()
        assertTrue(r.model.save.value!!.sending)
        r.door.user.value = 8
        advanceUntilIdle()
        assertNull(r.model.save.value)
        r.calls.saveHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.notices.isEmpty())
    }

    @Test
    fun `a finished pack's item can be saved too`() = runTest(dispatcher) {
        val r = rig()
        r.door.open.value = dk(readOnly = true)
        r.model.askSave("lz-1")
        r.model.confirmSave()
        advanceUntilIdle()
        assertEquals(1, r.log.count { it.startsWith("save") })
    }
}
