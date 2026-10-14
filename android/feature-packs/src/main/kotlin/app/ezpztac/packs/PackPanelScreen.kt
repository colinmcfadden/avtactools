package app.ezpztac.packs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.Tokens

/** What the Pack panel does: show a view, open or close an item here, rename or remove it for everyone, filter the history. */
class PackPanelActions(
    val tab: (PackTab) -> Unit = {},
    val open: (String) -> Unit = {},
    val closeHere: (String) -> Unit = {},
    val rename: (uuid: String, name: String, result: (String?) -> Unit) -> Unit = { _, _, _ -> },
    val remove: (String) -> Unit = {},
    val person: (String?) -> Unit = {},
    val item: (String?) -> Unit = {},
    /** Members of the pack, its invite field taking the keyboard when [Boolean] (the owner's *Invite*). */
    val members: (invite: Boolean) -> Unit = {},
    /** Asks whether to update a copy from its Library original (the design's PackDialogs B); none: not offered. */
    val update: ((String) -> Unit)? = null,
    /** Asks what to call a copy of an item saved to this person's Library (D); none: not offered. */
    val saveCopy: ((String) -> Unit)? = null,
    /** Keeps in the Library this person's version of what the pack would not take. */
    val keepDropped: () -> Unit = {},
    /** Lets go of what pack [pack] would not take that [ids] names (those shown when asked). */
    val discardDropped: (pack: String, ids: Set<String>) -> Unit = { _, _ -> },
)

/** The panel's actions over its view model. */
fun actionsOf(
    viewModel: PackPanelViewModel,
    onMembers: (invite: Boolean) -> Unit = {},
    onUpdate: ((String) -> Unit)? = null,
    onSaveCopy: ((String) -> Unit)? = null,
) = PackPanelActions(
    tab = viewModel::selectTab, open = viewModel::open, closeHere = viewModel::closeHere, rename = viewModel::rename, remove = viewModel::remove,
    person = viewModel::filterPerson, item = viewModel::filterItem, members = onMembers, update = onUpdate, saveCopy = onSaveCopy,
    keepDropped = viewModel::keepDropped, discardDropped = viewModel::discardDropped,
)

/**
 * The Pack panel in the frame (the design's PackItems and PackHistory). [looking] is whether it is the panel showing and in sight (not lowered on
 * a phone, not collapsed on a tablet): while it is, and the app is in front, what is new is marked as seen after a moment, as on the web.
 */
@Composable
fun PackPanelHost(
    viewModel: PackPanelViewModel,
    looking: Boolean,
    onMembers: (invite: Boolean) -> Unit = {},
    onUpdate: ((String) -> Unit)? = null,
    onSaveCopy: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val inSight = looking && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(inSight) { viewModel.setLooking(inSight) }
    // The panel goes (the pack closed or gone): nobody is looking at what opens next until it is shown.
    DisposableEffect(viewModel) { onDispose { viewModel.setLooking(false) } }
    PackPanelContent(state, actionsOf(viewModel, onMembers, onUpdate, onSaveCopy))
}

/**
 * What the panel holds, in a column that does not scroll by itself (the frame's sheet or pane does): how the pack stands, what it is for, who is in
 * it, then its items by kind or its history. Renaming and removing are asked about in dialogs, as on the web.
 */
