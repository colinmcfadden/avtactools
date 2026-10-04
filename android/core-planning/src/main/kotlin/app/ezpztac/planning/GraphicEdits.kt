package app.ezpztac.planning

import app.ezpztac.model.Doghouses
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What a person does to a planning graphic with the inspector: nudge it, put it at the crosshair, turn it, set how far a PZ marker reaches.
 * (On the web this is dragging; on a phone in a cockpit, with gloves, it is the more reliable way, and the map's own drag joins it later.)
 *
 * Each edit answers with a *patch*: only the fields it changed, as JSON, to merge into the saved graphic through `DiagramOps.patchGraphic`,
 * so every other field of the graphic (one a newer web release added, say) is left exactly as it was. Null means the edit does not apply:
 * the graphic has no position, or that kind of graphic has no such thing (a sector has no heading).
 *
 * Distances are on the sphere the map draws on (radius 6,378,137 m), so a nudge of 10 m is 10 m on the screen.
 */
public object GraphicEdits {
    private const val EARTH_RADIUS_M = 6_378_137.0

    /** The point [northM] metres north and [eastM] metres east of [at]. */
    public fun offset(at: LatLon, northM: Double, eastM: Double): LatLon = LatLon(
        at.lat + Math.toDegrees(northM / EARTH_RADIUS_M),
        at.lon + Math.toDegrees(eastM / (EARTH_RADIUS_M * cos(Math.toRadians(at.lat)))),
    )

    /** Metres north and east from [from] to [to], on the same flat approximation as [offset] (good over the few hundred metres a diagram spans). */
    public fun metresBetween(from: LatLon, to: LatLon): Pair<Double, Double> = Pair(
        Math.toRadians(to.lat - from.lat) * EARTH_RADIUS_M,
        Math.toRadians(to.lon - from.lon) * EARTH_RADIUS_M * cos(Math.toRadians(from.lat)),
    )

    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun num(value: Double) = JsonPrimitive(value)

    /** Where a graphic is: its position, or for a sector the middle of its points. Null when it has none. */
    public fun position(collection: String, graphic: JsonObject): LatLon? = when (collection) {
        "sectorsOfFire" -> sectorPoints(graphic).takeIf { it.size >= 3 }?.let { p -> LatLon(p.sumOf { it.lat } / p.size, p.sumOf { it.lon } / p.size) }
        "helicopters", "pzMarkers", "goArounds", "doghouses", "units" -> number(graphic["lat"])?.let { lat -> number(graphic["lon"])?.let { lon -> LatLon(lat, lon) } }
        else -> null
    }

    private fun sectorPoints(graphic: JsonObject): List<LatLon> = (graphic["points"] as? JsonArray).orEmpty().mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        LatLon(number(o["lat"]) ?: return@mapNotNull null, number(o["lng"]) ?: return@mapNotNull null)
    }

    /** The patch that puts a graphic at [to]. A PZ marker's tip and a sector's points go with it, keeping their shape. */
    public fun moveTo(collection: String, graphic: JsonObject, to: LatLon): JsonObject? {
        val from = position(collection, graphic) ?: return null
        val dLat = to.lat - from.lat
        val dLon = to.lon - from.lon
        return when (collection) {
            "pzMarkers" -> {
                val tipLat = number(graphic["tipLat"]) ?: from.lat
                val tipLon = number(graphic["tipLon"]) ?: (from.lon - 0.001)         // where the web draws a marker that has no tip
                JsonObject(mapOf("lat" to num(to.lat), "lon" to num(to.lon), "tipLat" to num(tipLat + dLat), "tipLon" to num(tipLon + dLon)))
            }
            "sectorsOfFire" -> JsonObject(
                mapOf(
                    "points" to JsonArray(sectorPoints(graphic).map { p -> JsonObject(mapOf("lat" to num(p.lat + dLat), "lng" to num(p.lon + dLon))) }),
                ),
            )
            else -> JsonObject(mapOf("lat" to num(to.lat), "lon" to num(to.lon)))
        }
    }

    /** The patch that moves a graphic [northM] metres north and [eastM] east. */
    public fun nudge(collection: String, graphic: JsonObject, northM: Double, eastM: Double): JsonObject? {
        val from = position(collection, graphic) ?: return null
        return moveTo(collection, graphic, offset(from, northM, eastM))
    }

    // -- Turning ---------------------------------------------------------------------------------------------------------

    /** [degrees] in `[0, 360)`. */
    public fun normalizeDegrees(degrees: Double): Double = ((degrees % 360) + 360) % 360

    /**
     * The way a graphic points: an aircraft's or a go-around's `rotation`, a doghouse's heading, or the bearing of a PZ marker's tip from its
     * anchor. Null for what does not turn.
     */
    public fun rotation(collection: String, graphic: JsonObject): Double? = when (collection) {
        "helicopters", "goArounds" -> normalizeDegrees(number(graphic["rotation"]) ?: 0.0)
        "doghouses" -> normalizeDegrees(Doghouses.rotation(graphic))
        "pzMarkers" -> pzVector(graphic)?.let { (north, east) -> normalizeDegrees(Math.toDegrees(atan2(east, north))) }
        else -> null
    }

    /** The patch that points a graphic at [degrees] (clockwise from north). A PZ marker keeps its reach and swings its tip round its anchor. */
    public fun setRotation(collection: String, graphic: JsonObject, degrees: Double): JsonObject? = when (collection) {
        "helicopters", "goArounds" -> JsonObject(mapOf("rotation" to num(normalizeDegrees(degrees))))
        // A doghouse keeps its heading as the web writes it ("270°"), to the whole degree, so it is also the flight data's heading.
        "doghouses" -> JsonObject(mapOf("heading" to JsonPrimitive(Doghouses.headingText(RouteCalc.jsRound(normalizeDegrees(degrees)) % 360))))
        "pzMarkers" -> pzVector(graphic)?.let { (north, east) -> setPz(graphic, hypot(north, east), degrees) }
        else -> null
    }

    /** The patch that turns a graphic by [delta] degrees, clockwise. */
    public fun rotateBy(collection: String, graphic: JsonObject, delta: Double): JsonObject? =
        rotation(collection, graphic)?.let { setRotation(collection, graphic, it + delta) }

    // -- A PZ marker's reach ------------------------------------------------------------------------------------------------

    /** Metres from a PZ marker's anchor to its tip. Null for anything else. */
    public fun pzReachM(graphic: JsonObject): Double? = pzVector(graphic)?.let { (north, east) -> hypot(north, east) }

    /** The patch that sets how far a PZ marker reaches, keeping its bearing. A marker whose tip is on its anchor has no bearing and points north. */
    public fun setPzReach(graphic: JsonObject, meters: Double): JsonObject? {
        val vector = pzVector(graphic) ?: return null
        val bearing = if (hypot(vector.first, vector.second) == 0.0) 0.0 else Math.toDegrees(atan2(vector.second, vector.first))
        return setPz(graphic, meters.coerceAtLeast(0.0), bearing)
    }

    /** The patch that puts a PZ marker's tip at [tip], wherever that is (the crosshair, say), keeping its anchor. */
    public fun setPzTip(graphic: JsonObject, tip: LatLon): JsonObject? {
        if (position("pzMarkers", graphic) == null) return null
        return JsonObject(mapOf("tipLat" to num(tip.lat), "tipLon" to num(tip.lon)))
    }

    /** Where a PZ marker's arrow ends (a marker with no tip is drawn pointing west of its anchor). Null for anything that is not one. */
    public fun pzTip(graphic: JsonObject): LatLon? {
        val anchor = position("pzMarkers", graphic) ?: return null
        return LatLon(number(graphic["tipLat"]) ?: anchor.lat, number(graphic["tipLon"]) ?: (anchor.lon - 0.001))
    }

    /** The tip's offset from the anchor, metres north and east. */
    private fun pzVector(graphic: JsonObject): Pair<Double, Double>? {
        val anchor = position("pzMarkers", graphic) ?: return null
        val tip = LatLon(number(graphic["tipLat"]) ?: anchor.lat, number(graphic["tipLon"]) ?: (anchor.lon - 0.001))
        return metresBetween(anchor, tip)
    }

    private fun setPz(graphic: JsonObject, reachM: Double, bearingDeg: Double): JsonObject? {
        val anchor = position("pzMarkers", graphic) ?: return null
        val bearing = Math.toRadians(normalizeDegrees(bearingDeg))
        val tip = offset(anchor, reachM * cos(bearing), reachM * sin(bearing))
        return JsonObject(mapOf("tipLat" to num(tip.lat), "tipLon" to num(tip.lon)))
    }

    /** The patch that points a go-around the other way round the pattern: `"left"` or `"right"`. */
    public fun setGoAroundDirection(graphic: JsonObject, direction: String): JsonObject? =
        if (direction == "left" || direction == "right") JsonObject(mapOf("direction" to JsonPrimitive(direction))) else null
}
