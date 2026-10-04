package app.ezpztac.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.model.Sidc
import app.ezpztac.model.SymbolPresets
import app.ezpztac.symbols.SymbolOutcome
import app.ezpztac.symbols.SymbolSpec
import app.ezpztac.symbols.rememberSymbol
import kotlin.math.roundToInt

/**
 * A unit where it stands: its MIL-STD-2525C symbol, the way milsymbol draws it (so the designation and the formation above it are in the
 * picture), with the symbol's own anchor on the unit's position. A symbol is not always the middle of its picture (a headquarters symbol
 * stands on the end of its staff), which is why the anchor is milsymbol's and not a guess.
 *
 * While the symbol is being drawn nothing shows; where it cannot be drawn (the device has no JavaScript sandbox and it is not a preset, or it
 * is not a symbol) a plain marker in the affiliation's colour stands in, so the unit is never missing from the map.
 */
@Composable
internal fun UnitMarker(placed: PlacedUnit, footprints: UnitFootprints? = null) {
    val unit = placed.unit
    val sidc = unit.sidc
    if (sidc == null) {                                                    // an older unit that was an image, not a symbol
        UnitPlaceholder(unit, placed.at)
        return
    }
    val outcome by rememberSymbol(SymbolSpec(sidc, unit.uniqueDesignation, unit.higherFormation))
    when (val result = outcome) {
        null -> Unit
        is SymbolOutcome.Drawn -> {
            val symbol = result.symbol
            // Where the picture is, for a finger to hit: the unit's position is the symbol's anchor, which is often well away from the frame a person presses.
            SideEffect {
                footprints?.report(
                    unit.ref,
                    UnitFootprints.Box(-symbol.anchorX.toDouble(), -symbol.anchorY.toDouble(), symbol.bitmap.width - symbol.anchorX.toDouble(), symbol.bitmap.height - symbol.anchorY.toDouble()),
                )
            }
            Image(
                bitmap = symbol.bitmap.asImageBitmap(), contentDescription = describe(unit),
                modifier = Modifier.offset { IntOffset((placed.at.x - symbol.anchorX).roundToInt(), (placed.at.y - symbol.anchorY).roundToInt()) },
            )
        }
        SymbolOutcome.Invalid, SymbolOutcome.Unavailable -> UnitPlaceholder(unit, placed.at)
    }
}

internal fun describe(unit: SceneUnit): String {
    val affiliation = SymbolPresets.affiliations.firstOrNull { it.id == Sidc.affiliation(unit.sidc) }?.label ?: "Unknown"
    val name = unit.uniqueDesignation.ifBlank { null }
    return listOfNotNull("$affiliation unit", name, unit.higherFormation.ifBlank { null }?.let { "of $it" }).joinToString(" ")
}

/** A plain marker for a unit whose symbol cannot be drawn: the affiliation's colour, black edge, and as much of the designation as fits. */
@Composable
internal fun UnitPlaceholder(unit: SceneUnit, at: ScreenPoint) {
    val color = SymbolPresets.affiliations.firstOrNull { it.id == Sidc.affiliation(unit.sidc) }?.color?.let(::parseColor) ?: Color(0xFFEAB308)
    Box(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .graphicsLayer {
                translationX = -size.width / 2f
                translationY = -size.height / 2f
            }
            .size(width = 44.dp, height = 30.dp)
            .background(color.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .border(2.dp, Color.Black, RoundedCornerShape(4.dp))
            .semantics { contentDescription = "${describe(unit)} (symbol not available)" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            unit.uniqueDesignation.ifBlank { "UNIT" }, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(horizontal = 2.dp),
            style = TextStyle(color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
    }
}

/** `#rrggbb` as the web writes the affiliation colours. */
internal fun parseColor(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
