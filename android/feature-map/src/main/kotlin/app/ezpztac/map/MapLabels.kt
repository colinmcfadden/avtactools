package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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

    /** The heights of the slope map's contour lines, where the server put each, kept while they may be on the screen. */
    fun contours(slope: SlopeImage?, view: MapProjection): List<MapLabel> = slope?.contours.orEmpty().mapNotNull { piece ->
        val at = view.toScreen(piece.label ?: return@mapNotNull null)
        if (!at.x.isFinite() || !at.y.isFinite()) return@mapNotNull null
        if (at.x < -MARGIN_PX || at.x > view.widthPx + MARGIN_PX || at.y < -MARGIN_PX || at.y > view.heightPx + MARGIN_PX) return@mapNotNull null
        MapLabel(text = piece.elevationFt.toString(), at = at, violating = false, description = "Contour ${piece.elevationFt} feet")
    }

    /** How far under an aircraft's picture its name sits, in points. */
    private const val NAME_GAP_PT = 4.0

    /**
     * The aircraft that have a name (a chalk or tail number), each with where it goes: the middle of its top edge, just under the whole of the aircraft's picture
     * (the frame round it, [AircraftIcons.frame], whose half is as far as the tail reaches from the mast) whichever way it faces or the map is turned, so a name
     * never sits on the rotor or the tail, nor turns with them.
     */
    fun aircraftNames(graphics: GraphicsScene, view: MapProjection): List<MapLabel> = graphics.aircraft.mapNotNull { a ->
        val name = a.name ?: return@mapNotNull null
        val centre = view.toScreen(a.at)
        if (!centre.x.isFinite() || !centre.y.isFinite()) return@mapNotNull null
        val reachM = a.diameterM * AircraftIcons.frame(a.iconKey).widthPerRotorDiameter / 2
        val at = ScreenPoint(centre.x, centre.y + view.metersToPixels(reachM, a.at.lat) + NAME_GAP_PT * view.density)
        if (at.x < -MARGIN_PX || at.x > view.widthPx + MARGIN_PX || at.y < -MARGIN_PX || at.y > view.heightPx + MARGIN_PX) return@mapNotNull null
        MapLabel(text = name, at = at, violating = false, description = name)
    }

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

/**
 * Draws what is written over the map: units' symbols, the doghouse boxes, the feet between aircraft and the aircraft's names. It takes no touches: what is under one is
 * still tapped.
 */
@Composable
fun GraphicLabelsLayer(host: MapHost, graphics: GraphicsScene, modifier: Modifier = Modifier, footprints: UnitFootprints? = null) =
    GraphicLabels({ host.camera }, graphics, modifier, footprints)

@Composable
internal fun GraphicLabels(camera: () -> CameraState?, graphics: GraphicsScene, modifier: Modifier = Modifier, footprints: UnitFootprints? = null) {
    val camera = camera()
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (camera == null || size == IntSize.Zero) return@Box
        if (graphics.separations.isEmpty() && graphics.doghouses.isEmpty() && graphics.units.isEmpty() && graphics.aircraft.none { it.name != null }) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density)
        MapLabels.units(graphics, view).forEach { placed -> UnitMarker(placed, footprints) }
        MapLabels.doghouses(graphics, view).forEach { placed -> PlacedDoghouse(placed, camera.bearingDegrees) }
        MapLabels.separations(graphics, view).forEach { label -> SeparationBadge(label) }
        MapLabels.aircraftNames(graphics, view).forEach { label -> AircraftNameTag(label) }
    }
}

/**
 * An aircraft's name under it ([MapLabels.aircraftNames]): a pill whose top edge is centred on its place. Drawn on a Box, not a Surface, so it takes no touches, as the
 * contours' pills do: it is within a finger's reach of the aircraft at a far zoom, and a tap or a press there is the map's, to hold or pick up the aircraft.
 */
@Composable
private fun AircraftNameTag(label: MapLabel) {
    Box(
        Modifier
            .offset { IntOffset(label.at.x.roundToInt(), label.at.y.roundToInt()) }
            .graphicsLayer { translationX = -size.width / 2f }                           // centred under the aircraft, whatever its width
            .alpha(0.92f)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Tokens.Radius.sm.dp))
            .padding(horizontal = Tokens.Spacing.sm.dp, vertical = 2.dp)
            .semantics { contentDescription = label.description },
    ) {
        Text(label.text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
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
    // A Box, not a Surface, which would take the touches on it: a tap there is the map's, on whatever is under the line.
    Box(
        Modifier
            .offset { IntOffset(label.at.x.roundToInt(), label.at.y.roundToInt()) }
            .graphicsLayer {
                translationX = -size.width / 2f                                     // centred on its place, whatever its width
                translationY = -size.height / 2f
            }
            .alpha(0.92f)
            .background(if (label.violating) scheme.error else scheme.surface, RoundedCornerShape(Tokens.Radius.sm.dp))
            .padding(horizontal = Tokens.Spacing.sm.dp, vertical = Tokens.Spacing.xs.dp)
            .semantics { contentDescription = label.description },
    ) {
        Text(label.text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = if (label.violating) scheme.onError else scheme.onSurface)
    }
}

/**
 * The heights of the slope map's contour lines, over the map where [slope] says ([MapLabels.contours]): 11 dp pills, dark on light, the other way
 * about from the lines they label. Hidden from a screen reader, as the lines are a picture of the ground; it takes no touches.
 */
@Composable
fun ContourLabelsLayer(host: MapHost, slope: SlopeImage?, modifier: Modifier = Modifier) = ContourLabels({ host.camera }, slope, modifier)

/** [ContourLabelsLayer] for the [camera] the map has now (a test hands it one). */
@Composable
internal fun ContourLabels(camera: () -> CameraState?, slope: SlopeImage?, modifier: Modifier = Modifier) {
    val camera = camera()
    val density = LocalDensity.current
    // Not `size`: the pills' graphicsLayer reads its own `size`, which a local of that name would hide.
    var area by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { area = it }.clearAndSetSemantics {}) {
        if (camera == null || area == IntSize.Zero || slope == null || slope.contours.none { it.label != null }) return@Box
        val view = MapProjection(camera, area.width.toDouble(), area.height.toDouble(), density.density.toDouble())
        // The pill keeps its size whatever the person's text size, as the grid's do: it labels a line a few metres from the next.
        val fontSize = with(density) { 11.dp.toSp() }
        MapLabels.contours(slope, view).forEach { label ->
            Box(
                Modifier
                    .offset { IntOffset(label.at.x.roundToInt(), label.at.y.roundToInt()) }
                    .graphicsLayer {
                        translationX = -size.width / 2f
                        translationY = -size.height / 2f
                    }
                    .background(CONTOUR_PILL, RoundedCornerShape(7.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    label.text,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold),
                    color = CONTOUR_TEXT, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

private val CONTOUR_PILL = Color(red = 255, green = 255, blue = 255, alpha = (0.82f * 255).roundToInt())
private val CONTOUR_TEXT = Color(0xFF121212)
