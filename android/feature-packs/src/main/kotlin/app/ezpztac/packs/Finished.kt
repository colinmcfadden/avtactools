package app.ezpztac.packs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelAction
import app.ezpztac.designsystem.Tokens
import java.time.Instant
import java.time.ZoneId

/**
 * A finished pack, said where the person works (the design's Finished): who finished it and when, that it is read-only for everyone, and what can
 * still be done with it: *Duplicate as new pack* for anyone in it, *Reopen pack* for its owner. On a phone a card at the top of the panel showing
 * ([FinishedBanner]); on a tablet a band across the top beside the rail ([FinishedBand]). Both say it again to a screen reader when it changes.
 */
@Composable
fun FinishedBanner(
    pack: OpenPackUi,
    onDuplicate: () -> Unit,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val (first, second) = PackWords.finished(pack, zone, now)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LockCircle(EzpzIcons.lock, 20)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(first) }
                        append(" ")
                        append(second)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // Each button takes a line of its own where both do not fit (large text), and its words a second line before they are cut.
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FinishedButton("Duplicate as new pack", null, outlined = true, onClick = onDuplicate)
                if (pack.owner) FinishedButton("Reopen pack", EzpzIcons.lockOpen, outlined = false, onClick = onReopen)
            }
            if (pack.owner) {
                Text(
                    "You own ${pack.name}, so only you can reopen it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The tablet's band (the design's AT Finished): across the top beside the rail, over every panel, with the window's top inset in it. */
@Composable
fun FinishedBand(
    pack: OpenPackUi,
    onDuplicate: () -> Unit,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val (first, second) = PackWords.finished(pack, zone, now)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth().semantics { paneTitle = "Pack finished" }) {
        Column {
            BesideOrUnder(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)).heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                words = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LockCircle(EzpzIcons.lockFill, 22)
                        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                            Text(first, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                buttons = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pack.owner) FinishedButton("Reopen pack", null, outlined = false, onClick = onReopen)
                        FinishedButton("Duplicate as new pack", EzpzIcons.contentCopy, outlined = true, onClick = onDuplicate)
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * [words] with [buttons] beside them at the end while the words keep [wordsMin] of room (a wide tablet), else the buttons on a line of their own under them (a
 * narrow window, or large text), so the words are never squeezed to a sliver. With [whole], the words stay beside the buttons only where they fit there on one
 * line (an address is not broken over lines to keep its buttons beside it).
 */
@Composable
internal fun BesideOrUnder(
    modifier: Modifier,
    words: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
    wordsMin: Dp = WORDS_MIN,
    space: Dp = 16.dp,
    whole: Boolean = false,
) {
    Layout(contents = listOf(words, buttons), modifier = modifier) { (wordsPart, buttonsPart), constraints ->
        val gap = space.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val wanted = buttonsPart.single().maxIntrinsicWidth(Constraints.Infinity)
        val room = constraints.maxWidth - wanted - gap
        val beside = if (whole) wordsPart.single().maxIntrinsicWidth(Constraints.Infinity) <= room else room >= wordsMin.roundToPx()
        if (beside) {
            val b = buttonsPart.single().measure(loose.copy(maxWidth = wanted))
            val w = wordsPart.single().measure(loose.copy(maxWidth = constraints.maxWidth - b.width - gap))
            val height = maxOf(w.height, b.height, constraints.minHeight)
            layout(constraints.maxWidth, height) {
                w.place(0, (height - w.height) / 2)
                b.place(constraints.maxWidth - b.width, (height - b.height) / 2)
            }
        } else {
            val w = wordsPart.single().measure(loose)
            val b = buttonsPart.single().measure(loose)
            val between = 8.dp.roundToPx()
            val height = maxOf(w.height + between + b.height, constraints.minHeight)
            layout(constraints.maxWidth, height) {
                w.place(0, 0)
                b.place(constraints.maxWidth - b.width, w.height + between)
            }
        }
    }
}

/** The room the band's words keep beside its buttons before the buttons go under them. */
private val WORDS_MIN = 320.dp

/** The design's 40 dp circle with a lock in it. */
@Composable
private fun LockCircle(icon: Int, size: Int) {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size.dp))
    }
}

/** The banner's buttons, the design's: outlined (*Duplicate*) or text (*Reopen*), in the accent colour; the words take two lines before they are cut. */
@Composable
private fun FinishedButton(text: String, icon: Int?, outlined: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Tokens.Spacing.sm.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, textAlign = TextAlign.Center)
    }
    val padding = PaddingValues(start = if (icon != null) 12.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    if (outlined) {
        OutlinedButton(
            onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50), contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = EzpzTheme.status.accent), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) { content() }
    } else {
        TextButton(
            onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50), contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(contentColor = EzpzTheme.status.accent),
        ) { content() }
    }
}

/**
 * The Pack panel's foot for the owner of a pack not finished: *Finish pack*, asked about first. On a phone the design's PackItems text button with a flag (16 sp);
 * on a [tablet] its PackLive outlined button with a task mark (14 sp).
 */
