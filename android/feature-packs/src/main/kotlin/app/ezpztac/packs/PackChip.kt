package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens
import app.ezpztac.missionpacks.PackStatus

/**
 * The workspace chip in a pack (the design's PackLive): the pack's name in its colour, the faces of who has it open (three, then "+N"; one at large text),
 * and the switcher's arrow. One button for a screen reader, which says the pack and who is here ([PackWords.chipDescription]). [onClick] opens the
 * switcher. Given less room than it would take, the name gives way (cut with an ellipsis), never the faces or the arrow.
 */
@Composable
fun PackChip(pack: OpenPackUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 2.dp,
        modifier = modifier.heightIn(min = 32.dp).clearAndSetSemantics { contentDescription = PackWords.chipDescription(pack) },
    ) {
        Row(Modifier.padding(start = Tokens.Spacing.sm.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(EzpzIcons.groupsFill), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            Text(
                pack.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Faces(pack.people, shown = if (LocalDensity.current.fontScale > LARGE_TEXT) 1 else MAX_FACES)
            Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Who has the pack open: [shown] faces, overlapping, then "+N" for the rest. Nothing when nobody is known to be here. */
@Composable
fun Faces(people: List<PersonUi>, modifier: Modifier = Modifier, shown: Int = MAX_FACES) {
    if (people.isEmpty()) return
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    // Side by side, each face's ring (drawn round it) cutting into the one before, as the design draws them: never into its letters.
    Row(modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        people.take(shown).forEach { person ->
            val (back, front) = faceColours(person)
            Face(person.initials, back, front, ring)
        }
        if (people.size > shown) Face("+${people.size - shown}", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, ring)
    }
}

/**
 * A face: 20 dp, its initials, ringed outside in the chip's colour (drawn, so it takes no room). The letters keep their size at any text size: the
 * chip's description names everyone, and larger letters would not fit the face.
 */
@Composable
private fun Face(text: String, back: Color, front: Color, ring: Color) {
    val letters = with(LocalDensity.current) { 10.dp.toSp() }
    Box(
        Modifier.size(20.dp).drawBehind { drawCircle(ring, radius = size.minDimension / 2 + 2.dp.toPx()) }.background(back, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = front, fontSize = letters, lineHeight = letters, letterSpacing = (-0.2).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** A person's face colours, the same for them every time: this person in the primary container, the others among the theme's other containers. */
@Composable
private fun faceColours(person: PersonUi): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    if (person.me) return c.primaryContainer to c.onPrimaryContainer
    val choices = listOf(c.tertiaryContainer to c.onTertiaryContainer, c.secondaryContainer to c.onSecondaryContainer, c.surfaceContainerHighest to c.onSurface)
    return choices[Math.floorMod(person.id ?: person.name.hashCode(), choices.size)]
}

/**
 * How the open pack stands (the design's "Live · all changes synced"), a status a screen reader is told of when it changes: a tick when everything has
 * reached everyone, a dot in the warning colour while changes are on their way or waiting, the error colour when the pack cannot be reached.
 */
@Composable
fun PackStatusLine(pack: OpenPackUi, modifier: Modifier = Modifier) {
    val words = PackWords.status(pack)
    val tone = when {
        pack.status == PackStatus.ERROR || pack.status == PackStatus.GONE -> MaterialTheme.colorScheme.error
        pack.pending > 0 || pack.status == PackStatus.LOADING || pack.status == PackStatus.OFFLINE || pack.status == PackStatus.PAUSED -> EzpzTheme.status.warning
        else -> EzpzTheme.status.success
    }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 2.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.heightIn(min = 28.dp).padding(horizontal = Tokens.Spacing.sm.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tone == EzpzTheme.status.success) {
                Icon(painterResource(EzpzIcons.cloudDone), contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
            } else {
                Box(Modifier.size(8.dp).border(4.dp, tone, CircleShape))
            }
            // Wraps at large text rather than running under the map's buttons (its host bounds it).
            Text(words, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private const val MAX_FACES = 3

/** The text size past which the chip shows one face: the name needs the room. */
private const val LARGE_TEXT = 1.3f
