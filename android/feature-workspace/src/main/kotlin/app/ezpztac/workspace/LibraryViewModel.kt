package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSession
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Library's tabs (docs/native-design: Library), with what a row of each is called and what its search looks in. */
enum class LibraryTab(val label: String, val one: String, val many: String, val search: String) {
    LZ("LZ/PZ", "saved LZ/PZ", "saved LZ/PZs", "Search LZ/PZs by name or grid"),
    ROUTES("Routes", "saved route set", "saved route sets", "Search route sets by name"),
    POINTS("Local points", "saved point set", "saved point sets", "Search point sets by name"),
}

/** How the Library is ordered, as the web's. */
enum class LibrarySort(val label: String) { UPDATED("Recently updated"), NAME("Name A to Z") }

/** One saved item in the Library. Everything the row shows, worked out, so the screen only draws. */
data class LibraryRowUi(
    val uuid: String,
    val tab: LibraryTab,
    val name: String,
    /** An LZ/PZ's target grid, in the monospace face. */
    val grid: String? = null,
    /** What it is beyond its name: `Sketched routes · 2 routes`, `Mission file · GOAT.msnx`, `1,234 points`. */
    val detail: String? = null,
    /** `Updated Aug 25 · 17:34`, or null when that was never noted (never a time made up). */
    val updated: String? = null,
    /** Open in this session (an LZ/PZ or a set of routes), or on the map (a set of points): *Open* has nothing left to do. */
    val inSession: Boolean = false,
    val sync: SyncStatus = SyncStatus.SYNCED,
    /** The item this is the person's kept version of, when another device changed it too: settled with the three choices. */
    val conflictOf: String? = null,
    /** An imported AMPS mission, kept with its file. */
    val isMission: Boolean = false,
    /** When it last changed, an ISO instant, for the sort. */
    internal val updatedAt: String? = null,
)

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.LZ,
    /** The tabs shown: those of the panels the account is offered. */
    val tabs: List<LibraryTab> = LibraryTab.entries,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.UPDATED,
    /** The tab's items that match the search, in the chosen order. */
    val rows: List<LibraryRowUi> = emptyList(),
    /** How many each tab holds, whatever the search. */
    val counts: Map<LibraryTab, Int> = emptyMap(),
    /** Every item's name on each tab, so naming one can say when a name is taken (and by which). */
    val names: Map<LibraryTab, List<Pair<String, String>>> = emptyMap(),
    /** A copy is being made: one at a time. */
    val busy: Boolean = false,
) {
    /** `12 saved LZ/PZs`, under the list. */
    val total: String get() = (counts[tab] ?: 0).let { n -> if (n == 1) "1 ${tab.one}" else "${withCommas(n.toLong())} ${tab.many}" }
}

