package app.ezpztac.briefs

import app.ezpztac.brief.SandtableSteps
import app.ezpztac.brief.SandtableSteps.Colours
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A line of a figure, through [points], [width] dp wide in [colour] at [opacity]; [order] is where it is drawn among the lines (later over earlier). */
data class FigureLine(val points: List<LatLon>, val colour: String, val width: Double, val opacity: Double, val order: Int)

/**
 * A dot of a figure: a circle [radius] dp to the middle of its ring, the ring [stroke] dp wide in [colour] at [opacity], filled with [fill] at [fillOpacity]. [order]
 * is where it is drawn among the dots (later over earlier).
 */
data class FigureDot(
    val at: LatLon,
    val radius: Double,
    val colour: String,
    val stroke: Double,
    val opacity: Double,
    val fill: String,
    val fillOpacity: Double,
    val order: Int,
)

/** The boundary of the LZ/PZ in focus: its [ring], outlined [stroke] dp wide in [colour] and filled with it at [fillOpacity]. */
data class FigureArea(val ring: List<LatLon>, val colour: String, val stroke: Double, val fillOpacity: Double)

/** A mark over the map, drawn as the web's are (Compose: a raster style has no symbols of its own); [rank] orders marks, lower first, then by place, lower on top. */
sealed interface FigureMark {
    val at: LatLon
    val rank: Int

    /** An LZ/PZ: a dark square [size] dp across, edged in its role's [colour]. */
    data class Square(override val at: LatLon, val colour: String, val size: Double) : FigureMark {
        override val rank: Int get() = RANK_SQUARE
    }

    /** A rally point: a dark triangle edged in the rally colour. */
    data class Rally(override val at: LatLon) : FigureMark {
        override val rank: Int get() = RANK_RALLY
    }

    /**
     * An arrow along [heading] (degrees true), [length] dp long in [colour]: its tail on the place ([fromTip] false, an aircraft's heading), or its tip ([fromTip]
     * true, the landing heading, arriving at the LZ/PZ).
     */
    data class Arrow(override val at: LatLon, val heading: Double, val length: Double, val colour: String, val fromTip: Boolean, override val rank: Int) : FigureMark
}

/** How large a figure's words are: a route point's or a threat's, an LZ/PZ's or a rally point's, the LZ/PZ in focus's. */
enum class LabelSize { SMALL, NORMAL, LARGE }

/** Words of a figure beside [at], in [colour], set as text (nothing in them is read as anything but words). */
data class FigureLabel(val at: LatLon, val text: String, val colour: String, val size: LabelSize)

/**
 * What the map shows for one step of the sandtable (docs/MISSION_BRIEF.md §2.8), as plain data, so *what* is drawn is tested without a GPU: the web's
 * `attachSandtable` (SandtableLayer.jsx), which draws a step's figure from the brief's `geometry` as the PDF does (backend/brief/figures.py). The lines, the LZ/PZ's
 * boundary and the dots go to MapLibre as GeoJSON ([geoJson], [SandtableOverlay]); the marks and the words are drawn over the map in Compose
 * ([SandtableMarksLayer]), as the app's other labels are, the style having no glyphs.
 *
 * Each route of the view, the unlit first and the lit last (drawn over the others): its line on a dark casing, in the focus colour when lit, else its own, dimmed on a
 * route step, a dot at each named point and its name where the route is labelled. Each LZ/PZ: a square in its role's colour and its name, and the one in focus close,
 * with its boundary, its aircraft (a dot and an arrow along each one's heading) and an arrow arriving along its landing heading. Every threat, a red dot and its
 * name. The rally points with their side, on the rally step only.
 *
 * Sizes are the web's pixels as dp. MapLibre draws a dot's ring outside its radius where an SVG circle's is centred on it, so [FigureDot.radius] is the web's
 * (the ring's middle) and [SandtableOverlay] draws the fill half a ring smaller. **Not like the web**: a route's colour that is not `#RRGGBB` is the figures' route
 * colour, as the PDF's rule (the web's would hand anything to the browser); and MapLibre draws every line under the boundary and every dot over both, where the web
 * interleaves them route by route (a route's dots under the lit route's line): with one route lit last the difference is a dot under or over a line it crosses.
 */
