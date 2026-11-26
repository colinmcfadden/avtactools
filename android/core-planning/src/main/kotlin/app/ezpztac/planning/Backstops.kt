package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a backstop's arrow runs now: from [tail] to [tip], the tip on the target and following it while [onTarget]. */
public data class BackstopArrow(
    val tail: LatLon,
    val tip: LatLon,
    val onTarget: Boolean,
    /** The tail as the backstop keeps it, when it has one of its own (a field beside the place rides along); null while it is the line's middle. */
    val tailKept: JsonObject? = null,
)

/** What a backstop's label says: its azimuth in whole degrees true, its length, and the words (`185°T · 2.4 km`). */
public data class BackstopReading(val azimuth: Int, val distanceKm: Double, val text: String)

/**
 * A backstop (the owner's, 2026-11-08; the web's feature/backstops): a reference for a crew that overflies an LZ/PZ, a dashed line sketched wherever
 * terrain or a landmark makes it plain, and an arrow from it back to the target, labelled with its azimuth and length. Kept in a diagram's
 * `graphics.backstops` as `{id, points: [{lat, lon}, ...], arrow?: {tail: {lat, lon}, tip?: {lat, lon}}}`. **The line stays where it was sketched and
 * the arrow is what moves** (the owner's, 2026-11-09): with no `arrow` it runs from the line's middle to the target, and a tip with no place of its
 * own is on the target and follows it. The label is worked out from the arrow, never saved, so they cannot disagree. Held to
 * `contracts/fixtures/planning/backstops.json`.
 */
public object Backstops {
    /** A tip dropped this near the target (screen pixels) goes back onto it. */
    public const val TARGET_SNAP_PX: Double = 20.0

    /** An arrow is never made shorter than this (screen pixels), so it can still be picked up. */
    public const val MIN_ARROW_PX: Double = 24.0

    private const val FEET_PER_KM = 3280.84

    /** The drawn points that are places (`backstopPoints`). */
    public fun points(backstop: JsonElement?): List<LatLon> = SetLines.places(backstop)

    /** A new backstop of [points] (places only), with no arrow of its own (`createBackstop`). */
    public fun create(points: List<LatLon>, id: String): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive(id), "points" to SetLines.plain(points)))

    /** The middle of the line by distance along it (`backstopAnchor`): where its arrow starts until it is moved. Null with no points. */
    public fun anchor(points: List<LatLon>): LatLon? {
        if (points.isEmpty()) return null
        val legs = (1 until points.size).map { GreatCircle.distanceFeet(points[it - 1].lat, points[it - 1].lon, points[it].lat, points[it].lon) }
        var toGo = legs.sum() / 2
        for (i in legs.indices) {
            if (legs[i] > 0 && toGo <= legs[i]) {
                val along = toGo / legs[i]
                return LatLon(points[i].lat + (points[i + 1].lat - points[i].lat) * along, points[i].lon + (points[i + 1].lon - points[i].lon) * along)
            }
            toGo -= legs[i]
        }
        return points.first()
    }

    /** Where [backstop]'s arrow runs now, for an LZ/PZ's [target] (`backstopArrow`); null when it has nowhere to start or nothing to point at. */
    public fun arrow(backstop: JsonElement?, target: LatLon?): BackstopArrow? {
        val kept = ((backstop as? JsonObject)?.get("arrow") as? JsonObject)
        val tailKept = kept?.get("tail")?.takeIf(SetLines::isPoint) as JsonObject?
        val tail = SetLines.place(tailKept) ?: anchor(points(backstop)) ?: return null
        val ownTip = SetLines.place(kept?.get("tip"))
        val onTarget = ownTip == null
        val tip = ownTip ?: target?.takeIf { it.lat.isFinite() && it.lon.isFinite() } ?: return null
        return BackstopArrow(tail, tip, onTarget, tailKept)
    }

    /** The arrow's azimuth (whole degrees true, 360 written as 0), its length and the label's words (`backstopReading`). */
    public fun reading(arrow: BackstopArrow): BackstopReading {
        val (tail, tip) = arrow
        val azimuth = (RouteCalc.jsRound(GreatCircle.trueCourseDeg(tail.lat, tail.lon, tip.lat, tip.lon)).toInt()) % 360
        val km = GreatCircle.distanceFeet(tail.lat, tail.lon, tip.lat, tip.lon) / FEET_PER_KM
        return BackstopReading(azimuth, km, "${azimuth.toString().padStart(3, '0')}°T · ${JsNumber.toFixed(km, 1)} km")
    }

    /** The arrow moved whole by degrees of latitude and longitude (`moveBackstopArrow`): its tip goes with it, off the target if it was on it. */
    public fun moved(arrow: BackstopArrow, dLat: Double, dLon: Double): JsonObject = JsonObject(
        mapOf(
            "tail" to SetLines.plainPoint(LatLon(arrow.tail.lat + dLat, arrow.tail.lon + dLon)),
            "tip" to SetLines.plainPoint(LatLon(arrow.tip.lat + dLat, arrow.tip.lon + dLon)),
        ),
    )

    /**
     * The arrow with its tip dragged to [at], its tail staying (`aimBackstopArrow`): `{tail}` (back on the target) when [at] is within [TARGET_SNAP_PX]
     * of [target], null when it would leave the arrow under [MIN_ARROW_PX]. [pixelsTo] is how far a place is from [at] on the screen.
     */
    public fun aimed(arrow: BackstopArrow, at: LatLon, target: LatLon?, pixelsTo: (LatLon) -> Double): JsonObject? {
        if (pixelsTo(arrow.tail) < MIN_ARROW_PX) return null
        val tail = arrow.tailKept ?: SetLines.plainPoint(arrow.tail)
        val onTarget = target?.takeIf { it.lat.isFinite() && it.lon.isFinite() }
        return if (onTarget != null && pixelsTo(onTarget) <= TARGET_SNAP_PX) JsonObject(mapOf("tail" to tail))
        else JsonObject(mapOf("tail" to tail, "tip" to SetLines.plainPoint(at)))
    }
}
