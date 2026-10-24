package app.ezpztac.briefs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.Tokens

/** A card of the setup (the design's: a raised surface with a hairline edge), its [title] a heading. */
@Composable
internal fun SetupCard(title: String?, modifier: Modifier = Modifier, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (hint != null) Hint(hint)
            content()
        }
    }
}

/** A line of explanation under a control or a heading, in the screen's quieter colour. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) app.ezpztac.designsystem.EzpzTheme.status.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** One choice of several, side by side ([options]: value and words), the one chosen marked; [label] names the group for a screen reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(label: String, options: List<Pair<T, String>>, chosen: T?, onChoose: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SingleChoiceSegmentedButtonRow(modifier.semantics { contentDescription = label }) {
        options.forEachIndexed { i, (value, words) ->
            SegmentedButton(
                selected = value == chosen,
                onClick = { onChoose(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(words, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A switch with its words before it, the whole row one control for a screen reader and a touch. */
@Composable
internal fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A button that says what is chosen and opens the other choices ([options]: value and words). Read as one control: [description] and what is chosen ("Role of LZ
 * HAWK, LZ").
 */
@Composable
internal fun <T> MenuButton(text: String, description: String, options: List<Pair<T, String>>, onChoose: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
                contentDescription = description
                stateDescription = text
                role = Role.DropdownList
                if (!enabled) disabled()
                onClick { open = true; true }
            },
            shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(EzpzIcons.expandMore), contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, words) ->
                DropdownMenuItem(text = { Text(words) }, onClick = { open = false; onChoose(value) })
            }
        }
    }
}

/** The setup's words, the web's (`BriefSetup.jsx`, `FlowStep.jsx`, `DetailsStep.jsx`, `ReviewStep.jsx`), where Android says them the same. */
internal object SetupWords {
    const val FLOW_HINT = "The order the flight meets each item. It sets the order the routes and LZ/PZs are briefed in, and the sandtable's steps. Times come from the routes' clocks."
    const val RESET_FLOW = "Reset to route order"
    const val EMPTY_PACK = "Nothing to brief yet: the pack has no LZ/PZs or route sets."
    const val EMPTY_SESSION = "Nothing to brief yet: open the mission's LZ/PZs and route sets in this session, or open its Mission Pack."
    const val ROLE_NOTE = "Each LZ/PZ's role decides where it is briefed. Change it from its badge."
    const val GONE = "No longer in the plan"
    const val GONE_HINT = "Remove it, or open it again."
    const val CONSTANTS = "Mission constants"
    const val CONSTANTS_ASIDE = "Always on screen while briefing"
    const val CONSTANTS_HINT = "Values the whole flight holds for the mission. They print on every page and stay in a box on the sandtable."
    const val ALSO = "Also in the brief"
    const val THREATS_HINT = "Under 2. Situation, each with where it lies from the route"
    const val YOUR_OWN = "+ Your own"

    val SOURCE = mapOf("typed" to "Typed", "plan" to "From the plan", "standard" to "Standard", "blank" to "Prints as a write-in line")
    val ROLE_LABELS = linkedMapOf("PZ" to "PZ", "LZ" to "LZ", "FARP" to "FARP", "HA" to "HA", "LAAGER" to "LAAGER", "FALSE_LZ" to "FALSE LZ")

    const val TYPE_OVER = "Type over it to change what is briefed."
    const val TYPED = "Typed"
    const val WRITE_IN = "Prints as a write-in line"
    const val NOT_FILLED = "Not filled yet: prints as a write-in line."
    const val USE_STANDARD = "Use standard for all"
    const val NOTHING_HERE = "Nothing in the mission flow is briefed here. Give an LZ/PZ this role in step 1 to brief it."
    const val SCRIPT_HINT = "Written from the fields. It prints under the figure and shows on the sandtable. Your edits are kept until you reset it."
    const val RESET_SCRIPT = "Reset script from fields"
    const val RALLY = "Rally points"
    const val RALLY_HINT = "Said with the loss of visual contact, and marked on its figure."

    const val IN_THE_PDF = "In the PDF"
    const val CHECK = "Check before briefing"
    const val CLASSIFICATION = "Every page is marked UNCLASSIFIED. EZ/PZ is for unclassified planning only: no CUI."
    const val MAKING = "Drawing the figures and printing the pages: this takes a few seconds."
    const val FAILED = "The brief could not be put together from the plan as it is now. Close it and open it again; if that does not help, the plan holds something the brief cannot read."
    const val STALE = "The brief changed since this PDF was made."
}
