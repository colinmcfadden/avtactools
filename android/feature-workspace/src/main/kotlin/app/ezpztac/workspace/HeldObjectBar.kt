package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens

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
)

/**
 * What is held, from what the sheet's own screens already know of it: a planning graphic first, then a threat, then a point of a route, which is the order a tap on the map
 * takes. Null when nothing is held.
 */
fun heldObjectOf(graphics: GraphicsUiState, threats: ThreatsUiState, routes: RoutesUiState, corner: HeldCornerUi? = null): HeldObjectUi? {
    graphics.inspector?.let { held ->
        return HeldObjectUi(held.title, held.grid, held.rotation, locked = graphics.locked, doghouse = held.doghouse, graphic = held.of.takeIf { held.doghouse != null })
    }
    threats.held?.let { return HeldObjectUi(it.name.ifBlank { "Threat" }, it.grid, heading = null) }
    val detail = routes.detail
    val routeLocked = routes.open?.inPack?.reason
    detail?.points?.firstOrNull { it.held }?.let { return HeldObjectUi(it.name.ifBlank { "Route point" }, "${detail.name} · ${it.grid}", heading = null, locked = routeLocked) }
    detail?.heldShaping?.let { return HeldObjectUi("Shaping point", "${detail.name} · ${it.grid}", heading = null, locked = routeLocked) }
    corner?.let {
        return HeldObjectUi(
            it.title, it.grid, heading = null, deleteLabel = "Delete corner", deleteEnabled = it.canDelete && it.locked == null,
            deleteNote = if (it.canDelete || it.locked != null) null else "A boundary needs at least 3 corners.", locked = it.locked,
        )
    }
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
    graphics: GraphicsViewModel = hiltViewModel(),
    threats: ThreatsViewModel = hiltViewModel(),
    routes: RoutesViewModel = hiltViewModel(),
    boundary: BoundaryViewModel = hiltViewModel(),
) {
    val g by graphics.state.collectAsStateWithLifecycle()
    val t by threats.state.collectAsStateWithLifecycle()
    val r by routes.state.collectAsStateWithLifecycle()
    val b by boundary.state.collectAsStateWithLifecycle()
    val held = heldObjectOf(g, t, r, b.heldCorner) ?: return
    HeldObjectBar(
        held,
        HeldObjectActions(
            rotateBy = graphics::rotateBy, options = onOptions, done = onDone, delete = boundary::deleteCorner,
            // Each to the graphic the bar shows now: a field left as another is held takes what was typed for the one it was showing.
            setHeading = { typed -> headingTyped(typed) { graphics.setRotation(it, held.graphic) } },
            setDoghouseLabel = { graphics.setDoghouseLabel(it, held.graphic) }, setDoghouseTime = { graphics.setDoghouseTime(it, held.graphic) },
            setDoghouseDistance = { graphics.setDoghouseDistance(it, held.graphic) }, setDoghouseAirspeed = { graphics.setDoghouseAirspeed(it, held.graphic) },
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
            // A boundary corner has no options of its own to open in the sheet; a doghouse's are beside Done.
            if (held.deleteLabel == null && editable == null) SecondaryButton("More options", onClick = actions.options)
            Text(
                held.locked ?: "Press and hold to move it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
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
