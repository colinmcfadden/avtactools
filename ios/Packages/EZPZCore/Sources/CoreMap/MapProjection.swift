import CoreModel
import Foundation

/// A position on the screen, in pixels from the top left.
public struct ScreenPoint: Hashable, Sendable {
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

/// Where a position is on the screen for a camera, and the other way round: Web Mercator on MapLibre's 512-point tiles, the camera's
/// centre in the middle of a `widthPx` by `heightPx` view, turned by its bearing. Plain arithmetic, so labels laid over the map, the
/// test for which graphic a tap was on, and the maths of dragging one are all tried without a GPU. (The map itself draws with its own
/// projection; this agrees with it for the flat, untilted camera the app uses.)
///
/// `density` is physical pixels per point (the screen's scale): a view's size is in pixels, the world's in points. The camera's padding
/// (`CameraState.paddingTopPx`, `paddingBottomPx`: the strips the search bar and the sheet cover) moves its centre to the middle of
/// what is between them, as the map draws it.
public struct MapProjection: Sendable {
    public let widthPx: Double
    public let heightPx: Double
    /// Physical pixels per point: what a size given in points (a label's) is on the screen.
    public let density: Double

    private let zoom: Double
    private let middleX: Double
    private let middleY: Double
    private let worldPx: Double
    private let centerX: Double
    private let centerY: Double
    private let cosB: Double
    private let sinB: Double

    /// MapLibre's tiles are 512 points wide, so the world is 512 * 2^zoom points across.
    private static let tilePoints = 512.0

    /// Web Mercator stops here; beyond it the map has no ground.
    private static let maxLatitude = 85.0511287798

    public init(camera: CameraState, widthPx: Double, heightPx: Double, density: Double = 1.0) {
        self.widthPx = widthPx
        self.heightPx = heightPx
        self.density = density
        zoom = camera.zoom
        middleX = widthPx / 2
        middleY = camera.paddingTopPx + (heightPx - camera.paddingTopPx - camera.paddingBottomPx) / 2
        worldPx = Self.tilePoints * pow(2.0, camera.zoom) * density
        centerX = Self.mercatorX(camera.center.lon)
        centerY = Self.mercatorY(camera.center.lat)
        let bearing = toRadians(camera.bearingDegrees)
        cosB = cos(bearing)
        sinB = sin(bearing)
    }

    /// Where `at` is on the screen. A point on the far side of the date line is placed on the nearer side, as the map draws it.
    public func toScreen(_ at: LatLon) -> ScreenPoint {
        var dx = Self.mercatorX(at.lon) - centerX
        if dx > 0.5 { dx -= 1.0 } else if dx < -0.5 { dx += 1.0 }
        let ux = dx * worldPx
        let uy = (Self.mercatorY(at.lat) - centerY) * worldPx
        // The map turns the other way from the bearing: facing east puts what is east of the centre at the top.
        return ScreenPoint(x: middleX + ux * cosB + uy * sinB, y: middleY - ux * sinB + uy * cosB)
    }

    /// What is under a point on the screen.
    public func toLatLon(_ x: Double, _ y: Double) -> LatLon {
        let sx = x - middleX
        let sy = y - middleY
        let ux = sx * cosB - sy * sinB
        let uy = sx * sinB + sy * cosB
        var worldX = centerX + ux / worldPx
        worldX -= worldX.rounded(.down)  // the world repeats: wrap into [0, 1)
        let worldY = coerceIn(centerY + uy / worldPx, 0.0, 1.0)
        return LatLon(lat: Self.latitudeOf(worldY), lon: worldX * 360 - 180)
    }

    /// How many pixels `meters` on the ground is, at `latitude`, at this zoom (the same ratio as `metersPerPixel`, with the density in
    /// it).
    public func metersToPixels(_ meters: Double, _ latitude: Double) -> Double {
        meters / metersPerPixel(latitude, zoom) * density
    }

    /// The distance between two points on the screen, in pixels.
    public func pixelsBetween(_ a: LatLon, _ b: LatLon) -> Double {
        let p = toScreen(a)
        let q = toScreen(b)
        return hypot(p.x - q.x, p.y - q.y)
    }

    private static func mercatorX(_ lon: Double) -> Double { (lon + 180) / 360 }

    private static func mercatorY(_ lat: Double) -> Double {
        let clamped = coerceIn(lat, -maxLatitude, maxLatitude)
        return 0.5 - log(tan(Double.pi / 4 + toRadians(clamped) / 2)) / (2 * Double.pi)
    }

    private static func latitudeOf(_ worldY: Double) -> Double {
        toDegrees(2 * atan(exp((0.5 - worldY) * 2 * Double.pi)) - Double.pi / 2)
    }
}
