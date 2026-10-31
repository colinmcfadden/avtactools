package app.ezpztac.data

import app.ezpztac.formats.RouteHandoff
import app.ezpztac.model.SketchRoute
import java.time.Instant
import javax.inject.Inject

/** A file another app can import a route from. */
public enum class HandoffFormat(public val extension: String) {
    /** GPX 1.1 route: ATAK, Garmin Pilot and most map apps. */
    GPX("gpx"),

    /** Garmin flight plan: Garmin Pilot, and ForeFlight on iOS. */
    FPL("fpl"),
}

/**
 * A route as a file for another app (`foreflight.js`'s GPX and FPL, held to `contracts/fixtures/routes/handoff.json`): every point of it, shaping points too, so the path
 * is the one flown. The route's positions are written in the clear into a file the person then chooses where to send; nothing is sent by the app.
 */
public class RouteHandoffExport @Inject constructor() {
    public fun build(route: SketchRoute, format: HandoffFormat, now: Instant = Instant.now()): ExportResult {
        if (route.points.isEmpty()) return ExportResult.Refused("${route.name} has no points to share.")
        val text = when (format) {
            HandoffFormat.GPX -> RouteHandoff.gpx(route)
            HandoffFormat.FPL -> RouteHandoff.fpl(route, now)
        }
        return ExportResult.Ready(RouteHandoff.fileName(route, format.extension), text.toByteArray(Charsets.UTF_8), warning = null)
    }
}
