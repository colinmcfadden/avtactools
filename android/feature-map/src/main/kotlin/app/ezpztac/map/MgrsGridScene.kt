package app.ezpztac.map

import app.ezpztac.geo.GridBounds
import app.ezpztac.geo.GridBox
import app.ezpztac.geo.GridLabel
import app.ezpztac.geo.GridOptions
import app.ezpztac.geo.GridResult
import app.ezpztac.geo.MgrsGrid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The part of the map the MGRS grid is worked out for ([MgrsGrid.grid]), in its own units: a point (dp), MapLibre's unit, so the grid's
 * 70-point line gap and its label sizes are the web's CSS pixels. [turned] is a camera facing other than north: the lines are then worked out
 * for the box round the turned view, and the labels for [labels], a north-up square inside it (the box's own corners and edges are off the
 * screen), with no line values along its edges, which are not the screen's.
 */
data class MgrsGridView(
    val bounds: GridBounds,
    val metersPerPixel: Double,
    val options: GridOptions,
    val turned: Boolean,
    val labels: MgrsGridLabelView? = null,
)

/** Where a turned map's labels are worked out: a north-up square inside what shows, with what floats over the map carried into its frame. */
data class MgrsGridLabelView(val bounds: GridBounds, val metersPerPixel: Double, val options: GridOptions)

/** How the map's grid is fitted to what shows, and its lines handed to MapLibre. Plain arithmetic, so it is tried without a GPU. */
object MgrsGridScene {
    /** Lines also cover this much of the view's size round it, so a pan shows no bare map before the next redraw (the web's). */
    const val PAD = 0.25

    /** A camera turned less than this faces north, as the compass button counts it. */
    private const val NORTH_DEGREES = 0.5

    /** Labels keep this far off what floats over the map (the web's 4 px round each overlay). */
    private const val AVOID_MARGIN_DP = 4.0

    private const val TILE_POINTS = 512.0
    private const val EARTH_RADIUS = 6378137.0
    private const val MAX_LATITUDE = 85.0511287798

    /**
     * The grid's view for a camera over a [widthPx] by [heightPx] map: from the top of the map to [coveredBottomPx] above its foot (the sheet,
     * which the camera's own padding also leaves out), at [density] pixels a point. [avoidPx] are the boxes, in the map's pixels, of what floats
     * over it (the search bar and chips, the map's buttons, the readout pill, a tool): labels keep off them. Null for a map with no size.
     *
     * The longitudes are not folded into 180: across the antimeridian, or zoomed out to several copies of the world, the bounds run past it,
     * as the web's map gives them and the grid expects.
     */
    fun viewOf(camera: CameraState, widthPx: Double, heightPx: Double, density: Double, coveredBottomPx: Double = camera.paddingBottomPx, avoidPx: List<GridBox> = emptyList()): MgrsGridView? {
        val bottom = heightPx - max(0.0, coveredBottomPx)
        if (widthPx <= 0 || bottom <= 0 || density <= 0 || !camera.zoom.isFinite()) return null
        val screen = Screen(camera, widthPx, heightPx, density)
        val turned = abs(camera.bearingDegrees) >= NORTH_DEGREES
        val corners = listOf(screen.world(0.0, 0.0), screen.world(widthPx, 0.0), screen.world(0.0, bottom), screen.world(widthPx, bottom))
        val bounds = boundsOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
        if (!turned) {
            // The frame's top-left is the screen's: a box on the screen is a box of the frame, in points.
            val avoid = avoidPx.map { box -> GridBox(box.x0 / density, box.y0 / density, box.x1 / density, box.y1 / density).grown() }
            return MgrsGridView(bounds, metersPerPoint(bounds, camera.zoom), GridOptions(pad = PAD, avoid = avoid), turned = false)
        }
        // Turned: the labels from a north-up square inside the circle the view's middle part leaves, which every bearing keeps on the screen,
        // and what floats over the map moved into that square's frame (the box round each one's corners there).
        val centre = screen.world(widthPx / 2, bottom / 2)
        val half = minOf(widthPx, bottom) / 2 / Math.sqrt(2.0) / screen.worldPx
        val west = centre.first - half
        val north = centre.second - half
        val inner = boundsOf(west, north, centre.first + half, centre.second + half)
        val pointsPerWorld = screen.worldPx / density
        val avoid = avoidPx.map { box ->
            val seen = listOf(screen.world(box.x0, box.y0), screen.world(box.x1, box.y0), screen.world(box.x0, box.y1), screen.world(box.x1, box.y1))
                .map { (x, y) -> (x - west) * pointsPerWorld to (y - north) * pointsPerWorld }
            GridBox(seen.minOf { it.first }, seen.minOf { it.second }, seen.maxOf { it.first }, seen.maxOf { it.second }).grown()
        }
        return MgrsGridView(
            bounds, metersPerPoint(bounds, camera.zoom), GridOptions(pad = PAD), turned = true,
            labels = MgrsGridLabelView(inner, metersPerPoint(inner, camera.zoom), GridOptions(avoid = avoid)),
        )
    }

