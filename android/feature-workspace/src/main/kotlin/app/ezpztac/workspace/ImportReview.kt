package app.ezpztac.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the review's controls do. */
class ImportReviewActions(
    val setName: (id: Long, name: String) -> Unit = { _, _ -> },
    val setPlace: (id: Long, place: ImportPlace) -> Unit = { _, _ -> },
    val remove: (Long) -> Unit = {},
    val cancel: () -> Unit = {},
    val importAll: () -> Unit = {},
)

/**
 * The import review (docs/native-design: ImportReview), shown whenever a file waits: one another app opened with this one (Files, a mail, the share sheet) or one
 * the person chose (Import). A full-screen dialog, because nothing is brought in until the person says so and nothing else should be tapped meanwhile; Back and
 * the close button bring nothing in. What each file came to is said in the frame's toasts ([IncomingViewModel.notices]), not here: the review has closed by then.
 */
@Composable
fun ImportReviewHost(
    /** Whether the account has threats: a threat file is offered only then, and otherwise said to be turned off. */
    threatsAllowed: Boolean,
    viewModel: IncomingViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, threatsAllowed) { viewModel.allowThreats(threatsAllowed) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return
    val actions = ImportReviewActions(viewModel::setName, viewModel::setPlace, viewModel::remove, viewModel::cancel, viewModel::importAll)
    Dialog(
        onDismissRequest = { if (!state.busy) actions.cancel() },                               // files being brought in are seen through
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        ImportReviewContent(state, actions)
    }
}

/**
 * The frame's way in for files the person chooses (Import, and the panels' own *Import .msnx* and *Import .ths*): one or more files chosen in the system's
 * picker, each read and put in the review like a file another app opened with this one, so a file means the same however it arrives (the web's one way in).
 * What it returns opens the picker. At most [MAX_PICKED] are taken at a time, as from the share sheet, and the rest are told about.
 */
@Composable
fun rememberFileImport(incoming: IncomingViewModel = hiltViewModel()): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult                  // the person backed out
        val pick = incoming.pickStarted()                                                // a cancelled review takes no more of this pick
        scope.launch {
            for (uri in uris.take(MAX_PICKED)) {
                when (val picked = withContext(Dispatchers.IO) { PickedFile.read(resolver, uri, tooBig = IncomingViewModel.TOO_BIG) }) {
                    is PickedFile.Result.Read -> incoming.offer(picked.name, picked.bytes, pick)
                    is PickedFile.Result.Failed -> incoming.refuse("a file you chose", picked.message, pick)
                }
            }
            if (uris.size > MAX_PICKED) {
                incoming.refuse("${uris.size - MAX_PICKED} more", "Only the first $MAX_PICKED files are taken at a time. Import the rest once these are dealt with.", pick)
            }
        }
    }
    // None of these formats has a media type of its own: what a file is comes from what is in it.
    return { picker.launch(arrayOf("*/*")) }
}

/** The most files one Import takes: the same as the share sheet's. */
const val MAX_PICKED = 5

/** The review's title and its button: `Import 3 files`, or plain `Import` when nothing here can be. */
internal fun importTitle(importable: Int): String = when (importable) {
    0 -> "Import"
    1 -> "Import 1 file"
    else -> "Import $importable files"
}

/**
 * The review itself, without the dialog around it: what each file turned out to be, how much is in it, and where it goes (a set of points to the Library or this
 * session; threats to this device; a mission to the Library with its file), each removable; under them, where they all go and *Import*.
 */
@Composable
fun ImportReviewContent(state: IncomingUiState, actions: ImportReviewActions, modifier: Modifier = Modifier) {
    val title = importTitle(state.importable)
    Surface(modifier.fillMaxSize().semantics { paneTitle = title }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.cancel, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(EzpzIcons.close), contentDescription = "Close without importing")
                }
                Text(
                    title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Tokens.Spacing.sm.dp).semantics { heading() },
                )
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "We worked out what each file is. Choose where it should live.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.xs.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.sm.dp),
                )
                state.items.forEachIndexed { index, item ->
                    key(item.id) {
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = Tokens.Spacing.lg.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ReviewItem(item, actions, enabled = !state.busy)
                    }
                }
                Spacer(Modifier.height(Tokens.Spacing.lg.dp))
            }
            ReviewFooter(state, actions)
        }
    }
}

