package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.HAWK
import app.ezpztac.data.PackEditorRig.Companion.ITEM
import app.ezpztac.data.PackEditorRig.Companion.LOCAL
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.SAM
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.array
import app.ezpztac.data.PackEditorRig.Companion.assertJson
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.data.PackEditorRig.Companion.packTest
import app.ezpztac.missionpacks.FakePackServer
import app.ezpztac.missionpacks.InMemoryPackStore
import app.ezpztac.missionpacks.LzPackKind
import app.ezpztac.missionpacks.PackKind
import app.ezpztac.missionpacks.PackLz
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PackStore
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramJson
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * A mission pack's LZ/PZ edited in the diagram session every editor uses, as Sam B. on his device while Colin M. changes the same pack from his:
 * what is sent and when, someone else's change taken in without breaking undo, what each person keeps to themselves, and what happens when the
 * pack will not take a change or no longer has the item. The rules are the web's usePackItemSync and usePackLz (docs/MISSION_PACKS.md §5a).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackEditorTest {
    // A regression that loops without end fails here rather than holding the build: runTest's own timeout cannot fire on a busy thread. A loop
    // of sends or edits is cut short sooner by the rig ([PackEditorRig.MAX_BATCHES], [PackEditorRig.MAX_WRITES]).
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private fun move(id: Int, patch: String): (Diagram) -> Diagram = { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(id), json(patch)) }

    private fun PackEditorRig.doc(): Diagram = checkNotNull(diagrams.active.value)

    private fun PackEditorRig.helicopter(index: Int): JsonObject = doc().graphics.helicopters[index].jsonObject

    private fun PackEditorRig.serverHelicopter(index: Int): JsonObject =
        serverData().getValue("graphics").jsonObject.getValue("helicopters").jsonArray[index].jsonObject

    private fun patchAt(id: Int, value: String, summary: String): JsonObject = json(
        """{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": $id}], "value": $value, "summary": "$summary"}""",
    )

    // -- Sending ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `opening a pack's LZ-PZ sends nothing, and the editor has it under the pack's id`() = packTest { r ->
        r.opened()
        assertEquals(LOCAL, r.doc().id)
        assertEquals("LZ HAWK", r.doc().name)
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    @Test
    fun `a drag is one change, sent once it has been still, at the aircraft with a sentence for the history`() = packTest { r ->
        r.opened()
        listOf(34.502, 34.503, 34.504).forEach { lat ->
            r.diagrams.edit("Move", coalesce = "drag", change = move(102, """{"lat": $lat, "lon": -84.101}"""))
            r.advance(100)
        }
        assertEquals("nothing goes while the finger moves", emptyList<List<JsonObject>>(), r.sent())
        r.advance(STILL)
        assertEquals(1, r.sent().size)
        assertJson(JsonArray(listOf(patchAt(102, """{"lat": 34.504, "lon": -84.101}""", "Sam B. moved Chalk 2 on LZ HAWK."))), JsonArray(r.sent().single()), "the change")
        assertEquals(34.504, r.serverHelicopter(1).getValue("lat").jsonPrimitive.double, 0.0)
        r.advance(10_000)
        assertEquals("the pack's echo of it sends nothing back", 1, r.sent().size)
        assertEquals("nothing of the pack's went to the library", emptyList<String>(), r.library.names(RecordKind.LZ))
    }

    @Test
    fun `a change waiting when the app stops is sent at once by the flush`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.49}"""))
        r.diagrams.flush()
        runCurrent()
        assertEquals(1, r.sent().size)
        assertEquals(34.49, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a change made just before the LZ-PZ is closed still goes`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.4}"""))
        r.diagrams.close()
        runCurrent()
        assertNull(r.diagrams.active.value)
        assertJson(JsonArray(listOf(patchAt(101, """{"lat": 34.4}""", "Sam B. moved Chalk 1 on LZ HAWK."))), JsonArray(r.sent().single()), "the change")
    }

    @Test
    fun `a base map is this person's own, so nothing is sent, and it is kept on the device for the next opening`() = packTest { r ->
        r.opened()
        r.diagrams.setQuietly { it.copy(view = it.view.copy(mapStyle = "vfr-sectional", showHeatmap = true)) }
        r.advance(STILL)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertEquals("vfr-sectional", r.packStore.own(PACK, ITEM)!!.getValue("view").jsonObject.getValue("mapStyle").jsonPrimitive.content)
        r.diagrams.close()
        r.openItem(ITEM)
        assertEquals("vfr-sectional", r.doc().view.mapStyle)
        assertTrue(r.doc().view.showHeatmap)
    }

    @Test
    fun `a rename goes as the item's rename, and someone else's name is taken`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Rename") { DiagramOps.setName(it, "LZ EAGLE") }
        r.advance(STILL)
        assertJson(
            array("""[[{"type": "item.rename", "item": "lz-1", "name": "LZ EAGLE", "summary": "Sam B. renamed \"LZ HAWK\" to \"LZ EAGLE\"."}]]"""),
            JsonArray(r.sent().map(::JsonArray)), "what was sent",
        )
        r.theirs(json("""{"type": "item.rename", "item": "lz-1", "name": "LZ OSPREY"}"""))
        assertEquals("LZ OSPREY", r.doc().name)
        r.advance(STILL)
        assertEquals(1, r.sent().size)
    }

    // -- Someone else's change -----------------------------------------------------------------------------------------------------

    @Test
    fun `someone else's change appears with this person's view kept, and undo takes back only their own step, sending only its inverse`() = packTest { r ->
        r.opened()
        r.diagrams.setQuietly { it.copy(view = it.view.copy(mapStyle = "topo")) }
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.499}"""))
        r.advance(STILL)
        assertEquals(1, r.sent().size)

        r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["flightData"], "value": {"landing_hdg": "270°"}}"""))
        assertEquals("270°", r.doc().flightData.getValue("landing_hdg").jsonPrimitive.content)
        assertEquals("topo", r.doc().view.mapStyle)
        assertEquals(34.499, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals("taken in quietly: not a step to undo", 1, r.diagrams.undoDepth.value)
        r.advance(STILL)
        assertEquals("theirs is not sent back", 1, r.sent().size)

        assertEquals("Move", r.diagrams.undo())
        assertEquals(34.5, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals("their change stays", "270°", r.doc().flightData.getValue("landing_hdg").jsonPrimitive.content)
        r.advance(STILL)
        assertJson(JsonArray(listOf(patchAt(101, """{"lat": 34.5}""", "Sam B. moved Chalk 1 on LZ HAWK."))), JsonArray(r.sent()[1]), "the undo")
        assertEquals(2, r.sent().size)
        assertEquals("270°", r.serverData().getValue("flightData").jsonObject.getValue("landing_hdg").jsonPrimitive.content)
        assertEquals(34.5, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a change waiting here is sent before someone else's is taken in, so both stand`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.499}"""))
        r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 102}], "value": {"heading": 270}}"""))
        assertEquals("ours went at once, without waiting out the pause", 1, r.sent().size)
        assertJson(JsonArray(listOf(patchAt(101, """{"lat": 34.499}""", "Sam B. moved Chalk 1 on LZ HAWK."))), JsonArray(r.sent().single()), "ours")
        assertEquals(34.499, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(270, r.helicopter(1).getValue("heading").jsonPrimitive.int)
        r.advance(10_000)
        assertEquals(1, r.sent().size)
        assertEquals(34.499, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(270, r.serverHelicopter(1).getValue("heading").jsonPrimitive.int)
    }

    @Test
    fun `the rest of a drag after someone else's change arrived in the middle of it goes as one more change`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", coalesce = "drag", change = move(101, """{"lat": 34.4991}"""))
        r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["flightData"], "value": {"landing_hdg": "180°"}}"""))
        listOf(34.4992, 34.4993, 34.4994).forEach { lat ->
            r.advance(50)
            r.diagrams.edit("Move", coalesce = "drag", change = move(101, """{"lat": $lat}"""))
        }
        r.advance(STILL)
        assertEquals(
            listOf(listOf(34.4991), listOf(34.4994)),
            r.sent().map { batch -> batch.map { it.getValue("value").jsonObject.getValue("lat").jsonPrimitive.double } },
        )
        assertEquals("180°", r.doc().flightData.getValue("landing_hdg").jsonPrimitive.content)
        assertEquals("still one step to undo", 1, r.diagrams.undoDepth.value)
        r.diagrams.undo()
        assertEquals(34.5, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals("180°", r.doc().flightData.getValue("landing_hdg").jsonPrimitive.content)
    }

    @Test
    fun `on one field the change that reaches the pack later stands, here ours, sent after theirs arrived`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Turn", change = move(101, """{"heading": 45}"""))
        r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"heading": 300}}"""))
        r.advance(10_000)
        assertEquals(45, r.serverHelicopter(0).getValue("heading").jsonPrimitive.int)
        assertEquals(45, r.helicopter(0).getValue("heading").jsonPrimitive.int)
    }

    @Test
    fun `on one field the change that reaches the pack later stands, here theirs, made after ours was sent`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Turn", change = move(101, """{"heading": 45}"""))
        r.advance(STILL)
        r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"heading": 300}}"""))
        r.advance(10_000)
        assertEquals(300, r.serverHelicopter(0).getValue("heading").jsonPrimitive.int)
        assertEquals(300, r.helicopter(0).getValue("heading").jsonPrimitive.int)
        assertEquals(1, r.sent().size)
    }

    // -- What is each person's own, and what this version cannot hold -----------------------------------------------------------

    @Test
    fun `an item in an older shape is brought to today's with its first change, and nothing is sent for opening it`() = packTest { r ->
        val legacy = json(
            """{"targetLocation": [34.5, -84.1], "gridInput": "16S GC 28864 55349", "status": "analyzed",
                "analysis": {"detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]]},
                "helicopters": [{"id": 7, "lat": 34.5, "lon": -84.1}], "savedId": 12, "dirty": false, "view": {"mapStyle": "topo"}}""",
        )
        r.opened(ITEM, create(ITEM, "lz", "LZ OLD", legacy))
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        r.diagrams.edit("Move", change = move(7, """{"lat": 34.6}"""))
        r.advance(STILL)
        val batch = r.sent().single()
        assertTrue("the reshape comes first, in the same send", batch.size > 1)
        assertEquals(setOf("Sam B. moved Chalk 1 on LZ OLD."), batch.map { it.getValue("summary").jsonPrimitive.content }.toSet())
        // Every operation applied on the server, and the item is in today's shape there.
        assertTrue(r.server.log(PACK).filter { it["client_op_id"]?.jsonPrimitive?.content?.startsWith("op-") == true }.all { it.getValue("status").jsonPrimitive.content == "applied" })
        assertEquals(34.6, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(34.5, r.serverData().getValue("target").jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        r.diagrams.edit("Move", change = move(7, """{"lat": 34.7}"""))
        r.advance(STILL)
        assertEquals("only the change, the second time", 1, r.sent()[1].size)
    }

    @Test
    fun `fields a newer version wrote ride through changes both ways, and nothing is ever sent for them`() = packTest { r ->
        val graphics = JsonObject(HAWK.getValue("graphics").jsonObject + ("futureGraphics" to array("""[{"id": "f-1"}]""")))
        val newer = JsonObject(HAWK + mapOf("weather" to json("""{"wind": "270/12"}"""), "graphics" to graphics))
        r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", newer))
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.499}"""))
        r.advance(STILL)
        r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["weather"], "value": {"wind": "300/20"}}"""))
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.498}"""))
        r.advance(STILL)
        assertJson(
            JsonArray(
                listOf(
                    JsonArray(listOf(patchAt(101, """{"lat": 34.499}""", "Sam B. moved Chalk 1 on LZ HAWK."))),
                    JsonArray(listOf(patchAt(101, """{"lat": 34.498}""", "Sam B. moved Chalk 1 on LZ HAWK."))),
                ),
            ),
            JsonArray(r.sent().map(::JsonArray)), "what was sent",
        )
        assertJson(json("""{"wind": "300/20"}"""), r.serverData()["weather"], "their change to what this version cannot hold")
        assertJson(array("""[{"id": "f-1"}]"""), r.serverData().getValue("graphics").jsonObject["futureGraphics"], "what nobody changed")
    }

    // LZ HAWK with a landing doghouse whose heading its flight data does not have (an older client's): the heading follows the doghouse.
    private fun headless(): JsonObject {
        val doghouse = json(
            """{"id": "dh-rp", "role": "landing", "lat": 34.5, "lon": -84.103, "id_val": "[RP1]", "heading": "270°", "time": "00+00", "dist": "0", "airspeed": "0"}""",
        )
        val graphics = JsonObject(HAWK.getValue("graphics").jsonObject + ("doghouses" to JsonArray(listOf(doghouse))))
        return JsonObject(HAWK + mapOf("flightData" to JsonObject(emptyMap()), "graphics" to graphics))
    }

    @Test
    fun `headings the doghouses give that the item lacks are owed, and sent as the web's doghouse effect sends them`() = packTest { r ->
        r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", headless()))
        assertEquals("270°", r.doc().flightData.getValue("landing_hdg").jsonPrimitive.content)
        r.advance(STILL)
        val sent = r.sent().single().single()
        assertEquals("Sam B. changed the landing heading to 270° on LZ HAWK.", sent.getValue("summary").jsonPrimitive.content)
        assertEquals("270°", r.serverData().getValue("flightData").jsonObject.getValue("landing_hdg").jsonPrimitive.content)
        r.advance(10_000)
        assertEquals(1, r.sent().size)
    }

    // -- What the pack will not take -----------------------------------------------------------------------------------------------

    @Test
    fun `made a viewer, a change is put back to the pack's version and the person told, and nothing is sent`() = packTest { r ->
        r.opened()
        r.server.setRole(PACK, SAM.id, "viewer", COLIN.id)
        r.engine.refresh()
        runCurrent()
        assertTrue(r.engine.open.value!!.readOnly)
        r.diagrams.edit("Move", change = move(101, """{"lat": 1}"""))
        assertEquals(1.0, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        r.advance(STILL)
        assertEquals(34.5, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertEquals(listOf(PackNotice.Refused(PACK, LOCAL, "read_only")), r.notices.filterIsInstance<PackNotice.Refused>())
        r.advance(10_000)
        assertEquals("told once", 1, r.notices.filterIsInstance<PackNotice.Refused>().size)
    }

    @Test
    fun `a change still waiting here when the pack is finished is put back, and the person told`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        r.server.finish(PACK, COLIN.id)
        r.engine.refresh()
        runCurrent()
        assertEquals(34.5, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(listOf(PackNotice.Refused(PACK, LOCAL, "read_only")), r.notices.filterIsInstance<PackNotice.Refused>())
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertEquals(1, r.notices.filterIsInstance<PackNotice.Refused>().size)
    }

    @Test
    fun `opening a finished pack's LZ-PZ sends nothing for the headings its doghouses give, and tells the person nothing`() = packTest { r ->
        r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", headless()))
        r.server.finish(PACK, COLIN.id)
        r.start()
        r.openPack()
        r.openItem(ITEM)
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertTrue(r.notices.none { it is PackNotice.Refused })
        assertNull("the pack's version, which has none", r.doc().flightData["landing_hdg"])
    }

    @Test
    fun `a pack finished while a change was on its way shows the pack's version, and the engine keeps the change as NAME (my edits)`() =
        packTest { r ->
            r.opened()
            var finished = false
            r.server.onCall = { call ->
                if (call == FakePackServer.Call.OPS && !finished) {
                    finished = true
                    r.server.finish(PACK, COLIN.id)
                }
            }
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.4}"""))
            r.advance(STILL)
            assertTrue(r.engine.open.value!!.readOnly)
            assertEquals(34.5, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
            assertEquals(34.5, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
            assertTrue(r.notices.any { it is PackNotice.Dropped && it.reasons == setOf("pack_finished") })
            r.advance(10_000)
            assertEquals("nothing more was sent", 1, r.sent().size)
            assertTrue("waiting for the person while the pack is open", r.keeper.records.isEmpty())

            r.workspace.closePack()
            runCurrent()
            val kept = r.keeper.records.single().version
            assertEquals("LZ HAWK (my edits)", kept.name)
            assertEquals(34.4, kept.data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        }

    // Owner decision 4: a pause for the account's sake drops nothing, so a change made while the pack is paused stays where it was made.
    private suspend fun PackEditorRig.paused() {
        server.setFeature(SAM.id, false)                            // an admin turned Mission Packs off for Sam: the pack pauses
        engine.refresh()
        test.runCurrent()
        assertEquals(PackStatus.PAUSED, engine.open.value!!.status)
    }

    @Test
    fun `a change made while the pack is paused for the account stays in the editor, and goes once the account is back`() = packTest { r ->
        r.opened()
        r.paused()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        r.advance(10_000)
        assertEquals("not put back", 34.3, r.helicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(1, r.diagrams.undoDepth.value)
        assertTrue(r.notices.none { it is PackNotice.Refused })
        assertEquals(emptyList<List<JsonObject>>(), r.sent())

        r.server.setFeature(SAM.id, true)                           // ticked again, and the app enables the packs once more
        r.engine.enable(SAM)
        r.advance(10_000)
        assertEquals(1, r.sent().size)
        assertEquals(34.3, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        assertTrue(r.keeper.records.isEmpty())
    }

    @Test
    fun `a change made while the pack is paused is kept as NAME (my edits) when its LZ-PZ is closed before the account is back`() = packTest { r ->
        r.opened()
        r.paused()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        r.diagrams.close()
        runCurrent()
        val kept = r.keeper.records.single().version
        assertEquals("LZ HAWK (my edits)", kept.name)
        assertEquals(34.3, kept.data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(setOf("paused"), r.notices.filterIsInstance<PackNotice.Kept>().single().reasons)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    // -- An item that is not in the pack any more ------------------------------------------------------------------------------------

    @Test
    fun `an item removed elsewhere closes the editor, and a change here that had not gone is kept as NAME (my edits)`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        r.theirs(json("""{"type": "item.delete", "item": "lz-1"}"""))
        assertNull(r.diagrams.active.value)
        val kept = r.keeper.records.single().version
        assertEquals("LZ HAWK (my edits)", kept.name)
        assertEquals("lz", kept.kind)
        assertEquals(34.3, kept.data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        val removed = r.notices.filterIsInstance<PackNotice.ItemRemoved>().single()
        assertEquals(PACK to LOCAL, removed.pack to removed.localId)
        assertEquals("LZ HAWK", removed.name)
        assertEquals(r.keeper.records.single().libraryUuid, removed.kept!!.libraryUuid)
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    @Test
    fun `the change kept when an item is removed is the document's last, edits made while it was closing included`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", HAWK), create("lz-2", "lz", "LZ CROW", HAWK))
            r.diagrams.edit("Move", coalesce = "drag", change = move(101, """{"lat": 34.3}"""))
            // The session is busy (a change to another LZ/PZ, reading this person's own fields for it) as the removal arrives.
            val gate = CompletableDeferred<Unit>().also { store.ownGate = it }
            launch { r.diagrams.update(PackRef.localId(PACK, "lz-2")) { it } }
            runCurrent()
            r.theirs(json("""{"type": "item.delete", "item": "lz-1"}"""))
            r.diagrams.edit("Move", coalesce = "drag", change = move(101, """{"lat": 34.2}"""))    // the finger still moving
            gate.complete(Unit)
            runCurrent()
            assertNull(r.diagrams.active.value)
            val kept = r.keeper.records.single().version
            assertEquals(34.2, kept.data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        } finally {
            r.close()
        }
    }

    // -- A pack that is not the open one any more ---------------------------------------------------------------------------------

    @Test
    fun `a pack closed under its open LZ-PZ closes it too, and a change that had not gone is kept as NAME (my edits)`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        r.engine.closePack()                                        // not through the workspace, which closes the pack's items first
        runCurrent()
        assertNull(r.diagrams.active.value)
        assertEquals("LZ HAWK (my edits)", r.keeper.records.single().version.name)
        val removed = r.notices.filterIsInstance<PackNotice.ItemRemoved>().single()
        assertEquals(LOCAL, removed.localId)
        assertEquals(r.keeper.records.single().libraryUuid, removed.kept!!.libraryUuid)
        r.advance(10_000)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
    }

    @Test
    fun `packs turned off under an open LZ-PZ close it, and its change waits for its account to be kept`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", HAWK), create("lz-2", "lz", "LZ CROW", HAWK))
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
            // The session is busy (a change to another LZ/PZ, reading this person's own fields for it) until the packs are off, so the LZ/PZ
            // closes once nobody's packs are on.
            val gate = CompletableDeferred<Unit>().also { store.ownGate = it }
            launch { r.diagrams.update(PackRef.localId(PACK, "lz-2")) { it } }
            runCurrent()
            r.engine.disable()                                      // Mission Packs turned off, or no longer through the gate
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertNull(r.diagrams.active.value)
            assertTrue("nobody's packs are on: whose library it would go to is not known yet", r.keeper.records.isEmpty())
            r.engine.enable(SAM)
            runCurrent()
            assertEquals("LZ HAWK (my edits)", r.keeper.records.single().version.name)
            assertEquals(setOf("closed"), r.notices.filterIsInstance<PackNotice.Kept>().single().reasons)
        } finally {
            r.close()
        }
    }

    @Test
    fun `another account's packs never keep this person's change in a library`() = packTest { r ->
        r.opened()
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        r.engine.enable(COLIN)                                      // someone else signed in on this device
        runCurrent()
        assertNull(r.diagrams.active.value)
        r.advance(10_000)
        assertTrue(r.keeper.records.isEmpty())
        assertNull(r.notices.filterIsInstance<PackNotice.ItemRemoved>().single().kept)
    }

    @Test
    fun `an item removed elsewhere with nothing unsent here closes the editor and keeps nothing`() = packTest { r ->
        r.opened()
        r.theirs(json("""{"type": "item.delete", "item": "lz-1"}"""))
        assertNull(r.diagrams.active.value)
        assertTrue(r.keeper.records.isEmpty())
        assertNull(r.notices.filterIsInstance<PackNotice.ItemRemoved>().single().kept)
    }

    // -- A save cut off -----------------------------------------------------------------------------------------------------------

    /**
     * The device's copy, whose next write waits for [gate] once armed, and is then written whatever became of the one who asked; whose next
     * read waits for [loadGate]; and whose next read or write of a person's own fields waits for [ownGate] or [putOwnGate].
     */
    private class GatedStore(private val inner: InMemoryPackStore = InMemoryPackStore()) : PackStore by inner {
        var gate: CompletableDeferred<Unit>? = null
        var loadGate: CompletableDeferred<Unit>? = null
        var ownGate: CompletableDeferred<Unit>? = null
        var putOwnGate: CompletableDeferred<Unit>? = null

        override suspend fun putOwn(pack: String, item: String, own: JsonObject) {
            putOwnGate?.let { waiting ->
                putOwnGate = null
                waiting.await()
            }
            inner.putOwn(pack, item, own)
        }

        override suspend fun own(pack: String, item: String): JsonObject? {
            ownGate?.let { waiting ->
                ownGate = null
                waiting.await()
            }
            return inner.own(pack, item)
        }

        override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
            gate?.let { waiting ->
                gate = null
                withContext(NonCancellable) { waiting.await() }
            }
            inner.write(before, after, me)
        }

        override suspend fun load(pack: String, me: Int): PackSession? {
            loadGate?.let { waiting ->
                loadGate = null
                waiting.await()
            }
            return inner.load(pack, me)
        }
    }

    @Test
    fun `someone else's change that arrives while an LZ-PZ opens is taken, and never undone`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
            r.start()
            r.openPack()
            val gate = CompletableDeferred<Unit>().also { store.ownGate = it }
            val opening = launch { r.workspace.openItem(ITEM) }
            runCurrent()                                            // made from the item as it was, waiting on this person's own fields
            r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"heading": 300}}"""))
            gate.complete(Unit)
            runCurrent()
            assertTrue(opening.isCompleted)
            assertEquals(300, r.helicopter(0).getValue("heading").jsonPrimitive.int)
            r.advance(10_000)
            assertEquals(emptyList<List<JsonObject>>(), r.sent())
            assertEquals(300, r.serverHelicopter(0).getValue("heading").jsonPrimitive.int)
        } finally {
            r.close()
        }
    }

    @Test
    fun `a change made to an LZ-PZ that is not open keeps someone else's that arrived while it was read`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", HAWK), create("lz-2", "lz", "LZ CROW", HAWK))
            val gate = CompletableDeferred<Unit>().also { store.ownGate = it }
            val updating = launch {
                r.diagrams.update(PackRef.localId(PACK, "lz-2")) { it.copy(flightData = JsonObject(it.flightData + ("callSign" to JsonPrimitive("CROW 6")))) }
            }
            runCurrent()
            r.theirs(json("""{"type": "patch", "item": "lz-2", "path": ["graphics", "helicopters", {"id": 101}], "value": {"heading": 300}}"""))
            gate.complete(Unit)
            r.advance(10_000)
            assertTrue(updating.isCompleted)
            val crow = r.serverData("lz-2")
            assertEquals("CROW 6", crow.getValue("flightData").jsonObject.getValue("callSign").jsonPrimitive.content)
            assertEquals(300, crow.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("heading").jsonPrimitive.int)
            assertEquals(listOf(listOf("flightData")), r.sent().map { batch -> batch.map { it.getValue("path").jsonArray.joinToString("/") { p -> p.jsonPrimitive.content } } })
        } finally {
            r.close()
        }
    }

    @Test
    fun `a pack gone while a change is on its way keeps that change once`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened()
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            r.advance(STILL)                                        // the save is under way, waiting on the device's copy
            r.server.deletePack(PACK)
            r.engine.refresh()
            runCurrent()
            gate.complete(Unit)
            r.advance(10_000)
            assertNull(r.diagrams.active.value)
            val kept = r.keeper.records.single().version
            assertEquals(34.3, kept.data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        } finally {
            r.close()
        }
    }

    @Test
    fun `a change the pack refuses because it went while the change was on its way is kept, never put back`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened()
            r.diagrams.setQuietly { it.copy(view = it.view.copy(mapStyle = "topo")) }       // so the save writes this person's own fields first
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
            val gate = CompletableDeferred<Unit>().also { store.putOwnGate = it }
            r.advance(STILL)                                        // the save has the item, and waits on this person's own fields
            r.server.deletePack(PACK)
            r.engine.refresh()
            runCurrent()                                            // the pack goes before the change reaches it
            gate.complete(Unit)
            r.advance(10_000)
            assertNull(r.diagrams.active.value)
            assertTrue(r.notices.none { it is PackNotice.Refused })
            val kept = r.keeper.records.single().version
            assertEquals(34.3, kept.data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        } finally {
            r.close()
        }
    }

    @Test
    fun `a base map changed as the pack goes is not written for a pack the device has forgotten`() = packTest { r ->
        r.opened()
        r.diagrams.setQuietly { it.copy(view = it.view.copy(mapStyle = "topo")) }
        r.server.deletePack(PACK)
        r.engine.refresh()
        runCurrent()
        r.advance(10_000)
        assertNull(r.diagrams.active.value)
        assertNull(r.packStore.own(PACK, ITEM))
    }

    /** An LZ/PZ kind that cannot lay a version over a document [fail] more times. */
    private class Faltering(private val inner: PackKind<Diagram>) : PackKind<Diagram> by inner {
        var fail = 0

        override fun withShared(document: Diagram, shared: JsonElement, name: String): Diagram {
            if (fail > 0) {
                fail--
                throw IllegalStateException("This version cannot be laid over the document.")
            }
            return inner.withShared(document, shared, name)
        }
    }

    @Test
    fun `someone else's change that cannot be laid over the document leaves it as it was, and the next save never undoes it`() = runTest {
        var faltering: Faltering? = null
        val r = PackEditorRig(this, lzKind = { env -> Faltering(LzPackKind(env)).also { faltering = it } })
        try {
            r.opened()
            checkNotNull(faltering).fail = 1
            r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"heading": 300}}"""))
            assertEquals(90, r.helicopter(0).getValue("heading").jsonPrimitive.int)
            r.theirs(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 102}], "value": {"heading": 120}}"""))
            assertEquals(listOf(300, 120), (0..1).map { r.helicopter(it).getValue("heading").jsonPrimitive.int })
            r.advance(10_000)
            assertEquals(emptyList<List<JsonObject>>(), r.sent())
            assertEquals(300, r.serverHelicopter(0).getValue("heading").jsonPrimitive.int)
        } finally {
            r.close()
        }
    }

    @Test
    fun `an edit made while a save is under way neither cuts it off nor says it failed, and goes next`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened()
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.2}"""))
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            r.advance(STILL)                                        // the save is under way, waiting on the device's copy
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.1}"""))
            gate.complete(Unit)
            runCurrent()
            assertFalse(r.diagrams.saveFailed.value)
            r.advance(STILL)
            assertEquals(listOf(listOf(34.2), listOf(34.1)), r.sent().map { batch -> batch.map { it.getValue("value").jsonObject.getValue("lat").jsonPrimitive.double } })
            assertFalse(r.diagrams.saveFailed.value)
        } finally {
            r.close()
        }
    }

    @Test
    fun `a pack read again after a pause keeps its item open in the editor while the device's copy is read`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened()
            r.server.setFeature(SAM.id, false)
            r.engine.refresh()
            runCurrent()
            assertEquals(PackStatus.PAUSED, r.engine.open.value!!.status)
            r.server.setFeature(SAM.id, true)
            val gate = CompletableDeferred<Unit>().also { store.loadGate = it }
            r.engine.enable(SAM)                                    // asked again, by a new client, which shows nothing until it has read the copy
            runCurrent()
            assertNull(r.engine.open.value!!.session)
            assertEquals(LOCAL, r.diagrams.active.value?.id)
            gate.complete(Unit)
            runCurrent()
            assertEquals(LOCAL, r.diagrams.active.value?.id)
            assertTrue(r.notices.none { it is PackNotice.ItemRemoved })
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.2}"""))
            r.advance(STILL)
            assertEquals(34.2, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
        } finally {
            r.close()
        }
    }

    @Test
    fun `a flush cancelled in the middle of a save never sends the change twice`() = runTest {
        val store = GatedStore()
        val r = PackEditorRig(this, store)
        try {
            r.opened()
            r.diagrams.edit("Move", change = move(101, """{"lat": 34.2}"""))
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            val flushing = launch { r.diagrams.flush() }
            runCurrent()                                            // the save waits on the device's copy, with the change in hand
            flushing.cancel()
            gate.complete(Unit)
            runCurrent()
            r.diagrams.flush()
            r.advance(10_000)
            assertEquals(1, r.sent().size)
            assertEquals(1, r.server.log(PACK).count { it["client_op_id"]?.jsonPrimitive?.content?.startsWith("op-") == true })
            assertEquals(34.2, r.serverHelicopter(0).getValue("lat").jsonPrimitive.double, 0.0)
            assertFalse(r.diagrams.saveFailed.value)
        } finally {
            r.close()
        }
    }

    // -- An analysis -------------------------------------------------------------------------------------------------------------

    private val found = listOf(listOf(34.5, -84.1), listOf(34.51, -84.1), listOf(34.51, -84.09))

    private inner class Terrain(val gate: CompletableDeferred<Unit>? = null) : TerrainApi {
        override suspend fun analyzeField(at: LatLon): FieldAnalysis {
            gate?.await()
            return FieldAnalysis("success", found, "1200", "Field detected")
        }

        override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis =
            throw ApiException(502, null, "No configured terrain source covers this LZ")
    }

    // An LZ/PZ with a target and nothing more, as the web makes one.
    private fun targeted(env: DiagramNormalizer.Environment): JsonObject {
        val target = DiagramTarget(34.5, -84.1, "16S GC 28864 55349")
        return PackLz.lzItemData(checkNotNull(DiagramNormalizer.fromTarget(DiagramJson.encode(target), mgrs = target.mgrs, id = "made-here", env = env)))
    }

    @Test
    fun `an analysis of the pack's LZ-PZ open in the editor lands in the pack item`() = packTest { r ->
        r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", targeted(r.env)))
        val service = AnalysisService(Terrain(), r.diagrams, r.scope, r.dispatcher)
        service.analyze(LOCAL)
        runCurrent()
        assertEquals(DiagramStatus.ANALYZED, r.doc().status)
        r.advance(STILL)
        assertEquals("analyzed", r.serverData().getValue("status").jsonPrimitive.content)
        assertJson(JsonArray(found.map { p -> JsonArray(p.map(::JsonPrimitive)) }), r.serverData().getValue("analysis").jsonObject["detectedLZ"], "the boundary")
        assertEquals(2, r.serverData().getValue("graphics").jsonObject.getValue("doghouses").jsonArray.size)
        assertEquals(1, r.sent().size)
        assertEquals("Sam B. analyzed LZ HAWK.", r.sent().single().first().getValue("summary").jsonPrimitive.content)
    }

    @Test
    fun `an analysis that comes back once another LZ-PZ is open lands in the item it was asked for`() = packTest { r ->
        r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", targeted(r.env)), create("lz-2", "lz", "LZ CROW", targeted(r.env)))
        val gate = CompletableDeferred<Unit>()
        val service = AnalysisService(Terrain(gate), r.diagrams, r.scope, r.dispatcher)
        service.analyze(LOCAL)
        runCurrent()
        r.openItem("lz-2")
        gate.complete(Unit)
        r.advance(STILL)
        assertEquals(PackRef.localId(PACK, "lz-2"), r.doc().id)
        assertEquals(DiagramStatus.TARGETED, r.doc().status)
        assertEquals("analyzed", r.serverData(ITEM).getValue("status").jsonPrimitive.content)
        assertEquals("targeted", r.serverData("lz-2").getValue("status").jsonPrimitive.content)
        assertEquals(listOf("lz-1"), r.sent().flatten().map { it.getValue("item").jsonPrimitive.content }.distinct())
    }

    @Test
    fun `an analysis that comes back once its pack is closed is dropped, and nothing goes to the pack or the library`() = packTest { r ->
        r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", targeted(r.env)))
        val gate = CompletableDeferred<Unit>()
        val service = AnalysisService(Terrain(gate), r.diagrams, r.scope, r.dispatcher)
        service.analyze(LOCAL)
        runCurrent()
        r.workspace.closePack()
        gate.complete(Unit)
        r.advance(STILL)
        assertEquals(AnalysisStatus.Idle, service.status.value)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertEquals("targeted", r.serverData().getValue("status").jsonPrimitive.content)
        assertEquals(emptyList<String>(), r.library.names(RecordKind.LZ))
    }

    @Test
    fun `a pack's LZ-PZ that is not open is read through the pack, and one of a pack that is not open is not there`() = packTest { r ->
        r.opened(ITEM, create(ITEM, "lz", "LZ HAWK", HAWK), create("lz-2", "lz", "LZ CROW", HAWK))
        r.diagrams.edit("Move", change = move(101, """{"lat": 34.3}"""))
        assertEquals("the open one as it is now", 34.3, r.diagrams.document(LOCAL)!!.graphics.helicopters[0].jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals("LZ CROW", r.diagrams.document(PackRef.localId(PACK, "lz-2"))!!.name)
        assertNull(r.diagrams.document(PackRef.localId("p-9", "lz-2")))
        assertNull(r.diagrams.document(PackRef.localId(PACK, "lz-9")))
    }

    // -- The library ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a library diagram opened in the same editor is saved to the library, never to the pack`() = packTest { r ->
        r.opened()
        val mine = r.diagramRepository.create(DiagramTarget(34.6, -84.2, "16S GC 1 2"), "LZ MINE")
        assertTrue(r.diagrams.open(mine.id))
        r.diagrams.edit("Rename") { it.copy(name = "LZ MINE 2") }
        r.advance(STILL)
        assertEquals("LZ MINE 2", r.diagramRepository.open(mine.id)!!.name)
        assertEquals(emptyList<List<JsonObject>>(), r.sent())
        assertEquals("LZ HAWK", r.server.name(PACK, ITEM))
    }
}
