package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.ThreatFileImport
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.SyncEngine

class ImportsActions(
    val toggleVisible: (String) -> Unit = {},
    val recolor: (uuid: String, color: String) -> Unit = { _, _ -> },
    /** Null when the rename is done, else the words for the field. */
    val rename: (uuid: String, name: String) -> String? = { _, _ -> null },
    val delete: (String) -> Unit = {},
    val removeFromSession: (String) -> Unit = {},
    val saveToLibrary: (String) -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val deselect: () -> Unit = {},
    val useForHeldRoutePoint: () -> Unit = {},
    val addToHeldRoute: () -> Unit = {},
    /** Opens a mission in the Routes panel; null when the account has no Routes panel to open it in. */
    val openMission: ((String) -> Unit)? = null,
    /** Shows the Threats panel. */
    val openThreats: () -> Unit = {},
)

/**
 * The Imports panel (docs/native-design: Imports) over [PointsViewModel]: what was brought in, by kind. Files come in through Import (the header's button, the
 * frame's chip) and the review, never from here, so a file means the same however it arrives.
 */
@Composable
fun ImportsHost(
    /** Whether the account has threats: its threat files are listed only then. */
    threatsAllowed: Boolean,
    onOpenMission: ((String) -> Unit)?,
    onOpenThreats: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PointsViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, threatsAllowed) { viewModel.allowThreats(threatsAllowed) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImportsContent(
        state,
        ImportsActions(
            toggleVisible = viewModel::toggleVisible, recolor = viewModel::recolor, rename = viewModel::rename, delete = viewModel::delete,
            removeFromSession = viewModel::removeFromSession, saveToLibrary = viewModel::saveToLibrary, resolve = viewModel::resolve, deselect = viewModel::deselect,
            useForHeldRoutePoint = viewModel::useForHeldRoutePoint, addToHeldRoute = viewModel::addToHeldRoute, openMission = onOpenMission, openThreats = onOpenThreats,
        ),
        modifier,
    )
}

/** The panel's header line: `5 items`, or that nothing has been brought in. */
fun importsLine(itemCount: Int): String = when (itemCount) {
    0 -> "Nothing imported yet"
    1 -> "1 item"
    else -> "$itemCount items"
}

/**
 * What the panel shows: where files come from; the point being held, if one is; the sets of local points (in the Library or this session), the missions kept with
 * their files, and the threat files brought in, each group with how many it holds.
 */
@Composable
fun ImportsContent(state: PointsUiState, actions: ImportsActions, modifier: Modifier = Modifier) {
    // Opening another panel leaves this one composed but out of sight: a field of it left focused (a rename) would keep the keyboard and take what is typed.
    val focus = LocalFocusManager.current
    val act = remember(actions, focus) {
        ImportsActions(
            toggleVisible = actions.toggleVisible, recolor = actions.recolor, rename = actions.rename, delete = actions.delete,
            removeFromSession = actions.removeFromSession, saveToLibrary = actions.saveToLibrary, resolve = actions.resolve, deselect = actions.deselect,
            useForHeldRoutePoint = actions.useForHeldRoutePoint, addToHeldRoute = actions.addToHeldRoute,
            openMission = actions.openMission?.let { open -> { uuid -> focus.clearFocus(); open(uuid) } },
            openThreats = { focus.clearFocus(); actions.openThreats() },
        )
    }
    Column(modifier.fillMaxWidth()) {
        WhereFilesComeFrom()
        state.held?.let { HeldPointCard(it, act, Modifier.padding(top = Tokens.Spacing.md.dp)) }

        GroupHeading(EzpzIcons.scatterPlot, "Local points", state.sets.size)
        if (state.sets.isEmpty()) {
            Empty("No local points yet. Import an AMPS .LPS file to see its points on the map and use them in routes.")
        }
        state.sets.forEach { key(it.uuid) { SetRow(it, act) } }

        GroupHeading(EzpzIcons.route, "Mission files", state.missions.size)
        if (state.missions.isEmpty()) Empty("No missions yet. An AMPS .msnx you import is kept with its file and opened in Routes.")
        state.missions.forEach { key(it.uuid) { MissionRow(it, act) } }

        if (state.threatFiles.isNotEmpty()) {
            GroupHeading(EzpzIcons.crisisAlert, "Threat files", state.threatFiles.size)
            state.threatFiles.forEach { ThreatFileRow(it, act) }
        }
    }
}

