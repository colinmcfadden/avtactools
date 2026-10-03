package app.ezpztac.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PointsActions(
    val importFile: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val dismissNote: () -> Unit = {},
    val toggleVisible: (String) -> Unit = {},
    val recolor: (uuid: String, color: String) -> Unit = { _, _ -> },
    /** Null when the rename is done, else the words for the field. */
    val rename: (uuid: String, name: String) -> String? = { _, _ -> null },
    val delete: (String) -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val deselect: () -> Unit = {},
    val useForHeldRoutePoint: () -> Unit = {},
    val addToHeldRoute: () -> Unit = {},
)

/**
 * The Points part of the sheet: the saved sets of local points, importing an `.LPS`, and the point being held. The file is chosen in the system's own
 * picker, so the app asks for no storage permission and the person can choose from Files, a download or a mail attachment.
 */
@Composable
fun PointsHost(modifier: Modifier = Modifier, viewModel: PointsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult                 // the person backed out
        scope.launch {
            when (val picked = withContext(Dispatchers.IO) { PickedFile.read(resolver, uri) }) {
                is PickedFile.Result.Read -> viewModel.importFile(picked.bytes, picked.name)
                is PickedFile.Result.Failed -> viewModel.importFailed(picked.message)
            }
        }
    }
    PointsContent(
        state,
        PointsActions(
            importFile = { picker.launch(arrayOf("*/*")) },                           // an .LPS has no media type of its own; the reader says what a file is
            dismissError = viewModel::dismissError, dismissNote = viewModel::dismissNote, toggleVisible = viewModel::toggleVisible, recolor = viewModel::recolor,
            rename = viewModel::rename, delete = viewModel::delete, resolve = viewModel::resolve, deselect = viewModel::deselect,
            useForHeldRoutePoint = viewModel::useForHeldRoutePoint, addToHeldRoute = viewModel::addToHeldRoute,
        ),
        modifier,
    )
}

@Composable
fun PointsContent(state: PointsUiState, actions: PointsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Local points", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (state.importing) Text("Reading…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TextAction("Import .LPS", onClick = actions.importFile)
        }
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        state.note?.let { Banner(it, BannerKind.Info, actionLabel = "Dismiss", onAction = actions.dismissNote) }
        state.held?.let { HeldPointCard(it, actions) }
        if (state.sets.isEmpty()) {
            Text(
                "No local points yet. Import an AMPS .LPS file, from Files, a download or an email, to see its points on the map and use them in routes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.sets.forEach { SetCard(it, actions) }
    }
}

// -- The held point -----------------------------------------------------------------------------------------------

@Composable
private fun HeldPointCard(held: HeldPointUi, actions: PointsActions) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Held", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(held.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(held.setName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextAction("Done", onClick = actions.deselect)
            }
            Text(held.grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurface)
            Text(held.latLon, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            held.elevation?.let { Text("Elevation $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            if (held.group.isNotBlank()) Text("Group: ${held.group}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (held.description.isNotBlank()) Text(held.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            held.addTo?.let { PrimaryButton("Add to ${it}", onClick = actions.addToHeldRoute, modifier = Modifier.semantics { contentDescription = "Add this point to the end of $it" }) }
            held.useFor?.let { SecondaryButton("Use for $it", onClick = actions.useForHeldRoutePoint, modifier = Modifier.semantics { contentDescription = "Move the held route point onto this local point: $it" }) }
            if (held.addTo == null) {
                Text(
                    "Open a set of routes and choose a route to add this point to it, or choose one of its points to move that point here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -- A set -------------------------------------------------------------------------------------------------------------

private val COLOR_NAMES = mapOf(
    "#FF453A" to "red", "#0A84FF" to "blue", "#32D74B" to "green", "#FFD60A" to "yellow",
    "#BF5AF2" to "purple", "#FF9F0A" to "orange", "#64D2FF" to "light blue", "#FF375F" to "pink",
)

private fun colorName(hex: String) = COLOR_NAMES[hex.uppercase()] ?: hex

private fun colorOf(hex: String): Color = try { Color(hex.toColorInt()) } catch (_: IllegalArgumentException) { Color.Gray }

@Composable
private fun SetCard(row: PointSetRowUi, actions: PointsActions) {
    var choosingColor by rememberSaveable(row.uuid) { mutableStateOf(false) }
    var rename by rememberSaveable(row.uuid) { mutableStateOf<String?>(null) }
    var renameProblem by rememberSaveable(row.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(row.uuid) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                // The colour is said in words as well: it is a swatch, and a set that is hidden is drawn faint, not only by colour.
                Surface(
                    shape = CircleShape, color = colorOf(row.color), modifier = Modifier.size(16.dp).semantics { contentDescription = "Drawn in ${colorName(row.color)}" },
                ) {}
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (row.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        (if (row.pointCount == 1) "1 point" else "${row.pointCount} points") + if (row.visible) "" else " · hidden",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SyncChip(row.sync, row.conflictOf)
            }
            if (row.conflictOf != null) ConflictPanel("set of points") { actions.resolve(row.uuid, it) }

            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction(if (row.visible) "Hide" else "Show", onClick = { actions.toggleVisible(row.uuid) }, modifier = Modifier.semantics { contentDescription = (if (row.visible) "Hide " else "Show ") + row.name + " on the map" })
                TextAction("Colour", onClick = { choosingColor = !choosingColor })
                TextAction("Rename", onClick = { rename = row.name; renameProblem = null })
                TextAction("Delete", onClick = { confirmDelete = true })
            }
            if (choosingColor) ColorChoices(row, actions) { choosingColor = false }
            if (rename != null) {
                EzpzTextField(
                    value = rename.orEmpty(), onValueChange = { rename = it; renameProblem = null }, label = "Name", error = renameProblem,
                    imeAction = ImeAction.Done, onImeAction = { renameProblem = actions.rename(row.uuid, rename.orEmpty()); if (renameProblem == null) rename = null },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    PrimaryButton("Save name", onClick = { renameProblem = actions.rename(row.uuid, rename.orEmpty()); if (renameProblem == null) rename = null }, modifier = Modifier.weight(1f))
                    TextAction("Cancel", onClick = { rename = null; renameProblem = null })
                }
            }
            if (confirmDelete) {
                Banner("Delete ${row.name}? Its points are removed from this device and, once it syncs, from your other devices.", BannerKind.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    SecondaryButton("Delete set", onClick = { actions.delete(row.uuid); confirmDelete = false }, modifier = Modifier.weight(1f))
                    PrimaryButton("Keep it", onClick = { confirmDelete = false }, modifier = Modifier.weight(1f))   // the safe answer is the prominent one
                }
            }
        }
    }
}

@Composable
private fun ColorChoices(row: PointSetRowUi, actions: PointsActions, done: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            RouteColors.PALETTE.take(4).forEach { SwatchButton(it, row.color == it) { actions.recolor(row.uuid, it); done() } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            RouteColors.PALETTE.drop(4).forEach { SwatchButton(it, row.color == it) { actions.recolor(row.uuid, it); done() } }
        }
    }
}

/** A colour to choose: at least a finger's size, with a ring where it is the one in use and its name for a screen reader. */
@Composable
private fun SwatchButton(hex: String, chosen: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape, color = colorOf(hex),
        border = BorderStroke(if (chosen) 4.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(Tokens.Size.touchTarget.dp).clickable(onClick = onClick).semantics {
            contentDescription = "Colour ${colorName(hex)}"
            selected = chosen
        },
    ) {}
}
