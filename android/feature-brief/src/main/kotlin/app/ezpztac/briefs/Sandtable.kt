package app.ezpztac.briefs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.ezpztac.brief.SandtableSteps
import app.ezpztac.data.BriefState
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.Tokens
import app.ezpztac.map.MapChromeBoxes
import app.ezpztac.map.chromeBox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The live sandtable as the screens draw it (docs/MISSION_BRIEF.md §2.8): the brief being presented ([brief]; null while it is read), whether it could not be put
 * together ([failed]), how it is presented ([ui]), its steps as the sandtable lists them ([steps], core-brief's `SandtableSteps`), the one shown ([index]; -1 for
 * none) with what the map shows for it ([view], [scene]) and what is said for it ([step]: its script and facts), the mission constants, and the bar's [title].
 */
data class SandtableShown(
    val brief: BriefState?,
    val failed: Boolean,
    val ui: SandtableUi,
    val steps: List<SandtableSteps.Step>,
    /** Each step's own title, as the list shows it (the web's `model.steps[i].title`). */
    val titles: List<String>,
    val index: Int,
    val view: SandtableSteps.View?,
    val step: BriefReading.Step?,
    val constants: List<BriefReading.Constant>,
    val scene: SandtableScene,
    val title: String,
) {
    val current: SandtableSteps.Step? get() = steps.getOrNull(index)

    /** The brief is still being read: nothing is shown or flown to yet. */
    val loading: Boolean get() = brief == null && !failed

    companion object {
        /** The sandtable of [brief] as [ui] presents it. */
        fun of(brief: BriefState?, failed: Boolean, ui: SandtableUi): SandtableShown {
            val model = brief?.model
            val steps = SandtableSteps.listed(model)
            val index = if (model == null) -1 else SandtableSteps.indexOfStep(model, ui.stepId, ui.anchor)
            val view = if (index >= 0) SandtableSteps.look(model, index) else null
            val read = model?.let(BriefReading::steps).orEmpty()
            val place = brief?.inputs?.pack?.let { it.name.ifEmpty { "The pack" } } ?: if (brief == null && ui.pack != null) "The pack" else "This session"
            val shortTitle = ((model?.get("template") as? JsonObject)?.get("shortTitle") as? JsonPrimitive)?.takeIf { it.isString }?.content
            return SandtableShown(
                brief = brief, failed = failed, ui = ui, steps = steps, titles = read.map { it.title }, index = index, view = view,
                step = read.getOrNull(index), constants = model?.let(BriefReading::constants).orEmpty(),
                scene = SandtableScene.of(view, model?.get("geometry")),
                title = listOf(place, shortTitle).filter { !it.isNullOrEmpty() }.joinToString(" · "),
            )
        }
    }
}

/** What the sandtable does, over [BriefViewModel]. */
class SandtableActions(
    val end: () -> Unit = {},
    val goTo: (Int) -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val first: () -> Unit = {},
    val last: () -> Unit = {},
    val showSteps: (Boolean) -> Unit = {},
    val showConstants: (Boolean) -> Unit = {},
)

fun sandtableActionsOf(viewModel: BriefViewModel) = SandtableActions(
    end = viewModel::endPresenting, goTo = viewModel::goToStep, next = viewModel::nextStep, previous = viewModel::previousStep, first = viewModel::firstStep,
    last = viewModel::lastStep, showSteps = viewModel::showSteps, showConstants = viewModel::showConstants,
)

/**
 * The live sandtable over the whole map (the web's `Sandtable.jsx`), drawn over it where it is (the map never moves for it): its bar across the top in the frame's
 * place (Presenting, what is briefed, UNCLASSIFIED, *Step n of N*, *End brief*), the steps down the side in the panels' place, the mission constants at the top
 * right of the map that shows and the script card at its foot (who briefs the step, its planned time, the script with its write-ins, its facts, ← and →, a
 * progress bar). The bar and the steps lie over the map as the panels do over the web's, so what is under them is never seen; they are told to [boxes] as covers,
 * like the card and the constants, so each step's figure is fitted clear of them ([SandtableFlights]), and to [chrome], so the MGRS grid's labels keep off them.
 * [toasts] are the frame's, given their place at the top of the map that shows. Narrower than [NARROW] the steps and the constants start folded and the steps lie
 * over the map's middle too, with a shadow, as the web's do below 850 px; there, and at large text, the bar keeps to its essentials and the card's way says where
 * the brief is. Where the map shows, a touch goes through to it: the presenter can pan and zoom to point at something.
 *
 * The arrows, Page Up and Page Down (a presenter's clicker), Home, End and the space bar step; Escape, Back and *End brief* end it. A step is never called by a
 * page: it is *Step n of N*.
 */
