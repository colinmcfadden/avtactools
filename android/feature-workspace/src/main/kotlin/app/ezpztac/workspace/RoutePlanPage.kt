package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import kotlinx.coroutines.flow.drop
import java.util.UUID

private val SPEED_CHOICES = listOf("GS" to "ground", "IAS" to "indicated", "TAS" to "true")
private val SPEED_WORDS = mapOf("ground" to "ground speed", "indicated" to "indicated airspeed", "true" to "true airspeed")
private val ALTITUDE_CHOICES = listOf("AGL" to "agl", "MSL" to "msl")
private val ALTITUDE_WORDS = mapOf("agl" to "above ground level", "msl" to "above mean sea level")
private val TYPE_CHOICES = listOf("Turn point" to "turn", "IP" to "ip", "Target" to "target")

/**
 * Below this width a row of the design (drawn 412 dp wide) does not fit: a point's wind goes on a row of its own, and the whole route's date on its own row. A 360 dp
 * phone is below it, the design's width is not.
 */
private val WIDE = 340.dp

/** Past this font scale a point card's header has no room for the clock beside the name: the clock goes under it. */
private const val LARGE_TEXT = 1.3f

/**
 * The held route's plan (the design's RoutePlan), a page of its own over the map: the route with its totals, share and more; the set it is in with *Save*; the
 * whole route's date, temperature and fuel flow; a card for each named point, the held one open (its name, its clock, the altitude, speed and wind flown *to*
 * it, the leg that arrives there); the total with the ground elevations; and the note the web gives. What is typed is applied as it is typed, when it is good,
 * a visit to a field one step to undo; what is wrong is said at the field. [snackbars] are the frame's toasts, drawn here too since the page covers the frame.
 */
@Composable
fun RoutePlanPage(
    state: RoutesUiState,
    actions: RoutesActions,
    crosshairGrid: String?,
    threatCount: Int,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
) {
    val detail = state.detail ?: return
    val open = state.open ?: return
    val row = open.routes.firstOrNull { it.id == detail.routeId }
    var renaming by rememberSaveable(detail.routeId) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(detail.routeId) { mutableStateOf(false) }
    val withThreats = state.includeThreats && threatCount > 0
    // A new page for a screen reader, said by its name, as a destination of its own would be.
    Surface(modifier.fillMaxSize().semantics { paneTitle = "Plan of ${detail.name}" }, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                PlanTopBar(detail, row, open.isMission, state.exporting, open.canUndo, open.canRedo, actions, withThreats, onRename = { renaming = detail.name }, onDelete = { confirmDelete = true })
                SetStrip(open, actions.save)
                // What the bar's menu and the buttons below ask (a rename, a delete, a refusal) is said here, under the bars, where it is in sight however far down the page is scrolled.
                val notices = state.error != null || state.exportWarning != null || renaming != null || confirmDelete
                if (notices) {
                    Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
                        state.exportWarning?.let { Banner(it, BannerKind.Warning, actionLabel = "Dismiss", onAction = actions.dismissExportWarning) }
                        renaming?.let { name ->
                            RouteRenameField(name, onChange = { renaming = it }, onDone = { actions.renameRoute(detail.routeId, it); renaming = null }, onCancel = { renaming = null })
                        }
                        if (confirmDelete) {
                            Banner("Delete \"${detail.name}\"? You can undo this until the set is closed.", BannerKind.Warning)
                            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                                PillButton("Keep it", onClick = { confirmDelete = false }, style = PillStyle.Tonal)
                                TextAction("Delete", onClick = { confirmDelete = false; actions.closePlan(); actions.deleteRoute(detail.routeId) })
                            }
                        }
                    }
                }
                // The keyboard shortens the scrolled area (so a field being typed into is brought above it), rather than padding the content under it.
                Column(
                    Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Tokens.Spacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
                ) {
                    WholeRoute(detail, state.fetching, actions)
                    state.note?.takeIf { it.kind == PlanningKind.WINDS }?.let { Note(it, actions) }
                    PointsSection(detail, crosshairGrid, actions)
                    TotalCard(detail, state.fetching, actions)
                    state.note?.takeIf { it.kind == PlanningKind.ELEVATIONS }?.let { Note(it, actions) }
                    Column(Modifier.padding(horizontal = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        Text(
                            "${detail.aircraft} · speeds, altitudes and winds are “to” each point and export to AMPS.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        detail.warnings.forEach { Banner(it, BannerKind.Warning) }
                    }
                }
            }
            snackbars?.let { ToastHost(it, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(Tokens.Spacing.lg.dp)) }
        }
    }
}

