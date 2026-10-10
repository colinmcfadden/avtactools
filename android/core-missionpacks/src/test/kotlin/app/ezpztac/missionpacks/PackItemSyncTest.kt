package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The decisions of the web's usePackItemSync ([PackItemSync]), held to its users' tests case for case: usePackLz.test.js,
 * usePackRoutes.test.js and usePackPoints.test.js, each run against an [Editor] (the documents open in an editor, a pack whose every
 * operation must be well formed and apply, as fakePack.js insists, and the hook's loop over [PackItemSync]: a pass after every change,
 * a send once an item has been still for 400 ms). Then each decision on its own.
 */
class PackItemSyncTest {
    private companion object {
        const val PACK = "p-1"
        const val ACTOR = "Colin M."
        const val DELAY_MS = 400L

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun array(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

        /** What the batches sent were, against [expected] (a JSON list of lists of operations): key order aside, numbers by value. */
        fun assertSent(expected: String, sent: List<List<JsonObject>>) =
            assertValue(Json.parseToJsonElement(expected), JsonArray(sent.map(::JsonArray)), "what was sent")

        /** fakePack.js's applyOps: the pack's items after [ops], each of which must be well formed and apply, as it would on the server. */
        fun applyOps(items: List<PackItemView>, ops: List<JsonObject>): List<PackItemView> {
            var state: Map<String, PackItemState> = items.associateTo(LinkedHashMap()) { it.uuid to PackItemState(it.kind, it.name, it.data, false) }
            val order = items.map { it.uuid }.toMutableList()
            ops.forEach { op ->
                val bare = JsonObject(op - "summary")
                assertNull(PackOps.validate(bare), "well formed: $op")
                val result = PackOps.apply(state, bare)
                assertEquals(OpStatus.APPLIED to null, result.status to result.reason, "applies: $op")
                state = result.items
                val item = op.getValue("item").jsonPrimitive.content
                if (item !in order) order += item
            }
            return order.filter { state[it]?.deleted == false }.map { uuid ->
                val after = state.getValue(uuid)
                val before = items.firstOrNull { it.uuid == uuid }
                PackItemView(uuid, after.kind, after.name, if (before != null && before.data === after.data) before.data else after.data)
            }
        }
    }

    /**
     * An editor and an open pack, as the web's hook tests have them (useLzWorkspace, useRouteSketch or useLocalPoints over useFakePack),
     * and usePackItemSync's loop between them, made of [PackItemSync]'s decisions as an editor here is to make it.
     */
    private class Editor<D : Any>(
        val kind: PackKind<D>,
        initial: List<PackItemView>,
        val readOnly: Boolean = false,
        val refuse: String? = null,
    ) {
        var items: List<PackItemView> = initial
            private set
        val sent = mutableListOf<List<JsonObject>>()
        val gone = mutableListOf<String>()
        val refusals = mutableListOf<String>()

        /** The documents open in the editor, by item. */
        val open = LinkedHashMap<String, D>()
        private val baselines = HashMap<String, PackBaseline>()
        private val due = LinkedHashMap<String, Long>()
        private val lastSeen = HashMap<String, D>()
        private var now = 0L

        fun localId(uuid: String): String = PackRef.localId(PACK, uuid)

        fun item(uuid: String): PackItemView? = items.firstOrNull { it.uuid == uuid && it.kind == kind.kind }

        fun doc(uuid: String): D = open.getValue(uuid)

        /** This person's edits, as the open pack takes them: shown at once, or all refused with [refuse]. */
        fun edit(ops: List<JsonObject>): String? {
            if (refuse != null) return refuse
            sent += ops
            items = applyOps(items, ops)
            return null
        }

        /** Someone else's edits reach the pack (and the editor hears, unless [settle] is false: something else comes in the same moment). */
        fun remote(vararg ops: JsonObject, settle: Boolean = true) {
            items = applyOps(items, ops.toList())
            if (settle) pass()
        }

        fun openItem(uuid: String) {
            open[uuid] = kind.fromItem(localId(uuid), item(uuid)!!, null)
            pass()
        }

        /** A change in the editor (and the editor hears, unless [settle] is false). */
        fun change(uuid: String, settle: Boolean = true, edit: (D) -> D) {
            open[uuid] = edit(doc(uuid))
            if (settle) pass()
        }

        fun close(uuid: String) {
            open.remove(uuid)
            pass()
        }

        /** Time passes: whatever has been still long enough is sent, in turn. */
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = due.entries.minByOrNull { it.value }?.takeIf { it.value <= until } ?: break
                now = next.value
                flush(next.key)
                pass()
            }
            now = until
        }

        // usePackItemSync's effect, run until it changes nothing more (React renders again after it changed the editor, or sent).
        fun pass() {
            repeat(10) { if (!once()) return }
            fail<Unit>("the editor and the pack never settle")
        }

        private fun once(): Boolean {
            var again = false
            for ((uuid, document) in open.entries.toList()) {
                lastSeen[uuid] = document
                val item = item(uuid)
                val base = baselines[uuid]
                when (PackItemSync.reconcile(kind, localId(uuid), base, item, document, readOnly)) {
                    Reconcile.NOTHING -> Unit
                    Reconcile.SETTLE -> baselines[uuid] = PackItemSync.settled(kind, localId(uuid), item!!)
                    Reconcile.SEND_LATER -> due[uuid] = now + DELAY_MS
                    Reconcile.FLUSH_FIRST -> {
                        flush(uuid)
                        again = true
                    }
                    Reconcile.TAKE_THEIRS -> {
                        take(uuid, PackItemSync.takeTheirs(kind, localId(uuid), base, item!!))
                        again = true
                    }
                    Reconcile.PUT_BACK -> {
                        take(uuid, PackItemSync.putBack(kind, localId(uuid), item!!))
                        again = true
                    }
                    Reconcile.REMOVED -> {
                        due.remove(uuid)
                        baselines.remove(uuid)
                        open.remove(uuid)
                        gone += base!!.name
                        again = true
                    }
                }
            }
            // Items no longer open here are forgotten, and a change of theirs still waiting is sent.
            for (uuid in baselines.keys.toList()) {
                if (uuid in open) continue
                if (uuid in due) flush(uuid)
                baselines.remove(uuid)
                lastSeen.remove(uuid)
            }
            return again
        }

