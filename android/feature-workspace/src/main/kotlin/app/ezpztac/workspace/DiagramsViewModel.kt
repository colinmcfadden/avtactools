package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.DiagramSummary
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
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

data class DiagramsUiState(
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
) : ViewModel() {
    private val local = MutableStateFlow(DiagramsUiState())

    val state: StateFlow<DiagramsUiState> = combine(repository.observe(), session.active, local) { summaries, active, local ->
        local.copy(rows = summaries.map { it.toRow(isActive = it.uuid == active?.id) })
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
