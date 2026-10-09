package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.HAWK
import app.ezpztac.data.PackEditorRig.Companion.ITEM
import app.ezpztac.data.PackEditorRig.Companion.LOCAL
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.SAM
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.assertJson
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.data.PackEditorRig.Companion.packTest
import app.ezpztac.missionpacks.InMemoryPackStore
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackStore
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Making, opening and leaving a mission pack's items in the editors, as the pack screens will ask for them. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackWorkspaceTest {
    // A regression that loops without end fails here rather than holding the build: runTest's own timeout cannot fire on a busy thread. A loop
    // of sends or edits is cut short sooner by the rig ([PackEditorRig.MAX_BATCHES], [PackEditorRig.MAX_WRITES]).
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private val target = DiagramTarget(34.6, -84.2, "16S GC 19594 66453")

    private fun PackEditorRig.created(): JsonObject = sent().single().single()

    @Test
    fun `a new LZ-PZ is made in the open pack and opened, named for the pack's count, with its sentence, and opening it sends nothing more`() = packTest { r ->
        r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
        r.start()
        r.openPack()
        val made = checkNotNull(r.workspace.createLz(target, null))
        runCurrent()
        val ref = checkNotNull(PackRef.parse(made))
        assertEquals(PACK, ref.pack)
        assertTrue(ref.item.startsWith("lz-"))
        assertEquals(made, r.diagrams.active.value!!.id)
        assertEquals("LZ/PZ 2", r.diagrams.active.value!!.name)
        val op = r.created()
        assertEquals(listOf("item.create", ref.item, "lz", "LZ/PZ 2"), listOf("type", "item", "kind", "name").map { op.getValue(it).jsonPrimitive.content })
        assertEquals("Sam B. added the LZ/PZ \"LZ/PZ 2\".", op.getValue("summary").jsonPrimitive.content)
        assertNull("this person's own fields are not in it", op.getValue("data").jsonObject["view"])
        assertEquals(34.6, r.serverData(ref.item).getValue("target").jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        r.advance(10_000)
        assertEquals(1, r.sent().size)
    }

    @Test
    fun `a new LZ-PZ's first change goes to the pack`() = packTest { r ->
        r.pack()
        r.start()
        r.openPack()
        val made = checkNotNull(r.workspace.createLz(target, "  LZ CROW "))
        assertEquals("LZ CROW", r.diagrams.active.value!!.name)
        r.diagrams.edit("Rename") { DiagramOps.setName(it, "LZ RAVEN") }
        r.advance(STILL)
        assertEquals(listOf("item.create", "item.rename"), r.sent().map { it.single().getValue("type").jsonPrimitive.content })
        assertEquals("LZ RAVEN", r.server.name(PACK, checkNotNull(PackRef.parse(made)).item))
    }

    @Test
    fun `a new route set is made in the open pack and opened in the route session`() = packTest { r ->
        r.pack()
        r.start()
        r.openPack()
        val made = checkNotNull(r.workspace.createRouteSet("OP DK EGRESS"))
        runCurrent()
        val item = checkNotNull(PackRef.parse(made)).item
        assertTrue(item.startsWith("rt-"))
        assertEquals(made, r.routes.active.value!!.id)
        val op = r.created()
        assertEquals("Sam B. added the route set \"OP DK EGRESS\".", op.getValue("summary").jsonPrimitive.content)
        assertJson(json("""{"version": 1, "routes": []}"""), r.serverData(item), "the new set")
        assertEquals("OP DK EGRESS", r.server.name(PACK, item))
        r.advance(10_000)
        assertEquals(1, r.sent().size)
    }

    @Test
    fun `a set made with no name is called for the pack's count of sets`() = packTest { r ->
        r.pack()
        r.start()
        r.openPack()
        r.workspace.createRouteSet(null)
        r.workspace.createRouteSet(" ")
        runCurrent()
        assertEquals(listOf("ROUTES 1", "ROUTES 2"), r.sent().flatten().map { it.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `nothing is made in a pack that takes nothing from this person, and they are told why`() = packTest { r ->
        r.pack()
        r.start()
        r.openPack()
        r.server.setRole(PACK, SAM.id, "viewer", COLIN.id)
        r.engine.refresh()
        runCurrent()
        assertNull(r.workspace.createLz(target, null))
        assertNull(r.workspace.createRouteSet(null))
        runCurrent()
        assertEquals(
            listOf(PackNotice.Refused(PACK, null, "read_only"), PackNotice.Refused(PACK, null, "read_only")),
            r.notices.filterIsInstance<PackNotice.Refused>(),
        )
        assertNull(r.diagrams.active.value)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    @Test
    fun `nothing is made with no pack open`() = packTest { r ->
        r.start()
        assertNull(r.workspace.createLz(target, null))
        assertNull(r.workspace.createRouteSet(null))
        assertFalse(r.workspace.openItem(ITEM))
    }

    @Test
    fun `an item opens in its own editor, and one with no editor, or not in the pack, opens nothing`() = packTest { r ->
        val points = json("""{"x": [{"id": "pt-0", "name": "KGVL", "lat": 34.27, "lon": -83.83}]}""").getValue("x")
        r.pack(
            PACK, "OP DK",
            create(ITEM, "lz", "LZ HAWK", HAWK),
            create("rt-1", "route", "OP DK INGRESS", json("""{"version": 1, "routes": []}""")),
            create("ps-1", "pointset", "NORTH GA", points),
        )
        r.start()
        r.openPack()
        assertTrue(r.workspace.openItem(ITEM))
        assertTrue(r.workspace.openItem("rt-1"))
        assertEquals(LOCAL, r.diagrams.active.value!!.id)
        assertEquals(PackRef.localId(PACK, "rt-1"), r.routes.active.value!!.id)
        assertFalse(r.workspace.openItem("ps-1"))
        assertFalse(r.workspace.openItem("lz-nope"))
    }

    @Test
    fun `opening an item already open keeps its undo`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move") { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(101), json("""{"lat": 34.4}""")) }
        assertTrue(r.workspace.openItem(ITEM))
        assertEquals(1, r.diagrams.undoDepth.value)
    }

    @Test
    fun `switching to another pack sends the last change to the pack being left, while it still takes it, and closes its items`() = packTest { r ->
        r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
        r.pack("p-2", "OP EAGLE", create("lz-9", "lz", "LZ CROW", HAWK))
        r.start()
        r.openPack()
        r.openItem(ITEM)
        r.diagrams.edit("Move") { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(101), json("""{"lat": 34.4}""")) }
        r.openPack("p-2")
        r.advance(10_000)
        assertNull("the item of the pack left is closed", r.diagrams.active.value)
        assertEquals("p-2", r.engine.open.value!!.uuid)
        assertEquals(34.4, r.serverData().getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        assertTrue("nothing refused", r.notices.none { it is PackNotice.Refused || it is PackNotice.Dropped })
    }

    /** The device's copy, whose next write fails once [failNext] is set, as a full disk fails it. */
    private class Failing(private val inner: InMemoryPackStore = InMemoryPackStore()) : PackStore by inner {
        var failNext = false

        override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
            if (failNext) {
                failNext = false
                throw IOException("The device is full.")
            }
            inner.write(before, after, me)
        }
    }

    @Test
    fun `a save that fails as a pack is left still closes both editors and opens the next pack, then says so`() = runTest {
        val store = Failing()
        val r = PackEditorRig(this, store)
        try {
            r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK), create("rt-1", "route", "OP DK INGRESS", json("""{"version": 1, "routes": []}""")))
            r.pack("p-2", "OP EAGLE")
            r.start()
            r.openPack()
            r.openItem(ITEM)
            r.openItem("rt-1")
            r.diagrams.edit("Move") { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(101), json("""{"lat": 34.4}""")) }
            store.failNext = true
            val failed = runCatching { r.workspace.openPack("p-2") }.exceptionOrNull()
            runCurrent()
            assertTrue(failed is IOException)
            assertNull(r.diagrams.active.value)
            assertNull(r.routes.active.value)
            assertEquals("p-2", r.engine.open.value!!.uuid)
        } finally {
            r.close()
        }
    }

    @Test
    fun `closing the pack sends the last change of its items first, and closes them`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move") { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(101), json("""{"lat": 34.4}""")) }
        r.workspace.closePack()
        r.advance(10_000)
        assertNull(r.diagrams.active.value)
        assertNull(r.engine.open.value)
        assertEquals(34.4, r.serverData().getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a pack deleted while it is open closes its items in the editors, keeping a change that had not gone`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move") { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(101), json("""{"lat": 34.4}""")) }
        r.server.deletePack(PACK)
        r.engine.refresh()
        runCurrent()
        assertEquals(PackStatus.GONE, r.engine.open.value!!.status)
        assertNull(r.diagrams.active.value)
        val kept = r.keeper.records.single().version
        assertEquals("LZ HAWK (my edits)", kept.name)
        val removed = r.notices.filterIsInstance<PackNotice.ItemRemoved>().single()
        assertEquals(LOCAL, removed.localId)
        assertNotNull(removed.kept)
        assertTrue(r.notices.any { it is PackNotice.Gone })
    }

    @Test
    fun `the LZ-PZ open in the editor is what this person has open, for everyone else's presence`() = runTest {
        val r = PackEditorRig(this, live = "ws://live.test/live")
        try {
            r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
            r.start()
            r.openPack()
            val socket = r.server.sockets.single()
            socket.welcome(r.server.headSeq(PACK))
            runCurrent()
            r.openItem(ITEM)
            r.advance(200)
            r.diagrams.close()
            r.advance(200)
            assertEquals(listOf("lz-1", null), socket.presenceSent.map { it?.get("item")?.jsonPrimitive?.content })
        } finally {
            r.close()
        }
    }

    @Test
    fun `the LZ-PZ open in the editor is told again to the pack's new client after a pause`() = runTest {
        val r = PackEditorRig(this, live = "ws://live.test/live")
        try {
            r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
            r.start()
            r.openPack()
            r.server.sockets.single().welcome(r.server.headSeq(PACK))
            runCurrent()
            r.openItem(ITEM)
            r.advance(200)
            r.server.setFeature(SAM.id, false)                      // paused for the account's sake
            r.engine.refresh()
            runCurrent()
            assertEquals(PackStatus.PAUSED, r.engine.open.value!!.status)
            r.server.setFeature(SAM.id, true)
            r.engine.enable(SAM)                                    // asked again, by a new client with a stream of its own
            runCurrent()
            val again = r.server.sockets.last()
            again.welcome(r.server.headSeq(PACK))
            r.advance(200)
            assertEquals(2, r.server.sockets.size)
            assertEquals("lz-1", again.presenceSent.firstOrNull()?.get("item")?.jsonPrimitive?.content)
        } finally {
            r.close()
        }
    }
}
