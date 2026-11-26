package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A place on the screen, in pixels (or points: whatever unit the caller works in), y running down. */
public data class Pixel(val x: Double, val y: Double)

/** A box on the screen, its edges inside it. */
public data class PixelBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * A line kept with a set of routes beside its routes: a FLOT ([Flots]) or a phase line ([PhaseLines]), the web's feature/setLines. Each is
 * `{id, points: [{lat, lon}, ...]}` with whatever its kind adds, under its kind's key of the set's document (`flots`, `phaseLines`), the key left out
 * when there are none. None is a route, so none is ever written to an AMPS or ForeFlight file.
 *
 * **Control measures** (the owner, 2026-11-15): a FLOT and a phase line are graphics of their own, tied to no route set, and kept in a set that holds
 * lines and never a route, stored as a route set's document with no routes (`{version: 1, flots?, phaseLines?, routes: []}`), so the server, the sync
 * and the older apps know nothing new. Lines are opaque JSON here as everywhere a document is: a field a newer web wrote rides along. Held to
 * `contracts/fixtures/planning/flots.json` (`data`, `lengths`, `sets`).
 */
public object SetLines {
    /** Under this key of a set's document are its FLOTs. */
    public const val FLOTS: String = "flots"

    /** Under this key are its phase lines. */
    public const val PHASE_LINES: String = "phaseLines"

    /** What a set of control measures is called until it is renamed (the web's `MEASURES_NAME`). */
    public const val MEASURES_NAME: String = "CONTROL MEASURES"

    private const val FEET_PER_KM = 3280.84

    /** Whether [point] is a place: an object whose `lat` and `lon` are finite JSON numbers (`Number.isFinite`, so text is none). */
    public fun isPoint(point: JsonElement?): Boolean = place(point) != null

    /** [point] as a place, or null when it is not one ([isPoint]). */
    public fun place(point: JsonElement?): LatLon? {
        val o = point as? JsonObject ?: return null
        return LatLon(finite(o["lat"]) ?: return null, finite(o["lon"]) ?: return null)
    }

    /** The points of [line] that are places, as they are kept (a field beside the place rides along): the web's `linePoints`. */
    public fun points(line: JsonElement?): List<JsonObject> =
        ((line as? JsonObject)?.get("points") as? JsonArray).orEmpty().filter(::isPoint).map { it as JsonObject }

    /** The points of [line] that are places. */
    public fun places(line: JsonElement?): List<LatLon> = points(line).map { place(it)!! }

    /** Points as a line keeps them: a place each, and nothing else (`plainPoints`). */
    public fun plain(points: List<LatLon>): JsonArray = JsonArray(points.map(::plainPoint))

    /** A place as a line's point, `{lat, lon}`. */
    public fun plainPoint(p: LatLon): JsonObject = JsonObject(mapOf("lat" to JsonPrimitive(p.lat), "lon" to JsonPrimitive(p.lon)))

    /** The lines under [key] of a set's saved data: the entries that are objects with an id, whatever it is (`linesFromData`). */
    public fun fromData(data: JsonElement?, key: String): List<JsonObject> =
        ((data as? JsonObject)?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { "id" in it }

    /** The lines under [key] of [set]'s document. */
    public fun of(set: RouteSet, key: String): List<JsonObject> = fromData(set.extras, key)

    /** How many lines a set's document holds, of every kind (`lineCount`). */
    public fun count(data: JsonElement?): Int {
        val o = data as? JsonObject ?: return 0
        return (o[FLOTS] as? JsonArray)?.size.orZero() + (o[PHASE_LINES] as? JsonArray)?.size.orZero()
    }

    /** How many lines [set] holds. */
    public fun count(set: RouteSet): Int = count(set.extras)

    /**
     * Whether a set's document is control measures and no route set (`isMeasuresSet`): it holds no routes, and either holds lines or was [made] to
     * (in a pack, its name says so). A set with no routes and no lines that was not made for them is an empty route set.
     */
    public fun isMeasures(data: JsonElement?, made: Boolean = false): Boolean {
        val routes = ((data as? JsonObject)?.get("routes") as? JsonArray)?.size.orZero()
        return routes == 0 && (made || count(data) > 0)
    }

    /** Whether [set] is control measures: no routes (a route this version cannot read counts), and lines or [made] for them. */
    public fun isMeasures(set: RouteSet, made: Boolean = false): Boolean =
        set.routes.isEmpty() && set.unreadable.isEmpty() && (made || count(set) > 0)

    /** The length of [line] along its places, in km, by the web's great-circle feet (`lineLengthKm`). */
    public fun lengthKm(line: JsonElement?): Double {
        val p = places(line)
        var feet = 0.0
        for (i in 1 until p.size) feet += GreatCircle.distanceFeet(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon)
        return feet / FEET_PER_KM
    }

    /** [set] with the lines under [key] made [lines]: the key left out when there are none, else put where it was, a new one before `routes`. */
    public fun withLines(set: RouteSet, key: String, lines: List<JsonObject>): RouteSet {
        val rest = set.extras.filterKeys { it != key }
        if (lines.isEmpty()) return if (key in set.extras) set.copy(extras = JsonObject(rest)) else set
        val value = JsonArray(lines)
        if (key in set.extras) return set.copy(extras = JsonObject(set.extras + (key to value)))
        // A phase line's key goes after a FLOT's, as the web writes them (routeSetData).
        val ordered = LinkedHashMap<String, JsonElement>()
        var placed = false
        for ((k, v) in set.extras) {
            if (!placed && key == FLOTS && k == PHASE_LINES) { ordered[key] = value; placed = true }
            ordered[k] = v
        }
        if (!placed) ordered[key] = value
        return set.copy(extras = JsonObject(ordered))
    }

    /** JavaScript's truthiness, for a field a line keeps (`flipped`). */
    internal fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull == true
            else -> value.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
        }
        else -> true
    }

    /** `String(value ?? "")` for a field a line keeps (a phase line's name): text as it is, a number as JavaScript writes it, nothing for null. */
    internal fun jsString(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.content
            else -> value.doubleOrNull?.let(JsNumber::toText) ?: value.content
        }
        is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else jsString(it) }
        is JsonObject -> "[object Object]"
    }

    private fun finite(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun Int?.orZero(): Int = this ?: 0
}