@Composable
fun SandtableFrame(
    shown: SandtableShown,
    actions: SandtableActions,
    boxes: SandtableBoxes,
    modifier: Modifier = Modifier,
    chrome: MapChromeBoxes? = null,
    toasts: @Composable (Modifier) -> Unit = {},
) {
    BackHandler { actions.end() }
    // The brief has the keyboard's focus once it begins: a clicker's next step goes to it, not to whatever had the focus before. A focus target, not a focusable:
    // the window is no stop of its own for a screen reader.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // What it measured goes with it, so the next sandtable starts from nothing.
    DisposableEffect(boxes) { onDispose { boxes.map = null } }
    // Laid out left to right whatever the language, as the map is: the steps on the left, ← before →, as the arrow keys go (its words are English).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(
        modifier.fillMaxSize().isSandtableMap(boxes)
            .semantics { paneTitle = SandtableWords.PANE }
            .focusRequester(focus).focusTarget()
            .onPreviewKeyEvent { event -> stepKey(event, actions) }
            // The space bar and Enter step unless what has the focus takes them first (a button presses itself), as the web's do.
            .onKeyEvent { event -> spaceKey(event, actions) },
    ) {
        val narrow = maxWidth < NARROW
        // The bar's sub-line and the step's place give way where the window or the text leaves them no room; the card's way says the place then.
        val compact = narrow || LocalDensity.current.fontScale > LARGE_TEXT
        val windowWidth = maxWidth
        // The window narrowed past the steps' wide layout (a split screen, a foldable): they would lie over the card, so they fold, as they start folded.
        var wasNarrow by remember { mutableStateOf(narrow) }
        LaunchedEffect(narrow) {
            if (narrow && !wasNarrow && shown.ui.stepsOpen) actions.showSteps(false)
            wasNarrow = narrow
        }
        // Over the map's middle, Back folds the steps first.
        BackHandler(enabled = narrow && shown.ui.stepsOpen) { actions.showSteps(false) }
        val beside = shown.ui.stepsOpen && !narrow
        Column(Modifier.fillMaxSize()) {
            SandtableBar(shown, actions, compact, Modifier.coversMap(boxes, "bar").chromeBox(chrome, "sandtable-bar"))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (beside) StepsPane(shown, actions, narrow = false, modifier = Modifier.width(STEPS_WIDTH).fillMaxHeight().coversMap(boxes, "steps").chromeBox(chrome, "sandtable-steps"))
                // The map that shows: right of the steps beside it, under the bar.
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = if (beside) STEPS_WIDTH else 0.dp)) {
                    Stage(shown, actions, boxes, chrome, narrow, compact, maxHeight)
                    // The navigation bar at the map's foot covers it too: only told, never drawn.
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.safeDrawing).coversMap(boxes, "navigation"))
                    toasts(Modifier.align(Alignment.TopCenter))
                }
                // In a narrow window the steps lie over the map's middle, with a shadow.
                if (shown.ui.stepsOpen && narrow) {
                    StepsPane(
                        shown, actions, narrow = true,
                        modifier = Modifier.width(min(NARROW_STEPS_WIDTH, windowWidth * 0.86f)).fillMaxHeight().shadow(8.dp).coversMap(boxes, "steps").chromeBox(chrome, "sandtable-steps"),
                    )
                }
            }
        }
    }
    }
}

