package app.ezpztac.documents

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.DocumentHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The document viewer over the whole screen (docs/DOCUMENTS.md §8.1), as the 3D view is: the frame under it stays composed and untouched, Back closes it, and the
 * frame's toasts ([snackbars]) are drawn over it. [onShare] hands a PDF to the share sheet. A host that leaves (a sign-out) lets the document go; an activity made
 * again for a new configuration is not the host leaving.
 */
@Composable
fun DocumentViewerHost(
    night: Boolean,
    onShare: (DocumentExport) -> Unit,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    focus: FocusRequester = remember { FocusRequester() },
    viewModel: DocumentViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toasts = snackbars ?: remember { SnackbarHostState() }
    val activity = LocalActivity.current
    DisposableEffect(viewModel) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.close() } }
    LaunchedEffect(viewModel) { viewModel.notices.collect { toasts.showSnackbar(it, withDismissAction = true) } }
    LaunchedEffect(viewModel) { viewModel.exports.collect { onShare(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF)) { uri -> viewModel.saveTo(uri) }
    LaunchedEffect(viewModel) { viewModel.saves.collect { name -> picker.launch(name) } }
    if (!state.open) return
    BackHandler {
        if (state.find.open) viewModel.closeFind() else viewModel.close()
    }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()                                                                     // a field left focused under it keeps no keyboard up
        runCatching { focus.requestFocus() }
    }
    DocumentViewerPage(state, actionsOf(viewModel), night, modifier, toasts, viewModel.goTo, focus)
}

/** What the viewer's controls do. */
class DocumentViewerActions(
    val close: () -> Unit = {},
    val retry: () -> Unit = {},
    val openFind: () -> Unit = {},
    val closeFind: () -> Unit = {},
    val find: (String) -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val showHit: (Int) -> Unit = {},
    val share: () -> Unit = {},
    val save: () -> Unit = {},
    val keep: (Boolean) -> Unit = {},
)

internal fun actionsOf(viewModel: DocumentViewerViewModel) = DocumentViewerActions(
    viewModel::close, viewModel::retry, viewModel::openFind, viewModel::closeFind, viewModel::find, viewModel::nextHit, viewModel::previousHit, viewModel::showHit,
    viewModel::share, viewModel::askSave, viewModel::keep,
)

/**
 * The page, stateless: a bar with the document's name, where in it the reader is and what it is, Find and the menu (Share PDF, Save to device, Keep on this
 * device); the find box under it while open; then the pages, drawn white in every theme (they are paper), under a red-shifted veil at night so a white page is
 * never the brightest thing on a night screen. It takes every touch, and names itself to a screen reader as a pane of its own.
 */
@Composable
fun DocumentViewerPage(
    ui: DocumentViewerUi,
    actions: DocumentViewerActions,
    night: Boolean,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    goTo: kotlinx.coroutines.flow.Flow<Int>? = null,
    focus: FocusRequester = remember { FocusRequester() },
) {
    val list = rememberLazyListState()
    val ready = ui.stage as? PdfStage.Ready
    val pages = ready?.pdf?.pages ?: 0
    // The page most in sight: the first one shown, or the next once the first is more than half scrolled away.
    val page by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0
            if (first.size > 0 && -first.offset + info.viewportStartOffset > first.size / 2) first.index + 1 else first.index
        }
    }
    LaunchedEffect(goTo, ready) {
        if (ready == null) return@LaunchedEffect
        goTo?.collect { index -> if (index in 0 until pages) list.animateScrollToItem(index) }
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize().semantics { paneTitle = "Document ${ui.name}" },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopBar(ui, actions, if (pages > 0) "Page ${(page + 1).coerceAtMost(pages)} of ${withCommas(pages)}" else null, focus)
                if (ui.find.open) FindBar(ui.find, actions)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        ui.waiting -> Waiting("Opening the pack…")
                        ready != null -> Pages(ready.pdf, night, list)
                        ui.stage is PdfStage.Failed -> Failed(ui.stage, actions.retry)
                        else -> Waiting("Opening the document…")
                    }
                }
            }
            snackbars?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(Tokens.Spacing.md.dp)) }
        }
    }
}

