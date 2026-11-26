package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.HeldMeasure
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.SectionLabel
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.planning.PhaseLines

/** What the *Control measures* cards can do ([MeasuresViewModel]'s, given here so the cards can be tried without it). */
class MeasuresCardActions(
    val hold: (HeldMeasure.Line) -> Unit = {},
    val remove: (HeldMeasure.Line) -> Unit = {},
    val flip: (HeldMeasure.Line) -> Unit = {},
    /** A phase line's new name; why it was refused, in words, or null. */
    val renameLine: (HeldMeasure.Line, String) -> String? = { _, _ -> null },
    /** The set's new name; why it was refused, or null. */
    val renameSet: (String) -> String? = { null },
    val closeSet: () -> Unit = {},
    val keepOnTheirOwn: (setId: String) -> Unit = {},
    val dismissError: () -> Unit = {},
    /** Asks which Mission Pack a copy of a set of the person's own goes into (the web's *Add to Mission Pack…*); null while packs do not run here, or one is open. */
    val addToPack: ((uuid: String, name: String) -> Unit)? = null,
)

internal fun measuresCardActionsOf(viewModel: MeasuresViewModel, addToPack: ((String, String) -> Unit)? = null) = MeasuresCardActions(
    hold = viewModel::holdLine, remove = { viewModel.removeLine(it) }, flip = { viewModel.flipLine(it) }, renameLine = viewModel::renameLine,
    renameSet = viewModel::renameSet, closeSet = viewModel::closeSet, keepOnTheirOwn = viewModel::keepOnTheirOwn, dismissError = viewModel::dismissError,
    addToPack = addToPack,
)

/** The Routes panel's *Control measures* (the web's section of `MeasuresCard`s), over [MeasuresViewModel]; nothing while no set has a line in it. */
@Composable
fun MeasuresSectionHost(
    viewModel: MeasuresViewModel = hiltViewModel(),
    /** Asks which Mission Pack a copy of the control measures goes into: given while packs run here and none is open. */
    onAddToPack: ((uuid: String, name: String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // While a line is being drawn its bar says what went wrong; here only what the cards were asked.
    MeasuresSection(state.cards, measuresCardActionsOf(viewModel, onAddToPack), state.error.takeIf { state.draft == null })
}

/** The cards under *Control measures · n*, n the lines in them all (the web's `measureCount`). */
@Composable
fun MeasuresSection(cards: List<MeasuresCardUi>, actions: MeasuresCardActions, error: String? = null) {
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        SectionLabel("Control measures · ${cards.sumOf { it.lines.size }}")
        error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        cards.forEach { MeasuresCard(it, actions) }
    }
}

/**
 * A set of control measures (renamed and closed here, *Close here* in a pack, where it stays), or the lines an older web saved inside a route set, which says so and
 * offers *Keep on their own*. Each FLOT and phase line is a row: a tap holds it on the map; *Flip*, rename and *Remove* are its own (none in a read-only pack).
 */
@Composable
fun MeasuresCard(card: MeasuresCardUi, actions: MeasuresCardActions) {
    var renaming by rememberSaveable(card.setId) { mutableStateOf<String?>(null) }
    var refused by rememberSaveable(card.setId) { mutableStateOf<String?>(null) }
    // A pack finished while the name was being typed: the field goes, and does not come back by itself if the pack is reopened.
    LaunchedEffect(card.locked) { if (card.locked != null) renaming = null }
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp, bottom = Tokens.Spacing.xs.dp)) {
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!card.attached) {
                    if (card.locked == null) SmallIconButton(EzpzIcons.edit, "Rename the control measures ${card.name}") { refused = null; renaming = card.name }
                    SmallIconButton(EzpzIcons.close, if (card.inPack) "Close the control measures ${card.name} here: they stay in the pack" else "Close the control measures ${card.name}", actions.closeSet)
                }
            }
            Text(card.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            renaming?.let { typed ->
                Column(Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                    val done = {
                        refused = actions.renameSet(typed)
                        if (refused == null) renaming = null
                    }
                    EzpzTextField(
                        value = typed, onValueChange = { refused = null; renaming = it.uppercase() }, label = "Name of these control measures", error = refused,
                        imeAction = ImeAction.Done, onImeAction = done, capitalization = KeyboardCapitalization.Characters,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        TextAction("Save name", onClick = done)
                        TextAction("Cancel", onClick = { renaming = null; refused = null })
                    }
                }
            }
            // A copy into a Mission Pack, as the web's card's menu offers one: of the person's own set, never a pack's or the lines inside a route set.
            val addToPack = actions.addToPack
            if (!card.attached && !card.inPack && addToPack != null) {
                PillButton(
                    "Add to Mission Pack…", onClick = { addToPack(card.setId, card.name) }, icon = EzpzIcons.groups, style = PillStyle.Outlined,
                    modifier = Modifier.padding(top = Tokens.Spacing.sm.dp),
                )
            }
            if (card.attached) {
                Text(
                    "Saved with the route set ${card.name}, from before control measures were kept on their own.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Tokens.Spacing.xs.dp, end = Tokens.Spacing.sm.dp),
                )
                if (card.locked == null) {
                    PillButton(
                        "Keep on their own", onClick = { actions.keepOnTheirOwn(card.setId) }, icon = EzpzIcons.logout, style = PillStyle.Outlined,
                        modifier = Modifier.padding(top = Tokens.Spacing.sm.dp),
                    )
                }
            } else if (!card.inPack) {
                SavesToLibrary(Modifier.padding(top = Tokens.Spacing.xs.dp, end = Tokens.Spacing.sm.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp))
            card.lines.forEach { line -> LineRow(line, card.locked == null, actions) }
        }
    }
}

