import CoreJS

/// The boundaries a diagram holds, as points. They are saved the way the web keeps them, `[[lat, lon], …]` in JSON of uncertain
/// shape, so reading one is the one place a bad point is dealt with: it is skipped, never guessed at.
public enum DiagramGeometry {
    /// `[[lat, lon], …]` as points. An entry that is not a pair of numbers is left out; anything that is not an array is no points.
    public static func points(_ saved: JSONTree?) -> [LatLon] {
        (saved?.arrayValue?.elements ?? []).compactMap { point in
            guard let pair = point.arrayValue, let lat = number(pair[safe: 0]), let lon = number(pair[safe: 1]) else { return nil }
            return LatLon(lat: lat, lon: lon)
        }
    }

    /// A finite number, from a number or from numeric text (the web's map reads `"34.5"` as 34.5); NaN, infinity and everything else
    /// are nothing. Kotlin's `doubleOrNull`: the primitive's text read with Java's grammar.
    private static func number(_ value: JSONTree?) -> Double? {
        guard let value, let text = ModelJSON.content(value), let number = Kotlin.toDoubleOrNull(text), number.isFinite else { return nil }
        return number
    }

    /// The analysis boundary: a polygon of three points or more, or nothing. Not closed (the first point is not repeated at the end).
    public static func boundary(_ diagram: Diagram) -> [LatLon] { polygon(diagram.analysis.detectedLZ) }

    /// A boundary the person drew and has not analysed: two points (a line being drawn) or more, or nothing.
    public static func drawn(_ diagram: Diagram) -> [LatLon] {
        let all = points(diagram.analysis.customLZ)
        return all.count >= 2 ? all : []
    }

    /// `saved` as a polygon: three points or more, or nothing.
    public static func polygon(_ saved: JSONTree?) -> [LatLon] {
        let all = points(saved)
        return all.count >= 3 ? all : []
    }
}
