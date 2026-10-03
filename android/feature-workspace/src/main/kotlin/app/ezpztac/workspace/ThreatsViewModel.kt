package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.ExportResult
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Radars
import app.ezpztac.model.SymbolPresets
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatDraft
import app.ezpztac.model.ThreatEntry
import app.ezpztac.model.Units
import app.ezpztac.planning.GraphicEdits
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
    /** The preset's name (`SAM Launcher`), or `Custom symbol` for a code that is not one. */
    val symbol: String,
    val grid: String,
    /** `Detection 25 nm · Engagement 15 nm`. */
    val ranges: String,
    val visible: Boolean,
    val held: Boolean,
)

/** The threat being held (tapped on the map, or chosen in the list): everything the file knows of it, in words. */
data class HeldThreatUi(
    val id: String,
    val name: String,
    val symbol: String,
    val grid: String,
    val latLon: String,
    val source: String,
    val information: String,
    /** One line for each radar: `Detection · 25 nm · antenna 20 ft AGL · rings on`. */
    val radars: List<String>,
)

/** The form for a threat being made ([id] null) or changed. [error] is what is wrong with it, in words for the form itself (the sheet's banner can be scrolled out of sight). */
data class ThreatEditUi(val id: String?, val at: LatLon, val draft: ThreatDraft, val error: String? = null) {
    val isNew: Boolean get() = id == null
}

data class ThreatsUiState(
    val threats: List<ThreatRowUi> = emptyList(),
    val held: HeldThreatUi? = null,
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
        ThreatsUiState(
            threats = entries.map { it.toRow(held) }, held = entries.firstOrNull { it.id == held && it.visible }?.let(::heldOf),
            editing = local.editing, importing = local.importing, exporting = local.exporting, error = local.error, note = local.note,
        )
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

    fun updateDraft(draft: ThreatDraft) = local.update { current -> current.copy(editing = current.editing?.copy(draft = draft, error = null), error = null) }

    fun cancelEdit() = local.update { it.copy(editing = null, error = null) }

    fun saveEdit() {
        val editing = local.value.editing ?: return
        when (val checked = editing.draft.check(editing.at, editing.id?.let { id -> store.entries.value.firstOrNull { it.id == id }?.threat })) {
            is ThreatDraft.Checked.Refused -> local.update { it.copy(editing = it.editing?.copy(error = checked.message)) }       // said in the form, where the person is looking
            is ThreatDraft.Checked.Valid -> {
                val id = editing.id
                if (id == null) {
                    selection.select(store.add(checked.threat))
                } else if (store.replace(id, checked.threat)) {
                    selection.select(id)
                } else {                                                                                                          // deleted (or wiped) while the form was open
                    return local.update { it.copy(editing = null, error = "That threat is no longer here.") }
                }
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

    /** Moves a threat [northFt] feet north and [eastFt] east (negative: south, west). */
    fun nudge(id: String, northFt: Double, eastFt: Double) = move(id) { at -> GraphicEdits.offset(at, northFt / Units.METERS_TO_FEET, eastFt / Units.METERS_TO_FEET) }

    /** Puts a threat at a grid or coordinate the person typed. Null when it is moved, else the words for the field. */
    fun moveToText(id: String, text: String): String? = when (val place = PlaceSearch.resolve(text)) {
        is PlaceResult.Found -> { move(id) { place.at }; null }
        is PlaceResult.NotUnderstood -> place.message
    }

    private fun move(id: String, to: (LatLon) -> LatLon) {
        val entry = store.entries.value.firstOrNull { it.id == id } ?: return
        val moved = to(LatLon(entry.threat.lat, entry.threat.lon))
        store.move(id, moved.lat, moved.lon)
    }

    /** Forgets every threat now, here and in the file (what signing out does too). */
    fun removeAll() {
        selection.clear()
        cancelEdit()
        store.wipe()
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

    /**
     * What goes to the share sheet with [mission]: the mission alone, or with the `.ths` that travels with it (named for it) when there are threats. The picture is the
     * crew's, and the mission export is how it reaches AMPS, so the two go together; a threat file that cannot be built is said so, and the mission is shared without it.
     */
    fun shareWithThreats(mission: ExportFile, share: (List<ExportFile>) -> Unit) {
        if (store.entries.value.isEmpty()) return share(listOf(mission))
        viewModelScope.launch {
            val result = try {
                withContext(worker) { transfer.export(store.entries.value, mission.fileName) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ExportResult.Refused("The threat file could not be built.")
            }
            when (result) {
                is ExportResult.Ready -> share(listOf(mission, ExportFile(result.fileName, result.bytes)))
                is ExportResult.Refused -> {
                    local.update { it.copy(error = "The threats could not be added to the export, so only the mission was shared. ${result.message}") }
                    share(listOf(mission))
                }
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
    fun dismissNote() = local.update { it.copy(note = null) }

    private fun fail(message: String) = local.update { it.copy(error = message) }

    private fun ThreatEntry.toRow(heldId: String?) = ThreatRowUi(
        id = id, name = threat.name, sidc = threat.milstdId, symbol = symbolName(threat.milstdId), grid = gridOf(threat), ranges = reachOf(threat.radars),
        visible = visible, held = id == heldId,
    )

    private fun heldOf(entry: ThreatEntry): HeldThreatUi {
        val t = entry.threat
        return HeldThreatUi(
            id = entry.id, name = t.name, symbol = symbolName(t.milstdId), grid = gridOf(t), latLon = "%.5f, %.5f".format(java.util.Locale.ROOT, t.lat, t.lon),
            source = t.source, information = t.information, radars = t.radars.map(::radarLine),
        )
    }

    private fun gridOf(t: Threat) = MgrsConverter.toMgrs(t.lat, t.lon)?.format() ?: "%.5f, %.5f".format(java.util.Locale.ROOT, t.lat, t.lon)

    private fun symbolName(sidc: String) = SymbolPresets.threats.firstOrNull { it.sidc == sidc }?.label ?: "Custom symbol"

    private fun reachOf(radars: List<Radar>): String = radars.joinToString(" · ") { "${radarName(it)} ${plain(it.rangeNmi)} nm" }.ifEmpty { "No radar" }

    private fun radarLine(r: Radar): String =
        "${radarName(r)} · ${plain(r.rangeNmi)} nm · antenna ${plain(r.antennaHeightFt)} ft ${if (r.aglNotMsl) "AGL" else "MSL"} · rings ${if (r.showRangeRings) "on" else "off"}"

    private fun radarName(r: Radar) = if (r.type == Radars.ENGAGEMENT) "Engagement" else "Detection"

    private fun count(n: Int) = if (n == 1) "1 threat" else "$n threats"

    private fun plain(n: Double) = JsNumber.toText(n)
}
