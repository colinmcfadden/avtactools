package app.ezpztac.data

import app.ezpztac.missionpacks.PackRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the open threat sets are kept between launches: a list of record uuids. A seam: the app keeps it in a preference, a test in memory. */
interface OpenThreatSetStore {
    fun load(): List<String>

    fun save(ids: List<String>)
}

/** An [OpenThreatSetStore] held in memory, for tests and previews. */
class InMemoryOpenThreatSetStore(var kept: List<String> = emptyList()) : OpenThreatSetStore {
    override fun load(): List<String> = kept

    override fun save(ids: List<String>) {
        kept = ids
    }
}

/**
 * The saved sets of threats open now, all of them on the map, under the same rules as the open LZ/PZs and route sets: leaving it deletes nothing, it outlives a restart and a sign-out,
 * and it is record uuids only, so a random id names no place and one the signed-in account does not have is simply never found.
 */
@Singleton
class OpenThreatSets @Inject constructor(private val store: OpenThreatSetStore) {
    private val _ids = MutableStateFlow(clean(store.load()))

    /** The open sets, oldest first. */
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    /** [id] has been opened: it joins the end, unless it is in already (its place is kept). */
    @Synchronized
    fun add(id: String) {
        if (!keepable(id) || id in _ids.value) return
        set((_ids.value + id).takeLast(MAX))
    }

    /** [id] is closed or deleted. The set itself is untouched. */
    @Synchronized
    fun remove(id: String) {
        if (id in _ids.value) set(_ids.value - id)
    }

    private fun set(ids: List<String>) {
        store.save(ids)
        _ids.value = ids
    }

    companion object {
        const val MAX = OpenDiagrams.MAX

        private fun keepable(id: String) = id.isNotBlank() && PackRef.parse(id) == null

        private fun clean(ids: List<String>) = ids.filter(::keepable).distinct().takeLast(MAX)
    }
}