@Composable
private fun ReviewItem(item: ImportItemUi, actions: ImportReviewActions, enabled: Boolean) {
    val remove = { actions.remove(item.id) }
    when (item) {
        is ImportItemUi.Problem -> ProblemItem(item, enabled, remove)
        is ImportItemUi.Reading -> ItemBlock(EzpzIcons.schedule, item.fileName, kind = null, meta = "Looking at what is in it…", enabled, remove) {}
        is ImportItemUi.Points -> ItemBlock(EzpzIcons.pinDrop, item.fileName, "Local points", "${plural(item.count, "point")} · ${item.size}", enabled, remove) {
            // Typed into a field of its own, so what is typed never waits on the review's state to come back round. Not saveable: the view model keeps the
            // name for as long as the file waits (a turn of the phone included), and a saved one would outlive the process and come back for another file with the
            // same per-process id.
            var name by remember(item.id) { mutableStateOf(item.name) }
            val focus = LocalFocusManager.current
            EzpzTextField(
                name, { name = it; actions.setName(item.id, it) }, "Name", capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done,
                onImeAction = { focus.clearFocus() }, enabled = enabled, modifier = Modifier.padding(top = 14.dp),
            )
            Segmented(
                PLACES, item.place.name, "Where ${item.fileName} goes", Modifier.fillMaxWidth().padding(top = Tokens.Spacing.md.dp),
            ) { if (enabled) actions.setPlace(item.id, ImportPlace.valueOf(it)) }
            if (item.place == ImportPlace.SESSION) {
                Note(EzpzIcons.info, "Shown in this session only. Nothing is saved, and it is gone when you sign out or the app is closed.")
            } else {
                Note(EzpzIcons.collectionsBookmark, "Saves to your Library and syncs with your account.", accent = true)
            }
        }
        is ImportItemUi.Threats -> ItemBlock(EzpzIcons.crisisAlert, item.fileName, "Threats", "${plural(item.count, "threat")} · ${item.size}", enabled, remove) {
            LockedPlace("This device only", "Threats are not saved to your account or shared yet.")
            Note(EzpzIcons.encrypted, "Kept in an encrypted file on this device until you sign out.", top = Tokens.Spacing.sm.dp)
        }
        is ImportItemUi.Mission -> ItemBlock(
            EzpzIcons.route, item.fileName, "Mission file", "${plural(item.routes, "route")} · ${plural(item.namedPoints, "named point")} · ${item.size}", enabled, remove,
        ) {
            LockedPlace("Library", "Kept with its file, and synced with your account.")
            Note(
                EzpzIcons.info,
                (item.aircraft?.let { "Planned for the $it in AMPS. " } ?: "") +
                    "What you change is written back into the file, so everything only AMPS holds stays in it.",
                top = Tokens.Spacing.sm.dp,
            )
        }
    }
}

private val PLACES = listOf("Library" to ImportPlace.LIBRARY.name, "This session" to ImportPlace.SESSION.name)

/** A file that can be brought in: its badge, name, kind and what is in it, with a way to take it out of the list, then where it goes ([body]). */
@Composable
private fun ItemBlock(icon: Int, fileName: String, kind: String?, meta: String, enabled: Boolean, onRemove: () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Tokens.Spacing.lg.dp, top = 10.dp, end = Tokens.Spacing.xs.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
            Badge(icon, plain = false)
            Column(Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    kind?.let { KindTag(it) }
                }
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RemoveButton(fileName, enabled, onRemove)
        }
        Column(Modifier.padding(end = Tokens.Spacing.md.dp)) { body() }
    }
}

/** A file nothing will be brought in from (not one of these kinds, damaged, too large, threats turned off): why, in words, and a way to take it out of the list. */
@Composable
private fun ProblemItem(item: ImportItemUi.Problem, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.xs.dp, bottom = Tokens.Spacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
    ) {
        Badge(EzpzIcons.description, plain = true)
        Column(Modifier.weight(1f)) {
            Text(item.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)) { append("Not imported.") }
                    append(" ")
                    append(item.message)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RemoveButton(item.fileName, enabled, onRemove)
    }
}

@Composable
private fun RemoveButton(fileName: String, enabled: Boolean, onRemove: () -> Unit) {
    IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(EzpzIcons.close), contentDescription = "Remove $fileName from the list", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The file's round badge: tinted for one that can be brought in, plain for one that cannot. */
@Composable
private fun Badge(icon: Int, plain: Boolean) {
    Box(
        Modifier.size(40.dp).background(if (plain) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp),
            tint = if (plain) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** What kind of file it is, in a small grey tag beside its name. */
@Composable
private fun KindTag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Where a kind with one place goes, locked (the design's: a lock and the place, then why, beside it or under it on a narrow phone), said as "Goes to …" to a
 * screen reader.
 */
@Composable
private fun LockedPlace(place: String, why: String) {
    SideBySide(
        minFlexible = MIN_WORDS, gap = Tokens.Spacing.md.dp, flexibleFirst = false, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        fixed = { LockedChip(place) },
    ) {
        Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LockedChip(place: String) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.heightIn(min = 32.dp).semantics(mergeDescendants = true) { contentDescription = "Goes to $place" },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = Tokens.Spacing.sm.dp, top = 2.dp, end = Tokens.Spacing.md.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(place, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** A line under a file: a small icon and words. */
@Composable
private fun Note(icon: Int, text: String, accent: Boolean = false, top: Dp = 6.dp) {
    Row(Modifier.padding(top = top), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp),
            tint = if (accent) EzpzTheme.status.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pinned under the files: where they all go (said again as it changes), Cancel, and Import. */
@Composable
private fun ReviewFooter(state: IncomingUiState, actions: ImportReviewActions) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = 10.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.lg.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                Icon(painterResource(EzpzIcons.moveToInbox), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(18.dp))
                Text(state.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions.cancel, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                PillButton(
                    if (state.busy) "Importing…" else importTitle(state.importable), onClick = actions.importAll, icon = EzpzIcons.download,
                    enabled = !state.busy && !state.reading && state.importable > 0, modifier = Modifier.widthIn(min = 184.dp),
                )
            }
        }
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "${withCommas(n.toLong())} ${noun}s"