@Composable
fun PackPanelContent(state: PackPanelUi, actions: PackPanelActions, modifier: Modifier = Modifier) {
    var renaming by rememberSaveable(state.uuid) { mutableStateOf<String?>(null) }
    var removing by rememberSaveable(state.uuid) { mutableStateOf<String?>(null) }
    var discarding by rememberSaveable(state.uuid) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        if (!state.loaded) {
            Text(
                if (state.unreachable) "The pack could not be opened. Check the connection; it is tried again by itself." else "Opening the pack…",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        if (state.unreachable) Banner("The pack cannot be reached. Your changes stay here and are sent when it can.", BannerKind.Warning)
        if (state.dropped > 0) DroppedNotice(state, onKeep = actions.keepDropped, onDiscard = { discarding = true })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            if (state.finished) StateChip("Finished", StatusTone.Neutral, icon = EzpzIcons.lock) else StateChip("Active", StatusTone.Success)
            if (state.readOnly && !state.finished) StateChip("View only", StatusTone.Neutral, icon = EzpzIcons.visibility)
        }
        state.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // Who is in it, as a way to Members (the design's); its owner has an Invite of its own beside it, which opens Members at the invite field.
        // (Invite goes under the row where beside it the words would not fit on one line.)
        BesideOrUnder(
            Modifier.fillMaxWidth(),
            words = {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium).clickable(role = Role.Button) { actions.members(false) }
                        .clearAndSetSemantics { contentDescription = state.membersButton; role = Role.Button }.padding(vertical = Tokens.Spacing.xs.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                ) {
                    Faces(state.faces, Modifier, shown = 4, size = 28.dp, markHere = true)
                    Text(state.membersLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                }
            },
            buttons = { if (state.owner) PillButton("Invite", onClick = { actions.members(true) }, icon = EzpzIcons.personAdd, style = PillStyle.Outlined) },
            space = Tokens.Spacing.sm.dp, whole = true,
        )
        ViewChoice(state, actions)
        when (state.tab) {
            PackTab.ITEMS -> Items(state, actions, onRename = { renaming = it }, onRemove = { removing = it })
            PackTab.HISTORY -> History(state.history, actions)
        }
    }
    renaming?.let { uuid ->
        val item = state.groups.flatMap { it.items }.firstOrNull { it.uuid == uuid }
        if (item == null || !state.canEdit) {
            LaunchedEffect(uuid) { renaming = null }                                          // gone meanwhile, or no longer theirs to change
        } else {
            Dialog(onDismissRequest = { renaming = null }) {
                PackRenameContent(item.uuid, item.name, onRename = { name, refused -> actions.rename(uuid, name) { words -> if (words == null) renaming = null else refused(words) } }, onDismiss = { renaming = null })
            }
        }
    }
    removing?.let { uuid ->
        val item = state.groups.flatMap { it.items }.firstOrNull { it.uuid == uuid }
        if (item == null || !state.canEdit) {
            LaunchedEffect(uuid) { removing = null }
        } else {
            RemoveDialog(item, state.name, onRemove = { removing = null; actions.remove(uuid) }, onCancel = { removing = null })
        }
    }
    if (discarding) {
        val pack = state.uuid
        if (pack == null || state.dropped == 0 || state.keepingDropped) {
            LaunchedEffect(Unit) { discarding = false }                                       // kept or let go meanwhile: nothing left to ask about
        } else {
            // What was counted in the question is what goes: a drop that came since stays, to be offered on its own.
            DiscardDialog(state, onDiscard = { discarding = false; actions.discardDropped(pack, state.droppedIds.toSet()) }, onCancel = { discarding = false })
        }
    }
}

/**
 * Edits the pack would not take while it was open (PackPanel.jsx's notice): how many and why, then *Save my version to Library*, and, Android's own,
 * *Discard…* (asked first). Left alone, they are kept in the Library when the pack is left.
 */
@Composable
private fun DroppedNotice(state: PackPanelUi, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val (what, rest) = PackPanelWords.dropped(state.dropped, state.droppedReasons)
    val tint = EzpzTheme.status.warning
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tint.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.30f)),
    ) {
        Row(
            Modifier.padding(start = Tokens.Spacing.md.dp, end = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.md.dp),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
        ) {
            Icon(painterResource(EzpzIcons.warning), contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(Tokens.Size.iconSmall.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(what) }
                        append(" ")
                        append(rest)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                    // Its words take a second line rather than be cut (a narrow phone, large text).
                    FilledTonalButton(
                        onClick = onKeep, enabled = !state.keepingDropped, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Icon(painterResource(EzpzIcons.save), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Tokens.Spacing.sm.dp))
                        Text(if (state.keepingDropped) "Saving…" else "Save my version to Library", maxLines = 2, textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = onDiscard, enabled = !state.keepingDropped, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard…") }
                }
            }
        }
    }
}

