package app.ezpztac.data

import app.ezpztac.formats.MsnxException
import app.ezpztac.formats.MsnxWriter
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteCalc
import java.time.LocalDate
import javax.inject.Inject

/** The AMPS mission an export is built on: the bundled UH-60L mission, which carries the one vehicle model the app can make a file for. */
public fun interface MissionTemplate {
    public fun bytes(): ByteArray
}

/** What exporting routes for AMPS came to. */
public sealed interface ExportResult {
    /** [bytes] is the `.msnx`. [warning] is something the person should know about the file before they send it, or null. */
    public data class Ready(val fileName: String, val bytes: ByteArray, val warning: String?) : ExportResult {
        override fun equals(other: Any?): Boolean = other is Ready && fileName == other.fileName && warning == other.warning && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * (31 * fileName.hashCode() + (warning?.hashCode() ?: 0)) + bytes.contentHashCode()
    }

    public data class Refused(val message: String) : ExportResult
}

/**
 * Exporting routes as an AMPS mission (`.msnx`), as the web's "Export" does (`buildSketchMsnx`): the file is named for the routes (their names joined with `_`) and holds
 * all of them, every one planned as the person has planned it.
 *
 * **What the file is an airframe of.** The bundled mission carries a UH-60L vehicle model that only AMPS can make, so a route planned for another aircraft still opens in AMPS as a
 * UH-60L. Its speeds, altitudes and winds export correctly; the person is told ([ExportResult.Ready.warning]) so they learn it here and not in AMPS. An administrator can attach another
 * airframe's real package to a profile, and the web uses it; this does not yet.
 */
public class RouteExport @Inject constructor(private val template: MissionTemplate) {
    /**
     * The mission for [routes] (all of them, in order), or why not: nothing to export, or a route that has fewer than two named points to run between (naming it).
     * [today] is the day a clock time falls on when a plan names none.
     */
    public fun build(routes: List<SketchRoute>, today: LocalDate = LocalDate.now()): ExportResult {
        if (routes.isEmpty()) return ExportResult.Refused("There is no route to export.")
        routes.firstOrNull { RouteCalc.planPoints(it.points).size < 2 }?.let {
            return ExportResult.Refused("${it.name} needs at least two named points before it can be exported.")
        }
        val bytes = try {
            MsnxWriter.build(template.bytes(), routes, missionName = routes.joinToString("_") { it.name }, today = today)
        } catch (e: MsnxException) {
            return ExportResult.Refused("The mission could not be built: ${e.message}")
        }
        return ExportResult.Ready(fileName(routes), bytes, warning(routes))
    }

    /** The routes of [set] to export: [routeId] alone, or every route in it. */
    public fun routesOf(set: RouteSet, routeId: String? = null): List<SketchRoute> = if (routeId == null) set.routes else listOfNotNull(set.route(routeId))

    private fun warning(routes: List<SketchRoute>): String? {
        val other = routes.firstOrNull { !it.plan.aircraftProfile.equals("uh60l", ignoreCase = true) } ?: return null
        val name = other.plan.aircraft.trim()
        // The web's text, except that an airframe with no name is "the selected aircraft", not "a the selected aircraft".
        val notA = if (name.isEmpty()) "the selected aircraft" else "a $name"
        val trueFile = if (name.isEmpty()) "a file for the selected aircraft" else "a true $name file"
        return "This mission will open in AMPS as a UH-60L, not $notA. Planned speeds, altitudes, and winds still export correctly. " +
            "To get $trueFile, an administrator needs to attach that airframe's AMPS package to the profile."
    }

    public companion object {
        private const val MAX_NAME = 80

        /** `NAME_NAME.msnx`: the routes' names joined, with anything a file system or a chat app would trip over made an underscore. */
        public fun fileName(routes: List<SketchRoute>): String {
            val base = routes.joinToString("_") { it.name }
                .map { if (it.isLetterOrDigit() || it in " -_.") it else '_' }.joinToString("")
                .replace(Regex("""\.{2,}"""), "_")
                .trim(' ', '.', '_')
                .take(MAX_NAME).trim(' ', '.', '_')
            return (base.ifEmpty { "ROUTES" }) + ".msnx"
        }
    }
}
