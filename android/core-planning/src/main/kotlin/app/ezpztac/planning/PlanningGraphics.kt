package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The planning graphics' geometry and placement: where a new aircraft, PZ marker, sector of fire or go-around goes, which aircraft are too
 * close and what is said about it, and the lines between rotor edges. Ported from `useHelicopters.js`, `usePzMarker.js`,
 * `useSectorsOfFire.js`, `useGoAround.js` and `utils/Helpers.js`, and held to `contracts/fixtures/planning/graphics.json`.
 *
 * Graphics are saved as the web's JSON objects, which carry fields this code does not know, so what is made here is a [JsonObject] and an
 * existing graphic is read through a small view ([Aircraft]) rather than converted: an edit goes back through `DiagramOps`, which keeps
 * every other field as it was.
 */
public object PlanningGraphics {
    // -- Geometry ------------------------------------------------------------------------------------------------------

    /** The line between two aircraft's rotor tips, and the gap along it. [edgeDistFt] is 0 when the rotors touch or overlap. */
    public data class RotorEdge(val start: LatLon, val end: LatLon, val edgeDistFt: Double)

    /**
     * Each end of the line from one aircraft to the other is pulled in by *that* aircraft's own rotor radius, so a Chinook beside a Little
     * Bird is measured tip to tip. Radii default to the UH-60's, and the second to the first. Overlapping discs give no gap and a line
     * that is not inverted (`getRotorEdgeCoords`).
     */
    public fun rotorEdge(from: LatLon, to: LatLon, radius1Ft: Double? = null, radius2Ft: Double? = null): RotorEdge {
        val first = radius1Ft ?: AircraftGeometry.rotorRadiusFt(AircraftProfile.FALLBACK)
        val second = radius2Ft ?: first
        val centerDist = GreatCircle.distanceFeet(from.lat, from.lon, to.lat, to.lon)
        if (centerDist <= 0) return RotorEdge(from, to, 0.0)

        val fraction1 = first / centerDist
        val fraction2 = second / centerDist
        if (fraction1 + fraction2 >= 1) return RotorEdge(from, to, 0.0)

        return RotorEdge(
            start = LatLon(from.lat + fraction1 * (to.lat - from.lat), from.lon + fraction1 * (to.lon - from.lon)),
            end = LatLon(to.lat - fraction2 * (to.lat - from.lat), to.lon - fraction2 * (to.lon - from.lon)),
            edgeDistFt = centerDist - (first + second),
        )
    }

    /** The heading, in degrees, a graphic at the centre is turned to when its rotate handle is at the pointer (`calculateAngle`): 0 is up the screen. */
    public fun angleDeg(center: LatLon, pointer: LatLon): Double = atan2(pointer.lat - center.lat, pointer.lon - center.lon) * (180 / Math.PI) + 90

    /** Where a graphic's rotate handle sits, 0.0001 degrees from its centre in the direction it is turned (`calculateHandlePos`). */
    public fun handlePosition(center: LatLon, rotationDeg: Double): LatLon {
        val offset = 0.0001
        val angle = (rotationDeg - 90) * (Math.PI / 180)
        return LatLon(center.lat - offset * sin(angle), center.lon + offset * cos(angle))
    }

    // -- Aircraft ---------------------------------------------------------------------------------------------------------

