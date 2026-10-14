package app.ezpztac.geo

import app.ezpztac.model.LatLon
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** A view's edges in degrees, as a map gives them: the longitudes may run past 180 (across the antimeridian, or several copies of the world). */
public data class GridBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** A box in pixels from the view's top-left: [x0], [y0] its top-left corner, [x1], [y1] its bottom-right. */
public data class GridBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

/**
 * How [MgrsGrid.grid] works: [pad] is the fraction of the view's size round it the lines also cover (so a pan shows no bare map before the
 * next redraw), [avoid] the boxes labels keep off (what floats over the map), and the caps on lines, points and labels; [tolerancePx] how
 * close a line's polyline keeps to its true curve.
 */
public data class GridOptions(
    val pad: Double = 0.0,
    val avoid: List<GridBox> = emptyList(),
    val maxLines: Int = 1500,
    val maxPoints: Int = 60000,
    val maxLabels: Int = 250,
    val tolerancePx: Double = 0.25,
)

/** One grid line: a zone or band boundary, a 100 km line, or a finer one. */
public data class GridLine(
    /** `zone`, `major` (100 km) or `minor`. */
    val kind: String,
    /** The zone it is worked out in; null for a zone or band boundary. */
    val zone: Int?,
    /** `easting`, `northing`, or for a boundary `parallel` or `meridian`. */
    val axis: String,
    /** Its easting or northing in metres (a southern northing with its false northing), or a boundary's latitude or longitude. */
    val value: Double,
    val points: List<LatLon>,
)

/** One label: a grid zone designator, a 100 km square, or a line's principal digits at an edge of the view. */
public data class GridLabel(
    /** `gzd`, `square`, `easting` or `northing`. */
    val kind: String,
    /** Which edge a line's value sits inside: `top`, `bottom`, `left` or `right`; null for a zone or square. */
    val edge: String?,
    val text: String,
    val zone: Int?,
    /** A line's value in metres; null for a zone or square. */
    val value: Double?,
    /** Where it is anchored: a line's value on the edge, a zone's or square's label at its middle. */
    val lat: Double,
    val lon: Double,
    /** The pixels it takes, from the view's top-left. */
    val box: GridBox,
)

/** What to draw: [spacing] the finest line spacing drawn (null for zone boundaries only), the lines and labels, and the levels [dropped] at the caps. */
public data class GridResult(
    val spacing: Double?,
    val lines: List<GridLine>,
    val labels: List<GridLabel>,
    val dropped: List<String>,
) {
    public companion object {
        public val EMPTY: GridResult = GridResult(null, emptyList(), emptyList(), emptyList())
    }
}

/**
 * The MGRS grid for one map view, as plain data: the web's `feature/mgrsGrid/mgrsGrid.js` function by function, held to
 * `contracts/fixtures/mgrs/grid.json`.
 *
 * Every line is worked out in its own UTM zone and clipped to that zone, so it agrees with the crosshair's readout ([MgrsConverter.toMgrs])
 * wherever it is drawn: a point on the 66 km easting line of 16S GD reads 66000. That matters most where the zones are not plain 6 degree
 * strips (Norway's 32V and Svalbard's 31X to 37X) and at a zone's edge, where the neighbouring zone's lines stop. The map is Web Mercator,
 * where a line of constant easting or northing is a curve: each line is sampled until its polyline is within [GridOptions.tolerancePx] of
 * the true curve at the view's scale, and its ends are found on the zone's or the view's edge, so the lines of two neighbouring zones meet
 * on their boundary and never cross it.
 *
 * **Not like the web**, where it would throw: a point the projection has no answer for (outside 80 S to 84 N) is a line or label that is not
 * drawn, never the app's end. Nothing the grid asks for falls there.
 */
public object MgrsGrid {
    private const val SOUTH_LIMIT = -80.0                 // UTM's range; the polar caps are UPS, not drawn
    private const val NORTH_LIMIT = 84.0
    private const val MERCATOR_LIMIT = 85.0511287798      // where Web Mercator's square world ends
    private const val EARTH_RADIUS = 6378137.0            // Web Mercator's sphere
    private const val SQUARE_M = 100000.0
    private const val FALSE_NORTHING_SOUTH = 10000000.0
    private const val RAD = Math.PI / 180
    private const val MAX_COPIES = 8                      // copies of the world side by side when zoomed far out
    private const val MAX_DEPTH = 14                      // halvings of a line while sampling it
    private const val EDGE_M = 1e-3                       // a line's end is found on an edge to a millimetre

    /** Line spacings, finest first. Finer than 100 m is never drawn. */
    public val GRID_SPACINGS: List<Double> = listOf(100.0, 1000.0, 10000.0, 100000.0)

    /** Neighbouring lines are at least this far apart on screen. */
    public const val MIN_LINE_GAP_PX: Double = 70.0

    /** Narrower than this on screen, zones are not drawn at all: the map would be all lines. */
    public const val MIN_ZONE_PX: Double = 12.0

