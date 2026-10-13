package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.ITEM
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.Units
import app.ezpztac.planning.GraphicEdits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * A Mission Pack's LZ/PZ open on two devices at once, Colin's (A) and Sam's (B), each with its own diagram session, pack engine and copy, over one pack
 * server and one clock, as the owner tries one. A graphic's place is one value and so is the target ([app.ezpztac.missionpacks.LzPackKind.keepWhole]):
 * each move is made the way the app makes it (the inspector's nudge pad, which changes one coordinate per arrow, and [MapDrag]'s drag) and checked where
 * the map draws it from, both devices' open documents, and in what the pack holds: where one of the two put it, never half of each.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LzPackTwoDevicesTest {
    // A regression that loops without end fails here rather than holding the build (PackEditorRig.MAX_BATCHES cuts a loop of sends short sooner).
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        /** Longer than a poll (3 s), and the sends either side of it. */
        const val POLL = 4_000L

        val CHALK_1: JsonPrimitive = JsonPrimitive(101)

        /** LZ HAWK, analysed, with one of each graphic that has a place, and a sector. */
        val HAWK: JsonObject = json(
            """{"schemaVersion": 2, "status": "analyzed",
                "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"}, "mapData": {"mgrs": "16S GC 28864 55349"},
                "flightData": {"landing_hdg": "090°"},
                "analysis": {"customLZ": null, "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "results": {"area": 1},
                             "gridElevation": "1200", "latLong": ""},
                "graphics": {
                  "helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l"},
                                  {"id": 102, "lat": 34.501, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l"}],
                  "pzMarkers": [{"id": "pz-1", "lat": 34.502, "lon": -84.102, "tipLat": 34.502, "tipLon": -84.104}],
                  "sectorsOfFire": [{"id": "sec-1", "points": [{"lat": 34.503, "lng": -84.1}, {"lat": 34.501, "lng": -84.099}, {"lat": 34.501, "lng": -84.101}]}],
                  "goArounds": [{"id": "ga-1", "lat": 34.499, "lon": -84.1, "direction": "left", "rotation": 0}],
                  "doghouses": [{"id": "dh-rp", "role": "landing", "lat": 34.5, "lon": -84.103, "id_val": "[RP1]", "heading": "090°", "time": "02+10",
                                 "dist": "5.20km", "airspeed": "40 KIAS"}],
                  "units": [{"id": "unit-1", "sidc": "SFGPUCI----D---", "uniqueDesignation": "A", "higherFormation": "1-327", "lat": 34.498, "lon": -84.098}],
                  "measurements": [], "exportBox": null}}""",
        )

        /** Each graphic with a place, as the map holds it. */
        val PLACED: List<GraphicRef> = listOf(
            GraphicRef("helicopters", "101"), GraphicRef("goArounds", "ga-1"), GraphicRef("doghouses", "dh-rp"), GraphicRef("units", "unit-1"),
            GraphicRef("pzMarkers", "pz-1"),
        )
    }

    private fun twoDevices(body: suspend TestScope.(a: PackEditorRig, b: PackEditorRig) -> Unit) = runTest {
        val b = PackEditorRig(this)
        val a = b.other(COLIN)
        try {
            body(a, b)
        } finally {
            a.close()
            b.close()
        }
    }

    /** OP DK with LZ HAWK in it, open on both devices. */
    private suspend fun opened(a: PackEditorRig, b: PackEditorRig) {
        b.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", HAWK))
        a.start(); a.openPack(); a.openItem(ITEM)
        b.start(); b.openPack(); b.openItem(ITEM)
    }

    private fun PackEditorRig.doc(): Diagram = checkNotNull(diagrams.active.value)

    private fun PackEditorRig.graphic(ref: GraphicRef): JsonObject = checkNotNull(DiagramOps.graphic(doc(), ref.collection, ref.key)) { "${ref.key} is not on $me's map" }

    private fun PackEditorRig.serverGraphic(ref: GraphicRef): JsonObject =
        serverData().getValue("graphics").jsonObject.getValue(ref.collection).jsonArray.single { DiagramOps.idText(it.jsonObject["id"]) == ref.key }.jsonObject

    // The graphic's place: its anchor, and for a PZ marker its tip too.
    private fun placeOf(graphic: JsonObject, ref: GraphicRef): List<Double> =
        (if (ref.collection == "pzMarkers") listOf("lat", "lon", "tipLat", "tipLon") else listOf("lat", "lon")).map { graphic.getValue(it).jsonPrimitive.double }

    /** The inspector's nudge pad (GraphicsViewModel.nudge): one arrow moves the held graphic [northFt] north or [eastFt] east, one edit. */
    private fun PackEditorRig.nudge(ref: GraphicRef, northFt: Double, eastFt: Double) {
        val graphic = graphic(ref)
        val patch = checkNotNull(GraphicEdits.nudge(ref.collection, graphic, northFt / Units.METERS_TO_FEET, eastFt / Units.METERS_TO_FEET))
        diagrams.edit("Move") { DiagramOps.patchGraphic(it, ref.collection, graphic["id"], patch) }
    }

    /** A long press on the graphic, the finger moved in [frames] steps, and lifted [northM] north and [eastM] east of where it picked it up ([MapDrag]). */
    private fun PackEditorRig.drag(ref: GraphicRef, northM: Double, eastM: Double, frames: Int = 10) {
        val drag = MapDrag(diagrams, routes, ThreatStore(NoThreatVault, scope, dispatcher) { 1_000L })
        val start = checkNotNull(GraphicEdits.position(ref.collection, graphic(ref)))
        assertTrue("picked up", drag.start(DragTarget.Graphic(ref), start))
        for (i in 1..frames) {
            drag.move(GraphicEdits.offset(start, northM * i / (frames + 1), eastM * i / (frames + 1)))
            advance(16)
        }
        drag.end(GraphicEdits.offset(start, northM, eastM))
    }

    private object NoThreatVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) = Unit
        override fun wipe() = Unit
    }

    // Both devices show [ref] at [place], and so does the pack.
    private fun assertPlace(a: PackEditorRig, b: PackEditorRig, ref: GraphicRef, place: List<Double>) {
        assertEquals("${ref.key} on Colin's device", place, placeOf(a.graphic(ref), ref))
        assertEquals("${ref.key} on Sam's device", place, placeOf(b.graphic(ref), ref))
        assertEquals("${ref.key} in the pack", place, placeOf(a.serverGraphic(ref), ref))
    }

    @Test
    fun `a graphic nudged north on one device and east on the other stands whole where the later move put it, for every graphic with a place`() {
        for (ref in PLACED) twoDevices { a, b ->
            opened(a, b)
            // Colin's arrow north goes first; Sam's arrow east, made from where he saw it before hearing of Colin's, reaches the pack later.
            a.nudge(ref, northFt = 200.0, eastFt = 0.0)
            a.advance(STILL)
            b.nudge(ref, northFt = 0.0, eastFt = 300.0)
            val sams = placeOf(b.graphic(ref), ref)
            b.advance(STILL)
            a.advance(POLL); a.advance(POLL)
            // Sam's whole: his longitude and the latitude he had, never Colin's latitude and Sam's longitude.
            assertPlace(a, b, ref, sams)
        }
    }

    @Test
    fun `a PZ marker turned by its tip on one device and moved north on the other is one person's arrow, anchor and tip`() = twoDevices { a, b ->
        opened(a, b)
        val pz = GraphicRef("pzMarkers", "pz-1")
        // Colin turns it a quarter round (its tip alone moves); Sam, before hearing of it, moves the marker north (anchor and tip by the same latitude).
        a.diagrams.edit("Turn") { d -> DiagramOps.patchGraphic(d, "pzMarkers", JsonPrimitive("pz-1"), checkNotNull(GraphicEdits.rotateBy("pzMarkers", a.graphic(pz), 90.0))) }
        a.advance(STILL)
        b.nudge(pz, northFt = 100.0, eastFt = 0.0)
        val sams = placeOf(b.graphic(pz), pz)
        b.advance(STILL)
        a.advance(POLL); a.advance(POLL)
        // Sam's reached the pack later, so it stands, anchor and tip together: never Sam's anchor with Colin's tip, which points nowhere either drew.
        assertPlace(a, b, pz, sams)
    }

    @Test
    fun `a graphic dragged one way and nudged the other stands whole where the later move put it`() = twoDevices { a, b ->
        opened(a, b)
        val chalk1 = GraphicRef("helicopters", "101")
        b.drag(chalk1, northM = 0.0, eastM = 90.0)
        b.advance(STILL)
        a.nudge(chalk1, northFt = 50.0, eastFt = 0.0)
        val colins = placeOf(a.graphic(chalk1), chalk1)
        a.advance(STILL)
        b.advance(POLL); b.advance(POLL)
        assertPlace(a, b, chalk1, colins)
    }

    @Test
    fun `someone else's move laid over an older version is their whole place, so undo never leaves a graphic half moved`() = twoDevices { a, b ->
        opened(a, b)
        val chalk1 = GraphicRef("helicopters", "101")
        // Sam moves Chalk 1 east, and both are in step.
        b.nudge(chalk1, northFt = 0.0, eastFt = 300.0)
        b.advance(STILL)
        a.advance(POLL); a.advance(POLL)
        // Colin moves it north from there; Sam hears of it.
        a.nudge(chalk1, northFt = 200.0, eastFt = 0.0)
        val colins = placeOf(a.graphic(chalk1), chalk1)
        a.advance(STILL)
        b.advance(POLL); b.advance(POLL)
        assertPlace(a, b, chalk1, colins)
        // Sam's undo goes back to his version from before his move, which has Colin's later place whole: Chalk 1 does not land at Colin's latitude and the
        // longitude Sam started from, where nobody put it.
        assertEquals("Move", b.diagrams.undo())
        b.advance(STILL)
        a.advance(POLL); a.advance(POLL)
        assertPlace(a, b, chalk1, colins)
    }

    @Test
    fun `the place of a graphic made on one device reaches the other, and a graphic moved there is where it was put`() = twoDevices { a, b ->
        opened(a, b)
        val chalk1 = GraphicRef("helicopters", "101")
        a.nudge(chalk1, northFt = 0.0, eastFt = -150.0)
        val colins = placeOf(a.graphic(chalk1), chalk1)
        a.advance(STILL)
        b.advance(POLL)
        assertPlace(a, b, chalk1, colins)
        // The rest of the LZ/PZ is as it was, on both devices.
        for (rig in listOf(a, b)) {
            assertEquals(listOf(34.501, -84.1), placeOf(rig.graphic(GraphicRef("helicopters", "102")), GraphicRef("helicopters", "102")))
            assertEquals(LatLon(34.5, -84.1), rig.doc().target?.let { LatLon(it.lat, it.lon) })
        }
        val sent: JsonElement? = a.sent().single().single()["value"]
        assertEquals(setOf("lat", "lon"), sent?.jsonObject?.keys)
        assertEquals(CHALK_1, a.serverGraphic(chalk1)["id"])
    }
}
