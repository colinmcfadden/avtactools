package app.ezpztac.data

import app.ezpztac.model.RouteSet
import kotlinx.coroutines.CoroutineScope

/**
 * The set of routes that is open: held in memory while its routes are drawn and planned, written back to the database after a pause, and undoable
 * ([DocumentSession] says how, and why the wait). Nothing in a route set follows from anything else, so there is nothing to tidy after a change.
 */
class RouteSession(
    repository: RouteRepository,
    scope: CoroutineScope,
) : DocumentSession<RouteSet>(
    store = object : DocumentStore<RouteSet> {
        override suspend fun open(uuid: String): RouteSet? = repository.open(uuid)
        override suspend fun save(document: RouteSet): RouteSet = repository.save(document)
    },
    scope = scope,
    idOf = { it.id },
    reidentify = { set, saved -> set.copy(id = saved.id, savedId = saved.savedId) },
)
