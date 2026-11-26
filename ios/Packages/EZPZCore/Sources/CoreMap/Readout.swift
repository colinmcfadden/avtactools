import CoreGeo
import CoreModel
import Foundation

/// Where the map is looking. `zoom` is MapLibre's (512-point tiles): one less than the same view in the web's Leaflet.
/// `paddingTopPx` and `paddingBottomPx` are the camera's padding as the map has it now (moving with it while it animates): the strips
/// the app's controls cover, so `center` is the middle of the rest, where the crosshair is.
public struct CameraState: Hashable, Sendable {
    public var center: LatLon
    public var zoom: Double
    public var bearingDegrees: Double
    public var paddingTopPx: Double
    public var paddingBottomPx: Double
    /// The move that brought it here began with the person's own gesture, not the app's.
    public var byHand: Bool

    public init(
        center: LatLon,
        zoom: Double,
        bearingDegrees: Double = 0.0,
        paddingTopPx: Double = 0.0,
        paddingBottomPx: Double = 0.0,
        byHand: Bool = false
    ) {
        self.center = center
        self.zoom = zoom
        self.bearingDegrees = bearingDegrees
        self.paddingTopPx = paddingTopPx
        self.paddingBottomPx = paddingBottomPx
        self.byHand = byHand
    }

    /// True when two cameras are visibly the same view, so a redraw of the readout can be skipped.
    public func sameView(_ other: CameraState) -> Bool {
        abs(zoom - other.zoom) < 0.01 && abs(center.lat - other.center.lat) < 1e-7 && abs(center.lon - other.center.lon) < 1e-7
    }
}

/// What the pill under the crosshair says: the grid first, because crews work in MGRS, then the position in degrees.
/// Computed on the device from the camera, as often as the camera moves (never a request: AGENTS.md §13).
public struct Readout: Hashable, Sendable {
    public let mgrs: String?
    public let latLon: String

    public init(mgrs: String?, latLon: String) {
        self.mgrs = mgrs
        self.latLon = latLon
    }

    public static func of(_ center: LatLon) -> Readout {
        Readout(
            // Outside the UTM/UPS bands the grid is not defined at this precision; the degrees still are.
            mgrs: MgrsConverter.toMgrs(center.lat, center.lon)?.format(),
            latLon: CoordinateParser.formatDecimal(center.lat, center.lon)
        )
    }
}

/// How far the ground at `latitude` is covered by one pixel at `zoom` on MapLibre's 512-point tiles, in metres.
public func metersPerPixel(_ latitude: Double, _ zoom: Double) -> Double {
    78_271.516964 * cos(toRadians(latitude)) / pow(2.0, zoom)
}