@Composable
private fun TopBar(ui: DocumentViewerUi, actions: DocumentViewerActions, where: String?, focus: FocusRequester) {
    var menu by remember { mutableStateOf(false) }
    val ready = ui.stage is PdfStage.Ready
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.close, modifier = Modifier.size(48.dp).focusRequester(focus)) {
            Icon(painterResource(EzpzIcons.close), contentDescription = "Close the document")
        }
        Column(Modifier.weight(1f).padding(start = Tokens.Spacing.sm.dp)) {
            Text(
                ui.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            val line = listOfNotNull(where, ui.detail.ifEmpty { null }).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        IconButton(onClick = if (ui.find.open) actions.closeFind else actions.openFind, enabled = ready, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(EzpzIcons.search), contentDescription = if (ui.find.open) "Close find" else "Find in the document")
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = ready, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(EzpzIcons.moreVert), contentDescription = "More actions for ${ui.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                DropdownMenuItem(
                    text = { Text("Share PDF") }, leadingIcon = { Icon(painterResource(EzpzIcons.share), contentDescription = null) },
                    onClick = { menu = false; actions.share() },
                )
                DropdownMenuItem(
                    text = { Text("Save to device") }, leadingIcon = { Icon(painterResource(EzpzIcons.download), contentDescription = null) },
                    onClick = { menu = false; actions.save() },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Keep on this device")
                            Text("To read offline, whatever else is opened", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(painterResource(EzpzIcons.offlinePin), contentDescription = null) },
                    trailingIcon = { Checkbox(checked = ui.pinned, onCheckedChange = null) },
                    onClick = { menu = false; actions.keep(!ui.pinned) },
                    modifier = Modifier.semantics { contentDescription = if (ui.pinned) "Keep on this device, on" else "Keep on this device, off" },
                )
            }
        }
    }
}

