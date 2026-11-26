import Foundation

/// What zoom shows a stretch of ground, on MapLibre's 512-point tiles (the same scale as the map screen's `metersPerPixel`).
public enum MapZoom {
    public static let min = 3.0

    /// A diagram opens at 17; a route that is only a few hundred metres across is never shown closer than a diagram is.
    public static let max = 17.0

    /// About the width a phone's map shows across, in points: a route is fitted into this, leaving room beside the sheet and the
    /// controls.
    public static let viewPoints = 320.0

    private static let metersPerPointAtZoom0 = 78_271.516964

    /// The zoom at which `spanMeters` of ground at `latitude` is about `viewPoints` points across; never past `min` or `max`, and
    /// `max` for a span with no length.
    public static func fitting(_ latitude: Double, _ spanMeters: Double, viewPoints: Double = viewPoints) -> Double {
        if !spanMeters.isFinite || spanMeters <= 0.0 { return max }
        // Java's toRadians multiplies by this constant, which is what pi / 180 rounds to.
        let perPoint = metersPerPointAtZoom0 * cos(coerceIn(latitude, -85.0, 85.0) * (Double.pi / 180))
        let zoom = log(perPoint * viewPoints / spanMeters) / log(2.0)
        return coerceIn(zoom, min, max)
    }
}
