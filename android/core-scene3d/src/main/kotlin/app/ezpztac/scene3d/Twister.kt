package app.ezpztac.scene3d

/**
 * The Mersenne Twister (MT19937) as the `mersenne-twister` package Cesium uses has it: seeded by `init_seed`, drawn by `random()`, a 32-bit
 * integer over 2^32. Only for [PointShading]'s sample of a tile's points, which Cesium draws once, from seed 0, for every tile; the draws are
 * held to the package's own in `contracts/fixtures/viewer3d/scene.json`.
 */
internal class Twister(seed: Int) {
    private val mt = IntArray(N)
    private var mti: Int

    init {
        mt[0] = seed
        for (i in 1 until N) {
            val s = mt[i - 1] xor (mt[i - 1] ushr 30)
            // The package splits the multiply to stay exact in a double; an Int wraps to the same 32 bits.
            mt[i] = 1812433253 * s + i
        }
        mti = N
    }

    /** The next 32-bit draw, as the unsigned number it is. */
    fun nextUInt(): Long {
        if (mti >= N) {
            for (k in 0 until N) {
                val y = (mt[k] and UPPER) or (mt[(k + 1) % N] and LOWER)
                mt[k] = mt[(k + M) % N] xor (y ushr 1) xor (if ((y and 1) != 0) MATRIX_A else 0)
            }
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and TEMPER_B)
        y = y xor ((y shl 15) and TEMPER_C)
        y = y xor (y ushr 18)
        return y.toLong() and 0xFFFF_FFFFL
    }

    /** In [0, 1), with 32 bits of resolution: the package's `random()`. */
    fun random(): Double = nextUInt() * (1.0 / 4294967296.0)

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0df.toInt()
        const val UPPER = 0x80000000.toInt()
        const val LOWER = 0x7fffffff
        const val TEMPER_B = 0x9d2c5680.toInt()
        const val TEMPER_C = 0xefc60000.toInt()
    }
}