/** The find box: what is typed, how many hits and which is shown, a step either way, and the hits with a few words round each (the web's). */
@Composable
private fun FindBar(find: FindUi, actions: DocumentViewerActions) {
    var listShown by remember { mutableStateOf(true) }
    val field = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.md.dp, vertical = Tokens.Spacing.xs.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                find.query, { actions.find(it); listShown = true }, singleLine = true, placeholder = { Text("Find in the document") },
                leadingIcon = { Icon(painterResource(EzpzIcons.search), contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).focusRequester(field),
            )
            IconButton(onClick = actions.previous, enabled = find.hits.hits.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(painterResource(EzpzIcons.expandLess), contentDescription = "Previous match")
            }
            IconButton(onClick = actions.next, enabled = find.hits.hits.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(painterResource(EzpzIcons.expandMore), contentDescription = "Next match")
            }
        }
        val count = if (find.searching) "Looking…" else find.count
        if (count != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (find.hits.hits.isNotEmpty()) {
                    TextButton(onClick = { listShown = !listShown }) { Text(if (listShown) "Hide matches" else "Show matches") }
                }
            }
        }
        when {
            find.noText -> Text(
                "This document is a scan: it has no text to search.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Tokens.Spacing.sm.dp),
            )
            find.failed -> Text(
                "Its text could not be fetched, so nothing can be found in it now. Try again when you are online.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Tokens.Spacing.sm.dp),
            )
            listShown && find.hits.hits.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                itemsIndexed(find.hits.hits, key = { index, _ -> index }) { index, hit ->
                    HitRow(hit, index == find.current) {
                        actions.showHit(index)
                        listShown = false
                        focusManager.clearFocus()
                    }
                }
                if (find.hits.more) {
                    item {
                        Text(
                            "Only the first ${withCommas(find.hits.hits.size)} are listed.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Tokens.Spacing.sm.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One hit: its page, and the words round it with the hit in bold (`p. 4  … LZ HAWK at grid …`). */
@Composable
private fun HitRow(hit: DocumentHit, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Go to page ${hit.page}", onClick = onClick)
            .padding(horizontal = Tokens.Spacing.sm.dp, vertical = Tokens.Spacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Text("p. ${hit.page}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Text(
            buildAnnotatedString {
                if (hit.cutBefore) append("… ")
                append(hit.before)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) { append(hit.match) }
                append(hit.after)
                if (hit.cutAfter) append(" …")
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Waiting(words: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        CircularProgressIndicator()
        Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun Failed(failed: PdfStage.Failed, retry: () -> Unit) {
    Column(
        Modifier.padding(Tokens.Spacing.xl.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Icon(painterResource(EzpzIcons.error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
        Text(failed.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        if (failed.retry) TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}

/**
 * The pages, one under another as they scroll into sight, each drawn as wide as it is shown (no wider than [MAX_PAGE_WIDTH] on a tablet). Pinch zooms
 * (1 to [MAX_ZOOM] times), a double tap goes to [DOUBLE_TAP_ZOOM] and back; zoomed in, the pages scroll sideways too. A page is drawn again for a new width
 * only once the width has settled, the old picture stretched meanwhile.
 */
@Composable
private fun Pages(pdf: DocumentPdf, night: Boolean, list: LazyListState) {
    var zoom by remember(pdf) { mutableFloatStateOf(1f) }
    val sideways = rememberScrollState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            // Two fingers zoom; one is the list's to scroll. Watched before the list sees the touch, and taken from it only while two are down.
            .pointerInput(pdf) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val by = event.calculateZoom()
                            if (by != 1f) {
                                val before = zoom
                                zoom = (zoom * by).coerceIn(1f, MAX_ZOOM)
                                val fraction = if (sideways.maxValue > 0) sideways.value.toFloat() / sideways.maxValue else 0.5f
                                if (zoom != before) scope.launch { sideways.scrollTo((fraction * sideways.maxValue).toInt()) }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Zoom in") { zoom = (zoom * 1.5f).coerceAtMost(MAX_ZOOM); true },
                    CustomAccessibilityAction("Zoom out") { zoom = (zoom / 1.5f).coerceAtLeast(1f); true },
                )
            },
    ) {
        val fit = min(maxWidth - GUTTER * 2, MAX_PAGE_WIDTH)
        val pageWidth = fit * zoom
        val listWidth = if (pageWidth + GUTTER * 2 > maxWidth) pageWidth + GUTTER * 2 else maxWidth
        Box(Modifier.fillMaxSize().horizontalScroll(sideways, enabled = zoom > 1f)) {
            LazyColumn(
                state = list, modifier = Modifier.width(listWidth).fillMaxHeight().imePadding(),
                contentPadding = PaddingValues(vertical = Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(pdf.pages, key = { it }) { index ->
                    PageImage(pdf, index, pageWidth, night) { zoom = if (zoom > 1.01f) 1f else DOUBLE_TAP_ZOOM }
                }
            }
        }
    }
}

@Composable
private fun PageImage(pdf: DocumentPdf, index: Int, width: Dp, night: Boolean, onDoubleTap: () -> Unit) {
    val size = pdf.sizes.getOrNull(index) ?: PageSize(0, 0)
    val px = with(LocalDensity.current) { width.roundToPx() }
    var bitmap by remember(pdf, index) { mutableStateOf<ImageBitmap?>(null) }
    var drawnAt by remember(pdf, index) { mutableStateOf(0) }
    LaunchedEffect(pdf, index, px) {
        if (bitmap != null && drawnAt == px) return@LaunchedEffect
        if (bitmap != null) delay(REDRAW_PAUSE_MS)                                                     // a pinch under way: drawn once it settles
        pdf.page(index, px)?.let { bitmap = it; drawnAt = px }
    }
    Box(
        Modifier.width(width).aspectRatio(1f / size.ratio).background(if (night) VEIL else Color.White)
            .pointerInput(onDoubleTap) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .semantics { contentDescription = "Page ${index + 1} of ${withCommas(pdf.pages)}" },
    ) {
        bitmap?.let {
            Image(
                it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                colorFilter = if (night) ColorFilter.tint(VEIL, BlendMode.Multiply) else null,
            )
        }
    }
}

internal const val PDF = "application/pdf"
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val REDRAW_PAUSE_MS = 150L
private val GUTTER = 12.dp
private val MAX_PAGE_WIDTH = 900.dp

/** The night veil: white paper drawn as this dim red, black ink still black, so a page reads at night without lighting the cockpit. */
private val VEIL = Color(0xFF6E2418)
