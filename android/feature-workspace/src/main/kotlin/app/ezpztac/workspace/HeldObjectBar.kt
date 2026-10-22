package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.RoutePoint

/**
 * What is held on the map, for the bar over it: a tap or a long press holds a graphic, a threat or a point of a route, and this is what is then within reach of a thumb without
 * pulling the sheet up: its name, where it is, how to turn it (for what turns), a doghouse's own fields, and a way to the rest of its options in the sheet. A long press also
 * picks it up, so the finger drags it.
 */
data class HeldObjectUi(
    val title: String,
    /** Where it is, or what it is part of: a grid, or the route a point belongs to. */
    val subtitle: String?,
    /** The way it points, degrees clockwise from north; null for what does not turn. */
    val heading: Double?,
    /** A button that deletes it ("Delete corner"), for what can be deleted from the bar; null for what cannot. */
    val deleteLabel: String? = null,
    /** Whether that button can be pressed, and why not when it cannot ([deleteNote]). */
    val deleteEnabled: Boolean = true,
    val deleteNote: String? = null,
    /** Why it cannot be moved, turned or deleted: a read-only pack's. Said in place of the invitation to move it; a threat, the person's own, never has one. */
    val locked: String? = null,
    /** A doghouse's own fields, typed into in the bar itself (the owner's request, 2026-10-21); null for anything else. */
    val doghouse: DoghouseUi? = null,
    /** Which graphic of which LZ/PZ it is: what its fields take lands on it alone, and what is typed for one is never shown in another's. */
    val graphic: GraphicOf? = null,
    /** An open LZ/PZ that is not the one being worked on: its bar offers to open it, or to hide it from the map. */
    val alsoOpen: AlsoOpenUi? = null,
    /** A point of a route: what it is, and whether it can be made something else from the bar. */
    val pointType: PointTypeUi? = null,
    /** A helicopter's name (a chalk or tail number), typed into in the bar (the owner's request, 2026-10-22): "" with none; null for anything else. */
    val aircraftName: String? = null,
)

/**
 * A held point of a route as its bar's type chips show it (the owner's requests of 2026-10-21 and 2026-10-22): which point of which route, its [name], what it is
 * ([role]; none for an IP point named neither SP nor RP), its AMPS type and its route's colour for its symbol, and why it cannot be changed, if it cannot: [fixed],
 * a mission's (its points' types are its file's), and [shapingOff], one of the route's last two named points, which a leg needs, cannot only bend the line.
 */
data class PointTypeUi(
    val routeId: String,
    val pointId: String,
    val name: String,
    val role: PointRole?,
    val ptType: String?,
    val color: String,
    val fixed: String? = null,
    val shapingOff: String? = null,
)

/**
 * An open LZ/PZ that is not the one being worked on, held after a tap on it (drawn faded under that one, the owner's request of 2026-10-21): its [name], its [grid],
 * and [where] it is open ("Also open", or "In OP DK" for a Mission Pack's).
 */
data class AlsoOpenUi(val id: String, val name: String, val grid: String?, val where: String)

/**
 * What is held, from what the sheet's own screens already know of it: a planning graphic first, then a threat, then a point of a route, then a corner of the boundary,
 * then an open LZ/PZ not being worked on ([alsoOpen]), which is the order a tap on the map takes. Null when nothing is held.
 */