/** Asks before letting go of what the pack would not take: it is not kept anywhere once discarded. */
@Composable
private fun DiscardDialog(state: PackPanelUi, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (state.dropped == 1) "Discard your change?" else "Discard your ${state.dropped} changes?") },
        text = { Text(discardText(state.dropped, state.name)) },
        confirmButton = { TextButton(onClick = onDiscard, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** What discarding the edits a pack would not take does. */
internal fun discardText(count: Int, pack: String): String =
    (if (count == 1) "$pack did not take it, and it" else "$pack did not take them, and they") + " will not be kept in your Library. This cannot be undone."


/** Items or History (the design's segmented control); History carries a dot while something changed since this person looked waits there. */
@Composable
private fun ViewChoice(state: PackPanelUi, actions: PackPanelActions) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PackTab.entries.forEachIndexed { index, tab ->
            val selected = state.tab == tab
            val dot = tab == PackTab.HISTORY && state.historyHasNew
            SegmentedButton(
                selected = selected,
                onClick = { actions.tab(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, PackTab.entries.size),
                modifier = if (dot) Modifier.semantics { contentDescription = "History, new changes" } else Modifier,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (tab == PackTab.ITEMS) "Items" else "History", maxLines = 1)
                        if (dot) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                    }
                },
            )
        }
    }
}