        private fun flush(uuid: String) {
            due.remove(uuid)
            val mine = open[uuid] ?: lastSeen[uuid] ?: return
            val item = item(uuid) ?: return
            val base = baselines[uuid] ?: return
            if (readOnly) {
                take(uuid, PackItemSync.putBack(kind, localId(uuid), item))
                return
            }
            val plan = PackItemSync.flushPlan(kind, localId(uuid), base, item, mine, ACTOR) ?: return
            val refused = edit(plan.ops)
            if (refused != null) {
                take(uuid, PackItemSync.putBack(kind, localId(uuid), item))
                refusals += refused
                return
            }
            baselines[uuid] = PackItemSync.sent(base, plan)
        }

        // The baseline first, then the editor's document: an editor here sets it before it changes the document, so the save that
        // follows finds nothing to send.
        private fun take(uuid: String, taken: TakeTheirs<D>) {
            baselines[uuid] = taken.baseline
            open[uuid]?.let { open[uuid] = taken.apply(it) }
        }
    }

    // -- usePackLz.test.js ------------------------------------------------------------------------------------------------------

    @Nested
    inner class LzPzs {
        private val lz = LzPackKind(PackFixtures.env())

        private val data = json(
            """{"schemaVersion": 2, "status": "analyzed",
                "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"},
                "mapData": {"mgrs": "16S GC 28864 55349"},
                "flightData": {"landing_hdg": "090°"},
                "analysis": {"customLZ": null, "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "results": {"area": 1}, "gridElevation": "1200", "latLong": ""},
                "graphics": {
                  "helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "heading": 90}, {"id": 102, "lat": 34.501, "lon": -84.1, "heading": 90}],
                  "doghouses": [], "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "units": [], "measurements": [], "exportBox": null},
                "view": {"mapStyle": "satellite", "showLZOutline": true, "showHeatmap": false}}""",
        )
        private val hawk = PackItemView("lz-1", "lz", "LZ HAWK", data)

        private fun opened(items: List<PackItemView> = listOf(hawk), readOnly: Boolean = false, refuse: String? = null) =
            Editor(lz, items, readOnly, refuse).apply {
                openItem("lz-1")
                advance(1_000)
            }

        private fun move(id: Int, patch: String): (Diagram) -> Diagram = { DiagramOps.patchGraphic(it, "helicopters", JsonPrimitive(id), json(patch)) }

        private fun helicopters(diagram: Diagram): List<JsonObject> = diagram.graphics.helicopters.map { it.jsonObject }

        private fun Editor<Diagram>.helicopter(index: Int): JsonObject = helicopters(doc("lz-1"))[index]

        private fun Editor<Diagram>.landing(): String = doc("lz-1").flightData.getValue("landing_hdg").jsonPrimitive.content

        @Test
        fun `opens an item as a diagram named for the pack and the item, and sends nothing for it`() {
            val e = opened()
            assertEquals("LZ HAWK", e.doc("lz-1").name)
            assertEquals(PackRef.localId(PACK, "lz-1"), e.doc("lz-1").id)
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `sends a drag as one change, at the helicopter, with a sentence for the history`() {
            val e = opened()
            e.change("lz-1", edit = move(102, """{"lat": 34.502}"""))
            e.change("lz-1", edit = move(102, """{"lat": 34.503, "lon": -84.101}"""))
            assertTrue(e.sent.isEmpty())
            e.advance(1_000)
            assertSent(
                """[[{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 102}], "value": {"lat": 34.503, "lon": -84.101},
                      "summary": "Colin M. moved Chalk 2 on LZ HAWK."}]]""",
                e.sent,
            )
            e.advance(1_000)
            assertEquals(1, e.sent.size)                                // the echo changes nothing
        }

        @Test
        fun `keeps this person's view to themselves`() {
            val e = opened()
            e.change("lz-1") { DiagramOps.setView(it, json("""{"mapStyle": "vfr-sectional", "showHeatmap": true}""")) }
            e.advance(1_000)
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `applies someone else's change and keeps this person's view of the diagram`() {
            val e = opened()
            e.change("lz-1") { DiagramOps.setView(it, json("""{"mapStyle": "vfr-sectional"}""")) }
            e.remote(json("""{"type": "patch", "item": "lz-1", "path": ["flightData"], "value": {"landing_hdg": "270°"}}"""))
            e.advance(1_000)
            assertEquals("270°", e.landing())
            assertEquals("vfr-sectional", e.doc("lz-1").view.mapStyle)
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `sends a change waiting here before taking someone else's, so both stand`() {
            val e = opened()
            e.change("lz-1", edit = move(101, """{"lat": 34.499}"""))
            e.remote(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 102}], "value": {"heading": 270}}"""))
            e.advance(1_000)
            assertEquals(1, e.sent.size)
            assertValue(array("""["graphics", "helicopters", {"id": 101}]"""), e.sent[0][0]["path"], "the change sent")
            assertEquals(34.499, e.helicopter(0).getValue("lat").jsonPrimitive.double)
            assertEquals(270, e.helicopter(1).getValue("heading").jsonPrimitive.int)
        }

        @Test
        fun `keeps both when a change here and someone else's arrive at the same moment`() {
            val e = opened()
            e.change("lz-1", settle = false, edit = move(101, """{"lat": 34.499}"""))
            e.remote(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 102}], "value": {"heading": 270}}"""))
            e.advance(1_000)
            assertEquals(34.499, e.helicopter(0).getValue("lat").jsonPrimitive.double)
            assertEquals(270, e.helicopter(1).getValue("heading").jsonPrimitive.int)
            assertEquals(1, e.sent.size)
        }

        @Test
        fun `still sends the rest of a drag as one change after someone else's arrived in the middle of it`() {
            val e = opened()
            e.change("lz-1", edit = move(101, """{"lat": 34.4991}"""))
            e.remote(json("""{"type": "patch", "item": "lz-1", "path": ["flightData"], "value": {"landing_hdg": "180°"}}"""))
            listOf(34.4992, 34.4993, 34.4994).forEach { lat ->
                e.advance(50)
                e.change("lz-1", edit = move(101, """{"lat": $lat}"""))
            }
            e.advance(1_000)
            assertEquals(
                listOf(listOf(34.4991), listOf(34.4994)),
                e.sent.map { batch -> batch.map { it.getValue("value").jsonObject.getValue("lat").jsonPrimitive.double } },
            )
            assertEquals("180°", e.landing())
            assertEquals(34.4994, e.helicopter(0).getValue("lat").jsonPrimitive.double)
        }

        @Test
        fun `on one field, the change sent later stands`() {
            val e = opened()
            e.change("lz-1", edit = move(101, """{"heading": 45}"""))
            e.remote(json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"heading": 300}}"""))
            e.advance(1_000)
            // Theirs reached the pack first; ours, sent after it, is the one that stands, here and in the pack.
            assertEquals(45, e.helicopter(0).getValue("heading").jsonPrimitive.int)
            val packs = e.items[0].data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray
            assertEquals(45, packs[0].jsonObject.getValue("heading").jsonPrimitive.int)
        }

        @Test
        fun `still sends a change made just before the LZ-PZ was closed`() {
            val e = opened()
            e.change("lz-1", edit = move(101, """{"lat": 34.4}"""))
            e.close("lz-1")
            e.advance(1_000)
            assertSent("""[[{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"lat": 34.4},
                             "summary": "Colin M. moved Chalk 1 on LZ HAWK."}]]""", e.sent)
            val packs = e.items[0].data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray
            assertEquals(34.4, packs[0].jsonObject.getValue("lat").jsonPrimitive.double)
        }

        @Test
        fun `puts back a change the pack will not take`() {
            val e = opened(readOnly = true)
            e.change("lz-1", edit = move(101, """{"lat": 1}"""))
            e.advance(1_000)
            assertEquals(34.5, e.helicopter(0).getValue("lat").jsonPrimitive.double)
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `puts back and reports a change the pack refused`() {
            val e = opened(refuse = "pack_finished")
            e.change("lz-1", edit = move(101, """{"lat": 1}"""))
            e.advance(1_000)
            assertEquals(34.5, e.helicopter(0).getValue("lat").jsonPrimitive.double)
            assertEquals(listOf("pack_finished"), e.refusals)
        }

        @Test
        fun `closes an item someone removed`() {
            val e = opened()
            e.remote(json("""{"type": "item.delete", "item": "lz-1"}"""))
            e.advance(1_000)
            assertFalse("lz-1" in e.open)
            assertEquals(listOf("LZ HAWK"), e.gone)
        }

        @Test
        fun `renames the item, and takes someone else's name for it`() {
            val e = opened()
            e.change("lz-1") { DiagramOps.setName(it, "LZ EAGLE") }
            e.advance(1_000)
            assertSent("""[[{"type": "item.rename", "item": "lz-1", "name": "LZ EAGLE", "summary": "Colin M. renamed \"LZ HAWK\" to \"LZ EAGLE\"."}]]""", e.sent)
            e.remote(json("""{"type": "item.rename", "item": "lz-1", "name": "LZ OSPREY"}"""))
            e.advance(1_000)
            assertEquals("LZ OSPREY", e.doc("lz-1").name)
            assertEquals(1, e.sent.size)
        }

        @Test
        fun `makes a new LZ-PZ in the pack`() {
            val e = Editor(lz, listOf(hawk))
            // usePackLz's createItem: the item and the diagram made together, opened at once.
            val uuid = PackSentences.packItemId("lz", "new")
            val diagram = DiagramNormalizer.fromTarget(array("[34.6, -84.2]"), mgrs = "16S GC 1 2", id = e.localId(uuid), env = PackFixtures.env())!!
            val op = PackSentences.newItemOp("lz", uuid, null, e.items.count { it.kind == "lz" }, PackLz.lzItemData(diagram), ACTOR)
            assertNull(e.edit(listOf(op)))
            e.open[uuid] = diagram.copy(name = op.getValue("name").jsonPrimitive.content)
            e.pass()
            e.advance(1_000)
            assertEquals("lz-new", uuid)
            val create = e.sent.single().single()
            assertEquals(listOf("item.create", "lz-new", "lz", "LZ/PZ 2"), listOf("type", "item", "kind", "name").map { create.getValue(it).jsonPrimitive.content })
            assertValue(PackLz.lzItemData(e.doc(uuid)), create["data"], "the new item's data")
            assertNull(create.getValue("data").jsonObject["view"])
            assertEquals(1, e.sent.size)
        }

        // Android's own (PackLz.carryUnknown): the web drops what a newer version wrote, the webBug case of shared.json. Here it rides
        // through every change made here and every one taken from someone else, and nothing is ever sent for it but what they sent.
        @Test
        fun `keeps the fields a newer version wrote through changes both ways, and never sends anything for them`() {
            val graphics = JsonObject(data.getValue("graphics").jsonObject + ("futureGraphics" to array("""[{"id": "f-1"}]""")))
            val newer = JsonObject(data + mapOf("weather" to json("""{"wind": "270/12"}"""), "graphics" to graphics))
            val e = opened(items = listOf(hawk.copy(data = newer)))
            e.change("lz-1", edit = move(101, """{"lat": 34.499}"""))
            e.advance(1_000)
            e.remote(json("""{"type": "patch", "item": "lz-1", "path": ["weather"], "value": {"wind": "300/20"}}"""))
            e.advance(1_000)
            e.change("lz-1", edit = move(101, """{"lat": 34.498}"""))
            e.advance(1_000)
            assertSent(
                """[[{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"lat": 34.499},
                       "summary": "Colin M. moved Chalk 1 on LZ HAWK."}],
                    [{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 101}], "value": {"lat": 34.498},
                       "summary": "Colin M. moved Chalk 1 on LZ HAWK."}]]""",
                e.sent,
            )
            val packs = e.items.single().data.jsonObject
            assertValue(json("""{"wind": "300/20"}"""), packs["weather"], "their change to it")
            assertValue(array("""[{"id": "f-1"}]"""), packs.getValue("graphics").jsonObject["futureGraphics"], "what nobody changed")
        }

        @Test
        fun `edits an item whose data is in an older shape without sending anything until it is changed`() {
            val legacy = PackItemView(
                "lz-1", "lz", "LZ OLD",
                json(
                    """{"targetLocation": [34.5, -84.1], "gridInput": "16S GC 28864 55349", "status": "analyzed",
                        "analysis": {"detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]]},
                        "helicopters": [{"id": 7, "lat": 34.5, "lon": -84.1}], "savedId": 12, "dirty": false, "view": {"mapStyle": "topo"}}""",
                ),
            )
            val e = opened(items = listOf(legacy))
            assertTrue(e.sent.isEmpty())
            assertEquals("topo", e.doc("lz-1").view.mapStyle)
            e.change("lz-1", edit = move(7, """{"lat": 34.6}"""))
            e.advance(1_000)
            // Every operation applied to the old shape (applyOps insists); the item is in today's shape now.
            val packs = e.items[0].data.jsonObject.getValue("graphics").jsonObject.getValue("helicopters").jsonArray
            assertEquals(34.6, packs[0].jsonObject.getValue("lat").jsonPrimitive.double)
            assertEquals(1, e.sent.size)
        }
    }

    // -- usePackRoutes.test.js --------------------------------------------------------------------------------------------------

    @Nested
    inner class RouteSets {
        private val rawRoute = json(
            """{"id": "r-1", "name": "RED 1", "color": "#0A84FF",
                "points": [
                  {"id": "p-1", "lat": 34.5, "lon": -84.1, "kind": "amps", "ptType": "start", "name": ".SP", "role": "start", "ele": null},
                  {"id": "p-2", "lat": 34.6, "lon": -84.2, "kind": "amps", "ptType": "ip", "name": ".RP", "role": "waypoint", "ele": null}]}""",
        )

        // The set as the pack has it: in today's shape, as anything this app sends would be.
        private val set = PackItemView("rt-1", "route", "OP DK INGRESS", PackRoutes.routeSetData(JsonArray(listOf(PackRoutes.restoreSketchRoute(rawRoute)))))

        private fun opened(items: List<PackItemView> = listOf(set)) = Editor(RoutePackKind, items).apply {
            openItem("rt-1")
            advance(1_000)
        }

        private fun moved(routeId: String, pointId: String, lat: Double, lon: Double): (RouteSet) -> RouteSet = { routes ->
            routes.mapRoute(routeId) { route -> route.copy(points = route.points.map { if (it.id == pointId) it.copy(lat = lat, lon = lon) else it }) }
        }

        @Test
        fun `opens a set's routes in the editor, with their ids, and sends nothing`() {
            val e = opened()
            assertEquals(listOf(Triple("r-1", "RED 1", true)), e.doc("rt-1").routes.map { Triple(it.id, it.name, it.visible) })
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `sends a moved point as one change at that point`() {
            val e = opened()
            e.change("rt-1", edit = moved("r-1", "p-2", 34.61, -84.21))
            e.advance(1_000)
            val op = e.sent.single().single()
            assertEquals("patch", op.getValue("type").jsonPrimitive.content)
            assertEquals("rt-1", op.getValue("item").jsonPrimitive.content)
            assertValue(array("""["routes", {"id": "r-1"}, "points", {"id": "p-2"}]"""), op["path"], "the path")
            assertEquals("Colin M. moved .RP on RED 1.", op.getValue("summary").jsonPrimitive.content)
            val value = op.getValue("value").jsonObject
            assertEquals(34.61, value.getValue("lat").jsonPrimitive.double)
            assertEquals(-84.21, value.getValue("lon").jsonPrimitive.double)
        }

        @Test
        fun `keeps a hidden route hidden and to this person`() {
            val e = opened()
            e.change("rt-1") { routes -> routes.mapRoute("r-1") { it.copy(visible = false) } }
            e.advance(1_000)
            assertTrue(e.sent.isEmpty())
            e.remote(json("""{"type": "patch", "item": "rt-1", "path": ["routes", {"id": "r-1"}], "value": {"name": "RED 9"}}"""))
            e.advance(1_000)
            assertEquals(listOf("RED 9" to false), e.doc("rt-1").routes.map { it.name to it.visible })
        }

        @Test
        fun `takes someone else's new point`() {
            val e = opened()
            e.remote(
                json(
                    """{"type": "insert", "item": "rt-1", "path": ["routes", {"id": "r-1"}, "points"], "after": "p-1",
                        "value": {"id": "p-9", "lat": 34.55, "lon": -84.15, "kind": "shaping"}}""",
                ),
            )
            e.advance(1_000)
            assertEquals(listOf("p-1", "p-9", "p-2"), e.doc("rt-1").routes.single().points.map { it.id })
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `puts a route drawn while the set is chosen into the set`() {
            val e = opened()
            val drawn = SketchRoute(
                id = "sketch-1-abc", name = "RED 2", color = "#32D74B",
                points = listOf(
                    RoutePoint(id = "a", lat = 34.7, lon = -84.3, kind = RoutePoint.KIND_AMPS, ptType = "start", name = ".SP"),
                    RoutePoint(id = "b", lat = 34.8, lon = -84.4, kind = RoutePoint.KIND_AMPS, ptType = "ip", name = ".RP"),
                ),
            )
            e.change("rt-1") { it.plus(drawn) }
            e.advance(1_000)
            val op = e.sent.single().single()
            assertEquals(listOf("insert", "rt-1", "r-1"), listOf("type", "item", "after").map { op.getValue(it).jsonPrimitive.content })
            assertValue(array("""["routes"]"""), op["path"], "the path")
            assertEquals("Colin M. added the route RED 2 to OP DK INGRESS.", op.getValue("summary").jsonPrimitive.content)
            val value = op.getValue("value").jsonObject
            assertFalse("visible" in value)
            assertFalse("setId" in value)
        }

        // The web's sketch holds every route at once, a set's filed under it, so a test there shows that a route of this person's
        // own, beside the set, is never sent. Here each set is a document of its own: what is open under any other id (one of the
        // library's) is not this pack's, and an editor never joins it to the pack, as PackRef says.
        @Test
        fun `leaves this person's own routes out of the pack`() {
            val e = opened()
            assertNull(PackRef.parse("8b2f1e7c-1111-4b6a-9c33-0f0e1d2c3b4a"))
            assertEquals(PackItemRef(PACK, "rt-1"), PackRef.parse(e.doc("rt-1").id))
            e.advance(1_000)
            assertTrue(e.sent.isEmpty())
            assertEquals(1, e.doc("rt-1").routes.size)
        }

        @Test
        fun `makes a set and renames one`() {
            val e = Editor(RoutePackKind, listOf(set))
            // usePackRoutes' createItem: the item made, then opened here.
            val uuid = PackSentences.packItemId("route", "new")
            val op = PackSentences.newItemOp("route", uuid, "OP DK EGRESS", e.items.count { it.kind == "route" }, json("""{"version": 1, "routes": []}"""), ACTOR)
            assertNull(e.edit(listOf(op)))
            e.openItem(uuid)
            e.advance(1_000)
            assertEquals("rt-new", uuid)
            e.change(uuid) { it.copy(name = "OP DK EXFIL") }
            e.advance(1_000)
            assertEquals(
                listOf(listOf("item.create" to "OP DK EGRESS"), listOf("item.rename" to "OP DK EXFIL")),
                e.sent.map { batch -> batch.map { it.getValue("type").jsonPrimitive.content to it.getValue("name").jsonPrimitive.content } },
            )
        }

        @Test
        fun `takes a set someone removed off the map`() {
            val e = opened()
            e.remote(json("""{"type": "item.delete", "item": "rt-1"}"""))
            e.advance(1_000)
            assertFalse("rt-1" in e.open)
            assertEquals(listOf("OP DK INGRESS"), e.gone)
        }

        @Test
        fun `brings a set saved in an older shape up to date with its first change, and sends nothing for opening it`() {
            val oldRoute = JsonObject(rawRoute + mapOf("visible" to JsonPrimitive(false), "plan" to json("""{"tot": {"time": "12:00:00", "pointId": "p-2"}}""")))
            val old = PackItemView("rt-1", "route", "OLD", JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to JsonArray(listOf(oldRoute)))))
            val e = opened(listOf(old))
            assertTrue(e.sent.isEmpty())
            e.change("rt-1", edit = moved("r-1", "p-1", 34.4, -84.0))
            e.advance(1_000)
            // Every operation applied to the old shape (applyOps insists), and the set has today's.
            val route = e.items[0].data.jsonObject.getValue("routes").jsonArray[0].jsonObject
            val clock = route.getValue("plan").jsonObject.getValue("perPoint").jsonObject.getValue("p-2").jsonObject.getValue("clock")
            assertEquals("12:00:00", clock.jsonPrimitive.content)
            assertEquals(34.4, route.getValue("points").jsonArray[0].jsonObject.getValue("lat").jsonPrimitive.double)
        }
    }

    // -- usePackPoints.test.js --------------------------------------------------------------------------------------------------

    /** A pack's point set as the map shows it: its points, and its colour and whether it is shown, which are this person's own. */
    private data class PointsDoc(val id: String, val name: String, val points: JsonElement, val color: String, val visible: Boolean)

    /** Points have no editor here, or on the web; the hook's rules for a set are held with this stand-in for the map's list of sets. */
    private object PointsKind : PackKind<PointsDoc> {
        override val kind: String = "pointset"

        override fun shared(data: JsonElement?): JsonElement = data ?: JsonArray(emptyList())

        override fun fromItem(localId: String, item: PackItemView, own: JsonObject?): PointsDoc = PointsDoc(
            localId, item.name, item.data,
            own?.get("color")?.jsonPrimitive?.contentOrNull ?: "#0A84FF",
            own?.get("visible")?.jsonPrimitive?.booleanOrNull ?: true,
        )

        override fun docOf(document: PointsDoc, carryFrom: JsonElement?): JsonElement = document.points

        override fun nameOf(document: PointsDoc): String = document.name

        override fun ownOf(document: PointsDoc): JsonObject =
            JsonObject(mapOf("color" to JsonPrimitive(document.color), "visible" to JsonPrimitive(document.visible)))

        override fun withShared(document: PointsDoc, shared: JsonElement, name: String): PointsDoc = document.copy(points = shared, name = name)

        override fun describe(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String =
            PackPoints.describePointsChange(before, after, name, actor)
    }

    @Nested
    inner class PointSets {
        private val points = array(
            """[{"id": "lps-0-aa", "name": "KGVL", "lat": 34.27, "lon": -83.83, "elevationFt": 1276}, {"id": "lps-1-bb", "name": "KJZP", "lat": 34.45, "lon": -84.46}]""",
        )
        private val set = PackItemView("ps-1", "pointset", "NORTH GA POINTS", points)

        @Test
        fun `shows a pack's set beside this person's own, and keeps its colour and visibility to them`() {
            val e = Editor(PointsKind, listOf(set))
            e.openItem("ps-1")
            e.advance(1_000)
            e.change("ps-1") { it.copy(visible = false, color = "#000000") }
            e.remote(json("""{"type": "patch", "item": "ps-1", "path": [{"id": "lps-1-bb"}], "value": {"name": "KJZP AIRPORT"}}"""))
            e.advance(1_000)
            val shown = e.doc("ps-1")
            assertEquals(Triple(false, "#000000", "KJZP AIRPORT"), Triple(shown.visible, shown.color, shown.points.jsonArray[1].jsonObject.getValue("name").jsonPrimitive.content))
            assertTrue(e.sent.isEmpty())
        }

        @Test
        fun `puts an import in the pack, and renames a set`() {
            val e = Editor(PointsKind, emptyList())
            val made = PackPoints.newPointSetOp(
                "KGVL TAXI POINTS", array("""[{"name": "A1", "lat": 1, "lon": 1}, {"name": "A2", "lat": 2, "lon": 2}]"""), ACTOR,
            ) { "new" } as NewPointSet.Made
            assertNull(e.edit(listOf(made.op)))
            e.openItem("ps-new")
            e.advance(1_000)
            val create = e.sent.single().single()
            assertEquals("item.create", create.getValue("type").jsonPrimitive.content)
            assertEquals("Colin M. added the point set \"KGVL TAXI POINTS\" (2 points).", create.getValue("summary").jsonPrimitive.content)
            assertEquals(listOf("pt-0", "pt-1"), create.getValue("data").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            e.change("ps-new") { it.copy(name = "KGVL TAXI") }
            e.advance(1_000)
            assertSent(
                """[[{"type": "item.rename", "item": "ps-new", "name": "KGVL TAXI", "summary": "Colin M. renamed \"KGVL TAXI POINTS\" to \"KGVL TAXI\"."}]]""",
                e.sent.drop(1),
            )
        }

        @Test
        fun `refuses a set with no points`() {
            assertEquals(NewPointSet.Refused("empty_point_set"), PackPoints.newPointSetOp("EMPTY", JsonArray(emptyList()), ACTOR) { fail("no id for a set refused") })
        }

        @Test
        fun `takes a set someone removed off the map`() {
            val e = Editor(PointsKind, listOf(set))
            e.openItem("ps-1")
            e.advance(1_000)
            e.remote(json("""{"type": "item.delete", "item": "ps-1"}"""))
            e.advance(1_000)
            assertFalse("ps-1" in e.open)
            assertEquals(listOf("NORTH GA POINTS"), e.gone)
        }

        @Test
        fun `gives each point an id no other has`() {
            val ready = PackPoints.pointsForPack(array("""[{"id": "a"}, {"id": "a"}, {"name": "x"}, {"id": 7}]"""))
            assertValue(array("""["a", "a-1", "pt-2", 7]"""), JsonArray(ready.map { it.jsonObject.getValue("id") }), "the ids")
        }

        @Test
        fun `says what changed`() {
            fun say(after: String) = PackPoints.describePointsChange(points, array(after), "NGA", "Sam B.")
            val kgvl = points[0].jsonObject
            val kjzp = points[1].jsonObject
            assertEquals("Sam B. added 1 point to NGA.", say("[$kgvl, $kjzp, {\"id\": \"n\", \"name\": \"NEW\"}]"))
            assertEquals("Sam B. removed 1 point from NGA.", say("[$kjzp]"))
            assertEquals("Sam B. changed KGVL2 in NGA.", say("[${JsonObject(kgvl + ("name" to JsonPrimitive("KGVL2")))}, $kjzp]"))
        }
    }

    // -- Each decision ---------------------------------------------------------------------------------------------------------

    @Nested
    inner class Decisions {
        private val data = json("""{"flightData": {"landingHeading": 270}, "notes": ""}""")
        private val item = PackItemView("lz-1", "lz", "LZ HAWK", data)
        private val shape = json("""{"flightData": {"landingHeading": 270}, "notes": ""}""")
        private val changed = json("""{"flightData": {"landingHeading": 90}, "notes": ""}""")

        private var asked = 0
        private val theirShape: () -> JsonElement? = {
            asked++
            item.data
        }

        private fun reconcile(base: PackBaseline?, item: PackItemView?, mine: EditVersion, readOnly: Boolean = false) =
            PackItemSync.reconcile(base, item, mine, readOnly, theirShape)

        @Test
        fun `an item not in the pack leaves the editor once it was seen there, and is nothing before`() {
            assertEquals(Reconcile.NOTHING, reconcile(null, null, EditVersion("LZ HAWK", shape)))
            assertEquals(Reconcile.REMOVED, reconcile(PackItemSync.settled(item, shape), null, EditVersion("LZ HAWK", shape)))
        }

        @Test
        fun `an item seen for the first time is settled, whatever the editor has`() {
            assertEquals(Reconcile.SETTLE, reconcile(null, item, EditVersion("LZ HAWK", changed)))
            val base = PackItemSync.settled(item, shape)
            assertTrue(base.data === item.data && base.carryFrom === item.data && base.doc === shape && !base.justSent)
            assertEquals("LZ HAWK", base.name)
        }

        @Test
        fun `in step, nothing is done, and the pack's shape is never asked for`() {
            val base = PackItemSync.settled(item, shape)
            assertEquals(Reconcile.NOTHING, reconcile(base, item, EditVersion("LZ HAWK", shape)))
            assertEquals(Reconcile.NOTHING, reconcile(base, item, EditVersion("  LZ HAWK \t", shape)))    // a name is compared trimmed
            assertEquals(0, asked)
        }

        @Test
        fun `a change here waits for the item to be still, or is put back where the pack takes nothing`() {
            val base = PackItemSync.settled(item, shape)
            assertEquals(Reconcile.SEND_LATER, reconcile(base, item, EditVersion("LZ HAWK", changed)))
            assertEquals(Reconcile.SEND_LATER, reconcile(base, item, EditVersion("LZ EAGLE", shape)))
            assertEquals(Reconcile.PUT_BACK, reconcile(base, item, EditVersion("LZ HAWK", changed), readOnly = true))
            assertEquals(0, asked)
        }

        @Test
        fun `someone else's change is taken, unless one waits here, which goes first`() {
            val base = PackItemSync.settled(item, shape)
            val theirs = item.copy(data = changed)
            assertEquals(Reconcile.FLUSH_FIRST, reconcile(base, theirs, EditVersion("LZ HAWK", json("""{"flightData": {"landingHeading": 270}, "notes": "x"}"""))))
            assertEquals(Reconcile.TAKE_THEIRS, PackItemSync.reconcile(base, theirs, EditVersion("LZ HAWK", shape), false) { changed })
            assertEquals(Reconcile.TAKE_THEIRS, reconcile(base, item.copy(name = "LZ OSPREY"), EditVersion("LZ HAWK", shape)))
            // A new instance of the same data (the session made it over again) changes nothing: settled.
            assertEquals(Reconcile.SETTLE, reconcile(base, item.copy(data = json(data.toString())), EditVersion("LZ HAWK", shape)))
        }

        @Test
        fun `right after a send, a change goes later, the pack's version is taken if it differs, and else the item is settled`() {
            val plan = FlushPlan(emptyList(), "LZ HAWK", changed)
            val sent = PackItemSync.sent(PackItemSync.settled(item, shape), plan)
            assertTrue(sent.justSent)
            assertEquals(Reconcile.SEND_LATER, reconcile(sent, item, EditVersion("LZ HAWK", shape)))
            assertEquals(Reconcile.TAKE_THEIRS, reconcile(sent, item, EditVersion("LZ HAWK", changed)))          // the pack does not have it (yet)
            assertEquals(Reconcile.SETTLE, PackItemSync.reconcile(sent, item, EditVersion("LZ HAWK", changed), false) { changed })
            assertEquals(Reconcile.TAKE_THEIRS, PackItemSync.reconcile(sent, item.copy(name = "LZ OSPREY"), EditVersion("LZ HAWK", changed), false) { changed })
        }

        @Test
        fun `a send is the rename, then the change, each with one sentence, and nothing when nothing changed`() {
            val base = PackItemSync.settled(item, shape)
            val describe = ChangeSentence(PackLz::describeLzChange)
            assertNull(PackItemSync.flushPlan("lz-1", base, item, EditVersion("LZ HAWK", shape), { shape }, { shape }, describe, ACTOR))
            val plan = PackItemSync.flushPlan("lz-1", base, item, EditVersion(" LZ EAGLE ", changed), { shape }, { shape }, describe, ACTOR)!!
            assertEquals(listOf("item.rename", "patch"), plan.ops.map { it.getValue("type").jsonPrimitive.content })
            assertEquals(1, plan.ops.map { it["summary"] }.toSet().size)
            assertEquals("LZ EAGLE", plan.sentName)
            assertTrue(plan.sentDoc === changed)
            val sent = PackItemSync.sent(base, plan)
            assertTrue(sent.justSent && sent.doc === changed && sent.name == "LZ EAGLE" && sent.carryFrom === base.carryFrom)
        }

        @Test
        fun `a change that would replace the content whole has no delta, and is taken whole`() {
            val base = PackItemSync.settled(item, shape)
            assertNull(PackItemSync.takeTheirsDelta(base, JsonArray(emptyList())))
            assertEquals(1, PackItemSync.takeTheirsDelta(base, changed)?.size)
        }

        @Test
        fun `a delta is laid over a version operation by operation, and one whose target that version lacks is passed over`() {
            val delta = listOf(
                json("""{"type": "patch", "path": ["graphics", "helicopters", {"id": 102}], "value": {"heading": 270}}"""),
                json("""{"type": "set", "path": ["flightData", "landing_hdg"], "value": "180°"}"""),
            )
            val older = json("""{"graphics": {"helicopters": [{"id": 101, "heading": 90}]}, "flightData": {"landing_hdg": "090°"}}""")
            assertValue(
                json("""{"graphics": {"helicopters": [{"id": 101, "heading": 90}]}, "flightData": {"landing_hdg": "180°"}}"""),
                PackItemSync.rebase(older, delta, "lz"),
                "the older version",
            )
            assertNull(PackItemSync.rebase(null, delta, "lz"))
        }

        @Test
        fun `taking theirs makes the version showing the pack's, and carries their change into an older one, keeping what was done there`() {
            val lz = LzPackKind(PackFixtures.env())
            val hawk = PackItemView(
                "lz-1", "lz", "LZ HAWK",
                json(
                    """{"schemaVersion": 2, "status": "analyzed", "target": {"lat": 34.5, "lon": -84.1, "mgrs": ""},
                        "flightData": {"landing_hdg": "090°"},
                        "graphics": {"helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "heading": 90}, {"id": 102, "lat": 34.501, "lon": -84.1, "heading": 90}]}}""",
                ),
            )
            val localId = PackRef.localId(PACK, "lz-1")
            val showing = DiagramOps.setView(lz.fromItem(localId, hawk, null), json("""{"mapStyle": "topo"}"""))
            val base = PackItemSync.settled(lz, localId, hawk)
            // An undo step from before helicopter 102 was placed, and with 101 somewhere else.
            val older = showing.copy(
                graphics = showing.graphics.copy(helicopters = listOf(json("""{"id": 101, "lat": 34.4, "lon": -84.1, "heading": 90}"""))),
            )
            val theirs = applyOps(
                listOf(hawk),
                listOf(
                    json("""{"type": "patch", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 102}], "value": {"heading": 270}}"""),
                    json("""{"type": "patch", "item": "lz-1", "path": ["flightData"], "value": {"landing_hdg": "270°"}}"""),
                    json("""{"type": "item.rename", "item": "lz-1", "name": "LZ OSPREY"}"""),
                ),
            ).single()
            val taken = PackItemSync.takeTheirs(lz, localId, base, theirs)
            assertTrue(taken.baseline.data === theirs.data && taken.baseline.name == "LZ OSPREY" && !taken.baseline.justSent)

            val now = taken.apply(showing)
            assertValue(lz.currentShape(localId, theirs), lz.docOf(now, theirs.data), "the version showing is the pack's")
            assertEquals("LZ OSPREY", now.name)
            assertEquals("topo", now.view.mapStyle)                      // this person's own
            assertEquals(Reconcile.NOTHING, PackItemSync.reconcile(lz, localId, taken.baseline, theirs, now, readOnly = false))

            val then = taken.apply(older)
            assertEquals(listOf(34.4), then.graphics.helicopters.map { it.jsonObject.getValue("lat").jsonPrimitive.double })  // what was done there
            assertEquals("270°", then.flightData.getValue("landing_hdg").jsonPrimitive.content)                          // and theirs
            assertEquals("LZ OSPREY", then.name)
        }

        @Test
        fun `putting back undoes a change the pack will not take, in every version, keeping each one's own fields`() {
            val lz = LzPackKind(PackFixtures.env())
            val localId = PackRef.localId(PACK, "lz-1")
            val hawk = PackItemView(
                "lz-1", "lz", "LZ HAWK",
                json(
                    """{"schemaVersion": 2, "status": "analyzed", "target": {"lat": 34.5, "lon": -84.1, "mgrs": ""},
                        "graphics": {"helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "heading": 90}]}}""",
                ),
            )
            val opened = DiagramOps.setView(lz.fromItem(localId, hawk, null), json("""{"mapStyle": "topo"}"""))
            val moved = DiagramOps.patchGraphic(DiagramOps.setName(opened, "LZ EAGLE"), "helicopters", JsonPrimitive(101), json("""{"lat": 1}"""))
            val back = PackItemSync.putBack(lz, localId, hawk)
            assertTrue(back.baseline.data === hawk.data && back.baseline.name == "LZ HAWK" && !back.baseline.justSent)
            val now = back.apply(moved)
            assertValue(lz.currentShape(localId, hawk), lz.docOf(now, hawk.data), "the pack's version, whole")
            assertEquals("LZ HAWK", now.name)
            assertEquals("topo", now.view.mapStyle)
            assertEquals(Reconcile.NOTHING, PackItemSync.reconcile(lz, localId, back.baseline, hawk, now, readOnly = true))
            // Taking theirs instead would keep the change, which can never be sent.
            val kept = PackItemSync.takeTheirs(lz, localId, PackItemSync.settled(lz, localId, hawk), hawk).apply(moved)
            assertEquals(1.0, kept.graphics.helicopters.single().jsonObject.getValue("lat").jsonPrimitive.double)
        }

        @Test
        fun `with nothing to measure their change from, each version takes the pack's whole shape and keeps its own fields`() {
            val lz = LzPackKind(PackFixtures.env())
            val localId = PackRef.localId(PACK, "lz-1")
            val hawk = PackItemView("lz-1", "lz", "LZ HAWK", json("""{"schemaVersion": 2, "flightData": {"landing_hdg": "090°"}}"""))
            val mine = DiagramOps.setView(lz.fromItem(localId, hawk, null), json("""{"mapStyle": "vfr-sectional"}"""))
            val taken = PackItemSync.takeTheirs(lz, localId, null, hawk.copy(data = json("""{"schemaVersion": 2, "flightData": {"landing_hdg": "180°"}}""")))
            val now = taken.apply(mine)
            assertEquals("180°", now.flightData.getValue("landing_hdg").jsonPrimitive.content)
            assertEquals("vfr-sectional", now.view.mapStyle)
            assertEquals(mine.id, now.id)
            assertNotNull(taken.baseline.doc)
        }
    }
}
