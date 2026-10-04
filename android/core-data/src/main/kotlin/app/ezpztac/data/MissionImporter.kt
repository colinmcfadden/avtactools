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
 * Brings an AMPS mission in **as it is**: the `.msnx` is kept and synced as the saved document (the web's `kind: mission`), and what the person changes is written back into
 * it, so everything AMPS keeps in it that this app never reads survives. The mission is opened as a set of routes, and the map taken to it, because a person who has just
 * imported a mission wants to see it.
 */
class MissionImporter @Inject constructor(
    private val routes: RouteRepository,
    private val session: RouteSession,
    private val focus: MapFocus,
) {
    /** [bytes] is the file and [mission] what was read out of it (so it is read once). */
    suspend fun import(bytes: ByteArray, mission: Mission, fileName: String): MissionImportOutcome {
        if (mission.routes.none { it.points.isNotEmpty() }) return MissionImportOutcome.Refused("This mission has no routes with points to bring in.")
        val uuid = java.util.UUID.randomUUID().toString()
        var n = 0
        val made = MissionRoutes.toMissionSketchRoutes(mission, emptyList()) { "mission-${uuid.take(8)}-${n++}" }
        val set = routes.createMission(RouteSet(id = uuid, name = nameOf(fileName), routes = made), fileName, bytes)
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
