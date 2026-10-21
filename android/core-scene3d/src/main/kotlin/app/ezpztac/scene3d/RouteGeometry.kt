package app.ezpztac.scene3d

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Cesium's `BoundingSphere.fromPoints`: Ritter's sphere and the one round the points' box, whichever is smaller. What *Routes* frames, as the
 * web's does (`frameSphere` over `BoundingSphere.fromPoints` of every drawn position); null for no points. Ritter's start depends on the axes,
 * so with a [frame] the points are taken in geocentric coordinates, as Cesium has them, and the sphere given back in the frame.
 */
public object BoundingSpheres {
    public fun fromPoints(points: List<Vec3>, frame: LocalFrame? = null): Sphere? {
        if (frame != null) return fromPoints(points.map(frame::toEcef))?.let { Sphere(frame.toLocal(it.center), it.radius) }
        if (points.isEmpty()) return null
        var xMin = points[0]
        var yMin = points[0]
        var zMin = points[0]
        var xMax = points[0]
        var yMax = points[0]
        var zMax = points[0]
        for (p in points) {
            if (p.x < xMin.x) xMin = p
            if (p.x > xMax.x) xMax = p
            if (p.y < yMin.y) yMin = p
            if (p.y > yMax.y) yMax = p
            if (p.z < zMin.z) zMin = p
            if (p.z > zMax.z) zMax = p
        }
        // The farthest-apart pair along the axes seeds Ritter's sphere.
        val xSpan = (xMax - xMin).let { it dot it }
        val ySpan = (yMax - yMin).let { it dot it }
        val zSpan = (zMax - zMin).let { it dot it }
        var diameter1 = xMin
        var diameter2 = xMax
        var maxSpan = xSpan
        if (ySpan > maxSpan) {
            maxSpan = ySpan
            diameter1 = yMin
            diameter2 = yMax
        }
        if (zSpan > maxSpan) {
            diameter1 = zMin
            diameter2 = zMax
        }
        var ritterCenter = (diameter1 + diameter2) * 0.5
        var radiusSquared = (diameter2 - ritterCenter).let { it dot it }
        var ritterRadius = sqrt(radiusSquared)
        // The box's own sphere.
        val minBox = Vec3(xMin.x, yMin.y, zMin.z)
        val maxBox = Vec3(xMax.x, yMax.y, zMax.z)
        val naiveCenter = (minBox + maxBox) * 0.5
        var naiveRadius = 0.0
        for (p in points) {
            naiveRadius = max(naiveRadius, (p - naiveCenter).length)
            // Grown to take in any point outside it.
            val oldCenterToPointSquared = (p - ritterCenter).let { it dot it }
            if (oldCenterToPointSquared > radiusSquared) {
                val oldCenterToPoint = sqrt(oldCenterToPointSquared)
                ritterRadius = (ritterRadius + oldCenterToPoint) * 0.5
                radiusSquared = ritterRadius * ritterRadius
                val oldToNew = oldCenterToPoint - ritterRadius
                ritterCenter = Vec3(
                    (ritterRadius * ritterCenter.x + oldToNew * p.x) / oldCenterToPoint,
                    (ritterRadius * ritterCenter.y + oldToNew * p.y) / oldCenterToPoint,
                    (ritterRadius * ritterCenter.z + oldToNew * p.z) / oldCenterToPoint,
                )
            }
        }
        return if (ritterRadius < naiveRadius) Sphere(ritterCenter, ritterRadius) else Sphere(naiveCenter, naiveRadius)
    }
}

/**
 * A route's colour as four floats, red, green, blue and alpha, read as Cesium reads the web's (`Color.fromCssColorString`, Cesium 1.144): a CSS
 * colour's name, `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa`, `rgb()` or `rgba()` in numbers or per cents, or `hsl()` or `hsla()`, as the 2D map
 * reads it too. None is the web's own blue (`routeEntities.js`), and so, **not like the web**, is one Cesium cannot read, which stops the web's
 * routes being drawn at all. An [alpha] given stands for the colour's own, as the web's `withAlpha` does.
 */
public object RouteColors3D {
    public const val FALLBACK: String = "#38bdf8"

