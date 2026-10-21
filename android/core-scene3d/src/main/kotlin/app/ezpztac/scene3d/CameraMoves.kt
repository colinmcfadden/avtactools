package app.ezpztac.scene3d

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** A point of the view, in its pixels from the top left. */
public data class ScreenPoint(val x: Double, val y: Double)

/** Where the camera is and which way it looks, in the scene's frame: [heading] radians clockwise from north, [pitch] radians up from level. */
public data class CameraPose(val position: Vec3, val heading: Double, val pitch: Double) {
    /** The camera this pose is for a view of [widthPx] by [heightPx] at [pixelRatio], seeing from [near] to [far] metres. */
    public fun camera(widthPx: Int, heightPx: Int, pixelRatio: Double, near: Double, far: Double): ViewCamera =
        ViewCamera(position, heading, pitch, widthPx, heightPx, near, far, pixelRatio)

    public val direction: Vec3 get() = Vec3(sin(heading) * cos(pitch), cos(heading) * cos(pitch), sin(pitch))

    public companion object {
        /**
         * The web's view of a [sphere] (`frameSphere` in `Viewer3D.jsx`): looking down at 35° from 30° west of north, 2.2 radii back. How the view
         * opens on the core's sphere, and where the *LZ* and *Routes* buttons fly to. A sphere with no size is looked at from [POINT_RANGE], as Cesium
         * looks at one (`adjustBoundingSphereOffset`: a range of 0 is none given).
         */
        public fun framing(frame: LocalFrame, sphere: Sphere): CameraPose {
            val range = (sphere.radius * SceneLook.CAMERA_RANGE_FACTOR).takeIf { it != 0.0 } ?: POINT_RANGE
            val (position, heading, pitch) = lookAt(
                frame, sphere.center, Math.toRadians(SceneLook.CAMERA_HEADING_DEG), Math.toRadians(SceneLook.CAMERA_PITCH_DEG), range,
            )
            return CameraPose(position, heading, pitch)
        }

        /** Cesium's `MINIMUM_ZOOM`: how far it stands from a sphere with no size. */
        public const val POINT_RANGE: Double = 100.0
    }
}

/**
 * The camera moved as fingers move it, as Cesium's touch controls move the web's on a touch screen: one finger drags the ground under it, a pinch
 * goes toward the place between the fingers, a twist turns the view and two fingers up or down tilt it, both about the ground at the middle of
 * the view. The ground the moves hold to is level, [groundZ] metres up the scene's frame (the LZ's ground, or the point cloud's middle until that
 * is known): picking the real surface needs the GPU's depth, which nothing here has. Each move gives the pose the camera then has.
 */
public class CameraMoves(public val groundZ: Double, public val limits: Limits = Limits()) {
    /**
     * How far it may go: [minRange] to [maxRange] metres from the ground it moves about, pitched no flatter than [maxPitch] (just under level, so
     * the ground stays in view), and never nearer than [minAboveGround] metres above the ground. A camera already further than [maxRange] (*Routes*
     * framing routes tens of kilometres across, which Cesium frames whatever their size: its `maximumZoomDistance` is unbounded) is never pulled in
     * to it by a pinch; it may only come nearer.
     */
    public data class Limits(
        val minRange: Double = 3.0,
        val maxRange: Double = 30_000.0,
        val minPitch: Double = -PI / 2,
        val maxPitch: Double = Math.toRadians(-2.0),
        val minAboveGround: Double = 2.0,
    )

    /** Where the ray through [at] meets the ground; null where it does not (it looks at or above the horizon, or meets it beyond any reach). */
    public fun ground(camera: ViewCamera, at: ScreenPoint): Vec3? {
        val ray = camera.rayThrough(at.x, at.y)
        if (ray.z > -RAY_EPSILON) return null
        val t = (groundZ - camera.position.z) / ray.z
        if (t <= 0 || t > maxOf(limits.maxRange, camera.position.z - groundZ) * REACH) return null
        return camera.position + ray * t
    }

    /** What the view turns and tilts about: the ground at its middle, or, looking above the horizon, a point ahead as far as the camera is up. */
    public fun pivot(camera: ViewCamera): Vec3 =
        ground(camera, ScreenPoint(camera.widthPx / 2.0, camera.heightPx / 2.0))
            ?: (camera.position + camera.direction * maxOf(limits.minRange, camera.position.z - groundZ))

    /** One finger moved from [from] to [to]: the ground under it goes with it, level; where it is not on the ground, as far for each pixel. */
    public fun pan(camera: ViewCamera, from: ScreenPoint, to: ScreenPoint): CameraPose {
        val a = ground(camera, from)
        val b = ground(camera, to)
        val move = if (a != null && b != null) {
            a - b
        } else {
            // A metre for so many pixels at the distance of what the view turns about, across and along the ground.
            val perPixel = (pivot(camera) - camera.position).length * camera.sseDenominator / camera.heightPx
            val ahead = Vec3(sin(camera.heading), cos(camera.heading), 0.0)
            camera.right * (-(to.x - from.x) * perPixel) + ahead * ((to.y - from.y) * perPixel)
        }
        return CameraPose(camera.position + Vec3(move.x, move.y, 0.0), camera.heading, camera.pitch)
    }