/** The design's card at the top: where files come from, and the kinds the app takes. */
@Composable
private fun WhereFilesComeFrom() {
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xs.dp),
    ) {
        Row(Modifier.padding(horizontal = Tokens.Spacing.md.dp, vertical = Tokens.Spacing.sm.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Icon(painterResource(EzpzIcons.info), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Open files in another app and choose EZ/PZ, or tap Import.", style = MaterialTheme.typography.bodyMedium)
                Text(".msnx · .LPS · .ths", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** A group's heading: its icon, its name and how many it holds. */
@Composable
private fun GroupHeading(icon: Int, text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = Tokens.Spacing.md.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Tokens.Spacing.sm.dp))
}

/**
 * What a row holds (`1,234 points`) and where it is kept, side by side as the design has them, the tag under the words when both do not fit (a narrow phone,
 * large text), so neither is cut.
 */
@Composable
private fun MetaLine(meta: String, tag: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.heightIn(min = 24.dp).wrapContentHeight())
        tag()
    }
}

/** Where something is kept, in a small tag: the Library (tinted, a bookmark), this session (outlined, a clock), this device only (grey, a lock). */
@Composable
private fun PlaceTag(icon: Int, text: String, tone: TagTone) {
    val (background, content) = when (tone) {
        TagTone.LIBRARY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        TagTone.SESSION -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        TagTone.DEVICE -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = background, contentColor = content,
        border = if (tone == TagTone.SESSION) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = Modifier.heightIn(min = 24.dp),                                       // grows with the person's text, never cuts it
    ) {
        Row(
            Modifier.heightIn(min = 24.dp).padding(start = 6.dp, top = 2.dp, end = Tokens.Spacing.sm.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp), tint = if (tone == TagTone.DEVICE) MaterialTheme.colorScheme.onSurfaceVariant else content)
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private enum class TagTone { LIBRARY, SESSION, DEVICE }

// -- A set of local points ----------------------------------------------------------------------------------------------------

private val COLOR_NAMES = mapOf(
    "#FF453A" to "red", "#0A84FF" to "blue", "#32D74B" to "green", "#FFD60A" to "yellow",
    "#BF5AF2" to "purple", "#FF9F0A" to "orange", "#64D2FF" to "light blue", "#FF375F" to "pink",
)

private fun colorName(hex: String) = COLOR_NAMES[hex.uppercase()] ?: hex

private fun colorOf(hex: String): Color = try { Color(hex.toColorInt()) } catch (_: IllegalArgumentException) { Color.Gray }

/**
 * One set (the design's row): its colour, name, how many points and where it is kept, then hide or show and the rest under *More* (rename, colour, save a session's
 * set to the Library, delete or remove). A conflict copy offers the three choices under it.
 */
@Composable
private fun SetRow(row: PointSetRowUi, actions: ImportsActions) {
    var choosingColor by rememberSaveable(row.uuid) { mutableStateOf(false) }
    var rename by rememberSaveable(row.uuid) { mutableStateOf<String?>(null) }
    var renameProblem by rememberSaveable(row.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(row.uuid) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    // The colour is said in words as well; a hidden set's mark is a ring, so it is not told only by colour.
                    Box(
                        Modifier.size(10.dp).border(if (row.visible) 5.dp else 2.dp, colorOf(row.color), CircleShape)
                            .semantics { contentDescription = "Drawn in ${colorName(row.color)}" + if (row.visible) "" else ", hidden" },
                    )
                    Text(
                        row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (row.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MetaLine(row.meta) {
                    when {
                        row.inSession -> PlaceTag(EzpzIcons.schedule, "This session", TagTone.SESSION)
                        row.conflictOf != null -> StateChip("Conflict copy", StatusTone.Error)
                        else -> PlaceTag(EzpzIcons.collectionsBookmark, "Library", TagTone.LIBRARY)
                    }
                }
            }
            IconButton(onClick = { actions.toggleVisible(row.uuid) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(if (row.visible) EzpzIcons.visibility else EzpzIcons.visibilityOff),
                    contentDescription = (if (row.visible) "Hide " else "Show ") + row.name,
                    tint = if (row.visible) EzpzTheme.status.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SetMenu(row, onRename = { rename = row.name; renameProblem = null }, onColour = { choosingColor = !choosingColor }, onDelete = { confirmDelete = true }, actions)
        }
        if (row.conflictOf != null) ConflictPanel("set of points") { actions.resolve(row.uuid, it) }
        if (choosingColor) ColorChoices(row, actions) { choosingColor = false }
        rename?.let { typed ->
            val save = { renameProblem = actions.rename(row.uuid, typed); if (renameProblem == null) rename = null }
            Column(Modifier.padding(bottom = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                EzpzTextField(
                    value = typed, onValueChange = { rename = it; renameProblem = null }, label = "Name", error = renameProblem,
                    capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, onImeAction = save,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Save name", onClick = save, style = PillStyle.Tonal)
                    TextAction("Cancel", onClick = { rename = null; renameProblem = null })
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${row.name}?",
            text = "Its points are removed from this device and, once it syncs, from your account and your other devices.",
            confirm = "Delete", onConfirm = { confirmDelete = false; actions.delete(row.uuid) }, onCancel = { confirmDelete = false },
        )
    }
}

/** A set's other actions: rename, colour, and, for a session's set, saving it to the Library or letting it go; for a saved one, deleting it (asked first). */
@Composable
private fun SetMenu(row: PointSetRowUi, onRename: () -> Unit, onColour: () -> Unit, onDelete: () -> Unit, actions: ImportsActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(EzpzIcons.moreVert), contentDescription = "More actions for ${row.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() }, leadingIcon = { Icon(painterResource(EzpzIcons.edit), contentDescription = null) })
            DropdownMenuItem(text = { Text("Colour") }, onClick = { open = false; onColour() }, leadingIcon = { Box(Modifier.size(18.dp).border(9.dp, colorOf(row.color), CircleShape)) })
            if (row.inSession) {
                DropdownMenuItem(
                    text = { MenuText("Save to Library", "Keeps it, and syncs it with your account") },
                    onClick = { open = false; actions.saveToLibrary(row.uuid) },
                    leadingIcon = { Icon(painterResource(EzpzIcons.collectionsBookmark), contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { MenuText("Remove from this session", "Nothing was saved, so nothing is deleted") },
                    onClick = { open = false; actions.removeFromSession(row.uuid) },
                    leadingIcon = { Icon(painterResource(EzpzIcons.close), contentDescription = null) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() },
                    leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

@Composable
private fun MenuText(title: String, supporting: String) {
    Column {
        Text(title)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColorChoices(row: PointSetRowUi, actions: ImportsActions, done: () -> Unit) {
    Column(Modifier.padding(bottom = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
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

// -- Missions and threat files ------------------------------------------------------------------------------------------------

/** A mission kept with its file: its name, how many routes, *Library*, and *Open in Routes*. */
@Composable
private fun MissionRow(mission: MissionFileUi, actions: ImportsActions) {
    val open = actions.openMission
    SideBySide(
        minFlexible = MIN_WORDS, gap = Tokens.Spacing.sm.dp, flexibleFirst = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Tokens.Spacing.sm.dp),
        fixed = { if (open != null) SmallTonal("Open in Routes", "Open ${mission.name} in Routes") { open(mission.uuid) } },
    ) {
        Column {
            Text(mission.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(mission.meta) {
                if (mission.conflictOf != null) StateChip("Conflict copy", StatusTone.Error) else PlaceTag(EzpzIcons.collectionsBookmark, "Library", TagTone.LIBRARY)
            }
        }
    }
}

/** A threat file brought in: its name, how many threats it held, *This device only*, and *Open in Threats*. */
@Composable
private fun ThreatFileRow(file: ThreatFileImport, actions: ImportsActions) {
    SideBySide(
        minFlexible = MIN_WORDS, gap = Tokens.Spacing.sm.dp, flexibleFirst = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Tokens.Spacing.sm.dp),
        fixed = { SmallTonal("Open in Threats", "Open Threats") { actions.openThreats() } },
    ) {
        Column {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (file.count == 1) "1 threat" else "${withCommas(file.count.toLong())} threats"} · listed under Threats",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.padding(top = Tokens.Spacing.xs.dp)) { PlaceTag(EzpzIcons.lock, "This device only", TagTone.DEVICE) }
        }
    }
}

/** The least a row's words are given beside its button, or a place's reason beside it: under this they go one under the other. */
internal val MIN_WORDS = 168.dp

/**
 * Two things side by side, [fixed] as wide as it needs and [flexible] in what is left ([flexibleFirst] puts it at the start), each centred on the other; or, when
 * what is left would be narrower than [minFlexible] (a narrow phone, large text), one under the other in that order, each at full width, so neither is squeezed
 * into a sliver or cut.
 */
@Composable
internal fun SideBySide(
    minFlexible: Dp,
    gap: Dp,
    flexibleFirst: Boolean,
    modifier: Modifier = Modifier,
    fixed: @Composable () -> Unit,
    flexible: @Composable () -> Unit,
) {
    Layout(contents = listOf(fixed, flexible), modifier = modifier) { (fixedOnes, flexibleOnes), constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val f = fixedOnes.firstOrNull()?.measure(loose)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val fixedWidth = f?.width ?: 0
        val room = width - fixedWidth - (if (f != null && fixedWidth > 0) gapPx else 0)
        if (f == null || fixedWidth == 0 || room >= minFlexible.roundToPx()) {
            val x = flexibleOnes.firstOrNull()?.measure(loose.copy(maxWidth = room.coerceAtLeast(0)))
            val height = maxOf(f?.height ?: 0, x?.height ?: 0, constraints.minHeight)
            val laidWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else (x?.width ?: 0) + fixedWidth + gapPx
            layout(laidWidth, height) {
                if (flexibleFirst) {
                    x?.place(0, (height - x.height) / 2)
                    f?.place(laidWidth - fixedWidth, (height - f.height) / 2)
                } else {
                    f?.place(0, (height - f.height) / 2)
                    x?.place(fixedWidth + gapPx, (height - x.height) / 2)
                }
            }
        } else {
            val x = flexibleOnes.firstOrNull()?.measure(loose)
            val first = if (flexibleFirst) x else f
            val second = if (flexibleFirst) f else x
            val height = maxOf((first?.height ?: 0) + gapPx + (second?.height ?: 0), constraints.minHeight)
            layout(constraints.maxWidth, height) {
                first?.place(0, 0)
                second?.place(0, (first?.height ?: 0) + gapPx)
            }
        }
    }
}

/** The design's small tonal button at a row's end (40 dp high, inside a 48 dp target). */
@Composable
private fun SmallTonal(text: String, description: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Tokens.Spacing.lg.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

// -- The held point -----------------------------------------------------------------------------------------------------------

/**
 * The local point held on the map: what the file said of it, and what can be done with it for the route being worked on (*Add to* its end, or *Use for* the held
 * route point). Opened from the held bar's options.
 */
@Composable
private fun HeldPointCard(held: HeldPointUi, actions: ImportsActions, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(held.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(held.setName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SmallIconButton(EzpzIcons.close, "Put ${held.title} down", actions.deselect)
            }
            Column(Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(held.grid, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                Text(held.latLon, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                held.elevation?.let { Text("Elevation $it", style = MaterialTheme.typography.bodyMedium) }
                if (held.group.isNotBlank()) Text("Group: ${held.group}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (held.description.isNotBlank()) Text(held.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                held.addTo?.let { route ->
                    PillButton(
                        "Add to $route", onClick = actions.addToHeldRoute, icon = EzpzIcons.add,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add this point to the end of $route" },
                    )
                }
                held.useFor?.let { point ->
                    PillButton(
                        "Use for $point", onClick = actions.useForHeldRoutePoint, style = PillStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Move the held route point onto this local point: $point" },
                    )
                }
                if (held.addTo == null) {
                    Text(
                        "Open a set of routes and choose a route to add this point to it, or choose one of its points to move that point here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
