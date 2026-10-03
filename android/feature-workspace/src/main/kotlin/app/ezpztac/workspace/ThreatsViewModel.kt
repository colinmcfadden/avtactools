package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.ExportResult
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.model.LatLon
import app.ezpztac.model.ThreatDraft
import app.ezpztac.model.ThreatEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ThreatRowUi(
    val id: String,
    val name: String,
    val sidc: String,
    val grid: String,
    val ranges: String,
    val visible: Boolean,
    val held: Boolean,
)

data class ThreatEditUi(val id: String?, val at: LatLon, val draft: ThreatDraft) {
    val isNew: Boolean get() = id == null
}

data class ThreatsUiState(
    val threats: List<ThreatRowUi> = emptyList(),
    val editing: ThreatEditUi? = null,
    val importing: Boolean = false,
    val exporting: Boolean = false,
    val error: String? = null,
    val note: String? = null,
)

/** The Threats part of the sheet. The store is local-only; importing and exporting are explicit file operations, never sync. */
@HiltViewModel
class ThreatsViewModel @Inject constructor(
    private val store: ThreatStore,
    private val selection: ThreatSelection,
    private val transfer: ThreatTransfer,
) : ViewModel() {
    private data class Local(
        val editing: ThreatEditUi? = null,
        val importing: Boolean = false,
        val exporting: Boolean = false,
        val error: String? = null,
        val note: String? = null,
    )

    internal var worker: CoroutineDispatcher = Dispatchers.IO
    private val local = MutableStateFlow(Local())
    private val _exports = MutableSharedFlow<ExportFile>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val exports: SharedFlow<ExportFile> = _exports.asSharedFlow()

    val state: StateFlow<ThreatsUiState> = combine(store.entries, selection.held, local) { entries, held, local ->
        ThreatsUiState(entries.map { it.toRow(held) }, local.editing, local.importing, local.exporting, local.error, local.note)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThreatsUiState())

    fun beginAdd(at: LatLon?) {
        if (at == null) return fail("Move the map to where the threat should go first.")
        local.update { it.copy(editing = ThreatEditUi(null, at, ThreatDraft.forNew(store.entries.value.size)), error = null, note = null) }
    }

    fun beginEdit(id: String) {
        val entry = store.entries.value.firstOrNull { it.id == id } ?: return
        selection.select(id)
        local.update { it.copy(editing = ThreatEditUi(id, LatLon(entry.threat.lat, entry.threat.lon), ThreatDraft.of(entry.threat)), error = null, note = null) }
    }

    fun updateDraft(draft: ThreatDraft) = local.update { current -> current.copy(editing = current.editing?.copy(draft = draft), error = null) }

    fun cancelEdit() = local.update { it.copy(editing = null, error = null) }

    fun saveEdit() {
        val editing = local.value.editing ?: return
        when (val checked = editing.draft.check(editing.at, editing.id?.let { id -> store.entries.value.firstOrNull { it.id == id }?.threat })) {
            is ThreatDraft.Checked.Refused -> fail(checked.message)
            is ThreatDraft.Checked.Valid -> {
                val id = editing.id
                if (id == null) selection.select(store.add(checked.threat)) else if (store.replace(id, checked.threat)) selection.select(id) else return
                local.update { it.copy(editing = null, error = null, note = if (id == null) "Threat added." else "Threat updated.") }
            }
        }
    }

    fun select(id: String) = selection.toggle(id)

    fun deselect() = selection.clear()

    fun toggleVisible(id: String) {
        val entry = store.entries.value.firstOrNull { it.id == id } ?: return
        store.setVisible(id, !entry.visible)
        if (entry.visible && selection.held.value == id) selection.clear()
    }

    fun moveToCrosshair(id: String, at: LatLon?) {
        if (at == null) return fail("Move the map to the new position first.")
        if (store.move(id, at.lat, at.lon)) local.update { it.copy(error = null, note = "Threat moved to the map centre.") }
    }

    fun remove(id: String) {
        if (store.remove(id)) {
            if (selection.held.value == id) selection.clear()
            if (local.value.editing?.id == id) cancelEdit()
        }
    }

    fun importFile(bytes: ByteArray) {
        local.update { it.copy(importing = true, error = null, note = null) }
        viewModelScope.launch {
            val result = try {
                withContext(worker) { transfer.import(bytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ThreatTransfer.Imported.Refused("That file could not be read.")
            }
            when (result) {
                is ThreatTransfer.Imported.Refused -> local.update { it.copy(importing = false, error = result.message) }
                is ThreatTransfer.Imported.Threats -> {
                    if (result.threats.isEmpty()) local.update { it.copy(importing = false, error = "That .ths file contains no threats.") }
                    else {
                        val ids = store.addAll(result.threats)
                        selection.select(ids.last())
                        local.update { it.copy(importing = false, note = "Imported ${count(result.threats.size)}.") }
                    }
                }
            }
        }
    }

    fun importFailed(message: String) = local.update { it.copy(importing = false, error = message, note = null) }

    fun export(baseName: String? = null) {
        local.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            val result = try {
                withContext(worker) { transfer.export(store.entries.value, baseName) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ExportResult.Refused("The threat file could not be built.")
            }
            when (result) {
                is ExportResult.Refused -> local.update { it.copy(exporting = false, error = result.message) }
                is ExportResult.Ready -> {
                    _exports.emit(ExportFile(result.fileName, result.bytes))
                    local.update { it.copy(exporting = false, note = "Threat file ready to share.") }
                }
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
    fun dismissNote() = local.update { it.copy(note = null) }

    private fun fail(message: String) = local.update { it.copy(error = message) }

    private fun ThreatEntry.toRow(heldId: String?) = ThreatRowUi(
        id, threat.name, threat.milstdId,
        MgrsConverter.toMgrs(threat.lat, threat.lon)?.format() ?: "%.5f, %.5f".format(java.util.Locale.ROOT, threat.lat, threat.lon),
        threat.radars.joinToString(" / ") { "${plain(it.rangeNmi)} nmi" }, visible, id == heldId,
    )

    private fun count(n: Int) = if (n == 1) "1 threat" else "$n threats"
    private fun plain(n: Double) = if (n == Math.floor(n)) n.toLong().toString() else n.toString()
}
