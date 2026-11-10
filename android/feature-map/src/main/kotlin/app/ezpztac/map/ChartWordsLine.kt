package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

/**
 * What the map says of the charts in view, for [camera] over a map of [size] pixels: worked out once the camera settles, and again at the instant a chart in the build runs
 * out, which turns its line red without anything else having moved. Null until it has been worked out.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberChartLines(charts: VfrCharts, camera: CameraState?, size: IntSize, density: Double, now: () -> Long = System::currentTimeMillis): ChartLines? {
    var lines by remember { mutableStateOf<ChartLines?>(null) }
    val current by rememberUpdatedState(camera)
    LaunchedEffect(charts, size, density) {
        if (size == IntSize.Zero) return@LaunchedEffect
        snapshotFlow { current }.debounce(SETTLE_MS).collectLatest { settled ->
            settled ?: return@collectLatest
            val bounds = MgrsGridScene.viewOf(settled, size.width.toDouble(), size.height.toDouble(), density)?.bounds ?: return@collectLatest
            val view = ChartBox(bounds.west, bounds.south, bounds.east, bounds.north)
            while (true) {
                val at = now()
                // MapLibre's zoom is the web's less one, and the words count in the web's.
                lines = ChartWords.of(charts.live, charts.coverage, view, settled.center, settled.zoom + 1.0, at, levelsFiner(density))
                val next = ChartWords.nextExpiry(charts.live, charts.coverage, at) ?: awaitCancellation()
                delay(next - at)
            }
        }
    }
    return lines
}

/**
 * The charts named, above the readout: a label over the map, on a plain ground (a Material surface would take the touches on it), and nothing that stops the map being used.
 * An expired chart says so in words as well as in red.
 */
@Composable
internal fun ChartWordsLine(lines: ChartLines, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.widthIn(max = MAX_WIDTH)
            .background(scheme.background.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        lines.lines.forEach { line -> Text(line.text, style = style, color = if (line.expired) scheme.error else scheme.onBackground, textAlign = TextAlign.Center) }
        lines.enlarged?.let { Text(it, style = style, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

private const val SETTLE_MS = 200L
private val MAX_WIDTH = 320.dp
