package app.ezpztac.data

import app.ezpztac.model.RouteSet
import kotlinx.coroutines.CoroutineScope

/**
 * The set of routes that is open: held in memory while its routes are drawn and planned, written back to the database after a pause, and undoable
 * ([DocumentSession] says how, and why the wait). Nothing in a route set follows from anything else, so there is nothing to tidy after a change.
 *
 * A mission pack's route set opens here too, under an id naming the pack and the item ([app.ezpztac.missionpacks.PackRef]), so every route tool works
 * on it as on any set; it is read from and written to [packs] ([PackItemStore]), never the library, and closing it lets go of nothing the library
 * holds (an open mission's file).
 */
class RouteSession(
    repository: RouteRepository,
    scope: CoroutineScope,
    /** Where a mission pack's route sets are read and written. None: an id naming a pack item opens nothing. */
    packs: DocumentStore<RouteSet>? = null,
) : DocumentSession<RouteSet>(
    store = PackRoutedStore(
        library = object : DocumentStore<RouteSet> {
            override suspend fun open(uuid: String): RouteSet? = repository.open(uuid)
            override suspend fun save(document: RouteSet): RouteSet = repository.save(document)
            override fun release(uuid: String) = repository.release()
        },
        packs = packs,
        idOf = { it.id },
    ),
    scope = scope,
    idOf = { it.id },
    reidentify = { set, saved -> set.copy(id = saved.id, savedId = saved.savedId) },
)
