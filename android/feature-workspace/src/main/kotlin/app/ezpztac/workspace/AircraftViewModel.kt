package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AircraftEntry
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.model.AircraftDraft
import app.ezpztac.planning.AircraftGeometry
import app.ezpztac.planning.RouteCalc
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

/** One airframe in the list. */
data class AircraftRowUi(
    /** What names it in a list and to the view model: the record's uuid for the user's own, the slug for the admin's. */
    val key: String,
    /** The own profile's record, or null for one of the admin's. */
    val uuid: String?,
    val slug: String,
    val title: String,
    /** `76 m spacing · 100 kt`. */
    val detail: String,
    val own: Boolean,
    /** The mission aircraft. */
    val chosen: Boolean,
    /** Whether it can be chosen: an own profile the server has not yet named cannot. */
    val usable: Boolean,
    /** The numbers are from a spec sheet, not an AMPS vehicle model. */
    val unverified: Boolean,
    val sync: SyncStatus?,
    /** The uuid of the profile this is the user's kept version of, when another device changed it too. */
    val conflictOf: String?,
)

/** The form for a new or changed profile. */
data class AircraftFormUi(
    /** The record being changed, or null for a new profile. */
    val editing: String?,
    val draft: AircraftDraft,
    /** What is wrong with the draft, in words, once saving was tried. */
    val error: String? = null,
    val busy: Boolean = false,
)

data class AircraftUiState(
    val rows: List<AircraftRowUi> = emptyList(),
    val form: AircraftFormUi? = null,
    val error: String? = null,
)

/**
 * The aircraft screen: the admin's airframes and the user's own, choosing the mission aircraft, and making, copying, changing and deleting
 * the user's own. Everything works with no signal; the server hears of it at the next sync and names a new profile then.
 */
@HiltViewModel
class AircraftViewModel @Inject constructor(
    private val aircraft: AircraftProfiles,
    private val conflicts: ConflictResolver,
) : ViewModel() {
    private data class Local(val form: AircraftFormUi? = null, val error: String? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AircraftUiState> = combine(aircraft.entries, aircraft.active, local) { entries, active, local ->
        AircraftUiState(rows = entries.map { rowOf(it, active.slug) }, form = local.form, error = local.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AircraftUiState())

    private fun rowOf(entry: AircraftEntry, activeSlug: String): AircraftRowUi {
        val p = entry.profile
        val spacing = RouteCalc.jsRound(AircraftGeometry.centerSpacingM(p)).toLong()
        val kts = RouteCalc.jsRound(p.defaultAirspeedKts).toLong()
        return AircraftRowUi(
            key = entry.uuid ?: p.slug, uuid = entry.uuid, slug = p.slug, title = "${p.designation} — ${p.name}",
            detail = "$spacing m spacing · $kts kt", own = entry.own, chosen = entry.usable && p.slug == activeSlug, usable = entry.usable,
            unverified = p.perfSource == "published", sync = entry.sync, conflictOf = entry.conflictOf,
        )
    }

    private fun find(key: String) = aircraft.entries.value.firstOrNull { (it.uuid ?: it.profile.slug) == key }

    /** Chooses the mission aircraft. */
    fun choose(slug: String) {
        if (!aircraft.select(slug)) local.update { it.copy(error = "That aircraft can be chosen once it has synced.") }
    }

    // -- The form ----------------------------------------------------------------------------------------------

    fun startNew() = local.update { it.copy(form = AircraftFormUi(editing = null, draft = AircraftDraft()), error = null) }

    /** A new profile that starts as a copy of [key]'s, which is how an admin's airframe is adjusted: the admin's list is not changed. */
    fun startCopy(key: String) {
        val entry = find(key) ?: return
        local.update { it.copy(form = AircraftFormUi(editing = null, draft = AircraftDraft.copyOf(entry.profile)), error = null) }
    }

    fun startEdit(uuid: String) {
        val entry = aircraft.entries.value.firstOrNull { it.own && it.uuid == uuid } ?: return
        local.update { it.copy(form = AircraftFormUi(editing = uuid, draft = AircraftDraft.of(entry.profile)), error = null) }
    }

    /** What the person has typed so far. It is held here so a turn of the phone does not lose it. */
    fun change(draft: AircraftDraft) = local.update { it.copy(form = it.form?.copy(draft = draft, error = null)) }

    fun cancel() = local.update { it.copy(form = null) }

    fun save() {
        val form = local.value.form ?: return
        if (form.busy) return
        local.update { it.copy(form = it.form?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                val refusal = if (form.editing == null) aircraft.create(form.draft) else aircraft.update(form.editing, form.draft)
                local.update { if (refusal == null) it.copy(form = null) else it.copy(form = it.form?.copy(error = refusal, busy = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(form = it.form?.copy(error = "The aircraft could not be saved.", busy = false)) }
            }
        }
    }

    // -- The list ----------------------------------------------------------------------------------------------

    fun delete(uuid: String) = run("The aircraft could not be deleted.") { aircraft.delete(uuid) }

    /** Settles a conflict the sync kept beside a profile: the same three choices a diagram has. */
    fun resolve(copyUuid: String, resolution: SyncEngine.Resolution) =
        run("The conflict could not be settled.") { conflicts.resolve(RecordKind.AIRCRAFT, copyUuid, resolution) }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun run(fallback: String, block: suspend () -> Unit) {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(error = fallback) }
            }
        }
    }
}
