package app.ezpztac.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import app.ezpztac.sync.SyncEngine

/** What the Library's controls do. Opening, renaming, deleting and settling a conflict go to the panel that owns the item; the rest is the Library's own. */
class LibraryActions(
    val close: () -> Unit = {},
    val showTab: (LibraryTab) -> Unit = {},
    val search: (String) -> Unit = {},
    val sortBy: (LibrarySort) -> Unit = {},
    val open: (LibraryRowUi) -> Unit = {},
    /** [replacing] is the item that has the name, when the person chose to replace it. */
    val rename: (row: LibraryRowUi, name: String, replacing: String?) -> Unit = { _, _, _ -> },
    val duplicate: (LibraryRowUi) -> Unit = {},
    val delete: (LibraryRowUi) -> Unit = {},
    val resolve: (LibraryRowUi, SyncEngine.Resolution) -> Unit = { _, _ -> },
)

/**
 * The Library (docs/native-design: Library), a page over the frame while [tab] is not null, opened on that tab. It keeps the map and the panels composed
 * under it, as the route's plan does. Back and its close button shut it, and an item opened shuts it too, as the web's does, showing the panel the item is in.
 */
@Composable
fun LibraryHost(
    tab: LibraryTab?,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    /** The tabs this account has (those of the panels it is offered). */
    tabs: List<LibraryTab> = LibraryTab.entries,
    snackbars: SnackbarHostState? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Each opening starts fresh; a turn of the phone (the same opening, saved with the screen) keeps the search and the order.
    var shownAt by rememberSaveable { mutableStateOf<LibraryTab?>(null) }
    LaunchedEffect(tab) {
        if (tab == null) {
            shownAt = null
        } else {
            if (shownAt == null) viewModel.open(tab) else viewModel.showTab(tab)
            shownAt = tab
        }
    }
    val toasts by rememberUpdatedState(snackbars)
    LaunchedEffect(viewModel) { viewModel.notices.collect { toasts?.showSnackbar(it, withDismissAction = true) } }
    if (tab == null) return
    BackHandler(onBack = actions.close)
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus() }                                             // a field left focused under it keeps no keyboard up
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    LibraryPage(
        state.copy(tabs = tabs),
        LibraryActions(
            close = actions.close, showTab = viewModel::showTab, search = viewModel::search, sortBy = viewModel::sortBy, open = actions.open,
            rename = actions.rename, duplicate = viewModel::duplicate, delete = actions.delete, resolve = actions.resolve,
        ),
        modifier, snackbars, renaming = renaming, onRenaming = { renaming = it },
    )
}

private val LibraryTab.icon: Int
    get() = when (this) {
        LibraryTab.LZ -> EzpzIcons.flightLand
        LibraryTab.ROUTES -> EzpzIcons.route
        LibraryTab.POINTS -> EzpzIcons.scatterPlot
    }

/** What an item already shown says (`Open in session`), and what a set of points says, since opening one is showing it on the map. */
private fun inSessionWords(tab: LibraryTab) = if (tab == LibraryTab.POINTS) "On the map" else "Open in session"

/** The page itself: its bar, the tabs with their counts, the search and the sort, the items, and how many there are. */
@Composable
fun LibraryPage(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    /** The item being renamed (its dialog is up), held by the host. */
    renaming: String? = null,
    onRenaming: (String?) -> Unit = {},
) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(modifier.fillMaxSize().semantics { paneTitle = "Library" }, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Tokens.Spacing.xs.dp, end = Tokens.Spacing.lg.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) { Icon(painterResource(EzpzIcons.close), contentDescription = "Close Library") }
                    Text("Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp).semantics { heading() })
                }
                Text(
                    "Everything you have saved. Opening one adds it to this session.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.md.dp),
                )
                if (state.tabs.size > 1) Tabs(state, actions)
                SearchField(state, actions)
                SortChooser(state.sort, actions.sortBy)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                    items(state.rows, key = { it.uuid }) { row ->
                        LibraryRow(row, actions, busy = state.busy, onRename = { onRenaming(row.uuid) }, onDelete = { deleting = row.uuid })
                    }
                    if (state.rows.isEmpty()) item { Empty(state) }
                }
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        state.total, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.lg.dp),
                    )
                }
            }
            snackbars?.let { ToastHost(it, Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Tokens.Spacing.lg.dp, vertical = 64.dp)) }
        }
    }
    renaming?.let { uuid ->
        val row = state.rows.firstOrNull { it.uuid == uuid }
        if (row == null) {
            LaunchedEffect(uuid) { onRenaming(null) }                                         // gone meanwhile: nothing to rename
        } else {
            Dialog(onDismissRequest = { onRenaming(null) }) {
                LibraryRenameContent(
                    row, others = state.names[row.tab].orEmpty().filter { it.first != row.uuid },
                    onRename = { name, replacing -> actions.rename(row, name, replacing); onRenaming(null) }, onDismiss = { onRenaming(null) },
                )
            }
        }
    }
    deleting?.let { uuid ->
        val row = state.rows.firstOrNull { it.uuid == uuid }
        if (row == null) {
            deleting = null
        } else {
            ConfirmDialog(
                title = "Delete “${row.name}”?", text = "This removes it from your Library on every device you use.", confirm = "Delete",
                onConfirm = { deleting = null; actions.delete(row) }, onCancel = { deleting = null },
            )
        }
    }
}

