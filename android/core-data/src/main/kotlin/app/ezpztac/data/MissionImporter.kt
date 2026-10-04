package app.ezpztac.data

import app.ezpztac.model.Mission
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.MissionRoutes
import javax.inject.Inject

/** What bringing a mission's routes in came to. */
sealed interface MissionImportOutcome {
    /** [set] was made from the mission's routes; [opened] is whether it is now the open set (another set that could not be saved first keeps its place). */
    data class Imported(val set: RouteSet, val opened: Boolean) : MissionImportOutcome

    /** Nothing was made; [message] is in words for the person. */
    data class Refused(val message: String) : MissionImportOutcome
}

/**
 * Brings the routes of an AMPS mission into a **new set of routes**: a copy the person can change and send on, saved and synced like any set they draw. The file
 * is not kept and not changed (see [MissionRoutes] for what that does and does not carry). The new set is opened, and the map taken to it, because a person who
 * has just imported a mission wants to see it.
 */
class MissionImporter @Inject constructor(
    private val routes: RouteRepository,
    private val session: RouteSession,
    private val focus: MapFocus,
) {
    suspend fun import(mission: Mission, fileName: String): MissionImportOutcome {
        val made = MissionRoutes.toSketchRoutes(mission, RouteIds.Random::route)
        if (made.isEmpty()) return MissionImportOutcome.Refused("This mission has no routes with points to bring in.")
        val set = routes.create(nameOf(fileName), made)
        val opened = session.open(set.id)
        MissionRoutes.extentOf(made)?.let(focus::show)
        return MissionImportOutcome.Imported(set, opened)
    }

    private fun nameOf(fileName: String): String =
        fileName.replace(EXTENSION, "").trim().uppercase().take(MAX_NAME).trim().ifEmpty { DEFAULT_NAME }

    private companion object {
        val EXTENSION = Regex("""\.msnx$""", RegexOption.IGNORE_CASE)
        const val MAX_NAME = 80
        const val DEFAULT_NAME = "IMPORTED MISSION"
    }
}
