package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AircraftEntry
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.DiagramSummary
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.OpenPack
import app.ezpztac.data.PackFacts
import app.ezpztac.data.PackGlance
import app.ezpztac.data.PackLock
import app.ezpztac.data.SlopeState
import app.ezpztac.data.SlopeVisibility
import app.ezpztac.data.WeatherService
import app.ezpztac.data.WeatherState
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.missionpacks.LzPackKind
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import app.ezpztac.model.Notams
import app.ezpztac.planning.AircraftGeometry
import app.ezpztac.planning.LzSummary
import app.ezpztac.planning.RouteCalc
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One line of the list. Everything the row shows, worked out, so the screen only draws. */
data class DiagramRow(
    val uuid: String,
    val name: String,
    val status: DiagramStatus,
    /** The target's grid as the person typed it, or computed from its position. */
    val grid: String?,
    val sync: SyncStatus,
    val conflictOf: String?,
    val isActive: Boolean,
    /** How far along it is and whether the server has it, as the design's chips say it ([lzChips]). */
    val chips: List<LzChip> = emptyList(),
    /** A Mission Pack's item that someone else has open: "Jess R. has it open". */
    val presence: String? = null,
)

/** The pages of the LZ/PZ panel: the open LZ/PZs, the open one's plan and tools, and the aircraft. Every LZ/PZ saved is the Library's (S7). */
enum class LzPage { HOME, PLAN, AIRCRAFT }

/** The colour role a state chip is drawn in. Colour is never all it says: the words do too. */
enum class ChipTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

/** A state chip: its words, its colour role, and an [icon] where the tone's own mark is not the one to draw (a download for *Imported*). */
data class LzChip(val text: String, val tone: ChipTone, val icon: Int? = null)

/** An LZ/PZ in the Library that is not open, offered under "Recent in Library", with when it last changed (`Aug 25 · 17:34`). */
data class RecentLz(val uuid: String, val name: String, val updated: String?)

/**
 * The chips of an LZ/PZ (the web's `lzStateChips`, with the sync state where the web says whether it is saved: every LZ/PZ here is saved as it is
 * changed, docs/ANDROID_SCREENS_PLAN.md): how far along it is, then whether the server has it, or, until it is analysed, that it must be before it is
 * saved, whatever it is called.
 */
internal fun lzChips(status: DiagramStatus, sync: SyncStatus?, conflictOf: String?): List<LzChip> {
    val progress = when (status) {
        DiagramStatus.ANALYZED -> LzChip("Analyzed", ChipTone.SUCCESS)
        DiagramStatus.TARGETED -> LzChip("Target set", ChipTone.WARNING)
        DiagramStatus.DRAFT -> LzChip("No target", ChipTone.NEUTRAL)
    }
    val saved = when {
        conflictOf != null || sync == SyncStatus.CONFLICT -> LzChip("Conflict copy", ChipTone.ERROR)
        status != DiagramStatus.ANALYZED -> LzChip("Analyze to save", ChipTone.INFO)          // named or not: it is saved once it is analysed
        sync == SyncStatus.SYNCED -> LzChip("Synced", ChipTone.SUCCESS)
        else -> LzChip("Waiting to sync", ChipTone.WARNING)                        // null too: made a moment ago, the list not caught up yet
    }
    return listOf(progress, saved)
}

/**
 * The chips of a Mission Pack's LZ/PZ (the web's `lzStateChips` in a pack): how far along it is, then the first that holds of read-only, its Library
 * original changed, copied from the Library, and whether the pack has this person's last change (*Waiting to send*, *Synced*: [SYNCED_IN_PACK]).
 */
internal fun packLzChips(status: DiagramStatus, readOnly: Boolean, original: String?, fromLibrary: Boolean, waiting: Boolean): List<LzChip> {
    val progress = lzChips(status, SyncStatus.SYNCED, null).first()
    val state = when {
        readOnly -> LzChip("Read-only", ChipTone.NEUTRAL, EzpzIcons.lock)
        original == "changed" -> LzChip("Original changed", ChipTone.WARNING, EzpzIcons.update)
        fromLibrary -> LzChip("From Library", ChipTone.NEUTRAL, EzpzIcons.collectionsBookmark)
        waiting -> LzChip("Waiting to send", ChipTone.WARNING)
        else -> SYNCED_IN_PACK
    }
    return listOf(progress, state)
}

/** A pack's item the pack has every change of: said on the open one's card, and left unsaid in a list of the pack's, where it is what most rows would say (the web's). */
internal val SYNCED_IN_PACK = LzChip("Synced", ChipTone.SUCCESS)

/**
 * The open LZ/PZ or set of routes is a Mission Pack's item: where it is saved ([savedLine]; in a read-only pack, why not: "Finished by Colin M. on Oct 5 at
 * 14:20"), who else is in it ([alsoHere]), and whether the pack takes changes. While it does not, [lock] says why: [reason] in a sentence, [short] in a tool's
 * few words.
 */
