package app.ezpztac.briefs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.brief.BriefEdits
import app.ezpztac.brief.BriefItem
import app.ezpztac.brief.BriefParagraph
import app.ezpztac.brief.BriefStandards
import app.ezpztac.brief.CompileBrief
import app.ezpztac.data.BriefState
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Setup, step 2 (the web's `DetailsStep`): the brief's details, a paragraph at a time. Each item shows what the plan gives it, and anything left blank prints as a line
 * to write on. A contingency is Standard (the handbook's procedure, with the person's own values where it has them), Custom (their words) or Blank. Beside it the
 * script of the step being filled (the one a field has the focus in, as the web's section focus), which the person may rewrite; their words are kept until they
 * reset it. Narrower than [WIDE] (a tablet split, a foldable) the script goes under the answers, which would otherwise be a column of words a few letters wide.
 *
 * Every row is keyed by what it holds (a step, an answer, a field of a procedure, a rally point), as the web keys them: a field's typed text belongs to its answer,
 * so a paragraph shown, a format chosen or a flow reordered by someone else never carries it to another.
 */
@Composable
internal fun DetailsStepContent(
    brief: BriefState,
    edit: ((JsonObject) -> JsonObject) -> Unit,
    refreshWeather: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val template = brief.format.template
    var current by rememberSaveable(template.id) { mutableStateOf(template.paragraphs.firstOrNull()?.number) }
    var stepId by rememberSaveable(template.id) { mutableStateOf<String?>(null) }
    val paragraph = template.paragraphs.firstOrNull { it.number == current } ?: template.paragraphs.firstOrNull() ?: return
    val stepObjects = BriefReading.stepObjects(brief.model)
    val steps = BriefReading.steps(brief.model)
    val ids = paragraph.sections.map { it.id }.toSet()
    val shownSteps = steps.filter { st -> st.sections.any { it in ids } }
    val shown = shownSteps.firstOrNull { it.id == stepId } ?: shownSteps.firstOrNull()
    val procedures = paragraph.sections.flatMap { s -> s.items.filter { it.kind == "procedure" && it.standard != null }.map { s.id to it.id } }
    val hasRally = paragraph.sections.any { s -> s.facts.orEmpty().contains("rally.points") }
    val weathered = paragraph.sections.any { s -> s.items.any { i -> i.bind?.startsWith("weather.") == true || i.bind?.startsWith("notams.") == true } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
            Column(Modifier.width(if (wide) 240.dp else 200.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp)) {
                template.paragraphs.forEach { p ->
                    val status = BriefEdits.statusOf(p, stepObjects)
                    ParagraphRow(p, status, p.number == paragraph.number) {
                        current = p.number
                        stepId = null
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${paragraph.number}. ${paragraph.title}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Hint("Briefed by ${paragraph.who ?: template.defaultWho}")
                    }
                    if (procedures.isNotEmpty()) {
                        val paragraphSteps = stepObjects.filter { s -> (s["sections"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull in ids } }
                        TextButton(onClick = { edit(BriefEdits.useStandardForAll(paragraphSteps, procedures)) }) { Text(SetupWords.USE_STANDARD) }
                    }
                }
                if (weathered) WeatherLine(brief, refreshWeather, zone)
                if (shownSteps.isEmpty()) Text(SetupWords.NOTHING_HERE, style = MaterialTheme.typography.bodyMedium)
                shownSteps.forEach { st ->
                    key(st.id) {
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (st.id == shown?.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface, RoundedCornerShape(Tokens.Radius.md.dp))
                                // The script beside is the step being filled.
                                .onFocusChanged { if (it.hasFocus) stepId = st.id }
                                .padding(Tokens.Spacing.md.dp),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                        ) {
                            TextButton(onClick = { stepId = st.id }) {
                                Text("${st.number} ${st.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            }
                            st.items.forEach { item ->
                                val section = paragraph.sections.firstOrNull { item.id.startsWith("${it.id}.") }
                                val def = section?.items?.firstOrNull { "${section.id}.${it.id}" == item.id }
                                if (def != null) key(item.key) { AnswerItem(item, def, BriefEdits.answerOf(brief.doc, item.key), brief.format.standards, edit) }
                            }
                        }
                    }
                }
                if (hasRally) RallyPoints(brief, edit)
                if (!wide && shown != null) key(shown.id) { ScriptPanel(shown, brief, edit) }
            }
            if (wide) {
                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp)) {
                    if (shown != null) key(shown.id) { ScriptPanel(shown, brief, edit) }
                }
            }
        }
    }
}

/** How wide the step must be for the script to stand beside the answers. */
internal val WIDE = 1000.dp

@Composable
private fun ParagraphRow(paragraph: BriefParagraph, status: BriefEdits.Status, current: Boolean, onClick: () -> Unit) {
    val (tone, words) = when (status) {
        BriefEdits.Status.NothingToFill -> MaterialTheme.colorScheme.onSurfaceVariant to "Nothing to fill"
        BriefEdits.Status.AllSet -> EzpzTheme.status.success to "All set"
        is BriefEdits.Status.ToAdd -> EzpzTheme.status.warning to "${status.count} to add"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(Tokens.Radius.md.dp))
            .clickable(onClick = onClick).semantics { selected = current }
            .padding(horizontal = Tokens.Spacing.md.dp, vertical = Tokens.Spacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(paragraph.number, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(paragraph.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            Text(words, style = MaterialTheme.typography.bodySmall, color = tone)
        }
    }
}

@Composable
private fun WeatherLine(brief: BriefState, refresh: () -> Unit, zone: ZoneId) {
    val words = when {
        brief.weather.loading -> "Asking for the weather at the planned times…"
        brief.weather.fetchedAt != null -> "Weather at the planned times, fetched ${DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(brief.weather.fetchedAt!!))}"
        else -> "No weather yet"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(words, Modifier.weight(1f))
        TextButton(onClick = refresh, enabled = !brief.weather.loading) { Text("Refresh weather") }
    }
}

@Composable
private fun AnswerItem(item: BriefReading.Item, def: BriefItem, answer: JsonObject?, standards: BriefStandards, edit: ((JsonObject) -> JsonObject) -> Unit) {
    val key = item.key
    val fromPlan = if (item.source == "plan") item.text else null
    when (def.kind) {
        "info" -> Hint("${def.label}: nothing to fill.")
        "procedure" -> ProcedureItem(item, def, answer, standards, edit)
        else -> {
            val typedValue = answer?.get("value")
            // What the plan gives is said where it can be read without typing (the web's placeholder): an empty field shows its label, not its placeholder.
            val hint = when {
                typedValue != null && typedValue != JsonPrimitive("") && (typedValue as? JsonArray)?.isEmpty() != true -> SetupWords.TYPED
                fromPlan != null -> "From the plan: $fromPlan\n${SetupWords.TYPE_OVER}"
                else -> SetupWords.WRITE_IN
            }
            when (def.kind) {
                "choice" -> Column {
                    Text(def.label, style = MaterialTheme.typography.bodyMedium)
                    val chosen = (typedValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                    MenuButton(
                        text = chosen.ifEmpty { "Write-in" },
                        description = def.label,
                        options = listOf("" to "Write-in") + def.options.orEmpty().map { it to it },
                        onChoose = { option -> edit(BriefEdits.setChoice(key, option)) },
                    )
                    Hint(hint)
                }
                "list" -> DraftField(
                    value = (typedValue as? JsonArray)?.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty(),
                    onCommit = { typed -> edit(BriefEdits.setList(key, typed)) },
                    label = def.label,
                    placeholder = fromPlan?.split("; ")?.joinToString("\n") ?: "One a line",
                    supporting = hint,
                    multiline = true,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> DraftField(
                    value = (typedValue as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    onCommit = { typed -> edit(BriefEdits.setText(key, typed)) },
                    label = def.label,
                    placeholder = fromPlan ?: if (def.kind == "time") "HH:MM" else "Write-in",
                    supporting = hint,
                    multiline = def.kind == "note",
                    minLines = if (def.kind == "note") 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProcedureItem(item: BriefReading.Item, def: BriefItem, answer: JsonObject?, standards: BriefStandards, edit: ((JsonObject) -> JsonObject) -> Unit) {
    val procedure = def.standard?.let { standards.procedures[it] }
    val mode = (answer?.get("mode") as? JsonPrimitive)?.contentOrNull ?: "blank"
    val fields = answer?.get("fields") as? JsonObject
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text(def.label, style = MaterialTheme.typography.bodyLarge)
        val options = buildList {
            if (procedure != null) add("standard" to "Standard")
            add("custom" to "Custom")
            add("blank" to "Blank")
        }
        Choice(def.label, options, mode, onChoose = { chosen -> edit(BriefEdits.setMode(item.key, chosen)) })
        when (mode) {
            "standard" -> if (procedure != null) {
                Hint(BriefEdits.summaryOf(procedure, standards, fields))
                procedure.fields.orEmpty().forEach { f ->
                    key(f.id) {
                        DraftField(
                            value = (fields?.get(f.id) as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            onCommit = { typed -> edit(BriefEdits.setProcedureField(item.key, f.id, typed)) },
                            label = f.label,
                            placeholder = "Write-in",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            "custom" -> DraftField(
                value = (answer?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                onCommit = { typed -> edit(BriefEdits.setProcedureText(item.key, typed)) },
                label = "${def.label}, in your words",
                placeholder = "In your words",
                multiline = true,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> Hint(SetupWords.WRITE_IN)
        }
        if (item.writeIn && mode != "blank") Hint(SetupWords.NOT_FILLED, warn = true)
    }
}

@Composable
private fun RallyPoints(brief: BriefState, edit: ((JsonObject) -> JsonObject) -> Unit) {
    val sets = (brief.inputs.plan["routeSets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val routes = sets.flatMap { set ->
        val setId = (set["id"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
        ((set["data"] as? JsonObject)?.get("routes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { r ->
            val routeId = r["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
            val points = (r["points"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["kind"] as? JsonPrimitive)?.contentOrNull != "shaping" && (it["id"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }
            Triple("route:$setId/$routeId", (r["name"] as? JsonPrimitive)?.contentOrNull, points)
        }
    }
    if (routes.isEmpty()) return
    SetupCard(SetupWords.RALLY, hint = SetupWords.RALLY_HINT) {
        routes.forEach { (ref, name, points) ->
            key(ref) {
                Text(name ?: "ROUTE", style = MaterialTheme.typography.labelLarge)
                points.forEach { p ->
                    val pointId = (p["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val pointName = (p["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().removePrefix(".").ifEmpty { "Point" }
                    val label = BriefEdits.rallyLabel(brief.doc, ref, pointId)
                    key(pointId) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pointName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            MenuButton(
                                text = when (label) { "inbound" -> "Inbound"; "outbound" -> "Outbound"; else -> "Not a rally point" },
                                description = "Rally point at $pointName",
                                options = listOf("" to "Not a rally point", "inbound" to "Inbound", "outbound" to "Outbound"),
                                onChoose = { chosen -> edit(BriefEdits.setRally(ref, pointId, chosen)) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ScriptPanel(step: BriefReading.Step, brief: BriefState, edit: ((JsonObject) -> JsonObject) -> Unit) {
    var editing by rememberSaveable(step.id) { mutableStateOf(false) }
    // A reset closes the editor with what was typed in it let go: committed as it closed, it would put the words back.
    var resetting by remember(step.id) { mutableStateOf(false) }
    val edited = BriefEdits.scriptEdited(brief.doc, step.id)
    SetupCard("Script · ${step.number}", hint = SetupWords.SCRIPT_HINT) {
        TextButton(onClick = {
            resetting = false
            editing = !editing
        }) { Text(if (editing) "Done" else "Edit") }
        if (editing) {
            val words = (brief.doc["scripts"] as? JsonObject)?.get(step.id)?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: step.script.joinToString("\n\n") { parts -> parts.joinToString("") { if (it.writeIn) CompileBrief.WRITE_IN else it.text } }
            DraftField(
                value = words,
                onCommit = { typed -> edit(BriefEdits.setScript(step.id, typed)) },
                label = "The script",
                multiline = true,
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
                discarded = { resetting },
            )
        } else {
            Text("${step.who}:", style = MaterialTheme.typography.labelLarge)
            step.script.forEach { parts -> Text(BriefReading.sentence(parts), style = MaterialTheme.typography.bodyMedium) }
        }
        if (edited) {
            TextButton(onClick = {
                resetting = true
                editing = false
                edit(BriefEdits.setScript(step.id, null))
            }) { Text(SetupWords.RESET_SCRIPT) }
        }
    }
}