fun heldObjectOf(graphics: GraphicsUiState, threats: ThreatsUiState, routes: RoutesUiState, corner: HeldCornerUi? = null, alsoOpen: AlsoOpenUi? = null): HeldObjectUi? {
    graphics.inspector?.let { held ->
        return HeldObjectUi(
            held.title, held.grid, held.rotation, locked = graphics.locked, doghouse = held.doghouse, aircraftName = held.name,
            graphic = held.of.takeIf { held.doghouse != null || held.name != null },
        )
    }
    threats.held?.let { return HeldObjectUi(it.name.ifBlank { "Threat" }, it.grid, heading = null) }
    val detail = routes.detail
    val routeLocked = routes.open?.inPack?.reason
    val fixed = RoutesViewModel.MISSION_POINT_TYPES_ARE_THE_FILES.takeIf { detail?.isMission == true }
    detail?.points?.firstOrNull { it.held }?.let {
        val type = PointTypeUi(
            detail.routeId, it.id, it.name, PointRoles.roleOf(RoutePoint.KIND_AMPS, it.ptType, it.name), it.ptType, detail.color, fixed,
            shapingOff = RoutesViewModel.TWO_NAMED_POINTS.takeIf { detail.points.size <= 2 },
        )
        return HeldObjectUi(it.name.ifBlank { "Route point" }, "${detail.name} · ${it.grid}", heading = null, locked = routeLocked, pointType = type)
    }
    detail?.heldShaping?.let {
        val type = PointTypeUi(detail.routeId, it.id, "", PointRole.SHAPING, null, detail.color, fixed)
        return HeldObjectUi("Shaping point", "${detail.name} · ${it.grid}", heading = null, locked = routeLocked, pointType = type)
    }
    corner?.let {
        return HeldObjectUi(
            it.title, it.grid, heading = null, deleteLabel = "Delete corner", deleteEnabled = it.canDelete && it.locked == null,
            deleteNote = if (it.canDelete || it.locked != null) null else "A boundary needs at least 3 corners.", locked = it.locked,
        )
    }
    // The grid breaks only between its halves, never inside one, where the line is too long (a narrow phone, large text).
    alsoOpen?.let { return HeldObjectUi(it.name, listOfNotNull(it.where, it.grid?.let(::gridBreakingBetweenHalves)).joinToString(" · "), heading = null, alsoOpen = it) }
    return null
}

/** The actions of the bar. */
class HeldObjectActions(
    /** Turns what is held by this many degrees, clockwise. */
    val rotateBy: (Double) -> Unit = {},
    /** Opens the sheet to the rest of what can be done to it. */
    val options: () -> Unit = {},
    /** Puts it down: nothing is held. */
    val done: () -> Unit = {},
    /** Deletes it (only offered for what [HeldObjectUi.deleteLabel] names). */
    val delete: () -> Unit = {},
    /** A heading typed in degrees; each field answers with what is wrong with what was typed, or null when it was taken. */
    val setHeading: (String) -> String? = { null },
    val setDoghouseLabel: (String) -> String? = { null },
    val setDoghouseTime: (String) -> String? = { null },
    val setDoghouseDistance: (String) -> String? = { null },
    val setDoghouseAirspeed: (String) -> String? = { null },
    /** An open LZ/PZ not being worked on is opened, to be worked on ([AlsoOpenUi.id]). */
    val openOther: (String) -> Unit = {},
    /** An open LZ/PZ not being worked on is hidden from the map. */
    val hideOther: (String) -> Unit = {},
    /** A point of a route is made what a role says ([PointTypeUi]): null once it is, else the words for why not. */
    val setPointRole: (routeId: String, pointId: String, role: PointRole) -> String? = { _, _, _ -> null },
    /** A helicopter's name typed: null once it is taken, else the words for why not. */
    val setAircraftName: (String) -> String? = { null },
)

/** A heading typed in degrees, as the panel's inspector reads one: a number, or the words for what is wrong. */
internal fun headingTyped(typed: String, set: (Double) -> Unit): String? {
    val degrees = typed.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return "Enter degrees, such as 270."
    set(degrees)
    return null
}