data class InPackUi(
    val pack: String,
    val savedLine: String,
    val alsoHere: String?,
    val readOnly: Boolean,
    val lock: PackLock? = null,
) {
    val reason: String? get() = lock?.reason
    val short: String? get() = lock?.short
}

/**
 * A Mission Pack is open (P2b): its LZ/PZs are the panel's, in place of the person's own, which are set aside. [head] is the panel's subtitle
 * ("OP DK · 3 items · live"); [rows] the pack's LZ/PZs other than the one open, in the order they were added; [count] all of them, the open one too.
 * [opening] is what the panel says in their place while the device does not hold the pack yet (opening it for the first time, or it cannot be reached).
 */
data class PackLzListUi(
    val name: String,
    val head: String,
    val rows: List<DiagramRow>,
    val count: Int = rows.size,
    val opening: String? = null,
    /** Nothing new can be added (finished, or a viewer): no *New LZ/PZ*, [hint] says why at the foot, and [off] where a way in is shown off. */
    val readOnly: Boolean = false,
    val hint: String? = null,
    val off: String? = null,
)

/** Where the analysis of the open diagram has got to, for the card that offers it. */
sealed interface AnalysisUi {
    data object Idle : AnalysisUi
    data class Running(val stage: AnalysisStatus.Running.Stage) : AnalysisUi
    data class Failed(val message: String) : AnalysisUi
}

/** The slope tile: not measured yet, could not be, or measured and called. */
sealed interface SlopeTileUi {
    data object Measuring : SlopeTileUi
    data class Unavailable(val message: String) : SlopeTileUi

    /** [source] and [resolutionM] say where the numbers came from, so two sources are never mixed silently. */
    data class Measured(val call: LzSummary.SlopeCall, val source: String, val resolutionM: Double) : SlopeTileUi
}

/** One airframe in the picker: the web's `DESIGNATION — Name`, and `(yours)` for one the user made. */
data class AircraftOptionUi(val slug: String, val label: String)

/**
 * The mission aircraft, as the picker shows it. It drives what a new aircraft on the map is placed as, how many fit in the landing zone and
 * the separation alerts; an aircraft already on the diagram keeps the airframe it was placed as. The meta line is the web's: the spacing from
 * rotor to rotor centre (rotor diameter and tip clearance), the cruise speed and, where the numbers are only a spec sheet's, a warning.
 */
data class AircraftUi(
    val options: List<AircraftOptionUi>,
    val activeSlug: String,
    val activeLabel: String,
    val spacingM: Long,
    val cruiseKts: Long,
    /** The performance numbers come from published specifications, not from an AMPS vehicle model: do not plan fuel on them unchecked. */
    val unverified: Boolean,
    /** The user's own profiles that the server has not yet given a name to (a slug) and so cannot be chosen yet. */
    val waiting: List<String>,
) {
    companion object {
        /** The built-in UH-60L alone: what is shown until the list of airframes has loaded. */
        val UH60L = AircraftUi(
            options = listOf(AircraftOptionUi("uh60l", "UH-60L — UH-60L Black Hawk")), activeSlug = "uh60l", activeLabel = "UH-60L — UH-60L Black Hawk",
            spacingM = 76, cruiseKts = 100, unverified = false, waiting = emptyList(),
        )
    }
}

/** What the mission summary tiles say about an analysed landing zone. */
data class SummaryUi(
    val aircraft: String,
    val capacity: Int,
    val areaSqFt: Long,
    /** Feet MSL as the server wrote it (`TBD` when it could not say), or null when there is none. */
    val elevation: String?,
    val slope: SlopeTileUi,
)

/**
 * What the weather tiles say about an analysed landing zone: the nearest station's report as it was last fetched, with when, and whether a new one is
 * being fetched or could not be. A number the station did not report is `--`, never a zero. How old it is is worked out by the screen from [fetchedAtMillis],
 * so this does not change as time passes.
 */
data class WeatherUi(
    /** False when no station answered, or nothing has been fetched yet. */
    val hasReport: Boolean,
    /** Whole knots, or one decimal (`12`, `3.5`), or `--`. */
    val windSpeed: String,
    /** `G20`, when the station reported gusts. */
    val windGust: String?,
    /** Degrees true the wind is from, for the arrow; null when it is variable or not reported. */
    val windFrom: Int?,
    val windVariable: Boolean,
    val temp: String,
    /** Inches of mercury to hundredths, or `--`. */
    val altimeter: String,
    /** `KRYY · Cobb County Airport · 0.7 mi`, or null with no report. */
    val station: String?,
    /** VFR, MVFR, IFR or LIFR. */
    val category: String?,
    /** When it was fetched, or null when it has not been. */
    val fetchedAtMillis: Long?,
    val fetching: Boolean,
    /** Why the last update failed, in words, when it did. The old report, if there is one, is still shown. */
    val failure: String?,
    /** Null until a fetch has answered. */
    val notams: Notams?,
)

