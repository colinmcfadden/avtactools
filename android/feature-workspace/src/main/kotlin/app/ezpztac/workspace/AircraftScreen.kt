package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.LabelledDivider
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.AircraftDraft
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus

class AircraftActions(
    val choose: (slug: String) -> Unit = {},
    val startNew: () -> Unit = {},
    val startCopy: (key: String) -> Unit = {},
    val startEdit: (uuid: String) -> Unit = {},
    val change: (AircraftDraft) -> Unit = {},
    val cancel: () -> Unit = {},
    val save: () -> Unit = {},
    val delete: (uuid: String) -> Unit = {},
    val resolve: (copyUuid: String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
)

/**
 * The aircraft section of the sheet: the mission aircraft, the admin's airframes, and the user's own, which can be made, copied, changed and
 * deleted. [canMake] is false for an account whose administrator has switched own aircraft off: such an account still chooses from the
 * admin's list, and a profile it made would only be refused by the server.
 */
@Composable
fun AircraftHost(canMake: Boolean, modifier: Modifier = Modifier, viewModel: AircraftViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AircraftContent(
        state = state, canMake = canMake, modifier = modifier,
        actions = AircraftActions(
            choose = viewModel::choose, startNew = viewModel::startNew, startCopy = viewModel::startCopy, startEdit = viewModel::startEdit,
            change = viewModel::change, cancel = viewModel::cancel, save = viewModel::save, delete = viewModel::delete, resolve = viewModel::resolve,
            dismissError = viewModel::dismissError,
        ),
    )
}

/** Stateless, so it can be tried (and looked at) without a view model. It starts shut: the list is long and the diagram is what the sheet is for. */
@Composable
fun AircraftContent(state: AircraftUiState, canMake: Boolean, actions: AircraftActions, modifier: Modifier = Modifier, startOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Aircraft", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            TextAction(if (open) "Hide" else "Manage aircraft", onClick = { open = !open })
        }
        if (!open) return@Column
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        val form = state.form
        if (form != null) {
            AircraftForm(form, actions)
            return@Column
        }
        if (canMake) PrimaryButton("New aircraft", onClick = actions.startNew)
        else Text("Your account can choose from the standard list. Making your own aircraft is switched off for it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.rows.forEach { row -> AircraftRow(row, canMake, actions) }
    }
}

@Composable
private fun AircraftRow(row: AircraftRowUi, canMake: Boolean, actions: AircraftActions) {
    var confirmDelete by rememberSaveable(row.key) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (row.chosen) 2.dp else 1.dp, if (row.chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val notes = listOfNotNull(
                "Mission aircraft".takeIf { row.chosen },
                if (row.own) "Yours" else null,
                "Unverified performance".takeIf { row.unverified },
                when {
                    row.conflictOf != null -> "Conflict copy"
                    row.own && row.sync != SyncStatus.SYNCED && !row.usable -> "Waiting to sync before it can be chosen"
                    row.own && row.sync != SyncStatus.SYNCED -> "Waiting to sync"
                    else -> null
                },
            )
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                    color = if (row.unverified || row.conflictOf != null) EzpzTheme.status.warning else MaterialTheme.colorScheme.primary,
                )
            }
            if (row.conflictOf != null) {
                Banner("This is your version, kept because another device changed the same aircraft. Nothing was overwritten.", BannerKind.Info)
                PrimaryButton("Keep both", onClick = { actions.resolve(row.uuid.orEmpty(), SyncEngine.Resolution.KEEP_BOTH) })
                SecondaryButton("Keep mine (replaces the other version)", onClick = { actions.resolve(row.uuid.orEmpty(), SyncEngine.Resolution.KEEP_MINE) })
                SecondaryButton("Keep theirs (discard mine)", onClick = { actions.resolve(row.uuid.orEmpty(), SyncEngine.Resolution.KEEP_THEIRS) })
            } else if (confirmDelete) {
                Banner("Delete \"${row.title}\"? Diagrams that use it keep the size it had when it was placed.", BannerKind.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Delete", onClick = { confirmDelete = false; actions.delete(row.uuid.orEmpty()) })
                    TextAction("Keep it", onClick = { confirmDelete = false })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    if (!row.chosen && row.usable) TextAction("Use", onClick = { actions.choose(row.slug) })
                    if (canMake) TextAction("Copy", onClick = { actions.startCopy(row.key) })
                    if (row.own && canMake) TextAction("Edit", onClick = { actions.startEdit(row.uuid.orEmpty()) })
                    if (row.own) TextAction("Delete", onClick = { confirmDelete = true })
                }
            }
        }
    }
}

