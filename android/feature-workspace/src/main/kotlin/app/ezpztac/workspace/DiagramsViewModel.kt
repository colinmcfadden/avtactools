package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.DiagramSummary
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.planning.LzSummary
import app.ezpztac.data.SlopeState
import kotlinx.serialization.json.JsonPrimitive
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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

/** What the mission summary tiles say about an analysed landing zone. */
data class SummaryUi(
    val aircraft: String,
    val capacity: Int,
    val areaSqFt: Long,
    /** Feet MSL as the server wrote it (`TBD` when it could not say), or null when there is none. */
    val elevation: String?,
    val slope: SlopeTileUi,
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
    /** Whether the server has this version; null in the moment before the list has caught up with a diagram just made. */
    val sync: SyncStatus?,
    /** The diagram this is a conflict copy of, if it is one. */
    val conflictOf: String?,
)

data class DiagramsUiState(
    val current: ActiveDiagramUi? = null,
    val rows: List<DiagramRow> = emptyList(),
    /** The form for a new diagram is open. */
    val creating: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
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
) : ViewModel() {
    private val local = MutableStateFlow(DiagramsUiState())

    val state: StateFlow<DiagramsUiState> =
        combine(repository.observe(), session.active, local, analysis.status, analysis.slopes) { summaries, active, local, status, slopes ->
            local.copy(
                current = active?.let { currentOf(it, status, slopes, summaries.firstOrNull { row -> row.uuid == it.id }) },
                rows = summaries.map { it.toRow(isActive = it.uuid == active?.id) },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagramsUiState())

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
        if (state.value.rows.firstOrNull { it.uuid == uuid }?.isActive == true) return
        run("The diagram could not be opened.") {
            if (!session.open(uuid)) local.update { it.copy(error = "That diagram is no longer here.") }
        }
    }

    fun rename(uuid: String, name: String) {
        val title = name.trim()
        if (title.isEmpty()) return fail("A diagram needs a name.")
        run("The diagram could not be renamed.") {
            // The open diagram is the one with unsaved edits in memory, so it is renamed there and saved with them.
            if (session.active.value?.id == uuid) {
                session.edit("Rename") { it.copy(name = title) }
                session.flush()
            } else {
                repository.rename(uuid, title)
            }
        }
    }

    fun delete(uuid: String) = run("The diagram could not be deleted.") {
        if (session.active.value?.id == uuid) session.close()
        repository.delete(uuid)
    }

    /** Settles a conflict the sync kept beside a diagram. */
    fun resolve(copyUuid: String, resolution: SyncEngine.Resolution) =
        run("The conflict could not be settled.") { conflicts.resolve(RecordKind.LZ, copyUuid, resolution) }

    // -- Analysis ---------------------------------------------------------------------------------------------

    /** Analyses the open diagram: finds the landing area around its target, then measures the slope over it. */
    fun analyze() {
        val open = session.active.value ?: return
        analysis.analyze(open.id)
    }

    /** Stops waiting for the server. */
    fun stopAnalysis() = analysis.cancel()

    fun dismissAnalysisError() = analysis.dismiss()

    private fun currentOf(diagram: Diagram, status: AnalysisStatus, slopes: Map<String, SlopeState>, listed: DiagramSummary?): ActiveDiagramUi = ActiveDiagramUi(
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
        summary = summaryOf(diagram, slopes[diagram.id]),
        sync = listed?.sync,
        conflictOf = listed?.conflictOf,
    )

    /** The tiles for an analysed diagram. The aircraft is the UH-60L until aircraft profiles are chosen per diagram. */
    private fun summaryOf(diagram: Diagram, slope: SlopeState?): SummaryUi? {
        val boundary = DiagramGeometry.boundary(diagram)
        if (diagram.status != DiagramStatus.ANALYZED || boundary.isEmpty()) return null
        val aircraft = AircraftProfile.FALLBACK
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

    private fun fail(message: String) = local.update { it.copy(error = message) }

    private fun run(fallback: String, block: suspend () -> Unit) {
        if (local.value.busy) return
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
    )
}