@Composable
private fun Items(state: PackPanelUi, actions: PackPanelActions, onRename: (String) -> Unit, onRemove: (String) -> Unit) {
    state.emptyItems?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    // Keyed, so what a row holds (its open menu) stays with its item when the list changes under it.
    state.groups.forEach { group ->
        key(group.kind) {
            Column {
                Row(Modifier.heightIn(min = 32.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                    Text(group.title, style = MaterialTheme.typography.labelLarge, color = EzpzTheme.status.accent)
                    Text("  ${group.items.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                group.items.forEach { item -> key(item.uuid) { ItemRow(item, state.canEdit, actions, onRename, onRemove) } }
            }
        }
    }
}

/**
 * One item (the design's row): its kind, name and a dot when someone else changed it since this person looked, who changed it last and when, where it
 * came from and who else has it open; a tap shows it (opens it in its editor), and the menu has the rest. One button for a screen reader.
 */
@Composable
private fun ItemRow(item: PackItemUi, canEdit: Boolean, actions: PackPanelActions, onRename: (String) -> Unit, onRemove: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // The design's *Update* beside the row, for whoever copied it in, while the original has changed and the pack can be changed; under the row's
    // words where beside them it would leave the words less than [ROW_WORDS_MIN] (a narrow phone, large text), rather than cut the name and its chips.
    val update = actions.update?.takeIf { item.original == "changed" && canEdit && !item.sending }
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(if (item.openHere) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(Tokens.Radius.md.dp))
            .padding(end = 4.dp),
    ) {
        val updateWidth = if (update == null) 0.dp else with(density) { measurer.measure(UPDATE, label).size.width.toDp() } + 24.dp
        val beside = maxWidth - ROW_LEAD - updateWidth - 48.dp >= ROW_WORDS_MIN
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 60.dp)
                        .clickable(role = Role.Button, onClickLabel = if (item.canOpen) "Show on the map" else null) { actions.open(item.uuid) }
                        .semantics(mergeDescendants = true) { contentDescription = item.description; selected = item.openHere }
                        .padding(start = Tokens.Spacing.md.dp, top = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.sm.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
                ) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(kindIcon(item.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (item.openHere) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (item.isNew) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Ringed in the row's own colour, so the ring parts it from its neighbours and draws no outline round it.
                            item.who?.let { PersonFace(it, 20.dp, ring = if (item.openHere) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) }
                            Text(item.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.fromLibrary || item.editing.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                                item.editing.forEach { StateChip(PackPanelWords.hasItOpen(listOf(it)), StatusTone.Neutral, icon = EzpzIcons.visibility) }
                                if (item.fromLibrary) StateChip("From Library", StatusTone.Neutral, icon = EzpzIcons.collectionsBookmark)
                                when (item.original) {
                                    "changed" -> StateChip("Original changed", StatusTone.Warning, icon = EzpzIcons.update)
                                    "deleted" -> StateChip("Original deleted", StatusTone.Neutral)
                                }
                            }
                        }
                    }
                }
                if (beside) update?.let { ask -> UpdateButton(item.name) { ask(item.uuid) } }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.moreVert), contentDescription = "More actions for ${item.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        if (item.openHere) {
                            DropdownMenuItem(
                                text = { Column { Text("Close here"); Text("It stays in the pack", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                leadingIcon = { Icon(painterResource(EzpzIcons.close), contentDescription = null) },
                                onClick = { menu = false; actions.closeHere(item.uuid) },
                            )
                        } else if (item.canOpen) {
                            DropdownMenuItem(
                                text = { Text("Open") }, leadingIcon = { Icon(painterResource(EzpzIcons.visibility), contentDescription = null) },
                                onClick = { menu = false; actions.open(item.uuid) },
                            )
                        }
                        update?.let { ask ->
                            DropdownMenuItem(
                                text = { Text("Update from original…") }, leadingIcon = { Icon(painterResource(EzpzIcons.refresh), contentDescription = null) },
                                onClick = { menu = false; ask(item.uuid) },
                            )
                        }
                        // Anyone in the pack keeps a copy of their own, a finished pack's too.
                        actions.saveCopy?.let { save ->
                            DropdownMenuItem(
                                text = { Text("Save a copy to Library…") }, leadingIcon = { Icon(painterResource(EzpzIcons.contentCopy), contentDescription = null) },
                                onClick = { menu = false; save(item.uuid) },
                            )
                        }
                        if (canEdit && !item.sending) {
                            DropdownMenuItem(
                                text = { Text("Rename") }, leadingIcon = { Icon(painterResource(EzpzIcons.edit), contentDescription = null) },
                                onClick = { menu = false; onRename(item.uuid) },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove from pack", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onRemove(item.uuid) },
                            )
                        }
                        if (!item.openHere && !item.canOpen && !(canEdit && !item.sending) && actions.saveCopy == null) {
                            DropdownMenuItem(text = { Text(PackPanelViewModel.POINT_SETS_LATER) }, onClick = { menu = false }, enabled = false)
                        }
                    }
                }
            }
            if (!beside) update?.let { ask -> UpdateButton(item.name, Modifier.padding(start = ROW_LEAD - 12.dp)) { ask(item.uuid) } }
        }
    }
}

/** A copy's *Update*, named for a screen reader by what it updates. */
@Composable
private fun UpdateButton(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "Update $name from the changed original" }) { Text(UPDATE) }
}

private const val UPDATE = "Update"

// What comes before an item's words (the padding and the kind's icon, and the space after it), and the least the words keep beside *Update*.
private val ROW_LEAD = (Tokens.Spacing.md + 40 + Tokens.Spacing.lg).dp
private val ROW_WORDS_MIN = 168.dp

private fun kindIcon(kind: String): Int = when (kind) {
    "lz" -> EzpzIcons.flightLand
    "route" -> EzpzIcons.route
    "pointset" -> EzpzIcons.scatterPlot
    else -> EzpzIcons.description
}

/** The history: whose and which filters, then each heading's events, newest first, or why there are none. */
@Composable
private fun History(history: HistoryUi, actions: PackPanelActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        FilterMenu(EzpzIcons.group, "Filter by person", history.person, history.people, actions.person)
        FilterMenu(EzpzIcons.filterList, "Filter by item", history.item, history.items, actions.item)
    }
    history.emptyText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    history.failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = EzpzTheme.status.warning) }
    history.groups.forEach { group ->
        key(group.title) { HistoryGroup(group) }
    }
}

