package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ezpztac.data.HandoffFormat
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft

private val AIRSPEED_CHOICES = listOf("Ground speed" to "ground", "Indicated (KIAS)" to "indicated", "True (KTAS)" to "true")
private val ALTITUDE_CHOICES = listOf("AGL" to "agl", "MSL" to "msl")
private val TYPE_CHOICES = listOf("Turn point" to "turn", "IP" to "ip", "Target" to "target")

private fun label(choices: List<Pair<String, String>>, value: String) = choices.firstOrNull { it.second == value }?.first ?: value

/** The route being worked on: its plan, its nav log (a row for each named point, the held one open for editing), its totals and what is wrong with it. */
@Composable
internal fun RouteDetailCard(
    detail: RouteDetailUi, fetching: PlanningKind?, note: PlanningNote?, exporting: Boolean, actions: RoutesActions, modifier: Modifier = Modifier, crosshairGrid: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Column {
                Text("Plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(detail.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                Text(detail.aircraft, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlanForm(detail, actions)
            FetchControls(detail.hasElevations, fetching, note, actions)
            detail.warnings.forEach { Banner(it, BannerKind.Warning) }
            if (detail.points.isNotEmpty()) {
                Text("Nav log", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                detail.points.forEach { PointRow(detail.routeId, it, crosshairGrid, actions, fixedType = detail.isMission) }
            }
            detail.heldShaping?.let { ShapingPointStrip(detail.routeId, it, crosshairGrid, actions, fixedType = detail.isMission) }
            if (detail.shapingPoints > 0) {
                Text(
                    if (detail.shapingPoints == 1) "1 shaping point bends the line between named points." else "${detail.shapingPoints} shaping points bend the line between named points.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // In an imported mission the point put on the line is a named one that splits the leg (the file has no other kind to give it).
            val addLabel = if (detail.isMission) "Add a point on the line at the crosshair" else "Add a shaping point at the crosshair"
            SecondaryButton(
                if (crosshairGrid != null) "$addLabel · $crosshairGrid" else addLabel,
                onClick = { actions.addShapingPoint(detail.routeId) },
                modifier = Modifier.semantics { contentDescription = if (detail.isMission) "Add a named point on the line at the crosshair" else "Add a point that bends the line at the crosshair" },
            )
            detail.totals?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface) }
            if (exporting) PrimaryButton("Export this route", onClick = {}, busy = true, busyText = "Building the mission…")
            else SecondaryButton("Export this route for AMPS", onClick = { actions.exportRoute(detail.routeId) })
            // To other apps: every point of the route, shaping points too, so the path is the one flown. Nothing is sent by the app; the share sheet is the person's.
            Text("Share with another app", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                SecondaryButton("GPX", onClick = { actions.shareRoute(detail.routeId, HandoffFormat.GPX) }, enabled = !exporting, modifier = Modifier.weight(1f))
                SecondaryButton("Garmin FPL", onClick = { actions.shareRoute(detail.routeId, HandoffFormat.FPL) }, enabled = !exporting, modifier = Modifier.weight(1f))
            }
            Text(
                "GPX opens in ATAK, Garmin Pilot and most map apps. FPL is a Garmin flight plan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- The route-wide plan ------------------------------------------------------------------------------------------

@Composable
private fun PlanForm(detail: RouteDetailUi, actions: RoutesActions) {
    // The form starts from the plan as it is and starts again whenever the plan changes under it (an undo, another device's edit): what is typed is never
    // applied to a plan it was not typed against.
    var draft by remember(detail.routeId, detail.plan) { mutableStateOf(detail.plan) }
    var problem by remember(detail.routeId, detail.plan) { mutableStateOf<String?>(null) }
    fun change(next: PlanDraft) { draft = next; problem = null }

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        EzpzTextField(draft.date, { change(draft.copy(date = it)) }, "Date (YYYY-MM-DD)", hint = "The day the clock times fall on. Blank is today.")                  // a number pad has no hyphen
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            EzpzTextField(draft.airspeedValue, { change(draft.copy(airspeedValue = it)) }, "Airspeed", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
            Box(Modifier.weight(1f)) { Choice("Reference", label(AIRSPEED_CHOICES, draft.airspeedType), AIRSPEED_CHOICES) { change(draft.copy(airspeedType = it)) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            EzpzTextField(draft.altitudeValue, { change(draft.copy(altitudeValue = it)) }, "Altitude (ft)", Modifier.weight(1f))                                  // can be below sea level, and a decimal pad has no minus sign
            Box(Modifier.weight(1f)) { Choice("Reference", label(ALTITUDE_CHOICES, draft.altitudeRef), ALTITUDE_CHOICES) { change(draft.copy(altitudeRef = it)) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            EzpzTextField(draft.windDir, { change(draft.copy(windDir = it)) }, "Wind from (°T)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
            EzpzTextField(draft.windSpeed, { change(draft.copy(windSpeed = it)) }, "Wind (kt)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            EzpzTextField(draft.tempC, { change(draft.copy(tempC = it)) }, "Temperature (°C)", Modifier.weight(1f))                               // can be below zero
            EzpzTextField(draft.fuelFlowLbHr, { change(draft.copy(fuelFlowLbHr = it)) }, "Fuel (lb/hr)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
        }
        problem?.let { Banner(it, BannerKind.Error) }
        PrimaryButton("Apply plan", onClick = { problem = actions.applyPlan(detail.routeId, draft) }, enabled = draft != detail.plan)
    }
}

/** Winds and ground elevations come from the server, and each says in words what it found; one is fetched at a time. */
@Composable
private fun FetchControls(hasElevations: Boolean, fetching: PlanningKind?, note: PlanningNote?, actions: RoutesActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            if (fetching == PlanningKind.WINDS) PrimaryButton("Fetch winds", onClick = {}, busy = true, busyText = "Fetching winds…", modifier = Modifier.weight(1f))
            else SecondaryButton("Fetch winds", onClick = actions.fetchWinds, enabled = fetching == null, modifier = Modifier.weight(1f))
            if (fetching == PlanningKind.ELEVATIONS) PrimaryButton("Ground elevations", onClick = {}, busy = true, busyText = "Fetching…", modifier = Modifier.weight(1f))
            else SecondaryButton(if (hasElevations) "Refresh elevations" else "Fetch elevations", onClick = actions.fetchElevations, enabled = fetching == null, modifier = Modifier.weight(1f))
        }
        Text(
            "Winds come from the nearest station: the latest report, or the forecast for a time ahead. Ground elevations let altitudes read AGL and MSL. Both need a connection.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let { Banner(it.text, if (it.failed) BannerKind.Error else BannerKind.Info, actionLabel = "Dismiss", onAction = actions.dismissNote) }
    }
}

// -- The nav log ---------------------------------------------------------------------------------------------------

@Composable
private fun PointRow(routeId: String, point: PlanPointUi, crosshairGrid: String?, actions: RoutesActions, fixedType: Boolean = false) {
    val border = if (point.held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(if (point.held) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).clickable { actions.selectPoint(point.id) }.semantics {
                    contentDescription = "${pointTitle(point)}. ${point.facts}. Elapsed ${point.elapsed}. Clock ${point.clock}"
                    selected = point.held
                },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pointTitle(point), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(point.facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    // The clock time is the TOT when this point carries one: said in words, not only by colour.
                    Text(
                        if (point.hasClock) "${point.clock} TOT" else point.clock,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (point.hasClock) FontWeight.Bold else FontWeight.Normal),
                        color = if (point.hasClock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(point.elapsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (point.held) PointForm(routeId, point, crosshairGrid, actions)
        }
    }
}

private fun pointTitle(point: PlanPointUi): String {
    val glyph = when (point.ptType) { "target" -> "▲"; "ip" -> "■"; else -> "●" }
    return "$glyph ${point.name.ifEmpty { if (point.first) ".SP" else "(unnamed)" }}"
}

@Composable
private fun PointForm(routeId: String, point: PlanPointUi, crosshairGrid: String?, actions: RoutesActions, fixedType: Boolean = false) {
    var typed by remember(point.id, point.values) { mutableStateOf(point.values) }
    var problem by remember(point.id, point.values) { mutableStateOf<String?>(null) }
    var name by remember(point.id, point.name) { mutableStateOf(point.name) }
    var moving by rememberSaveable(point.id) { mutableStateOf(false) }
    fun change(next: PointDraft) { typed = next; problem = null }
    // A name that is a local point's puts the point on it when it is saved: said before it is, so a move is never a surprise.
    val local = if (name != point.name) actions.localPointNamed(name) else null
    val nameHint = local?.let { "Matches a local point: saving puts this point on it${it.chartElevationFt?.let { ft -> ", at ${withCommas(Math.round(ft))} ft" }.orEmpty()}." }
        ?: "Shown in AMPS. Left blank, the point is known by its place in the route."

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        EzpzTextField(
            name, { name = it }, "Name", capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, onImeAction = { actions.renamePoint(routeId, point.id, name) },
            hint = nameHint,
        )
        if (name != point.name) TextAction("Save name", onClick = { actions.renamePoint(routeId, point.id, name) })
        if (!fixedType) Choice("Type", label(TYPE_CHOICES, point.ptType.orEmpty()), TYPE_CHOICES) { actions.setPointType(routeId, point.id, it) }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            EzpzTextField(typed.altitudeValue, { change(typed.copy(altitudeValue = it)) }, if (point.first) "Start altitude (ft)" else "Altitude to (ft)", Modifier.weight(1f))
            Box(Modifier.weight(1f)) { Choice("Reference", label(ALTITUDE_CHOICES, typed.altitudeRef), ALTITUDE_CHOICES) { change(typed.copy(altitudeRef = it)) } }
        }
        if (!point.first) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                EzpzTextField(typed.speedValue, { change(typed.copy(speedValue = it)) }, "Speed to", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                Box(Modifier.weight(1f)) { Choice("Reference", label(AIRSPEED_CHOICES, typed.speedType), AIRSPEED_CHOICES) { change(typed.copy(speedType = it)) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                EzpzTextField(typed.windDir, { change(typed.copy(windDir = it)) }, "Wind from (°T)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                EzpzTextField(typed.windSpeed, { change(typed.copy(windSpeed = it)) }, "Wind (kt)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
            }
        }
        EzpzTextField(
            typed.clock, { change(typed.copy(clock = it)) }, "Time to be here (HH:MM:SS)",                               // a number pad has no colon
            hint = "Sets the time the other points are timed from. Blank clears it.",
        )
        problem?.let { Banner(it, BannerKind.Error) }
        PrimaryButton("Apply point", onClick = { problem = actions.applyPoint(routeId, point.id, typed, point.values, point.first) }, enabled = typed != point.values)
        if (moving) {
            PointPosition(routeId, point.id, point.grid, crosshairGrid, actions)
            TextAction("Hide position controls", onClick = { moving = false })
        } else {
            SecondaryButton("Move this point", onClick = { moving = true }, modifier = Modifier.semantics { contentDescription = "Show controls to move this point" })
        }
        if (!fixedType) SecondaryButton("Make it only shape the line", onClick = { actions.makeShaping(routeId, point.id) })
    }
}

@Composable
private fun ShapingPointStrip(routeId: String, point: ShapingPointUi, crosshairGrid: String?, actions: RoutesActions, fixedType: Boolean = false) {
    Surface(shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
        Column(Modifier.fillMaxWidth().padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Text("Shaping point", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (fixedType) "It only bends the line, as AMPS calculated it. Moving it moves the shape of the leg in the mission's file."
                else "It only bends the line. Make it a route point to give it a name, an altitude or a time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PointPosition(routeId, point.id, point.grid, crosshairGrid, actions)
            if (!fixedType) PrimaryButton("Make it a route point", onClick = { actions.makeNamed(routeId, point.id) })
        }
    }
}

/**
 * Where a point is and how to move it: the grid it is at, a pad of four arrows with a step, the crosshair, or a grid typed in. The same controls as a
 * held graphic has, because dragging a point on a bouncing phone in gloves is not a plan.
 */
@Composable
private fun PointPosition(routeId: String, pointId: String, grid: String, crosshairGrid: String?, actions: RoutesActions) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    val feet = NUDGE_STEPS_FT[step.coerceIn(NUDGE_STEPS_FT.indices)].toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = "Point position $grid" },
        )
        NudgeSteps(step) { step = it }
        NudgePad(feet) { north, east -> actions.nudgePoint(routeId, pointId, north, east) }
        SecondaryButton(
            if (crosshairGrid != null) "Put at the crosshair · $crosshairGrid" else "Put at the crosshair",
            onClick = { actions.pointToCrosshair(routeId, pointId) }, modifier = Modifier.semantics { contentDescription = "Put this point at the crosshair" },
        )
        EntryRow("Move to a grid", "16S GD 66993 52949", "Go", KeyboardType.Ascii, onSubmit = { actions.pointToText(routeId, pointId, it) })
    }
}
