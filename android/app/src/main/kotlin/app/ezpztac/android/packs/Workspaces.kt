package app.ezpztac.android.packs

import android.content.Context
import androidx.core.content.edit
import app.ezpztac.android.LastDiagram
import app.ezpztac.android.LastRouteSet
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.RouteSession
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.packs.LastPack
import app.ezpztac.packs.Parking
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * [LastPack] in a plain preference: the pack's uuid (no name, nothing it holds), with the account it was open for, so another person signing in on the
 * device is never put in it.
 */
@Singleton
class LastPackPreferences @Inject constructor(@ApplicationContext context: Context) : LastPack {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    override fun id(user: Int): String? = prefs.getString(LAST_PACK, null)?.takeIf { prefs.getInt(LAST_PACK_USER, -1) == user }

    override fun remember(user: Int, uuid: String?) = prefs.edit {
        if (uuid == null) {
            remove(LAST_PACK)
            remove(LAST_PACK_USER)
        } else {
            putString(LAST_PACK, uuid)
            putInt(LAST_PACK_USER, user)
        }
    }

    private companion object {
        const val LAST_PACK = "last_pack"
        const val LAST_PACK_USER = "last_pack_user"
    }
}

/**
 * The person's own LZ/PZ and set of routes set aside while a pack is open, and put back on the way out (the web parks the Library's open LZ/PZs). Both
 * are written before either leaves the editor, and neither leaves if one cannot be written: a switch is not a sign-out, and closing would drop what was
 * not saved (`DocumentSession.close` closes whatever happens). What comes back is the one last open ([LastDiagram], [LastRouteSet], which never name a
 * pack's item), if it is still among the open ones, as a launch brings it back; each on its own, so one that will not open does not keep the other.
 */
@Singleton
class SessionParking @Inject constructor(
    private val diagrams: DiagramSession,
    private val routes: RouteSession,
    private val lastDiagram: LastDiagram,
    private val lastSet: LastRouteSet,
    private val openDiagrams: OpenDiagrams,
    private val openSets: OpenRouteSets,
) : Parking {
    override suspend fun park() {
        // Only the person's own: a pack's item is the pack's to close (PackWorkspace).
        val diagram = diagrams.active.value?.id?.takeIf { PackRef.parse(it) == null }
        val set = routes.active.value?.id?.takeIf { PackRef.parse(it) == null }
        // Written first, both: a write that fails throws here and keeps the document open, and owed.
        if (diagram != null) diagrams.flush()
        if (set != null) routes.flush()
        if (diagram != null) diagrams.closeIfOpen(diagram)
        if (set != null) routes.closeIfOpen(set)
    }

    override suspend fun unpark() {
        quietly { lastDiagram.id()?.takeIf { it in openDiagrams.ids.value && diagrams.active.value == null }?.let { diagrams.open(it) } }
        quietly { lastSet.id()?.takeIf { it in openSets.ids.value && routes.active.value == null }?.let { routes.open(it) } }
    }

    /** A document that will not open (gone, or the database) leaves the person to start from the list, as a launch does. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