/** The three tabs, each with how many it holds. */
@Composable
private fun Tabs(state: LibraryUiState, actions: LibraryActions) {
    val accent = EzpzTheme.status.accent
    PrimaryTabRow(
        selectedTabIndex = state.tabs.indexOf(state.tab).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.background, contentColor = accent,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
    ) {
        state.tabs.forEach { tab ->
            val chosen = tab == state.tab
            val count = state.counts[tab] ?: 0
            Tab(
                selected = chosen, onClick = { actions.showTab(tab) }, selectedContentColor = accent, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${tab.label}, $count" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The count is measured first, so a long label at large text takes a second line rather than pushing the count off.
                    Text(
                        tab.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium), maxLines = 2,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text("$count", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

/** The search, a pill as the design draws it: by name, and an LZ/PZ by its grid too. */
@Composable
private fun SearchField(state: LibraryUiState, actions: LibraryActions) {
    val focus = LocalFocusManager.current
    TextField(
        value = state.query, onValueChange = actions.search, singleLine = true,
        placeholder = { Text(state.tab.search, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(EzpzIcons.search), contentDescription = null) },
        trailingIcon = if (state.query.isNotEmpty()) {
            { IconButton(onClick = { actions.search("") }) { Icon(painterResource(EzpzIcons.close), contentDescription = "Clear search") } }
        } else {
            null
        },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp),
    )
}

/** The order, as a chip that opens its two choices. */
@Composable
private fun SortChooser(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.xs.dp)) {
        Surface(
            shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.heightIn(min = 48.dp).padding(vertical = Tokens.Spacing.sm.dp)
                .clickable(role = Role.Button, onClickLabel = "Change sort") { open = true }
                .semantics(mergeDescendants = true) { contentDescription = "Sort: ${sort.label}. Change sort" },
        ) {
            Row(Modifier.padding(start = Tokens.Spacing.sm.dp, end = Tokens.Spacing.xs.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                Icon(painterResource(EzpzIcons.sort), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(18.dp))
                Text(sort.label, style = MaterialTheme.typography.labelLarge)
                Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            LibrarySort.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) }, onClick = { open = false; onSort(choice) },
                    leadingIcon = if (choice == sort) ({ Icon(painterResource(EzpzIcons.check), contentDescription = null) }) else null,
                )
            }
        }
    }
}

