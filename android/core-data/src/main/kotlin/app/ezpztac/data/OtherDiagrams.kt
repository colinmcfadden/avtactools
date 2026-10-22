package app.ezpztac.data

import app.ezpztac.missionpacks.LzPackKind
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackState
import app.ezpztac.model.Diagram
import app.ezpztac.sync.LocalRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The open LZ/PZs other than the one being worked on, for the map to draw dimmed under it (the owner's request of 2026-10-21): making a new LZ/PZ no longer takes
 * the last one off the map. The web draws the open one alone (AGENTS.md §15).
 *
 * In the Library, the person's own open ones ([OpenDiagrams]) as their records have them: each was written as it was left, since opening another writes the one
 * before. Only their rows are read ([DiagramRepository.records]), and each is read as a diagram again only when its record changed. While a Mission Pack is open,
 * the pack's other LZ/PZs as the pack has them, each read once for as long as its data is the same instance (an item no change touched keeps it); the person's own
 * are set aside there, as the panels set them aside. Those the person hid ([DiagramsOnMap.hidden]) are left out.
 */
class OtherDiagrams(
    private val repository: DiagramRepository,
    private val openSet: OpenDiagrams,
    private val onMap: DiagramsOnMap,
    /** Where the documents are read: the work of several, so not the main thread. A test gives its own. */
    private val work: CoroutineDispatcher,
) {
    @Inject
    constructor(repository: DiagramRepository, openSet: OpenDiagrams, onMap: DiagramsOnMap) : this(repository, openSet, onMap, Dispatchers.Default)

    /** A pack's LZ/PZs, told apart by instance: a pack's state changes many times a second (people pointing), and an item can be megabytes. */
    private class PackLzs(val pack: String, val items: List<PackItemView>) {
        fun same(other: PackLzs): Boolean = pack == other.pack && items.size == other.items.size &&
            items.indices.all { i -> items[i].uuid == other.items[i].uuid && items[i].name == other.items[i].name && items[i].data === other.items[i].data }
    }

    /**
     * The others, in the order the panel lists them, for [active] the one being worked on and [pack] the open Mission Pack (null in the Library). A list is passed
     * on only when one of them is a new document (an edit, a sync, one opened, closed, hidden or shown): the same ones read again are the same instances.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(active: Flow<String?>, pack: Flow<PackState?>): Flow<List<Diagram>> = flow {
        // What each was read from, and what it was read as: kept for as long as it is drawn, so one that did not change is not read again.
        var own = emptyMap<String, Pair<LocalRecord, Diagram>>()
        var packed = emptyMap<String, Pair<PackItemView, Diagram>>()
        val kind = LzPackKind()
        val activeId = active.distinctUntilChanged()
        val packLzs = pack.map { state -> state?.let { s -> PackLzs(s.uuid, s.items.filter { it.kind == "lz" }) } }
            .distinctUntilChanged { a, b -> if (a == null || b == null) a === b else a.same(b) }
        emitAll(
            packLzs.flatMapLatest { lzs ->
                if (lzs != null) {
                    own = emptyMap()
                    combine(activeId, onMap.hidden) { open, hidden ->
                        val read = LinkedHashMap<String, Pair<PackItemView, Diagram>>()
                        for (item in lzs.items) {
                            val id = PackRef.localId(lzs.pack, item.uuid)
                            if (id == open || id in hidden) continue
                            read[id] = packed[id]?.takeIf { (was, _) -> was.data === item.data && was.name == item.name } ?: (item to kind.fromItem(id, item, null))
                        }
                        packed = read
                        read.values.map { it.second }
                    }
                } else {
                    packed = emptyMap()
                    combine(openSet.ids, activeId, onMap.hidden) { ids, open, hidden -> ids.filter { it != open && it !in hidden } }
                        .distinctUntilChanged()
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) {
                                own = emptyMap()
                                flowOf(emptyList())
                            } else {
                                repository.records(ids.toSet()).map { records ->
                                    val byId = records.associateBy { it.uuid }
                                    val read = LinkedHashMap<String, Pair<LocalRecord, Diagram>>()
                                    for (id in ids) {
                                        val record = byId[id] ?: continue                              // gone: deleted, or another account's
                                        read[id] = own[id]?.takeIf { it.first == record } ?: (record to repository.diagramOf(record))
                                    }
                                    own = read
                                    read.values.map { it.second }
                                }
                            }
                        }
                }
            },
        )
    }.distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it] === b[it] } }.flowOn(work)
}
