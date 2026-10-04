package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.Tokens
import app.ezpztac.symbols.SymbolOutcome
import app.ezpztac.symbols.SymbolSpec
import app.ezpztac.symbols.rememberSymbol
import kotlin.math.roundToInt

/** A threat and the screen point where its symbol's anchor belongs. */
data class PlacedThreat(val pin: ThreatPin, val at: ScreenPoint)

object ThreatLabels {
    private const val MARGIN_PX = 200.0

    fun placed(scene: ThreatScene, view: MapProjection): List<PlacedThreat> = scene.pins.mapNotNull { pin ->
        val at = view.toScreen(pin.at)
        if (at.x < -MARGIN_PX || at.x > view.widthPx + MARGIN_PX || at.y < -MARGIN_PX || at.y > view.heightPx + MARGIN_PX) null else PlacedThreat(pin, at)
    }
}

/** Draws each threat's MIL-STD-2525C symbol and name over the map. It takes no touches; map hit testing remains in [ThreatHitTest]. */
@Composable
fun ThreatLabelsLayer(host: MapHost, scene: ThreatScene, modifier: Modifier = Modifier) {
    val camera = host.camera
    val density = androidx.compose.ui.platform.LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (camera == null || size == IntSize.Zero || scene.isEmpty) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density, host.bottomPaddingPx.toDouble())
        ThreatLabels.placed(scene, view).forEach { ThreatMarker(it) }
    }
}

@Composable
internal fun ThreatMarker(placed: PlacedThreat) {
    val pin = placed.pin
    if (pin.selected) {
        Box(
            Modifier.offset { IntOffset(placed.at.x.roundToInt(), placed.at.y.roundToInt()) }
                .graphicsLayer { translationX = -size.width / 2f; translationY = -size.height / 2f }
                .size(48.dp).border(3.dp, Color(0xFFFFC107), CircleShape)
                .semantics { contentDescription = "Held threat ${pin.name}" },
        )
    }
    val outcome by rememberSymbol(SymbolSpec(pin.sidc))
    when (val result = outcome) {
        is SymbolOutcome.Drawn -> androidx.compose.foundation.Image(
            bitmap = result.symbol.bitmap.asImageBitmap(), contentDescription = "Threat ${pin.name}",
            modifier = Modifier.offset { IntOffset((placed.at.x - result.symbol.anchorX).roundToInt(), (placed.at.y - result.symbol.anchorY).roundToInt()) },
        )
        // A threat is never missing from the map: the diamond stands in while the symbol is being drawn, and where it cannot be (no JavaScript sandbox and not a
        // preset, or not a symbol). A crew that sees nothing where a threat is would plan through it.
        null, SymbolOutcome.Invalid, SymbolOutcome.Unavailable -> ThreatFallback(placed)
    }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.offset { IntOffset(placed.at.x.roundToInt() + 20.dp.roundToPx(), placed.at.y.roundToInt() - 10.dp.roundToPx()) }.alpha(0.92f),
    ) {
        Text(pin.name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = Tokens.Spacing.xs.dp, vertical = 2.dp), maxLines = 1)
    }
}

@Composable
private fun ThreatFallback(placed: PlacedThreat) {
    Box(
        Modifier.offset { IntOffset(placed.at.x.roundToInt(), placed.at.y.roundToInt()) }
            .graphicsLayer { translationX = -size.width / 2f; translationY = -size.height / 2f }
            .size(20.dp).rotate(45f).background(Color(0xFFEF4444)).border(2.dp, Color.White)
            .semantics { contentDescription = "Threat ${placed.pin.name} (symbol not available)" },
        contentAlignment = Alignment.Center,
    ) {}
}
