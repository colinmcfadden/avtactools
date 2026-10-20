package app.ezpztac.scene3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A `.pnts` tile that cannot be read, in words for a log. */
public class PntsException(message: String) : Exception(message)

/**
 * A tile's points in the scene's frame, ready for the GPU: [positions] x, y, z each (metres from the LZ), [rgb] three bytes each, and
 * [estimatedError], Cesium's guess at their spacing ([PointShading.estimate]), which sizes the points of a tile with no geometric error of its own.
 */
public class ScenePoints(public val count: Int, public val positions: FloatArray, public val rgb: ByteArray, public val estimatedError: Double = 0.0) {
    /** What it holds, for a cache measured in bytes. */
    public val bytes: Long get() = positions.size * 4L + rgb.size
}

/**
 * Reads a 3D Tiles 1.0 point cloud (`.pnts`): the 28-byte header, the feature table's JSON and binary body, and of its semantics the ones a
 * point cloud is drawn with: `POINTS_LENGTH`; `POSITION` (float32) or `POSITION_QUANTIZED` (uint16, with `QUANTIZED_VOLUME_OFFSET` and
 * `QUANTIZED_VOLUME_SCALE`); `RTC_CENTER`; and `RGB`, `RGBA`, `RGB565` or `CONSTANT_RGBA` for colour. py3dtiles writes `POSITION` and `RGB`
 * (contracts/fixtures/viewer3d/tileset). The batch table, normals and batch ids are passed over: colour is baked into RGB when a tileset is
 * built (backend/lidar/pipeline.py), and a `.pnts` carries no classification (AGENTS.md §14).
 *
 * A file is untrusted: every length is checked against the file before anything is read, the feature table nests no deeper than a feature
 * table can ([TABLE_NESTING]: a parser recurses into arrays), the point count is capped at [MAX_POINTS] (py3dtiles writes at most 20,000
 * a tile; a million is 15 MB of floats and colours), and a point whose position is not finite, in doubles or once it is a float, is dropped,
 * never drawn somewhere made up.
 */
public object PntsReader {
    public const val MAX_POINTS: Int = 1_000_000

    /** A feature table holds objects of numbers and arrays of them: nothing nests deeper than this. */
    internal const val TABLE_NESTING: Int = 8
    private const val HEADER = 28

    private val json = Json { isLenient = false }