/** The open diagram, with what can be done to it from here. */
data class ActiveDiagramUi(
    val uuid: String,
    val name: String,
    val status: DiagramStatus,
    val grid: String?,
    /** An analysis needs a target. */
    val canAnalyze: Boolean,
    val analysis: AnalysisUi,
    val summary: SummaryUi?,
    /** The weather at the target, for an analysed diagram. */
    val weather: WeatherUi? = null,
    /** Whether the server has this version; null in the moment before the list has caught up with a diagram just made. */
    val sync: SyncStatus?,
    /** The diagram this is a conflict copy of, if it is one. */
    val conflictOf: String?,
    /** The mission aircraft and the airframes to choose from. */
    val aircraft: AircraftUi = AircraftUi.UH60L,
    /** Still called by its made-up name (`LZ/PZ 3`): *Save* names it. */
    val madeUpName: Boolean = false,
    val chips: List<LzChip> = emptyList(),
    /** Whether the slope heat map is drawn; null while there is no slope measured to draw. */
    val slopeShown: Boolean? = null,
    /** What naming it keeps, in the save dialog's words: the boundary, target and slope; its graphics. */
    val saveContents: List<String> = emptyList(),
    /** A Mission Pack's item: saved to the pack as it changes, with no *Save* of its own. Null for the person's own. */
    val inPack: InPackUi? = null,
) {
    /** *Save* names an LZ/PZ that still has its made-up name, once it is analysed (the web's "Analyze to save"); a pack's are named in the pack. */
    val canSave: Boolean get() = inPack == null && madeUpName && status == DiagramStatus.ANALYZED && conflictOf == null

    /** Why it cannot be changed: a read-only pack's. Looked at, analysed by nobody, named by nobody. */
    val locked: String? get() = inPack?.reason
}

data class DiagramsUiState(
    val current: ActiveDiagramUi? = null,
    val rows: List<DiagramRow> = emptyList(),
    /** The other open LZ/PZs, in the order they were opened ("Also open"). */
    val open: List<DiagramRow> = emptyList(),
    /** How many are open, the one on the map included. */
    val openCount: Int = 0,
    /** Up to three LZ/PZs not open, most recently changed first. */
    val recent: List<RecentLz> = emptyList(),
    val page: LzPage = LzPage.HOME,
    /** The names of the LZ/PZs other than the open one, so naming it can say when a name is taken. */
    val otherNames: List<String> = emptyList(),
    /** The form for a new diagram is open. */
    val creating: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    /** How many times the plan has been asked for: a tablet brings its pane forward each time, though the page is the plan already. */
    val planAsked: Int = 0,
    /** The open Mission Pack's LZ/PZs, while one is open; null in the Library. */
    val pack: PackLzListUi? = null,
)

/**
 * The diagrams list and what can be done from it. The open diagram is the [DiagramSession]'s; opening one here is what makes the map go to
 * it (the app watches the session), so this screen does not know about the map.
 */
