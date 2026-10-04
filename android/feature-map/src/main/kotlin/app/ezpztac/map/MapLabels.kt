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
/** A doghouse and where its middle is on the screen. */
data class PlacedDoghouse(val box: SceneDoghouse, val at: ScreenPoint)

/** A unit and where its position is on the screen (the symbol's own anchor goes there). */
data class PlacedUnit(val unit: SceneUnit, val at: ScreenPoint)

object MapLabels {
    /** A doghouse is about 60 by 90 dp: it is kept while any of it may be on the screen. */
    private const val DOGHOUSE_MARGIN_PX = 160.0

    /** A unit's symbol and labels reach some way from its position (a designation is drawn beside it). */
    private const val UNIT_MARGIN_PX = 200.0

    /** The units whose symbol may be on the screen, each with its position there. */
    fun units(graphics: GraphicsScene, view: MapProjection): List<PlacedUnit> = graphics.units.mapNotNull { unit ->
        val at = view.toScreen(unit.at)
        if (at.x < -UNIT_MARGIN_PX || at.x > view.widthPx + UNIT_MARGIN_PX || at.y < -UNIT_MARGIN_PX || at.y > view.heightPx + UNIT_MARGIN_PX) null
        else PlacedUnit(unit, at)
    }

    /** The doghouses whose box may be on the screen, each with the place of its middle. */
    fun doghouses(graphics: GraphicsScene, view: MapProjection): List<PlacedDoghouse> = graphics.doghouses.mapNotNull { box ->
        val at = view.toScreen(box.at)
        if (at.x < -DOGHOUSE_MARGIN_PX || at.x > view.widthPx + DOGHOUSE_MARGIN_PX || at.y < -DOGHOUSE_MARGIN_PX || at.y > view.heightPx + DOGHOUSE_MARGIN_PX) null
        else PlacedDoghouse(box, at)
    }

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

/** Draws what is written over the map: units' symbols, the doghouse boxes and the feet between aircraft. It takes no touches: what is under one is still tapped. */
@Composable
fun GraphicLabelsLayer(host: MapHost, graphics: GraphicsScene, modifier: Modifier = Modifier, footprints: UnitFootprints? = null) {
    val camera = host.camera
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (camera == null || size == IntSize.Zero || (graphics.separations.isEmpty() && graphics.doghouses.isEmpty() && graphics.units.isEmpty())) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density, host.bottomPaddingPx.toDouble())
        MapLabels.units(graphics, view).forEach { placed -> UnitMarker(placed, footprints) }
        MapLabels.doghouses(graphics, view).forEach { placed -> PlacedDoghouse(placed, camera.bearingDegrees) }
        MapLabels.separations(graphics, view).forEach { label -> SeparationBadge(label) }
    }
}

/** A doghouse box where it goes, turned by its heading less the map's own turn (a map facing east shows a box that points east at the top). */
@Composable
private fun PlacedDoghouse(placed: PlacedDoghouse, bearingDegrees: Double) {
    Box(
        Modifier
            .offset { IntOffset(placed.at.x.roundToInt(), placed.at.y.roundToInt()) }
            .graphicsLayer {
                translationX = -size.width / 2f
                translationY = -size.height / 2f
                rotationZ = (placed.box.rotationDeg - bearingDegrees).toFloat()
            },
    ) { DoghouseBox(placed.box) }
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
