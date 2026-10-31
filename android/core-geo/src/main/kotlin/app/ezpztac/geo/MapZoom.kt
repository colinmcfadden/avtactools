package app.ezpztac.geo

import kotlin.math.cos
import kotlin.math.ln

/** What zoom shows a stretch of ground, on MapLibre's 512-point tiles (the same scale as the map screen's `metersPerPixel`). */
public object MapZoom {
    public const val MIN: Double = 3.0

    /** A diagram opens at 17; a route that is only a few hundred metres across is never shown closer than a diagram is. */
    public const val MAX: Double = 17.0

    /** About the width a phone's map shows across, in points: a route is fitted into this, leaving room beside the sheet and the controls. */
    public const val VIEW_POINTS: Double = 320.0

    private const val METERS_PER_POINT_AT_ZOOM_0 = 78_271.516964

    /** The zoom at which [spanMeters] of ground at [latitude] is about [viewPoints] points across; never past [MIN] or [MAX], and [MAX] for a span with no length. */
    public fun fitting(latitude: Double, spanMeters: Double, viewPoints: Double = VIEW_POINTS): Double {
        if (!spanMeters.isFinite() || spanMeters <= 0.0) return MAX
        val metersPerPointAtZoom0 = METERS_PER_POINT_AT_ZOOM_0 * cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0)))
        val zoom = ln(metersPerPointAtZoom0 * viewPoints / spanMeters) / ln(2.0)
        return zoom.coerceIn(MIN, MAX)
    }
}
