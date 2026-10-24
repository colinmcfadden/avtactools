package app.ezpztac.briefs

import app.ezpztac.brief.SandtableSteps
import app.ezpztac.model.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan

/** Where the map is flown for a step: its [center] and [zoom], north up (MapLibre's zoom, on 512-point tiles). */
data class SandtableView(val center: LatLon, val zoom: Double)

/**
 * Where the map goes for a step's area (the web's `map.flyToBounds(bounds, { paddingTopLeft, paddingBottomRight, maxZoom: 17 })`), worked out as Leaflet works it
 * out (`_getBoundsCenterZoom`, `getBoundsZoom`) so the figure is framed as the web frames it: the largest zoom at which the area fits the map less the [insets]
 * ([SandtableSteps.fitInsets]), snapped down to a tenth of a level (the web's map has `zoomSnap` 0.1), no closer than [MAX_ZOOM], and the area's middle put in the
 * middle of what the insets leave. Plain arithmetic on Web Mercator, north up: the camera is flown with no padding of its own, and turned north.
 *
 * MapLibre's zoom is Leaflet's less one (AGENTS.md §17, *The map*: its tiles are 512 points, Leaflet's 256), so the web's `maxZoom: 17` is [MAX_ZOOM] 16 here.
 */
object SandtableCamera {
    /** The closest a figure is shown: the web's 17 on Leaflet's tiles. */
    const val MAX_ZOOM: Double = 16.0

    /** The world is 512 points across at zoom 0. */
    private const val TILE_POINTS = 512.0

    /** Web Mercator stops here (Leaflet's `SphericalMercator.MAX_LATITUDE`). */
    private const val MAX_LATITUDE = 85.0511287798

    /** Leaflet's zoom snap on the web's map, and the share of it within which a zoom is taken as on the snap ("don't jump if within 1%"). */
    private const val SNAP = 0.1

    /**
     * The view that shows [bounds] in a map [width] by [height] (dp) with [insets] (dp) kept clear; null when the map has no room left, where nothing is flown
     * (the web's insets leave at least 160 dp each way of a map that has a size).
     */
    fun fit(bounds: SandtableSteps.Bounds, width: Double, height: Double, insets: SandtableSteps.Insets, maxZoom: Double = MAX_ZOOM): SandtableView? {
        val freeX = width - insets.left - insets.right
        val freeY = height - insets.top - insets.bottom
        if (!(freeX > 0) || !(freeY > 0)) return null
        // Corners in either order, as Leaflet's LatLngBounds takes them.
        val south = min(bounds.south, bounds.north)
        val north = maxOf(bounds.south, bounds.north)
        val west = min(bounds.west, bounds.east)
        val east = maxOf(bounds.west, bounds.east)
        // The area's size on the screen at zoom 0, then the zoom whose scale fits it: Leaflet's getScaleZoom is log2 of the scale.
        val nw = project(north, west, 0.0)
        val se = project(south, east, 0.0)
        val sizeX = se.first - nw.first
        val sizeY = se.second - nw.second
        val scale = min(freeX / sizeX, freeY / sizeY)
        var zoom = log2(scale)
        if (zoom.isFinite()) {
            zoom = Math.round(zoom / (SNAP / 100)) * (SNAP / 100)
            zoom = floor(zoom / SNAP) * SNAP
        }
        if (zoom.isNaN()) return null
        zoom = zoom.coerceIn(0.0, maxZoom)
        // The middle of the area, moved by half the difference of the insets, so it sits in the middle of the map they leave.
        val sw = project(south, west, zoom)
        val ne = project(north, east, zoom)
        val x = (sw.first + ne.first) / 2 + (insets.right - insets.left) / 2
        val y = (sw.second + ne.second) / 2 + (insets.bottom - insets.top) / 2
        val center = unproject(x, y, zoom)
        return SandtableView(LatLon(center.lat, wrap(center.lon)), zoom)
    }

    private fun worldSize(zoom: Double) = TILE_POINTS * Math.pow(2.0, zoom)

    /** A place's point in the world at [zoom], in points from its top left (x east, y south). Longitude is not wrapped, as Leaflet's is not. */
    internal fun project(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> {
        val world = worldSize(zoom)
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (lon + 180) / 360 * world
        val y = (0.5 - ln(tan(PI / 4 + Math.toRadians(clamped) / 2)) / (2 * PI)) * world
        return x to y
    }

    internal fun unproject(x: Double, y: Double, zoom: Double): LatLon {
        val world = worldSize(zoom)
        val lon = x / world * 360 - 180
        val lat = Math.toDegrees(2 * atan(exp((0.5 - y / world) * 2 * PI)) - PI / 2)
        return LatLon(lat, lon)
    }

    /** A longitude in [-180, 180): the camera's target is one place on the world, wherever on the brief's unwrapped longitudes the area lies. */
    internal fun wrap(lon: Double): Double {
        if (lon >= -180 && lon < 180) return lon
        val wrapped = ((lon + 180) % 360 + 360) % 360 - 180
        return wrapped
    }
}
