import CoreGeo
import CoreJS
import Foundation

/// The part of the map the MGRS grid is worked out for (`MgrsGrid.grid`), in its own units: a point, MapLibre's unit, so the grid's
/// 70-point line gap and its label sizes are the web's CSS pixels. `turned` is a camera facing other than north: the lines are then
/// worked out for the box round the turned view, and the labels for `labels`, a north-up square inside it (the box's own corners and
/// edges are off the screen), with no line values along its edges, which are not the screen's.
public struct MgrsGridView: Hashable, Sendable {
    public let bounds: GridBounds
    public let metersPerPixel: Double
    public let options: GridOptions
    public let turned: Bool
    public let labels: MgrsGridLabelView?

    public init(bounds: GridBounds, metersPerPixel: Double, options: GridOptions, turned: Bool, labels: MgrsGridLabelView? = nil) {
        self.bounds = bounds
        self.metersPerPixel = metersPerPixel
        self.options = options
        self.turned = turned
        self.labels = labels
    }
}

/// Where a turned map's labels are worked out: a north-up square inside what shows, with what floats over the map carried into its
/// frame.
public struct MgrsGridLabelView: Hashable, Sendable {
    public let bounds: GridBounds
    public let metersPerPixel: Double
    public let options: GridOptions

    public init(bounds: GridBounds, metersPerPixel: Double, options: GridOptions) {
        self.bounds = bounds
        self.metersPerPixel = metersPerPixel
        self.options = options
    }
}

/// How the map's grid is fitted to what shows, and its lines handed to MapLibre. Plain arithmetic, so it is tried without a GPU.
///
/// `min` and `max` are Java's here, as in the Kotlin (`minOf`, `maxOf` and `kotlin.math.min` all go through `Math.min`): a NaN in a
/// camera is carried into the bounds, where the grid draws nothing for it, rather than a corner quietly winning over it.
public enum MgrsGridScene {
    /// Lines also cover this much of the view's size round it, so a pan shows no bare map before the next redraw (the web's).
    public static let pad = 0.25

    /// A camera turned less than this faces north, as the compass button counts it.
    private static let northDegrees = 0.5

    /// Labels keep this far off what floats over the map (the web's 4 px round each overlay).
    private static let avoidMarginPoints = 4.0

    private static let tilePoints = 512.0
    private static let earthRadius = 6378137.0
    private static let maxLatitude = 85.0511287798

