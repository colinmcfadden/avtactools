package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.PlaceTool
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft

/**
 * One of the LZ/PZ tools on the map's edge: its symbol, its words (the menu's), what a screen reader says of it (what it does, and why it is off when it is), whether it
 * can be used now, whether its mode is the one on, and what it does.
 */
data class RailTool(val glyph: ToolSymbol, val label: String, val description: String, val enabled: Boolean, val on: Boolean, val onClick: () -> Unit)

/**
 * The LZ/PZ tools on the map's right edge while the LZ/PZ panel is the one chosen (the owner's request, 2026-10-22: no going into the panel and its plan to add an
 * object), over the panel's own view models. A unit is friendly infantry at the crosshair, held, to be changed from its options, as the map menu's *Add object* makes
 * one. A refusal (another drawing has the map's taps, no crosshair yet) is said through [onSaid], never left for the panel's banner, which is out of sight.
 */
@Composable
fun LzToolRailHost(
    crosshair: LatLon?,
    onSaid: (String) -> Unit,
    modifier: Modifier = Modifier,
    graphics: GraphicsViewModel = hiltViewModel(),
    boundary: BoundaryViewModel = hiltViewModel(),
) {
    val g by graphics.state.collectAsStateWithLifecycle()
    val b by boundary.state.collectAsStateWithLifecycle()
    val placing by graphics.placing.collectAsStateWithLifecycle()
    LzToolRailContent(
        g, b, placing?.tool, onSaid, modifier,
        // Said here, so the panel's banner does not show it later for no reason.
        startDrawing = { boundary.start()?.also { boundary.dismissError() } },
        place = graphics::beginPlacing,
        done = { graphics.donePlacing() },
        addUnit = {
            if (crosshair == null) {
                NO_CROSSHAIR
            } else {
                graphics.addUnit(UnitDraft(), crosshair)
                null
            }
        },
    )
}

internal const val NO_CROSSHAIR = "Move the map to where it should go first."

/**
 * The rail ([railTools], [LzToolRail]) and its question before a boundary clears an analysis, apart from the view models so a test can drive them. Each action
 * answers null once done, else why not, which is said through [onSaid]. The question is the open LZ/PZ's ([BoundaryUiState.diagramId]) and goes when drawing can
 * no longer start there (a pack finished, an analysis begun), never to come back by itself.
 */
@Composable
internal fun LzToolRailContent(
    g: GraphicsUiState,
    b: BoundaryUiState,
    placing: PlaceTool?,
    onSaid: (String) -> Unit,
    modifier: Modifier = Modifier,
    startDrawing: () -> String?,
    place: (PlaceTool) -> String?,
    done: () -> Unit,
    addUnit: () -> String?,
) {
    var confirmDraw by rememberSaveable(b.diagramId) { mutableStateOf(false) }
    LaunchedEffect(b.canStart) { if (!b.canStart) confirmDraw = false }
    val draw = { startDrawing()?.let(onSaid) }
    val tools = railTools(
        g, b, placing,
        draw = { if (b.clears) confirmDraw = true else draw() },
        place = { tool -> place(tool)?.let(onSaid) },
        done = done,
        unit = { addUnit()?.let(onSaid) },
    ) ?: return
    LzToolRail(tools, modifier)
    if (confirmDraw && b.canStart) {
        AlertDialog(
            onDismissRequest = { confirmDraw = false },
            title = { Text("Draw a new boundary?") },
            text = { Text("Drawing a new boundary clears this analysis. The aircraft and other graphics stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDraw = false
                    draw()
                }) { Text("Draw") }
            },
            dismissButton = { TextButton(onClick = { confirmDraw = false }) { Text("Keep it") } },
        )
    }
}

/**
 * The rail's tools for the open LZ/PZ ([g], [b]) and the placing mode on ([placing]), as the plan's tool grid offers them: *Draw LZ*, then a helicopter, a PZ
 * marker, a sector of fire, a unit, and the left and right go-arounds. The placing tools are off until the LZ/PZ is analysed, and the one on finishes its mode
 * when tapped again (the placing bar's *Done*); *Draw LZ* is off while a boundary is drawn. What is off says why to a screen reader. Null where none could be used
 * (no LZ/PZ open, or a read-only pack's), so nothing is drawn.
 */
