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
import androidx.compose.ui.unit.Dp
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
            // Finished (the design's Finished): the outlined icon, the name in the surface's text, "· Finished" after it, and nobody's face.
            Icon(painterResource(if (pack.finished) EzpzIcons.groups else EzpzIcons.groupsFill), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            Text(
                pack.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = if (pack.finished) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (pack.finished && LocalDensity.current.fontScale > LARGE_TEXT) {
                Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            } else if (pack.finished) {
                Text("· Finished", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            } else {
                Faces(pack.people, shown = if (LocalDensity.current.fontScale > LARGE_TEXT) 1 else MAX_FACES)
            }
            Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Who has the pack open, or who is in it: [shown] faces, side by side, then "+N" for the rest. Nothing when there is nobody. [size] is the faces'
 * (20 dp in the chip); with [markHere], someone here now has a dot in the success colour on their face (the design's members row).
 */
@Composable
fun Faces(people: List<PersonUi>, modifier: Modifier = Modifier, shown: Int = MAX_FACES, size: Dp = 20.dp, markHere: Boolean = false) {
    if (people.isEmpty()) return
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    // Side by side, each face's ring (drawn round it) cutting into the one before, as the design draws them: never into its letters.
    Row(modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        people.take(shown).forEach { person -> PersonFace(person, size, ring, markHere) }
        if (people.size > shown) Face("+${people.size - shown}", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, ring, size)
    }
}

/** One person's face, in their colours: [size] across, ringed in [ring]; with [markHere], a dot when they are here now. */
@Composable
internal fun PersonFace(person: PersonUi, size: Dp, ring: Color = MaterialTheme.colorScheme.surfaceContainerLow, markHere: Boolean = false) {
    val (back, front) = faceColours(person)
    Box {
        Face(person.initials, back, front, ring, size)
        if (markHere && person.here) {
            val dot = EzpzTheme.status.success
            Box(Modifier.align(Alignment.BottomEnd).size(size * 0.3f).drawBehind { drawCircle(ring, radius = this.size.minDimension / 2 + 1.5.dp.toPx()) }.background(dot, CircleShape))
        }
    }
}

/**
 * A face: [size] across, its initials, ringed outside in [ring] (drawn, so it takes no room). The letters keep their size at any text size: what
 * the face stands for is said in words beside it, and larger letters would not fit the face.
 */
@Composable
private fun Face(text: String, back: Color, front: Color, ring: Color, size: Dp) {
    val letters = with(LocalDensity.current) { (size * 0.5f).toSp() }
    Box(
        Modifier.size(size).drawBehind { drawCircle(ring, radius = this.size.minDimension / 2 + 2.dp.toPx()) }.background(back, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = front, fontSize = letters, lineHeight = letters, letterSpacing = (-0.2).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** A person's face colours, the same for them every time: this person in the primary container, the others among the theme's other containers. */
@Composable
internal fun faceColours(person: PersonUi): Pair<Color, Color> {
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
    val troubled = pack.status == PackStatus.ERROR || pack.status == PackStatus.GONE
    val waiting = pack.status == PackStatus.LOADING || pack.status == PackStatus.PAUSED
    // Finished it says so (its words come first, as on the web), with a lock in the text's own colour: not the tick of a pack whose changes reach everyone.
    val locked = pack.finished && !troubled && !waiting
    val tone = when {
        troubled -> MaterialTheme.colorScheme.error
        locked -> MaterialTheme.colorScheme.onSurfaceVariant
        pack.pending > 0 || waiting || pack.status == PackStatus.OFFLINE -> EzpzTheme.status.warning
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
            if (locked) {
                Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
            } else if (tone == EzpzTheme.status.success) {
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
