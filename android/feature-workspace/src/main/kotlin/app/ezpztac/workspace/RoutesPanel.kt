package app.ezpztac.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.ezpztac.data.HandoffFormat
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.ListRow
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.SectionLabel
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens

// -- The sets open now -----------------------------------------------------------------------------------------------------------

/**
 * The route sets open now (the design's Routes): the one on the map as a card with its routes, the others "Also open", and the way to every set in the
 * Library. A conflict waiting for a choice is said at the top, because it is easy to miss on the list of everything.
 */
@Composable
fun RoutesHomeContent(state: RoutesUiState, actions: RoutesActions, modifier: Modifier = Modifier, threatCount: Int = 0) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        state.error?.takeIf { state.drawing == null }?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        val conflicts = state.sets.count { it.conflictOf != null }
        if (conflicts > 0) {
            Banner(
                if (conflicts == 1) "1 route set needs a choice: another device changed it too." else "$conflicts route sets need a choice: another device changed them too.",
                BannerKind.Warning, actionLabel = "Review", onAction = { actions.showPage(RoutesPage.LIBRARY) },
            )
        }
        state.open?.let { ActiveSetCard(it, state.drawing, state.exporting, state.includeThreats, threatCount, actions) }
        state.exportWarning?.let { Banner(it, BannerKind.Warning, actionLabel = "Dismiss", onAction = actions.dismissExportWarning) }
        if (state.alsoOpen.isNotEmpty()) {
            SectionLabel("Also open", Modifier.padding(top = Tokens.Spacing.sm.dp))
            state.alsoOpen.forEach { row ->
                ListRow(
                    if (row.isMission) EzpzIcons.description else EzpzIcons.route, row.name, emphasis = true, onClick = { actions.openSet(row.uuid) }, onClickLabel = "Open",
                    supporting = { SetLine(row) },
                )
            }
        }
        if (state.sets.isNotEmpty()) {
            ListRow(
                EzpzIcons.collectionsBookmark, "Every route set", onClick = { actions.showPage(RoutesPage.LIBRARY) }, onClickLabel = "Browse",
                supporting = { Muted(if (state.sets.size == 1) "1 in your Library" else "${state.sets.size} in your Library") },
                modifier = Modifier.padding(top = Tokens.Spacing.xs.dp),
            ) { Icon(painterResource(EzpzIcons.chevronRight), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (state.open == null && state.alsoOpen.isEmpty()) {
            Text(
                "No routes open. Sketch a route on the map, or import an AMPS mission file.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp),
            )
        }
    }
}

/** A set's second line in a list: how far it has reached the server, in its chip's colour, then what it holds. */
@Composable
private fun SetLine(row: RouteSetRow) {
    val chip = row.chips.lastOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (chip != null) {
            Text(chip.text, style = MaterialTheme.typography.bodyMedium, color = toneColor(chip.tone), maxLines = 1)
            Muted(" · ")
        }
        Text(
            (if (row.isMission) "AMPS mission · " else "") + row.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

/**
 * The set on the map (the design's card): its name with rename and more, its chips and what it holds, *Save* (which names it) or *Saved*, *Export .msnx*
 * with the threats beside it if wanted, and its routes. A delete is asked about first; a close is not (nothing is lost: the Library keeps it).
 */
@Composable
private fun ActiveSetCard(open: OpenSetUi, drawing: RouteDrawingUi?, exporting: Boolean, includeThreats: Boolean, threatCount: Int, actions: RoutesActions) {
    var renaming by rememberSaveable(open.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(open.uuid) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.xs.dp)) {
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(open.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SmallIconButton(EzpzIcons.edit, "Rename ${open.name}") { renaming = open.name }
                SetMoreMenu(open, actions, onDelete = { confirmDelete = true })
            }
            Row(Modifier.padding(top = Tokens.Spacing.xs.dp, end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                Chips(open.chips)
                Text(open.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.padding(top = 10.dp, end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                SaveSetButton(open, actions.save)
                PillButton(
                    if (exporting) "Building…" else "Export .msnx", onClick = { actions.exportSet(includeThreats && threatCount > 0) }, icon = EzpzIcons.uploadFile,
                    style = PillStyle.Outlined, enabled = !exporting && open.routes.isNotEmpty(),
                )
                SavesToLibrary(Modifier.weight(1f))
            }
            if (threatCount > 0) ThreatsBox(threatCount, includeThreats, actions.includeThreats)
            renaming?.let { name ->
                SetRenameField(name, open.name, onChange = { renaming = it }, onDone = { actions.renameSet(open.uuid, it); renaming = null }, onCancel = { renaming = null })
            }
            if (confirmDelete) SetDeleteConfirm(open.name, onDelete = { confirmDelete = false; actions.deleteSet(open.uuid) }, onKeep = { confirmDelete = false })
            if (open.conflictOf != null) {
                Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    ConflictPanel(if (open.isMission) "mission" else "set of routes") { actions.resolve(open.uuid, it) }
                }
            }
            when {
                drawing != null -> Note("Drawing a route: ${pointsText(drawing.points)}. Tap the map to add a point, then Finish. The buttons are over the map.")
                open.isMission -> Note("An AMPS mission. Moving, renaming and adding points and the plan are written back into its file; its routes themselves stay as they are.")
            }
            HorizontalDivider(Modifier.padding(top = 2.dp, end = Tokens.Spacing.sm.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (open.routes.isEmpty() && drawing == null) Note("No routes in this set yet. Sketch a route to start one.", bottom = true)
            open.routes.forEach { RouteRow(it, actions, fixed = open.isMission, exporting = exporting, withThreats = includeThreats && threatCount > 0) }
        }
    }
}

@Composable
private fun Note(text: String, bottom: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp, bottom = if (bottom) Tokens.Spacing.md.dp else 0.dp),
    )
}

/** *Save* while a set of sketched routes still has its made-up name (disabled until it has a route); *Saved* once it is named, and for an imported mission. */
@Composable
private fun SaveSetButton(open: OpenSetUi, save: () -> Unit) {
    if (!open.isMission && open.madeUpName) {
        PillButton("Save", onClick = save, icon = EzpzIcons.save, enabled = open.canSave)
    } else {
        PillButton("Saved", onClick = {}, icon = EzpzIcons.check, enabled = false)
    }
}

/** The design's box: the threat picture as a `.ths` beside an exported mission. Never saved: it is built on this device when the mission is shared. */
@Composable
private fun ThreatsBox(count: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).offset(x = (-15).dp).padding(top = 2.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Checkbox(checked = checked, onCheckedChange = null) }
        Column(Modifier.weight(1f)) {
            Text("Include threats ($count) as a .ths file", style = MaterialTheme.typography.bodyMedium)
            Text("Built on this device; never saved to your account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The open set's other actions: undo and redo its last change, close (it stays in the Library), and delete (asked about first, where it is asked). */
@Composable
private fun SetMoreMenu(open: OpenSetUi, actions: RoutesActions, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SmallIconButton(EzpzIcons.moreVert, "More actions for ${open.name}") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(text = { Text("Undo") }, onClick = { expanded = false; actions.undo() }, enabled = open.canUndo)
            DropdownMenuItem(text = { Text("Redo") }, onClick = { expanded = false; actions.redo() }, enabled = open.canRedo)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Close")
                        Text("Takes these routes out of this session", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = { expanded = false; actions.close(open.uuid) },
                leadingIcon = { Icon(painterResource(EzpzIcons.close), contentDescription = null) },
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; onDelete() }, leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null) })
        }
    }
}

