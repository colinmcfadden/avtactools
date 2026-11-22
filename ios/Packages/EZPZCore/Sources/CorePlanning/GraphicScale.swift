import CoreJS
import CoreModel
import Foundation

/// How large the LZ/PZ's planning graphics are drawn as the map zooms out (the web's `feature/graphicScale/scaleRule.js`, held to
/// contracts/fixtures/planning/graphicScale.json). A doghouse, a go-around or a unit is drawn at a fixed size on the screen, so zoomed
/// out it came to cover the LZ and the route it was there to explain. From the zoom at which the LZ is about `boundaryPoints` across
/// (the reference) they shrink as the square root of the map's scale, and two levels out are put away. A helicopter is drawn to scale
/// already, and is not one.
///
/// Zooms here are Leaflet's, as the web's are: MapLibre's zoom is Leaflet's less one (512-point tiles), so a caller on the map adds one.
public enum GraphicScale {
    /// The LZ's extent on the screen at the reference zoom: about a doghouse and a half.
    public static let boundaryPoints: Double = 100
    /// The reference with no boundary to measure (an LZ/PZ not analysed yet), and how far one may be from it.
    public static let defaultReference: Double = 17
    public static let minReference: Double = 15
    public static let maxReference: Double = 18
    /// Levels below the reference at which the graphics are put away (they are drawn at half their size there).
    public static let hideBelow: Double = 2

    /// The overlay's `MAX_LAT`: Web Mercator's limit, where latitudes are clamped before they are projected.
    static let maxLatitude = 85.0511287798

    /// Leaflet's projection at zoom 0, a 256-pixel world (the web's `project0`).
    static func project0(_ lat: Double, _ lon: Double) -> (x: Double, y: Double) {
        let sine = sin(max(-maxLatitude, min(maxLatitude, lat)) * Double.pi / 180)
        return (((lon + 180) / 360) * 256, (0.5 - log((1 + sine) / (1 - sine)) / (4 * Double.pi)) * 256)
    }

    /// A boundary's corners as `[lat, lon]`, however they came (`[lat, lon]`, `{lat, lon}` or `{lat, lng}`); those that are not,
    /// left out. Only JSON numbers count (`typeof value === "number"`).
    static func corners(_ boundary: JSONTree?) -> [(lat: Double, lon: Double)] {
        guard case .array(let list)? = boundary else { return [] }
        return list.compactMap { corner in
            let lat: JSONTree?
            let lon: JSONTree?
            if case .array = corner {
                (lat, lon) = (Js.prop(corner, "0"), Js.prop(corner, "1"))
            } else {
                // `corner?.lon ?? corner?.lng`
                (lat, lon) = (Js.prop(corner, "lat"), JsValue.present(Js.prop(corner, "lon")) ?? Js.prop(corner, "lng"))
            }
            guard case .number(let a)? = lat, case .number(let b)? = lon, a.value.isFinite, b.value.isFinite else { return nil }
            return (a.value, b.value)
        }
    }

    /// The zoom at which the boundary's longer side is `boundaryPoints` on the screen, within 15 to 18 and to a hundredth; 17 without
    /// a boundary of some size.
    public static func referenceZoom(_ boundary: JSONTree?) -> Double {
        let points = corners(boundary).map { project0($0.lat, $0.lon) }
        guard points.count >= 2 else { return defaultReference }
        let xs = points.map(\.x)
        let ys = points.map(\.y)
        let extent = max(xs.max()! - xs.min()!, ys.max()! - ys.min()!)
        guard extent > 0 else { return defaultReference }
        let zoom = log2(boundaryPoints / extent)
        return Js.round(min(maxReference, max(minReference, zoom)) * 100) / 100
    }

    /// At a map zoom, the graphics' scale (1 at the reference and nearer, to a thousandth) and whether they are put away.
    public static func scale(zoom: Double, reference: Double = defaultReference) -> (scale: Double, hidden: Bool) {
        guard zoom.isFinite else { return (1, false) }
        let scale = min(1, pow(2, (zoom - reference) / 2))
        return (Js.round(scale * 1000) / 1000, zoom < reference - hideBelow - 1e-9)
    }
}
