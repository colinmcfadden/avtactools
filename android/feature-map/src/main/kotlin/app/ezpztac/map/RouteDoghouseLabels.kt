package app.ezpztac.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.ezpztac.model.LatLon
import app.ezpztac.planning.LegDoghouse
import app.ezpztac.planning.RouteDoghouses
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A leg's doghouse on the screen: where its box's middle is (px), how far it is turned there and how large it is drawn. */
data class PlacedLegDoghouse(val doghouse: LegDoghouse, val middle: ScreenPoint, val rotationDeg: Double, val scale: Double)

/**
 * Where each leg's doghouse goes for a camera ([RouteDoghouses]): turned to its leg's course less the map's own turn, drawn smaller where its leg is short on
 * the screen (MapLibre's zoom is Leaflet's less one, so the web's size at a zoom is had at that zoom plus one), and moved off the line in its own turned frame by
 * its offset, which shrinks with it, as the web's marker does. Plain arithmetic on [MapProjection], so it is tried without a map.
 */
object RouteDoghouseLabels {
    /** A box is about 64 by 117 dp and stands up to about 75 dp off its point: it is kept while any of it may be on the screen. */
    private const val MARGIN_DP = 150.0

    fun placed(doghouses: List<LegDoghouse>, view: MapProjection, camera: CameraState): List<PlacedLegDoghouse> {
        val margin = MARGIN_DP * view.density
        val scale0 = camera.zoom + 1
        return doghouses.mapNotNull { doghouse ->
            val at = view.toScreen(LatLon(doghouse.lat, doghouse.lon))
            if (at.x < -margin || at.x > view.widthPx + margin || at.y < -margin || at.y > view.heightPx + margin) return@mapNotNull null
            val scale = RouteDoghouses.scaleAt(doghouse.lengthPx0, scale0)
            val turn = doghouse.rotation - camera.bearingDegrees
            val rad = turn * PI / 180
            val x = doghouse.offset.x * scale * view.density
            val y = doghouse.offset.y * scale * view.density
            PlacedLegDoghouse(doghouse, ScreenPoint(at.x + x * cos(rad) - y * sin(rad), at.y + x * sin(rad) + y * cos(rad)), turn, scale)
        }
    }
}

/**
 * A doghouse on each leg of the routes shown, over the map ([RouteDoghouseLabels]). It takes no touches (the routes' lines and points under them keep theirs),
 * and says nothing to a screen reader: the route's plan says the same of each leg in words.
 */
@Composable
fun RouteDoghousesLayer(host: MapHost, doghouses: List<LegDoghouse>, modifier: Modifier = Modifier) = LegDoghouses({ host.camera }, doghouses, modifier)

/** [RouteDoghousesLayer] for the [camera] the map has now (a test hands it one). */
@Composable
internal fun LegDoghouses(camera: () -> CameraState?, doghouses: List<LegDoghouse>, modifier: Modifier = Modifier) {
    val camera = camera()
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }.clearAndSetSemantics { }) {
        if (camera == null || size == IntSize.Zero || doghouses.isEmpty()) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density)
        for (placed in RouteDoghouseLabels.placed(doghouses, view, camera)) {
            key(placed.doghouse.key) { PlacedLegDoghouseBox(placed) }
        }
    }
}

/** A leg's box with its middle where [placed] says, turned and sized about it (a composable of its own, so the layer's size is the box's). */
@Composable
private fun PlacedLegDoghouseBox(placed: PlacedLegDoghouse) {
    val box = placed.doghouse.box
    Box(
        Modifier
            .offset { IntOffset(placed.middle.x.roundToInt(), placed.middle.y.roundToInt()) }
            .graphicsLayer {
                translationX = -size.width / 2f
                translationY = -size.height / 2f
                rotationZ = placed.rotationDeg.toFloat()
                scaleX = placed.scale.toFloat()
                scaleY = placed.scale.toFloat()
            },
    ) { DoghouseBox(DoghouseFace(box.id, box.heading, box.minutes, box.seconds, box.distance, box.airspeed)) }
}