/** The bar over the map for whatever is held: absent when nothing is. */
@Composable
fun HeldObjectBarHost(
    onOptions: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** The open LZ/PZ not being worked on that a tap on the map holds, if one is: last in the bar's order. */
    alsoOpen: AlsoOpenUi? = null,
    onOpenOther: (String) -> Unit = {},
    onHideOther: (String) -> Unit = {},
    graphics: GraphicsViewModel = hiltViewModel(),
    threats: ThreatsViewModel = hiltViewModel(),
    routes: RoutesViewModel = hiltViewModel(),
    boundary: BoundaryViewModel = hiltViewModel(),
) {
    val g by graphics.state.collectAsStateWithLifecycle()
    val t by threats.state.collectAsStateWithLifecycle()
    val r by routes.state.collectAsStateWithLifecycle()
    val b by boundary.state.collectAsStateWithLifecycle()
    val held = heldObjectOf(g, t, r, b.heldCorner, alsoOpen) ?: return
    HeldObjectBar(
        held,
        HeldObjectActions(
            rotateBy = graphics::rotateBy, options = onOptions, done = onDone, delete = boundary::deleteCorner,
            // Each to the graphic the bar shows now: a field left as another is held takes what was typed for the one it was showing.
            setHeading = { typed -> headingTyped(typed) { graphics.setRotation(it, held.graphic) } },
            setDoghouseLabel = { graphics.setDoghouseLabel(it, held.graphic) }, setDoghouseTime = { graphics.setDoghouseTime(it, held.graphic) },
            setDoghouseDistance = { graphics.setDoghouseDistance(it, held.graphic) }, setDoghouseAirspeed = { graphics.setDoghouseAirspeed(it, held.graphic) },
            openOther = onOpenOther, hideOther = onHideOther, setPointRole = routes::setPointRole,
            setAircraftName = { graphics.setAircraftName(it, held.graphic) },
        ),
        modifier,
    )
}

