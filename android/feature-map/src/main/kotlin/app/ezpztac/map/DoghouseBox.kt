package app.ezpztac.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color.Black
private val Paper = Color.White

/** What a doghouse's box says: the label in its roof, then its heading (without the degree sign), time as minutes and seconds, distance in km and airspeed in knots. */
internal data class DoghouseFace(
    val label: String,
    val heading: String,
    val minutes: String,
    val seconds: String,
    val distance: String,
    val airspeed: String,
)

/** An LZ/PZ's doghouse, read by a screen reader with what it says. */
@Composable
internal fun DoghouseBox(box: SceneDoghouse, modifier: Modifier = Modifier) = DoghouseBox(
    DoghouseFace(box.label, box.heading, box.minutes, box.seconds, box.distanceKm, box.airspeedKts),
    modifier,
    selected = box.selected,
    description = "Doghouse ${box.label}: heading ${box.heading} degrees, ${box.minutes} minutes ${box.seconds} seconds, ${box.distanceKm} kilometres, ${box.airspeedKts} knots",
)

/**
 * A doghouse as the web draws it (`doghouseBox.js`): a black triangle carrying the label, over a white box with a black border of four rows (heading,
 * time, distance, airspeed) in bold monospace. It is a printed symbol, so it is black and white in every theme, as it is on an LZ card. Drawn upright;
 * the caller turns it. [selected] draws the app's accent round it; with no [description] a screen reader is told nothing of it (its caller decides).
 * A label wider than the roof is cut at its edge, never wrapped.
 */
@Composable
internal fun DoghouseBox(face: DoghouseFace, modifier: Modifier = Modifier, selected: Boolean = false, description: String? = null) {
    val accent = MaterialTheme.colorScheme.primary
    val row = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink, textAlign = TextAlign.Center)
    val unit = row.copy(fontSize = 10.sp)
    Column(
        modifier
            .width(BOX_WIDTH)
            .then(if (selected) Modifier.border(3.dp, accent).padding(3.dp) else Modifier)
            .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().height(TRIANGLE_HEIGHT), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxWidth().height(TRIANGLE_HEIGHT)) {
                val apex = Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(apex, Ink)
            }
            Text(
                face.label,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Paper, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp),
            )
        }
        Column(Modifier.fillMaxWidth().background(Paper).border(2.dp, Ink)) {
            Line { Text("${face.heading}°", style = row) }
            Divider()
            Line { Text("${face.minutes}+${face.seconds}", style = row) }
            Divider()
            Line {
                Text(face.distance, style = row)
                Text(" km", style = unit)
            }
            Divider()
            Line {
                Text(face.airspeed, style = row)
                Text(" kts", style = unit)
            }
        }
    }
}

@Composable
private fun Line(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink))
}

private val BOX_WIDTH = 64.dp
private val TRIANGLE_HEIGHT = 22.dp
private val ROW_HEIGHT = 22.dp
