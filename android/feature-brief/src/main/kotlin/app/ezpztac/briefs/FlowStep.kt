package app.ezpztac.briefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ezpztac.brief.BriefEdits
import app.ezpztac.brief.BriefFormats
import app.ezpztac.data.BriefState
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.Tokens
import kotlinx.serialization.json.JsonObject

/**
 * Setup, step 1 (the web's `FlowStep`): the mission flow and the constants. The flow is the order the flight meets each route and LZ/PZ, and each LZ/PZ's role decides
 * where the format briefs it; until the person moves or re-roles something it follows the routes, and *Reset to route order* goes back to that. Beside it the mission
 * constants (each from the plan, standard or typed, a blank one a write-in), and whether the threats are briefed. Each row is keyed by what it holds (an entry by its
 * reference, a constant by its id), so a field's text or an open menu never passes to another as the list changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowStepContent(brief: BriefState, edit: ((JsonObject) -> JsonObject) -> Unit, modifier: Modifier = Modifier) {
    val model = brief.model
    val flow = BriefReading.flow(model)
    val catalog = brief.format.constants
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val side = if (maxWidth >= WIDE) 380.dp else 300.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Mission flow", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Hint(SetupWords.FLOW_HINT)
                    }
                    if (!BriefEdits.followsRoutes(brief.doc)) TextButton(onClick = { edit(BriefEdits.resetFlow) }) { Text(SetupWords.RESET_FLOW) }
                }
                if (flow.isEmpty()) {
                    Text(
                        if (brief.inputs.pack != null) SetupWords.EMPTY_PACK else SetupWords.EMPTY_SESSION,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = Tokens.Spacing.xl.dp),
                    )
                } else {
                    Column(Modifier.padding(top = Tokens.Spacing.md.dp)) {
                        flow.forEachIndexed { index, entry ->
                            key(entry.ref) {
                                FlowRowOf(entry, index, flow.size, onMove = { by -> edit(BriefEdits.move(model, entry.ref, by)) },
                                    onRole = { role -> edit(BriefEdits.setRole(model, entry.ref, role)) }, onRemove = { edit(BriefEdits.removeGone(model, entry.ref)) })
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = Tokens.Spacing.md.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(EzpzIcons.info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Hint(SetupWords.ROLE_NOTE, Modifier.padding(start = Tokens.Spacing.sm.dp))
                }
            }
            Column(
                Modifier.width(side).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
            ) {
                Constants(brief, catalog, edit)
                SetupCard(SetupWords.ALSO) {
                    SwitchRow("Threats (${brief.inputs.threatCount})", SetupWords.THREATS_HINT, BriefEdits.threatsOn(brief.doc), { on -> edit(BriefEdits.setThreats(on)) })
                }
            }
        }
    }
}

@Composable
private fun FlowRowOf(entry: BriefReading.FlowEntry, index: Int, count: Int, onMove: (Int) -> Unit, onRole: (String) -> Unit, onRemove: () -> Unit) {
    val name = entry.name ?: "item"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMove(-1) }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(EzpzIcons.expandLess), contentDescription = "Move $name up")
        }
        IconButton(onClick = { onMove(1) }, enabled = index < count - 1, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(EzpzIcons.expandMore), contentDescription = "Move $name down")
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp).padding(start = Tokens.Spacing.xs.dp))
        when {
            entry.gone -> StateChip("GONE", StatusTone.Error)
            entry.kind == "route" -> StateChip("ROUTE", StatusTone.Info)
            else -> MenuButton(
                text = SetupWords.ROLE_LABELS[entry.role] ?: entry.role.orEmpty(),
                description = "Role of $name",
                options = BriefFormats.ROLES.map { it to (SetupWords.ROLE_LABELS[it] ?: it) },
                onChoose = onRole,
                modifier = Modifier.widthIn(min = 96.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = Tokens.Spacing.md.dp)) {
            Text(if (entry.gone) SetupWords.GONE else name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Hint(
                when {
                    entry.gone -> SetupWords.GONE_HINT
                    entry.kind == "route" -> "${entry.start ?: "no clock"} to ${entry.end ?: "no clock"}"
                    else -> "${BriefFormats.ROLE_NAMES[entry.role] ?: entry.role.orEmpty()} · ${entry.grid ?: "no grid"}"
                },
            )
        }
        Text(
            (if (entry.kind == "stop") entry.arrive else entry.start) ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.gone) {
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(EzpzIcons.close), contentDescription = "Remove from the flow")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Constants(brief: BriefState, catalog: List<app.ezpztac.brief.BriefConstant>, edit: ((JsonObject) -> JsonObject) -> Unit) {
    val entries = BriefEdits.constantsOf(brief.doc, catalog)
    var custom by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    SetupCard(SetupWords.CONSTANTS, hint = SetupWords.CONSTANTS_HINT) {
        Hint(SetupWords.CONSTANTS_ASIDE)
        BriefReading.constants(brief.model).forEach { c ->
            val typed = entries.firstOrNull { (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == c.id }
                ?.let { (it["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            key(c.id) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DraftField(
                        value = typed,
                        onCommit = { value -> edit(BriefEdits.setConstant(catalog, c.id, value)) },
                        label = c.label,
                        placeholder = if (c.source == "typed") null else c.value ?: "Write-in",
                        // What the plan or the standard gives is said where it can be read without typing: an empty field shows its label, not its placeholder.
                        supporting = constantSource(c),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { edit(BriefEdits.removeConstant(catalog, c.id)) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.close), contentDescription = "Remove ${c.label}")
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            BriefEdits.suggestions(brief.doc, catalog).forEach { c ->
                AssistChip(onClick = { edit(BriefEdits.addConstant(catalog, c.id)) }, label = { Text("+ ${c.label}") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (custom == null) AssistChip(onClick = { custom = "" to "" }, label = { Text(SetupWords.YOUR_OWN) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        custom?.let { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                OutlinedTextField(label, { custom = it to value }, label = { Text("Name of the constant") }, placeholder = { Text("Formation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value, { custom = label to it }, label = { Text("Its value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextButton(onClick = { custom = null }) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            edit(BriefEdits.addCustomConstant(catalog, label, value))
                            custom = null
                        },
                        enabled = label.isNotBlank(),
                    ) { Text("Add") }
                }
            }
        }
    }
}

/** Where a constant's value comes from, with the value itself when the plan or a standard gives it ("From the plan: 120 KIAS"). */
internal fun constantSource(c: BriefReading.Constant): String = when (c.source) {
    "plan" -> c.value?.let { "From the plan: $it" } ?: SetupWords.SOURCE.getValue("plan")
    "standard" -> c.value?.let { "Standard: $it" } ?: SetupWords.SOURCE.getValue("standard")
    else -> SetupWords.SOURCE[c.source] ?: SetupWords.WRITE_IN
}