    /// The grid's view for a camera over a `widthPx` by `heightPx` map: from the top of the map to `coveredBottomPx` above its foot
    /// (the sheet, which the camera's own padding also leaves out), at `density` pixels a point. `avoidPx` are the boxes, in the map's
    /// pixels, of what floats over it (the search bar and chips, the map's buttons, the readout pill, a tool): labels keep off them.
    /// Nil for a map with no size.
    ///
    /// The longitudes are not folded into 180: across the antimeridian, or zoomed out to several copies of the world, the bounds run
    /// past it, as the web's map gives them and the grid expects.
    public static func viewOf(
        _ camera: CameraState,
        _ widthPx: Double,
        _ heightPx: Double,
        _ density: Double,
        coveredBottomPx: Double? = nil,
        avoidPx: [GridBox] = []
    ) -> MgrsGridView? {
        let bottom = heightPx - javaMax(0.0, coveredBottomPx ?? camera.paddingBottomPx)
        if widthPx <= 0 || bottom <= 0 || density <= 0 || !camera.zoom.isFinite { return nil }
        let screen = Screen(camera, widthPx, heightPx, density)
        let turned = abs(camera.bearingDegrees) >= northDegrees
        let corners = [screen.world(0.0, 0.0), screen.world(widthPx, 0.0), screen.world(0.0, bottom), screen.world(widthPx, bottom)]
        let bounds = boundsOf(
            javaMinOf(corners.map(\.x)), javaMinOf(corners.map(\.y)), javaMaxOf(corners.map(\.x)), javaMaxOf(corners.map(\.y)))
        if !turned {
            // The frame's top-left is the screen's: a box on the screen is a box of the frame, in points.
            let avoid = avoidPx.map { box in
                grown(GridBox(x0: box.x0 / density, y0: box.y0 / density, x1: box.x1 / density, y1: box.y1 / density))
            }
            return MgrsGridView(
                bounds: bounds, metersPerPixel: metersPerPoint(bounds, camera.zoom), options: GridOptions(pad: pad, avoid: avoid),
                turned: false)
        }
        // Turned: the labels from a north-up square inside the circle the view's middle part leaves, which every bearing keeps on the
        // screen, and what floats over the map moved into that square's frame (the box round each one's corners there).
        let centre = screen.world(widthPx / 2, bottom / 2)
        let half = javaMin(widthPx, bottom) / 2 / 2.0.squareRoot() / screen.worldPx
        let west = centre.x - half
        let north = centre.y - half
        let inner = boundsOf(west, north, centre.x + half, centre.y + half)
        let pointsPerWorld = screen.worldPx / density
        let avoid = avoidPx.map { box in
            let seen = [
                screen.world(box.x0, box.y0), screen.world(box.x1, box.y0), screen.world(box.x0, box.y1), screen.world(box.x1, box.y1),
            ]
            .map { (x: ($0.x - west) * pointsPerWorld, y: ($0.y - north) * pointsPerWorld) }
            return grown(
                GridBox(
                    x0: javaMinOf(seen.map(\.x)), y0: javaMinOf(seen.map(\.y)), x1: javaMaxOf(seen.map(\.x)), y1: javaMaxOf(seen.map(\.y))))
        }
        return MgrsGridView(
            bounds: bounds, metersPerPixel: metersPerPoint(bounds, camera.zoom), options: GridOptions(pad: pad), turned: true,
            labels: MgrsGridLabelView(bounds: inner, metersPerPixel: metersPerPoint(inner, camera.zoom), options: GridOptions(avoid: avoid))
        )
    }

    /// The grid to draw for `view`: turned, its lines for the whole view and its labels from the square inside it, without the lines'
    /// values.
    public static func gridFor(_ view: MgrsGridView) -> GridResult {
        let grid = MgrsGrid.grid(view.bounds, view.metersPerPixel, options: view.options)
        guard let labels = view.labels else { return grid }
        let inner = MgrsGrid.grid(labels.bounds, labels.metersPerPixel, options: labels.options)
        return GridResult(spacing: grid.spacing, lines: grid.lines, labels: inner.labels.filter { $0.edge == nil }, dropped: grid.dropped)
    }

    private static func grown(_ box: GridBox) -> GridBox {
        GridBox(
            x0: box.x0 - avoidMarginPoints, y0: box.y0 - avoidMarginPoints, x1: box.x1 + avoidMarginPoints, y1: box.y1 + avoidMarginPoints)
    }

    // Bounds from the world's coordinates (0 to 1 across, 0 at the top to 1 at the bottom), the longitudes not folded.
    private static func boundsOf(_ westX: Double, _ northY: Double, _ eastX: Double, _ southY: Double) -> GridBounds {
        GridBounds(south: latitudeOf(southY), west: westX * 360 - 180, north: latitudeOf(northY), east: eastX * 360 - 180)
    }

    // The scale at the middle of the bounds as Web Mercator has it, which is what the grid takes: a point is then exactly a point.
    private static func metersPerPoint(_ bounds: GridBounds, _ zoom: Double) -> Double {
        let middle = mercatorLat((mercatorY(bounds.north) + mercatorY(bounds.south)) / 2)
        return 2 * Double.pi * earthRadius * cos(toRadians(middle)) / (tilePoints * pow(2.0, zoom))
    }

    private static func latitudeOf(_ worldY: Double) -> Double {
        toDegrees(2 * atan(exp((0.5 - coerceIn(worldY, 0.0, 1.0)) * 2 * Double.pi)) - Double.pi / 2)
    }

