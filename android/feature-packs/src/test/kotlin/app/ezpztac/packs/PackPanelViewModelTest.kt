package app.ezpztac.packs

import app.ezpztac.missionpacks.DroppedOp
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.PackEvent
import app.ezpztac.network.PackEventPage
import app.ezpztac.network.PackPerson
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Pack panel: how far this person has looked, the items and what changed, the history a page at a time, and what the panel does. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackPanelViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    // -- How far this person has looked (usePackSeen.js) -----------------------------------------------------------------------------------

    @Test
    fun `nothing is new until the pack has loaded, and the first marker the server says is where new starts`() {
        val loading = Seen().next("p", server = null, looking = true)
        assertEquals(Seen("p", null, null, true), loading)
        val loaded = loading.next("p", server = 4, looking = true)
        assertEquals(4L, loaded.seen)
        assertEquals(4L, loaded.since)
    }

    @Test
    fun `new is measured from where they were when they started looking, and stays put while they look`() {
        var mark = Seen().next("p", 4, looking = false)
        mark = mark.copy(seen = 9)                                                                     // marked meanwhile
        mark = mark.next("p", 4, looking = true)
        assertEquals(9L, mark.since)
        mark = mark.copy(seen = 12).next("p", 4, looking = true)                                       // the marker moves while they look
        assertEquals(9L, mark.since)
        mark = mark.next("p", 15, looking = true)                                                      // another device says more: it moves, "new" does not
        assertEquals(15L, mark.seen)
        assertEquals(9L, mark.since)
        mark = mark.next("p", 15, looking = false)                                                     // they look away: "new" stays where it was
        assertEquals(9L, mark.since)
        assertEquals(Seen("q", 2, 2, false), mark.next("q", 2, looking = false))                        // a new pack starts fresh
    }

    @Test
    fun `an item is new once someone else changed it after where new starts`() {
        fun item(seq: Long?, by: Int?) = PackItemView("lz-1", "lz", "LZ HAWK", JsonObject(emptyMap()), info(seq, by))
        assertFalse(PackPanelViewModel.changedSince(item(11, 2), null, 1))
        assertFalse(PackPanelViewModel.changedSince(item(8, 2), 8, 1))
        assertFalse(PackPanelViewModel.changedSince(item(11, 1), 8, 1))                                // their own
        assertTrue(PackPanelViewModel.changedSince(item(11, 2), 8, 1))
        assertTrue(PackPanelViewModel.changedSince(item(11, null), 8, 1))                              // the pack's own change counts
        assertFalse(PackPanelViewModel.changedSince(item(null, 2), 8, 1))                              // made here, not taken yet
    }

    // -- A pack, as the engine holds it ------------------------------------------------------------------------------------------------

    private val now = Instant.parse("2026-10-12T14:00:00Z")
    private val utc = ZoneId.of("UTC")

    private fun info(seq: Long?, by: Int?, name: String = "Sam B.", at: String = "2026-10-12T13:56:00", source: JsonElement? = null) = buildJsonObject {
        seq?.let { put("seq", it) }
        put("updated_by", buildJsonObject { if (by != null) put("id", by) else put("id", JsonNull); put("name", name) })
        put("updated_at", at)
        source?.let { put("source", it) }
    }

    private fun session(
        me: Int = 1,
        seq: Long = 12,
        seen: Long = 8,
        role: String = "owner",
        status: String = "active",
        readOnly: Boolean = false,
        audience: Int = 4,
        team: Boolean = true,
        description: String? = "North Georgia night LZ/PZ and route planning.",
        dropped: List<DroppedOp> = emptyList(),
    ) = PackSession(
        me,
        buildJsonObject {
            put("uuid", "p")
            put("name", "OP DK")
            put("status", status)
            put("role", role)
            put("seen_seq", seen)
            put("head_seq", seq)
            put("audience_count", audience)
            if (team) put("team", buildJsonObject { put("id", 3); put("name", "B Co 2-10 AVN") }) else put("team", JsonNull)
            description?.let { put("description", it) }
        },
        buildJsonArray {
            add(member(1, "Colin McFadden"))
            add(member(2, "Sam Bell"))
            add(member(3, "Jess Reed"))
        },
        emptyMap(), emptyMap(), emptyList(), seq, emptyList(), dropped, readOnly, null, false, emptyMap(),
    )

    private fun member(id: Int, name: String) = buildJsonObject { put("user_id", id); put("name", name); put("role", "editor") }

    private fun here(id: Int, name: String, item: String? = null) = buildJsonObject {
        put("session", "s-$id")
        put("user_id", id)
        put("name", name)
        put("focus", buildJsonObject { item?.let { put("item", it) } })
    }

    private val lz = PackItemView("lz-1", "lz", "LZ IBIS", JsonObject(emptyMap()), info(11, 2, "Sam Bell"))
    private val hawk = PackItemView(
        "lz-2", "lz", "LZ HAWK", JsonObject(emptyMap()),
        info(7, 1, "Colin McFadden", at = "2026-10-12T09:40:00", source = buildJsonObject { put("kind", "lz"); put("original", "changed") }),
    )
    private val routes = PackItemView(
        "rt-1", "route", "OP DK INGRESS",
        buildJsonObject { put("version", 1); put("routes", buildJsonArray { add(JsonObject(emptyMap())); add(JsonObject(emptyMap())) }) },
        info(10, 2, "Sam Bell", at = "2026-10-04T13:38:00"),
    )
    private val points = PackItemView("ps-1", "pointset", "NORTH GA", JsonArray(List(1234) { JsonObject(emptyMap()) }), info(5, 1, "Colin McFadden", at = "2026-10-12T13:59:50"))
    private val sending = PackItemView("lz-3", "lz", "LZ/PZ 3", JsonObject(emptyMap()), null, pendingCreate = true)

    private class FakeDoor : PackDoor {
        override val user = MutableStateFlow<Int?>(1)
        override val open = MutableStateFlow<PackState?>(null)
        override suspend fun openPack(uuid: String) = Unit
        override suspend fun closePack() = Unit
    }

    private class FakePanel : PackPanelDoor {
        override val openHere = MutableStateFlow<Set<String>>(emptySet())
        override val notices = MutableSharedFlow<PackNotice>(extraBufferCapacity = 8)
        var log = mutableListOf<String>()
        val pages = ArrayDeque<PackEventPage>()
        var hold: CompletableDeferred<Unit>? = null
        var failEvents = false
        var failSeen = false
        var seenAnswer: Long? = null
        var refuse: String? = null
        var opens = true
        var openFails = false
        override suspend fun events(pack: String, since: Long, limit: Int): PackEventPage {
            log += "events $pack $since $limit"
            hold?.await()
            if (failEvents) throw IllegalStateException("no signal")
            return pages.removeFirstOrNull() ?: PackEventPage(emptyList(), since, false, since)
        }
        override suspend fun markSeen(pack: String, seq: Long): Long {
            log += "seen $pack $seq"
            if (failSeen) throw IllegalStateException("no signal")
            return seenAnswer ?: seq
        }
        override suspend fun openItem(item: String): Boolean {
            if (openFails) throw IllegalStateException("the disk is full")
            log += "open $item"
            return opens
        }
        override suspend fun closeItem(item: String) { log += "close $item" }
        override suspend fun renameItem(item: String, name: String): String? { log += "rename $item $name"; return refuse }
        override suspend fun removeItem(item: String): String? { log += "remove $item"; return refuse }
        var keepAnswer: List<PackNotice>? = null
        var keepHold: CompletableDeferred<Unit>? = null
        var discarded = 1
        override suspend fun keepDropped(pack: String): List<PackNotice>? {
            log += "keep $pack"
            keepHold?.await()
            return keepAnswer
        }
        override suspend fun discardDropped(pack: String, ids: Set<String>): Int {
            log += "discard $pack ${ids.sorted()}"
            return discarded
        }
    }

    private inner class Rig(scope: TestScope) {
        val door = FakeDoor()
        val panel = FakePanel()
        val model = PackPanelViewModel(door, panel).also {
            it.zone = { utc }
            it.now = { now }
        }
        val notices = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val kept = mutableListOf<CopyNotice>()
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.kept.collect { kept += it } }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.opened.collect { opened += it } }
        }

        fun open(session: PackSession? = session(), items: List<PackItemView> = listOf(lz, hawk, routes, points, sending), people: List<JsonObject> = emptyList(), status: PackStatus = PackStatus.LIVE) {
            door.open.value = PackState("p", status, session, items, people, null)
        }
    }

    // -- The items -----------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the items are grouped by kind, each saying who changed it last and when, what it holds and where it came from`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(people = listOf(here(1, "Colin McFadden", "lz-1"), here(2, "Sam Bell", "lz-1"), here(3, "Jess Reed", "rt-1")))
        r.panel.openHere.value = setOf("lz-1")
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals(listOf("LZ/PZ" to listOf("LZ IBIS", "LZ HAWK", "LZ/PZ 3"), "Routes" to listOf("OP DK INGRESS"), "Point sets" to listOf("NORTH GA")), s.groups.map { g -> g.title to g.items.map { it.name } })
        val (ibis, hawk, made) = s.groups[0].items
        assertEquals("Sam B. · 4 min ago", ibis.meta)
        assertEquals("Colin M. · 09:40", hawk.meta)
        assertEquals("Sending…", made.meta)
        assertEquals("2 routes · Sam B. · Oct 4", s.groups[1].items[0].meta)
        assertEquals("1,234 points · Colin M. · just now", s.groups[2].items[0].meta)
        assertTrue(ibis.isNew)                                                                         // Sam's, after where new starts (8)
        assertFalse(hawk.isNew)
        assertEquals(listOf("Sam B."), ibis.editing)                                                   // not this person
        assertEquals(listOf("Jess R."), s.groups[1].items[0].editing)
        assertTrue(ibis.openHere)
        assertTrue(hawk.fromLibrary)
        assertEquals("changed", hawk.original)
        assertFalse(s.groups[2].items[0].canOpen)
        assertEquals(
            "LZ IBIS, changed in the pack since you looked, by Sam B. 4 min ago. Sam B. has it open. Open here. Show on the map",
            ibis.description,
        )
        assertEquals("LZ HAWK, by Colin M. at 09:40. From Library, original changed. Show on the map", hawk.description)
        assertEquals("OP DK INGRESS, changed in the pack since you looked, 2 routes, by Sam B. on Oct 4. Jess R. has it open. Show on the map", s.groups[1].items[0].description)
        assertEquals(2, s.newCount)
        assertTrue(s.historyHasNew)
    }

    @Test
    fun `the head says what this person is in the pack, who is in it and who is here now`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(people = listOf(here(1, "Colin McFadden"), here(2, "Sam Bell"), here(2, "Sam Bell")))         // Sam twice: two devices, one person
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals("OP DK", s.name)
        assertEquals("Mission Pack · you are the owner", s.subtitle)
        assertEquals("4 members · 2 here now", s.membersLine)                                        // the audience, a team's included; this person is here too
        assertEquals(listOf("CM" to true, "SB" to true, "JR" to false), s.faces.map { it.initials to it.here })
        assertTrue(s.canEdit)
        assertTrue(s.owner)
        assertEquals("4 members, 2 here now. Manage members", s.membersButton)
        assertEquals("North Georgia night LZ/PZ and route planning.", s.description)
        r.open(session(role = "editor"))
        advanceUntilIdle()
        assertTrue(r.model.state.value.canEdit)
        assertFalse(r.model.state.value.owner)                                                       // an editor has no Invite
        r.open(session(role = "viewer", readOnly = true))
        advanceUntilIdle()
        assertEquals("Mission Pack · you are a viewer", r.model.state.value.subtitle)
        assertFalse(r.model.state.value.canEdit)
        assertFalse(r.model.state.value.owner)
        r.open(session(status = "finished", readOnly = true), items = emptyList())
        advanceUntilIdle()
        assertTrue(r.model.state.value.finished)
        assertEquals("This pack is empty.", r.model.state.value.emptyItems)
        r.open(session(audience = 0, description = "  "), items = emptyList())
        advanceUntilIdle()
        assertEquals("3 members", r.model.state.value.membersLine)                                       // never fewer than its members
        r.open(session(audience = 4, team = false, description = "  "), items = emptyList())
        advanceUntilIdle()
        assertEquals("3 members", r.model.state.value.membersLine)                                       // no team: its members, as a member leaves
        assertNull(r.model.state.value.description)
        assertEquals("Nothing in this pack yet. Set a target or sketch a route while the pack is open, and it goes into the pack.", r.model.state.value.emptyItems)
    }

    @Test
    fun `a pack not yet on the device says it is opening, or that it cannot be reached`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session = null, items = emptyList(), status = PackStatus.LOADING)
        advanceUntilIdle()
        assertFalse(r.model.state.value.loaded)
        assertEquals("Opening…", r.model.state.value.subtitle)
        r.open(session = null, items = emptyList(), status = PackStatus.ERROR)
        advanceUntilIdle()
        assertEquals("Cannot reach the pack", r.model.state.value.subtitle)
        assertTrue(r.model.state.value.unreachable)
    }

    // -- Seen -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `while they look, what is new is marked seen after a moment, and only then`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.panel.log.filter { it.startsWith("seen") })               // not looking
        r.model.setLooking(true)
        runCurrent()
        advanceTimeBy(1_400)
        assertEquals(emptyList<String>(), r.panel.log.filter { it.startsWith("seen") })
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("seen p 12"), r.panel.log.filter { it.startsWith("seen") })
        assertTrue("still new while they look", r.model.state.value.groups[0].items[0].isNew)
        r.model.setLooking(false)
        runCurrent()
        r.model.setLooking(true)                                                                        // they look again: new starts where they had got to
        advanceUntilIdle()
        assertFalse(r.model.state.value.groups[0].items[0].isNew)
        assertEquals(0, r.model.state.value.newCount)
    }

    @Test
    fun `a marker that could not be sent is tried again when the pack moves on`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.panel.failSeen = true
        r.model.setLooking(true)
        advanceUntilIdle()
        assertEquals(listOf("seen p 12"), r.panel.log.filter { it.startsWith("seen") })
        r.panel.failSeen = false
        r.open(session(seq = 13))
        advanceUntilIdle()
        assertEquals(listOf("seen p 12", "seen p 13"), r.panel.log.filter { it.startsWith("seen") })
    }

    // -- The history ----------------------------------------------------------------------------------------------------------------------

    private fun event(seq: Long, actor: Int?, name: String, summary: String, item: String? = "lz-1", type: String = "patch", status: String = "applied", reason: String? = null, at: String = "2026-10-12T13:42:00", op: JsonObject = JsonObject(emptyMap())) =
        PackEvent(seq, type, item, PackPerson(actor, name), summary, status, reason, null, op, at)

    @Test
    fun `the history's first page is the newest, never the oldest, and what comes after is asked for from the newest held`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(seq = 600))
        r.panel.pages += PackEventPage(listOf(event(101, 2, "Sam Bell", "Sam Bell moved CH2.")), 600, true, 1000)
        r.panel.pages += PackEventPage(listOf(event(999, 2, "Sam Bell", "Sam Bell moved CH3.")), 1000, false, 1000)
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        assertEquals(listOf("events p 100 500", "events p 500 500"), r.panel.log)
        r.panel.log.clear()
        r.panel.pages += PackEventPage(listOf(event(1001, 3, "Jess Reed", "Jess Reed renamed it.")), 1001, false, 1001)
        r.open(session(seq = 1001))
        advanceUntilIdle()
        assertEquals(listOf("events p 1000 500"), r.panel.log)
        assertEquals(listOf(1001L, 999L), r.model.state.value.history.groups.flatMap { it.events }.map { it.seq })
        r.model.selectTab(PackTab.ITEMS)                                                                 // leaving the tab lets it all go
        advanceUntilIdle()
        r.panel.log.clear()
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        assertEquals(listOf("events p 501 500"), r.panel.log)
    }

    @Test
    fun `new events go first, then each day's, newest first, with who did it, what and where`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(seq = 13, seen = 10))
        r.panel.pages += PackEventPage(
            listOf(
                event(9, 1, "Colin McFadden", "Colin McFadden updated LZ HAWK from Library.", item = "lz-2", at = "2026-10-11T11:05:00"),
                event(10, 3, "Jess Reed", "Jess Reed changed the landing heading.", item = "lz-gone", at = "2026-10-12T10:48:00", status = "skipped", reason = "target_missing"),
                event(11, 1, "Colin McFadden", "Colin McFadden invited m@army.mil as Viewer.", item = null, type = "invite.create", at = "2026-10-12T13:00:00"),
                event(12, 2, "Sam Bell", "Sam Bell moved CH2 on LZ IBIS.", at = "2026-10-12T13:42:00"),
                event(13, null, "", "The pack was renamed.", item = "lz-gone", type = "item.rename", op = buildJsonObject { put("name", "LZ CROW") }, at = "2026-10-12T13:50:00"),
            ),
            13, false, 13,
        )
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        val groups = r.model.state.value.history.groups
        assertEquals(listOf("New since you looked · 2", "Earlier today", "Yesterday"), groups.map { it.title })
        assertEquals(listOf(13L, 12L), groups[0].events.map { it.seq })                                  // the person's own (11) is not new
        val sam = groups[0].events[1]
        assertEquals("Sam Bell" to " moved CH2 on LZ IBIS.", sam.lead to sam.rest)
        assertEquals("13:42 · LZ IBIS", sam.meta)
        assertEquals("13:50 · LZ CROW", groups[0].events[0].meta)                                   // a removed item, by the name it had
        assertNull(groups[0].events[0].who)
        val skipped = groups[1].events.first { it.seq == 10L }
        assertTrue(skipped.skipped)
        assertEquals("Edit skipped:", skipped.lead)
        assertEquals(" what it changed had already been removed. Jess R.’s change was not applied (Jess Reed changed the landing heading).", skipped.rest)
        assertEquals("10:48 · Removed item", skipped.meta)
        assertEquals("13:00 · Members", groups[1].events.first { it.seq == 11L }.meta)
        assertEquals("11:05 · LZ HAWK", groups[2].events.single().meta)
        r.model.filterPerson("3")
        advanceUntilIdle()
        assertEquals(listOf(10L), r.model.state.value.history.groups.flatMap { it.events }.map { it.seq })
        assertEquals("Jess Reed", r.model.state.value.history.person.label)
        r.model.filterPerson(null)
        r.model.filterItem("lz-1")
        advanceUntilIdle()
        assertEquals(listOf(12L), r.model.state.value.history.groups.flatMap { it.events }.map { it.seq })
        assertEquals(listOf("Everyone", "You", "Sam Bell", "Jess Reed"), r.model.state.value.history.people.map { it.label })
    }

    @Test
    fun `a history that could not be loaded says so, and is tried again when the pack changes`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.panel.failEvents = true
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        assertEquals("The history could not be loaded. It is tried again when the pack changes.", r.model.state.value.history.emptyText)
        r.panel.failEvents = false
        r.panel.pages += PackEventPage(listOf(event(13, 2, "Sam Bell", "Sam Bell moved CH2.")), 13, false, 13)
        r.open(session(seq = 13))
        advanceUntilIdle()
        assertNull(r.model.state.value.history.emptyText)
    }

    @Test
    fun `a page that answers once the account or the pack has changed is not shown`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.panel.hold = CompletableDeferred()
        r.panel.pages += PackEventPage(listOf(event(12, 2, "Sam Bell", "Sam Bell moved CH2.")), 12, false, 12)
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        r.door.user.value = 7                                                                            // someone else signed in meanwhile
        r.panel.hold?.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.model.state.value.history.groups.none { g -> g.events.any { it.seq == 12L } })
    }

    @Test
    fun `another pack starts on its items with nothing filtered, and the one open from the start keeps what was chosen`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.model.selectTab(PackTab.HISTORY)
        r.model.filterPerson("2")
        advanceUntilIdle()
        assertEquals(PackTab.HISTORY, r.model.state.value.tab)
        r.door.open.value = PackState("q", PackStatus.LIVE, session(), listOf(lz), emptyList(), null)
        advanceUntilIdle()
        assertEquals(PackTab.ITEMS, r.model.state.value.tab)
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        assertEquals("Everyone", r.model.state.value.history.person.label)
    }

    @Test
    fun `the marker of the device's copy is not where new starts, the server's is, once it answers`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(seen = 4), status = PackStatus.OFFLINE)                                        // the copy kept on the device, an old marker
        r.model.setLooking(true)
        advanceUntilIdle()
        assertEquals(0, r.model.state.value.newCount)                                                  // nothing is known to be new yet
        assertTrue(r.model.state.value.unreachable)
        r.open(session(seen = 10))                                                                     // the server answers: they had looked as far as 10
        advanceUntilIdle()
        assertFalse(r.model.state.value.unreachable)
        assertFalse("measured from 10, not the copy's 4", r.model.state.value.groups[1].items[0].isNew)
        assertTrue(r.model.state.value.groups[0].items[0].isNew)
        assertEquals(1, r.model.state.value.newCount)
    }

    @Test
    fun `a history that failed is not asked for again for who is here, only when the pack moves on`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.panel.failEvents = true
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        val asked = r.panel.log.count { it.startsWith("events") }
        repeat(5) { n ->
            r.open(people = listOf(here(2, "Sam Bell", "lz-$n")))                                    // presence frames
            advanceUntilIdle()
        }
        assertEquals(asked, r.panel.log.count { it.startsWith("events") })
    }

    @Test
    fun `a page that says there is more, even later, is followed by the newest page`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(seq = 20))
        r.panel.pages += PackEventPage(listOf(event(20, 2, "Sam Bell", "Sam Bell moved CH2.")), 20, false, 20)
        r.model.selectTab(PackTab.HISTORY)
        advanceUntilIdle()
        r.panel.log.clear()
        r.panel.pages += PackEventPage(listOf(event(21, 2, "Sam Bell", "Sam Bell moved CH3.")), 521, true, 3000)
        r.panel.pages += PackEventPage(listOf(event(2999, 2, "Sam Bell", "Sam Bell moved CH4.")), 3000, false, 3000)
        r.open(session(seq = 3000))
        advanceUntilIdle()
        assertEquals(listOf("events p 20 500", "events p 2500 500"), r.panel.log)
    }

    @Test
    fun `a failure is said above the events still shown, and a filter whose person has gone keeps nothing back`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(seq = 12))
        r.panel.pages += PackEventPage(listOf(event(12, 3, "Jess Reed", "Jess Reed moved CH2."), event(11, 2, "Sam Bell", "Sam Bell moved CH1.")), 12, false, 12)
        r.model.selectTab(PackTab.HISTORY)
        r.model.filterPerson("3")
        advanceUntilIdle()
        assertEquals(listOf(12L), r.model.state.value.history.groups.flatMap { it.events }.map { it.seq })
        r.panel.failEvents = true
        r.open(session(seq = 13))
        advanceUntilIdle()
        assertEquals(HistoryUi.FAILED, r.model.state.value.history.failure)
        val gone = session(seq = 13).let { it.copy(members = JsonArray(it.members.take(2))) }       // Jess leaves the pack
        r.open(gone)
        advanceUntilIdle()
        assertEquals("Everyone", r.model.state.value.history.person.label)
        assertEquals(listOf(12L, 11L), r.model.state.value.history.groups.flatMap { it.events }.map { it.seq })
    }

    // -- What the panel does --------------------------------------------------------------------------------------------------------------

    @Test
    fun `opening an item shows it in its panel, and a point set says it is not drawn yet`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        r.model.open("rt-1")
        advanceUntilIdle()
        r.model.open("lz-1")
        advanceUntilIdle()
        r.model.open("ps-1")
        advanceUntilIdle()
        assertEquals(listOf("open rt-1", "open lz-1"), r.panel.log)
        assertEquals(listOf("route", "lz"), r.opened)
        assertEquals(listOf(PackPanelViewModel.POINT_SETS_LATER), r.notices)
        r.panel.opens = false
        r.model.open("lz-2")
        advanceUntilIdle()
        assertEquals("“LZ HAWK” could not be opened.", r.notices.last())
    }

    @Test
    fun `a name that will not do is said at the field, and one the pack will not take in a toast`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        val said = mutableListOf<String?>()
        r.panel.refuse = "bad_name"
        r.model.rename("lz-1", " ") { said += it }
        advanceUntilIdle()
        r.panel.refuse = "read_only"
        r.model.rename("lz-1", "LZ EAGLE") { said += it }
        advanceUntilIdle()
        r.panel.refuse = null
        r.model.rename("lz-1", "LZ EAGLE") { said += it }
        advanceUntilIdle()
        assertEquals(listOf(PackPanelViewModel.NAME_REFUSED, null, null), said)
        assertEquals(listOf("That change was not saved to the pack: you can only look at this pack, so changes are not saved to it."), r.notices)
        r.panel.refuse = "loading"
        r.model.remove("lz-1")
        advanceUntilIdle()
        assertEquals("That change was not saved to the pack: the pack is still opening.", r.notices.last())
    }

    @Test
    fun `one thing at a time, and one asked while another runs is said not to be done`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        val said = mutableListOf<String?>()
        r.model.open("lz-1")
        r.model.rename("lz-1", "LZ EAGLE") { said += it }                                              // the open has not run yet
        r.model.remove("lz-1")
        r.model.closeHere("lz-1")
        r.model.open("lz-2")
        advanceUntilIdle()
        assertEquals(listOf("open lz-1"), r.panel.log)
        assertEquals(listOf(PackPanelViewModel.STILL_WORKING), said)
        assertEquals(List(3) { PackPanelViewModel.STILL_WORKING }, r.notices)
    }

    @Test
    fun `an item that cannot be opened, as what is open cannot be saved, is said, and never takes the app down`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        r.panel.openFails = true
        r.model.open("lz-2")
        advanceUntilIdle()
        assertEquals("The last change to what is open could not be saved, so “LZ HAWK” was not opened.", r.notices.single())
        assertEquals(emptyList<String>(), r.opened)
        r.panel.openFails = false
        r.model.open("lz-2")                                                                             // and the panel still works
        advanceUntilIdle()
        assertEquals(listOf("lz"), r.opened)
    }

    @Test
    fun `an item that leaves the editor because its pack went is not said to be removed`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        val kept = KeptOutcome.Saved("k", "lz", "lib-1", "LZ HAWK (my edits)")
        r.door.open.value = PackState("p", PackStatus.GONE, session(), emptyList(), emptyList(), null)
        r.panel.notices.emit(PackNotice.ItemRemoved("p", "pack:p:lz-2", "LZ HAWK", kept))
        r.panel.notices.emit(PackNotice.ItemRemoved("p", "pack:p:lz-1", "LZ IBIS", null))
        advanceUntilIdle()
        assertEquals(listOf("Your changes to “LZ HAWK” are in your Library as “LZ HAWK (my edits)”."), r.notices)
    }

    @Test
    fun `what happens to the pack and the items open here is said in words`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        val kept = KeptOutcome.Saved("k", "lz", "lib-1", "LZ HAWK (my edits)")
        r.panel.notices.emit(PackNotice.ItemRemoved("p", "pack:p:lz-2", "LZ HAWK", kept))
        r.panel.notices.emit(PackNotice.Dropped("p", 2, setOf("pack_finished")))
        r.panel.notices.emit(PackNotice.Kept("p-old", "OP EAGLE", listOf(kept), setOf("read_only")))
        r.panel.notices.emit(PackNotice.Gone("p", "not_found"))
        r.panel.notices.emit(PackNotice.OwnSkipped("p", 1))
        advanceUntilIdle()
        assertEquals(
            listOf(
                "“LZ HAWK” was removed from the pack. Your changes to it are in your Library as “LZ HAWK (my edits)”.",
                "2 of your changes were not taken by OP DK: the pack was finished. Keep your version from the Pack panel; it goes to your Library when you leave the pack anyway.",
                "A change to OP EAGLE could not be saved: you can only look at it now. It is in your Library as “LZ HAWK (my edits)”.",
                "One of your changes changed nothing, as what it changed was already gone. The history says which.",
            ),
            r.notices,
        )
    }

    // -- The edits the pack would not take ---------------------------------------------------------------------------------------------------

    private fun dropped(vararg reasons: String) = reasons.mapIndexed { i, reason -> DroppedOp(buildJsonObject { put("client_op_id", "op-$i"); put("item", "lz-2") }, reason) }

    @Test
    fun `the edits the pack would not take are counted, with why, and kept from the panel with the Library to open`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(dropped = dropped("pack_finished", "pack_finished")))
        advanceUntilIdle()
        assertEquals(2, r.model.state.value.dropped)
        assertEquals(setOf("pack_finished"), r.model.state.value.droppedReasons)
        val saved = KeptOutcome.Saved("k", "lz", "lib-1", "LZ HAWK (my edits)")
        r.panel.keepAnswer = listOf(PackNotice.Kept("p", "OP DK", listOf(saved), setOf("pack_finished")))
        r.panel.keepHold = CompletableDeferred()
        r.model.keepDropped()
        advanceUntilIdle()
        assertTrue(r.model.state.value.keepingDropped)                                                   // "Saving…" meanwhile
        r.model.keepDropped()
        assertEquals(listOf(PackPanelViewModel.STILL_WORKING), r.notices)                                 // one at a time
        r.panel.keepHold!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.model.state.value.keepingDropped)
        assertEquals(listOf("keep p"), r.panel.log.filter { it.startsWith("keep") })
        assertEquals(listOf(CopyNotice("Saved “LZ HAWK (my edits)” to your Library", library = "lz")), r.kept)
    }

    @Test
    fun `a keep that did nothing says so and the offer stays, and what had nothing to keep is said in words`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(dropped = dropped("read_only")))
        advanceUntilIdle()
        r.model.keepDropped()
        advanceUntilIdle()
        assertEquals(listOf(PackPanelViewModel.KEEP_FAILED), r.notices)
        assertTrue(r.kept.isEmpty())
        r.panel.keepAnswer = listOf(PackNotice.NothingToKeep("p", "k", "empty_point_set"))
        r.panel.keepHold = CompletableDeferred()
        r.model.keepDropped()
        advanceUntilIdle()
        r.open(session(dropped = emptyList()))                                                          // the engine let it go
        r.panel.keepHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("A change could not be kept: a point set needs at least one point.", r.notices.last())
    }

    @Test
    fun `a keep with nothing left to keep says why rather than that it failed, and one stopped part way says the rest waits`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(dropped = dropped("pack_finished")))
        advanceUntilIdle()
        r.panel.keepAnswer = emptyList()
        r.model.keepDropped()
        advanceUntilIdle()
        assertEquals(listOf(PackPanelViewModel.NOTHING_LEFT), r.notices)
        r.panel.keepAnswer = listOf(PackNotice.Kept("p", "OP DK", listOf(KeptOutcome.Saved("k", "lz", "lib-1", "LZ HAWK (my edits)")), emptySet()))
        r.model.keepDropped()                                                                           // one item kept, another still waiting
        advanceUntilIdle()
        assertEquals("Saved “LZ HAWK (my edits)” to your Library", r.kept.single().words)
        assertEquals(PackPanelViewModel.KEEP_REST_FAILED, r.notices.last())
    }

    @Test
    fun `a keep that did nothing once the pack was left is not said to have failed, as the close keeps them`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(dropped = dropped("pack_finished")))
        advanceUntilIdle()
        r.panel.keepHold = CompletableDeferred()
        r.model.keepDropped()
        advanceUntilIdle()
        r.door.open.value = null
        r.panel.keepHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.notices.isEmpty())
    }

    @Test
    fun `the panel's actions reach the view model`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(dropped = dropped("pack_finished")))
        advanceUntilIdle()
        val actions = actionsOf(r.model)
        actions.keepDropped()
        advanceUntilIdle()
        actions.discardDropped("p", setOf("op-0"))
        advanceUntilIdle()
        assertEquals(listOf("keep p", "discard p [op-0]"), r.panel.log.filter { it.startsWith("keep") || it.startsWith("discard") })
    }

    @Test
    fun `nothing is kept when nothing waits, and what is kept is never said to another account`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        r.model.keepDropped()
        advanceUntilIdle()
        assertTrue(r.panel.log.none { it.startsWith("keep") })
        r.open(session(dropped = dropped("pack_finished")))
        r.panel.keepAnswer = listOf(PackNotice.Kept("p", "OP DK", listOf(KeptOutcome.Saved("k", "lz", "lib-1", "LZ HAWK (my edits)")), emptySet()))
        r.panel.keepHold = CompletableDeferred()
        advanceUntilIdle()
        r.model.keepDropped()
        advanceUntilIdle()
        r.door.user.value = 2
        r.panel.keepHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.kept.isEmpty())
        assertTrue(r.notices.isEmpty())
    }

    @Test
    fun `the edits the pack would not take are discarded only for the pack asked about, only those shown, and what came of it is said`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(session(dropped = dropped("pack_finished", "pack_finished")))
        advanceUntilIdle()
        assertEquals(listOf("op-0", "op-1"), r.model.state.value.droppedIds)
        r.model.discardDropped("q", setOf("op-0"))
        r.model.discardDropped("p", emptySet())
        advanceUntilIdle()
        assertTrue(r.panel.log.none { it.startsWith("discard") })
        r.model.discardDropped("p", setOf("op-0", "op-1"))
        advanceUntilIdle()
        assertEquals(listOf("discard p [op-0, op-1]"), r.panel.log.filter { it.startsWith("discard") })
        assertEquals(listOf(PackPanelViewModel.DISCARDED), r.notices)
        r.panel.discarded = 0
        r.model.discardDropped("p", setOf("op-0"))
        advanceUntilIdle()
        assertEquals(PackPanelViewModel.NOTHING_DISCARDED, r.notices.last())
    }

    @Test
    fun `the notice says why in the web's words, or the reason itself when the web has none for it`() {
        assertEquals(
            "One of your changes was not taken." to "The pack was finished, or you can only view it now. Keep your version so the work is not lost.",
            PackPanelWords.dropped(1, setOf("pack_finished")),
        )
        assertEquals("1,200 of your changes were not taken.", PackPanelWords.dropped(1200, setOf("read_only")).first)
        assertEquals("It is no longer available to you. Keep your version so the work is not lost.", PackPanelWords.dropped(2, setOf("gone")).second)
        assertEquals(
            "An item would be larger than a pack can hold (5 MB). Keep your version so the work is not lost.",
            PackPanelWords.dropped(1, setOf("item_too_large")).second,
        )
        assertEquals("The pack would not take them. Keep your version so the work is not lost.", PackPanelWords.dropped(2, setOf("http_400")).second)
        assertEquals("The pack would not take it. Keep your version so the work is not lost.", PackPanelWords.dropped(1, setOf("http_400")).second)
        assertEquals(
            "Saved “LZ HAWK (my edits)”, “OP DK INGRESS (my edits)” to your Library",
            PackPanelWords.keptYours(listOf(KeptOutcome.Saved("a", "lz", "1", "LZ HAWK (my edits)"), KeptOutcome.Saved("b", "route", "2", "OP DK INGRESS (my edits)"))),
        )
    }

    // -- Words --------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `times are the design's, in the person's zone, and names are short`() {
        val chicago = ZoneId.of("America/Chicago")
        assertEquals("just now", PackPanelWords.whenText("2026-10-12T13:59:31", utc, now))
        assertEquals("1 min ago", PackPanelWords.whenText("2026-10-12T13:59:29", utc, now))           // Math.round of the minutes
        assertEquals("59 min ago", PackPanelWords.whenText("2026-10-12T13:01:00", utc, now))
        assertEquals("07:30", PackPanelWords.whenText("2026-10-12T12:30:00", chicago, now))           // 12:30 UTC is 07:30 in Chicago, today there
        assertEquals("Oct 11", PackPanelWords.whenText("2026-10-12T04:00:00", chicago, now))          // the evening before, there
        assertEquals("Dec 31, 2025", PackPanelWords.whenText("2025-12-31T12:00:00Z", utc, now))
        assertEquals("", PackPanelWords.whenText("soon", utc, now))
        assertEquals("Yesterday", PackPanelWords.dayLabel(Instant.parse("2026-10-11T23:00:00Z"), utc, now))
        assertEquals("Oct 10", PackPanelWords.dayLabel(Instant.parse("2026-10-10T23:00:00Z"), utc, now))
        assertEquals("Sam B.", PackPanelWords.shortName("  Sam   Bell "))
        assertEquals("Cher", PackPanelWords.shortName("Cher"))
        assertEquals("Someone", PackPanelWords.shortName(" "))
        assertEquals("Ann 𝐀.", PackPanelWords.shortName("Ann 𝐀lpha"))                 // never half a character
    }
}