@HiltViewModel
class DiagramsViewModel @Inject constructor(
    private val repository: DiagramRepository,
    private val session: DiagramSession,
    private val conflicts: ConflictResolver,
    private val analysis: AnalysisService,
    private val aircraft: AircraftProfiles,
    private val weather: WeatherService,
    private val openSet: OpenDiagrams,
    private val slopeVisibility: SlopeVisibility,
    /** The open Mission Pack: while one is open its LZ/PZs are the panel's, and a new one goes into it. */
    private val pack: OpenPack = OpenPack.None,
) : ViewModel() {
    private val local = MutableStateFlow(DiagramsUiState())
    private val page = MutableStateFlow(LzPage.HOME)
    private val analyses = combine(analysis.status, analysis.slopes, slopeVisibility.shown) { status, slopes, shown -> Triple(status, slopes, shown) }
    private val airframes = combine(aircraft.entries, aircraft.active) { entries, active -> entries to active }
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** What the panel tells the person in a toast once it has happened: Saved "LZ HAWK" to your Library. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // What the panel reads of the open pack: a presence frame that only moves someone's pointer is not passed on (PackGlance).
    private val inputs = combine(repository.observe(), session.active, local, openSet.ids, page) { summaries, active, local, open, page ->
        Inputs(summaries, active, local, open, page)
    }.combine(pack.glance) { inputs, glance -> inputs.copy(pack = glance) }

    private data class Inputs(val summaries: List<DiagramSummary>, val active: Diagram?, val local: DiagramsUiState, val open: List<String>, val page: LzPage, val pack: PackGlance? = null)

    // The open pack's LZ/PZs as diagrams, by local id, each read once for as long as its data is the same (an item no change touched keeps its instance). Made
    // again from the pack's items each time, so an item removed, or another pack's, is let go.
    private var packDiagrams: Map<String, Pair<JsonElement, Diagram>> = emptyMap()

    /** How many times a pack's LZ/PZ has been read as a diagram: a test's way of seeing that one no change touched is not read again. */
    internal var packReads = 0
        private set

    private fun diagramsOf(glance: PackGlance?): Map<String, Diagram> {
        val before = packDiagrams
        val now = if (glance == null) {
            emptyMap()
        } else {
            glance.items.filter { it.kind == "lz" }.associate { item ->
                val localId = PackRef.localId(glance.uuid, item.uuid)
                localId to (before[localId]?.takeIf { it.first === item.data } ?: (item.data to lzKind.fromItem(localId, item, null).also { packReads++ }))
            }
        }
        packDiagrams = now
        return now.mapValues { it.value.second }
    }

    private val lzKind = LzPackKind()

    val state: StateFlow<DiagramsUiState> =
        combine(inputs, analyses, airframes, weather.states) { inputs, (status, slopes, slopeShown), (entries, chosen), weatherStates ->
            val (summaries, active, local, open, page) = inputs
            val glance = inputs.pack
            val byId = summaries.associateBy { it.uuid }
            val rows = summaries.map { it.toRow(isActive = it.uuid == active?.id) }
            // In a pack the person's own LZ/PZs are set aside: what is open is the pack's.
            val openRows = if (glance != null) emptyList() else open.filter { it != active?.id }.mapNotNull { id -> byId[id]?.toRow(isActive = false) }
            val diagrams = diagramsOf(glance)
            val packList = glance?.let { packListOf(it, active, diagrams) }
            local.copy(
                current = active?.let {
                    inPack(
                        currentOf(it, status, slopes, slopeShown, byId[it.id], aircraftOf(entries, chosen), chosen, weatherStates[it.id] ?: WeatherState()), glance,
                    )
                },
                rows = rows,
                open = openRows,
                openCount = openRows.size + (if (active != null) 1 else 0),
                pack = packList,
                recent = if (glance != null) emptyList() else summaries.asSequence()
                    .filter { it.uuid !in open && it.uuid != active?.id && it.conflictOf == null }
                    .sortedByDescending { it.updatedAt.orEmpty() }
                    .take(RECENT)
                    .map { RecentLz(it.uuid, it.name, LzNames.updated(it.updatedAt)) }
                    .toList(),
                page = if (page == LzPage.PLAN && active == null) LzPage.HOME else page,
                otherNames = summaries.filter { it.uuid != active?.id }.map { it.name },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagramsUiState())

    init {
        // What is on the map is open: however it was opened (here, a launch putting back the last one, a mission brought in), it joins the set. And an LZ/PZ
        // closed from outside (a sign-out, a mission pack that removed it) takes its plan page with it: the next one opened starts from the open LZ/PZs.
        viewModelScope.launch {
            session.active.collect { diagram ->
                if (diagram != null) openSet.add(diagram.id) else if (page.value == LzPage.PLAN) page.value = LzPage.HOME
            }
        }
    }

    // -- The panel's pages -------------------------------------------------------------------------------------------------

    /** Goes to [to]: the plan only while an LZ/PZ is open. */
    fun showPage(to: LzPage) {
        if (to == LzPage.PLAN && session.active.value == null) return
        if (to == LzPage.PLAN) local.update { it.copy(planAsked = it.planAsked + 1) }
        page.value = to
    }

    /** Draws or hides the slope heat map (the plan's switch; the map's button does the same). */
    fun toggleSlope() = slopeVisibility.toggle()

    // -- Opening, making, closing ------------------------------------------------------------------------------------------

    /**
     * A new LZ/PZ with its target at [at] (the crosshair), called by the next made-up name until it is saved with one of the person's own, and opened.
     * The search bar takes the map to a grid; this starts the LZ/PZ there. While a Mission Pack is open it goes into the pack ("New LZ/PZs you start
     * while OP DK is open are added to it"), named as the pack names it, and the pack says why if it would not take it.
     */
    fun newAt(at: LatLon?) {
        if (at == null) return fail("Move the map to where the LZ/PZ should be first.")
        val grid = MgrsConverter.toMgrs(at.lat, at.lon)?.format() ?: ""
        if (pack.state.value != null) {
            if (pack.lockNow() != null) return fail(PackFacts.NOTHING_NEW)
            return run("The LZ/PZ could not be made.") {
                pack.createLz(DiagramTarget(at.lat, at.lon, grid))
                page.value = LzPage.HOME
            }
        }
        val name = LzNames.next(state.value.rows.map { it.name })
        run("The LZ/PZ could not be made.") {
            val made = repository.create(DiagramTarget(at.lat, at.lon, grid), name)
            openSet.add(made.id)
            session.open(made.id)
            page.value = LzPage.HOME
        }
    }

    /**
     * Takes [uuid] out of the open LZ/PZs. It is not deleted: the Library still has it. If it was the one on the map, the one opened before it (the last
     * still open) takes its place, or none.
     */
    fun close(uuid: String) = run("The LZ/PZ could not be closed.") {
        // A pack's LZ/PZ is closed here and stays in the pack; nothing of the person's own comes back in its place.
        packItem(uuid)?.let { item ->
            pack.closeItem(item)
            page.value = LzPage.HOME
            return@run
        }
        openSet.remove(uuid)
        if (session.active.value?.id == uuid) {
            val before = openSet.ids.value.toSet()
            try {
                session.close()
            } finally {
                // One whose record went while it was open (deleted elsewhere) is kept under a new id by the save that closing makes: it is closed too,
                // not opened again as the one before it.
                openSet.ids.value.filterNot { it in before }.forEach(openSet::remove)
            }
            page.value = LzPage.HOME
            openNext()
        }
    }

    /**
     * Opens the last LZ/PZ still in the set that is still here; an id whose LZ/PZ has gone is let go on the way. Not while a pack is open: the person's own are
     * set aside there, and come back when it is left (one of theirs on the map in a pack, closed, is not followed by the next).
     */
    private suspend fun openNext() {
        if (pack.state.value != null) return
        for (id in openSet.ids.value.reversed()) {
            if (session.open(id)) return
            openSet.remove(id)
        }
    }

    // -- Naming ------------------------------------------------------------------------------------------------------------

    /**
     * Names the open LZ/PZ [name] (the design's *Save*: everything is already saved and synced as it changes, so what saving adds is the name the
     * Library finds it by). A name another LZ/PZ already has is kept apart as `NAME 2` (the web's *Keep both*), and the toast says the name it got;
     * or, when the person chose *Replace* ([replacing], the LZ/PZ that has the name), that one is deleted and this one takes its name.
     */
    fun saveAs(name: String, replacing: String? = null) {
        val title = name.trim()
        if (title.isEmpty()) return fail("Give the LZ/PZ a name.")
        val open = session.active.value ?: return
        if (PackRef.parse(open.id) != null) return fail(SAVED_TO_PACK)                       // a pack's is saved to the pack, never named into the Library
        if (replacing == open.id) return
        val chosen = if (replacing != null) title else LzNames.free(title, state.value.otherNames)
        if (LzNames.isMadeUp(chosen)) return fail(LzNames.MADE_UP_REFUSED)
        run("The LZ/PZ could not be saved.") {
            if (session.active.value?.id != open.id) return@run
            replacing?.let { deleteNow(it) }
            session.edit("Name") { it.copy(name = chosen) }
            session.flush()
            _notices.tryEmit(savedToLibrary(chosen, replaced = replacing != null))
        }
    }

    /** Fetches the weather at the open diagram's target again, now. */
    fun refreshWeather() {
        val diagram = session.active.value ?: return
        val target = diagram.target ?: return
        weather.refresh(diagram.id, LatLon(target.lat, target.lon))
    }

    /** Chooses the mission aircraft. An airframe the list does not have (or one with no slug yet) is not chosen. */
    fun selectAircraft(slug: String) {
        aircraft.select(slug)
    }

    private fun aircraftOf(entries: List<AircraftEntry>, chosen: AircraftProfile): AircraftUi {
        fun label(p: AircraftProfile) = "${p.designation} — ${p.name}${if (p.isSystem) "" else " (yours)"}"
        // Until the list arrives the chosen airframe is the only one, so the menu is never blank (the web's rule too).
        val usable = entries.filter { it.usable }.map { it.profile }.ifEmpty { listOf(chosen) }
        return AircraftUi(
            options = usable.map { AircraftOptionUi(it.slug, label(it)) },
            activeSlug = chosen.slug, activeLabel = label(chosen),
            spacingM = RouteCalc.jsRound(AircraftGeometry.centerSpacingM(chosen)).toLong(),
            cruiseKts = RouteCalc.jsRound(chosen.defaultAirspeedKts).toLong(),
            unverified = chosen.perfSource == "published",
            waiting = entries.filter { !it.usable }.map { it.profile.name },
        )
    }

    fun startCreating() = local.update { it.copy(creating = true, error = null) }

    fun cancelCreating() = local.update { it.copy(creating = false, error = null) }

    fun dismissError() = local.update { it.copy(error = null) }

    /**
     * Makes a diagram at [targetText] (a grid or a coordinate, in any format the web accepts) and opens it. A blank [name] is named for its grid.
     */
    fun create(name: String, targetText: String) {
        val place = when (val result = PlaceSearch.resolve(targetText)) {
            is PlaceResult.Found -> result
            is PlaceResult.NotUnderstood -> return fail(result.message)
        }
        val grid = MgrsConverter.toMgrs(place.at.lat, place.at.lon)?.format() ?: ""
        val target = DiagramTarget(place.at.lat, place.at.lon, grid)
        val title = name.trim().ifEmpty { if (grid.isNotEmpty()) "LZ $grid" else "LZ ${"%.4f".format(place.at.lat)}, ${"%.4f".format(place.at.lon)}" }
        run("The diagram could not be made.") {
            val made = repository.create(target, title)
            session.open(made.id)
            local.update { it.copy(creating = false) }
        }
    }

    fun open(uuid: String) {
        if (session.active.value?.id == uuid) return
        packItem(uuid)?.let { item ->
            return run("The LZ/PZ could not be opened.") {
                if (!pack.openItem(item)) local.update { it.copy(error = "That LZ/PZ is no longer in the pack.") }
            }
        }
        if (state.value.rows.firstOrNull { it.uuid == uuid }?.isActive == true) return
        // The person's own are set aside in a pack, and come back when it is left: one opened here would be edited beside the pack's, as if it were one of them.
        if (pack.state.value != null && PackRef.parse(uuid) == null) return fail(OWN_IN_PACK)
        run("The diagram could not be opened.") {
            if (session.open(uuid)) openSet.add(uuid) else local.update { it.copy(error = "That diagram is no longer here.") }
        }
    }

    /** Renames [uuid] [name]; with [replacing], the LZ/PZ that has the name is deleted first (the Library's *Replace*). */
    fun rename(uuid: String, name: String, replacing: String? = null) {
        val title = name.trim()
        pack.lockNow()?.reasonFor(uuid)?.let { return fail(it) }
        if (title.isEmpty()) return fail("A diagram needs a name.")
        // A pack's own names are "LZ/PZ n", and nothing of a pack's is named to be saved: any name will do there, as on the web.
        if (PackRef.parse(uuid) == null && LzNames.isMadeUp(title)) return fail(LzNames.MADE_UP_REFUSED)
        if (replacing == uuid) return
        run("The diagram could not be renamed.") {
            replacing?.let { deleteNow(it) }
            // The open diagram is the one with unsaved edits in memory, so it is renamed there and saved with them.
            if (session.active.value?.id == uuid) {
                session.edit("Rename") { it.copy(name = title) }
                session.flush()
            } else {
                repository.rename(uuid, title)
            }
        }
    }

    fun delete(uuid: String) {
        // A pack's LZ/PZ goes for everyone, which is the Pack panel's to ask about (Remove from pack), never a Library delete.
        if (PackRef.parse(uuid) != null) return fail(REMOVE_IN_PACK_PANEL)
        run("The diagram could not be deleted.") { deleteNow(uuid) }
    }

    // [uuid]'s item in the open pack, when it is one.
    private fun packItem(uuid: String): String? = PackRef.parse(uuid)?.takeIf { it.pack == pack.state.value?.uuid }?.item

    /**
     * The open pack's LZ/PZs for the panel: the ones not open, each with how far along it is, what holds of it in the pack (never *Synced*, which most would say)
     * and who else has it open, and the panel's head. Before the device holds the pack, what the Pack panel says in their place.
     */
    private fun packListOf(glance: PackGlance, active: Diagram?, diagrams: Map<String, Diagram>): PackLzListUi {
        if (!glance.loaded) {
            val unreachable = glance.status == PackStatus.ERROR
            return PackLzListUi(
                glance.name, if (unreachable) "Cannot reach the pack" else "Opening…", emptyList(), count = 0,
                opening = if (unreachable) PACK_UNREACHABLE else "Opening the pack…",
            )
        }
        val items = glance.items.filter { it.kind == "lz" }
        val rows = items.filter { PackRef.localId(glance.uuid, it.uuid) != active?.id }.mapNotNull { item ->
            val localId = PackRef.localId(glance.uuid, item.uuid)
            val diagram = diagrams[localId] ?: return@mapNotNull null
            DiagramRow(
                uuid = localId, name = item.name, status = diagram.status, grid = diagram.target?.mgrs?.takeIf { it.isNotBlank() },
                sync = SyncStatus.SYNCED, conflictOf = null, isActive = false,
                // Read-only is said once, by the open card and the head, as the design draws it (the web marks every row).
                chips = packLzChips(diagram.status, false, PackFacts.original(item), PackFacts.fromLibrary(item), glance.waiting(item)) - SYNCED_IN_PACK,
                presence = glance.withItOpen(item.uuid).takeIf { it.isNotEmpty() }?.let(PackFacts::hasItOpen),
            )
        }
        val count = if (items.size == 1) "1 item" else "${items.size} items"
        return PackLzListUi(
            glance.name, "${glance.name} · $count · ${glance.standing}", rows, count = items.size, readOnly = glance.lock != null, hint = glance.lockedHint,
            off = glance.lock?.short,
        )
    }

    /**
     * The open LZ/PZ as a pack's item: its chips, where it is saved, who else is in it; no *Save* of its own. Told by its id, so one the pack's state does not list
     * for a moment (its client reading the device's copy after a pause, or removed before the editor has closed it) is still the pack's, never a Library record.
     */
    private fun inPack(ui: ActiveDiagramUi, glance: PackGlance?): ActiveDiagramUi {
        val ref = PackRef.parse(ui.uuid) ?: return ui
        val ours = glance?.takeIf { it.uuid == ref.pack }
        val item = ours?.itemOf(ui.uuid)
        val readOnly = ours?.readOnly == true
        val lock = ours?.lock
        val chips = if (ours != null && item != null) {
            packLzChips(ui.status, readOnly, PackFacts.original(item), PackFacts.fromLibrary(item), ours.waiting(item))
        } else {
            listOf(lzChips(ui.status, SyncStatus.SYNCED, null).first())
        }
        return ui.copy(
            chips = chips, madeUpName = false, sync = null, conflictOf = null,
            inPack = InPackUi(
                ours?.name ?: "the pack", (if (lock != null) ours.lockedLine else null) ?: ours?.savedLine ?: "Saved to the pack as you work.",
                item?.let { ours.withItOpen(it.uuid) }?.let(PackFacts::alsoHere), readOnly, lock,
            ),
        )
    }

    /** Deletes [uuid], closing it first if it is the one on the map (so it cannot be saved back) and opening the one before it. */
    private suspend fun deleteNow(uuid: String) {
        val wasOpen = session.active.value?.id == uuid
        if (wasOpen) session.close()
        repository.delete(uuid)
        openSet.remove(uuid)
        if (wasOpen) {
            page.value = LzPage.HOME
            openNext()
        }
    }

    /** Settles a conflict the sync kept beside a diagram. */
    fun resolve(copyUuid: String, resolution: SyncEngine.Resolution) =
        run("The conflict could not be settled.") { conflicts.resolve(RecordKind.LZ, copyUuid, resolution) }

    // -- Analysis ---------------------------------------------------------------------------------------------

    /** Analyses the open diagram: finds the landing area around its target, then measures the slope over it. */
    fun analyze() {
        val open = session.active.value ?: return
        pack.lockNow()?.reasonFor(open.id)?.let { return fail(it) }                         // asked of nobody: its result would be put back
        analysis.analyze(open.id)
    }

    /** Stops waiting for the server. */
    fun stopAnalysis() = analysis.cancel()

    fun dismissAnalysisError() = analysis.dismiss()

    private fun currentOf(
        diagram: Diagram, status: AnalysisStatus, slopes: Map<String, SlopeState>, slopeShown: Boolean, listed: DiagramSummary?, airframes: AircraftUi,
        chosen: AircraftProfile, weatherState: WeatherState,
    ): ActiveDiagramUi = ActiveDiagramUi(
        uuid = diagram.id,
        name = diagram.name,
        status = diagram.status,
        grid = diagram.target?.mgrs?.takeIf { it.isNotBlank() },
        canAnalyze = diagram.target != null,
        analysis = when {
            status is AnalysisStatus.Running && status.diagramId == diagram.id -> AnalysisUi.Running(status.stage)
            status is AnalysisStatus.Failed && status.diagramId == diagram.id -> AnalysisUi.Failed(status.message)
            else -> AnalysisUi.Idle
        },
        summary = summaryOf(diagram, slopes[diagram.id], chosen),
        weather = if (diagram.canEditGraphics) weatherOf(weatherState) else null,           // the web shows the weather once there is an analysis
        aircraft = airframes,
        sync = listed?.sync,
        conflictOf = listed?.conflictOf,
        madeUpName = LzNames.isMadeUp(diagram.name),
        chips = lzChips(diagram.status, listed?.sync, listed?.conflictOf),
        // The switch is there while the open LZ/PZ has a slope measured for the boundary it has now, as the map's button is.
        slopeShown = slopeShown.takeIf { (slopes[diagram.id] as? SlopeState.Ready)?.let { it.boundaryKey == analysis.boundaryKey(diagram) } == true },
        saveContents = saveContentsOf(diagram),
    )

    /** What naming the LZ/PZ keeps, as the save dialog lists it: its boundary, target and slope; then its graphics, counted. */
    private fun saveContentsOf(diagram: Diagram): List<String> {
        fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
        val grid = diagram.target?.mgrs?.takeIf { it.isNotBlank() }
        val g = diagram.graphics
        val placed = listOfNotNull(
            g.helicopters.size.takeIf { it > 0 }?.let { count(it, "helicopter", "helicopters") },
            g.pzMarkers.size.takeIf { it > 0 }?.let { count(it, "PZ marker", "PZ markers") },
            g.sectorsOfFire.size.takeIf { it > 0 }?.let { count(it, "sector", "sectors") },
            g.goArounds.size.takeIf { it > 0 }?.let { count(it, "go-around", "go-arounds") },
            g.units.size.takeIf { it > 0 }?.let { count(it, "unit", "units") },
        )
        val graphics = (placed + "doghouses and flight data").joinToString(", ").replaceFirstChar { it.uppercase() }
        return listOf(if (grid != null) "Boundary, target $grid and slope analysis" else "Boundary, target and slope analysis", graphics)
    }

    /** The tiles for an analysed diagram, for the mission aircraft ([aircraft]): how many of them fit is what the capacity tile says. */
    private fun summaryOf(diagram: Diagram, slope: SlopeState?, aircraft: AircraftProfile): SummaryUi? {
        val boundary = DiagramGeometry.boundary(diagram)
        if (diagram.status != DiagramStatus.ANALYZED || boundary.isEmpty()) return null
        val fit = LzSummary.areaAndCapacity(boundary, aircraft)
        val key = analysis.boundaryKey(diagram)
        val tile = when {
            slope is SlopeState.Ready && slope.boundaryKey == key -> {
                val directional = slope.analysis.directional?.let { LzSummary.Directional(it.noseHighMaxDeg, it.noseLowMaxDeg, it.crossSlopeMaxDeg) }
                SlopeTileUi.Measured(LzSummary.slopeCall(slope.analysis.stats.maxDeg, directional), slope.analysis.source, slope.analysis.resolutionM)
            }
            slope is SlopeState.Unavailable -> SlopeTileUi.Unavailable(slope.message)
            else -> SlopeTileUi.Measuring                               // not asked for yet, in flight, or measured for a boundary that has since moved
        }
        val elevation = (diagram.analysis.gridElevation as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return SummaryUi(aircraft = aircraft.designation, capacity = fit.capacity, areaSqFt = fit.areaSqFt, elevation = elevation, slope = tile)
    }

    private fun weatherOf(state: WeatherState): WeatherUi {
        val snapshot = state.snapshot
        val o = snapshot?.observation
        return WeatherUi(
            hasReport = o != null,
            windSpeed = o?.windSpeedKt?.let(::plain) ?: "--",
            windGust = o?.windGustKt?.let { "G${plain(it)}" },
            windFrom = o?.windFromDegrees, windVariable = o?.windVariable ?: false,
            temp = o?.tempC?.let(::plain) ?: "--",
            altimeter = o?.altimeterInHg?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "--",
            station = o?.let { "${it.stationId} · ${it.stationName.ifBlank { it.stationId }} · ${oneDecimal(it.distanceMiles)} mi" },
            category = o?.flightCategory,
            fetchedAtMillis = snapshot?.fetchedAtMillis,
            fetching = state.fetching, failure = state.failure,
            notams = snapshot?.notams,
        )
    }

    /** A reading as the station would say it: whole numbers without a decimal point, the rest to one place (no locale: a phone's can write other digits). */
    private fun plain(value: Double): String = JsNumber.toText(Math.round(value * 10) / 10.0)

    private fun fail(message: String) = local.update { it.copy(error = message) }

    private fun run(fallback: String, block: suspend () -> Unit) {
        // Said, not dropped: a rename or a delete asked from the Library while an LZ/PZ is still opening would otherwise vanish without a word.
        if (local.value.busy) return fail(STILL_WORKING)
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = e.message?.takeIf { m -> m.isNotBlank() } ?: fallback) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun DiagramSummary.toRow(isActive: Boolean) = DiagramRow(
        uuid = uuid, name = name, status = status, grid = target?.mgrs?.takeIf { it.isNotBlank() }, sync = sync, conflictOf = conflictOf, isActive = isActive,
        chips = lzChips(status, sync, conflictOf),
    )

    internal companion object {
        /** How many LZ/PZs "Recent in Library" offers. */
        const val RECENT = 3

        const val REMOVE_IN_PACK_PANEL = "This LZ/PZ is the pack's: Remove from pack, in the Pack panel, takes it out for everyone."

        /** A Library LZ/PZ asked for while a pack is open (the Library page's Open, until it can add one to the pack). */
        const val OWN_IN_PACK = "Your own LZ/PZs open in Personal: switch to it from the workspace chip to open this one."

        const val SAVED_TO_PACK = "This LZ/PZ is saved to the pack as you work."

        /** Said in place of the pack's items while it cannot be reached and the device has no copy of it. */
        const val PACK_UNREACHABLE = "The pack cannot be reached, and this device has no copy of it yet. It is tried again by itself."
    }
}