    /** A pinch at [at] that grew by [factor] (the span now over the span before): toward what is between the fingers by that much, within the limits. */
    public fun zoom(camera: ViewCamera, at: ScreenPoint, factor: Double): CameraPose {
        if (!(factor > 0) || !factor.isFinite()) return pose(camera)
        val target = ground(camera, at) ?: (camera.position + camera.rayThrough(at.x, at.y) * (pivot(camera) - camera.position).length)
        val away = camera.position - target
        val range = (away.length / factor).coerceIn(limits.minRange, maxOf(limits.maxRange, away.length))
        var position = target + away.normalized() * range
        if (position.z < groundZ + limits.minAboveGround) position = Vec3(position.x, position.y, groundZ + limits.minAboveGround)
        return CameraPose(position, camera.heading, camera.pitch)
    }

    /** A twist by [angle] radians clockwise on the screen: the view turns with the fingers, about the ground at its middle. */
    public fun turn(camera: ViewCamera, angle: Double): CameraPose {
        val pivot = pivot(camera)
        val offset = camera.position - pivot
        // A clockwise twist turns the heading back by as much, which swings the camera anticlockwise about the pivot.
        val c = cos(angle)
        val s = sin(angle)
        val turned = Vec3(offset.x * c - offset.y * s, offset.x * s + offset.y * c, offset.z)
        return CameraPose(pivot + turned, camera.heading - angle, camera.pitch)
    }

    /** Two fingers moved up the view by [by] radians' worth: toward the horizon (down the view: toward looking straight down), about its middle. */
    public fun tilt(camera: ViewCamera, by: Double): CameraPose {
        val pivot = pivot(camera)
        val range = (camera.position - pivot).length
        // No flatter than leaves the camera above the ground at that range.
        val rise = (groundZ + limits.minAboveGround - pivot.z).coerceAtLeast(0.0)
        val aboveGround = if (range > rise) -asin((rise / range).coerceAtMost(1.0)) else limits.minPitch
        val pitch = (camera.pitch + by).coerceIn(limits.minPitch, maxOf(limits.minPitch, minOf(limits.maxPitch, aboveGround)))
        return orbit(pivot, range, camera.heading, pitch)
    }

    /** Looking at [pivot] from [range] metres at [heading] and [pitch]. */
    public fun orbit(pivot: Vec3, range: Double, heading: Double, pitch: Double): CameraPose {
        val look = Vec3(sin(heading) * cos(pitch), cos(heading) * cos(pitch), sin(pitch))
        return CameraPose(pivot - look * range, heading, pitch)
    }

    /** Facing north about the ground at the middle of the view, as far away and as steep (the web's compass, `Compass3D.jsx`). */
    public fun facingNorth(camera: ViewCamera): CameraPose {
        val pivot = pivot(camera)
        return orbit(pivot, (camera.position - pivot).length, 0.0, camera.pitch)
    }

    private fun pose(camera: ViewCamera) = CameraPose(camera.position, camera.heading, camera.pitch)

    private companion object {
        const val RAY_EPSILON = 1e-6

        // A ray meeting the ground further than this many times the furthest the camera goes is grazing the horizon: no ground to hold.
        const val REACH = 4.0
    }
}

/**
 * The camera on its way from [from] to [to] over [durationMs] (the web's flights: [SceneLook.FRAME_MS] to frame the LZ, [SceneLook.FACE_NORTH_MS]
 * to face north), eased in and out as Cesium eases a flight that does not come down from high up (`EasingFunction.QUINTIC_IN_OUT`, its
 * default under 11.5 km), turning the short way round. In a straight line: Cesium's goes by longitude, latitude and height, which over the few
 * hundred metres of these flights is the same line to the millimetre.
 */
public class CameraFlight(public val from: CameraPose, public val to: CameraPose, public val durationMs: Long) {
    /** Where it is [elapsedMs] after it set off. */
    public fun at(elapsedMs: Long): CameraPose {
        val t = if (durationMs <= 0) 1.0 else (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        val e = easing(t)
        val turn = ((to.heading - from.heading) % (2 * PI) + 3 * PI) % (2 * PI) - PI
        return CameraPose(from.position + (to.position - from.position) * e, from.heading + turn * e, from.pitch + (to.pitch - from.pitch) * e)
    }

    public fun done(elapsedMs: Long): Boolean = elapsedMs >= durationMs

    public companion object {
        /** Tween.js's `Easing.Quintic.InOut`, which Cesium's `QUINTIC_IN_OUT` is. */
        public fun easing(t: Double): Double {
            val u = t * 2
            if (u < 1) return 0.5 * u * u * u * u * u
            val v = u - 2
            return 0.5 * (v * v * v * v * v + 2)
        }
    }
}
