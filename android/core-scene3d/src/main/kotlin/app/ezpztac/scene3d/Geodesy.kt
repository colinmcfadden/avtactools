package app.ezpztac.scene3d

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** The WGS84 ellipsoid, which the LiDAR's geocentric points, the terrain's heights and Cesium's globe are all measured on. */
public object Wgs84 {
    public const val A: Double = 6378137.0
    public const val F: Double = 1.0 / 298.257223563
    public const val E2: Double = F * (2.0 - F)
}

/** A place: degrees of latitude and longitude, and metres above the ellipsoid (never above sea level: MSL is turned with the server's geoid). */
public data class Geodetic(val lat: Double, val lon: Double, val height: Double)

/** Geocentric (ECEF) metres of a place, held to PROJ (contracts/fixtures/viewer3d/geodesy.json). */
public fun geodeticToEcef(lat: Double, lon: Double, height: Double): Vec3 {
    val phi = Math.toRadians(lat)
    val lambda = Math.toRadians(lon)
    val sinPhi = sin(phi)
    val cosPhi = cos(phi)
    val n = Wgs84.A / sqrt(1.0 - Wgs84.E2 * sinPhi * sinPhi)
    return Vec3((n + height) * cosPhi * cos(lambda), (n + height) * cosPhi * sin(lambda), (n * (1.0 - Wgs84.E2) + height) * sinPhi)
}

/**
 * The place at geocentric [p]: latitude by Bowring's iteration, which settles to well under a millimetre in a few rounds, and the height
 * by the form that holds at the poles too (`p cos φ + z sin φ − a √(1 − e² sin² φ)`), where `p / cos φ − N` divides by nothing.
 */
public fun ecefToGeodetic(p: Vec3): Geodetic {
    val lon = atan2(p.y, p.x)
    val r = hypot(p.x, p.y)
    var lat = atan2(p.z, r * (1.0 - Wgs84.E2))
    repeat(6) {
        val sinLat = sin(lat)
        val n = Wgs84.A / sqrt(1.0 - Wgs84.E2 * sinLat * sinLat)
        val height = r * cos(lat) + p.z * sinLat - Wgs84.A * sqrt(1.0 - Wgs84.E2 * sinLat * sinLat)
        lat = atan2(p.z, r * (1.0 - Wgs84.E2 * n / (n + height)))
    }
    val sinLat = sin(lat)
    val height = r * cos(lat) + p.z * sinLat - Wgs84.A * sqrt(1.0 - Wgs84.E2 * sinLat * sinLat)
    return Geodetic(Math.toDegrees(lat), Math.toDegrees(lon), height)
}

/** East, north and up at a place, as unit directions in geocentric coordinates. */
public fun enuAxes(lat: Double, lon: Double): Triple<Vec3, Vec3, Vec3> {
    val phi = Math.toRadians(lat)
    val lambda = Math.toRadians(lon)
    val east = Vec3(-sin(lambda), cos(lambda), 0.0)
    val north = Vec3(-sin(phi) * cos(lambda), -sin(phi) * sin(lambda), cos(phi))
    val up = Vec3(cos(phi) * cos(lambda), cos(phi) * sin(lambda), sin(phi))
    return Triple(east, north, up)
}

/**
 * The scene's one frame: east, north and up at the LZ (docs/ANDROID_3D_PLAN.md), in metres. An LZ's view spans a few kilometres, so no globe
 * is needed; points far from the origin keep the earth's curve, since this is a rigid move of geocentric coordinates, not a map projection.
 * Everything the renderer draws is in this frame, as floats near zero.
 */
public class LocalFrame(public val lat: Double, public val lon: Double, public val height: Double = 0.0) {
    /** The origin, in geocentric coordinates. */
    public val origin: Vec3 = geodeticToEcef(lat, lon, height)

    private val axes = enuAxes(lat, lon)
    public val east: Vec3 get() = axes.first
    public val north: Vec3 get() = axes.second
    public val up: Vec3 get() = axes.third

    /** Geocentric to this frame: the rotation onto east, north and up after taking away the origin. */
    public val fromEcef: Mat4 = run {
        val (e, n, u) = axes
        // Rows are the axes: the transpose of the frame's columns, then the origin taken away.
        val rotation = Mat4.columnMajor(listOf(e.x, n.x, u.x, 0.0, e.y, n.y, u.y, 0.0, e.z, n.z, u.z, 0.0, 0.0, 0.0, 0.0, 1.0))
        rotation * Mat4.translation(-origin)
    }

    /** This frame to geocentric. */
    public val toEcef: Mat4 = Mat4.fromAxes(axes.first, axes.second, axes.third, origin)

    public fun toLocal(ecef: Vec3): Vec3 {
        val d = ecef - origin
        return Vec3(d dot axes.first, d dot axes.second, d dot axes.third)
    }

    public fun toEcef(local: Vec3): Vec3 = origin + axes.first * local.x + axes.second * local.y + axes.third * local.z

    public fun fromGeodetic(lat: Double, lon: Double, height: Double): Vec3 = toLocal(geodeticToEcef(lat, lon, height))

    public fun toGeodetic(local: Vec3): Geodetic = ecefToGeodetic(toEcef(local))

    /** East, north and up at another place, in this frame: where "north" is a little way from the origin. */
    public fun axesAt(lat: Double, lon: Double): Triple<Vec3, Vec3, Vec3> {
        val (e, n, u) = enuAxes(lat, lon)
        fun turned(v: Vec3) = Vec3(v dot axes.first, v dot axes.second, v dot axes.third)
        return Triple(turned(e), turned(n), turned(u))
    }
}