/** Renaming a set: in capitals, as the web names them. A made-up name is not a name, and what is wrong is said at the field once *Save name* is pressed. */
@Composable
internal fun SetRenameField(name: String, was: String, onChange: (String) -> Unit, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var refused by rememberSaveable { mutableStateOf<String?>(null) }
    val done: () -> Unit = {
        val title = RouteSetNames.clean(name)
        refused = when {
            title.isEmpty() -> "Give it a name."
            title != RouteSetNames.clean(was) && RouteSetNames.isMadeUp(title) -> RouteSetNames.MADE_UP_REFUSED
            else -> null
        }
        when {
            refused != null -> {}
            title == RouteSetNames.clean(was) -> onCancel()                                      // nothing to change
            else -> onDone(title)
        }
    }
    Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        EzpzTextField(
            value = name, onValueChange = { refused = null; onChange(it) }, label = "Name", error = refused, imeAction = ImeAction.Done, onImeAction = done,
            capitalization = KeyboardCapitalization.Characters,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            TextAction("Save name", onClick = done)
            TextAction("Cancel", onClick = onCancel)
        }
    }
}

/** Deleting a set is asked about first; keeping it is the prominent answer, because a delete cannot be taken back. */
@Composable
private fun SetDeleteConfirm(name: String, onDelete: () -> Unit, onKeep: () -> Unit) {
    Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Banner("Delete \"$name\" and its routes? It is removed from this device and from your account.", BannerKind.Warning)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            PillButton("Keep it", onClick = onKeep, style = PillStyle.Tonal)
            TextAction("Delete", onClick = onDelete)
        }
    }
}

