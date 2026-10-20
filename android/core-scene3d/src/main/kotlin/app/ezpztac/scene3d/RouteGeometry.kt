package app.ezpztac.scene3d

import kotlin.math.max
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

/** A colour as four floats, red, green, blue and alpha, from `#RRGGBB`; the web's own blue for anything else (`routeEntities.js`). */
public object RouteColors3D {
    public const val FALLBACK: String = "#38bdf8"

    public fun rgba(css: String?, alpha: Double = 1.0): FloatArray {
        val hex = css?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: FALLBACK
        val v = hex.substring(1).toInt(16)
        return floatArrayOf(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, alpha.toFloat())
    }
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
         * How big a label is at [distance] metres (Cesium's `NearFarScalar(1500, 1.0, 60000, 0.55)`): full size to 1.5 km, then smaller in a
         * straight line to 55% at 60 km and beyond, so a long route does not become a wall of text.
         */
        public fun labelScale(distance: Double): Double {
            val t = ((distance - 1_500.0) / (60_000.0 - 1_500.0)).coerceIn(0.0, 1.0)
            return 1.0 + (0.55 - 1.0) * t
        }
    }
}