/** What a key does on the sandtable (the web's): the arrows, Page Up and Page Down, Home and End step; Escape ends it. A key with Ctrl, Alt or Meta does nothing. */
internal fun stepKey(event: KeyEvent, actions: SandtableActions): Boolean {
    if (event.type != KeyEventType.KeyDown || !plain(event)) return false
    when (event.key) {
        Key.DirectionRight, Key.DirectionDown, Key.PageDown -> actions.next()
        Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> actions.previous()
        Key.MoveHome -> actions.first()
        Key.MoveEnd -> actions.last()
        Key.Escape -> actions.end()
        else -> return false
    }
    return true
}

/** The space bar and Enter step on, once what has the focus has not taken them (the web's Next button has the focus as the brief begins: Enter presses it). */
internal fun spaceKey(event: KeyEvent, actions: SandtableActions): Boolean {
    if (event.type != KeyEventType.KeyDown || !plain(event)) return false
    if (event.key != Key.Spacebar && event.key != Key.Enter && event.key != Key.NumPadEnter) return false
    actions.next()
    return true
}

private fun plain(event: KeyEvent) = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed

/**
 * Across the top, in the frame's place: what is being briefed, where it is, and *End brief*. It takes the window's top inset. [compact] (a narrow window, large
 * text) keeps it to what must be there: the sub-line and the step's place go (the card says the place), and *Steps* is its icon.
 */
@Composable
private fun SandtableBar(shown: SandtableShown, actions: SandtableActions, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .heightIn(min = BAR_HEIGHT).padding(horizontal = Tokens.Spacing.lg.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
            ) {
                StateChip(SandtableWords.PRESENTING, StatusTone.Warning)
                Column(Modifier.weight(1f)) {
                    Text(
                        shown.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!compact) {
                        Text(SandtableWords.SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!shown.ui.stepsOpen) {
                    if (compact) {
                        OutlinedIconButton(onClick = { actions.showSteps(true) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(EzpzIcons.formatListNumbered), contentDescription = SandtableWords.SHOW_STEPS)
                        }
                    } else {
                        PillButton(SandtableWords.STEPS, onClick = { actions.showSteps(true) }, icon = EzpzIcons.formatListNumbered, style = PillStyle.Outlined)
                    }
                }
                Text(SandtableWords.MARK, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = EzpzTheme.status.success, maxLines = 1)
                if (!compact) shown.current?.let { Text(it.position, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                PillButton(SandtableWords.END, onClick = actions.end, icon = EzpzIcons.close, style = PillStyle.Outlined)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The steps, in the panels' place (over the map in a narrow window): where the brief is, and a way to any step, each a 56 dp button. */
@Composable
private fun StepsPane(shown: SandtableShown, actions: SandtableActions, narrow: Boolean, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    // The step shown is brought into sight when it changes, or the list opens: by as little as it takes (the web's `scrollIntoView({ block: "nearest" })`).
    LaunchedEffect(shown.index, shown.ui.stepsOpen) {
        val index = shown.index
        if (index >= 0) bringIntoSight(list, index)
    }
    Surface(modifier.semantics { paneTitle = SandtableWords.BRIEF_STEPS }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start))) {
            Row(Modifier.fillMaxWidth().padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.xs.dp, top = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${SandtableWords.STEPS} · ${shown.steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = { actions.showSteps(false) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(EzpzIcons.chevronLeft), contentDescription = SandtableWords.HIDE_STEPS)
                }
            }
            if (shown.loading) Text(SandtableWords.OPENING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Tokens.Spacing.lg.dp))
            val keys = remember(shown.steps) { stepKeys(shown.steps) }
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.md.dp)) {
                itemsIndexed(shown.steps, key = { i, _ -> keys[i] }) { i, step ->
                    StepRow(step, shown.titles.getOrNull(i) ?: step.title, current = i == shown.index) {
                        actions.goTo(i)
                        // In a narrow window the list lies over the map and the card: the step chosen is what they want to see.
                        if (narrow) actions.showSteps(false)
                    }
                }
            }
        }
    }
}