internal fun railTools(
    g: GraphicsUiState,
    b: BoundaryUiState,
    placing: PlaceTool?,
    draw: () -> Unit,
    place: (PlaceTool) -> Unit,
    done: () -> Unit,
    unit: () -> Unit,
): List<RailTool>? {
    if (!g.canEdit && !b.canStart && !b.drawing) return null
    val offUntil = if (g.canEdit) "" else ". Analyze the LZ/PZ first"
    fun placer(tool: PlaceTool, glyph: ToolSymbol, label: String, plural: String): RailTool {
        val on = placing == tool
        return RailTool(glyph, label, if (on) "Placing $plural. Tap to finish" else "Place $plural$offUntil", g.canEdit, on) { if (on) done() else place(tool) }
    }
    val drawSays = when {
        b.drawing -> "Drawing the LZ/PZ boundary"
        else -> "Draw the LZ/PZ boundary" + (b.cannotStart?.takeIf { !b.canStart }?.let { ". $it" } ?: "")
    }
    return listOf(
        RailTool(ToolSymbol.Boundary, "Draw LZ", drawSays, b.canStart && !b.drawing, b.drawing, draw),
        placer(PlaceTool.HELICOPTER, ToolSymbol.Helicopter, "Helicopter", "helicopters"),
        placer(PlaceTool.PZ_MARKER, ToolSymbol.Pz, "PZ marker", "PZ markers"),
        placer(PlaceTool.SECTOR_OF_FIRE, ToolSymbol.Sector, "Sector of fire", "sectors of fire"),
        RailTool(ToolSymbol.Unit, "Unit", "Add a unit at the crosshair$offUntil", g.canEdit, false, unit),
        placer(PlaceTool.GO_AROUND_LEFT, ToolSymbol.GoAroundLeft, "Left go-around", "left go-arounds"),
        placer(PlaceTool.GO_AROUND_RIGHT, ToolSymbol.GoAroundRight, "Right go-around", "right go-arounds"),
    )
}

/** How big a tool of the rail is (a 48 dp target, crews wear gloves) and the gap between two. */
internal val RAIL_BUTTON: Dp = 48.dp
internal val RAIL_GAP: Dp = 8.dp

/** How tall the rail is with all of [count] tools in a column. */
internal fun railHeight(count: Int): Dp = RAIL_BUTTON * count + RAIL_GAP * (count - 1).coerceAtLeast(0)

/** How many of the rail's buttons stand in [height]. */
internal fun railFits(height: Dp): Int = ((height + RAIL_GAP) / (RAIL_BUTTON + RAIL_GAP)).toInt().coerceAtLeast(0)

/**
 * Which of [tools] stand on the rail when only [count] of them do, and which the *More* button lists: the first ones, in order, but never the tool whose mode is on
 * listed, which takes the last place shown, so the one in use is always a tap away.
 */
internal fun railSplit(tools: List<RailTool>, count: Int): Pair<List<RailTool>, List<RailTool>> {
    if (count <= 0) return emptyList<RailTool>() to tools
    val shown = tools.indices.take(count).toMutableList()
    val on = tools.indexOfFirst { it.on }
    if (on >= count) shown[count - 1] = on
    return shown.map(tools::get) to tools.indices.filter { it !in shown }.map(tools::get)
}

/**
 * The tools as a column where the room it is given holds them all; where it does not (the phone's sheet at half, a held object's bar over the readout), as many as
 * fit, the last place a *More* button that lists the rest; where not even one fits, nothing.
 */
@Composable
fun LzToolRail(tools: List<RailTool>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val fits = railFits(maxHeight)
        if (fits == 0) return@BoxWithConstraints
        Column(verticalArrangement = Arrangement.spacedBy(RAIL_GAP)) {
            if (fits >= tools.size) {
                tools.forEach { RailButton(it) }
            } else {
                val (shown, rest) = railSplit(tools, fits - 1)
                shown.forEach { RailButton(it) }
                MoreTools(rest, all = shown.isEmpty())
            }
        }
    }
}

@Composable
private fun RailButton(tool: RailTool) {
    Surface(
        onClick = tool.onClick,
        enabled = tool.enabled,
        modifier = Modifier.size(RAIL_BUTTON).semantics {
            contentDescription = tool.description
            selected = tool.on
        },
        shape = RoundedCornerShape(14.dp),
        color = if (tool.on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (tool.on) BorderStroke(1.dp, EzpzTheme.status.accent) else null,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { ToolGlyph(tool.glyph, tool.enabled) }
    }
}

/** One button over a list of [tools] that do not fit on the rail ([all]: none do); the one whose mode is on, if listed, is ticked, and finishes it. */
@Composable
private fun MoreTools(tools: List<RailTool>, all: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier.size(RAIL_BUTTON).semantics { contentDescription = if (all) "Add to the LZ/PZ" else "More LZ/PZ tools" },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = EzpzTheme.status.accent,
            shadowElevation = 3.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(if (all) EzpzIcons.add else EzpzIcons.moreVert), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tools.forEach { tool ->
                DropdownMenuItem(
                    text = { Text(tool.label) },
                    leadingIcon = { ToolGlyph(tool.glyph, tool.enabled) },
                    trailingIcon = if (tool.on) {
                        { Icon(painterResource(EzpzIcons.check), contentDescription = null, tint = EzpzTheme.status.accent) }
                    } else {
                        null
                    },
                    enabled = tool.enabled,
                    onClick = {
                        open = false
                        tool.onClick()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = tool.description
                        selected = tool.on
                    },
                )
            }
        }
    }
}
