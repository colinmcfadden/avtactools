package app.ezpztac.packs

import app.ezpztac.data.CopyIn
import app.ezpztac.data.LibraryCopier
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.ApiException
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.Invite
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.StopReason
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Adding a copy of the person's own Library item to a pack: the dialog's packs, one copy at a time, the item id kept through tries, and the account it is for. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackCopyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val me = 6

    private fun pack(uuid: String, name: String, role: String = "editor", status: String = "active", audience: Int = 4, finishedAt: String? = null) = PackSummary(
        uuid, name, "", status, role, PackPerson(6, "Colin"), headSeq = 1, seenSeq = 1, memberCount = audience, audienceCount = audience, itemCount = 0,
        itemCounts = PackItemCounts(0, 0, 0), finishedAt = finishedAt, createdAt = "x", updatedAt = "x",
    )

    private inner class FakeApi : PacksApi {
        val log = mutableListOf<String>()
        var packs = listOf(pack("razor", "OP RAZORBILL", status = "finished", finishedAt = "2026-09-22T10:00:00"), pack("dk", "OP DK"), pack("bco", "B CO FIELD EX", audience = 18))
        var fail: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun packs(): List<PackSummary> {
            log += "packs"
            val answer = packs
            val failing = fail
            hold?.await()
            failing?.let { throw it }
            return answer
        }
        override suspend fun invites(): List<Invite> = emptyList()
        override suspend fun teams(): List<TeamSummary> = emptyList()
        override suspend fun accept(inviteId: Int): InviteAccepted = error("not here")
        override suspend fun decline(inviteId: Int) = Unit
        override suspend fun finish(uuid: String, user: Int) = Unit
        override suspend fun reopen(uuid: String, user: Int) = Unit
        override suspend fun duplicate(uuid: String, name: String, user: Int): PackMade = error("not here")
    }

    private class FakeDoor(user: Int?) : PackDoor {
        override val user = MutableStateFlow(user)
        override val open = MutableStateFlow<PackState?>(null)
        val refreshed = mutableListOf<String?>()
        override suspend fun openPack(uuid: String) = Unit
        override suspend fun closePack() = Unit
        override fun refresh() {
            refreshed += open.value?.uuid
        }
    }

    private inner class FakeCopier : LibraryCopier {
        val log = mutableListOf<String>()
        var answer: CopyIn = CopyIn.Copied("LZ HAWK", again = false)
        var fail: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        val moved = mutableMapOf<String, String>()

        /** A move the next copy learns as it is made (its open document kept under a new id), as LibraryToPack does. */
        var learns: Pair<String, String>? = null
        override fun currentId(uuid: String): String = moved[uuid] ?: uuid
        override suspend fun copyIn(pack: String, kind: RecordKind, uuid: String, item: String, user: Int): CopyIn {
            learns?.let { (from, to) -> moved[from] = to }
            log += "$kind $uuid into $pack as $item for $user"
            hold?.await()
            fail?.let { throw it }
            return answer
        }
    }

    private inner class Rig(scope: TestScope, user: Int? = me) {
        val api = FakeApi()
        val door = FakeDoor(user)
        val copier = FakeCopier()
        val model = PackCopyViewModel(api, door, copier).also {
            it.zone = { ZoneId.of("UTC") }
            it.now = { Instant.parse("2026-10-13T12:00:00Z") }
        }
        val notices = mutableListOf<CopyNotice>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
        }

        val add get() = model.add.value
    }

    private fun TestScope.asked(kind: RecordKind = RecordKind.LZ) = Rig(this).also {
        advanceUntilIdle()
        it.model.ask(kind, "u1", "LZ HAWK (SHOPE)")
        advanceUntilIdle()
    }

    @Test
    fun `the packs are listed, those worked on first, and the first that can take a copy is chosen`() = runTest(dispatcher) {
        val r = asked()
        val s = r.add!!
        val packs = s.packs!!
        assertEquals(listOf("dk", "bco", "razor"), packs.map { it.uuid })
        assertEquals(
            listOf("4 members · you can edit", "18 members · you can edit", "Finished Sep 22. Reopen it to add items."),
            packs.map { it.meta },
        )
        assertEquals(listOf(true, true, false), packs.map { it.usable })
        assertEquals("dk", s.chosen)
        assertTrue(s.item.startsWith("lz-"))
        r.model.choose("razor")                                                                      // finished: it cannot take one
        assertEquals("dk", r.add!!.chosen)
        r.model.choose("bco")
        assertEquals("bco", r.add!!.chosen)
    }

    @Test
    fun `a viewer's pack cannot take a copy, and with no pack to choose nothing is chosen`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(pack("dk", "OP DK", role = "viewer", audience = 1))
        r.model.ask(RecordKind.ROUTE, "u1", "RED 1")
        advanceUntilIdle()
        assertEquals("1 member · you can view only", r.add!!.packs!!.single().meta)
        assertNull(r.add!!.chosen)
        assertTrue(r.add!!.item.startsWith("rt-"))
        r.model.addCopy()
        advanceUntilIdle()
        assertTrue(r.copier.log.isEmpty())
    }

    @Test
    fun `packs that could not be had are said, with a way to try again that keeps the choice`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.fail = NetworkException("down", requestMayHaveBeenSent = false)
        r.model.ask(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        assertEquals("There is no connection to the server. Try again when you are back online.", r.add!!.listError)
        assertNull(r.add!!.packs)
        r.api.fail = null
        r.model.loadPacks()
        advanceUntilIdle()
        assertNull(r.add!!.listError)
        assertEquals("dk", r.add!!.chosen)
    }

    @Test
    fun `a copy is made into the pack chosen, said with Open pack, and the dialog goes`() = runTest(dispatcher) {
        val r = asked()
        val item = r.add!!.item
        r.model.addCopy()
        advanceUntilIdle()
        assertEquals(listOf("LZ u1 into dk as $item for $me"), r.copier.log)
        assertNull(r.add)
        assertEquals(listOf(CopyNotice("Added a copy of “LZ HAWK (SHOPE)” to OP DK", openPack = "dk")), r.notices)
    }

    @Test
    fun `a copy that did not go is said in the dialog, and trying again makes it as the same item`() = runTest(dispatcher) {
        val r = asked()
        val item = r.add!!.item
        r.copier.fail = NetworkException("lost", requestMayHaveBeenSent = true)
        r.model.addCopy()
        advanceUntilIdle()
        assertEquals(PackCopyViewModel.LOST, r.add!!.error)
        assertFalse(r.add!!.sending)
        r.copier.fail = null
        r.copier.answer = CopyIn.NotUploaded(StopReason.Offline, ran = true)
        r.model.addCopy()
        advanceUntilIdle()
        assertEquals(PackCopyViewModel.NOT_UP_OFFLINE, r.add!!.error)
        r.copier.answer = CopyIn.Copied("LZ HAWK", again = true)
        r.model.addCopy()
        advanceUntilIdle()
        assertNull(r.add)
        assertEquals(3, r.copier.log.size)
        assertTrue(r.copier.log.all { it.endsWith("as $item for $me") })
        assertEquals(CopyNotice("“LZ HAWK (SHOPE)” was in OP DK already, from an earlier try", openPack = "dk"), r.notices.last())
    }

    @Test
    fun `a pack that has the item id already takes a new one, since that one can never go`() = runTest(dispatcher) {
        val r = asked()
        val item = r.add!!.item
        r.copier.fail = ApiException(409, "item_exists", "x")
        r.model.addCopy()
        advanceUntilIdle()
        assertEquals("The pack already has that item.", r.add!!.error)
        val next = r.add!!.item
        assertTrue(next != item && next.startsWith("lz-"))
        r.copier.fail = ApiException(500, null, "x")
        r.model.addCopy()
        advanceUntilIdle()
        assertEquals(next, r.add!!.item)                                                            // a failure that proves nothing keeps it
    }

    @Test
    fun `a copy whose answer could not be read was made`() = runTest(dispatcher) {
        val r = asked()
        r.copier.fail = ApiException(201, "unreadable_response", "x")
        r.model.addCopy()
        advanceUntilIdle()
        assertNull(r.add)
        assertEquals("Added a copy of “LZ HAWK (SHOPE)” to OP DK", r.notices.last().words)
    }

    @Test
    fun `the dialog is put away, a pack chosen stays chosen when the packs come again, and another account's packs are never shown`() = runTest(dispatcher) {
        val r = asked()
        r.model.choose("bco")
        r.model.loadPacks()
        advanceUntilIdle()
        assertEquals("bco", r.add!!.chosen)
        r.model.dismiss()
        assertNull(r.add)

        val o = asked()
        o.api.hold = CompletableDeferred()
        o.model.loadPacks()
        advanceUntilIdle()
        o.door.user.value = 8
        advanceUntilIdle()
        o.model.ask(RecordKind.LZ, "u2", "LZ CROW")
        o.api.packs = emptyList()
        o.api.hold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(o.add!!.packs!!.isEmpty())                                                       // the first account's answer never landed
    }

    @Test
    fun `what a copy came to is said in the app's words`() {
        assertEquals("That is no longer in your Library.", PackCopyViewModel.refusal(CopyIn.Gone))
        assertEquals(PackCopyViewModel.UNSAVED, PackCopyViewModel.refusal(CopyIn.Unsaved))
        assertEquals(PackCopyViewModel.NOT_UP_OFFLINE, PackCopyViewModel.refusal(CopyIn.NotUploaded(StopReason.Offline, ran = true)))
        assertEquals(PackCopyViewModel.NOT_UP_SIGNED_OUT, PackCopyViewModel.refusal(CopyIn.NotUploaded(StopReason.SessionEnded("x"), ran = true)))
        assertEquals(PackCopyViewModel.NOT_UP, PackCopyViewModel.refusal(CopyIn.NotUploaded(null, ran = false)))
        assertEquals(PackCopyViewModel.LOST, PackCopyViewModel.failure(ApiException(502, null, "x")))
        assertEquals("Imported AMPS missions cannot go in a pack yet. Sketched routes can.", PackCopyViewModel.failure(ApiException(400, "mission_not_supported", "x")))
        assertEquals("This pack is finished. It is read-only until its owner reopens it.", PackCopyViewModel.failure(ApiException(423, "pack_finished", "x")))
        assertEquals(PackCopyViewModel.CONFLICTED, PackCopyViewModel.refusal(CopyIn.Conflicted))
        assertEquals(PackCopyViewModel.REFUSED, PackCopyViewModel.refusal(CopyIn.Refused))
    }

    @Test
    fun `while a copy is made it is not made twice, nothing else is chosen, and it goes on once the dialog is put away`() = runTest(dispatcher) {
        val r = asked()
        r.copier.hold = CompletableDeferred()
        r.model.addCopy()
        advanceUntilIdle()
        assertTrue(r.add!!.sending)
        r.model.addCopy()
        r.model.choose("bco")
        r.model.ask(RecordKind.ROUTE, "u9", "RED 1")
        advanceUntilIdle()
        assertEquals("u1", r.add!!.uuid)
        assertEquals("dk", r.add!!.chosen)
        assertEquals(1, r.copier.log.size)
        r.model.dismiss()                                                                           // the copy goes on
        assertNull(r.add)
        r.model.ask(RecordKind.ROUTE, "u9", "RED 1")
        advanceUntilIdle()
        r.model.addCopy()
        assertEquals(PackPanelViewModel.STILL_WORKING, r.add!!.error)                                // said in the dialog, which covers the toasts
        r.copier.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CopyNotice("Added a copy of “LZ HAWK (SHOPE)” to OP DK", openPack = "dk"), r.notices.last())
        assertEquals("u9", r.add!!.uuid)                                                            // the other dialog stays as it was
    }

    @Test
    fun `a copy refused once its dialog was put away is said in a toast`() = runTest(dispatcher) {
        val r = asked()
        r.copier.hold = CompletableDeferred()
        r.copier.fail = ApiException(423, "pack_finished", "x")
        r.model.addCopy()
        advanceUntilIdle()
        r.model.dismiss()
        r.copier.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("“LZ HAWK (SHOPE)” was not added to OP DK. This pack is finished. It is read-only until its owner reopens it.", r.notices.last().words)
    }

    @Test
    fun `another account puts the dialog away, and what its copy came to is never said to the next`() = runTest(dispatcher) {
        val r = asked()
        r.copier.hold = CompletableDeferred()
        r.model.addCopy()
        advanceUntilIdle()
        r.door.user.value = 8
        advanceUntilIdle()
        assertNull(r.add)
        r.copier.hold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.notices.isEmpty())

        val o = Rig(this, user = null)
        o.model.ask(RecordKind.LZ, "u1", "LZ HAWK")
        assertNull(o.add)                                                                             // packs do not run here
        o.model.ask(RecordKind.MISSION, "u1", "GOAT")
        assertNull(o.add)
    }

    @Test
    fun `a copy into the pack open asks it for what is new, and offers no way to open it`() = runTest(dispatcher) {
        val r = asked()
        r.door.open.value = PackState("dk", PackStatus.LIVE, null, emptyList(), emptyList(), null)
        r.model.addCopy()
        advanceUntilIdle()
        assertEquals(listOf<String?>("dk"), r.door.refreshed)
        assertNull(r.notices.single().openPack)
    }

    // -- Straight into the pack open (the Library's Add to pack) ---------------------------------------------------------

    private fun openDk(readOnly: Boolean = false): PackState {
        val pack = kotlinx.serialization.json.buildJsonObject {
            put("uuid", kotlinx.serialization.json.JsonPrimitive("dk"))
            put("name", kotlinx.serialization.json.JsonPrimitive("OP DK"))
        }
        val session = app.ezpztac.missionpacks.PackSession(
            me, pack, kotlinx.serialization.json.JsonArray(emptyList()), emptyMap(), emptyMap(), emptyList(), 10, emptyList(), emptyList(), readOnly, null, false, emptyMap(),
        )
        return PackState("dk", PackStatus.LIVE, session, emptyList(), emptyList(), null)
    }

    @Test
    fun `a Library item is added to the pack open, as one item through every try, and the pack is asked for what is new`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = openDk()
        advanceUntilIdle()
        r.copier.fail = NetworkException("lost", requestMayHaveBeenSent = true)
        r.model.addToOpen(RecordKind.POINT_SET, "u1", "NORTH GA")
        advanceUntilIdle()
        assertEquals("“NORTH GA” was not added. ${PackCopyViewModel.LOST}", r.notices.last().words)
        r.copier.fail = null
        r.model.addToOpen(RecordKind.POINT_SET, "u1", "NORTH GA")
        advanceUntilIdle()
        assertEquals(CopyNotice("Added a copy of “NORTH GA” to OP DK"), r.notices.last())
        assertEquals(listOf<String?>("dk"), r.door.refreshed)
        assertEquals(1, r.copier.log.map { it.substringAfter(" as ").substringBefore(" for") }.distinct().size)   // the same item both times
        assertTrue(r.copier.log.all { it.startsWith("POINT_SET u1 into dk as ps-") })
        r.model.addToOpen(RecordKind.POINT_SET, "u1", "NORTH GA")                                   // once it is in, another copy is another item
        advanceUntilIdle()
        assertEquals(2, r.copier.log.map { it.substringAfter(" as ").substringBefore(" for") }.distinct().size)
    }

    @Test
    fun `nothing is added to a pack this person can only view, one at a time, and never a mission`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = openDk(readOnly = true)
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        assertEquals("You can view this pack but not change it.", r.notices.last().words)
        assertTrue(r.copier.log.isEmpty())
        r.door.open.value = openDk()
        r.copier.hold = CompletableDeferred()
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        r.model.addToOpen(RecordKind.ROUTE, "u2", "RED 1")
        assertEquals(PackPanelViewModel.STILL_WORKING, r.notices.last().words)
        r.model.addToOpen(RecordKind.MISSION, "u3", "GOAT")
        r.copier.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, r.copier.log.size)
    }

    @Test
    fun `what a copy into the pack open came to is never said to another account`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = openDk()
        r.copier.hold = CompletableDeferred()
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        r.door.user.value = 8
        advanceUntilIdle()
        r.copier.hold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.notices.isEmpty())
    }

    @Test
    fun `a pack still opening says so, never that this person can only view it`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = PackState("dk", PackStatus.LOADING, null, emptyList(), emptyList(), null)
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        assertEquals(PackCopyViewModel.STILL_OPENING, r.notices.last().words)
        assertTrue(r.copier.log.isEmpty())
    }

    @Test
    fun `the row being added says so until its copy is in`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = openDk()
        r.copier.hold = CompletableDeferred()
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        assertEquals("u1", r.model.adding.value)
        r.copier.hold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(r.model.adding.value)
    }

    @Test
    fun `a record seen moving keeps its item, and one the pack has already takes a new one`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = openDk()
        r.copier.fail = NetworkException("lost", requestMayHaveBeenSent = true)
        r.copier.learns = "u1" to "u2"
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        r.model.addToOpen(RecordKind.LZ, "u2", "LZ HAWK")                                          // the row shows the record under its new uuid
        advanceUntilIdle()
        val items = r.copier.log.map { it.substringAfter(" as ").substringBefore(" for") }
        assertEquals(1, items.distinct().size)
        r.copier.fail = ApiException(409, "item_exists", "x")
        r.model.addToOpen(RecordKind.LZ, "u2", "LZ HAWK")
        advanceUntilIdle()
        r.copier.fail = null
        r.model.addToOpen(RecordKind.LZ, "u2", "LZ HAWK")
        advanceUntilIdle()
        assertEquals(2, r.copier.log.map { it.substringAfter(" as ").substringBefore(" for") }.distinct().size)
    }

    @Test
    fun `another account ends a copy under way, so the next one is not kept waiting behind it`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = openDk()
        r.copier.hold = CompletableDeferred()
        r.model.addToOpen(RecordKind.LZ, "u1", "LZ HAWK")
        advanceUntilIdle()
        r.door.user.value = 8
        advanceUntilIdle()
        assertNull(r.model.adding.value)
        r.copier.hold = null
        r.model.addToOpen(RecordKind.LZ, "u2", "LZ CROW")
        advanceUntilIdle()
        assertEquals(CopyNotice("Added a copy of “LZ CROW” to OP DK"), r.notices.last())
    }
}
