package app.ezpztac.map

import app.ezpztac.model.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/** A position on the screen, in pixels from the top left. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * Where a position is on the screen for a camera, and the other way round: Web Mercator on MapLibre's 512-point tiles, the camera's
 * centre in the middle of a [widthPx] by [heightPx] view, turned by its bearing. Plain arithmetic, so labels laid over the map, the
 * test for which graphic a tap was on, and the maths of dragging one are all tried without a GPU. (The map itself draws with its own
 * projection; this agrees with it for the flat, untilted camera the app uses.)
 *
 * [density] is physical pixels per point (the screen's density): a view's size is in pixels, the world's in points. [bottomInsetPx] is the
 * strip at the bottom the map reserves for the sheet (`MapHost.setBottomPadding`): the camera's centre is the middle of what is above it.
 */
class MapProjection(
    private val camera: CameraState,
    val widthPx: Double,
    val heightPx: Double,
    private val density: Double = 1.0,
    private val bottomInsetPx: Double = 0.0,
) {
    private val middleX = widthPx / 2
    private val middleY = (heightPx - bottomInsetPx) / 2

    private val worldPx = TILE_POINTS * Math.pow(2.0, camera.zoom) * density
    private val centerX = mercatorX(camera.center.lon)
    private val centerY = mercatorY(camera.center.lat)
    private val bearing = Math.toRadians(camera.bearingDegrees)
    private val cosB = cos(bearing)
    private val sinB = sin(bearing)

    /** Where [at] is on the screen. A point on the far side of the date line is placed on the nearer side, as the map draws it. */
    fun toScreen(at: LatLon): ScreenPoint {
        var dx = mercatorX(at.lon) - centerX
        if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
        val ux = dx * worldPx
        val uy = (mercatorY(at.lat) - centerY) * worldPx
        // The map turns the other way from the bearing: facing east puts what is east of the centre at the top.
        return ScreenPoint(middleX + ux * cosB + uy * sinB, middleY - ux * sinB + uy * cosB)
    }

    /** What is under a point on the screen. */
    fun toLatLon(x: Double, y: Double): LatLon {
        val sx = x - middleX
        val sy = y - middleY
        val ux = sx * cosB - sy * sinB
        val uy = sx * sinB + sy * cosB
        var worldX = centerX + ux / worldPx
        worldX -= Math.floor(worldX)                                    // the world repeats: wrap into [0, 1)
        val worldY = (centerY + uy / worldPx).coerceIn(0.0, 1.0)
        return LatLon(latitudeOf(worldY), worldX * 360 - 180)
    }

    /** How many pixels [meters] on the ground is, at [latitude], at this zoom (the same ratio as [metersPerPixel], with the density in it). */
    fun metersToPixels(meters: Double, latitude: Double): Double = meters / metersPerPixel(latitude, camera.zoom) * density

    /** The distance between two points on the screen, in pixels. */
    fun pixelsBetween(a: LatLon, b: LatLon): Double = toScreen(a).let { p -> toScreen(b).let { q -> Math.hypot(p.x - q.x, p.y - q.y) } }

    private fun mercatorX(lon: Double) = (lon + 180) / 360

    private fun mercatorY(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return 0.5 - ln(tan(PI / 4 + Math.toRadians(clamped) / 2)) / (2 * PI)
    }

    private fun latitudeOf(worldY: Double): Double = Math.toDegrees(2 * atan(exp((0.5 - worldY) * 2 * PI)) - PI / 2)

    private companion object {
        /** MapLibre's tiles are 512 points wide, so the world is 512 * 2^zoom points across. */
        const val TILE_POINTS = 512.0

        /** Web Mercator stops here; beyond it the map has no ground. */
        const val MAX_LATITUDE = 85.0511287798
    }
}