    // Labels are drawn as 11 px pills; these sizes keep them apart.
    internal const val CHAR_PX = 7.0
    internal const val PAD_PX = 10.0
    internal const val HEIGHT_PX = 17.0
    private const val GAP_PX = 3.0
    internal const val EDGE_PX = 4.0
    private const val CORNER_PX = 36.0
    private const val SIDE_PX = 40.0
    private val PRINCIPAL_DIGITS = mapOf(10000.0 to 1, 1000.0 to 2, 100.0 to 3)

    private fun clamp(value: Double, lo: Double, hi: Double) = min(hi, max(lo, value))

    /** The finest spacing whose lines are at least [MIN_LINE_GAP_PX] apart, or null for zones only. */
    public fun gridSpacing(metersPerPixel: Double): Double? {
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0) return null
        return GRID_SPACINGS.firstOrNull { it / metersPerPixel >= MIN_LINE_GAP_PX }
    }

    /** A line's value in the digits a grid reference gives it at this spacing: 66 km at 1 km is "66". */
    public fun principalDigits(metres: Double, spacing: Double): String {
        val within = ((metres % SQUARE_M) + SQUARE_M) % SQUARE_M
        return floor(within / spacing).toLong().toString().padStart(PRINCIPAL_DIGITS[spacing] ?: 1, '0')
    }

    /** A label's width in pixels for its text. */
    internal fun labelWidth(text: String): Double = text.length * CHAR_PX + PAD_PX

    // -- Grid zones -------------------------------------------------------------------------------

    // Band edges: 8 degrees from 80 S, with band X running 72 to 84 N.
    private val BAND_EDGES: List<Double> = List(20) { -80.0 + 8 * it } + 84.0

    private class Cell(val zone: Int, val band: Char, val south: Double, val north: Double, val west: Double, val east: Double)

    /**
     * Every grid zone cell (a zone within one latitude band) with its longitude range, read off [MgrsConverter.zoneFor] itself so the Norway and
     * Svalbard exceptions are exactly the readout's. Their edges all fall on whole degrees, and within a band the zone depends only on longitude.
     */
    private val GRID_ZONES: List<Cell> = buildList {
        for (b in 0 until 20) {
            val south = BAND_EDGES[b]
            val north = BAND_EDGES[b + 1]
            val middle = (south + north) / 2
            var west = -180.0
            var zone: Int? = MgrsConverter.zoneFor(middle, -179.5)
            for (lon in -179..180) {
                val next = if (lon < 180) MgrsConverter.zoneFor(middle, lon + 0.5) else null
                if (next != zone) {
                    add(Cell(zone!!, MgrsConverter.bandFor(middle), south, north, west, lon.toDouble()))
                    west = lon.toDouble()
                    zone = next
                }
            }
        }
    }

    private class Strip(val zone: Int, val south: Double, var north: Double, val west: Double, val east: Double)

    // A zone's cells merged where the longitude range runs unchanged from band to band: the stretches a grid line can run along without leaving its zone.
    private val STRIPS: List<Strip> = run {
        val strips = mutableListOf<Strip>()
        val last = HashMap<Int, Strip>()
        GRID_ZONES.forEach { cell ->
            val previous = last[cell.zone]
            if (previous != null && previous.north == cell.south && previous.west == cell.west && previous.east == cell.east) {
                previous.north = cell.north
                return@forEach
            }
            val strip = Strip(cell.zone, cell.south, cell.north, cell.west, cell.east)
            strips += strip
            last[cell.zone] = strip
        }
        strips
    }

    // The meridians that are zone boundaries in each band.
    private val BAND_MERIDIANS: List<List<Double>> = BAND_EDGES.dropLast(1).map { south ->
        (GRID_ZONES.filter { it.south == south }.map { it.west } + 180.0).toSortedSet().toList()
    }

    // -- The view ---------------------------------------------------------------------------------

    private fun mercatorY(lat: Double) = ln(tan(Math.PI / 4 + (clamp(lat, -MERCATOR_LIMIT, MERCATOR_LIMIT) * RAD) / 2))
    private fun mercatorLat(y: Double) = (2 * atan(exp(y)) - Math.PI / 2) / RAD

    /** Pixels for the view, from its bounds and its ground scale at the centre: Web Mercator keeps a degree of longitude the same width everywhere. */
    private class Frame(bounds: GridBounds, metersPerPixel: Double) {
        private val west = bounds.west
        private val top = mercatorY(bounds.north)
        private val bottom = mercatorY(bounds.south)
        val pxPerRad = EARTH_RADIUS * cos(mercatorLat((top + bottom) / 2) * RAD) / metersPerPixel
        val pxPerDeg = pxPerRad * RAD
        val width = (bounds.east - bounds.west) * pxPerDeg
        val height = (top - bottom) * pxPerRad
        fun x(lon: Double) = (lon - west) * pxPerDeg
        fun y(lat: Double) = (top - mercatorY(lat)) * pxPerRad
        fun lon(x: Double) = west + x / pxPerDeg
        fun lat(y: Double) = mercatorLat(top - y / pxPerRad)

        // Map pixels for a point anywhere, for measuring how far a polyline strays from its curve.
        fun px(p: Pt) = doubleArrayOf(p.lon * pxPerDeg, -mercatorY(p.lat) * pxPerRad)
    }

    private class Piece(val west: Double, val east: Double, val offset: Double)

    /** A map's bounds run past 180 once the view takes in the antimeridian or more than one world: each copy in view is worked out in -180..180 and moved by its offset. */
    private fun worldPieces(west: Double, east: Double): List<Piece> {
        val middle = (west + east) / 2
        val from = max(west, middle - 180.0 * MAX_COPIES)
        val to = min(east, middle + 180.0 * MAX_COPIES)
        val pieces = mutableListOf<Piece>()
        var k = floor((from + 180) / 360)
        val last = floor((to + 180) / 360)
        while (k <= last) {
            val offset = 360 * k
            val pieceWest = max(from, offset - 180) - offset
            val pieceEast = min(to, offset + 180) - offset
            if (pieceEast > pieceWest) pieces += Piece(pieceWest, pieceEast, offset)
            k += 1
        }
        return pieces
    }

    private class View(val south: Double, val north: Double, val west: Double, val east: Double)

    private class Region(val zone: Int, val offset: Double, val south: Double, val north: Double, val west: Double, val east: Double, val zoneEast: Double) {
        fun bound(side: Int) = when (side) {
            SOUTH -> south
            NORTH -> north
            WEST -> west
            else -> east
        }
    }

    /** The parts of each zone's strips inside a view, one per world copy. */
    private fun regionsIn(view: View, pieces: List<Piece>): List<Region> = buildList {
        pieces.forEach { piece ->
            STRIPS.forEach { strip ->
                val region = Region(
                    strip.zone, piece.offset,
                    max(strip.south, view.south), min(strip.north, view.north),
                    max(strip.west, piece.west), min(strip.east, piece.east),
                    strip.east,
                )
                if (region.north > region.south && region.east > region.west) add(region)
            }
        }
    }

    // -- UTM, with a signed northing ---------------------------------------------------------------

    private class Pt(var lat: Double, var lon: Double)

    private class Projected(val e: Double, val y: Double)

    // Grid lines run across the equator, so they are worked out with the northing signed (south negative); a southern line's value adds the
    // false northing back. A point with no answer is NaN, which no comparison passes: nothing is drawn for it.
    private fun project(zone: Int, lat: Double, lon: Double): Projected {
        val utm = MgrsConverter.toUtmInZone(lat, lon, zone) ?: return Projected(Double.NaN, Double.NaN)
        return Projected(utm.easting, if (lat < 0) utm.northing - FALSE_NORTHING_SOUTH else utm.northing)
    }

    private fun unproject(zone: Int, e: Double, y: Double): Pt {
        val point = MgrsConverter.fromUtm(zone, y < 0, e, if (y < 0) y + FALSE_NORTHING_SOUTH else y) ?: return Pt(Double.NaN, Double.NaN)
        // fromUtm folds into 180; keep the longitude on its zone's side of the antimeridian.
        val meridian = MgrsConverter.centralMeridian(zone)
        var lon = point.lon
        if (lon - meridian > 180) lon -= 360 else if (lon - meridian < -180) lon += 360
        return Pt(point.lat, lon)
    }

    private fun northingValue(y: Double) = if (y < 0) y + FALSE_NORTHING_SOUTH else y

    private class UtmBox(var eMin: Double, var eMax: Double, var yMin: Double, var yMax: Double)

    /**
     * The eastings and northings a region spans. For a fixed latitude easting and northing only grow away from the central meridian, and for
     * a fixed longitude only change one way with latitude (easting peaking at the equator), so the corners and those points bound them.
     */
    private fun utmBox(region: Region): UtmBox {
        val meridian = clamp(MgrsConverter.centralMeridian(region.zone), region.west, region.east)
        val equator = clamp(0.0, region.south, region.north)
        val box = UtmBox(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        listOf(
            region.south to region.west, region.south to region.east, region.north to region.west, region.north to region.east,
            region.south to meridian, region.north to meridian, equator to region.west, equator to region.east,
        ).forEach { (lat, lon) ->
            val p = project(region.zone, lat, lon)
            box.eMin = min(box.eMin, p.e)
            box.eMax = max(box.eMax, p.e)
            box.yMin = min(box.yMin, p.y)
            box.yMax = max(box.yMax, p.y)
        }
        return box
    }

    internal fun multiplesIn(lo: Double, hi: Double, step: Double): List<Double> = buildList {
        var k = ceil(lo / step)
        while (k * step <= hi) {
            // Plus zero turns -0.0 (ceil of a small negative) into 0.0: the web keys a line by its value's text, where -0 is "0".
            add(k * step + 0.0)
            k += 1
        }
    }

    // -- Tracing a line ----------------------------------------------------------------------------

    private const val SOUTH = 0
    private const val NORTH = 1
    private const val WEST = 2
    private const val EAST = 3

    private class Edge(val latitude: Boolean, val side: Int, val sign: Double)

    private val EDGES = listOf(Edge(true, SOUTH, 1.0), Edge(true, NORTH, -1.0), Edge(false, WEST, 1.0), Edge(false, EAST, -1.0))

    /**
     * Where a function changes sign, between [a0] (f >= 0) and [b0] (f < 0), to within [within]: the Illinois form of false position, which
     * keeps the root bracketed like bisection but takes a handful of steps on these smooth, nearly straight curves. Returns the end on the
     * f >= 0 side.
     */
    private fun crossing(f: (Double) -> Double, a0: Double, fa0: Double, b0: Double, fb0: Double, within: Double): Double {
        var a = a0
        var fa = fa0
        var b = b0
        var fb = fb0
        var kept = 0
        var i = 0
        while (i < 100 && abs(b - a) > within) {
            var m = (a * fb - b * fa) / (fb - fa)
            if (!(m > min(a, b) && m < max(a, b))) m = (a + b) / 2
            val fm = f(m)
            if (fm == 0.0) return m
            if (fm >= 0) {
                a = m
                fa = fm
                if (kept == 1) fb /= 2
                kept = 1
            } else {
                b = m
                fb = fm
                if (kept == -1) fa /= 2
                kept = -1
            }
            i += 1
        }
        return a
    }

    private class EdgeHit(val latitude: Boolean, val bound: Double)

    private class Clip(val lo: Double, val hi: Double, val loEdge: EdgeHit?, val hiEdge: EdgeHit?)

    /**
     * The part of a stretch of line inside a region. On a stretch where latitude and longitude each only rise or only fall, that part is one
     * interval, and each of its ends is found on the edge that cuts it. Null when no part is inside.
     */
    private fun clipStretch(pointAt: (Double) -> Pt, t0: Double, t1: Double, region: Region): Clip? {
        val p0 = pointAt(t0)
        val p1 = pointAt(t1)
        var lo = t0
        var hi = t1
        var loEdge: EdgeHit? = null
        var hiEdge: EdgeHit? = null
        for (edge in EDGES) {
            val bound = region.bound(edge.side)
            fun inside(p: Pt) = edge.sign * ((if (edge.latitude) p.lat else p.lon) - bound)
            val in0 = inside(p0) >= 0
            val in1 = inside(p1) >= 0
            if (!in0 && !in1) return null
            if (in0 != in1) {
                val a = crossing(
                    { t -> inside(pointAt(t)) },
                    if (in0) t0 else t1, inside(if (in0) p0 else p1),
                    if (in0) t1 else t0, inside(if (in0) p1 else p0),
                    EDGE_M,
                )
                if (in0 && a < hi) {
                    hi = a
                    hiEdge = EdgeHit(edge.latitude, bound)
                } else if (in1 && a > lo) {
                    lo = a
                    loEdge = EdgeHit(edge.latitude, bound)
                }
            }
        }
        return if (hi > lo) Clip(lo, hi, loEdge, hiEdge) else null
    }

    // Pixels from a point to a segment.
    private fun offSegment(p: DoubleArray, p0: DoubleArray, p1: DoubleArray): Double {
        val dx = p1[0] - p0[0]
        val dy = p1[1] - p0[1]
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else clamp(((p[0] - p0[0]) * dx + (p[1] - p0[1]) * dy) / length2, 0.0, 1.0)
        return hypot(p[0] - (p0[0] + t * dx), p[1] - (p0[1] + t * dy))
    }

    /** Points along [lo, hi], halving each piece until its middle is within tolerance of the chord. */
    private fun sampleStretch(pointAt: (Double) -> Pt, lo: Double, hi: Double, frame: Frame, tolerancePx: Double): MutableList<Pt> {
        val first = pointAt(lo)
        val last = pointAt(hi)
        val points = mutableListOf(first)
        fun refine(t0: Double, q0: DoubleArray, t1: Double, q1: DoubleArray, depth: Int) {
            val tm = (t0 + t1) / 2
            val pm = pointAt(tm)
            val qm = frame.px(pm)
            // Always halve once, so a gently curving line is never judged by its ends alone.
            if (depth > 0 && (depth >= MAX_DEPTH || offSegment(qm, q0, q1) <= tolerancePx)) return
            refine(t0, q0, tm, qm, depth + 1)
            points += pm
            refine(tm, qm, t1, q1, depth + 1)
        }
        refine(lo, frame.px(first), hi, frame.px(last), 0)
        points += last
        return points
    }

    private class Traced(var hi: Double, val points: MutableList<Pt>)

    /**
     * One easting or northing line in a region, as polylines. A line of constant easting turns back in longitude at the equator, and one of
     * constant northing in latitude at the central meridian, so each is traced as two stretches either side and joined again.
     */
    private fun traceLine(region: Region, easting: Boolean, value: Double, box: UtmBox, frame: Frame, tolerancePx: Double): List<List<LatLon>> {
        val pointAt: (Double) -> Pt = if (easting) { t -> unproject(region.zone, value, t) } else { t -> unproject(region.zone, t, value) }
        val from = if (easting) box.yMin else box.eMin
        val to = if (easting) box.yMax else box.eMax
        val turn = if (easting) 0.0 else 500000.0
        val stretches = if (turn > from && turn < to) listOf(from to turn, turn to to) else listOf(from to to)

        val pieces = mutableListOf<Traced>()
        stretches.forEach { (t0, t1) ->
            val clip = clipStretch(pointAt, t0, t1, region) ?: return@forEach
            val points = sampleStretch(pointAt, clip.lo, clip.hi, frame, tolerancePx)
            // The ends sit exactly on the edge that cut them.
            clip.loEdge?.let { if (it.latitude) points[0].lat = it.bound else points[0].lon = it.bound }
            clip.hiEdge?.let { if (it.latitude) points[points.size - 1].lat = it.bound else points[points.size - 1].lon = it.bound }
            val previous = pieces.lastOrNull()
            if (previous != null && previous.hi == clip.lo) {
                previous.points += points.drop(1)
                previous.hi = clip.hi
            } else {
                pieces += Traced(clip.hi, points)
            }
        }
        return pieces.map { piece -> piece.points.map { LatLon(it.lat, it.lon + region.offset) } }
    }

    /**
     * Zone 31's central meridian is its east edge in band V (Norway's 32V takes 3 to 12 E), so its 500 km easting runs along the boundary,
     * which is zone 32's: the readout puts 3 E in 32. The boundary is drawn as one; the line is not.
     */
    private fun alongEastEdge(zone: Int, zoneEast: Double, easting: Double) = easting == 500000.0 && MgrsConverter.centralMeridian(zone) == zoneEast

    private class Values(val eastings: List<Double>, val northings: List<Double>)

    private fun levelValues(regions: List<Region>, boxes: List<UtmBox>, spacing: Double, skipSquares: Boolean): List<Values> =
        regions.mapIndexed { i, region ->
            fun keep(value: Double) = !(skipSquares && value % SQUARE_M == 0.0)
            Values(
                multiplesIn(boxes[i].eMin, boxes[i].eMax, spacing).filter { keep(it) && !alongEastEdge(region.zone, region.zoneEast, it) },
                multiplesIn(boxes[i].yMin, boxes[i].yMax, spacing).filter { keep(it) },
            )
        }

    private fun traceLevel(regions: List<Region>, boxes: List<UtmBox>, values: List<Values>, kind: String, frame: Frame, tolerancePx: Double): List<GridLine> =
        buildList {
            regions.forEachIndexed { i, region ->
                values[i].eastings.forEach { e ->
                    traceLine(region, true, e, boxes[i], frame, tolerancePx).forEach { add(GridLine(kind, region.zone, "easting", e, it)) }
                }
                values[i].northings.forEach { y ->
                    traceLine(region, false, y, boxes[i], frame, tolerancePx).forEach { add(GridLine(kind, region.zone, "northing", northingValue(y), it)) }
                }
            }
        }

    /** Band edges as parallels across the view, and zone edges as meridians in each band. */
    private fun zoneLines(view: View, pieces: List<Piece>): List<GridLine> = buildList {
        BAND_EDGES.forEach { lat ->
            if (lat >= view.south && lat <= view.north) {
                add(GridLine("zone", null, "parallel", lat, listOf(LatLon(lat, view.west), LatLon(lat, view.east))))
            }
        }
        pieces.forEach { piece ->
            val spans = LinkedHashMap<Double, MutableList<DoubleArray>>()
            BAND_MERIDIANS.forEachIndexed { b, meridians ->
                val lo = max(BAND_EDGES[b], view.south)
                val hi = min(BAND_EDGES[b + 1], view.north)
                if (hi <= lo) return@forEachIndexed
                meridians.forEach { lon ->
                    if (lon < piece.west || lon > piece.east) return@forEach
                    val list = spans.getOrPut(lon) { mutableListOf() }
                    val previous = list.lastOrNull()
                    if (previous != null && previous[1] == lo) previous[1] = hi else list += doubleArrayOf(lo, hi)
                }
            }
            spans.forEach { (lon, list) ->
                list.forEach { (lo, hi) ->
                    add(GridLine("zone", null, "meridian", lon, listOf(LatLon(lo, lon + piece.offset), LatLon(hi, lon + piece.offset))))
                }
            }
        }
    }

    // -- Labels -----------------------------------------------------------------------------------

    private fun zoneText(zone: Int, lat: Double) = "${zone.toString().padStart(2, '0')}${MgrsConverter.bandFor(lat)}"

    // Where a line of one value crosses a parallel or a meridian (to 1e-10 degrees, about 0.01 mm): easting only grows eastward along a
    // parallel, and northing only grows northward along a meridian, so the line crosses once.
    private fun solve(measure: (Double) -> Double, lo: Double, hi: Double, target: Double): Double {
        val f = { x: Double -> measure(x) - target }
        val flo = f(lo)
        if (flo >= 0) return lo
        val fhi = f(hi)
        if (fhi <= 0) return hi
        return crossing(f, hi, fhi, lo, flo, 1e-10)
    }

    private fun stripsAtLatitude(lat: Double) =
        STRIPS.filter { (lat >= it.south && lat < it.north) || (lat == NORTH_LIMIT && it.north == NORTH_LIMIT) }

    // A line labelled along the top or bottom: its world copy, zone and value.
    private data class EastingKey(val offset: Double, val zone: Int, val value: Double)

    private class SquareEntry(val region: Region, val e0: Double, val e1: Double, val y0: Double, val y1: Double, val clipped: UtmBox, val area: Double)

    private fun placeLabels(view: View, pieces: List<Piece>, frame: Frame, spacing: Double?, metersPerPixel: Double, options: GridOptions): List<GridLabel> {
        val placed = options.avoid.toMutableList()
        val labels = mutableListOf<GridLabel>()
        fun place(label: (GridBox) -> GridLabel, box: GridBox): Boolean {
            if (labels.size >= options.maxLabels) return false
            if (box.x0 < 0 || box.y0 < 0 || box.x1 > frame.width || box.y1 > frame.height) return false
            val g = GAP_PX
            if (placed.any { box.x0 < it.x1 + g && box.x1 + g > it.x0 && box.y0 < it.y1 + g && box.y1 + g > it.y0 }) return false
            placed += box
            labels += label(box)
            return true
        }
        fun centredBox(x: Double, y: Double, text: String): GridBox {
            val w = labelWidth(text)
            return GridBox(x - w / 2, y - HEIGHT_PX / 2, x + w / 2, y + HEIGHT_PX / 2)
        }
        val minor = spacing != null && spacing < SQUARE_M

        // Grid zone designators, largest cells first, while the squares are not yet labelled with them.
        if (!minor) {
            class Shown(val cell: Cell, val x0: Double, val x1: Double, val y0: Double, val y1: Double, val area: Double)
            val cells = mutableListOf<Shown>()
            pieces.forEach { piece ->
                GRID_ZONES.forEach { cell ->
                    val s = max(cell.south, view.south)
                    val n = min(cell.north, view.north)
                    val w = max(cell.west, piece.west)
                    val e = min(cell.east, piece.east)
                    if (n <= s || e <= w) return@forEach
                    val x0 = frame.x(w + piece.offset)
                    val x1 = frame.x(e + piece.offset)
                    val y0 = frame.y(n)
                    val y1 = frame.y(s)
                    cells += Shown(cell, x0, x1, y0, y1, (x1 - x0) * (y1 - y0))
                }
            }
            cells.sortedByDescending { it.area }.forEach { shown ->
                val text = "${shown.cell.zone.toString().padStart(2, '0')}${shown.cell.band}"
                if (shown.x1 - shown.x0 < labelWidth(text) + 24 || shown.y1 - shown.y0 < HEIGHT_PX + 24) return@forEach
                val x = (shown.x0 + shown.x1) / 2
                val y = (shown.y0 + shown.y1) / 2
                place({ GridLabel("gzd", null, text, shown.cell.zone, null, frame.lat(y), frame.lon(x), it) }, centredBox(x, y, text))
            }
        }

        if (minor) {
            val step: Double = spacing
            // Eastings where each line crosses one edge of the view. Returns the lines whose label did not fit there, so they can try the
            // opposite edge; [only] limits the run to those.
            fun eastingsAlong(lat: Double, labelTop: Double, edge: String, only: Set<EastingKey>?): Set<EastingKey> {
                val missed = mutableSetOf<EastingKey>()
                pieces.forEach { piece ->
                    stripsAtLatitude(lat).forEach strip@{ strip ->
                        val a = max(strip.west, piece.west)
                        val b = min(strip.east, piece.east)
                        if (b <= a) return@strip
                        val eastingAt = { lon: Double -> project(strip.zone, lat, lon).e }
                        multiplesIn(eastingAt(a), eastingAt(b), step).forEach value@{ value ->
                            if (alongEastEdge(strip.zone, strip.east, value)) return@value
                            val key = EastingKey(piece.offset, strip.zone, value)
                            if (only != null && key !in only) return@value
                            val lon = solve(eastingAt, a, b, value)
                            val text = principalDigits(value, step)
                            val x = frame.x(lon + piece.offset)
                            val w = labelWidth(text)
                            val placedHere = place(
                                { GridLabel("easting", edge, text, strip.zone, value, lat, lon + piece.offset, it) },
                                GridBox(x - w / 2, labelTop, x + w / 2, labelTop + HEIGHT_PX),
                            )
                            if (!placedHere) missed += key
                        }
                    }
                }
                return missed
            }
            // Along the top first; a line whose label is blocked there (on a phone the search bar covers that strip) is labelled along the
            // bottom instead, so both halves of a reference can be read.
            val missedTop = eastingsAlong(view.north, frame.y(view.north) + EDGE_PX, "top", null)
            if (missedTop.isNotEmpty()) eastingsAlong(view.south, frame.y(view.south) - EDGE_PX - HEIGHT_PX, "bottom", missedTop)

            // Northings down one side of the view, the same way: the left first, then the right. By value only: the two sides of the view
            // can be in different zones, and what the fallback must give is the row's digits.
            fun northingsAlong(piece: Piece?, lon: Double, labelLeft: (String) -> Double, edge: String, only: Set<Double>?): Set<Double> {
                val missed = mutableSetOf<Double>()
                if (piece == null) return missed
                STRIPS.filter { lon >= it.west && lon < it.east }.forEach strip@{ strip ->
                    val s = max(strip.south, view.south)
                    val n = min(strip.north, view.north)
                    if (n <= s) return@strip
                    val northingAt = { lat: Double -> project(strip.zone, lat, lon).y }
                    multiplesIn(northingAt(s), northingAt(n), step).forEach value@{ y ->
                        val value = northingValue(y)
                        if (only != null && value !in only) return@value
                        val lat = solve(northingAt, s, n, y)
                        val text = principalDigits(value, step)
                        val py = frame.y(lat)
                        val x = labelLeft(text)
                        val placedHere = place(
                            { GridLabel("northing", edge, text, strip.zone, value, lat, lon + piece.offset, it) },
                            GridBox(x, py - HEIGHT_PX / 2, x + labelWidth(text), py + HEIGHT_PX / 2),
                        )
                        if (!placedHere) missed += value
                    }
                }
                return missed
            }
            // The first piece holds the view's left edge, the last its right edge.
            val left = pieces.firstOrNull()
            val leftEdge = left?.takeIf { abs(it.west + it.offset - view.west) < 1e-9 }
            val missedLeft = northingsAlong(leftEdge, leftEdge?.west ?: 0.0, { EDGE_PX }, "left", null)
            val right = pieces.lastOrNull()
            val rightEdge = right?.takeIf { abs(it.east + it.offset - view.east) < 1e-9 }
            if (rightEdge != null && (missedLeft.isNotEmpty() || leftEdge == null)) {
                // A hair inside the edge, so the strip it falls in is the one the view ends in.
                northingsAlong(rightEdge, rightEdge.east - 1e-9, { text -> frame.width - EDGE_PX - labelWidth(text) }, "right", if (leftEdge != null) missedLeft else null)
            }
        }

        // 100 km squares: at their middle while they are small, else in a corner with the zone too, so a full reference can be read off the screen.
        if (spacing != null) {
            val squares = mutableListOf<SquareEntry>()
            regionsIn(view, pieces).forEach { region ->
                val box = utmBox(region)
                var c = floor(box.eMin / SQUARE_M)
                while (c * SQUARE_M < box.eMax) {
                    var r = floor(box.yMin / SQUARE_M)
                    while (r * SQUARE_M < box.yMax) {
                        val e0 = c * SQUARE_M
                        val e1 = (c + 1) * SQUARE_M
                        val y0 = r * SQUARE_M
                        val y1 = (r + 1) * SQUARE_M
                        val clipped = UtmBox(max(e0, box.eMin), min(e1, box.eMax), max(y0, box.yMin), min(y1, box.yMax))
                        // Only the columns a zone has (A to H and so on): the rest is outside every zone.
                        if (c >= 1 && c <= 8 && clipped.eMax > clipped.eMin && clipped.yMax > clipped.yMin) {
                            squares += SquareEntry(region, e0, e1, y0, y1, clipped, (clipped.eMax - clipped.eMin) * (clipped.yMax - clipped.yMin))
                        }
                        r += 1
                    }
                    c += 1
                }
            }

            // A label belongs to its square only if all of it is in the square and in the region.
            fun fits(entry: SquareEntry, box: GridBox): Boolean =
                listOf(box.x0 to box.y0, box.x1 to box.y0, box.x0 to box.y1, box.x1 to box.y1).all { (x, y) ->
                    val region = entry.region
                    val lat = frame.lat(y)
                    val lon = frame.lon(x) - region.offset
                    if (lat < region.south || lat > region.north || lon < region.west || lon > region.east) return@all false
                    val p = project(region.zone, lat, lon)
                    p.e >= entry.e0 && p.e <= entry.e1 && p.y >= entry.y0 && p.y <= entry.y1
                }

            squares.sortedByDescending { it.area }.forEach { entry ->
                val region = entry.region
                val clipped = entry.clipped
                val middleE = (clipped.eMin + clipped.eMax) / 2
                val middleY = (clipped.yMin + clipped.yMax) / 2
                val letters = MgrsConverter.squareLetters(region.zone, middleE, northingValue(middleY))
                fun toScreen(e: Double, y: Double): Pair<Double, Double> {
                    val point = unproject(region.zone, e, y)
                    return frame.x(point.lon + region.offset) to frame.y(point.lat)
                }
                val candidates = mutableListOf<Pair<Double, Double>>()
                if (!minor) {
                    candidates += toScreen(middleE, middleY)
                } else {
                    // Clear of the line labels along the top and left: first the view's own corners, top right first, then the corners of the
                    // square's part of the view, then its middle.
                    val halfW = labelWidth("${zoneText(region.zone, 0.0)} $letters") / 2 + SIDE_PX
                    val halfH = HEIGHT_PX / 2 + CORNER_PX
                    val dx = halfW * metersPerPixel
                    val dy = halfH * metersPerPixel
                    candidates += listOf(
                        frame.width - halfW to halfH,
                        frame.width - halfW to frame.height - halfH,
                        halfW to halfH,
                        halfW to frame.height - halfH,
                        toScreen(clipped.eMax - dx, clipped.yMax - dy),
                        toScreen(clipped.eMin + dx, clipped.yMax - dy),
                        toScreen(clipped.eMax - dx, clipped.yMin + dy),
                        toScreen(clipped.eMin + dx, clipped.yMin + dy),
                        toScreen(middleE, middleY),
                    )
                }
                candidates.any { (x, y) ->
                    val lat = frame.lat(y)
                    val text = if (minor) "${zoneText(region.zone, lat)} $letters" else letters
                    val box = centredBox(x, y, text)
                    if (!fits(entry, box)) return@any false
                    place({ GridLabel("square", null, text, region.zone, null, lat, frame.lon(x), it) }, box)
                }
            }
        }

        return labels
    }

    // -- The grid ---------------------------------------------------------------------------------

    private class Level(val kind: String, val spacing: Double, val skipSquares: Boolean)

    /**
     * What to draw for a view: [bounds] as the map gives them (they may run past 180), and the ground scale at the view's centre in metres a
     * pixel. A level that would pass the caps on lines or points is left out whole, finest first, and named in [GridResult.dropped]: the grid
     * is then coarser, never wrong.
     */
    public fun grid(bounds: GridBounds?, metersPerPixel: Double, options: GridOptions = GridOptions()): GridResult {
        if (bounds == null) return GridResult.EMPTY
        val (south, west, north, east) = bounds
        if (!listOf(south, west, north, east, metersPerPixel).all { it.isFinite() }) return GridResult.EMPTY
        if (metersPerPixel <= 0 || north <= south || east <= west) return GridResult.EMPTY

        val frame = Frame(bounds, metersPerPixel)
        if (6 * frame.pxPerDeg < MIN_ZONE_PX) return GridResult.EMPTY

        // Lines also cover a margin round the view, so a pan does not show bare map before the redraw; labels go on the view itself.
        val padX = (east - west) * options.pad
        val padY = (mercatorY(north) - mercatorY(south)) * options.pad
        val lineView = View(
            max(SOUTH_LIMIT, mercatorLat(mercatorY(south) - padY)),
            min(NORTH_LIMIT, mercatorLat(mercatorY(north) + padY)),
            west - padX,
            east + padX,
        )
        if (lineView.north <= lineView.south) return GridResult.EMPTY
        val linePieces = worldPieces(lineView.west, lineView.east)
        val lines = zoneLines(lineView, linePieces).toMutableList()
        val dropped = mutableListOf<String>()
        var drawn: Double? = null

        val spacing = gridSpacing(metersPerPixel)
        val levels = buildList {
            if (spacing != null) add(Level("major", SQUARE_M, false))
            if (spacing != null && spacing < SQUARE_M) add(Level("minor", spacing, true))
        }

        val regions = if (levels.isNotEmpty()) regionsIn(lineView, linePieces) else emptyList()
        val boxes = regions.map(::utmBox)
        var lineCount = lines.size
        var pointCount = lines.size * 2
        levels.forEach { level ->
            if (dropped.isNotEmpty()) {
                dropped += level.kind
                return@forEach
            }
            val values = levelValues(regions, boxes, level.spacing, level.skipSquares)
            val count = values.sumOf { it.eastings.size + it.northings.size }
            if (lineCount + count > options.maxLines) {
                dropped += level.kind
                return@forEach
            }
            val traced = traceLevel(regions, boxes, values, level.kind, frame, options.tolerancePx)
            val points = traced.sumOf { it.points.size }
            if (pointCount + points > options.maxPoints) {
                dropped += level.kind
                return@forEach
            }
            lineCount += traced.size
            pointCount += points
            lines += traced
            drawn = level.spacing
        }

        val labelView = View(max(SOUTH_LIMIT, south), min(NORTH_LIMIT, north), west, east)
        val labels = if (labelView.north > labelView.south) {
            placeLabels(labelView, worldPieces(west, east), frame, drawn, metersPerPixel, options)
        } else {
            emptyList()
        }
        return GridResult(drawn, lines, labels, dropped)
    }
}