    /** A placed aircraft as the geometry sees it. [id] is the saved id as it is (the web's are numbers made from the clock, or text). */
    public data class Aircraft(val id: JsonElement, val at: LatLon, val profileRef: String?) {
        public companion object {
            /** Reads a saved helicopter. One with no position cannot be measured and is left out. */
            public fun of(saved: JsonElement): Aircraft? {
                val o = saved as? JsonObject ?: return null
                val lat = number(o["lat"]) ?: return null
                val lon = number(o["lon"]) ?: return null
                val ref = (o["profileId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                return Aircraft(o["id"] ?: JsonNull, LatLon(lat, lon), ref)
            }
        }
    }

    /**
     * Where a new aircraft goes: the target, nudged east in steps of 0.0001 degrees until it is clear of every aircraft already down by
     * the separation each pair actually requires, giving up after 50 nudges. It records [active]'s slug as its profile, so it keeps its
     * identity across databases (`placeHelicopter`).
     */
    public fun placeHelicopter(
        target: LatLon,
        existing: List<Aircraft>,
        active: AircraftProfile,
        resolve: (Aircraft) -> AircraftProfile,
        id: JsonElement,
    ): JsonObject {
        var lat = target.lat
        var lon = target.lon
        var clear = false
        var attempts = 0
        val step = 0.0001
        while (!clear && attempts < MAX_NUDGES) {
            clear = true
            for (other in existing) {
                val distance = GreatCircle.distanceFeet(lat, lon, other.at.lat, other.at.lon)
                if (distance < AircraftGeometry.pairSeparation(active, resolve(other)).minCenterDistanceFt) {
                    clear = false
                    lon += step
                    break
                }
            }
            attempts += 1
        }
        return JsonObject(
            linkedMapOf(
                "id" to id, "lat" to JsonPrimitive(lat), "lon" to JsonPrimitive(lon), "rotation" to JsonPrimitive(0),
                "type" to JsonPrimitive("helo"), "profileId" to JsonPrimitive(active.slug.ifEmpty { AircraftProfile.FALLBACK.slug }),
            ),
        )
    }

    private const val MAX_NUDGES = 50

    /** Two aircraft whose rotor tips are closer than the stricter of their clearances. [id] is the pair's, as the web names it. */
    public data class SeparationAlert(val id: String, val first: JsonElement, val second: JsonElement, val gapFt: Double, val requiredFt: Double, val message: String)

    /** Every such pair, in the order the pairs are met (each aircraft against those after it), with the words shown for it. */
    public fun separationAlerts(aircraft: List<Aircraft>, resolve: (Aircraft) -> AircraftProfile): List<SeparationAlert> {
        val alerts = mutableListOf<SeparationAlert>()
        for (i in aircraft.indices) {
            for (j in i + 1 until aircraft.size) {
                val a = aircraft[i]
                val b = aircraft[j]
                val profileA = resolve(a)
                val profileB = resolve(b)
                val centerDistance = GreatCircle.distanceFeet(a.at.lat, a.at.lon, b.at.lat, b.at.lon)
                val gap = AircraftGeometry.edgeGapFt(centerDistance, profileA, profileB)
                val required = AircraftGeometry.pairSeparation(profileA, profileB).requiredClearanceFt
                if (gap < required) {
                    val shown = max(0.0, RouteCalc.jsRound(gap)).toLong()
                    val pair = if (profileA.slug == profileB.slug) profileA.designation else "${profileA.designation}/${profileB.designation}"
                    val metres = RouteCalc.jsRound(max(profileA.rotorTipClearanceM, profileB.rotorTipClearanceM)).toLong()
                    alerts += SeparationAlert(
                        id = "${text(a.id)}-${text(b.id)}", first = a.id, second = b.id, gapFt = gap, requiredFt = required,
                        message = "Separation Alert ($pair): Rotor edges are only $shown ft apart (Min: ${RouteCalc.jsRound(required).toLong()} ft / $metres m).",
                    )
                }
            }
        }
        return alerts
    }

    /** `String(id)`: text as it is, a whole number without a fraction, anything else as JSON writes it. */
    private fun text(id: JsonElement): String {
        val primitive = id as? JsonPrimitive ?: return id.toString()
        if (primitive is JsonNull) return "null"
        val number = primitive.takeIf { !it.isString }?.doubleOrNull
        return if (number != null && number == Math.rint(number) && abs(number) < 1e21) number.toLong().toString() else primitive.content
    }

    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    // -- The other graphics -----------------------------------------------------------------------------------------------

    /**
     * A PZ marker at the target, with its tip 0.002 degrees (about 160 m) west: the marker is a line from the centre to the tip, and its
     * length and direction are what the crew drags (`createPzMarker`). Null for a target that is not a position, where the web would make
     * a marker whose fields are NaN.
     */
    public fun createPzMarker(lat: Double, lon: Double, id: String): JsonObject? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        return JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(id), "lat" to JsonPrimitive(lat), "lon" to JsonPrimitive(lon),
                "tipLat" to JsonPrimitive(lat), "tipLon" to JsonPrimitive(lon - 0.002),
            ),
        )
    }

    /** A sector of fire: a triangle about 110 m (0.001 degrees) across, pointing north from the target (`createSectorOfFire`). Points are `{lat, lng}`, as the web saves them. */
    public fun createSectorOfFire(lat: Double, lon: Double, id: String): JsonObject? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val offset = 0.001
        fun point(la: Double, lo: Double) = JsonObject(linkedMapOf("lat" to JsonPrimitive(la), "lng" to JsonPrimitive(lo)))
        return JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(id),
                "points" to kotlinx.serialization.json.JsonArray(listOf(point(lat + offset, lon), point(lat - offset, lon + offset), point(lat - offset, lon - offset))),
            ),
        )
    }

    /**
     * A go-around arrow 0.001 degrees (about 110 m) from the target (`createGoAround`). **Quirk kept on purpose:** the web puts it north
     * only for a direction spelled "N", which nothing sends: the two it does send, "left" and "right", both start south.
     */
    public fun createGoAround(lat: Double, lon: Double, direction: String?, id: String): JsonObject? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val offset = 0.001
        return JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(id), "lat" to JsonPrimitive(if (direction == "N") lat + offset else lat - offset), "lon" to JsonPrimitive(lon),
                "direction" to (if (direction == null) JsonNull else JsonPrimitive(direction)), "rotation" to JsonPrimitive(0),
            ),
        )
    }
}
