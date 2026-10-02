package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * What the mission summary says about a landing zone: its area, how many aircraft fit, and the call the slope tile makes. Ported from the
 * web's `MissionSummary.jsx` (`lzAreaAndCapacity`, `slopeStatusFor`) and `getPolygonArea` in `utils/Helpers.js`, held to
 * `contracts/fixtures/planning/summary.json`.
 */
public object LzSummary {
    /** The Earth's radius in feet, as the web uses it for areas. */
    private const val EARTH_RADIUS_FT = 20_902_231.0

    /**
     * The area of a polygon in square feet, by spherical excess (`getPolygonArea`). Fewer than three points is no area. Kept exactly as the
     * web has it, quirks included: a polygon that crosses the antimeridian is not unwrapped, so its longitude jump is taken at face value.
     */
    public fun polygonAreaSqFt(polygon: List<LatLon>?): Double {
        if (polygon == null || polygon.size < 3) return 0.0
        val toRadians = Math.PI / 180                                  // multiplied, as the web does, not divided (the last bit differs)
        var area = 0.0
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val lat1 = polygon[i].lat * toRadians
            val lat2 = polygon[j].lat * toRadians
            val lon1 = polygon[i].lon * toRadians
            val lon2 = polygon[j].lon * toRadians
            area += (lon2 - lon1) * (2 + sin(lat1) + sin(lat2))
        }
        area = (area * EARTH_RADIUS_FT * EARTH_RADIUS_FT) / 2.0
        return abs(area)
    }

    /** [areaSqFt] is rounded the way JavaScript's `Math.round` does (halves up), as the tile shows it. */
    public data class AreaAndCapacity(val areaSqFt: Long, val capacity: Int)

    /**
     * The area and the number of [profile] aircraft that fit (a conservative square-grid estimate using that aircraft's own spacing, so a
     * Chinook LZ holds fewer than a Black Hawk one). Capacity is worked out from the unrounded area, as the web does.
     */
    public fun areaAndCapacity(polygon: List<LatLon>?, profile: AircraftProfile = AircraftProfile.FALLBACK): AreaAndCapacity {
        if (polygon == null) return AreaAndCapacity(0, 0)
        val area = polygonAreaSqFt(polygon)
        return AreaAndCapacity(floor(area + 0.5).toLong(), max(0, AircraftGeometry.capacityForArea(area, profile)))
    }

    public enum class SlopeLevel { SAFE, WARNING, DANGER }

    /** Slope along and across the landing heading, in degrees (the server's `directional`). */
    public data class Directional(val noseHighMaxDeg: Double, val noseLowMaxDeg: Double, val crossSlopeMaxDeg: Double)

    /** [label] is the tile's own word; [maxDeg] is 0 when nothing was measured. */
    public data class SlopeCall(val level: SlopeLevel, val label: String, val maxDeg: Double)

    /**
     * The call the slope tile makes. The UH-60 limits are directional, so a limit is only called with a landing heading; a general slope
     * magnitude is context, and 15 degrees or more without a heading asks for one. Nothing measured is "NO DATA".
     */
    public fun slopeCall(maxDeg: Double?, directional: Directional?): SlopeCall {
        if (maxDeg == null) return SlopeCall(SlopeLevel.SAFE, "NO DATA", 0.0)
        if (directional != null) {
            val exceeds = directional.noseHighMaxDeg >= NOSE_HIGH_LIMIT || directional.noseLowMaxDeg >= NOSE_LOW_LIMIT ||
                directional.crossSlopeMaxDeg >= CROSS_SLOPE_LIMIT
            if (exceeds) return SlopeCall(SlopeLevel.DANGER, "LIMIT EXCEEDED", maxDeg)
        }
        if (directional == null && maxDeg >= 15) return SlopeCall(SlopeLevel.WARNING, "HEADING REQUIRED", maxDeg)
        if (maxDeg > 10) return SlopeCall(SlopeLevel.WARNING, "CAUTION", maxDeg)
        return SlopeCall(SlopeLevel.SAFE, "LANDING", maxDeg)
    }

    /** UH-60 landing limits, degrees (`UH60_LIMITS_DEG` on the server, which sends them with every analysis). */
    private const val NOSE_HIGH_LIMIT = 6.0
    private const val NOSE_LOW_LIMIT = 15.0
    private const val CROSS_SLOPE_LIMIT = 15.0
}
