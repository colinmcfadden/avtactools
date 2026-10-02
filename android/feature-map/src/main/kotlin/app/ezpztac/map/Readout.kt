package app.ezpztac.map

import app.ezpztac.geo.CoordinateParser
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.model.LatLon
import kotlin.math.abs

/** Where the map is looking. [zoom] is MapLibre's (512-point tiles): one less than the same view in the web's Leaflet. */
data class CameraState(val center: LatLon, val zoom: Double, val bearingDegrees: Double = 0.0)

/**
 * What the pill under the crosshair says: the grid first, because crews work in MGRS, then the position in degrees.
 * Computed on the device from the camera, as often as the camera moves (never a request: AGENTS.md §13).
 */
data class Readout(val mgrs: String?, val latLon: String) {
    companion object {
        fun of(center: LatLon): Readout = Readout(
            // Outside the UTM/UPS bands the grid is not defined at this precision; the degrees still are.
            mgrs = MgrsConverter.toMgrs(center.lat, center.lon)?.format(),
            latLon = CoordinateParser.formatDecimal(center.lat, center.lon),
        )
    }
}

/** How far the ground at [latitude] is covered by one pixel at [zoom] on MapLibre's 512-point tiles, in metres. */
fun metersPerPixel(latitude: Double, zoom: Double): Double =
    78_271.516964 * Math.cos(Math.toRadians(latitude)) / Math.pow(2.0, zoom)

/** True when two cameras are visibly the same view, so a redraw of the readout can be skipped. */
fun CameraState.sameView(other: CameraState): Boolean =
    abs(zoom - other.zoom) < 0.01 && abs(center.lat - other.center.lat) < 1e-7 && abs(center.lon - other.center.lon) < 1e-7