/** Scrolls [list] by as little as brings item [index] whole into sight: to its top edge when it is above, to its bottom edge when it is below. */
private suspend fun bringIntoSight(list: LazyListState, index: Int) {
    fun item() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item() == null) {
        val first = list.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        if (index < first) {
            list.animateScrollToItem(index)
            return
        }
        list.scrollToItem(index)                                       // below: brought in, then to the foot of the view
        val placed = item() ?: return
        list.scrollBy((placed.offset + placed.size - list.layoutInfo.viewportEndOffset).toFloat().coerceAtMost(0f))
        return
    }
    val placed = item() ?: return
    val start = list.layoutInfo.viewportStartOffset
    val end = list.layoutInfo.viewportEndOffset
    when {
        placed.offset < start -> list.animateScrollBy((placed.offset - start).toFloat())
        placed.offset + placed.size > end -> list.animateScrollBy((placed.offset + placed.size - end).toFloat())
    }
}

/** Each step's key in the list: its id, and its place among steps that share one (a brief should have none, and two alike would end the list). */
internal fun stepKeys(steps: List<SandtableSteps.Step>): List<String> {
    val seen = HashMap<String, Int>()
    return steps.map { s ->
        val n = seen.merge(s.id, 1, Int::plus)!!
        if (n == 1) "step:${s.id}" else "step:${s.id}#$n"
    }
}

@Composable
private fun StepRow(step: SandtableSteps.Step, title: String, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Tokens.Radius.sm.dp)
    // At large text the time goes under the title, which keeps the room.
    val large = LocalDensity.current.fontScale > LARGE_TEXT
    val time: @Composable () -> Unit = { step.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant) } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = STEP_HEIGHT)
            .then(if (current) Modifier.background(scheme.secondaryContainer, shape).border(1.dp, scheme.primary.copy(alpha = 0.55f), shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            // Only the step shown says so (the web's aria-current): a "selected" of false on every other would be read as "not selected" on each.
            .semantics(mergeDescendants = true) {
                if (current) {
                    selected = true
                    stateDescription = SandtableWords.CURRENT
                }
            }
            .padding(horizontal = Tokens.Spacing.md.dp, vertical = Tokens.Spacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
    ) {
        Text(
            step.number, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
            modifier = Modifier.widthIn(min = 40.dp), color = if (current) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) scheme.onSecondaryContainer else scheme.onSurface,
            )
            if (large) time()
        }
        if (!large) time()
    }
}

/** Over the map: the mission constants (top right) and the script card (bottom), each told to [boxes] so the figure is fitted clear of it. */
@Composable
private fun BoxScope.Stage(
    shown: SandtableShown,
    actions: SandtableActions,
    boxes: SandtableBoxes,
    chrome: MapChromeBoxes?,
    narrow: Boolean,
    compact: Boolean,
    mapHeight: Dp,
) {
    if (shown.constants.isNotEmpty()) {
        ConstantsBox(
            shown.constants, open = shown.ui.constantsOpen, onToggle = { actions.showConstants(!shown.ui.constantsOpen) },
            // At most this share of the map, so a long list (large text) stays clear of the card along the foot; it scrolls.
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)).padding(12.dp)
                .width(if (narrow) 240.dp else 270.dp).heightIn(max = (mapHeight * CONSTANTS_SHARE).coerceAtLeast(48.dp))
                .coversMap(boxes, "constants").chromeBox(chrome, "sandtable-constants"),
        )
    }
    StepCard(
        shown, actions, compact,
        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(if (narrow) 8.dp else 16.dp).widthIn(max = CARD_WIDTH).fillMaxWidth()
            .heightIn(max = cardCap(mapHeight, narrow))
            .coversMap(boxes, "card").chromeBox(chrome, "sandtable-card"),
    )
}

/**
 * How tall the card may be over a map [mapHeight] tall: the web's (48% of the map and 400 dp at most; 52% in a narrow window), but never less than [CARD_MIN], so
 * the arrows and a few lines of the script keep their room at large text or in a short window, and never more than the map less its margins.
 */
internal fun cardCap(mapHeight: Dp, narrow: Boolean): Dp {
    val web = if (narrow) mapHeight * 0.52f else min(mapHeight * 0.48f, 400.dp)
    return maxOf(web, CARD_MIN).coerceAtMost((mapHeight - 24.dp).coerceAtLeast(0.dp))
}

