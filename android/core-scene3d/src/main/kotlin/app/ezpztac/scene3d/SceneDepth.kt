package app.ezpztac.scene3d

import kotlin.math.log2
import kotlin.math.max

/**
 * How the view writes depth: logarithmically, the depth of a point [distance] metres in front of the eye being
 * `log2(distance / NEAR) / log2(FAR / NEAR)`. A 24-bit buffer then holds about a millionth of any distance from a tenth of a metre to a thousand
 * kilometres, where the usual depth holds a metre's worth to a tenth of a millimetre and a kilometre's to a decimetre, which the eye-dome lighting
 * would read as relief that is not there. Cesium writes a logarithmic depth too (its default). A point is a single vertex, so writing it in the
 * vertex shader is exact.
 *
 * The eye-dome lighting compares the logs of distances, which are the depths times [logRange]: `log2(d / NEAR) = depth × logRange`, and the
 * near plane's own log cancels between a point and its neighbour ([response]).
 */
public object SceneDepth {
    public const val NEAR: Double = 0.1
    public const val FAR: Double = 1_000_000.0

    /** `log2(FAR / NEAR)`: the octaves the depth spans. */
    public val logRange: Double = log2(FAR / NEAR)

    /** The depth written for a point [distance] metres in front of the eye: 0 at [NEAR] (or nearer), 1 at [FAR], past 1 beyond it (clipped). */
    public fun depthOf(distance: Double): Double = log2(max(distance, NEAR) / NEAR) / logRange

    /**
     * Cesium's eye-dome lighting response of a point at depth [point] to a neighbour at depth [neighbour]: how many octaves nearer the neighbour
     * is, `max(0, log2(d) - log2(dNeighbour))`, or nothing where it is not nearer.
     */
    public fun response(point: Double, neighbour: Double): Double = max(0.0, (point - neighbour) * logRange)
}

/**
 * The level ground the camera's moves hold to ([CameraMoves.groundZ]) until the terrain is drawn, from the first points to come: the middle height
 * of those within [radius] metres of the LZ (a landing zone is open ground, so they are its surface), else of all of them. Null with no points:
 * the camera then holds to the middle of the cloud's volume, never to a height made up.
 */
public object SceneGround {
    public const val RADIUS: Double = 30.0

    /** The fewest points near the LZ that are its ground; fewer, and every point is taken. */
    public const val ENOUGH: Int = 20

    public fun of(points: ScenePoints, radius: Double = RADIUS): Double? {
        if (points.count == 0) return null
        val p = points.positions
        val near = ArrayList<Float>()
        for (i in 0 until points.count) {
            val x = p[3 * i]
            val y = p[3 * i + 1]
            if (x * x + y * y <= radius * radius) near += p[3 * i + 2]
        }
        val heights = if (near.size >= ENOUGH) near.toFloatArray() else FloatArray(points.count) { p[3 * it + 2] }
        heights.sort()
        val n = heights.size
        return if (n % 2 == 1) heights[n / 2].toDouble() else (heights[n / 2 - 1].toDouble() + heights[n / 2]) / 2
    }
}
