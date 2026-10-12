package app.ezpztac.data

import app.ezpztac.missionpacks.PackRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the open route sets are kept between launches: a list of record uuids. A seam: the app keeps it in a preference, a test in memory. */
interface OpenRouteSetStore {
    fun load(): List<String>

    fun save(ids: List<String>)
}

/** An [OpenRouteSetStore] held in memory, for tests and previews. */
class InMemoryOpenRouteSetStore(var kept: List<String> = emptyList()) : OpenRouteSetStore {
    override fun load(): List<String> = kept

    override fun save(ids: List<String>) {
        kept = ids
    }
}

/**
 * The route sets the person has open now: the Routes half of the redesign's "this session" (docs/ANDROID_SCREENS_PLAN.md, *The session*), as [OpenDiagrams]
 * is the LZ/PZ half. One of them is on the map (the open set, [RouteSession]); the rest are "also open", in the order they were opened. Kept on the device so it
 * outlives a restart, and leaving it deletes nothing: the Library holds everything. Record uuids only, under the same rules as the open LZ/PZs: a random id names
 * no route, one the signed-in account does not have is simply never found, and a mission pack's sets are the pack's and are not kept here.
 */
@Singleton
class OpenRouteSets @Inject constructor(private val store: OpenRouteSetStore) {
    private val _ids = MutableStateFlow(clean(store.load()))

    /** The open route sets, oldest first. */
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    /** [id] has been opened: it joins the end of the set, unless it is in it already (its place is kept). */
    @Synchronized
    fun add(id: String) {
        if (!keepable(id) || id in _ids.value) return
        set((_ids.value + id).takeLast(MAX))
    }

    /** [id] is closed (or deleted): out of the set. The route set itself is untouched. */
    @Synchronized
    fun remove(id: String) {
        if (id in _ids.value) set(_ids.value - id)
    }

    private fun set(ids: List<String>) {
        store.save(ids)
        _ids.value = ids
    }

    companion object {
        /** Past this the oldest go first, as for the open LZ/PZs. */
        const val MAX = OpenDiagrams.MAX

        private fun keepable(id: String) = id.isNotBlank() && PackRef.parse(id) == null

        private fun clean(ids: List<String>) = ids.filter(::keepable).distinct().takeLast(MAX)
    }
}