@Composable
private fun Note(note: PlanningNote, actions: RoutesActions) {
    Box(Modifier.padding(horizontal = Tokens.Spacing.lg.dp)) {
        Banner(note.text, if (note.failed) BannerKind.Error else BannerKind.Info, actionLabel = "Dismiss", onAction = actions.dismissNote)
    }
}

// -- The bars at the top -------------------------------------------------------------------------------------------------------------

/** The page's bar (the design's): Back, the route's colour, name and totals, share (GPX or FPL) and more (undo, redo, rename, an AMPS file of it alone, delete). */
@Composable
private fun PlanTopBar(
    detail: RouteDetailUi,
    row: RouteRowUi?,
    fixed: Boolean,
    exporting: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    actions: RoutesActions,
    withThreats: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp),
    ) {
        IconButton(onClick = actions.closePlan, modifier = Modifier.size(48.dp)) { Icon(painterResource(EzpzIcons.arrowBack), contentDescription = "Back to Routes") }
        Column(Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                row?.let { Box(Modifier.size(12.dp).background(routeColor(it.color), CircleShape)) }
                Text(
                    detail.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            row?.let { Text(it.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        ShareMenu(detail.name, enabled = !exporting, icon = { onClick -> RowIcon(EzpzIcons.share, "Share ${detail.name} as GPX or FPL", !exporting, onClick) }) {
            actions.shareRoute(detail.routeId, it)
        }
        RouteMoreMenu(
            detail.name, fixed, exporting, onRename = onRename, onExport = { actions.exportRoute(detail.routeId, withThreats) }, onDelete = onDelete,
            extra = { close ->
                DropdownMenuItem(text = { Text("Undo") }, onClick = { close(); actions.undo() }, enabled = canUndo)
                DropdownMenuItem(text = { Text("Redo") }, onClick = { close(); actions.redo() }, enabled = canRedo)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            },
        )
    }
}

/** The set the route is in (the design's strip under the bar): its name and state, whose Library it is in, and *Save* while it still has its made-up name. */
@Composable
private fun SetStrip(open: OpenSetUi, save: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.sm.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Text(open.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    open.chips.lastOrNull()?.let { StateChip(it.text, it.tone.status(), icon = it.icon) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                    Icon(painterResource(EzpzIcons.person), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(14.dp))
                    Text("Personal · saves to your Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (open.canSave) {
                PillButton("Save", onClick = save, icon = EzpzIcons.save, style = PillStyle.Tonal, modifier = Modifier.semantics { contentDescription = "Save ${open.name}" })
            } else if (!open.madeUpName || open.isMission) {
                PillButton("Saved", onClick = {}, icon = EzpzIcons.check, style = PillStyle.Tonal, enabled = false)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RouteRenameField(name: String, onChange: (String) -> Unit, onDone: (String) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        OutlinedTextField(
            value = name, onValueChange = onChange, label = { Text("Route name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.Radius.sm.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(name) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            TextAction("Save name", onClick = { onDone(name) })
            TextAction("Cancel", onClick = onCancel)
        }
    }
}

// -- The whole route -------------------------------------------------------------------------------------------------------------------

/** The design's "Whole route": *Fetch winds*, and the date (typed, or from the calendar), the temperature and the fuel flow. */
@Composable
private fun WholeRoute(detail: RouteDetailUi, fetching: PlanningKind?, actions: RoutesActions) {
    val plan = detail.plan
    fun apply(typed: PlanDraft, key: Any?): String? = actions.applyPlan(detail.routeId, typed, plan, key)
    Column(Modifier.padding(horizontal = Tokens.Spacing.lg.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Whole route", Modifier.weight(1f))
            AccentTextButton(
                if (fetching == PlanningKind.WINDS) "Fetching winds…" else "Fetch winds", EzpzIcons.air, enabled = fetching == null,
                description = "Fetch winds aloft for ${detail.name}", onClick = actions.fetchWinds,
            )
        }
        BoxWithConstraints(Modifier.padding(top = Tokens.Spacing.xs.dp)) {
            val date: @Composable (Modifier) -> Unit = { m -> DateField(plan.date, detail.routeId, m) { typed, key -> apply(plan.copy(date = typed), key) } }
            val temp: @Composable (Modifier) -> Unit = { m ->
                LiveField(plan.tempC, "Temp °C", m, key = "temp:${detail.routeId}") { text, key -> apply(plan.copy(tempC = text), key) }
            }
            val fuel: @Composable (Modifier) -> Unit = { m ->
                LiveField(plan.fuelFlowLbHr, "Fuel lb/hr", m, key = "fuel:${detail.routeId}", keyboardType = KeyboardType.Decimal) { text, key -> apply(plan.copy(fuelFlowLbHr = text), key) }
            }
            if (maxWidth >= WIDE) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    date(Modifier.weight(168f)); temp(Modifier.weight(92f)); fuel(Modifier.weight(104f))
                }
            } else {
                // A narrow phone: the date as wide as the page, so the whole of it shows beside the calendar.
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    date(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) { temp(Modifier.weight(1f)); fuel(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = EzpzTheme.status.accent, modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun AccentTextButton(text: String, icon: Int, enabled: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = if (enabled) EzpzTheme.status.accent else Color.Unspecified)
        Spacer(Modifier.width(Tokens.Spacing.sm.dp))
        Text(text, color = if (enabled) EzpzTheme.status.accent else Color.Unspecified)
    }
}

/**
 * A visit to a field: a new key each time the field takes focus, so what is typed in one visit is one step to undo and the next visit is a step of its own, whether
 * or not the field has left the page meanwhile (a card shut, the page closed). [base] says which field, for anyone reading an undo key.
 */
@Composable
private fun rememberVisit(base: String): Pair<String, () -> Unit> {
    var visit by rememberSaveable(base) { mutableStateOf(UUID.randomUUID().toString()) }
    return "$base#$visit" to { visit = UUID.randomUUID().toString() }
}

/**
 * The date as the design's field writes it (`mm/dd/yyyy`), applied once it is a whole date that exists (or blank, for today), and the calendar beside it, whose
 * choice is a step of its own. What is typed that is not a date is said when the field is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(planDate: String, routeId: String, modifier: Modifier, apply: (String, Any?) -> String?) {
    val shown = PlanDates.display(planDate)
    val field = rememberTextFieldState(shown)
    var error by remember { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val (visit, nextVisit) = rememberVisit("date:$routeId")
    val applyNow by rememberUpdatedState(apply)
    val visitNow by rememberUpdatedState(visit)
    // A date changed under the field (an undo, the calendar) is shown, unless what is typed already says it.
    LaunchedEffect(planDate) {
        if (PlanDates.toPlan(field.text.toString()) != planDate.trim()) {
            field.setTextAndPlaceCursorAtEnd(shown)
            error = null
        }
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.drop(1).collect { typed ->
            error = null
            PlanDates.toPlan(typed)?.let { error = applyNow(it, visitNow) }
        }
    }
    OutlinedTextField(
        state = field,
        modifier = modifier.onFocusChanged {
            if (it.isFocused) nextVisit()
            else if (field.text.isNotBlank() && PlanDates.toPlan(field.text.toString()) == null) error = "Type the date as mm/dd/yyyy."
        },
        // The label stays up, so the format shows before anything is typed, as the design draws it (blank is today).
        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
        label = { Text("Date") },
        placeholder = { Text("mm/dd/yyyy") },
        lineLimits = TextFieldLineLimits.SingleLine,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(painterResource(EzpzIcons.calendarMonth), contentDescription = "Choose the date from a calendar") }
        },
    )
    if (picking) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = PlanDates.millisOf(planDate))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { error = apply(PlanDates.fromMillis(it), null) }
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }
}

/** Two texts are the same number (`050` and `50`, `1.` and `1`), or the same text. */
private fun sameNumber(a: String, b: String): Boolean {
    if (a.trim() == b.trim()) return true
    val x = a.trim().toDoubleOrNull() ?: return false
    val y = b.trim().toDoubleOrNull() ?: return false
    return x == y
}

/**
 * A value of the plan typed into (the design's outlined field, no Apply button): applied as it is typed when it is good, every keystroke of one visit to the field
 * under one key ([rememberVisit]), so a visit is one step to undo; what is wrong is said under it, in the planner's words. A value changed under it (an undo, a
 * fetched wind) is shown, unless what is typed already says the same. Left blank, it shows the value again. Its label names it: a field that is edited is never
 * given a description of its own, which a screen reader would read in place of what is typed.
 */
@Composable
private fun LiveField(
    value: String,
    label: String,
    modifier: Modifier,
    key: String,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onType: (text: String, coalesce: String) -> String?,
) {
    var text by rememberSaveable(key) { mutableStateOf(value) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val (visit, nextVisit) = rememberVisit(key)
    LaunchedEffect(value) { if (!sameNumber(text, value)) { text = value; error = null } }
    OutlinedTextField(
        value = text,
        onValueChange = { typed -> text = typed; error = onType(typed, visit) },
        modifier = modifier.onFocusChanged {
            if (it.isFocused) nextVisit()
            else if (text.isBlank()) { text = value; error = null }
        },
        label = { Text(label, maxLines = 1) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
    )
}

// -- The points ------------------------------------------------------------------------------------------------------------------------

@Composable
private fun PointsSection(detail: RouteDetailUi, crosshairGrid: String?, actions: RoutesActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = Tokens.Spacing.lg.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Points", Modifier.weight(1f))
        Text(countOf(detail.points.size, "point"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.padding(horizontal = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        detail.heldShaping?.let { ShapingPointCard(detail.routeId, it, crosshairGrid, actions, fixedType = detail.isMission) }
        if (detail.points.isEmpty()) {
            Text(
                "A route needs two named points before it can be planned.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val color = routeColor(detail.color)
        detail.points.forEach { point ->
            if (point.held) OpenPointCard(detail.routeId, point, color, crosshairGrid, actions, fixedType = detail.isMission)
            else ClosedPointCard(point, color) { actions.selectPoint(point.id) }
        }
        if (detail.shapingPoints > 0) {
            Text(
                if (detail.shapingPoints == 1) "1 shaping point bends the line between named points." else "${detail.shapingPoints} shaping points bend the line between named points.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // In an imported mission the point put on the line is a named one that splits the leg (the file has no other kind to give it).
        CrosshairLine(crosshairGrid)
        PillButton(
            if (detail.isMission) "Add a point at the crosshair" else "Add a shaping point at the crosshair", onClick = { actions.addShapingPoint(detail.routeId) },
            icon = EzpzIcons.addLocationAlt, style = PillStyle.Outlined,
            modifier = Modifier.semantics {
                contentDescription = (if (detail.isMission) "Add a named point on the line at the crosshair" else "Add a point that bends the line at the crosshair") +
                    (crosshairGrid?.let { ", $it" } ?: "")
            },
        )
    }
}

/**
 * Where the crosshair is, in words: the page covers the map, so the grid is how the person knows where a point put "at the crosshair" will go. It is where the map
 * was left; Back shows the map, to move it.
 */
@Composable
private fun CrosshairLine(grid: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Icon(painterResource(EzpzIcons.add), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            if (grid != null) "Crosshair: $grid" else "The crosshair is where the map was left. Back shows the map.",
            style = if (grid != null) EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize) else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A named point's symbol, as the map draws it: a target a triangle, an IP a square, a turn a dot; in the route's colour. */
@Composable
private fun PointGlyph(ptType: String?, color: Color) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            val u = size.width / 14f
            when (ptType) {
                "target" -> drawPath(Path().apply { moveTo(7f * u, 1.5f * u); lineTo(13f * u, 12f * u); lineTo(1f * u, 12f * u); close() }, color)
                "ip" -> drawRect(color, topLeft = Offset(2f * u, 2f * u), size = androidx.compose.ui.geometry.Size(10f * u, 10f * u))
                else -> drawCircle(color, radius = 5f * u)
            }
        }
    }
}

private fun pointName(point: PlanPointUi): String = point.name.ifEmpty { if (point.first) ".SP" else "(unnamed)" }

private fun typeWord(ptType: String?): String = when (ptType) { "target" -> "Target"; "ip" -> "IP"; "turn" -> "Turn point"; null -> "Point"; else -> ptType }

/** `50 AGL · 100 GS · Wind 0°T 0 kt`: the values flown to a point, as the design's closed card says them. */
private fun valuesLine(point: PlanPointUi): String {
    val v = point.values
    val altitude = "${v.altitudeValue} ${v.altitudeRef.uppercase()}"
    if (point.first) return "Start alt $altitude"
    val speed = SPEED_CHOICES.firstOrNull { it.second == v.speedType }?.first ?: v.speedType
    return "$altitude · ${v.speedValue} $speed · Wind ${v.windDir}°T ${v.windSpeed} kt"
}

/** The clock at a point as a screen reader says it: "clock time 12:30:00, the time on target", or "clock time not set". */
private fun clockWords(point: PlanPointUi): String = when {
    point.hasClock -> "Clock time ${point.clock}, the time the others are timed from"
    point.clock.startsWith("--") -> "Clock time not set"
    else -> "Clock time ${point.clock}"
}

/** A point's card shut (the design's): its symbol, name and clock; the leg that arrives there and the time since the start; the values flown to it. A tap opens it. */
@Composable
private fun ClosedPointCard(point: PlanPointUi, color: Color, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (point.first) 72.dp else 88.dp).semantics {
            contentDescription = "${typeWord(point.ptType)} ${pointName(point)}. ${clockWords(point)}. ${point.facts}. Elapsed ${point.elapsed}. ${valuesLine(point)}"
            stateDescription = "Closed"
        },
    ) {
        Row(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.md.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            PointGlyph(point.ptType, color)
            Column(Modifier.weight(1f)) {
                Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Text(pointName(point), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    ClockLabel(point)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Text(
                        if (point.first) valuesLine(point) else point.facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (point.first) Text("START", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(point.elapsed, style = MaterialTheme.typography.titleMedium)
                }
                if (!point.first) {
                    Text(valuesLine(point), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(painterResource(EzpzIcons.expandMore), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The clock in a card's first line: `--:--:--` until a point holds one, the held time in the accent colour, said as the TOT in words too. */
@Composable
private fun ClockLabel(point: PlanPointUi) {
    val tint = if (point.hasClock) EzpzTheme.status.accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(painterResource(EzpzIcons.schedule), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            if (point.hasClock) "${point.clock} TOT" else point.clock,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = tint,
        )
    }
}

/**
 * A point's card open (the design's): its name (a local point's name puts it there), its clock, the altitude, speed and wind flown *to* it, the leg that arrives
 * there, and what else can be done to it (its kind, moving it, making it only shape the line). The first point has no arriving leg: only its start altitude. On a
 * narrow phone the wind goes on a row of its own, and with large text the clock goes under the name.
 */
@Composable
private fun OpenPointCard(routeId: String, point: PlanPointUi, color: Color, crosshairGrid: String?, actions: RoutesActions, fixedType: Boolean) {
    var name by remember(point.id, point.name) { mutableStateOf(point.name) }
    var clockOpen by rememberSaveable(point.id) { mutableStateOf(false) }
    var moving by rememberSaveable(point.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val values = point.values
    fun apply(typed: PointDraft, key: String?): String? = actions.applyPoint(routeId, point.id, typed, values, point.first, key)
    val title = pointName(point)
    // A name that is a local point's puts the point on it when it is saved: said before it is, so a move is never a surprise. Saving the name it already has does nothing.
    val local = if (name != point.name) actions.localPointNamed(name) else null
    val saveName: () -> Unit = { if (name != point.name) actions.renamePoint(routeId, point.id, name) else focus.clearFocus() }
    val largeText = LocalDensity.current.fontScale > LARGE_TEXT
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.md.dp)) {
            val wide = maxWidth >= WIDE && !largeText
            val clock: @Composable () -> Unit = {
                OutlinedButton(
                    onClick = { clockOpen = !clockOpen },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = if (point.clock.startsWith("--")) "Set clock time at $title, not set" else "Set clock time at $title, ${point.clock}"
                    },
                    contentPadding = PaddingValues(start = 10.dp, end = 14.dp),
                ) {
                    Icon(painterResource(EzpzIcons.schedule), contentDescription = null, modifier = Modifier.size(18.dp), tint = EzpzTheme.status.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(point.clock, style = EzpzText.grid.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = EzpzTheme.status.accent)
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    PointGlyph(point.ptType, color)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Point name") }, singleLine = true, modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), shape = RoundedCornerShape(Tokens.Radius.sm.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveName() }),
                        placeholder = { Text(if (point.first) ".SP" else "") },
                    )
                    if (wide) clock()
                    IconButton(onClick = { actions.selectPoint(point.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.expandLess), contentDescription = "Collapse $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!wide) Box(Modifier.padding(start = 32.dp, top = Tokens.Spacing.sm.dp)) { clock() }
                if (name != point.name) {
                    Text(
                        local?.let { "Matches a local point: saving puts this point on it${it.chartElevationFt?.let { ft -> ", at ${withCommas(Math.round(ft))} ft" }.orEmpty()}." }
                            ?: "Shown in AMPS. Left blank, the point is known by its place in the route.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.xs.dp, start = 32.dp),
                    )
                    TextAction("Save name", onClick = saveName, modifier = Modifier.padding(start = 24.dp))
                }
                if (clockOpen) ClockEditor(point, onSet = { text -> apply(values.copy(clock = text), null) }, onClose = { clockOpen = false })
                PointValues(routeId, point, wide, ::apply)
                HorizontalDivider(Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.heightIn(min = 44.dp).padding(end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Icon(painterResource(EzpzIcons.east), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(point.facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(point.elapsed, style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!fixedType) TypeMenu(title, point.ptType) { actions.setPointType(routeId, point.id, it) }
                    TextButton(onClick = { moving = !moving }, modifier = Modifier.semantics { contentDescription = if (moving) "Hide the controls to move $title" else "Show controls to move this point" }) {
                        Text(if (moving) "Done moving" else "Move")
                    }
                    if (!fixedType) TextButton(onClick = { actions.makeShaping(routeId, point.id) }) { Text("Shape only") }
                }
                if (moving) PointPosition(routeId, point.id, point.grid, crosshairGrid, actions)
            }
        }
    }
}

/**
 * The altitude, speed and wind flown to a point, as the design lays them out three to a row ([wide]); on a narrow phone, or with large text, the wind has a row of its
 * own. The first point has only its altitude. Each field's label says what it is; the open card is the one point it is for.
 */
@Composable
private fun PointValues(routeId: String, point: PlanPointUi, wide: Boolean, apply: (PointDraft, String?) -> String?) {
    val values = point.values
    val title = pointName(point)
    val altitude: @Composable (Modifier) -> Unit = { m ->
        LiveField(values.altitudeValue, if (point.first) "Start alt" else "Alt to", m, key = "alt:$routeId:${point.id}", suffix = "ft") { text, key -> apply(values.copy(altitudeValue = text), key) }
    }
    val altitudeRef: @Composable (Modifier) -> Unit = { m ->
        Segmented(ALTITUDE_CHOICES, values.altitudeRef, "Altitude reference for $title", m, words = ALTITUDE_WORDS) { ref -> apply(values.copy(altitudeRef = ref), null) }
    }
    val speed: @Composable (Modifier) -> Unit = { m ->
        LiveField(values.speedValue, "Speed to", m, key = "spd:$routeId:${point.id}", suffix = "kt", keyboardType = KeyboardType.Decimal) { text, key -> apply(values.copy(speedValue = text), key) }
    }
    val speedRef: @Composable (Modifier) -> Unit = { m ->
        Segmented(SPEED_CHOICES, values.speedType, "Speed reference for $title", m, words = SPEED_WORDS) { type -> apply(values.copy(speedType = type), null) }
    }
    val windDir: @Composable (Modifier) -> Unit = { m ->
        LiveField(values.windDir, "Wind °T", m, key = "wdir:$routeId:${point.id}", keyboardType = KeyboardType.Decimal) { text, key -> apply(values.copy(windDir = text), key) }
    }
    val windSpeed: @Composable (Modifier) -> Unit = { m ->
        LiveField(values.windSpeed, "Wind kt", m, key = "wkt:$routeId:${point.id}", keyboardType = KeyboardType.Decimal) { text, key -> apply(values.copy(windSpeed = text), key) }
    }
    val row = Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp)
    val gap = Arrangement.spacedBy(Tokens.Spacing.sm.dp)
    if (wide) {
        // The design's rows: the value (80 dp in the design; the unit inside it), its reference at a fixed width, and the wind taking the rest.
        Row(row, verticalAlignment = Alignment.Top, horizontalArrangement = gap) {
            altitude(Modifier.width(96.dp)); altitudeRef(Modifier.width(124.dp).padding(top = 4.dp))
            if (!point.first) windDir(Modifier.weight(1f).padding(start = 4.dp))
        }
        if (!point.first) {
            Row(row, verticalAlignment = Alignment.Top, horizontalArrangement = gap) {
                speed(Modifier.width(96.dp)); speedRef(Modifier.width(150.dp).padding(top = 4.dp))
                windSpeed(Modifier.weight(1f).padding(start = 4.dp))
            }
        }
    } else {
        Row(row, verticalAlignment = Alignment.Top, horizontalArrangement = gap) { altitude(Modifier.weight(1f)); altitudeRef(Modifier.width(124.dp).padding(top = 4.dp)) }
        if (!point.first) {
            Row(row, verticalAlignment = Alignment.Top, horizontalArrangement = gap) { speed(Modifier.weight(1f)); speedRef(Modifier.width(150.dp).padding(top = 4.dp)) }
            Row(row, verticalAlignment = Alignment.Top, horizontalArrangement = gap) { windDir(Modifier.weight(1f)); windSpeed(Modifier.weight(1f)) }
        }
    }
}

/** What kind of point it is: a turn point, the IP or the target. A mission's are its file's and are not offered. */
@Composable
private fun TypeMenu(title: String, ptType: String?, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Kind of point for $title: ${typeWord(ptType)}" }) {
            Text(typeWord(ptType))
            Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            TYPE_CHOICES.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; if (value != ptType) onChoose(value) })
            }
        }
    }
}

/**
 * The design's segmented reference (AGL or MSL; GS, IAS or TAS), drawn as the design draws it rather than as Material's, whose padding and icon do not leave room for
 * the words at the design's width: a 48 dp pill, the chosen part tinted with a tick. A screen reader hears a group of choices, each by its words ([words]).
 */
@Composable
internal fun Segmented(
    choices: List<Pair<String, String>>,
    selected: String,
    description: String,
    modifier: Modifier = Modifier,
    words: Map<String, String> = emptyMap(),
    onSelect: (String) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        // As tall as its tallest part, so each part's tint fills it: at the largest text a label takes two lines rather than being cut.
        modifier.height(IntrinsicSize.Min).heightIn(min = 48.dp).border(1.dp, outline, RoundedCornerShape(24.dp)).padding(1.dp)
            .semantics { contentDescription = description }.selectableGroup(),
    ) {
        choices.forEachIndexed { i, (label, value) ->
            if (i > 0) VerticalDivider(Modifier.fillMaxHeight(), color = outline)
            val chosen = value == selected
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 23.dp, bottomStart = 23.dp)
                choices.lastIndex -> RoundedCornerShape(topEnd = 23.dp, bottomEnd = 23.dp)
                else -> RoundedCornerShape(0)
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = 46.dp)
                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
                    .selectable(selected = chosen, role = Role.RadioButton) { if (!chosen) onSelect(value) }
                    .semantics { words[value]?.let { contentDescription = "$label, $it" } }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                if (chosen) Icon(painterResource(EzpzIcons.check), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    label, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center,
                    color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = if (chosen) 2.dp else 0.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** Setting the clock at a point: the time to be there (`HH:MM:SS`), which the other points are timed from. *Clear* takes it away. */
@Composable
private fun ClockEditor(point: PlanPointUi, onSet: (String) -> String?, onClose: () -> Unit) {
    var text by rememberSaveable(point.id) { mutableStateOf(point.values.clock) }
    var error by remember(point.id) { mutableStateOf<String?>(null) }
    val set: () -> Unit = {
        error = onSet(text.trim())
        if (error == null) onClose()
    }
    Column(Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it; error = null }, label = { Text("Clock time at ${pointName(point)} (HH:MM:SS)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), isError = error != null, supportingText = { Text(error ?: "Sets the time the other points are timed from.") },
            shape = RoundedCornerShape(Tokens.Radius.sm.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { set() }),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            PillButton("Set", onClick = set, enabled = text.isNotBlank())
            if (point.values.clock.isNotBlank()) TextAction("Clear", onClick = { if (onSet("") == null) onClose() })
            TextAction("Cancel", onClick = onClose)
        }
    }
}

/** A held point that only shapes the line: where it is, how to move it, and (not in a mission) making it a named point. */
@Composable
private fun ShapingPointCard(routeId: String, point: ShapingPointUi, crosshairGrid: String?, actions: RoutesActions, fixedType: Boolean) {
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Text("Shaping point", style = MaterialTheme.typography.titleMedium)
            Text(
                if (fixedType) "It only bends the line, as AMPS calculated it. Moving it moves the shape of the leg in the mission's file."
                else "It only bends the line. Make it a route point to give it a name, an altitude or a time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PointPosition(routeId, point.id, point.grid, crosshairGrid, actions)
            if (!fixedType) PillButton("Make it a route point", onClick = { actions.makeNamed(routeId, point.id) }, style = PillStyle.Tonal)
        }
    }
}

/**
 * Where a point is and how to move it: the grid it is at, a pad of four arrows with a step, the crosshair (whose grid is said above its button, the map being under
 * the page), or a grid typed in. The same controls as a held graphic has, because dragging a point on a bouncing phone in gloves is not a plan.
 */
@Composable
private fun PointPosition(routeId: String, pointId: String, grid: String, crosshairGrid: String?, actions: RoutesActions) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    val feet = NUDGE_STEPS_FT[step.coerceIn(NUDGE_STEPS_FT.indices)].toDouble()
    Column(Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = "Point position $grid" },
        )
        NudgeSteps(step) { step = it }
        NudgePad(feet) { north, east -> actions.nudgePoint(routeId, pointId, north, east) }
        CrosshairLine(crosshairGrid)
        PillButton(
            "Put at the crosshair", onClick = { actions.pointToCrosshair(routeId, pointId) }, icon = EzpzIcons.myLocation, style = PillStyle.Outlined,
            modifier = Modifier.semantics { contentDescription = "Put this point at the crosshair" + (crosshairGrid?.let { ", $it" } ?: "") },
        )
        EntryRow("Move to a grid", "16S GD 66993 52949", "Go", KeyboardType.Ascii, onSubmit = { actions.pointToText(routeId, pointId, it) })
    }
}

// -- The total -------------------------------------------------------------------------------------------------------------------------

/** The design's total (distance, time and fuel) with *Fetch elevations*, which lets the altitudes read AGL and MSL. */
@Composable
private fun TotalCard(detail: RouteDetailUi, fetching: PlanningKind?, actions: RoutesActions) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.xs.dp, end = Tokens.Spacing.lg.dp).fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text("Total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detail.totals ?: "--", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            PillButton(
                when {
                    fetching == PlanningKind.ELEVATIONS -> "Fetching…"
                    detail.hasElevations -> "Refresh elevations"
                    else -> "Fetch elevations"
                },
                onClick = actions.fetchElevations, icon = EzpzIcons.terrain, style = PillStyle.Tonal, enabled = fetching == null,
            )
        }
    }
}
