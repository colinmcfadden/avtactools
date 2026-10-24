package app.ezpztac.scene3d

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Where a frustum puts a volume. */
public enum class Visibility { OUTSIDE, INTERSECTING, INSIDE }

/** A plane `normal · p + distance = 0`, its normal pointing to the side that is in. */
public data class Plane(val normal: Vec3, val distance: Double) {
    public fun signedDistance(p: Vec3): Double = (normal dot p) + distance
}

/** The six planes a camera sees between, each facing in. */
public class Frustum(public val planes: List<Plane>) {
    public fun visibility(volume: BoundingVolume): Visibility {
        var inside = true
        for (plane in planes) {
            when (volume.side(plane)) {
                Visibility.OUTSIDE -> return Visibility.OUTSIDE
                Visibility.INTERSECTING -> inside = false
                Visibility.INSIDE -> Unit
            }
        }
        return if (inside) Visibility.INSIDE else Visibility.INTERSECTING
    }
}

/** A tile's bounding volume, in the scene's frame: what culling and the screen-space error measure. */
public sealed interface BoundingVolume {
    public val center: Vec3

    /** How far [p] is from the volume: none inside it. Cesium's `distanceToCamera`. */
    public fun distanceTo(p: Vec3): Double

    public fun side(plane: Plane): Visibility

    /** The radius of a sphere round [center] that holds it. */
    public val radius: Double
}

/** A box, Cesium's `OrientedBoundingBox`: its centre and three half-axes (which carry their lengths), in the scene's frame. */
public class OrientedBox(override val center: Vec3, public val halfAxes: List<Vec3>) : BoundingVolume {
    init {
        require(halfAxes.size == 3) { "A box has three half-axes" }
    }

    // Without squaring past a double's range, so a box a file made huge is a finite one, and only one beyond doubles is refused as infinite.
    override val radius: Double = hypot(hypot(halfAxes[0].reach, halfAxes[1].reach), halfAxes[2].reach)

    /** Cesium's `OrientedBoundingBox.distanceSquaredTo`, rooted: the offset measured along each axis, beyond the box's half-length on it. */
    override fun distanceTo(p: Vec3): Double {
        val offset = p - center
        var squared = 0.0
        var explained = Vec3.ZERO
        for (axis in halfAxes) {
            val half = axis.length
            if (half == 0.0) continue
            val unit = axis * (1.0 / half)
            val along = offset dot unit
            explained += unit * along
            if (along < -half) squared += (along + half) * (along + half) else if (along > half) squared += (along - half) * (along - half)
        }
        // A flat box (a half-axis of no length): what the others do not explain of the offset is off its plane, all of it.
        if (halfAxes.any { it.length == 0.0 }) {
            val rest = offset - explained
            squared += rest dot rest
        }
        return sqrt(squared)
    }

    override fun side(plane: Plane): Visibility {
        val reach = halfAxes.sumOf { abs(it dot plane.normal) }
        val d = plane.signedDistance(center)
        return when {
            d < -reach -> Visibility.OUTSIDE
            d > reach -> Visibility.INSIDE
            else -> Visibility.INTERSECTING
        }
    }

    /** This box moved by [m]: its centre as a point, its half-axes as directions. */
    public fun transformed(m: Mat4): OrientedBox = OrientedBox(m.transformPoint(center), halfAxes.map(m::transformDirection))
}

/** A sphere, Cesium's `BoundingSphere`. */
public class Sphere(override val center: Vec3, override val radius: Double) : BoundingVolume {
    override fun distanceTo(p: Vec3): Double = max(0.0, (p - center).length - radius)

    override fun side(plane: Plane): Visibility {
        val d = plane.signedDistance(center)
        return when {
            d < -radius -> Visibility.OUTSIDE
            d > radius -> Visibility.INSIDE
            else -> Visibility.INTERSECTING
        }
    }

    /** This sphere moved by [m]: its radius by the most [m] stretches anything. */
    public fun transformed(m: Mat4): Sphere = Sphere(m.transformPoint(center), radius * m.maximumScale)
}