@Composable
fun PackPanelFoot(onFinish: () -> Unit, modifier: Modifier = Modifier, tablet: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (tablet) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            val described = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Finish pack, makes it read-only for everyone" }
            if (tablet) {
                OutlinedButton(
                    onClick = onFinish, modifier = described, shape = RoundedCornerShape(50), contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EzpzTheme.status.accent), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Icon(painterResource(EzpzIcons.taskAlt), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Tokens.Spacing.sm.dp))
                    Text("Finish pack", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium))
                }
            } else {
                TextButton(onClick = onFinish, modifier = described, colors = ButtonDefaults.textButtonColors(contentColor = EzpzTheme.status.accent)) {
                    Icon(painterResource(EzpzIcons.flag), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Tokens.Spacing.sm.dp))
                    Text("Finish pack", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** The Pack panel header's menu (the web's ⋯ on a pack): *Duplicate as a new pack*, for anyone in it. */
@Composable
fun PackHeaderMenu(pack: String, onDuplicate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PanelAction(EzpzIcons.moreVert, "More actions for $pack", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(
                text = { Text("Duplicate as a new pack") }, leadingIcon = { Icon(painterResource(EzpzIcons.contentCopy), contentDescription = null) },
                onClick = { open = false; onDuplicate() },
            )
        }
    }
}

/** The pack's dialogs (Finish, Reopen, Duplicate), shown where the frame is, whatever asked for them. */
@Composable
fun PackDialogsHost(viewModel: WorkspaceViewModel) {
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    PackDialogs(
        dialog, onFinish = viewModel::finish, onReopen = viewModel::reopen, onDuplicate = viewModel::duplicate, onDismiss = viewModel::dismissDialog,
        onEdited = viewModel::duplicateEdited,
    )
}

/**
 * [dialog], asked first in the web's words: finishing and reopening are plain choices (neither is destroyed: a finished pack reopens, the design draws
 * both buttons alike); duplicating takes a name, and while it is made the dialog stays and cannot be put away.
 */
@Composable
fun PackDialogs(dialog: PackDialog?, onFinish: () -> Unit, onReopen: () -> Unit, onDuplicate: (String) -> Unit, onDismiss: () -> Unit, onEdited: () -> Unit = {}) {
    when (dialog) {
        null -> Unit
        is PackDialog.Finish -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Finish ${dialog.pack}?") },
            text = { Text(FINISH_WORDS) },
            confirmButton = { TextButton(onClick = onFinish) { Text("Finish pack") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        is PackDialog.Reopen -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Reopen ${dialog.pack}?") },
            text = { Text(REOPEN_WORDS) },
            confirmButton = { TextButton(onClick = onReopen) { Text("Reopen pack") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        is PackDialog.Duplicate -> Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = !dialog.sending, dismissOnClickOutside = !dialog.sending),
        ) { DuplicateContent(dialog, onDuplicate, onDismiss, onEdited) }
    }
}

/** Naming the copy (the web's Duplicate dialog), apart from its window so it can be tried on its own. */
@Composable
internal fun DuplicateContent(dialog: PackDialog.Duplicate, onDuplicate: (String) -> Unit, onDismiss: () -> Unit, onEdited: () -> Unit = {}) {
    var field by rememberSaveable(dialog.uuid, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(dialog.name, TextRange(0, dialog.name.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { if (!dialog.sending) onDuplicate(field.text) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semantics { paneTitle = DUPLICATE_TITLE }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text(DUPLICATE_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "A new pack with copies of everything in ${dialog.pack}. You are its owner; nobody else is in it yet.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = field,
                // At most 100 characters (the server's limit, counted as characters), but one longer can always be made shorter.
                onValueChange = { typed ->
                    if (characters(typed.text) <= PackDialog.MAX_NAME || typed.text.length < field.text.length) {
                        if (typed.text != field.text) onEdited()                                    // what was said of the last name goes, as on the web
                        field = typed
                    }
                },
                // Read-only while it is made, not disabled: the field keeps its focus and the keyboard, for a name to be put right if it is refused.
                label = { Text("Name") }, singleLine = true, isError = dialog.error != null, readOnly = dialog.sending,
                supportingText = dialog.error?.let { { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), shape = RoundedCornerShape(Tokens.Radius.sm.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, enabled = !dialog.sending) { Text("Cancel") }
                TextButton(onClick = submit, enabled = !dialog.sending) { Text(if (dialog.sending) "Duplicating…" else "Duplicate") }
            }
        }
    }
}

private fun characters(text: String) = text.codePointCount(0, text.length)

internal const val FINISH_WORDS =
    "Everyone becomes read-only, you included. Members can still view, export and save copies to their own Library. You can reopen the pack whenever you need to."
internal const val REOPEN_WORDS = "Editors can change it again. The history keeps when it was finished."
internal const val DUPLICATE_TITLE = "Duplicate as a new pack"
