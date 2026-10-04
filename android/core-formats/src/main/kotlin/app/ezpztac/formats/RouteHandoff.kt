package app.ezpztac.formats

import app.ezpztac.model.JsNumber
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A route as other apps take it: a Garmin flight plan (`.fpl`, ForeFlight's and Garmin Pilot's import) and a GPX 1.1 route (ATAK, Garmin Pilot and most others), written
 * from **all of the route's points**, shaping points included, so the path is the one that is flown. A port of `foreflight.js`, held to `contracts/fixtures/routes/handoff.json`.
 *
 * What is easy to get wrong, each in the fixture:
 * - **Identifiers** are uppercase letters and digits only, ten at most (the Garmin convention), a point with none left called `WP<position>`, and duplicates numbered
 *   (`CP`, `CP2`, `CP3`; the number takes the place of the last characters so the result stays within ten).
 * - **Coordinates** are `toFixed(6)`, JavaScript's: the exact binary value rounded half up, and a tiny negative written `-0.000000`.
 * - **Names** are XML-escaped for `&`, `<` and `>` only (the web leaves quotes alone), and the route name is uppercased and cut to 25.
 * - **The FPL's creation time** is `toISOString()`: always three digits of milliseconds, in UTC.
 */
public object RouteHandoff {
    private const val MAX_IDENTIFIER = 10
    private const val MAX_ROUTE_NAME = 25
    private val CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val NOT_IDENTIFIER = Regex("[^A-Z0-9]")
    private val NOT_FILE_NAME = Regex("[^\\w-]+")

    /** The points handed over: every one, in order. */
    public fun points(route: SketchRoute): List<RoutePoint> = route.points

    /** Waypoint identifiers, one for each of [points]. */
    public fun identifiers(points: List<RoutePoint>): List<String> {
        val used = HashSet<String>()
        return points.mapIndexed { i, p ->
            var base = (p.name?.takeIf { it.isNotEmpty() } ?: "WP${i + 1}").uppercase(Locale.ROOT).replace(NOT_IDENTIFIER, "").take(MAX_IDENTIFIER)
            if (base.isEmpty()) base = "WP${i + 1}"
            var id = base
            var n = 2
            while (id in used) {
                val suffix = (n++).toString()
                id = base.take(MAX_IDENTIFIER - suffix.length) + suffix
            }
            used += id
            id
        }
    }

    /** Garmin FPL v1, with [now] as its creation time. */
    public fun fpl(route: SketchRoute, now: Instant): String {
        val points = points(route)
        val ids = identifiers(points)
        val waypoints = points.mapIndexed { i, p ->
            "    <waypoint>\n" +
                "      <identifier>${ids[i]}</identifier>\n" +
                "      <type>USER WAYPOINT</type>\n" +
                "      <country-code></country-code>\n" +
                "      <lat>${fixed(p.lat)}</lat>\n" +
                "      <lon>${fixed(p.lon)}</lon>\n" +
                "      <comment>${escape(p.name ?: "")}</comment>\n" +
                "    </waypoint>"
        }.joinToString("\n")
        val routePoints = ids.joinToString("\n") { id ->
            "    <route-point>\n" +
                "      <waypoint-identifier>$id</waypoint-identifier>\n" +
                "      <waypoint-type>USER WAYPOINT</waypoint-type>\n" +
                "      <waypoint-country-code></waypoint-country-code>\n" +
                "    </route-point>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<flight-plan xmlns=\"http://www8.garmin.com/xmlschemas/FlightPlan/v1\">\n" +
            "  <created>${CREATED.format(now)}</created>\n" +
            "  <waypoint-table>\n$waypoints\n  </waypoint-table>\n" +
            "  <route>\n" +
            "    <route-name>${escape(routeName(route))}</route-name>\n" +
            "    <flight-plan-index>1</flight-plan-index>\n" +
            "$routePoints\n" +
            "  </route>\n" +
            "</flight-plan>\n"
    }

    /** GPX 1.1: one `<rte>` of named `<rtept>`s, which imports as a single route rather than loose waypoints. */
    public fun gpx(route: SketchRoute): String {
        val points = points(route)
        val ids = identifiers(points)
        val rtepts = points.mapIndexed { i, p ->
            "    <rtept lat=\"${fixed(p.lat)}\" lon=\"${fixed(p.lon)}\">\n" +
                "      <name>${escape(ids[i])}</name>\n" +
                "    </rtept>"
        }.joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"AV Tac Tools\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            "  <rte>\n" +
            "    <name>${escape(routeName(route))}</name>\n" +
            "$rtepts\n" +
            "  </rte>\n" +
            "</gpx>\n"
    }

    /** `lat/lon lat/lon …`: ForeFlight's route search format. */
    public fun routeString(route: SketchRoute): String = points(route).joinToString(" ") { "${fixed(it.lat)}/${fixed(it.lon)}" }

    /** ForeFlight's documented URL scheme, which opens the route in its Maps view (iOS; the string is here so the apps share one definition). */
    public fun foreFlightUrl(route: SketchRoute): String = "foreflightmobile://maps/search?q=" + encodeUriComponent(routeString(route))

    /** A file name for the route: its name with anything that is not a letter, digit, underscore or hyphen made an underscore, and `route` when there is nothing. */
    public fun fileName(route: SketchRoute, extension: String): String = (route.name.replace(NOT_FILE_NAME, "_").ifEmpty { "route" }) + "." + extension

    private fun routeName(route: SketchRoute): String = route.name.uppercase(Locale.ROOT).take(MAX_ROUTE_NAME)

    private fun fixed(degrees: Double): String = JsNumber.toFixed(degrees, 6)

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** JavaScript's `encodeURIComponent`: everything but letters, digits and `- _ . ! ~ * ' ( )` as percent-escaped UTF-8 (Java's `URLEncoder` writes a space as `+`). */
    private fun encodeUriComponent(text: String): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) out.append(c.toChar())
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
        }
        return out.toString()
    }
}