    /** The points of [bytes], a tile whose frame is [world] (to geocentric), moved into [frame]. */
    public fun read(bytes: ByteArray, world: Mat4, frame: LocalFrame): ScenePoints {
        if (bytes.size < HEADER) throw PntsException("${bytes.size} bytes is shorter than a header")
        val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes[0] != 'p'.code.toByte() || bytes[1] != 'n'.code.toByte() || bytes[2] != 't'.code.toByte() || bytes[3] != 's'.code.toByte()) {
            throw PntsException("Not a .pnts")
        }
        val version = head.getInt(4)
        if (version != 1) throw PntsException("Version $version, not 1")
        val byteLength = head.getInt(8).toLong() and 0xFFFFFFFFL
        val tableJson = head.getInt(12).toLong() and 0xFFFFFFFFL
        val tableBinary = head.getInt(16).toLong() and 0xFFFFFFFFL
        val batchJson = head.getInt(20).toLong() and 0xFFFFFFFFL
        val batchBinary = head.getInt(24).toLong() and 0xFFFFFFFFL
        if (byteLength > bytes.size) throw PntsException("The header says $byteLength bytes; there are ${bytes.size}")
        if (HEADER + tableJson + tableBinary + batchJson + batchBinary > byteLength) throw PntsException("The tables run past the tile")
        val text = String(bytes, HEADER, tableJson.toInt(), Charsets.UTF_8)
        if (!JsonNesting.within(text, TABLE_NESTING)) throw PntsException("The feature table nests deeper than $TABLE_NESTING")
        val table = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw PntsException("The feature table is not JSON: ${e.message}")
        } as? JsonObject ?: throw PntsException("The feature table is not an object")
        val bodyStart = HEADER + tableJson.toInt()
        val body = Body(bytes, bodyStart, tableBinary.toInt())

        val count = integer(table["POINTS_LENGTH"], "POINTS_LENGTH")
        if (count < 0 || count > MAX_POINTS) throw PntsException("$count points")

        val rtc = table["RTC_CENTER"]?.let { vector(it, body, "RTC_CENTER") } ?: Vec3.ZERO
        // Each point's place: decoded in doubles, moved by the tile's frame and the RTC centre into the scene, and only then made a float.
        val toScene = frame.fromEcef * world * Mat4.translation(rtc)
        var quantizedScale: Vec3? = null
        val read: (Int) -> Vec3 = when {
            table["POSITION"] != null -> {
                val offset = body.offset(table["POSITION"], 12L * count, "POSITION")
                val positions: (Int) -> Vec3 = { i -> Vec3(body.float(offset + 12 * i).toDouble(), body.float(offset + 12 * i + 4).toDouble(), body.float(offset + 12 * i + 8).toDouble()) }
                positions
            }
            table["POSITION_QUANTIZED"] != null -> {
                val offset = body.offset(table["POSITION_QUANTIZED"], 6L * count, "POSITION_QUANTIZED")
                val origin = vector(table["QUANTIZED_VOLUME_OFFSET"] ?: throw PntsException("No QUANTIZED_VOLUME_OFFSET"), body, "QUANTIZED_VOLUME_OFFSET")
                val scale = vector(table["QUANTIZED_VOLUME_SCALE"] ?: throw PntsException("No QUANTIZED_VOLUME_SCALE"), body, "QUANTIZED_VOLUME_SCALE")
                quantizedScale = scale
                val quantized: (Int) -> Vec3 = { i ->
                    Vec3(
                        origin.x + body.ushort(offset + 6 * i) * scale.x / 65535.0,
                        origin.y + body.ushort(offset + 6 * i + 2) * scale.y / 65535.0,
                        origin.z + body.ushort(offset + 6 * i + 4) * scale.z / 65535.0,
                    )
                }
                quantized
            }
            else -> throw PntsException("No POSITION")
        }
        val colour: (Int, ByteArray, Int) -> Unit = when {
            table["RGBA"] != null -> {
                val offset = body.offset(table["RGBA"], 4L * count, "RGBA")
                val rgba: (Int, ByteArray, Int) -> Unit = { i, out, at -> for (k in 0..2) out[at + k] = body.byte(offset + 4 * i + k) }
                rgba
            }
            table["RGB"] != null -> {
                val offset = body.offset(table["RGB"], 3L * count, "RGB")
                val rgb: (Int, ByteArray, Int) -> Unit = { i, out, at -> for (k in 0..2) out[at + k] = body.byte(offset + 3 * i + k) }
                rgb
            }
            table["RGB565"] != null -> {
                val offset = body.offset(table["RGB565"], 2L * count, "RGB565")
                val rgb565: (Int, ByteArray, Int) -> Unit = { i, out, at ->
                    val v = body.ushort(offset + 2 * i)
                    out[at] = (((v shr 11) and 0x1F) * 255 / 31).toByte()
                    out[at + 1] = (((v shr 5) and 0x3F) * 255 / 63).toByte()
                    out[at + 2] = ((v and 0x1F) * 255 / 31).toByte()
                }
                rgb565
            }
            else -> {
                // One colour for every point, else Cesium's white.
                val constant = (table["CONSTANT_RGBA"] as? JsonArray)?.let { a ->
                    if (a.size != 4) throw PntsException("CONSTANT_RGBA has ${a.size} values")
                    a.map { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { v -> v in 0.0..255.0 }?.toInt()?.toByte() ?: throw PntsException("CONSTANT_RGBA is not bytes") }
                } ?: listOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())
                val fixed: (Int, ByteArray, Int) -> Unit = { _, out, at -> for (k in 0..2) out[at + k] = constant[k] }
                fixed
            }
        }

        val positions = FloatArray(3 * count)
        val rgb = ByteArray(3 * count)
        var kept = 0
        for (i in 0 until count) {
            val p = toScene.transformPoint(read(i))
            val x = p.x.toFloat()
            val y = p.y.toFloat()
            val z = p.z.toFloat()
            // A double past a float's range is infinite as a float.
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            positions[3 * kept] = x
            positions[3 * kept + 1] = y
            positions[3 * kept + 2] = z
            colour(i, rgb, 3 * kept)
            kept++
        }
        // Cesium measures twenty of the file's points where the file has them (a quantized tile: its whole volume), and divides by all of them.
        val sampled = quantizedScale ?: extent(PointShading.sampleIndices(count).map(read))
        val estimated = PointShading.estimate(sampled, count)
        return if (kept == count) {
            ScenePoints(count, positions, rgb, estimated)
        } else {
            ScenePoints(kept, positions.copyOf(3 * kept), rgb.copyOf(3 * kept), estimated)
        }
    }

    /** The size of the box round [points] (the finite ones), or null for none. */
    private fun extent(points: List<Vec3>): Vec3? {
        val finite = points.filter { it.isFinite }
        if (finite.isEmpty()) return null
        return Vec3(finite.maxOf { it.x } - finite.minOf { it.x }, finite.maxOf { it.y } - finite.minOf { it.y }, finite.maxOf { it.z } - finite.minOf { it.z })
    }

    private fun integer(element: JsonElement?, what: String): Int {
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw PntsException("No $what")
        if (value != Math.floor(value) || value > Int.MAX_VALUE) throw PntsException("$what is not a whole number")
        return value.toInt()
    }

    /** A vec3 global: three numbers in the JSON, or a `byteOffset` to three floats in the binary body. */
    private fun vector(element: JsonElement, body: Body, what: String): Vec3 {
        val v = when (element) {
            is JsonArray -> {
                if (element.size != 3) throw PntsException("$what has ${element.size} values")
                element.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: throw PntsException("$what is not numbers") }.let { Vec3(it[0], it[1], it[2]) }
            }
            is JsonObject -> body.offset(element, 12, what).let { o -> Vec3(body.float(o).toDouble(), body.float(o + 4).toDouble(), body.float(o + 8).toDouble()) }
            else -> throw PntsException("$what is neither numbers nor a reference")
        }
        if (!v.isFinite) throw PntsException("$what is not finite")
        return v
    }

    /** The feature table's binary body, read only inside its bounds. */
    private class Body(val bytes: ByteArray, val start: Int, val length: Int) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        /** The start of a semantic's [size] bytes, checked to lie inside the body. */
        fun offset(reference: JsonElement?, size: Long, what: String): Int {
            val o = (reference as? JsonObject)?.get("byteOffset")?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }
                ?: throw PntsException("$what has no byteOffset")
            if (o < 0 || o != Math.floor(o) || o + size > length) throw PntsException("$what runs past the binary body")
            return o.toInt()
        }

        fun float(at: Int): Float = buffer.getFloat(start + at)

        fun ushort(at: Int): Int = buffer.getShort(start + at).toInt() and 0xFFFF

        fun byte(at: Int): Byte = bytes[start + at]
    }
}
