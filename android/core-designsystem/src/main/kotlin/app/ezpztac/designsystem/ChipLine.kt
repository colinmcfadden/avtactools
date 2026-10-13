package app.ezpztac.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * What a chip on the phone's row holds, on one line: [lead] (its icon), its [name], the first of [extras] (fullest first: a pack's faces, three then one) and an
 * [arrow] (the switcher's), [CHIP_LINE_GAP] apart and centred across. Given less room than it would take, what only adds gives way before the name is cut: it
 * takes the first of its forms in which the name is whole, fullest first: each of [extras] in turn, then none of them when [extrasMayGo] (else the last stays:
 * a finished pack's lock), then the same without the arrow, then, when [leadMayGo], without [lead] too. Where even the last does not leave the name whole, the
 * name is cut (with the ellipsis its text asks for). What a form leaves out is composed and not placed, so it is not drawn; the chip's own semantics say what it
 * is, whatever shows.
 */
@Composable
fun ChipLine(
    lead: @Composable () -> Unit,
    name: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    extras: List<@Composable () -> Unit> = emptyList(),
    arrow: (@Composable () -> Unit)? = null,
    extrasMayGo: Boolean = true,
    leadMayGo: Boolean = false,
) {
    Layout(contents = listOf(lead, name, arrow ?: {}) + extras, modifier = modifier) { parts, constraints ->
        val gap = CHIP_LINE_GAP.roundToPx()
        val probe = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        fun widthOf(measurables: List<Measurable>) = measurables.sumOf { it.maxIntrinsicWidth(probe) }
        val leadWidth = widthOf(parts[0])
        val nameWidth = widthOf(parts[1])
        val arrowWidth = widthOf(parts[2])
        val extraWidths = parts.drop(3).map(::widthOf)
        // The forms, fullest first: which of the extras (-1: none), whether the arrow is there, whether the lead is.
        val left = if (extrasMayGo || extraWidths.isEmpty()) -1 else extraWidths.lastIndex
        val forms = buildList {
            extraWidths.indices.forEach { add(Form(it, arrow = true, lead = true)) }
            if (left == -1) add(Form(-1, arrow = true, lead = true))
            if (arrowWidth > 0) add(Form(left, arrow = false, lead = true))
            if (leadMayGo) add(Form(left, arrow = false, lead = false))
        }
        fun others(form: Form): Int {
            val widths = listOfNotNull(
                leadWidth.takeIf { form.lead && it > 0 },
                extraWidths.getOrNull(form.extra)?.takeIf { it > 0 },
                arrowWidth.takeIf { form.arrow && it > 0 },
            )
            return widths.sum() + gap * widths.size
        }
        val room = constraints.maxWidth
        val unbounded = room == Constraints.Infinity
        val form = forms.firstOrNull { unbounded || others(it) + nameWidth <= room } ?: forms.last()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nameRoom = if (unbounded) nameWidth else (room - others(form)).coerceIn(0, nameWidth)
        val placed = listOfNotNull(
            parts[0].firstOrNull()?.takeIf { form.lead }?.measure(loose),
            parts[1].firstOrNull()?.measure(loose.copy(maxWidth = nameRoom)),
            parts.getOrNull(3 + form.extra)?.takeIf { form.extra >= 0 }?.firstOrNull()?.measure(loose),
            parts[2].firstOrNull()?.takeIf { form.arrow }?.measure(loose),
        ).filter { it.width > 0 }
        val width = (placed.sumOf { it.width } + gap * (placed.size - 1).coerceAtLeast(0)).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placed.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            placed.forEach { item ->
                item.placeRelative(x, (height - item.height) / 2)
                x += item.width + gap
            }
        }
    }
}

/** One of a [ChipLine]'s forms: which of its extras (-1 for none), and whether its arrow and its lead are there. */
private data class Form(val extra: Int, val arrow: Boolean, val lead: Boolean)

/** The gap between what a [ChipLine] holds. */
val CHIP_LINE_GAP = 6.dp
