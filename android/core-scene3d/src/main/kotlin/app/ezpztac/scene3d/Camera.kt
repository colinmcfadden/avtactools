package app.ezpztac.scene3d

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the scene is seen from, in the scene's frame: a position, which way it faces ([heading], radians clockwise from north; [pitch], radians
 * up from level, −π/2 straight down) with no roll, and a perspective as Cesium's default camera has it ([fov] 60°, across the wider of the
 * view's sides). [pixelRatio] is how many of the buffer's pixels make one of the web's CSS pixels (the screen's density): Cesium measures the
 * screen-space error and the points' sizes in those, so dividing by it keeps the level of detail and the look the web's on any screen.
 */
public class ViewCamera(
    public val position: Vec3,
    public val heading: Double,
    public val pitch: Double,
    public val widthPx: Int,
    public val heightPx: Int,
    public val near: Double,
    public val far: Double,
    public val pixelRatio: Double = 1.0,
    public val fov: Double = PI / 3,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "A view of ${widthPx}x$heightPx" }
        require(near > 0 && far > near) { "Near $near and far $far" }
    }

    public val aspect: Double = widthPx.toDouble() / heightPx

    /** The vertical field of view: Cesium's `fov` is across the width when the view is wider than tall, else up and down. */
    public val fovy: Double = if (aspect <= 1.0) fov else 2.0 * atan(tan(fov * 0.5) / aspect)

    /** Cesium's `sseDenominator`. */
    public val sseDenominator: Double = 2.0 * tan(0.5 * fovy)

    public val direction: Vec3 = Vec3(sin(heading) * cos(pitch), cos(heading) * cos(pitch), sin(pitch))

    /** To the right of the view, level: defined by the heading, so looking straight down has one too. */
    public val right: Vec3 = Vec3(cos(heading), -sin(heading), 0.0)

    public val up: Vec3 = right cross direction

    /** The six planes it sees between, each facing in. */
    public val frustum: Frustum = run {
        val ty = tan(fovy * 0.5)
        val tx = ty * aspect
        fun through(normal: Vec3, point: Vec3): Plane = normal.normalized().let { n -> Plane(n, -(n dot point)) }
        Frustum(
            listOf(
                through(direction, position + direction * near),
                through(-direction, position + direction * far),
                through(up cross (direction + right * tx), position),
                through((direction - right * tx) cross up, position),
                through((direction + up * ty) cross right, position),
                through(right cross (direction - up * ty), position),
            ),
        )
    }

    /** Scene to eye coordinates, OpenGL's way (the eye looks down its −z). */
    public val view: Mat4 = Mat4.columnMajor(
        listOf(
            right.x, up.x, -direction.x, 0.0,
            right.y, up.y, -direction.y, 0.0,
            right.z, up.z, -direction.z, 0.0,
            -(right dot position), -(up dot position), direction dot position, 1.0,
        ),
    )

    /** Eye to clip coordinates: OpenGL's perspective, `[-1, 1]` in depth. */
    public val projection: Mat4 = run {
        val f = 1.0 / tan(fovy * 0.5)
        Mat4.columnMajor(
            listOf(
                f / aspect, 0.0, 0.0, 0.0,
                0.0, f, 0.0, 0.0,
                0.0, 0.0, (far + near) / (near - far), -1.0,
                0.0, 0.0, 2.0 * far * near / (near - far), 0.0,
            ),
        )
    }

    /** The ray from the eye through a point of the view ([x], [y] in pixels from the top left), as a direction. */
    public fun rayThrough(x: Double, y: Double): Vec3 {
        val ty = tan(fovy * 0.5)
        val nx = (2.0 * x / widthPx - 1.0) * ty * aspect
        val ny = (1.0 - 2.0 * y / heightPx) * ty
        return (direction + right * nx + up * ny).normalized()
    }

    public fun copy(
        position: Vec3 = this.position,
        heading: Double = this.heading,
        pitch: Double = this.pitch,
        widthPx: Int = this.widthPx,
        heightPx: Int = this.heightPx,
        near: Double = this.near,
        far: Double = this.far,
        pixelRatio: Double = this.pixelRatio,
    ): ViewCamera = ViewCamera(position, heading, pitch, widthPx, heightPx, near, far, pixelRatio, fov)

    public companion object {
        /** The heading and pitch of [direction] in the scene's frame. */
        public fun orientationOf(direction: Vec3): Pair<Double, Double> {
            val d = direction.normalized()
            return atan2(d.x, d.y) to asin(d.z.coerceIn(-1.0, 1.0))
        }
    }
}

/**
 * Where a camera goes to look at [center] from [heading] and [pitch] (radians) at [range] metres: Cesium's `Camera.lookAt` with a
 * `HeadingPitchRange`, whose offset is worked out in the east-north-up frame at the centre itself ([frame]'s axes there), so a centre away from
 * the scene's origin is looked at exactly as Cesium would. Returns the camera's position and its heading and pitch in the scene's frame.
 *
 * The heading is the one Cesium's camera turns out with (`lookAtTransform`): its right is the look direction across the centre's up, or the
 * centre's east when the look has no direction across it, so looking straight down faces north, and looking nearly straight down keeps the
 * heading asked for. Read from the look alone, a heading near the vertical swings with the few arcseconds between the centre's up and the
 * scene's (ten degrees at 89.99°, 200 m from the origin).
 */
public fun lookAt(frame: LocalFrame, center: Vec3, heading: Double, pitch: Double, range: Double): Triple<Vec3, Double, Double> {
    val p = pitch.coerceIn(-PI / 2, PI / 2)
    val at = frame.toGeodetic(center)
    val (east, north, up) = frame.axesAt(at.lat, at.lon)
    // Cesium's offsetFromHeadingPitchRange: the direction the camera looks, at that heading and pitch, negated and scaled by the range.
    val look = east * (sin(heading) * cos(p)) + north * (cos(heading) * cos(p)) + up * sin(p)
    val position = center - look * range
    val across = look cross up
    val right = if (across dot across < 1e-10) east else across.normalized()
    return Triple(position, atan2(-right.y, right.x), asin(look.normalized().z.coerceIn(-1.0, 1.0)))
}
