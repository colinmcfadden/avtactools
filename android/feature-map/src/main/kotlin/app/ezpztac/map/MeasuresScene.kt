package app.ezpztac.map

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.BackstopArrow
import app.ezpztac.planning.Backstops
import app.ezpztac.planning.Flots
import app.ezpztac.planning.PhaseLines
import app.ezpztac.planning.SetLines
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A FLOT or a phase line of an open set: [key] is `flots` or `phaseLines` ([SetLines.FLOTS], [SetLines.PHASE_LINES]). */
data class LineRef(val setId: String, val key: String, val id: String) {
    val isFlot: Boolean get() = key == SetLines.FLOTS
}

/**
 * A backstop of the open LZ/PZ as the map draws it: its dashed [line], and its [arrow] back to the target with what the label says ([reading]); no arrow
 * where it has neither a place of its own nor a target to run to. [movable] is false in a read-only pack: it is drawn and held, never picked up.
 */
data class BackstopDrawn(val id: String, val line: List<LatLon>, val arrow: BackstopArrow?, val reading: String?, val held: Boolean, val movable: Boolean)

/** A FLOT or a phase line as the map draws it. A FLOT is its scallops ([flipped]: which side), a phase line its black line with `PL <NAME>` ([label]) at each end. */
data class SetLineDrawn(val ref: LineRef, val points: List<LatLon>, val flipped: Boolean, val label: String?, val held: Boolean, val movable: Boolean)

/** Which line is being drawn point by point. */
enum class MeasureDraftKind { BACKSTOP, FLOT, PHASE_LINE }

/**
 * What the map draws of the backstops, FLOTs and phase lines (the web's Backstop.jsx, Flot.jsx and PhaseLine.jsx, and their drafts in MapView), as plain data so it is
 * tested without a GPU. The lines that are only lines (a backstop's dashes, a phase line, a line being drawn) go to MapLibre as GeoJSON ([MeasuresOverlay]); what is
 * worked out on the screen in pixels (a backstop's arrow and its label, a FLOT's scallops and label, a phase line's names, the handles) is Compose
 * ([MeasuresLabelsLayer]), as the web works them out in pixels.
 */
data class MeasuresScene(
    /** The open LZ/PZ's target, which every backstop's arrow points at. */
    val target: LatLon? = null,
    val backstops: List<BackstopDrawn> = emptyList(),
    val lines: List<SetLineDrawn> = emptyList(),
    val draftKind: MeasureDraftKind? = null,
    val draft: List<LatLon> = emptyList(),
) {
    val isEmpty: Boolean get() = backstops.isEmpty() && lines.isEmpty() && draft.isEmpty()

    val heldBackstop: BackstopDrawn? get() = backstops.firstOrNull { it.held }

    val heldLine: SetLineDrawn? get() = lines.firstOrNull { it.held }

    /**
     * The vector part as GeoJSON: each feature has a `role` the layers choose their style by. A backstop's line (`backstop`), a phase line (`phase-line`), a halo
     * under what is held (`halo`), a line being drawn (`draft-backstop`, `draft-flot`, `draft-phase-line`) and its points (`draft-vertex`, with the line's `kind`).
     * A FLOT is drawn in Compose: its scallops are worked out on the screen.
     */
    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            backstops.filter { it.held && it.line.size >= 2 }.forEach { add(feature("halo", line(it.line))) }
            lines.filter { it.held && it.points.size >= 2 }.forEach { add(feature("halo", line(it.points))) }
            backstops.forEach { if (it.line.size >= 2) add(feature("backstop", line(it.line))) }
            lines.forEach { if (!it.ref.isFlot && it.points.size >= 2) add(feature("phase-line", line(it.points))) }
            val kind = draftKind
            if (kind != null) {
                val name = kind.name.lowercase().replace('_', '-')
                if (draft.size >= 2) add(feature("draft-$name", line(draft)))
                draft.forEach { add(feature("draft-vertex", point(it), "kind" to JsonPrimitive(name))) }
            }
        }
    }.toString()

    private fun feature(role: String, geometry: JsonObject, vararg properties: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") {
            put("role", role)
            properties.forEach { (k, v) -> put(k, v) }
        }
        put("geometry", geometry)
    }

    private fun point(p: LatLon) = buildJsonObject {
        put("type", "Point")
        putJsonArray("coordinates") { add(p.lon); add(p.lat) }
    }

    private fun line(points: List<LatLon>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { p -> add(buildJsonArray { add(p.lon); add(p.lat) }) } }
    }

    companion object {
        val EMPTY = MeasuresScene()

        /**
         * The scene for the open LZ/PZ's backstops ([diagram]'s `graphics.backstops`) and the FLOTs and phase lines of the open [sets], with [heldBackstop] or
         * [heldLine] marked and the line being drawn. [backstopsMovable] and [setMovable] say what may be picked up (a read-only pack's may not). A backstop with no
         * point that is a place is not drawn, nor a line with fewer than two.
         */
        fun of(
            diagram: Diagram?,
            sets: List<RouteSet>,
            heldBackstop: String? = null,
            heldLine: LineRef? = null,
            backstopsMovable: Boolean = true,
            setMovable: (String) -> Boolean = { true },
            draftKind: MeasureDraftKind? = null,
            draft: List<LatLon> = emptyList(),
        ): MeasuresScene {
            val target = diagram?.target?.let { LatLon(it.lat, it.lon) }?.takeIf { it.lat.isFinite() && it.lon.isFinite() }
            val backstops = diagram?.graphics?.backstops.orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = DiagramOps.idText(item["id"])
                val points = Backstops.points(item)
                if (points.isEmpty()) return@mapNotNull null
                val arrow = Backstops.arrow(item, target)
                BackstopDrawn(id, points, arrow, arrow?.let { Backstops.reading(it).text }, held = heldBackstop == id, movable = backstopsMovable)
            }
            val lines = sets.flatMap { set ->
                val movable = setMovable(set.id)
                listOf(SetLines.FLOTS, SetLines.PHASE_LINES).flatMap { key ->
                    SetLines.of(set, key).mapNotNull { item ->
                        val points = SetLines.places(item)
                        if (points.size < 2) return@mapNotNull null
                        val ref = LineRef(set.id, key, DiagramOps.idText(item["id"]))
                        val flot = key == SetLines.FLOTS
                        SetLineDrawn(
                            ref, points, flipped = flot && Flots.flipped(item), label = if (flot) null else PhaseLines.label(item),
                            held = heldLine == ref, movable = movable,
                        )
                    }
                }
            }
            return MeasuresScene(target, backstops, lines, draftKind, if (draftKind == null) emptyList() else draft)
        }
    }
}