@Composable
private fun ConstantsBox(constants: List<BriefReading.Constant>, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(Tokens.Radius.md.dp), color = glass(), contentColor = MaterialTheme.colorScheme.onSurface, border = hairline(), shadowElevation = 4.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle)
                    .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }.padding(horizontal = Tokens.Spacing.md.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(SandtableWords.CONSTANTS, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(painterResource(if (open) EzpzIcons.expandLess else EzpzIcons.expandMore), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Column(Modifier.padding(start = Tokens.Spacing.md.dp, end = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    constants.forEach { c ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                            Text(c.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            // A long value (a formation typed out) keeps to its side, so the label beside it is not squeezed to a letter a line.
                            ValueText(
                                c.value,
                                MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                Modifier.widthIn(max = CONSTANT_VALUE_MAX),
                                align = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A value, or the write-in line it prints as ([BriefReading.WRITE_IN], said as a blank to a screen reader). */
@Composable
private fun ValueText(value: String?, style: TextStyle, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    if (value != null) {
        Text(value, style = style, textAlign = align, modifier = modifier)
    } else {
        Text(
            BriefReading.WRITE_IN, style = style.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align,
            modifier = modifier.semantics { contentDescription = SandtableWords.BLANK },
        )
    }
}

/**
 * The step shown: its paragraph and who briefs it, its planned time, its title, its script and facts, and the way to the steps either side of it. Everything but the
 * way scrolls, and each step starts at its top (**not like the web**, whose card keeps the scroll it had, so a step after a long one opened part-way down); a fade at
 * the foot says there is more. Where the bar has no room for it ([compact]), the way says where the brief is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(shown: SandtableShown, actions: SandtableActions, compact: Boolean, modifier: Modifier = Modifier) {
    val step = shown.step
    val current = shown.current
    val card = glass()
    Surface(modifier, shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = card, contentColor = MaterialTheme.colorScheme.onSurface, border = hairline(), shadowElevation = 6.dp) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            val scroll = rememberScrollState()
            LaunchedEffect(current?.id) { scroll.scrollTo(0) }
            Column(
                Modifier.weight(1f, fill = false).fadeWhileMore(scroll, card).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                if (step != null && current != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        StateChip("${step.number} ${current.section}".uppercase(), StatusTone.Info)
                        if (step.who.isNotEmpty()) StateChip(step.who, StatusTone.Neutral)
                        current.time?.let {
                            Spacer(Modifier.weight(1f))
                            Text(it, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        // Said when the step changes, with where it is (the web's live region).
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "${current.position}: ${step.title}"
                        },
                    )
                    if (step.script.isEmpty()) {
                        Text(SandtableWords.NOTHING_TO_SAY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { step.script.forEach { Sentence(it) } }
                    }
                    if (step.facts.isNotEmpty()) {
                        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            step.facts.forEach { Fact(it) }
                        }
                    }
                } else {
                    Text(
                        when {
                            shown.loading -> SandtableWords.OPENING
                            shown.failed -> SandtableWords.FAILED
                            else -> SandtableWords.NO_STEPS
                        },
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Nav(shown, actions, compact)
        }
    }
}

/** A fade into [colour] along the foot of what [scroll] scrolls, while there is more of it below. */
private fun Modifier.fadeWhileMore(scroll: ScrollState, colour: Color): Modifier = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val fade = FADE.toPx().coerceAtMost(size.height)
        drawRect(Brush.verticalGradient(listOf(colour.copy(alpha = 0f), colour), startY = size.height - fade, endY = size.height), topLeft = Offset(0f, size.height - fade), size = Size(size.width, fade))
    }
}

/** One sentence of the script: its words, and a write-in as a line with what it is for (the web's `Sentence`). */
@Composable
private fun Sentence(parts: List<BriefReading.Part>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        parts.forEach { p ->
            if (p.writeIn) withStyle(SpanStyle(color = muted, fontSize = 12.sp)) { append("${p.label?.let { "$it " }.orEmpty()}${BriefReading.WRITE_IN}") } else append(p.text)
        }
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = BriefReading.sentence(parts).replace(BriefReading.WRITE_IN, SandtableWords.BLANK) })
}

@Composable
private fun Fact(fact: BriefReading.Fact) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), border = hairline()) {
        Row(Modifier.padding(horizontal = Tokens.Spacing.sm.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fact.label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ValueText(fact.value, MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * ← and →, 56 dp across (gloves) whatever room they are given, and the progress through the brief between them; in a narrow window, where the bar does not say it,
 * where the brief is ("Step 8 of 18") before it, when the bar does not say it.
 */
@Composable
private fun Nav(shown: SandtableShown, actions: SandtableActions, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        OutlinedIconButton(onClick = actions.previous, enabled = shown.index > 0, modifier = Modifier.requiredSize(ARROW), shape = CircleShape) {
            Icon(painterResource(EzpzIcons.chevronLeft), contentDescription = SandtableWords.PREVIOUS)
        }
        if (compact) shown.current?.let { Text(it.position, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        Progress(shown, Modifier.weight(1f))
        FilledIconButton(onClick = actions.next, enabled = shown.index >= 0 && shown.index < shown.steps.lastIndex, modifier = Modifier.requiredSize(ARROW), shape = CircleShape) {
            Icon(painterResource(EzpzIcons.chevronRight), contentDescription = SandtableWords.NEXT)
        }
    }
}

/**
 * A bar for each step: those briefed, the one shown (taller, so it is told by more than its colour), those to come, in the palette's own colours (the web's
 * figure blue and yellow would vanish on the light palette and glare on the red night one). Drawn, not read: the bar and the card say where the brief is.
 */
@Composable
private fun Progress(shown: SandtableShown, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val rest = scheme.onSurface.copy(alpha = 0.16f)
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.steps.indices.forEach { i ->
            val now = i == shown.index
            val colour = when {
                i < shown.index -> scheme.primary
                now -> scheme.onSurface
                else -> rest
            }
            Box(Modifier.weight(1f).height(if (now) 8.dp else 4.dp).background(colour, RoundedCornerShape(2.dp)))
        }
    }
}

// The web's glass: the panel colour, a little of the map through it. Not one of the theme's roles, so what is on it is given its colour (contentColorFor finds none).
@Composable
private fun glass(): Color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)

@Composable
private fun hairline() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

private val BAR_HEIGHT = 64.dp
private val STEPS_WIDTH = 300.dp
private val NARROW_STEPS_WIDTH = 300.dp
private val STEP_HEIGHT = 56.dp
private val CARD_WIDTH = 780.dp
private val ARROW = 56.dp

/** The least the card is given: its tags, title and arrows and a few lines of the script, at large text too. */
internal val CARD_MIN = 220.dp

/** The most of the map's height the constants box takes, so it stays clear of the card along the map's foot. */
private const val CONSTANTS_SHARE = 0.42f

/** The widest a constant's value is, beside its label in the 240–270 dp box. */
private val CONSTANT_VALUE_MAX = 150.dp

/** How tall the fade is that says the card's words go on below. */
private val FADE = 28.dp

/** From this font scale a step's time goes under its title in the list, and the bar keeps to its essentials. */
private const val LARGE_TEXT = 1.3f

/** Narrower than this the steps lie over the map and start folded, with the constants, as the web's do below 850 px. */
internal val NARROW = 850.dp

/** The sandtable's words (the web's). */
internal object SandtableWords {
    const val PRESENTING = "Presenting"
    const val SUB = "Presenter's map · only your map moves · editing is off"
    const val STEPS = "Steps"
    const val HIDE_STEPS = "Hide steps"
    const val SHOW_STEPS = "Show steps"
    const val MARK = "UNCLASSIFIED"
    const val END = "End brief"
    const val CONSTANTS = "Mission constants"
    const val OPENING = "Opening the brief…"
    const val NO_STEPS = "This brief has no steps."
    const val NOTHING_TO_SAY = "Nothing to say for this step."
    const val PREVIOUS = "Previous step"
    const val NEXT = "Next step"
    const val PRESENT = "Present sandtable"
    const val PANE = "Presenting the brief"
    const val BRIEF_STEPS = "Brief steps"
    const val CURRENT = "Current step"
    const val FAILED = "The brief could not be put together from the plan as it is now. End it and present it again; if that does not help, the plan holds something the brief cannot read."

    /** A write-in line, as a screen reader says it. */
    const val BLANK = "blank"
}