private val ICON_LABELS = listOf(
    "UH-60 Black Hawk" to "uh60", "AH-64 Apache" to "ah64", "CH-47 Chinook" to "ch47", "UH-72 Lakota" to "uh72", "MH-6 Little Bird" to "mh6", "Generic helicopter" to "generic",
)

private val AIRSPEED_LABELS = listOf("GS (ground)" to "ground", "KIAS (indicated)" to "indicated", "KTAS (true)" to "true")

private val ALTITUDE_LABELS = listOf("AGL" to "agl", "MSL" to "msl")

/** The web's aircraft form: who it is, how big it is and so how far apart two stand, and what a route starts from. */
@Composable
private fun AircraftForm(form: AircraftFormUi, actions: AircraftActions) {
    val d = form.draft
    fun change(draft: AircraftDraft) = actions.change(draft)
    val editable = !form.busy
    Surface(shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text(if (form.editing == null) "New aircraft" else "Change aircraft", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            form.error?.let { Banner(it, BannerKind.Error) }
            EzpzTextField(
                value = d.designation, onValueChange = { change(d.copy(designation = it.take(AircraftDraft.MAX_DESIGNATION))) }, label = "Designation", enabled = editable,
                hint = "For example MH-47G", capitalization = KeyboardCapitalization.Characters,
            )
            EzpzTextField(
                value = d.name, onValueChange = { change(d.copy(name = it.take(AircraftDraft.MAX_NAME))) }, label = "Name", enabled = editable,
                hint = "For example MH-47G Chinook", capitalization = KeyboardCapitalization.Words,
            )
            Choice("Map icon", ICON_LABELS.firstOrNull { it.second == d.iconKey }?.first ?: d.iconKey, ICON_LABELS) { change(d.copy(iconKey = it)) }

            LabelledDivider("Footprint and separation")
            EzpzTextField(value = d.rotorDiameterM, onValueChange = { change(d.copy(rotorDiameterM = it)) }, label = "Rotor diameter (m)", enabled = editable, keyboardType = KeyboardType.Decimal)
            EzpzTextField(value = d.rotorTipClearanceM, onValueChange = { change(d.copy(rotorTipClearanceM = it)) }, label = "Tip clearance (m)", enabled = editable, keyboardType = KeyboardType.Decimal)
            Text(
                d.spacingM?.let { "Centre spacing works out to ${oneDecimal(it)} m. " }.orEmpty() + "Tandem rotors: use the overall fore-to-aft span as the diameter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LabelledDivider("Route planning defaults")
            EzpzTextField(value = d.defaultAirspeedKts, onValueChange = { change(d.copy(defaultAirspeedKts = it)) }, label = "Cruise airspeed (kt)", enabled = editable, keyboardType = KeyboardType.Decimal)
            Choice("Airspeed reference", AIRSPEED_LABELS.firstOrNull { it.second == d.defaultAirspeedType }?.first ?: d.defaultAirspeedType, AIRSPEED_LABELS) { change(d.copy(defaultAirspeedType = it)) }
            EzpzTextField(value = d.maxIndicatedKts, onValueChange = { change(d.copy(maxIndicatedKts = it)) }, label = "Max indicated (kt)", enabled = editable, keyboardType = KeyboardType.Decimal)
            Choice("Altitude reference", ALTITUDE_LABELS.firstOrNull { it.second == d.defaultAltitudeRef }?.first ?: d.defaultAltitudeRef, ALTITUDE_LABELS) { change(d.copy(defaultAltitudeRef = it)) }
            // A text keyboard, not a number pad: many number pads have no minus sign, and an altitude can be below sea level.
            EzpzTextField(value = d.defaultAltitudeFt, onValueChange = { change(d.copy(defaultAltitudeFt = it)) }, label = "Default altitude (ft)", enabled = editable)
            EzpzTextField(value = d.defaultFuelFlowLbHr, onValueChange = { change(d.copy(defaultFuelFlowLbHr = it)) }, label = "Fuel flow (lb/hr)", enabled = editable, keyboardType = KeyboardType.Decimal)
            EzpzTextField(
                value = d.defaultGrossWeightLb, onValueChange = { change(d.copy(defaultGrossWeightLb = it)) }, label = "Gross weight (lb)", enabled = editable,
                keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done, onImeAction = actions.save,
            )
            Text(
                "Exports built on this aircraft still open in AMPS as a UH-60L: an airframe can only come from files AMPS produced, which an administrator attaches to a standard profile.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(if (form.editing == null) "Create aircraft" else "Save changes", onClick = actions.save, busy = form.busy, busyText = "Saving…")
            SecondaryButton("Cancel", onClick = actions.cancel)
        }
    }
}
