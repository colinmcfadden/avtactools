package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.ITEM
import app.ezpztac.data.PackEditorRig.Companion.LOCAL
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.missionpacks.PackDiff
import app.ezpztac.missionpacks.PackLz
import app.ezpztac.model.AircraftName
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.DiagramView
import app.ezpztac.model.Doghouses
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft
import app.ezpztac.model.UnitMarkers
import app.ezpztac.model.Units
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.PlanningGraphics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Every setting of an LZ/PZ's planning graphics in a Mission Pack, made on one device as the app makes it (the inspector's edits, the planning tools, a drag, the
 * boundary tools, an analysis coming back) and found, identical, on the other device's open document and in what the pack holds; on the other device's copy
 * while it has the LZ/PZ closed, and once it opens it; undone, as a change of its own; and made by two people at once, where two settings of one graphic both
 * stand and one setting made twice stands as the later made it, whole. The owner's rule (2026-10-13): everything syncs on change but each person's view of it
 * (the base map, the LZ outline, the slope map), which stays theirs. Colin's device is A, Sam's is B, over one pack server and one clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LzPackSettingsTest {
    // Each test runs every setting on a pair of devices of its own; a regression that loops without end fails here rather than holding the build.
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(300, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        /** Longer than a poll (3 s), and the sends either side of it. */
        const val POLL = 4_000L

        val CROSSHAIR = LatLon(34.5031, -84.0972)
        const val GRID = "16S GC 29100 55600"

        /** Sam's own view of LZ HAWK: never the pack's. */
        val SAMS_VIEW = DiagramView(mapStyle = "topo", showLZOutline = false, showHeatmap = true)

        /** LZ HAWK, analysed, with one of each planning graphic, its flight data what its doghouses give. */
        val HAWK: JsonObject = json(
            """{"schemaVersion": 2, "status": "analyzed",
                "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"}, "mapData": {"mgrs": "16S GC 28864 55349"},
                "flightData": {"landing_hdg": "090°", "takeoff_hdg": "270°"},
                "analysis": {"customLZ": null, "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09], [34.5, -84.09]],
                             "results": {"area": 1000, "capacity": 3, "slope": 4.5}, "gridElevation": "1200", "latLong": "N 34°30' W 084°06'"},
                "graphics": {
                  "doghouses": [
                    {"id": "dh-sp", "role": "takeoff", "lat": 34.5, "lon": -84.103, "id_val": "[SP1]", "heading": "270°", "time": "01+57", "dist": "3.13km", "airspeed": "60 KIAS"},
                    {"id": "dh-rp", "role": "landing", "lat": 34.4985, "lon": -84.103, "id_val": "[RP1]", "heading": "090°", "time": "02+10", "dist": "5.20km", "airspeed": "40 KIAS"}],
                  "helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l"},
                                  {"id": 102, "lat": 34.501, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l", "label": "CHALK 2"}],
                  "pzMarkers": [{"id": "pz-1", "lat": 34.502, "lon": -84.102, "tipLat": 34.502, "tipLon": -84.104}],
                  "sectorsOfFire": [{"id": "sec-1", "points": [{"lat": 34.503, "lng": -84.1}, {"lat": 34.501, "lng": -84.099}, {"lat": 34.501, "lng": -84.101}]}],
                  "goArounds": [{"id": "ga-1", "lat": 34.499, "lon": -84.1, "direction": "left", "rotation": 0}],
                  "units": [{"id": "unit-1", "sidc": "SHGPUCA---D----", "uniqueDesignation": "A/1-171", "higherFormation": "2-101", "lat": 34.498, "lon": -84.098}],
                  "measurements": [], "exportBox": null}}""",
        )

        val CHALK_1 = GraphicRef("helicopters", "101")
        val CHALK_2 = GraphicRef("helicopters", "102")
        val PZ = GraphicRef("pzMarkers", "pz-1")
        val SECTOR = GraphicRef("sectorsOfFire", "sec-1")
        val GO_AROUND = GraphicRef("goArounds", "ga-1")
        val SP = GraphicRef("doghouses", "dh-sp")
        val RP = GraphicRef("doghouses", "dh-rp")
        val UNIT = GraphicRef("units", "unit-1")
    }

    // -- What a setting is ---------------------------------------------------------------------------------------------------------------------------

    /** An item as the pack holds it, or a device's document as its item's data would be: its name and its data. */
    private class Item(val name: String, val data: JsonObject)

    /**
     * One setting: how the app makes it on a device's open LZ/PZ ([change]), what it is ([read], from an item), and how many steps of the session's undo
     * take it back (0: a change that is not the person's own edit, such as an analysis coming back).
     */
    private class Setting(val name: String, val read: (Item) -> JsonElement?, val undos: Int = 1, val change: suspend PackEditorRig.() -> Unit) {
        override fun toString() = name
    }

    private fun graphicOf(item: Item, ref: GraphicRef): JsonObject? =
        (item.data["graphics"] as? JsonObject)?.get(ref.collection)?.jsonArray?.firstOrNull { DiagramOps.idText((it as? JsonObject)?.get("id")) == ref.key } as? JsonObject

    /** The whole graphic: what a graphic placed, or deleted, is. */
    private fun graphic(ref: GraphicRef): (Item) -> JsonElement? = { graphicOf(it, ref) ?: JsonNull }

    /** Some of a graphic's fields: what a setting of it is. */
    private fun fields(ref: GraphicRef, vararg names: String): (Item) -> JsonElement? = { item ->
        graphicOf(item, ref)?.let { g -> JsonObject(names.associateWith { g[it] ?: JsonNull }) } ?: JsonNull
    }

    private fun analysis(vararg names: String): (Item) -> JsonElement? = { item ->
        val a = item.data["analysis"] as? JsonObject
        JsonObject(names.associateWith { a?.get(it) ?: JsonNull } + ("status" to (item.data["status"] ?: JsonNull)))
    }

    // -- How the app makes each, on a device -------------------------------------------------------------------------------------------------------------

    private fun PackEditorRig.doc(): Diagram = checkNotNull(diagrams.active.value)

    private fun PackEditorRig.held(ref: GraphicRef): JsonObject = checkNotNull(DiagramOps.graphic(doc(), ref.collection, ref.key)) { "${ref.key} is not on ${me.name}'s map" }

    /** An edit of the held graphic as the inspector makes one (GraphicsViewModel.edit): a patch worked out from the graphic as it is, one step. */
    private fun PackEditorRig.inspect(ref: GraphicRef, label: String, patch: (String, JsonObject) -> JsonObject?) {
        val graphic = held(ref)
        val changes = checkNotNull(patch(ref.collection, graphic)) { "$label cannot be done to ${ref.key}" }
        diagrams.edit(label) { DiagramOps.patchGraphic(it, ref.collection, graphic["id"], changes) }
    }

    private fun PackEditorRig.nudge(ref: GraphicRef, northFt: Double, eastFt: Double) =
        inspect(ref, "Move") { c, g -> GraphicEdits.nudge(c, g, northFt / Units.METERS_TO_FEET, eastFt / Units.METERS_TO_FEET) }

    /** What a planning tool makes at [at] ([GraphicPlacement.make]), placed as one step. */
    private fun PackEditorRig.place(collection: String, made: JsonObject?) {
        diagrams.edit("Place") { DiagramOps.upsertGraphic(it, collection, checkNotNull(made)) }
    }

    private fun PackEditorRig.drag(target: DragTarget, from: LatLon, northM: Double, eastM: Double) {
        val drag = MapDrag(diagrams, routes, ThreatStore(NoThreatVault, scope, dispatcher) { 1_000L })
        assertTrue("picked up", drag.start(target, from))
        for (i in 1..5) {
            drag.move(GraphicEdits.offset(from, northM * i / 6, eastM * i / 6))
            advance(16)
        }
        drag.end(GraphicEdits.offset(from, northM, eastM))
    }

    private object NoThreatVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) = Unit
        override fun wipe() = Unit
    }

    /** The unit builder applied to the held unit (GraphicsViewModel.updateUnit), the builder having been changed from the unit as it showed it by [edit]. */
    private fun PackEditorRig.builder(edit: (UnitDraft) -> UnitDraft) {
        val shown = UnitDraft.of(held(UNIT))
        inspect(UNIT, "Unit") { _, g -> edit(shown).patchOver(shown, UnitDraft.hasCode(g)) }
    }

    private fun named(name: String) = JsonObject(mapOf(AircraftName.FIELD to JsonPrimitive(name)))

    /** What an analysis writes into a diagram (AnalysisService.patch), applied as one comes back: through the session's update, quietly. */
    private suspend fun PackEditorRig.analysed(area: Int, capacity: Int, elevation: String, boundary: String = "[[34.5, -84.1], [34.512, -84.1], [34.512, -84.088], [34.5, -84.088]]") {
        val patch = json(
            """{"latLong": "N 34°30' W 084°06'", "gridElevation": "$elevation", "customLZ": null, "detectedLZ": $boundary,
                "results": {"area": $area, "capacity": $capacity, "slope": 4.5}}""",
        )
        assertTrue(diagrams.update(LOCAL) { DiagramOps.afterAnalysis(it, patch) })
    }

    private val SETTINGS: List<Setting> = listOf(
        Setting("an aircraft placed, as the mission aircraft", graphic(GraphicRef("helicopters", "1760000000500"))) {
            val down = doc().graphics.helicopters.mapNotNull(PlanningGraphics.Aircraft::of)
            place("helicopters", PlanningGraphics.placeHelicopter(CROSSHAIR, down, AircraftProfile.FALLBACK, { AircraftProfile.FALLBACK }, JsonPrimitive(1760000000500)))
        },
        Setting("an aircraft placed as a custom airframe of the person's own: what it carries is its profile's slug", graphic(GraphicRef("helicopters", "1760000000600"))) {
            val mine = AircraftProfile(id = 77, slug = "colin-ch47", name = "CH-47F (Colin's)", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29, rotorTipClearanceM = 75.0)
            val down = doc().graphics.helicopters.mapNotNull(PlanningGraphics.Aircraft::of)
            place("helicopters", PlanningGraphics.placeHelicopter(CROSSHAIR, down, mine, { AircraftProfile.FALLBACK }, JsonPrimitive(1760000000600)))
        },
        Setting("a PZ marker placed", graphic(GraphicRef("pzMarkers", "pz-1760000000500"))) { place("pzMarkers", PlanningGraphics.createPzMarker(CROSSHAIR.lat, CROSSHAIR.lon, "pz-1760000000500")) },
        Setting("a sector of fire placed", graphic(GraphicRef("sectorsOfFire", "sec-1760000000500"))) {
            place("sectorsOfFire", PlanningGraphics.createSectorOfFire(CROSSHAIR.lat, CROSSHAIR.lon, "sec-1760000000500"))
        },
        Setting("a go-around placed", graphic(GraphicRef("goArounds", "ga-1760000000500"))) {
            place("goArounds", PlanningGraphics.createGoAround(CROSSHAIR.lat, CROSSHAIR.lon, "right", "ga-1760000000500"))
        },
        Setting("a unit placed from the builder", graphic(GraphicRef("units", "unit-1760000000500"))) {
            place("units", UnitMarkers.create(UnitDraft("N", "UCA---", "E", "B/2", "1-22").config, CROSSHAIR, 0.0, "unit-1760000000500"))
        },
        Setting("an aircraft nudged north", fields(CHALK_1, "lat", "lon")) { nudge(CHALK_1, northFt = 200.0, eastFt = 0.0) },
        Setting("an aircraft put at the crosshair", fields(CHALK_1, "lat", "lon")) { inspect(CHALK_1, "Move") { c, g -> GraphicEdits.moveTo(c, g, CROSSHAIR) } },
        Setting("an aircraft put at a typed grid", fields(CHALK_1, "lat", "lon")) {
            val found = PlaceSearch.resolve(GRID) as PlaceResult.Found
            inspect(CHALK_1, "Move") { c, g -> GraphicEdits.moveTo(c, g, found.at) }
        },
        Setting("an aircraft dragged", fields(CHALK_1, "lat", "lon")) { drag(DragTarget.Graphic(CHALK_1), LatLon(34.5, -84.1), northM = 40.0, eastM = 25.0) },
        Setting("an aircraft turned", fields(CHALK_1, "rotation")) { inspect(CHALK_1, "Turn") { c, g -> GraphicEdits.rotateBy(c, g, 15.0) } },
        Setting("an aircraft's heading typed", fields(CHALK_1, "rotation")) { inspect(CHALK_1, "Turn") { c, g -> GraphicEdits.setRotation(c, g, 200.0) } },
        // As GraphicsViewModel.setAircraftName writes a name (the owner's request of 2026-10-22), and takes one away: null, as a pack sends a field that went.
        Setting("an aircraft's name", fields(CHALK_1, AircraftName.FIELD)) { inspect(CHALK_1, "Aircraft name") { _, _ -> named("CHALK 1") } },
        Setting("an aircraft's name taken away", fields(CHALK_2, AircraftName.FIELD)) { inspect(CHALK_2, "Aircraft name") { _, _ -> JsonObject(mapOf(AircraftName.FIELD to JsonNull)) } },
        Setting("an aircraft deleted", graphic(CHALK_2)) { diagrams.edit("Delete helicopter") { DiagramOps.removeGraphic(it, "helicopters", held(CHALK_2)["id"]) } },
        Setting("a PZ marker's reach", fields(PZ, "lat", "lon", "tipLat", "tipLon")) {
            inspect(PZ, "PZ reach") { _, g -> GraphicEdits.setPzReach(g, checkNotNull(GraphicEdits.pzReachM(g)) + 30.0) }
        },
        Setting("a PZ marker's tip put at the crosshair", fields(PZ, "lat", "lon", "tipLat", "tipLon")) { inspect(PZ, "PZ tip") { _, g -> GraphicEdits.setPzTip(g, CROSSHAIR) } },
        Setting("a PZ marker's tip dragged", fields(PZ, "lat", "lon", "tipLat", "tipLon")) {
            drag(DragTarget.PzTip(PZ), checkNotNull(GraphicEdits.pzTip(held(PZ))), northM = 30.0, eastM = 0.0)
        },
        Setting("a PZ marker turned", fields(PZ, "lat", "lon", "tipLat", "tipLon")) { inspect(PZ, "Turn") { c, g -> GraphicEdits.rotateBy(c, g, 45.0) } },
        Setting("a PZ marker moved", fields(PZ, "lat", "lon", "tipLat", "tipLon")) { nudge(PZ, northFt = 0.0, eastFt = 100.0) },
        Setting("a sector of fire moved, its shape with it", fields(SECTOR, "points")) { inspect(SECTOR, "Move") { c, g -> GraphicEdits.moveTo(c, g, CROSSHAIR) } },
        Setting("a go-around's side", fields(GO_AROUND, "direction")) { inspect(GO_AROUND, "Go-around") { _, g -> GraphicEdits.setGoAroundDirection(g, "right") } },
        Setting("a go-around turned", fields(GO_AROUND, "rotation")) { inspect(GO_AROUND, "Turn") { c, g -> GraphicEdits.rotateBy(c, g, -15.0) } },
        Setting("a go-around moved", fields(GO_AROUND, "lat", "lon")) { nudge(GO_AROUND, northFt = -50.0, eastFt = 0.0) },
        Setting("a doghouse's label", fields(RP, "id_val")) { inspect(RP, "Doghouse label") { _, _ -> Doghouses.labelPatch("[RP2]") } },
        Setting("a doghouse's time", fields(RP, "time")) { inspect(RP, "Doghouse time") { _, _ -> Doghouses.timePatch("4+30") } },
        Setting("a doghouse's distance", fields(RP, "dist")) { inspect(RP, "Doghouse distance") { _, _ -> Doghouses.distancePatch("4.2") } },
        Setting("a doghouse's airspeed", fields(RP, "airspeed")) { inspect(RP, "Doghouse airspeed") { _, _ -> Doghouses.airspeedPatch("70") } },
        Setting("the landing doghouse's heading, and the landing heading its flight data takes from it", { item ->
            JsonArray(listOf(fields(RP, "heading")(item) ?: JsonNull, (item.data["flightData"] as? JsonObject)?.get("landing_hdg") ?: JsonNull))
        }) { inspect(RP, "Turn") { c, g -> GraphicEdits.setRotation(c, g, 250.0) } },
        Setting("a doghouse moved", fields(SP, "lat", "lon")) { nudge(SP, northFt = 0.0, eastFt = -100.0) },
        Setting("a unit's symbol and labels, the builder applied", fields(UNIT, "sidc", "uniqueDesignation", "higherFormation")) {
            builder { it.copy(affiliation = "F", echelon = "E", uniqueDesignation = "C/1-171") }
        },
        Setting("a unit moved", fields(UNIT, "lat", "lon")) { nudge(UNIT, northFt = 75.0, eastFt = 75.0) },
        Setting("a boundary drawn by hand", analysis("customLZ", "detectedLZ", "results", "gridElevation", "latLong"), undos = 2) {
            val drawing = BoundaryDrawing(diagrams)
            assertEquals(null, drawing.start())
            listOf(LatLon(34.5, -84.1), LatLon(34.511, -84.1), LatLon(34.511, -84.091)).forEach { assertTrue(drawing.addPoint(it)) }
            assertEquals(null, drawing.finish())
        },
        Setting("a corner of the boundary deleted", analysis("detectedLZ")) {
            val corners = BoundaryCorners(diagrams)
            corners.select(BoundaryCornerRef(drawn = false, index = 1))
            assertEquals(null, corners.deleteHeld())
        },
        Setting("an analysis that came back", analysis("customLZ", "detectedLZ", "results", "gridElevation", "latLong"), undos = 0) {
            analysed(area = 1440, capacity = 4, elevation = "1210")
        },
        Setting("the LZ/PZ's name", { JsonPrimitive(it.name) }) { diagrams.edit("Rename") { DiagramOps.setName(it, "LZ EAGLE") } },
    )

    // -- Two devices --------------------------------------------------------------------------------------------------------------------------------------

    private fun PackEditorRig.item(): Item = Item(doc().name, PackLz.lzItemData(doc()))

    private fun PackEditorRig.serverItem(): Item = Item(checkNotNull(server.name(PACK, ITEM)), serverData())

    private fun PackEditorRig.copyItem(): Item = checkNotNull(engine.open.value?.item(ITEM)) { "${me.name}'s copy has no $ITEM" }.let { Item(it.name, it.data.jsonObject) }

    private fun assertSameValue(setting: Setting, where: String, expected: JsonElement?, actual: JsonElement?) =
        assertTrue("$setting, $where: expected $expected, was $actual", PackDiff.sameData(expected, actual))

    /** For each of [settings], a pack of Colin's with LZ HAWK in it, Sam an editor, on two devices of their own, made as [open] says: [body] runs on them. */
    private fun eachOnTwoDevices(settings: List<Setting> = SETTINGS, open: Boolean = true, body: suspend TestScope.(Setting, PackEditorRig, PackEditorRig) -> Unit) = runTest {
        for (setting in settings) {
            val b = PackEditorRig(this)
            val a = b.other(COLIN)
            try {
                b.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
                a.start(); a.openPack(); a.openItem(ITEM)
                b.start(); b.openPack()
                if (open) {
                    b.openItem(ITEM)
                    b.diagrams.setQuietly { it.copy(view = SAMS_VIEW) }
                    b.advance(STILL)
                }
                body(setting, a, b)
            } finally {
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun `every setting made on one device is on the other device's open LZ-PZ and in the pack, identical, and the other device's view stays its own`() =
        eachOnTwoDevices { setting, a, b ->
            val before = setting.read(a.serverItem())
            setting.change(a)
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL)
            val now = setting.read(a.serverItem())
            assertFalse("$setting changed nothing in the pack", PackDiff.sameData(before, now))
            assertSameValue(setting, "Colin's device", now, setting.read(a.item()))
            assertSameValue(setting, "Sam's device", now, setting.read(b.item()))
            assertEquals("$setting: Sam's view is his own", SAMS_VIEW, b.doc().view)
            assertEquals("$setting: Sam sent nothing", emptyList<List<JsonObject>>(), b.sent())
            // The rest of the LZ/PZ is the same on both devices too.
            assertTrue("$setting: the whole LZ/PZ", PackDiff.sameData(a.item().data, b.item().data))
        }

    @Test
    fun `every setting made on one device reaches the other device's copy while it has the LZ-PZ closed, and opens there identical`() =
        eachOnTwoDevices(open = false) { setting, a, b ->
            setting.change(a)
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL)
            val now = setting.read(a.serverItem())
            assertSameValue(setting, "Sam's copy", now, setting.read(b.copyItem()))
            b.openItem(ITEM)
            assertSameValue(setting, "Sam's device, opened after", now, setting.read(b.item()))
            b.advance(STILL); b.advance(POLL)
            assertEquals("$setting: opening it sent nothing", emptyList<List<JsonObject>>(), b.sent())
        }

    @Test
    fun `undoing a setting is a change too, and the other device shows it undone`() =
        eachOnTwoDevices(SETTINGS.filter { it.undos > 0 }) { setting, a, b ->
            val before = setting.read(a.serverItem())
            setting.change(a)
            a.advance(STILL)
            b.advance(POLL)
            repeat(setting.undos) { assertNotNull("$setting: something to undo", a.diagrams.undo()) }
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL)
            assertSameValue(setting, "undone in the pack", before, setting.read(a.serverItem()))
            assertSameValue(setting, "undone on Colin's device", before, setting.read(a.item()))
            assertSameValue(setting, "undone on Sam's device", before, setting.read(b.item()))
        }

    // -- Both at once -------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Colin makes [first] and Sam [second] at once, Sam's made before he hears of Colin's and reaching the pack after it. With [both] they are two settings
     * and each stands, on both devices and in the pack; without, one setting made twice, and the later (Sam's) stands whole on both and in the pack.
     */
    private fun atOnce(first: Setting, second: Setting, both: Boolean) = eachOnTwoDevices(listOf(first)) { _, a, b ->
        first.change(a)
        a.advance(STILL)
        second.change(b)
        val sams = second.read(b.item())
        b.advance(STILL)
        a.advance(POLL); a.advance(POLL); b.advance(POLL)
        val colins = first.read(a.serverItem())
        for ((who, item) in listOf("the pack" to a.serverItem(), "Colin's device" to a.item(), "Sam's device" to b.item())) {
            assertSameValue(second, "$who, beside $first", sams, second.read(item))
            if (both) assertSameValue(first, "$who, beside $second", colins, first.read(item))
        }
        if (both) assertFalse("$first did not stand", PackDiff.sameData(first.read(Item("LZ HAWK", HAWK)), colins))
        assertTrue("the whole LZ/PZ is the same on both devices", PackDiff.sameData(a.item().data, b.item().data))
    }

    private fun setting(name: String): Setting = SETTINGS.single { it.name == name }

    @Test
    fun `two settings of one graphic made at once both stand`() {
        for ((first, second) in listOf(
            "an aircraft nudged north" to "an aircraft turned",
            "an aircraft's name" to "an aircraft turned",
            "a doghouse's label" to "a doghouse's time",
            "a go-around's side" to "a go-around moved",
            "a unit moved" to "a unit's symbol and labels, the builder applied",
            "a doghouse's distance" to "the landing doghouse's heading, and the landing heading its flight data takes from it",
        )) atOnce(setting(first), setting(second), both = true)
        // Two parts of one unit, each in the builder on a device of its own: the designation here, the echelon there.
        atOnce(
            Setting("the unit's designation", fields(UNIT, "uniqueDesignation")) { builder { it.copy(uniqueDesignation = "B/1-171") } },
            Setting("the unit's echelon", fields(UNIT, "sidc")) { builder { it.copy(echelon = "E") } },
            both = true,
        )
        // Each of the two headings the flight data takes from its doghouse.
        atOnce(
            Setting("the landing heading", { JsonArray(listOf(fields(RP, "heading")(it) ?: JsonNull, (it.data["flightData"] as? JsonObject)?.get("landing_hdg") ?: JsonNull)) }) {
                inspect(RP, "Turn") { c, g -> GraphicEdits.setRotation(c, g, 250.0) }
            },
            Setting("the takeoff heading", { JsonArray(listOf(fields(SP, "heading")(it) ?: JsonNull, (it.data["flightData"] as? JsonObject)?.get("takeoff_hdg") ?: JsonNull)) }) {
                inspect(SP, "Turn") { c, g -> GraphicEdits.setRotation(c, g, 300.0) }
            },
            both = true,
        )
    }

    @Test
    fun `one setting made on two devices at once stands as the later made it, whole`() {
        atOnce(
            setting("an aircraft's heading typed"),
            Setting("an aircraft's heading typed elsewhere", fields(CHALK_1, "rotation")) { inspect(CHALK_1, "Turn") { c, g -> GraphicEdits.setRotation(c, g, 45.0) } },
            both = false,
        )
        atOnce(
            setting("an aircraft's name"),
            Setting("an aircraft's name typed elsewhere", fields(CHALK_1, AircraftName.FIELD)) { inspect(CHALK_1, "Aircraft name") { _, _ -> named("20-27345") } },
            both = false,
        )
        atOnce(
            setting("a doghouse's time"),
            Setting("a doghouse's time typed elsewhere", fields(RP, "time")) { inspect(RP, "Doghouse time") { _, _ -> Doghouses.timePatch("5+10") } },
            both = false,
        )
        // A PZ marker's reach here and its place there: its arrow is one value, the later whole.
        atOnce(setting("a PZ marker's reach"), setting("a PZ marker moved"), both = false)
        // A unit's designation typed in the builder on each device: the later stands. Its symbol is one code, whatever part of it each changed: the later.
        atOnce(
            Setting("a unit's designation", fields(UNIT, "uniqueDesignation")) { builder { it.copy(uniqueDesignation = "C/1-171") } },
            Setting("a unit's designation typed elsewhere", fields(UNIT, "uniqueDesignation")) { builder { it.copy(uniqueDesignation = "D/1-171") } },
            both = false,
        )
        atOnce(
            Setting("a unit's echelon", fields(UNIT, "sidc")) { builder { it.copy(echelon = "E") } },
            Setting("a unit's affiliation chosen elsewhere", fields(UNIT, "sidc")) { builder { it.copy(affiliation = "F") } },
            both = false,
        )
        // Two analyses at once, of two boundaries: the later one's boundary, results, grid elevation and words, never one's boundary with the other's results.
        atOnce(
            Setting("an analysis", analysis("detectedLZ", "results", "gridElevation", "latLong"), undos = 0) { analysed(area = 1440, capacity = 4, elevation = "1210") },
            Setting("another analysis", analysis("detectedLZ", "results", "gridElevation", "latLong"), undos = 0) {
                analysed(area = 1000, capacity = 3, elevation = "1200", boundary = "[[34.5, -84.1], [34.511, -84.1], [34.511, -84.091]]")
            },
            both = false,
        )
    }

    // -- What only one platform makes, and what the pack makes ------------------------------------------------------------------------------------------

    @Test
    fun `a new LZ-PZ made on one device reaches the other with its target and grid, opened there as it was made`() = runTest {
        val b = PackEditorRig(this)
        val a = b.other(COLIN)
        try {
            b.pack(PACK, "OP DK")
            a.start(); a.openPack()
            b.start(); b.openPack()
            val local = checkNotNull(a.workspace.createLz(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), null))
            a.advance(STILL); b.advance(POLL)
            val item = checkNotNull(app.ezpztac.missionpacks.PackRef.parse(local)).item
            b.openItem(item)
            for (rig in listOf(a, b)) {
                assertEquals(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), rig.doc().target)
                assertEquals("LZ/PZ 1", rig.doc().name)
            }
            assertTrue(PackDiff.sameData(a.server.data(PACK, item), PackLz.lzItemData(b.doc())))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `headings an LZ-PZ's doghouses give that its item lacks are owed by each device that opens it, and the pack has them once`() = runTest {
        val b = PackEditorRig(this)
        val a = b.other(COLIN)
        try {
            // As an older client wrote it: doghouses with headings, and a flight data that does not say them.
            b.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", JsonObject(HAWK + ("flightData" to JsonObject(emptyMap())))))
            a.start(); a.openPack(); a.openItem(ITEM)
            b.start(); b.openPack(); b.openItem(ITEM)
            a.advance(STILL); b.advance(STILL)
            a.advance(POLL); b.advance(POLL); a.advance(POLL)
            val owed = json("""{"landing_hdg": "090°", "takeoff_hdg": "270°"}""")
            assertTrue(PackDiff.sameData(owed, a.serverData()["flightData"]))
            for (rig in listOf(a, b)) assertTrue(PackDiff.sameData(owed, rig.doc().flightData))
            // Each sent them once at most, and then nothing: the second finds the pack has them.
            assertTrue("${a.sent()} ${b.sent()}", a.sent().size + b.sent().size in 1..2)
            a.advance(POLL); b.advance(POLL)
            assertTrue(a.sent().size + b.sent().size <= 2)
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `a heading the web typed for the flight data stands on both devices, and neither puts the doghouse's back over it`() =
        eachOnTwoDevices(listOf(setting("a doghouse's label"))) { _, a, b ->
            // What the web's flight data field sends: the landing heading alone, its doghouse as it was.
            a.theirs(json("""{"type": "patch", "item": "$ITEM", "path": ["flightData"], "value": {"landing_hdg": "123°"}}"""))
            b.advance(POLL)
            a.advance(STILL); b.advance(STILL); a.advance(POLL); b.advance(POLL)
            for (rig in listOf(a, b)) assertEquals(JsonPrimitive("123°"), rig.doc().flightData["landing_hdg"])
            assertEquals(JsonPrimitive("123°"), a.serverData().getValue("flightData").jsonObject["landing_hdg"])
            assertEquals(emptyList<List<JsonObject>>(), a.sent() + b.sent())
        }

    @Test
    fun `an analysis that comes back once another LZ-PZ is open is sent all the same, and reaches the other device`() =
        eachOnTwoDevices(listOf(setting("an analysis that came back"))) { setting, a, b ->
            a.server.elsewhere(PACK, COLIN.id, create("lz-2", "lz", "LZ CROW", HAWK))
            a.advance(POLL)
            a.openItem("lz-2")                                                                 // the person moved on while the analysis ran
            setting.change(a)                                                                  // to LZ HAWK, which is not open now
            a.advance(STILL); b.advance(POLL); b.advance(POLL)
            val now = setting.read(a.serverItem())
            assertSameValue(setting, "Sam's device", now, setting.read(b.item()))
            assertTrue("it is the analysis", PackDiff.sameData(json("""{"area": 1440, "capacity": 4, "slope": 4.5}"""), (now as JsonObject)["results"]))
        }
}
