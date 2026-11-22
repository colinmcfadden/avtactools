package app.ezpztac.data

import app.ezpztac.model.RouteSet
import kotlinx.coroutines.CoroutineScope

/**
 * The set of control measures that is open (docs: AGENTS.md §4, *Control measures*): FLOTs and phase lines belong to no route set, so the web keeps them in sets of
 * their own, a route set's document with no routes in it (`{version: 1, flots?, phaseLines?, routes: []}`, named `CONTROL MEASURES` until renamed), in `/api/routes`
 * and in a pack as a `route` item. It is open here beside the route session's set, so a line can be drawn while a route set is open and go into neither of its routes.
 * A session as [RouteSession] is, with a pack store of its own ([items]); it never opens a mission, and lets go of no file when it closes.
 */
class MeasuresSession(
    repository: RouteRepository,
    scope: CoroutineScope,
    /** Where a mission pack's control measures are read and written. None: an id naming a pack item opens nothing. */
    val items: PackItemStore<RouteSet>? = null,
) : DocumentSession<RouteSet>(
    store = PackRoutedStore(
        library = object : DocumentStore<RouteSet> {
            override suspend fun open(uuid: String): RouteSet? = repository.open(uuid)?.takeIf { it.mission == null }
            override suspend fun save(document: RouteSet): RouteSet = repository.save(document)
        },
        packs = items,
        idOf = { it.id },
    ),
    scope = scope,
    idOf = { it.id },
    reidentify = { set, saved -> set.copy(id = saved.id, savedId = saved.savedId) },
)

/** Which set of control measures was open when the app was last used, so the next launch can open it again; forgotten when it is closed. */
interface LastMeasures {
    fun id(): String?

    fun remember(id: String?)

    companion object {
        /** Remembers nothing. */
        val None: LastMeasures = object : LastMeasures {
            override fun id(): String? = null

            override fun remember(id: String?) {}
        }
    }
}