data class SandtableScene(
    val lines: List<FigureLine> = emptyList(),
    val areas: List<FigureArea> = emptyList(),
    val dots: List<FigureDot> = emptyList(),
    val marks: List<FigureMark> = emptyList(),
    val labels: List<FigureLabel> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty() && areas.isEmpty() && dots.isEmpty() && marks.isEmpty() && labels.isEmpty()

    /**
     * The shapes as GeoJSON for the map's source: coordinates `[longitude, latitude]` as GeoJSON has them, longitudes as the brief gives them (a figure across the
     * antimeridian runs on past 180°, as MapLibre draws it). Each feature carries a `role` (`line`, `area` or `dot`) the layers choose by, and its own style.
     */
    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            lines.forEach { l ->
                add(
                    feature(
                        "line", lineString(l.points),
                        "colour" to JsonPrimitive(l.colour), "width" to JsonPrimitive(l.width), "opacity" to JsonPrimitive(l.opacity), "order" to JsonPrimitive(l.order),
                    ),
                )
            }
            areas.forEach { a ->
                add(feature("area", polygon(a.ring), "colour" to JsonPrimitive(a.colour), "stroke" to JsonPrimitive(a.stroke), "fillOpacity" to JsonPrimitive(a.fillOpacity)))
            }
            dots.forEach { d ->
                add(
                    feature(
                        "dot", point(d.at),
                        "colour" to JsonPrimitive(d.colour), "radius" to JsonPrimitive(d.radius), "stroke" to JsonPrimitive(d.stroke), "opacity" to JsonPrimitive(d.opacity),
                        "fill" to JsonPrimitive(d.fill), "fillOpacity" to JsonPrimitive(d.fillOpacity), "order" to JsonPrimitive(d.order),
                        // MapLibre's ring is outside the radius: the fill is half a ring smaller, so the ring's middle is at the radius, as an SVG circle's is.
                        "fillRadius" to JsonPrimitive(maxOf(0.0, d.radius - d.stroke / 2)),
                    ),
                )
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

    private fun position(p: LatLon) = buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }

    private fun point(p: LatLon) = buildJsonObject {
        put("type", "Point")
        put("coordinates", position(p))
    }

    private fun lineString(points: List<LatLon>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { add(position(it)) } }
    }

    // A ring closed on its first corner, as GeoJSON has it (Leaflet closes a polygon itself).
    private fun polygon(ring: List<LatLon>) = buildJsonObject {
        put("type", "Polygon")
        putJsonArray("coordinates") {
            add(buildJsonArray { (if (ring.first() == ring.last()) ring else ring + ring.first()).forEach { add(position(it)) } })
        }
    }

    companion object {
        val EMPTY = SandtableScene()

        /** The words' colour where the web gives none: white, over a dark halo. */
        const val WHITE = "#ffffff"

        /** The scene of [view] (a step of the sandtable, [SandtableSteps.look]) drawn from the brief's [geometry]; nothing for no step. */
        fun of(view: SandtableSteps.View?, geometry: JsonElement?): SandtableScene {
            if (view == null) return EMPTY
            val lines = ArrayList<FigureLine>()
            val areas = ArrayList<FigureArea>()
            val dots = ArrayList<FigureDot>()
            val marks = ArrayList<FigureMark>()
            val labels = ArrayList<FigureLabel>()
            val routes = (geometry as? JsonObject)?.get("routes") as? JsonObject
            val stops = (geometry as? JsonObject)?.get("stops") as? JsonObject

            // The lit route last, so it is drawn over the others.
            (view.routes.filter { !it.lit } + view.routes.filter { it.lit }).forEach { look ->
                val route = routes?.get(look.ref) as? JsonObject ?: return@forEach
                val colour = if (look.lit) Colours.FOCUS else hexColour(route["color"]) ?: Colours.ROUTE
                val opacity = if (look.dimmed) DIMMED else 1.0
                val weight = if (look.lit) 6.0 else 4.0
                val line = (route["line"] as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonArray)?.let { at(it.getOrNull(0), it.getOrNull(1)) } }
                if (line.size >= 2) {
                    lines += FigureLine(line, Colours.CASING, weight + 4, 0.6 * opacity, lines.size)
                    lines += FigureLine(line, colour, weight, opacity, lines.size)
                }
                (route["points"] as? JsonArray).orEmpty().forEach { p ->
                    val o = p as? JsonObject
                    val place = at(o?.get("lat"), o?.get("lon")) ?: return@forEach
                    dots += FigureDot(place, if (look.lit) 6.0 else 5.0, colour, 2.5, opacity, Colours.CASING, opacity, dots.size)
                    val name = o?.get("name")
                    if (look.labelled && truthy(name)) labels += FigureLabel(place, text(name), WHITE, LabelSize.SMALL)
                }
            }

            view.stops.forEach { look ->
                val stop = stops?.get(look.ref) as? JsonObject ?: return@forEach
                val place = at(stop["lat"], stop["lon"]) ?: return@forEach
                val colour = SandtableSteps.roleColour(stop["role"])
                if (look.close) {
                    val boundary = (stop["boundary"] as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonArray)?.let { at(it.getOrNull(0), it.getOrNull(1)) } }
                    if (boundary.size >= 3) areas += FigureArea(boundary, colour, 3.0, 0.16)
                    (stop["aircraft"] as? JsonArray).orEmpty().forEach { craft ->
                        val o = craft as? JsonObject
                        val spot = at(o?.get("lat"), o?.get("lon")) ?: return@forEach
                        dots += FigureDot(spot, 7.0, Colours.AIRCRAFT, 2.5, 1.0, Colours.CASING, 1.0, dots.size)
                        marks += FigureMark.Arrow(spot, finite(o?.get("heading")) ?: 0.0, 30.0, Colours.AIRCRAFT, fromTip = false, rank = RANK_AIRCRAFT)
                    }
                    finite(stop["landingHeading"])?.let { heading ->
                        marks += FigureMark.Arrow(place, heading, 96.0, WHITE, fromTip = true, rank = RANK_LANDING)
                    }
                }
                marks += FigureMark.Square(place, colour, if (look.close) 18.0 else 14.0)
                val name = stop["name"]
                if (truthy(name)) labels += FigureLabel(place, text(name), WHITE, if (look.close) LabelSize.LARGE else LabelSize.NORMAL)
            }

            ((geometry as? JsonObject)?.get("threats") as? JsonArray).orEmpty().forEach { threat ->
                val o = threat as? JsonObject
                val place = at(o?.get("lat"), o?.get("lon")) ?: return@forEach
                dots += FigureDot(place, 9.0, Colours.THREAT, 3.0, 1.0, Colours.THREAT, 0.35, dots.size)
                val name = o?.get("name")
                labels += FigureLabel(place, if (truthy(name)) text(name) else "Threat", Colours.THREAT_LABEL, LabelSize.SMALL)
            }

            if (view.rally) {
                ((geometry as? JsonObject)?.get("rally") as? JsonArray).orEmpty().forEach { rally ->
                    val o = rally as? JsonObject
                    val place = at(o?.get("lat"), o?.get("lon")) ?: return@forEach
                    marks += FigureMark.Rally(place)
                    labels += FigureLabel(place, "${text(o?.get("name"))} (${text(o?.get("label"))})", Colours.RALLY, LabelSize.NORMAL)
                }
            }
            return SandtableScene(lines, areas, dots, marks, labels)
        }

        /** How a route on a route step that is not the one in focus is drawn: faded, as the web's (and the PDF's) are. */
        const val DIMMED = 0.55

        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        private fun hexColour(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(HEX::matches)

        // `typeof v === "number" && Number.isFinite(v)`
        private fun finite(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun at(lat: JsonElement?, lon: JsonElement?): LatLon? {
            val a = finite(lat) ?: return null
            val b = finite(lon) ?: return null
            return LatLon(a, b)
        }

        // JavaScript's truthiness, for a name: present, not null, not "" and not 0.
        private fun truthy(value: JsonElement?): Boolean = when (value) {
            null, JsonNull -> false
            is JsonPrimitive -> if (value.isString) value.content.isNotEmpty() else value.content != "false" && value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } != false
            else -> true
        }

        // `String(v)` in a template literal: text as it is, a number as JavaScript writes it.
        private fun text(value: JsonElement?): String = when (value) {
            null -> "undefined"
            is JsonNull -> "null"
            is JsonPrimitive -> if (value.isString) value.content else value.doubleOrNull?.let(JsNumber::toText) ?: value.content
            is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else text(it) }
            is JsonObject -> "[object Object]"
        }
    }
}

/** The marks' ranks: Leaflet's z-index offsets, the landing arrow under the aircraft's under the squares under the rally points. */
internal const val RANK_LANDING = -100
internal const val RANK_AIRCRAFT = 0
internal const val RANK_SQUARE = 100
internal const val RANK_RALLY = 200
