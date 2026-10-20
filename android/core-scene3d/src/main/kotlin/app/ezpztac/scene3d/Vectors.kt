package app.ezpztac.scene3d

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** A point or a direction, in metres. Doubles: a geocentric coordinate is millions of metres, and a float holds it only to half a metre. */
public data class Vec3(val x: Double, val y: Double, val z: Double) {
    public operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)

    public operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)

    public operator fun times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)

    public operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    public infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    public infix fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    public val length: Double get() = sqrt(x * x + y * y + z * z)

    /** Its length worked out without squaring past a double's range, for numbers a file gave: 1e300 is 1e300, not infinite. */
    public val reach: Double get() = hypot(hypot(x, y), z)

    /** This direction with a length of one; the zero vector stays zero. */
    public fun normalized(): Vec3 = length.let { if (it == 0.0) this else Vec3(x / it, y / it, z / it) }

    public val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    public companion object {
        public val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
        public val UNIT_X: Vec3 = Vec3(1.0, 0.0, 0.0)
        public val UNIT_Y: Vec3 = Vec3(0.0, 1.0, 0.0)
        public val UNIT_Z: Vec3 = Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * A 4 × 4 matrix in column-major order, as 3D Tiles writes a tile's `transform` and OpenGL takes a uniform: element (row, column) is at
 * `column * 4 + row`, and the translation is the last column.
 */
public class Mat4 private constructor(private val m: DoubleArray) {
    public operator fun get(row: Int, column: Int): Double = m[column * 4 + row]

    public operator fun times(o: Mat4): Mat4 {
        val out = DoubleArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var sum = 0.0
            for (k in 0 until 4) sum += this[r, k] * o[k, c]
            out[c * 4 + r] = sum
        }
        return Mat4(out)
    }

    /** A point moved by the whole matrix (and divided by w, which an affine transform leaves at one). */
    public fun transformPoint(p: Vec3): Vec3 {
        val x = this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2] * p.z + this[0, 3]
        val y = this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2] * p.z + this[1, 3]
        val z = this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2] * p.z + this[2, 3]
        val w = this[3, 0] * p.x + this[3, 1] * p.y + this[3, 2] * p.z + this[3, 3]
        return if (w == 1.0) Vec3(x, y, z) else Vec3(x / w, y / w, z / w)
    }

    /** A direction turned and scaled by the matrix's upper 3 × 3, with no translation. */
    public fun transformDirection(v: Vec3): Vec3 = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z,
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z,
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z,
    )

    /** The largest a direction is stretched by the upper 3 × 3, bounded by its largest column: what a sphere's radius is scaled by. */
    public val maximumScale: Double
        get() = maxOf(
            Vec3(this[0, 0], this[1, 0], this[2, 0]).reach,
            Vec3(this[0, 1], this[1, 1], this[2, 1]).reach,
            Vec3(this[0, 2], this[1, 2], this[2, 2]).reach,
        )

    /** Whether the bottom row is (0, 0, 0, 1): every transform a tileset may give, which 3D Tiles requires to be affine. */
    public val isAffine: Boolean get() = this[3, 0] == 0.0 && this[3, 1] == 0.0 && this[3, 2] == 0.0 && this[3, 3] == 1.0

    public val isFinite: Boolean get() = m.all { it.isFinite() }

    /** The inverse, or null for a matrix that has none (a determinant too near zero to trust). */
    public fun inverse(): Mat4? {
        val a = m
        val inv = DoubleArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (!det.isFinite() || abs(det) < 1e-300) return null
        return Mat4(DoubleArray(16) { inv[it] / det })
    }

    /** The matrix as OpenGL takes it, column-major, in floats: for one already brought near the scene's origin. */
    public fun toFloatArray(): FloatArray = FloatArray(16) { m[it].toFloat() }

    /** The sixteen values in column-major order. */
    public fun toList(): List<Double> = m.toList()

    override fun equals(other: Any?): Boolean = other is Mat4 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = m.joinToString(prefix = "Mat4[", postfix = "]")

    public companion object {
        public val IDENTITY: Mat4 = Mat4(DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 })

        /** Sixteen values in column-major order, as a tile's `transform` holds them. */
        public fun columnMajor(values: List<Double>): Mat4 {
            require(values.size == 16) { "A 4 × 4 matrix has 16 values, not ${values.size}" }
            return Mat4(values.toDoubleArray())
        }

        public fun translation(v: Vec3): Mat4 = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, v.x, v.y, v.z, 1.0))

        /** A matrix whose columns are [x], [y] and [z] (where this frame's axes point, in the other) and whose origin is [origin]. */
        public fun fromAxes(x: Vec3, y: Vec3, z: Vec3, origin: Vec3 = Vec3.ZERO): Mat4 =
            Mat4(doubleArrayOf(x.x, x.y, x.z, 0.0, y.x, y.y, y.z, 0.0, z.x, z.y, z.z, 0.0, origin.x, origin.y, origin.z, 1.0))
    }
}