    public fun rgba(css: String?, alpha: Double? = null): FloatArray {
        val c = css?.let(::read) ?: read(FALLBACK)!!
        return floatArrayOf(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), (alpha ?: c[3]).toFloat())
    }

    /** [css] as Cesium reads it, red, green, blue and alpha from 0 to 1; null for what it cannot read. */
    public fun read(css: String): DoubleArray? {
        // Trimmed as JavaScript trims.
        val color = css.replace(EDGES, "")
        if (color.uppercase() == "TRANSPARENT") return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        NAMED[color.uppercase()]?.let { return hex(it, 2) }
        SHORT_HEX.matchEntire(color)?.let { return hex(it.groupValues.drop(1).filter { g -> g.isNotEmpty() }.joinToString(""), 1) }
        LONG_HEX.matchEntire(color)?.let { return hex(it.groupValues.drop(1).filter { g -> g.isNotEmpty() }.joinToString(""), 2) }
        RGB.matchEntire(color)?.let { m ->
            val (r, g, b) = (1..3).map { i -> m.groupValues[i].let { v -> parseFloat(v) / if (v.endsWith("%")) 100.0 else 255.0 } }
            return finite(doubleArrayOf(r, g, b, m.groupValues[4].ifEmpty { "1.0" }.let(::parseFloat)))
        }
        HSL.matchEntire(color)?.let { m ->
            val alpha = m.groupValues[4].ifEmpty { "1.0" }.let(::parseFloat)
            return finite(hsl(parseFloat(m.groupValues[1]) / 360.0, parseFloat(m.groupValues[2]) / 100.0, parseFloat(m.groupValues[3]) / 100.0, alpha))
        }
        return null
    }

    // Each channel [width] hex digits, the alpha opaque when there is none.
    private fun hex(digits: String, width: Int): DoubleArray {
        val most = if (width == 1) 15.0 else 255.0
        val channels = digits.chunked(width).map { it.toInt(16) / most }
        return doubleArrayOf(channels[0], channels[1], channels[2], channels.getOrElse(3) { 1.0 })
    }

    // Cesium's `Color.fromHsl`.
    private fun hsl(hue: Double, saturation: Double, lightness: Double, alpha: Double): DoubleArray {
        val h = hue % 1.0
        if (saturation == 0.0) return doubleArrayOf(lightness, lightness, lightness, alpha)
        val m2 = if (lightness < 0.5) lightness * (1 + saturation) else lightness + saturation - lightness * saturation
        val m1 = 2.0 * lightness - m2
        return doubleArrayOf(hueToRgb(m1, m2, h + 1.0 / 3), hueToRgb(m1, m2, h), hueToRgb(m1, m2, h - 1.0 / 3), alpha)
    }

    private fun hueToRgb(m1: Double, m2: Double, hue: Double): Double {
        var h = hue
        if (h < 0) h += 1
        if (h > 1) h -= 1
        return when {
            h * 6 < 1 -> m1 + (m2 - m1) * 6 * h
            h * 2 < 1 -> m2
            h * 3 < 2 -> m1 + (m2 - m1) * (2.0 / 3 - h) * 6
            else -> m1
        }
    }

    // JavaScript's parseFloat of what the patterns let through (digits and points, then perhaps a per cent): the longest number it starts with.
    private fun parseFloat(text: String): Double = LEADING_NUMBER.find(text)?.value?.toDouble() ?: Double.NaN

    // A channel that is not a number is a colour that cannot be drawn: read as none.
    private fun finite(channels: DoubleArray): DoubleArray? = channels.takeIf { c -> c.all { it.isFinite() } }

    // Cesium's patterns, with JavaScript's \s (Java's would miss a no-break space and the like).
    private const val S = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private val SHORT_HEX = Regex("#([0-9a-f])([0-9a-f])([0-9a-f])([0-9a-f])?", RegexOption.IGNORE_CASE)
    private val LONG_HEX = Regex("#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})?", RegexOption.IGNORE_CASE)
    private val RGB = Regex(
        "rgba?$S*\\($S*([0-9.]+%?)$S*[,$S]+$S*([0-9.]+%?)$S*[,$S]+$S*([0-9.]+%?)(?:$S*[,$S/]+$S*([0-9.]+))?$S*\\)",
        RegexOption.IGNORE_CASE,
    )
    private val HSL = Regex(
        "hsla?$S*\\($S*([0-9.]+)$S*[,$S]+$S*([0-9.]+%)$S*[,$S]+$S*([0-9.]+%)(?:$S*[,$S/]+$S*([0-9.]+))?$S*\\)",
        RegexOption.IGNORE_CASE,
    )
    private val EDGES = Regex("^$S+|$S+$")
    private val LEADING_NUMBER = Regex("^(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)")

    // Cesium's named colours (its `Color.ALICEBLUE` to `Color.YELLOWGREEN`, the greys spelled both ways), taken from its source.
    private val NAMED: Map<String, String> = (
            "aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4 black 000000 " +
            "blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 " +
            "chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan " +
            "008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b " +
            "darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a darkseagreen 8fbc8f " +
            "darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 " +
            "deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen " +
            "228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 green 008000 " +
            "greenyellow adff2f grey 808080 honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c " +
            "lavender e6e6fa lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff " +
            "lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightseagreen 20b2aa " +
            "lightskyblue 87cefa lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 " +
            "limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 " +
            "mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc " +
            "mediumvioletred c71585 midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy " +
            "000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa " +
            "palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb " +
            "plum dda0dd powderblue b0e0e6 purple 800080 red ff0000 rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513 salmon " +
            "fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd " +
            "slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 " +
            "tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen " +
            "9acd32"
        ).split(' ').chunked(2).associate { (name, hex) -> name.uppercase() to hex }
}

