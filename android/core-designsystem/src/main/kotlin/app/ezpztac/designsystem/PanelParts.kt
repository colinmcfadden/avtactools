package app.ezpztac.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The colour role a state chip is drawn in (docs/native-design: Analyzed and Synced green, waiting amber, a conflict red, a hint blue). */
enum class StatusTone { Success, Warning, Error, Info, Neutral }

/** How strongly a state chip is tinted with its tone behind its words, which are in the tone itself (`TokenContrastTest` holds the pair to AA). */
internal const val CHIP_TINT = 0.15f

@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Success -> EzpzTheme.status.success
    StatusTone.Warning -> EzpzTheme.status.warning
    StatusTone.Error -> MaterialTheme.colorScheme.error
    StatusTone.Info -> EzpzTheme.status.accent
    StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * A state chip (the design's: 28 dp, its tone's colour tinted behind words in that colour, a little stronger at the edge). A tick for done, a dot for
 * waiting, the error mark for a conflict, the info mark for a hint, or [icon] in place of any of them; a neutral one is an outline only. The words always say it:
 * colour never does alone.
 */
@Composable
fun StateChip(text: String, tone: StatusTone, modifier: Modifier = Modifier, icon: Int? = null) {
    val color = toneColor(tone)
    val neutral = tone == StatusTone.Neutral
    Surface(
        modifier = modifier.height(28.dp),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        color = if (neutral) Color.Transparent else color.copy(alpha = CHIP_TINT),
        contentColor = color,
        border = BorderStroke(1.dp, if (neutral) MaterialTheme.colorScheme.outline else color.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(start = if (neutral) 10.dp else 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            else when (tone) {
                StatusTone.Success -> Icon(painterResource(EzpzIcons.checkCircleFill), contentDescription = null, modifier = Modifier.size(16.dp))
                StatusTone.Warning -> Box(Modifier.padding(horizontal = 2.dp).size(8.dp).background(color, CircleShape))
                StatusTone.Error -> Icon(painterResource(EzpzIcons.errorFill), contentDescription = null, modifier = Modifier.size(16.dp))
                StatusTone.Info -> Icon(painterResource(EzpzIcons.info), contentDescription = null, modifier = Modifier.size(16.dp))
                StatusTone.Neutral -> Unit
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** A small heading over a group of rows ("Also open", "Recent in Library"), with an [action] at its end if there is one (Browse all). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
    }
}

/**
 * A row of a panel's list (the design's: at least 56 dp, a round 40 dp badge with [icon], the [title] and a [supporting] line, and what can be done at
 * its end). [emphasis] draws the badge in the tonal colour (an open item) rather than the plain one (an item in the Library). With [onClick] the whole
 * row is the target, named [onClickLabel] for a screen reader.
 */
@Composable
fun ListRow(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    emphasis: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Tokens.Spacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(
                if (emphasis) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (emphasis) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            supporting?.invoke()
        }
        trailing()
    }
}

/** How a [PillButton] is drawn: the main action filled, a second one tonal, a third outlined. */
enum class PillStyle { Filled, Tonal, Outlined }

/** The design's button: a 48 dp pill as wide as its words, an icon before them. Disabled, a filled one goes grey, as the design's *Saved* does. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    style: PillStyle = PillStyle.Filled,
    enabled: Boolean = true,
) {
    val padding = PaddingValues(start = if (icon != null) 16.dp else 20.dp, end = 20.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Box(Modifier.size(Tokens.Spacing.sm.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
    val sized = modifier.heightIn(min = 48.dp)
    when (style) {
        PillStyle.Filled -> Button(onClick = onClick, modifier = sized, enabled = enabled, shape = RoundedCornerShape(50), contentPadding = padding, content = content)
        PillStyle.Tonal -> FilledTonalButton(onClick = onClick, modifier = sized, enabled = enabled, shape = RoundedCornerShape(50), contentPadding = padding, content = content)
        PillStyle.Outlined -> OutlinedButton(
            onClick = onClick, modifier = sized, enabled = enabled, shape = RoundedCornerShape(50), contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = EzpzTheme.status.accent),
            // The design's 1 dp outline (`#8a94a1`); Material's own is the fainter outline variant.
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), content = content,
        )
    }
}