/**
 * The Library (docs/native-design: Library; the web's `LibraryDialog`): everything the person has saved, in three tabs, searched and sorted, each with what it is,
 * when it last changed and whether it is open now. Opening, renaming, deleting and settling a conflict are the panels' own (an open item is renamed and closed
 * where its unsaved edits are), so the screen hands them there; what this does itself is the list, and *Duplicate*.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val diagrams: DiagramRepository,
    private val routeSets: RouteRepository,
    private val pointSets: PointSetRepository,
    private val diagramSession: DiagramSession,
    private val routeSession: RouteSession,
    openDiagrams: OpenDiagrams,
    openRouteSets: OpenRouteSets,
    views: PointSetViews,
) : ViewModel() {
    private data class Choices(val tab: LibraryTab = LibraryTab.LZ, val query: String = "", val sort: LibrarySort = LibrarySort.UPDATED, val busy: Boolean = false)

    private val choices = MutableStateFlow(Choices())
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** What an action came to, in words, for the page's toasts. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val lzRows = combine(diagrams.observe(), openDiagrams.ids, diagramSession.active) { summaries, open, active ->
        summaries.map { s ->
            LibraryRowUi(
                s.uuid, LibraryTab.LZ, s.name, grid = s.target?.let { t -> t.mgrs.ifBlank { null } ?: MgrsConverter.toMgrs(t.lat, t.lon)?.format() },
                updated = updatedLine(s.updatedAt), inSession = s.uuid in open || s.uuid == active?.id, sync = s.sync, conflictOf = s.conflictOf,
                updatedAt = s.updatedAt,
            )
        }
    }

    private val routeRows = combine(routeSets.observe(), openRouteSets.ids, routeSession.active) { summaries, open, active ->
        summaries.map { s ->
            LibraryRowUi(
                s.uuid, LibraryTab.ROUTES, s.name,
                detail = if (s.isMission) listOfNotNull("Mission file", s.fileName).joinToString(" · ") else "Sketched routes · ${countOf(s.routeCount, "route")}",
                updated = updatedLine(s.updatedAt), inSession = s.uuid in open || s.uuid == active?.id, sync = s.sync, conflictOf = s.conflictOf,
                isMission = s.isMission, updatedAt = s.updatedAt,
            )
        }
    }

    private val pointRows = combine(pointSets.observe(), views.views) { rows, shown ->
        rows.map { r ->
            LibraryRowUi(
                r.uuid, LibraryTab.POINTS, r.name, detail = if (r.pointCount == 1) "1 point" else "${withCommas(r.pointCount.toLong())} points",
                updated = updatedLine(r.updatedAt), inSession = shown[r.uuid]?.visible ?: true, sync = r.sync, conflictOf = r.conflictOf, updatedAt = r.updatedAt,
            )
        }
    }

    val state: StateFlow<LibraryUiState> = combine(lzRows, routeRows, pointRows, choices) { lz, routes, points, c ->
        val all = mapOf(LibraryTab.LZ to lz, LibraryTab.ROUTES to routes, LibraryTab.POINTS to points)
        LibraryUiState(
            tab = c.tab, query = c.query, sort = c.sort, busy = c.busy,
            rows = sorted(all.getValue(c.tab).filter { matches(it, c.query) }, c.sort),
            counts = all.mapValues { it.value.size },
            names = all.mapValues { (_, rows) -> rows.map { it.uuid to it.name } },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** Shows [tab]; another tab starts with no search (a search is for names on one tab), the same one keeps what was typed. */
    fun showTab(tab: LibraryTab) = choices.update { if (it.tab == tab) it else it.copy(tab = tab, query = "") }

    /** The Library is opened at [tab]: from nothing, as the web's (no search, the newest first), so *Review* never lands on a list an old search filters. */
    fun open(tab: LibraryTab) = choices.update { it.copy(tab = tab, query = "", sort = LibrarySort.UPDATED) }

    fun search(query: String) = choices.update { it.copy(query = query) }

    fun sortBy(sort: LibrarySort) = choices.update { it.copy(sort = sort) }

    /**
     * Makes a copy of [row] named the first free `NAME 2`, `NAME 3` … (the web's), under an identity of its own, and says so. An item open now is saved first, so
     * the copy has what is on the map, not what was saved a moment ago.
     */
    fun duplicate(row: LibraryRowUi) {
        if (choices.value.busy) return
        val taken = state.value.names[row.tab].orEmpty().map { it.second }
        val name = LzNames.free(row.name, taken)
        choices.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val made = when (row.tab) {
                    LibraryTab.LZ -> {
                        if (diagramSession.active.value?.id == row.uuid) diagramSession.flush()
                        diagrams.duplicate(row.uuid, name)
                    }
                    LibraryTab.ROUTES -> {
                        if (routeSession.active.value?.id == row.uuid) routeSession.flush()
                        routeSets.duplicate(row.uuid, name)
                    }
                    LibraryTab.POINTS -> pointSets.duplicate(row.uuid, name)
                }
                _notices.tryEmit(if (made != null) "Made “$name”, a copy of “${row.name}”" else "“${row.name}” is no longer in your Library.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notices.tryEmit("“${row.name}” could not be copied.")                     // the app's words: nothing from inside a failure
            } finally {
                choices.update { it.copy(busy = false) }
            }
        }
    }

    internal companion object {
        /** `Updated Aug 25 · 17:34`, or null for a time never noted or that cannot be read. */
        fun updatedLine(iso: String?): String? = LzNames.updated(iso)?.let { "Updated $it" }

        /** By name, without regard to case; and an LZ/PZ by its grid too, spaces ignored (the design's *by name or grid*). */
        fun matches(row: LibraryRowUi, query: String): Boolean {
            val wanted = query.trim().lowercase()
            if (wanted.isEmpty()) return true
            if (row.name.lowercase().contains(wanted)) return true
            val grid = row.grid ?: return false
            return grid.replace(" ", "").lowercase().contains(wanted.replace(" ", ""))
        }

        /** Most recently changed first (one never noted last), or by name, as the person reads it. */
        fun sorted(rows: List<LibraryRowUi>, sort: LibrarySort): List<LibraryRowUi> = when (sort) {
            LibrarySort.UPDATED -> rows.sortedWith(
                compareByDescending<LibraryRowUi, java.time.Instant?>(nullsFirst()) { LzNames.instantOf(it.updatedAt) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            LibrarySort.NAME -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }
}