/** A run of a route's line in the scene's frame, and its colour. */
public class LineRun(public val points: List<Vec3>, public val color: FloatArray)

/** A curtain: the route's places over the ground's under them, and its colour (the route's, at a fifth). */
public class Curtain(public val tops: List<Vec3>, public val bottoms: List<Vec3>, public val color: FloatArray)

/** A drop line from the ground up to a named point. */
public class DropLine(public val ground: Vec3, public val point: Vec3)

/** A named point's dot: where it is, how big (11 for a target, 8 otherwise, in the screen's points, inside its ring), and its ring's colour. */
public class RouteDot(public val at: Vec3, public val size: Double, public val ring: FloatArray)

/** A named point's label: where it hangs from, its lines, and whether it says the AGL measured disagrees with the plan's (drawn amber). */
public class RouteLabel(public val at: Vec3, public val text: String, public val mismatch: Boolean)

/**
 * The placed routes as the renderer draws them (`routeEntities.js`), in the scene's frame: each line run in the route's colour, 3 points wide,
 * dashed at 60% where the point cloud hides it; each curtain to the ground at a fifth of the colour; a white drop line from the ground to a
 * named point that is more than a metre above it; a white dot ringed in the route's colour, and the label, both always in front.
 */
public class RouteGeometry(
    public val lines: List<LineRun>,
    public val curtains: List<Curtain>,
    public val drops: List<DropLine>,
    public val dots: List<RouteDot>,
    public val labels: List<RouteLabel>,
) {
    /** Every place a line passes, for framing the routes (the web's `routePositions`). */
    public val positions: List<Vec3> get() = lines.flatMap { it.points }

    public companion object {
        public val NONE: RouteGeometry = RouteGeometry(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        public const val LINE_WIDTH: Double = 3.0
        public const val HIDDEN_ALPHA: Double = 0.6
        public const val DASH_LENGTH: Double = 14.0
        public const val CURTAIN_ALPHA: Double = 0.2
        public const val DROP_WIDTH: Double = 1.5
        public const val DROP_ALPHA: Double = 0.75
        public const val DOT_RING: Double = 3.0
        public const val TARGET_DOT: Double = 11.0
        public const val DOT: Double = 8.0

        /** [placed] in [frame]. */
        public fun of(placed: List<PlacedRoute>, frame: LocalFrame): RouteGeometry {
            fun at(p: RoutePosition) = frame.fromGeodetic(p.lat, p.lon, p.heightM)
            val lines = ArrayList<LineRun>()
            val curtains = ArrayList<Curtain>()
            val drops = ArrayList<DropLine>()
            val dots = ArrayList<RouteDot>()
            val labels = ArrayList<RouteLabel>()
            for (route in placed) {
                val color = RouteColors3D.rgba(route.color)
                for (line in route.lines) lines += LineRun(line.map(::at), color)
                for (wall in route.walls) {
                    curtains += Curtain(
                        wall.positions.map(::at),
                        wall.positions.mapIndexed { i, p -> frame.fromGeodetic(p.lat, p.lon, wall.groundM[i]) },
                        RouteColors3D.rgba(route.color, CURTAIN_ALPHA),
                    )
                }
                for (marker in route.markers) {
                    val point = frame.fromGeodetic(marker.lat, marker.lon, marker.heightM)
                    // Nothing to drop to from a point already on the ground.
                    if (marker.groundM != null && marker.heightM - marker.groundM > 1) {
                        drops += DropLine(frame.fromGeodetic(marker.lat, marker.lon, marker.groundM), point)
                    }
                    dots += RouteDot(point, if (marker.ptType == "target") TARGET_DOT else DOT, color)
                    labels += RouteLabel(point, marker.label, marker.mismatch)
                }
            }
            return RouteGeometry(lines, curtains, drops, dots, labels)
        }

        /**
         * How big a label is at [distance] metres, as Cesium scales the web's (its `NearFarScalar(1500, 1.0, 60000, 0.55)` through
         * `czm_nearFarScalar`): full size to 1.5 km, then smaller, quickly at first (the squared distance's share of the way, to the power 0.2),
         * to 55% at 60 km and beyond, so a long route does not become a wall of text.
         */
        public fun labelScale(distance: Double): Double {
            val t = ((distance * distance - NEAR_M * NEAR_M) / (FAR_M * FAR_M - NEAR_M * NEAR_M)).coerceIn(0.0, 1.0).pow(0.2)
            return 1.0 + (FAR_SCALE - 1.0) * t
        }

        private const val NEAR_M = 1_500.0
        private const val FAR_M = 60_000.0
        private const val FAR_SCALE = 0.55
    }
}