/** One heading of the history and its events, newest first: what is new since this person looked marked with the pack's dot. */
@Composable
private fun HistoryGroup(group: EventGroupUi) {
    Column {
        Row(Modifier.heightIn(min = 36.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (group.fresh) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
            Text(
                group.title, style = MaterialTheme.typography.labelLarge,
                color = if (group.fresh) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        group.events.forEach { event -> key(event.seq) { EventRow(event) } }
    }
}

/** A filter of the history: a chip that opens its choices ([label] for a screen reader: "Filter by person: Everyone"). */
@Composable
private fun FilterMenu(icon: Int, label: String, chosen: FilterChoice, choices: List<FilterChoice>, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true }, shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            // A drop-down list, named for what it filters and what it keeps; Material makes its touch target 48 dp.
            modifier = Modifier.semantics { contentDescription = "$label: ${chosen.label}"; role = Role.DropdownList },
        ) {
            Row(Modifier.heightIn(min = 32.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(18.dp))
                Text(chosen.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (choice.key == chosen.key) ({ Icon(painterResource(EzpzIcons.check), contentDescription = "Chosen") }) else null,
                    onClick = { open = false; onChoose(choice.key) },
                )
            }
        }
    }
}

/** One event: who (a face, or the pack's mark for its own events), what in a sentence, and when and where; a skipped edit stands out. */
@Composable
private fun EventRow(event: EventUi) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
    ) {
        // The face says nothing the sentence does not: a screen reader skips it.
        Box(Modifier.clearAndSetSemantics { }) {
            val who = event.who
            if (who != null) {
                PersonFace(who, 40.dp)
            } else {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (event.skipped) EzpzIcons.syncProblem else EzpzIcons.layers), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    if (event.lead.isNotEmpty()) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (event.skipped) EzpzTheme.status.warning else Color.Unspecified)) { append(event.lead) }
                    }
                    append(event.rest)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(event.meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (event.fresh) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape).semantics { contentDescription = "New since you looked" })
        }
    }
}

/**
 * Renaming an item for everyone (the web's rename dialog: "Everyone in the pack sees the new name."), apart from its window so it can be tried on its
 * own. [onRename] is told the name and how to say it will not do.
 */
@Composable
internal fun PackRenameContent(uuid: String, name: String, onRename: (name: String, refused: (String) -> Unit) -> Unit, onDismiss: () -> Unit) {
    // Kept for the item, not its name: someone else renaming it meanwhile does not wipe what is typed (the web takes the name once too).
    var field by rememberSaveable(uuid, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    var refusal by rememberSaveable(uuid) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { onRename(field.text) { refusal = it } }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semantics { paneTitle = "Rename “$name”" }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text("Rename “$name”", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = field,
                onValueChange = { if (it.text.length <= MAX_TYPED || it.text.length < field.text.length) { field = it; refusal = null } },
                label = { Text("Name") }, singleLine = true, isError = refusal != null,
                supportingText = { Text(refusal ?: "Everyone in the pack sees the new name.") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), shape = RoundedCornerShape(Tokens.Radius.sm.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = submit) { Text("Rename") }
            }
        }
    }
}

/** Removing an item for everyone, asked first (the web's words); both choices plain, the destructive one in the error colour. */
@Composable
private fun RemoveDialog(item: PackItemUi, pack: String, onRemove: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Remove “${item.name}” from $pack?") },
        text = { Text(removeText(item.kind)) },
        confirmButton = { TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** What removing an item does, in the web's words. */
internal fun removeText(kind: String): String =
    "The ${PackPanelWords.kindWords(kind)} goes for everyone in the pack. The history keeps what it was, and anyone can save a copy of it first."

// What the rename field takes: a name too long is refused in words, but one past this cannot be typed at all.
private const val MAX_TYPED = 200
