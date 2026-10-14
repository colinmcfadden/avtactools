package app.ezpztac.data

import app.ezpztac.missionpacks.PackRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the open LZ/PZs are kept between launches: a list of record uuids. A seam: the app keeps it in a preference, a test in memory. */
interface OpenDiagramStore {
    fun load(): List<String>

    fun save(ids: List<String>)
}

/** An [OpenDiagramStore] held in memory, for tests and previews. */
class InMemoryOpenDiagramStore(var kept: List<String> = emptyList()) : OpenDiagramStore {
    override fun load(): List<String> = kept

    override fun save(ids: List<String>) {
        kept = ids
    }
}

/**
 * The LZ/PZs the person has open now: the redesign's "this session" (docs/ANDROID_SCREENS_PLAN.md, *The session*). One of them is on the map (the
 * open diagram, [DiagramSession]); the rest are "also open", in the order they were opened. Kept on the device so it outlives a restart, and leaving
 * it deletes nothing: the Library holds everything. Record uuids only: a random id names no place, and one the signed-in account does not have is
 * simply never found (it is not shown, and goes once the set is full). A mission pack's items are the pack's, not the Library's, and are not kept here.
 */
@Singleton
class OpenDiagrams @Inject constructor(private val store: OpenDiagramStore) {
    private val _ids = MutableStateFlow(clean(store.load()))

    /** The open LZ/PZs, oldest first. */
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    /** [id] has been opened: it joins the end of the set, unless it is in it already (its place is kept). */
    @Synchronized
    fun add(id: String) {
        if (!keepable(id) || id in _ids.value) return
        set((_ids.value + id).takeLast(MAX))
    }

    /** [id] is closed (or deleted): out of the set. The LZ/PZ itself is untouched. */
    @Synchronized
    fun remove(id: String) {
        if (id in _ids.value) set(_ids.value - id)
    }

    private fun set(ids: List<String>) {
        store.save(ids)
        _ids.value = ids
    }

    companion object {
        /** Past this the oldest go first: a set kept for ever would gather the ids of LZ/PZs long gone. */
        const val MAX = 20

        private fun keepable(id: String) = id.isNotBlank() && PackRef.parse(id) == null

        private fun clean(ids: List<String>) = ids.filter(::keepable).distinct().takeLast(MAX)
    }
}
