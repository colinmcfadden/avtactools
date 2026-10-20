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
 * The level ground the camera's moves hold to ([CameraMoves.groundZ]) until the terrain is drawn, from the points that have come: the lowest
 * tenth of a tile's points within [RADIUS] metres of the LZ. Not their middle: an LZ is a clearing, and the trees round it put the middle many
 * metres up (ten in the fixture's, `contracts/fixtures/viewer3d/tileset`), where the lowest tenth is the ground under them, past a few stray
 * returns below it. Only a tile with a tenth of those points at least on every side of the LZ counts: py3dtiles splits its octree in three
 * dimensions, so a tile of it near the LZ can hold one side of the ground round it, or only the canopy over it. Null when a tile says nothing
 * (too few points round the LZ, or not on every side): the camera then holds to what it held to before, the cloud's middle at first, never to
 * a height made up.
 */
public object SceneGround {
    public const val RADIUS: Double = 30.0

    /** The fewest points round the LZ that say where its ground is. */
    public const val ENOUGH: Int = 20

    /** The share of the points round the LZ under the height taken for its ground. */
    public const val LOW_SHARE: Double = 0.1

    /** The least share of the points round the LZ on each side of it (east and north of it, west and north, …). */
    public const val SIDE_SHARE: Double = 0.1

    /** What a tile's points say of the ground at the LZ: its height, and from how many points round it (more is better). */
    public class Estimate(public val z: Double, public val points: Int)

    public fun of(points: ScenePoints, radius: Double = RADIUS): Estimate? {
        val p = points.positions
        val near = ArrayList<Float>()
        val sides = IntArray(4)
        for (i in 0 until points.count) {
            val x = p[3 * i]
            val y = p[3 * i + 1]
            if (x * x + y * y > radius * radius) continue
            near += p[3 * i + 2]
            sides[(if (x >= 0) 1 else 0) + (if (y >= 0) 2 else 0)]++
        }
        if (near.size < ENOUGH || sides.any { it < near.size * SIDE_SHARE }) return null
        val heights = near.toFloatArray()
        heights.sort()
        return Estimate(heights[((heights.size - 1) * LOW_SHARE).toInt()].toDouble(), heights.size)
    }
}