/** One item: its badge, name, what it is, when it changed, whether it is open, then *Open* and its other actions; a conflict copy's three choices under it. */
@Composable
private fun LibraryRow(row: LibraryRowUi, actions: LibraryActions, busy: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // The words and the actions side by side, the actions under the words on a narrow phone at large text: a grid is never split over two lines.
        SideBySide(
            minFlexible = 240.dp, gap = Tokens.Spacing.xs.dp, flexibleFirst = true, stackedEnd = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.xs.dp, bottom = Tokens.Spacing.md.dp),
            fixed = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { actions.open(row) }, enabled = !row.inSession && row.conflictOf == null,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = if (row.inSession) "Open ${row.name}, already ${inSessionWords(row.tab).lowercase()}" else "Open ${row.name}"
                        },
                    ) { Text("Open") }
                    RowMenu(row, busy, onRename, onDelete, actions)
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(row.tab.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    row.grid?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    row.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    row.updated?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    when {
                        row.conflictOf != null -> StateChip("Conflict copy", StatusTone.Error, Modifier.padding(top = 6.dp))
                        row.inSession -> InSessionTag(inSessionWords(row.tab))
                    }
                }
            }
        }
        if (row.conflictOf != null) {
            // The panel is several things one under the other (its notice and the three choices): a column, never a box, which would stack them.
            Column(Modifier.padding(start = 72.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                ConflictPanel(noun(row)) { actions.resolve(row, it) }
            }
        }
        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

private fun noun(row: LibraryRowUi) = when (row.tab) {
    LibraryTab.LZ -> "LZ/PZ"
    LibraryTab.ROUTES -> if (row.isMission) "mission" else "set of routes"
    LibraryTab.POINTS -> "set of points"
}

@Composable
private fun InSessionTag(words: String) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(top = 6.dp).heightIn(min = 24.dp),
    ) {
        Row(Modifier.padding(start = Tokens.Spacing.xs.dp, top = 2.dp, end = Tokens.Spacing.sm.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            Icon(painterResource(EzpzIcons.check), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(words, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** An item's other actions: rename, duplicate, delete (asked about first). *Add to Mission Pack…* joins them with the pack dialogs (P4). */
@Composable
private fun RowMenu(row: LibraryRowUi, busy: Boolean, onRename: () -> Unit, onDelete: () -> Unit, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(EzpzIcons.moreVert), contentDescription = "More actions for ${row.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() }, leadingIcon = { Icon(painterResource(EzpzIcons.edit), contentDescription = null) })
            DropdownMenuItem(
                text = { Text("Duplicate") }, onClick = { open = false; actions.duplicate(row) }, enabled = !busy,
                leadingIcon = { Icon(painterResource(EzpzIcons.contentCopy), contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() },
                leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

@Composable
private fun Empty(state: LibraryUiState) {
    val text = when {
        state.query.isNotBlank() -> when (state.tab) {
            LibraryTab.LZ -> "No saved LZ/PZ matches that name or grid."
            LibraryTab.ROUTES -> "No saved route set matches that name."
            LibraryTab.POINTS -> "No saved point set matches that name."
        }
        else -> when (state.tab) {
            LibraryTab.LZ -> "No LZ/PZs saved yet. Make one in the LZ/PZ panel."
            LibraryTab.ROUTES -> "No route sets saved yet. Sketch a route, or import an AMPS mission file."
            LibraryTab.POINTS -> "No sets of points saved yet. Import an AMPS .LPS file."
        }
    }
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl.dp, vertical = 32.dp),
    )
}

// -- Naming ---------------------------------------------------------------------------------------------------------------------

/**
 * What to do with a name another item already has (the design's Confirm A): keep both, this one as [keepBothName] (the default), or replace the other, which is
 * deleted (its old version is not kept).
 */
@Composable
internal fun NameTakenChoice(keepBothName: String, replace: Boolean, onReplace: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Text("How to save it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Choice(!replace, "Keep both", "Save this one as “$keepBothName”.") { onReplace(false) }
        Choice(replace, "Replace the saved one", "Overwrite it with this version. The old version is not kept.") { onReplace(true) }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, supporting: String, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) EzpzTheme.status.accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(Modifier.padding(end = Tokens.Spacing.md.dp, top = Tokens.Spacing.xs.dp, bottom = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Renaming an item of the Library: the name, chosen and ready to type over; a made-up name refused in words (an LZ/PZ's, a set of routes'); a name another item
 * of its kind has offers *Keep both* (the default) or *Replace*.
 */
@Composable
internal fun LibraryRenameContent(row: LibraryRowUi, others: List<Pair<String, String>>, onRename: (name: String, replacing: String?) -> Unit, onDismiss: () -> Unit) {
    var field by rememberSaveable(row.uuid, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(row.name, TextRange(0, row.name.length))) }
    var replace by rememberSaveable(row.uuid) { mutableStateOf(false) }
    var tried by rememberSaveable(row.uuid) { mutableStateOf(false) }
    val typed = cleanName(row.tab, field.text)
    val clash = others.firstOrNull { it.second.trim().equals(typed, ignoreCase = true) }
    val keepBoth = if (clash == null) typed else LzNames.free(typed, others.map { it.second })
    val refusal = when {
        typed.isEmpty() -> "Give it a name."
        row.tab == LibraryTab.LZ && LzNames.isMadeUp(typed) -> LzNames.MADE_UP_REFUSED
        row.tab == LibraryTab.ROUTES && !row.isMission && RouteSetNames.isMadeUp(if (clash != null && !replace) keepBoth else typed) -> RouteSetNames.MADE_UP_REFUSED
        else -> null
    }
    val submit: () -> Unit = {
        tried = true
        if (refusal == null) {
            when {
                clash == null -> onRename(typed, null)
                replace -> onRename(typed, clash.first)
                else -> onRename(keepBoth, null)
            }
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // What the dialog holds, apart from its window so it can be tried on its own (a text field in a dialog window never settles under the test harness).
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semantics { paneTitle = "Rename ${noun(row)}" }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text("Rename ${noun(row)}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = field,
                // A name over the limit (a conflict copy's can be) can always be made shorter, never longer.
                onValueChange = { if (it.text.length <= MAX_NAME || it.text.length < field.text.length) { field = it; tried = false } },
                label = { Text("Name") }, singleLine = true, isError = tried && refusal != null,
                supportingText = {
                    Text(
                        when {
                            tried && refusal != null -> refusal
                            clash != null -> "Name is already used: you already have ${a(noun(row))} with this name."
                            else -> "You can rename it any time."
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = if (row.tab == LibraryTab.ROUTES) KeyboardCapitalization.Characters else KeyboardCapitalization.None, imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }), shape = RoundedCornerShape(Tokens.Radius.sm.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (clash != null) NameTakenChoice(keepBoth, replace, onReplace = { replace = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = submit) { Text(if (clash == null) "Rename" else if (replace) "Replace" else "Keep both") }
            }
        }
    }
}

/** A name as each kind keeps it: a set of routes in capitals, as the web writes them (`RouteSetNames.clean`); an LZ/PZ and a set of points as typed. */
internal fun cleanName(tab: LibraryTab, name: String): String = if (tab == LibraryTab.ROUTES) name.trim().uppercase() else name.trim()

/** The noun with its article, as it is said: "an LZ/PZ" (el-zee), "a set of routes", "a mission". */
private fun a(noun: String) = if (noun.startsWith("LZ") || noun.first().lowercaseChar() in "aeiou") "an $noun" else "a $noun"

/** Names are kept to what the server stores, as the web's field is (`maxLength={100}`). */
private const val MAX_NAME = 100
