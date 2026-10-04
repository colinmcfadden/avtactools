package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens

/**
 * What is held on the map, for the bar over it: a tap holds a graphic, a threat or a point of a route, and this is what is then within reach of a thumb without pulling the sheet
 * up: its name, where it is, how to turn it (for what turns) and a way to the rest of its options in the sheet. A long press on the same thing picks it up and drags it instead.
 */
data class HeldObjectUi(
    val title: String,
    /** Where it is, or what it is part of: a grid, or the route a point belongs to. */
    val subtitle: String?,
    /** The way it points, degrees clockwise from north; null for what does not turn. */
    val heading: Double?,
)

/**
 * What is held, from what the sheet's own screens already know of it: a planning graphic first, then a threat, then a point of a route, which is the order a tap on the map
 * takes. Null when nothing is held.
 */
fun heldObjectOf(graphics: GraphicsUiState, threats: ThreatsUiState, routes: RoutesUiState): HeldObjectUi? {
    graphics.inspector?.let { return HeldObjectUi(it.title, it.grid, it.rotation) }
    threats.held?.let { return HeldObjectUi(it.name.ifBlank { "Threat" }, it.grid, heading = null) }
    val detail = routes.detail ?: return null
    detail.points.firstOrNull { it.held }?.let { return HeldObjectUi(it.name.ifBlank { "Route point" }, "${detail.name} · ${it.grid}", heading = null) }
    detail.heldShaping?.let { return HeldObjectUi("Shaping point", "${detail.name} · ${it.grid}", heading = null) }
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
)

/** The bar over the map for whatever is held: absent when nothing is. */
@Composable
fun HeldObjectBarHost(
    onOptions: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    graphics: GraphicsViewModel = hiltViewModel(),
    threats: ThreatsViewModel = hiltViewModel(),
    routes: RoutesViewModel = hiltViewModel(),
) {
    val g by graphics.state.collectAsStateWithLifecycle()
    val t by threats.state.collectAsStateWithLifecycle()
    val r by routes.state.collectAsStateWithLifecycle()
    val held = heldObjectOf(g, t, r) ?: return
    HeldObjectBar(held, HeldObjectActions(rotateBy = graphics::rotateBy, options = onOptions, done = onDone), modifier)
}

@Composable
fun HeldObjectBar(held: HeldObjectUi, actions: HeldObjectActions, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = Tokens.Spacing.md.dp).semantics { contentDescription = "Held on the map: ${held.title}" },
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(held.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    held.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextAction("Done", onClick = actions.done)
            }
            held.heading?.let { heading ->
                Text("Heading ${heading.toLong()}°", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    listOf(-15.0 to "−15°", -1.0 to "−1°", 1.0 to "+1°", 15.0 to "+15°").forEach { (delta, label) ->
                        PadButton(label, "Turn ${if (delta < 0) "left" else "right"} ${kotlin.math.abs(delta).toLong()} degrees", Modifier.weight(1f)) { actions.rotateBy(delta) }
                    }
                }
            }
            SecondaryButton("More options", onClick = actions.options)
            Text(
                "Press and hold to move it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
