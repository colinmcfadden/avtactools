package app.ezpztac.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import app.ezpztac.designsystem.Tokens
import kotlin.math.roundToInt

/** A word laid over the map at a place on the screen. [violating] is a separation that is too small. */
data class MapLabel(val text: String, val at: ScreenPoint, val violating: Boolean, val description: String)

/**
 * The words that go with the planning graphics, worked out for a camera. A raster style has no glyphs, so these are not map text: they are
 * Compose, put where [MapProjection] says a position is, which is the same arithmetic the tap-to-select and drag use.
 */
object MapLabels {
    /** A line shorter than this on the screen has no room for its words; they would sit on the aircraft. */
    const val MIN_LINE_PX = 56.0

    /** How far off the screen a label may be and still be kept, so one that is sliding in is not missing for a frame. */
    private const val MARGIN_PX = 48.0

    /** The feet between each pair of aircraft's rotor edges, at the middle of the line between them. */
    fun separations(graphics: GraphicsScene, view: MapProjection): List<MapLabel> = graphics.separations.mapNotNull { line ->
        if (view.pixelsBetween(line.from, line.to) < MIN_LINE_PX) return@mapNotNull null
        val at = view.toScreen(line.middle)
        if (at.x < -MARGIN_PX || at.x > view.widthPx + MARGIN_PX || at.y < -MARGIN_PX || at.y > view.heightPx + MARGIN_PX) return@mapNotNull null
        MapLabel(
            text = line.label, at = at, violating = line.violating,
            description = if (line.violating) "Rotor edges ${line.label} apart: too close" else "Rotor edges ${line.label} apart",
        )
    }
}

/** Draws [MapLabels.separations] over the map. It takes no touches: what is under a label is still tapped. */
@Composable
fun GraphicLabelsLayer(host: MapHost, graphics: GraphicsScene, modifier: Modifier = Modifier) {
    val camera = host.camera
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (camera == null || size == IntSize.Zero || graphics.separations.isEmpty()) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density, host.bottomPaddingPx.toDouble())
        MapLabels.separations(graphics, view).forEach { label -> SeparationBadge(label) }
    }
}

@Composable
private fun SeparationBadge(label: MapLabel) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        color = if (label.violating) scheme.error else scheme.surface,
        contentColor = if (label.violating) scheme.onError else scheme.onSurface,
        modifier = Modifier
            .offset { IntOffset(label.at.x.roundToInt(), label.at.y.roundToInt()) }
            .graphicsLayer {
                translationX = -size.width / 2f                                     // centred on its place, whatever its width
                translationY = -size.height / 2f
            }
            .alpha(0.92f)
            .semantics { contentDescription = label.description },
    ) {
        Text(label.text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = Tokens.Spacing.sm.dp, vertical = Tokens.Spacing.xs.dp))
    }
}