// -- A route of the open set ------------------------------------------------------------------------------------------------------

/**
 * One route (the design's row): its colour, name and totals, then share (GPX or FPL), hide, more and the way into its plan. The whole row opens the plan;
 * the buttons are their own targets. A route of an imported mission is part of its file: it is not renamed or removed here.
 */
@Composable
private fun RouteRow(route: RouteRowUi, actions: RoutesActions, fixed: Boolean, exporting: Boolean, withThreats: Boolean) {
    var rename by rememberSaveable(route.id) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(route.id) { mutableStateOf(false) }
    val dim = if (route.visible) Modifier else Modifier.alpha(0.6f)
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .background(if (route.selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(Tokens.Radius.sm.dp))
                .clickable(onClickLabel = "Open the plan", role = Role.Button) { actions.openPlan(route.id) }
                .semantics {
                    contentDescription = "${route.name}. ${route.meta}${if (route.visible) "" else ". Hidden"}"
                    selected = route.selected
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(dim.padding(end = Tokens.Spacing.md.dp).size(12.dp).background(routeColor(route.color), CircleShape))
            Column(dim.weight(1f)) {
                Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(route.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ShareMenu(route.name, enabled = !exporting) { actions.shareRoute(route.id, it) }
            RowIcon(
                if (route.visible) EzpzIcons.visibility else EzpzIcons.visibilityOff, if (route.visible) "Hide ${route.name}" else "Show ${route.name}",
            ) { actions.toggleVisible(route.id) }
            RouteMoreMenu(
                route.name, fixed, exporting,
                onRename = { rename = route.name }, onExport = { actions.exportRoute(route.id, withThreats) }, onDelete = { confirmDelete = true },
            )
            Icon(painterResource(EzpzIcons.chevronRight), contentDescription = null, modifier = Modifier.padding(horizontal = 12.dp))
        }
        rename?.let { name ->
            Column(Modifier.padding(end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                EzpzTextField(
                    value = name, onValueChange = { rename = it }, label = "Route name", imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Characters,
                    onImeAction = { actions.renameRoute(route.id, name); rename = null },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Save name", onClick = { actions.renameRoute(route.id, name); rename = null })
                    TextAction("Cancel", onClick = { rename = null })
                }
            }
        }
        if (confirmDelete) {
            Column(Modifier.padding(end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                Banner("Delete \"${route.name}\"? You can undo this until the set is closed.", BannerKind.Warning)
                // Keeping it is the prominent answer.
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    PillButton("Keep it", onClick = { confirmDelete = false }, style = PillStyle.Tonal)
                    TextAction("Delete", onClick = { confirmDelete = false; actions.deleteRoute(route.id) })
                }
            }
        }
    }
}

/** A route's colour, from its `#RRGGBB`, or grey for one that is not a colour. The colour is never the only thing that names a route. */
internal fun routeColor(hex: String): Color = runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)

/** A row's icon button: 48 dp, the icon at 22 as the design draws them. */
@Composable
internal fun RowIcon(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/**
 * Hands a route to another app (the design's share button, a menu): a GPX for ATAK, Garmin Pilot and most map apps, or a Garmin flight plan. Every point of
 * the route goes, shaping points too, so the path is the one flown. Nothing is sent by the app: the share sheet is the person's.
 */
@Composable
internal fun ShareMenu(name: String, enabled: Boolean, icon: @Composable (onClick: () -> Unit) -> Unit = { RowIcon(EzpzIcons.share, "Share $name as GPX or FPL", enabled, it) }, onShare: (HandoffFormat) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        icon { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(
                text = { MenuText("GPX", "ATAK, Garmin Pilot and most map apps") },
                onClick = { expanded = false; onShare(HandoffFormat.GPX) },
            )
            DropdownMenuItem(
                text = { MenuText("Garmin FPL", "A Garmin flight plan") },
                onClick = { expanded = false; onShare(HandoffFormat.FPL) },
            )
        }
    }
}

@Composable
private fun MenuText(title: String, detail: String) {
    Column {
        Text(title)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A route's other actions: rename and delete (not a mission's, whose routes are its file's), and an AMPS file of this route alone. */
@Composable
internal fun RouteMoreMenu(
    name: String,
    fixed: Boolean,
    exporting: Boolean,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    extra: @Composable (close: () -> Unit) -> Unit = {},
    icon: @Composable (onClick: () -> Unit) -> Unit = { RowIcon(EzpzIcons.moreVert, "More actions for $name", onClick = it) },
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        icon { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            extra { expanded = false }
            if (!fixed) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() }, leadingIcon = { Icon(painterResource(EzpzIcons.edit), contentDescription = null) })
            }
            DropdownMenuItem(
                text = { Text("Export this route as .msnx") }, onClick = { expanded = false; onExport() }, enabled = !exporting,
                leadingIcon = { Icon(painterResource(EzpzIcons.uploadFile), contentDescription = null) },
            )
            if (!fixed) {
                DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; onDelete() }, leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null) })
            }
        }
    }
}

