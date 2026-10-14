package app.ezpztac.data

import app.ezpztac.data.PackEditorRig.Companion.COLIN
import app.ezpztac.data.PackEditorRig.Companion.ITEM
import app.ezpztac.data.PackEditorRig.Companion.PACK
import app.ezpztac.data.PackEditorRig.Companion.STILL
import app.ezpztac.data.PackEditorRig.Companion.array
import app.ezpztac.data.PackEditorRig.Companion.create
import app.ezpztac.data.PackEditorRig.Companion.json
import app.ezpztac.missionpacks.PackHistory
import app.ezpztac.missionpacks.PackLz
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.Units
import app.ezpztac.network.PackEvent
import app.ezpztac.planning.GraphicEdits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
 * the map draws it from, both devices' open documents, and in what the pack holds: where one of the two put it, never half of each. And a doghouse turned
 * is one change by the device that turned it and one line of the pack's history, and nothing from the other device (*A doghouse turned*, below).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LzPackTwoDevicesTest {
    // A regression that loops without end fails here rather than holding the build (PackEditorRig.MAX_BATCHES cuts a loop of sends short sooner).
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private companion object {
        /** Longer than a poll (3 s), and the sends either side of it. */
        const val POLL = 4_000L

        const val LIVE = "ws://live.test/live"

        val CHALK_1: JsonPrimitive = JsonPrimitive(101)

        /** Where a doghouse turned goes, in order: the landing heading it gives, then its own heading. */
        val TURN_PATHS: List<JsonElement> = listOf(array("""["flightData"]"""), array("""["graphics", "doghouses", {"id": "dh-rp"}]"""))

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

    // -- A doghouse turned ---------------------------------------------------------------------------------------------------------------------------
    //
    // The owner (2026-10-13): "doghouses, when turned, are getting duplicate history entries." A turn is one change, sent by the device that turned it
    // with the landing heading the doghouse gives, as two operations, so the pack logs it as two events, each with the change's sentence: the history
    // showed the sentence twice for every turn (and five times for an analysis). It reads them as one line now (PackHistory, the web's packHistory.js),
    // which names the doghouse and the heading it was turned to (PackLz, packLz.js), so turns a second apart are a line each.
    // Nothing else is sent: the other device takes the turn as it came, whether it has the LZ/PZ open or closed, on the same account or another, by
    // polling or on the live stream an event at a time.

    /** LZ HAWK as the first analysis leaves it: the standard doghouses, and the landing and takeoff headings they give. */
    private val standard: JsonObject = json(
        """{"schemaVersion": 2, "status": "analyzed",
            "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"}, "mapData": {"mgrs": "16S GC 28864 55349"},
            "flightData": {"landing_hdg": "090°", "takeoff_hdg": "270°"},
            "analysis": {"customLZ": null, "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "results": {"area": 1},
                         "gridElevation": "1200", "latLong": ""},
            "graphics": {
              "doghouses": [
                {"id": "dh-sp", "role": "takeoff", "lat": 34.5, "lon": -84.103, "id_val": "[SP1]", "heading": "270°", "time": "01+57", "dist": "3.13km", "airspeed": "60 KIAS"},
                {"id": "dh-rp", "role": "landing", "lat": 34.4985, "lon": -84.103, "id_val": "[RP1]", "heading": "090°", "time": "02+10", "dist": "5.20km", "airspeed": "40 KIAS"}],
              "helicopters": [], "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "units": [], "measurements": [], "exportBox": null}}""",
    )

    private val rp = GraphicRef("doghouses", "dh-rp")

    /** The held object's bar: −15°, −1°, +1° or +15° (GraphicsViewModel.rotateBy), one edit of the doghouse held. */
    private fun PackEditorRig.pressTurn(degrees: Double) = turnHeld { c, g -> GraphicEdits.rotateBy(c, g, degrees) }

    /** The inspector's heading typed and set (GraphicsViewModel.setRotation), one edit of the doghouse held. */
    private fun PackEditorRig.typeHeading(degrees: Double) = turnHeld { c, g -> GraphicEdits.setRotation(c, g, degrees) }

    private fun PackEditorRig.turnHeld(patch: (String, JsonObject) -> JsonObject?) {
        val doghouse = graphic(rp)
        val changes = checkNotNull(patch(rp.collection, doghouse))
        diagrams.edit("Turn") { DiagramOps.patchGraphic(it, rp.collection, doghouse["id"], changes) }
    }

    /** The pack's log after [from], as the History panel reads it: its lines, each the events of one change. */
    private fun PackEditorRig.historyAfter(from: Long): List<List<PackEvent>> =
        PackHistory.lines(server.log(PACK).map { Json.decodeFromJsonElement(PackEvent.serializer(), it) }.filter { it.seq > from })

    /** The landing doghouse's heading and the landing heading the flight data takes from it, as [item] has them. */
    private fun landingOf(item: JsonObject): List<String?> = listOf(
        item.getValue("graphics").jsonObject.getValue("doghouses").jsonArray.single { it.jsonObject["id"]?.jsonPrimitive?.content == rp.key }.jsonObject["heading"]?.jsonPrimitive?.content,
        item.getValue("flightData").jsonObject["landing_hdg"]?.jsonPrimitive?.content,
    )

    private fun PackEditorRig.landingHere(): List<String?> = landingOf(PackLz.lzItemData(doc()))

    /**
     * [turner]'s turns since [from] were one change for each of [headings], each one batch of two operations from its device, and nothing came from
     * [other]: the history reads each change as one line in [turner]'s name, naming the doghouse and the heading it was turned to, and both devices
     * and the pack give the doghouse the last of [headings], the landing heading with it.
     */
    private fun assertTurned(turner: PackEditorRig, other: PackEditorRig, from: Long, sentBefore: Int, headings: List<String>, otherOpen: Boolean = true) {
        val what = "${turner.me.name}'s turn"
        val changes = headings.size
        assertEquals("$what: one batch for each change", changes, turner.sent().size - sentBefore)
        assertTrue("$what: each change the landing heading and the doghouse's", turner.sent().drop(sentBefore).all { ops -> ops.map { it["path"] } == TURN_PATHS })
        assertEquals("$what: nothing from the other device", emptyList<List<JsonObject>>(), other.sent())
        val events = turner.server.log(PACK).map { it.getValue("seq").jsonPrimitive.content.toLong() }.filter { it > from }
        assertEquals("$what: two events in the log for each change", 2 * changes, events.size)
        val lines = turner.historyAfter(from)
        assertEquals("$what: a line of the history for each change, of its two events", List(changes) { 2 }, lines.map { it.size })
        assertEquals("$what: in the turner's name", setOf(turner.me.id), lines.flatten().map { it.actor.id }.toSet())
        assertEquals(
            "$what: each line's sentence, naming the doghouse and its heading",
            headings.map { listOf("${turner.me.name} turned doghouse RP1 to $it on LZ HAWK.") },
            lines.map { line -> line.map { it.summary }.distinct() },
        )
        val heading = headings.last()
        assertEquals("$what: in the pack", listOf(heading, heading), landingOf(turner.serverData()))
        assertEquals("$what: on ${turner.me.name}'s device", listOf(heading, heading), turner.landingHere())
        if (otherOpen) assertEquals("$what: on the other device", listOf(heading, heading), other.landingHere())
    }

    /** Two devices with OP DK and LZ HAWK ([data]) open: Sam's (B) and, beside it, Colin's or Sam's own tablet (A), polling or on the live stream. */
    private fun turning(sameAccount: Boolean = false, live: Boolean = false, data: JsonObject = standard, bOpen: Boolean = true, body: suspend TestScope.(a: PackEditorRig, b: PackEditorRig) -> Unit) = runTest {
        val b = PackEditorRig(this, live = if (live) LIVE else null)
        val a = if (sameAccount) b.other(PackEditorRig.SAM, device = "tablet") else b.other(COLIN)
        try {
            b.pack(PACK, "OP DK", create(ITEM, "lz", "LZ HAWK", data))
            for ((rig, open) in listOf(a to true, b to bOpen)) {
                rig.start()
                rig.openPack()
                if (live) {
                    rig.server.sockets.last().welcome(rig.server.headSeq(PACK))
                    rig.advance(0)
                }
                if (open) rig.openItem(ITEM)
            }
            a.advance(STILL); b.advance(STILL); a.advance(POLL); b.advance(POLL)
            body(a, b)
        } finally {
            a.close()
            b.close()
        }
    }

    // Both devices hear everything there is: a send waits for the session's pause, a poll for the next poll.
    private fun settleBoth(a: PackEditorRig, b: PackEditorRig) {
        a.advance(STILL)
        b.advance(POLL); b.advance(POLL)
        a.advance(POLL); b.advance(POLL)
    }

    @Test
    fun `a doghouse turned on one device is one change and one line of the history, and the other device sends nothing, on two accounts or one`() {
        val bar: PackEditorRig.() -> Unit = { pressTurn(15.0) }
        val typed: PackEditorRig.() -> Unit = { typeHeading(250.0) }
        for (sameAccount in listOf(false, true)) {
            for ((turn, heading) in listOf(bar to "105°", typed to "250°")) {
                turning(sameAccount = sameAccount) { a, b ->
                    val from = a.server.headSeq(PACK)
                    val sentBefore = a.sent().size
                    a.turn()
                    settleBoth(a, b)
                    assertTurned(a, b, from, sentBefore, headings = listOf(heading))
                }
            }
        }
    }

    @Test
    fun `presses of the bar a moment apart are one change and one line, and a press each second a change and a line each, each naming its heading`() {
        turning { a, b ->
            val from = a.server.headSeq(PACK)
            repeat(5) {
                a.pressTurn(1.0)
                a.advance(200)
            }
            settleBoth(a, b)
            assertTurned(a, b, from, sentBefore = 0, headings = listOf("095°"))
        }
        turning { a, b ->
            val from = a.server.headSeq(PACK)
            repeat(3) {
                a.pressTurn(1.0)
                a.advance(1_000)
            }
            settleBoth(a, b)
            assertTurned(a, b, from, sentBefore = 0, headings = listOf("091°", "092°", "093°"))
        }
    }

    @Test
    fun `a doghouse turned while the other device has the LZ-PZ closed reaches its copy, and opening it there sends nothing`() = turning(bOpen = false) { a, b ->
        val from = a.server.headSeq(PACK)
        a.typeHeading(250.0)
        settleBoth(a, b)
        assertTurned(a, b, from, sentBefore = 0, headings = listOf("250°"), otherOpen = false)
        assertEquals(listOf("250°", "250°"), landingOf(checkNotNull(b.engine.open.value?.item(ITEM)).data.jsonObject))
        b.openItem(ITEM)
        settleBoth(a, b)
        assertEquals(listOf("250°", "250°"), b.landingHere())
        assertTurned(a, b, from, sentBefore = 0, headings = listOf("250°"))
    }

    @Test
    fun `a doghouse turned, heard on the live stream an event at a time, is taken as it came and nothing is sent back`() = turning(live = true) { a, b ->
        val from = a.server.headSeq(PACK)
        a.pressTurn(-15.0)
        a.advance(STILL)
        b.advance(STILL); a.advance(STILL)
        assertTurned(a, b, from, sentBefore = 0, headings = listOf("075°"))
    }

    @Test
    fun `on an item the web wrote without the flight data, the headings owed go once from each device that opened it, and a turn is still one line`() =
        turning(data = JsonObject(standard + ("flightData" to JsonObject(emptyMap())))) { a, b ->
            // Each device that opened it before hearing the other's sent the headings its doghouses give, once; then nothing more.
            assertTrue("${a.sent()} ${b.sent()}", a.sent().size <= 1 && b.sent().size <= 1 && a.sent().size + b.sent().size >= 1)
            val owed = listOf(a.sent().size, b.sent().size)
            settleBoth(a, b)
            assertEquals(owed, listOf(a.sent().size, b.sent().size))
            val from = a.server.headSeq(PACK)
            a.pressTurn(15.0)
            a.advance(STILL)
            b.advance(POLL); b.advance(POLL); a.advance(POLL); b.advance(POLL)
            assertEquals("nothing more from Sam's device", owed[1], b.sent().size)
            assertEquals("one batch for the turn", owed[0] + 1, a.sent().size)
            val lines = a.historyAfter(from)
            assertEquals(1, lines.size)
            assertEquals(listOf("105°", "105°"), landingOf(a.serverData()))
            assertEquals(listOf("105°", "105°"), b.landingHere())
        }
}