@Composable
fun HeldObjectBar(held: HeldObjectUi, actions: HeldObjectActions, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = Tokens.Spacing.md.dp).semantics { contentDescription = "Held on the map: ${held.title}" },
    ) {
        // A doghouse's fields are typed into here, its heading among them; in a read-only pack they are said.
        val editable = held.doghouse?.takeIf { held.locked == null }
        // A point's type is chosen in the bar itself while it can be changed: never in a mission (its points' types are its file's) or a read-only pack.
        val typeChangeable = held.pointType != null && held.locked == null && held.pointType.fixed == null
        // Never taller than most of the window: what does not fit scrolls (a doghouse's bar at large text, over a sheet at half).
        val tallest = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.6f
        Column(
            Modifier.heightIn(max = tallest).verticalScroll(rememberScrollState()).padding(Tokens.Spacing.md.dp),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(held.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    held.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // A doghouse's bar is tall with its fields: the way to the rest of its options is up here, not a button of its own at the foot.
                if (editable != null) IconButton(onClick = actions.options) { Icon(painterResource(EzpzIcons.tune), contentDescription = "More options") }
                TextAction("Done", onClick = actions.done)
            }
            // A helicopter's name, typed here; in a read-only pack it is said.
            held.aircraftName?.let { name ->
                if (held.locked == null) {
                    key(held.graphic) { AircraftNameField(name, actions) }
                } else if (name.isNotEmpty()) {
                    Text("Name: $name", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (editable != null) key(held.graphic) { DoghouseFields(editable, held.heading, actions) }
            held.doghouse?.takeIf { held.locked != null }?.let { d ->
                Text(
                    "Label ${d.label} · Time ${d.time} · ${d.distanceKm} km · ${d.airspeedKts} kts",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            held.heading?.let { heading ->
                if (editable == null) Text("Heading ${heading.toLong()}°", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Nothing to turn it with in a read-only pack: the heading is still said.
                if (held.locked == null) Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    listOf(-15.0 to "−15°", -1.0 to "−1°", 1.0 to "+1°", 15.0 to "+15°").forEach { (delta, label) ->
                        PadButton(label, "Turn ${if (delta < 0) "left" else "right"} ${kotlin.math.abs(delta).toLong()} degrees", Modifier.weight(1f)) { actions.rotateBy(delta) }
                    }
                }
            }
            held.deleteLabel?.let { label ->
                SecondaryButton(label, onClick = actions.delete, enabled = held.deleteEnabled)
                held.deleteNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            held.alsoOpen?.let { other -> AlsoOpenActions(other, actions) }
            held.pointType?.let { type ->
                key(type.routeId, type.pointId) { PointTypeChips(type, enabled = typeChangeable) { role -> actions.setPointRole(type.routeId, type.pointId, role) } }
                type.fixed?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // A boundary corner has no options of its own to open in the sheet; a doghouse's are beside Done; an LZ/PZ also open is opened to be worked on.
            if (held.deleteLabel == null && editable == null && held.alsoOpen == null) SecondaryButton("More options", onClick = actions.options)
            Text(
                held.locked ?: if (held.alsoOpen != null) "Drawn faded while another LZ/PZ is open." else "Press and hold to move it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The held point's type, chosen in the bar itself (the owner's requests of 2026-10-21 and 2026-10-22: no trip into the route's plan for it, and no second dialog): a
 * chip for each of checkpoint, SP, RP, target and shaping point, with its symbol, the one it is now selected. A choice is made as it is tapped, through the plan page's
 * own rules ([PointRoles]); one refused is said under the chips. Off in a mission (the bar says why under them) and in a read-only pack (the bar says why at its foot);
 * the shaping point off, with the reason, where one of the route's last two named points cannot only bend the line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PointTypeChips(type: PointTypeUi, enabled: Boolean, onChoose: (PointRole) -> String?) {
    var refusal by remember { mutableStateOf<String?>(null) }
    val color = routeColor(type.color)
    val shapingOff = type.shapingOff.takeIf { type.role != PointRole.SHAPING }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Text(
            if (type.role == null) "Point type · an IP point" else "Point type",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            PointRole.entries.forEach { role ->
                val shaping = role == PointRole.SHAPING
                FilterChip(
                    selected = role == type.role,
                    onClick = { refusal = onChoose(role) },
                    label = { Text(if (shaping) "Shaping" else role.label) },
                    leadingIcon = { PointGlyph(role.ptType, color, shaping = shaping) },
                    enabled = enabled && !(shaping && shapingOff != null),
                    // One of five, not five boxes to tick; and what it does to the point's name, which a tap changes at once (an SP takes .SP).
                    modifier = Modifier.semantics {
                        contentDescription = choiceSays(role, type)
                        this.role = Role.RadioButton
                    },
                )
            }
        }
        shapingOff?.takeIf { enabled }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        refusal?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** What a choice is, and what it does to the point's name: `RP: Release point, named .RP`; the one it is, what it is alone. */
private fun choiceSays(role: PointRole, type: PointTypeUi): String {
    val what = "${role.label}: ${role.meaning}"
    if (role == type.role) return what
    if (role == PointRole.SHAPING) return "$what, and has no name"
    val name = PointRoles.nameFor(role, type.name)
    return if (name == type.name) what else "$what, named $name"
}

/** A helicopter's name in the bar (the owner's request, 2026-10-22): a chalk or tail number, taken as the inspector takes it, a refusal said under it. */
@Composable
private fun AircraftNameField(name: String, actions: HeldObjectActions) {
    var refusal by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        BarField(
            "Name", name, KeyboardType.Text, Modifier.fillMaxWidth(), placeholder = "Chalk or tail number", capitalization = KeyboardCapitalization.Characters,
            onSettled = { refusal = null },
        ) { typed ->
            actions.setAircraftName(typed).also { refusal = it }
        }
        refusal?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/**
 * What can be done to an open LZ/PZ that is not the one being worked on, from its bar: open it (the one being worked on goes faded in its place), or hide it from
 * the map (the eye on its row in the LZ/PZ panel shows it again). Side by side, each half the bar.
 */
@Composable
private fun AlsoOpenActions(other: AlsoOpenUi, actions: HeldObjectActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        PillButton("Open", onClick = { actions.openOther(other.id) }, icon = EzpzIcons.flightLand, modifier = Modifier.weight(1f).semantics { contentDescription = "Open ${other.name}" })
        PillButton(
            "Hide", onClick = { actions.hideOther(other.id) }, icon = EzpzIcons.visibilityOff, style = PillStyle.Tonal,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Hide ${other.name} on the map" },
        )
    }
}

/**
 * A doghouse's own fields in the bar, so changing one is not a trip into the panel (the owner's request, 2026-10-21): its label and heading, then its time,
 * distance and airspeed, as the panel's inspector takes them. What the refused field was told is said under them.
 */
@Composable
private fun DoghouseFields(doghouse: DoghouseUi, heading: Double?, actions: HeldObjectActions) {
    // Which field was refused, and the words: a field taken, or left with nothing to take, puts its own away, never another's.
    var refusal by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun told(field: String, answer: String?): String? {
        if (answer != null) refusal = field to answer else if (refusal?.first == field) refusal = null
        return answer
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        doghouse.feeds?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            BarField("Label", doghouse.label, KeyboardType.Text, Modifier.weight(1f), onSettled = { told("Label", null) }) { told("Label", actions.setDoghouseLabel(it)) }
            heading?.let { h ->
                BarField("Heading", h.toLong().toString(), KeyboardType.Number, Modifier.weight(1f), suffix = "°", onSettled = { told("Heading", null) }) {
                    told("Heading", actions.setHeading(it))
                }
            }
        }
        // Time, distance and airspeed on one row where three fields keep their labels whole, else airspeed under them (a narrow phone, large text).
        BoxWithConstraints {
            val gap = Tokens.Spacing.sm.dp
            val three = (maxWidth - gap * 2) / 3 >= THREE_FIELD_PADDING + THREE_FIELD_LABEL * LocalDensity.current.fontScale
            val time = @Composable { m: Modifier ->
                BarField("Time", doghouse.time, KeyboardType.Ascii, m, onSettled = { told("Time", null) }) { told("Time", actions.setDoghouseTime(it)) }
            }
            val distance = @Composable { m: Modifier ->
                BarField("Distance", doghouse.distanceKm, KeyboardType.Decimal, m, suffix = "km", onSettled = { told("Distance", null) }) {
                    told("Distance", actions.setDoghouseDistance(it))
                }
            }
            val airspeed = @Composable { m: Modifier ->
                BarField("Airspeed", doghouse.airspeedKts, KeyboardType.Number, m, suffix = "kts", onSettled = { told("Airspeed", null) }) {
                    told("Airspeed", actions.setDoghouseAirspeed(it))
                }
            }
            if (three) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    time(Modifier.weight(1f))
                    distance(Modifier.weight(1f))
                    airspeed(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        time(Modifier.weight(1f))
                        distance(Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        airspeed(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        refusal?.let { (_, words) ->
            Text(words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

// What a field of three needs to keep "Distance" or "Airspeed" whole: its padding, and the label at the person's text size.
private val THREE_FIELD_PADDING = 32.dp
private val THREE_FIELD_LABEL = 56.dp

/**
 * One of the bar's fields: shows [value], takes what is typed when the keyboard's Done is pressed or the field is left, and follows a value changed under it (an undo, a
 * turn from the bar's own buttons, someone else's change in a pack) until the person types, even while it has focus: only what they typed is ever taken, never the value
 * it showed when they came in (AGENTS.md §14, a form that keeps what it opened with). Refused on Done, what was typed stays to be put right; refused as the field is left,
 * the value comes back. [onSettled]: the field is done with nothing to take. Its label names it, and it is given no description of its own, which a screen reader would read
 * in place of what is typed.
 */
@Composable
private fun BarField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    modifier: Modifier,
    suffix: String? = null,
    placeholder: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    onSettled: () -> Unit = {},
    onCommit: (String) -> String?,
) {
    var text by remember { mutableStateOf(value) }
    // Typed into since it last took or showed a value: what is in it is the person's, and a value changed under it does not replace it.
    var typed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value) { if (!typed) text = value }
    // Taken, or nothing to take: true. Refused: false, with the words said by [onCommit]'s caller.
    fun commit(): Boolean {
        if (!typed || text.trim() == value.trim()) {
            typed = false
            text = value
            onSettled()
            return true
        }
        val taken = onCommit(text) == null
        if (taken) typed = false
        return taken
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            typed = true
        },
        label = { Text(label, maxLines = 1) },
        suffix = suffix?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (commit()) focus.clearFocus() }),
        modifier = modifier.onFocusChanged { state ->
            if (focused && !state.isFocused && !commit()) {
                text = value
                typed = false
            }
            focused = state.isFocused
        },
    )
}
