package app.ezpztac.map

import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.Backstops
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.SetLines
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the map draws of the backstops, FLOTs and phase lines, and which of them a finger is on: plain data and geometry, so tried without a GPU. */
class MeasuresSceneTest {
    private val here = LatLon(34.78, -84.08)
    private val view = MapProjection(CameraState(here, 18.0, 0.0), 1000.0, 2000.0)
    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)

    private fun flot(id: String, vararg p: LatLon, flipped: Boolean = false) = JsonObject(
        buildMap {
            put("id", JsonPrimitive(id))
            put("points", SetLines.plain(p.toList()))
            if (flipped) put("flipped", JsonPrimitive(true))
        },
    )

    private fun phaseLine(id: String, name: String, vararg p: LatLon) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "points" to SetLines.plain(p.toList())))

    private fun set(id: String, flots: List<JsonObject> = emptyList(), phaseLines: List<JsonObject> = emptyList()) = RouteSet(
        id = id, name = SetLines.MEASURES_NAME, routes = emptyList(),
        extras = JsonObject(buildMap { if (flots.isNotEmpty()) put(SetLines.FLOTS, JsonArray(flots)); if (phaseLines.isNotEmpty()) put(SetLines.PHASE_LINES, JsonArray(phaseLines)) }),
    )

    // A backstop 60 m north of the target, its arrow from the line's middle back to it.
    private val target = here
    private val backstopItem = JsonObject(mapOf("id" to JsonPrimitive("bs-1"), "points" to SetLines.plain(listOf(at(60.0, -30.0), at(60.0, 30.0)))))
    private val backstop = BackstopDrawn("bs-1", Backstops.points(backstopItem), Backstops.arrow(backstopItem, target), null, held = false, movable = true)

    @Test
    fun `the open sets' lines are drawn, the held one marked, and a line of fewer than two points is not`() {
        val measures = set("m-1", flots = listOf(flot("flot-1", at(0.0), at(0.0, 50.0), flipped = true), flot("flot-short", at(0.0))), phaseLines = listOf(phaseLine("pl-1", "RED", at(20.0), at(20.0, 50.0))))
        val scene = MeasuresScene.of(null, listOf(measures), heldLine = LineRef("m-1", SetLines.PHASE_LINES, "pl-1"), setMovable = { false })
        assertEquals(2, scene.lines.size)
        val drawnFlot = scene.lines.single { it.ref.isFlot }
        assertTrue(drawnFlot.flipped)
        assertNull(drawnFlot.label)
        val pl = scene.lines.single { !it.ref.isFlot }
        assertEquals("PL RED", pl.label)
        assertTrue(pl.held)
        assertEquals(false, pl.movable)
        assertEquals(pl, scene.heldLine)
    }

    @Test
    fun `the vector part is the backstops' dashes, the phase lines, a halo under what is held and a line being drawn, never a FLOT, drawn in Compose`() {
        val measures = set("m-1", flots = listOf(flot("flot-1", at(0.0), at(0.0, 50.0))), phaseLines = listOf(phaseLine("pl-1", "RED", at(20.0), at(20.0, 50.0))))
        val scene = MeasuresScene.of(null, listOf(measures), heldLine = LineRef("m-1", SetLines.PHASE_LINES, "pl-1"))
            .copy(backstops = listOf(backstop), draftKind = MeasureDraftKind.FLOT, draft = listOf(at(80.0), at(80.0, 20.0)))
        val roles = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray
            .map { it.jsonObject.getValue("properties").jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("halo", "backstop", "phase-line", "draft-flot", "draft-vertex", "draft-vertex"), roles)
    }

    @Test
    fun `the end of a backstop's arrow wins over the arrow and its line, and each is picked up as it moves`() {
        val scene = MeasuresScene(target = target, backstops = listOf(backstop))
        val arrow = MeasuresOnScreen.arrow(backstop, target, view)!!
        val tipAt = view.toLatLon(arrow.head.x, arrow.head.y)
        val tip = MeasuresHitTest.pick(scene, view, tipAt, 24.0)
        assertEquals(MeasureHit.Backstop("bs-1", BackstopPart.TIP), tip)
        assertEquals(DragTarget.BackstopTip(GraphicRef("backstops", "bs-1")), MeasuresHitTest.dragTarget(scene, tip!!))

        val mid = MeasuresHitTest.pick(scene, view, at(30.0), 24.0)
        assertEquals(MeasureHit.Backstop("bs-1", BackstopPart.ARROW), mid)
        assertEquals(DragTarget.BackstopArrow(GraphicRef("backstops", "bs-1")), MeasuresHitTest.dragTarget(scene, mid!!))

        val line = MeasuresHitTest.pick(scene, view, at(60.0, 25.0), 24.0)
        assertEquals(MeasureHit.Backstop("bs-1", BackstopPart.LINE), line)
        assertNull("the dashed line is held, never moved", MeasuresHitTest.dragTarget(scene, line!!))
        assertNull(MeasuresHitTest.pick(scene, view, at(-80.0, 80.0), 24.0))
    }

    @Test
    fun `a point of a line is picked up, a leg only held, and nothing of a read-only pack's is picked up`() {
        val measures = set("m-1", phaseLines = listOf(phaseLine("pl-1", "RED", at(0.0), at(0.0, 60.0))))
        val scene = MeasuresScene.of(null, listOf(measures))
        val ref = LineRef("m-1", SetLines.PHASE_LINES, "pl-1")
        val vertex = MeasuresHitTest.pick(scene, view, at(0.5, 60.0), 24.0)
        assertEquals(MeasureHit.Line(ref, 1), vertex)
        assertEquals(DragTarget.LineVertex("m-1", SetLines.PHASE_LINES, "pl-1", 1), MeasuresHitTest.dragTarget(scene, vertex!!))
        val leg = MeasuresHitTest.pick(scene, view, at(0.5, 30.0), 24.0)
        assertEquals(MeasureHit.Line(ref, null), leg)
        assertNull(MeasuresHitTest.dragTarget(scene, leg!!))

        val readOnly = MeasuresScene.of(null, listOf(measures), setMovable = { false })
        assertNull(MeasuresHitTest.dragTarget(readOnly, MeasuresHitTest.pick(readOnly, view, at(0.5, 60.0), 24.0)!!))
        val lockedBackstop = MeasuresScene(target = target, backstops = listOf(backstop.copy(movable = false)))
        assertNull(MeasuresHitTest.dragTarget(lockedBackstop, MeasureHit.Backstop("bs-1", BackstopPart.ARROW)))
    }
}
