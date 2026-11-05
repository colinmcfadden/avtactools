package app.ezpztac.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.Tokens
import app.ezpztac.geo.GridShare
import app.ezpztac.geo.PlaceSearch

/** A grid to send: its grid as the app writes it, the point, and a label (an LZ/PZ's name) or "" for none. */
data class GridSharePlace(val mgrs: String, val lat: Double, val lon: Double, val label: String = "")

/** The lines a place is shared as (the web's `shareText`, held to `contracts/fixtures/coords/share.json`). */
internal fun GridSharePlace.text(): String = GridShare.shareText(mgrs, lat, lon, label)

/**
 * The system's share sheet with [place]'s lines as plain text: Quick Share (phone to phone over Bluetooth and Wi-Fi, no signal needed),
 * Bluetooth, messages, Signal, mail, and *Copy*. The person chooses where it goes; the app sends nothing itself.
 */
internal fun shareGrid(context: Context, place: GridSharePlace) {
    val label = GridShare.cleanLabel(place.label)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, place.text())
        putExtra(Intent.EXTRA_SUBJECT, label.ifEmpty { place.mgrs })
    }
    context.startActivity(Intent.createChooser(send, "Share grid").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** What [place] is copied as: its lines, or its link alone. */
internal fun copyClip(what: String, text: String): ClipEntry = ClipEntry(ClipData.newPlainText(what, text))

/**
 * Sends a grid to someone (`docs/GRID_SHARING.md`, options 1 to 3): the grid, its lat/long and its label, through the system's share sheet
 * ([onShare]), or copied ([onCopy] with the lines, or with the link alone), and a QR code of the same lines for another phone's camera, which
 * needs no signal. At [night] the code waits behind *Show QR code*: it is black on white whatever the theme (a camera reads it best so), the
 * brightest thing on a night screen, which would spoil dark adaptation and bloom under NVGs until someone is about to scan it.
 */
@Composable
internal fun GridShareDialog(
    place: GridSharePlace,
    onShare: () -> Unit,
    onCopy: (label: String, text: String) -> Unit,
    onDismiss: () -> Unit,
    night: Boolean = false,
) {
    val label = GridShare.cleanLabel(place.label)
    val text = place.text()
    val qr = remember(text) { GridQr.image(text) }
    var shown by rememberSaveable(text, night) { mutableStateOf(!night) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().semantics { paneTitle = "Share grid" },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                // A phone on its side has room for less than the code and the buttons.
                Modifier.verticalScroll(rememberScrollState()).padding(Tokens.Spacing.xl.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Share grid", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                Text(
                    place.mgrs,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    PlaceSearch.formatLatLong(place.lat, place.lon),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (qr != null && !shown) {
                    OutlinedButton(onClick = { shown = true }, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp)) { Text("Show QR code") }
                } else if (qr != null) {
                    Image(
                        qr,
                        contentDescription = "QR code of ${if (label.isNotEmpty()) "$label, " else ""}${place.mgrs}",
                        // Each module a block of its own: smoothed, the code blurs and a camera reads it less well.
                        filterQuality = FilterQuality.None,
                        // As wide as the dialog allows up to 300 dp, so a long label's denser code keeps its modules big enough to read.
                        modifier = Modifier.padding(top = Tokens.Spacing.sm.dp).widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Text("The QR code could not be drawn.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Another phone's camera reads the code. Quick Share and Bluetooth reach a phone nearby. Neither needs a signal. Unclassified only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = Tokens.Spacing.sm.dp),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    OutlinedButton(onClick = { onCopy("Link", GridShare.gridLink(place.mgrs, label)) }) { Text("Copy link") }
                    OutlinedButton(onClick = { onCopy("Grid", place.text()) }) {
                        Icon(painterResource(EzpzIcons.contentCopy), contentDescription = null, modifier = Modifier.padding(end = Tokens.Spacing.xs.dp))
                        Text("Copy")
                    }
                    Button(onClick = onShare) {
                        Icon(painterResource(EzpzIcons.share), contentDescription = null, modifier = Modifier.padding(end = Tokens.Spacing.xs.dp))
                        Text("Share…")
                    }
                }
            }
        }
    }
}