/** A FLOT's row (its blue, its length, *Flip*, *Remove*) or a phase line's (black on white, `Phase line · 2.4 km`, rename, *Remove*): a tap holds it on the map. */
@Composable
private fun LineRow(row: MeasureLineUi, editable: Boolean, actions: MeasuresCardActions) {
    var renaming by rememberSaveable(row.line.id) { mutableStateOf<String?>(null) }
    var refused by rememberSaveable(row.line.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(editable) { if (!editable) renaming = null }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(onClickLabel = if (row.held) "Put down" else "Hold on the map") { actions.hold(row.line) }
                .semantics { selected = row.held },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(row.isFlot)
            Column(Modifier.weight(1f).padding(start = Tokens.Spacing.md.dp)) {
                Text(
                    row.title, style = MaterialTheme.typography.bodyLarge,
                    color = if (row.held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(row.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editable) {
                if (row.isFlot) {
                    RowIcon(EzpzIcons.refresh, "Flip the scallops of this FLOT to the other side") { actions.flip(row.line) }
                    RowIcon(EzpzIcons.delete, "Remove this FLOT") { actions.remove(row.line) }
                } else {
                    RowIcon(EzpzIcons.edit, "Rename ${row.title}") { refused = null; renaming = row.name.orEmpty() }
                    RowIcon(EzpzIcons.delete, "Remove ${row.title}") { actions.remove(row.line) }
                }
            }
        }
        renaming?.takeIf { editable }?.let { typed ->
            Column(Modifier.padding(bottom = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                val done = {
                    refused = actions.renameLine(row.line, typed)
                    if (refused == null) renaming = null
                }
                EzpzTextField(
                    value = typed,
                    onValueChange = { next ->
                        val upper = next.uppercase()
                        // At most the web's 24 characters after PL, and a "PL " typed in front, which the clean name drops.
                        if (upper.codePointCount(0, upper.length) <= PhaseLines.NAME_MAX + 3 || upper.length < typed.length) {
                            refused = null
                            renaming = upper
                        }
                    },
                    label = "Name after PL", error = refused, imeAction = ImeAction.Done, onImeAction = done, capitalization = KeyboardCapitalization.Characters,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Save name", onClick = done)
                    TextAction("Cancel", onClick = { renaming = null; refused = null })
                }
            }
        }
    }
}

/** The line's colour, as the web's swatch: a FLOT's blue; a phase line's black, ringed in its white casing so it shows on a dark card. */
@Composable
private fun Swatch(flot: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    if (flot) {
        Box(Modifier.size(width = 14.dp, height = 14.dp).background(FLOT_BLUE, shape))
    } else {
        Box(Modifier.size(width = 14.dp, height = 14.dp).border(BorderStroke(1.5.dp, Color.White), shape).padding(1.5.dp).background(PHASE_LINE_BLACK, shape))
    }
}

private val FLOT_BLUE = Color(0xFF2F8FFF)
private val PHASE_LINE_BLACK = Color(0xFF0B0F14)