    /// The grid's lines as GeoJSON, each line a feature carrying its kind (`zone`, `major`, `minor`), which picks its layer.
    ///
    /// A coordinate is written as JavaScript writes the number (`-84.5`, `1e-7`), where kotlinx writes Kotlin's (`-84.5`, `1.0E-7`):
    /// the same value to MapLibre. A NaN, which the grid never puts in a line, would be `null` here and `NaN` (not JSON) there.
    public static func geoJson(_ grid: GridResult) -> String {
        let features: [JSONTree] = grid.lines.map { line in
            .object([
                "type": .string("Feature"),
                "properties": .object(["kind": .string(line.kind)]),
                "geometry": .object([
                    "type": .string("LineString"),
                    "coordinates": .array(line.points.map { .array([.number($0.lon), .number($0.lat)]) }),
                ]),
            ])
        }
        return JSONTree.object(["type": .string("FeatureCollection"), "features": .array(features)]).serialized
    }

    /// Where a label's pill goes, its top-left corner in pixels, for its anchor on the screen and its size: a line's value just inside
    /// the edge it is on (below the top, above the bottom, right of the left, left of the right), a zone or square centred on its point
    /// (the web's CSS).
    public static func pillTopLeft(
        _ label: GridLabel, _ anchor: ScreenPoint, _ widthPx: Double, _ heightPx: Double, _ gapPx: Double
    ) -> ScreenPoint {
        // The grid gives an edge as one of these four ASCII words or none, so `==` (canonical equivalence) is the UTF-16 comparison.
        switch label.edge {
        case "top"?: ScreenPoint(x: anchor.x - widthPx / 2, y: anchor.y + gapPx)
        case "bottom"?: ScreenPoint(x: anchor.x - widthPx / 2, y: anchor.y - heightPx - gapPx)
        case "left"?: ScreenPoint(x: anchor.x + gapPx, y: anchor.y - heightPx / 2)
        case "right"?: ScreenPoint(x: anchor.x - widthPx - gapPx, y: anchor.y - heightPx / 2)
        default: ScreenPoint(x: anchor.x - widthPx / 2, y: anchor.y - heightPx / 2)
        }
    }

    // A point on the screen to the world's coordinates (0 to 1 across, not folded; 0 at the top to 1 at the bottom): MapProjection's
    // arithmetic.
    private struct Screen {
        let middleX: Double
        let middleY: Double
        let worldPx: Double
        let centerX: Double
        let centerY: Double
        let bearing: Double

        init(_ camera: CameraState, _ widthPx: Double, _ heightPx: Double, _ density: Double) {
            middleX = widthPx / 2
            middleY = camera.paddingTopPx + (heightPx - camera.paddingTopPx - camera.paddingBottomPx) / 2
            worldPx = MgrsGridScene.tilePoints * pow(2.0, camera.zoom) * density
            centerX = (camera.center.lon + 180) / 360
            let lat = coerceIn(camera.center.lat, -MgrsGridScene.maxLatitude, MgrsGridScene.maxLatitude)
            centerY = 0.5 - log(tan(Double.pi / 4 + toRadians(lat) / 2)) / (2 * Double.pi)
            bearing = toRadians(camera.bearingDegrees)
        }

        func world(_ x: Double, _ y: Double) -> (x: Double, y: Double) {
            let sx = x - middleX
            let sy = y - middleY
            let ux = sx * cos(bearing) - sy * sin(bearing)
            let uy = sx * sin(bearing) + sy * cos(bearing)
            return (centerX + ux / worldPx, centerY + uy / worldPx)
        }
    }

    private static func mercatorY(_ lat: Double) -> Double {
        log(tan(Double.pi / 4 + toRadians(javaMin(maxLatitude, javaMax(-maxLatitude, lat))) / 2))
    }

    private static func mercatorLat(_ y: Double) -> Double { toDegrees(2 * atan(exp(y)) - Double.pi / 2) }
}
