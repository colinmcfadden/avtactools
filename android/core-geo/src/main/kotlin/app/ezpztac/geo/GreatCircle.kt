package app.ezpztac.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and true course between two points on a sphere, as `routeCalc.js`
 * computes them for route legs. The spherical model (3,440.065 nm radius) is the
 * planner's, not a geodesic's: matching the web matters more here than the
 * fraction of a percent an ellipsoid would add, because the same legs are
 * exported to AMPS.
 */
public object GreatCircle {
    private const val EARTH_RADIUS_NM = 3440.065
    private const val EARTH_RADIUS_FT = 20_902_231.0

    private fun toRad(deg: Double) = deg * Math.PI / 180
    private fun toDeg(rad: Double) = rad * 180 / Math.PI

    public fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_NM * asin(sqrt(a))
    }

    /**
     * Distance in feet, by the haversine on the web's 20,902,231 ft sphere (`getDistanceFeet` in `utils/Helpers.js`). It is what the
     * separation between two aircraft is measured with, and it is *not* the planner's sphere above: the two differ in the fifth digit,
     * and each has to agree with the web where it is used.
     */
    public fun distanceFeet(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rad = Math.PI / 180
        val dLat = (lat2 - lat1) * rad
        val dLon = (lon2 - lon1) * rad
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_FT * c
    }

    /** Initial true course from the first point to the second, 0 up to 360 degrees. */
    public fun trueCourseDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = toRad(lon2 - lon1)
        val y = sin(dLon) * cos(toRad(lat2))
        val x = cos(toRad(lat1)) * sin(toRad(lat2)) - sin(toRad(lat1)) * cos(toRad(lat2)) * cos(dLon)
        return (toDeg(atan2(y, x)) + 360) % 360
    }
}