    /** The grid to draw for [view]: turned, its lines for the whole view and its labels from the square inside it, without the lines' values. */
    fun gridFor(view: MgrsGridView): GridResult {
        val grid = MgrsGrid.grid(view.bounds, view.metersPerPixel, view.options)
        val labels = view.labels ?: return grid
        val inner = MgrsGrid.grid(labels.bounds, labels.metersPerPixel, labels.options)
        return grid.copy(labels = inner.labels.filter { it.edge == null })
    }

    private fun GridBox.grown() = GridBox(x0 - AVOID_MARGIN_DP, y0 - AVOID_MARGIN_DP, x1 + AVOID_MARGIN_DP, y1 + AVOID_MARGIN_DP)

    // Bounds from the world's coordinates (0 to 1 across, 0 at the top to 1 at the bottom), the longitudes not folded.
    private fun boundsOf(westX: Double, northY: Double, eastX: Double, southY: Double) = GridBounds(
        south = latitudeOf(southY), west = westX * 360 - 180, north = latitudeOf(northY), east = eastX * 360 - 180,
    )

    // The scale at the middle of the bounds as Web Mercator has it, which is what the grid takes: a point is then exactly a point.
    private fun metersPerPoint(bounds: GridBounds, zoom: Double): Double {
        val middle = mercatorLat((mercatorY(bounds.north) + mercatorY(bounds.south)) / 2)
        return 2 * PI * EARTH_RADIUS * cos(Math.toRadians(middle)) / (TILE_POINTS * Math.pow(2.0, zoom))
    }

    private fun latitudeOf(worldY: Double) = Math.toDegrees(2 * atan(exp((0.5 - worldY.coerceIn(0.0, 1.0)) * 2 * PI)) - PI / 2)

    /** The grid's lines as GeoJSON, each line a feature carrying its kind (`zone`, `major`, `minor`), which picks its layer. */
    fun geoJson(grid: GridResult): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            grid.lines.forEach { line ->
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") { put("kind", line.kind) }
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        put("coordinates", JsonArray(line.points.map { buildJsonArray { add(JsonPrimitive(it.lon)); add(JsonPrimitive(it.lat)) } }))
                    }
                })
            }
        }
    }.toString()

    /**
     * Where a label's pill goes, its top-left corner in pixels, for its anchor on the screen and its size: a line's value just inside the edge
     * it is on (below the top, above the bottom, right of the left, left of the right), a zone or square centred on its point (the web's CSS).
     */
    fun pillTopLeft(label: GridLabel, anchor: ScreenPoint, widthPx: Double, heightPx: Double, gapPx: Double): ScreenPoint = when (label.edge) {
        "top" -> ScreenPoint(anchor.x - widthPx / 2, anchor.y + gapPx)
        "bottom" -> ScreenPoint(anchor.x - widthPx / 2, anchor.y - heightPx - gapPx)
        "left" -> ScreenPoint(anchor.x + gapPx, anchor.y - heightPx / 2)
        "right" -> ScreenPoint(anchor.x - widthPx - gapPx, anchor.y - heightPx / 2)
        else -> ScreenPoint(anchor.x - widthPx / 2, anchor.y - heightPx / 2)
    }

    // A point on the screen to the world's coordinates (0 to 1 across, not folded; 0 at the top to 1 at the bottom): MapProjection's arithmetic.
    private class Screen(camera: CameraState, widthPx: Double, heightPx: Double, density: Double) {
        private val middleX = widthPx / 2
        private val middleY = camera.paddingTopPx + (heightPx - camera.paddingTopPx - camera.paddingBottomPx) / 2
        val worldPx = TILE_POINTS * Math.pow(2.0, camera.zoom) * density
        private val centerX = (camera.center.lon + 180) / 360
        private val centerY = 0.5 - ln(tan(PI / 4 + Math.toRadians(camera.center.lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)) / 2)) / (2 * PI)
        private val bearing = Math.toRadians(camera.bearingDegrees)

        fun world(x: Double, y: Double): Pair<Double, Double> {
            val sx = x - middleX
            val sy = y - middleY
            val ux = sx * cos(bearing) - sy * sin(bearing)
            val uy = sx * sin(bearing) + sy * cos(bearing)
            return (centerX + ux / worldPx) to (centerY + uy / worldPx)
        }
    }

    private fun mercatorY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(min(MAX_LATITUDE, max(-MAX_LATITUDE, lat))) / 2))
    private fun mercatorLat(y: Double) = Math.toDegrees(2 * atan(exp(y)) - PI / 2)
}