// -- Every set ---------------------------------------------------------------------------------------------------------------------

/** Every set in the Library, to open, rename, delete, or settle a conflict: what the Library page will hold for all kinds (S7). */
@Composable
fun RoutesLibraryContent(state: RoutesUiState, actions: RoutesActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        if (state.sets.isEmpty()) Muted("No route sets yet. Sketch a route, or import an AMPS mission file.")
        state.sets.forEach { row -> LibrarySetCard(row, actions) }
        Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun LibrarySetCard(row: RouteSetRow, actions: RoutesActions) {
    var renaming by rememberSaveable(row.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(row.uuid) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.md.dp)) {
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (row.isOpen) {
                    Text("On the map", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = Tokens.Spacing.sm.dp))
                }
            }
            Row(Modifier.padding(top = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                Chips(row.chips)
                Text(row.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.conflictOf != null) {
                Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    ConflictPanel(if (row.isMission) "mission" else "set of routes") { actions.resolve(row.uuid, it) }
                }
            }
            renaming?.let { name ->
                SetRenameField(name, row.name, onChange = { renaming = it }, onDone = { actions.renameSet(row.uuid, it); renaming = null }, onCancel = { renaming = null })
            }
            if (confirmDelete) SetDeleteConfirm(row.name, onDelete = { confirmDelete = false; actions.deleteSet(row.uuid) }, onKeep = { confirmDelete = false })
            if (renaming == null && !confirmDelete) {
                Row(Modifier.padding(top = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    PillButton("Open", onClick = { actions.openSet(row.uuid) }, icon = EzpzIcons.map, style = PillStyle.Tonal, enabled = !row.isOpen)
                    TextButton(onClick = { renaming = row.name }, modifier = Modifier.semantics { contentDescription = "Rename ${row.name}" }) { Text("Rename") }
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.semantics { contentDescription = "Delete ${row.name}" }) { Text("Delete") }
                }
            }
        }
    }
}

// -- The foot ------------------------------------------------------------------------------------------------------------------------

/**
 * The panel's foot (the design's): *Sketch a route* and *Import .msnx*; while a route is being drawn, *Cancel* and *Finish* (the web's), which the toolbar over
 * the map also has. Pinned under the panel at half and full height.
 */
@Composable
fun RoutesFooter(state: RoutesUiState, actions: RoutesActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = Tokens.Spacing.lg.dp, top = 7.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.sm.dp),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            val drawing = state.drawing
            if (drawing != null) {
                PillButton("Cancel", onClick = actions.cancelDrawing, style = PillStyle.Outlined, modifier = Modifier.weight(1f))
                PillButton(
                    if (drawing.canFinish) "Finish route" else "Add two points", onClick = actions.finishDrawing, icon = EzpzIcons.check, enabled = drawing.canFinish,
                    modifier = Modifier.weight(1f),
                )
            } else {
                PillButton("Sketch a route", onClick = actions.startSketch, icon = EzpzIcons.draw, style = PillStyle.Tonal, modifier = Modifier.weight(1f))
                PillButton("Import .msnx", onClick = actions.importMission, icon = EzpzIcons.fileOpen, style = PillStyle.Outlined, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A set's line in a screen reader's words, used by the headers: `2 route sets in this session`. */
internal fun sessionLine(openCount: Int): String = when (openCount) {
    0 -> "Nothing open"
    1 -> "1 route set in this session"
    else -> "$openCount route sets in this session"
}
