package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.tan

/** How the view looks, held to the web's settings and to the twister Cesium samples tiles with (contracts/fixtures/viewer3d/scene.json), and the sizes it gives. */
class SceneLookTest {
    private val fixture = Fixtures.load("viewer3d/scene.json")

    @Test
    fun `the settings are the web's`() {
        val s = fixture.getValue("settings").jsonObject
        assertEquals("#05080d", s.getValue("BACKGROUND").jsonPrimitive.content)
        assertEquals(0xFF05080D.toInt(), SceneLook.BACKGROUND)
        assertEquals(s.getValue("CAMERA_HEADING_DEG").jsonPrimitive.double, SceneLook.CAMERA_HEADING_DEG)
        assertEquals(s.getValue("CAMERA_PITCH_DEG").jsonPrimitive.double, SceneLook.CAMERA_PITCH_DEG)
        assertEquals(s.getValue("CAMERA_RANGE_FACTOR").jsonPrimitive.double, SceneLook.CAMERA_RANGE_FACTOR)
        assertEquals(s.getValue("DEFAULT_POINT_SIZE_PX").jsonPrimitive.int, SceneLook.DEFAULT_POINT_SIZE)
        assertEquals(listOf(SceneLook.MIN_POINT_SIZE, SceneLook.MAX_POINT_SIZE), s.getValue("POINT_SIZE_RANGE").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(s.getValue("CORE_SCREEN_SPACE_ERROR").jsonPrimitive.double, SceneLook.CORE_SCREEN_SPACE_ERROR)
        assertEquals(s.getValue("CONTEXT_SCREEN_SPACE_ERROR").jsonPrimitive.double, SceneLook.CONTEXT_SCREEN_SPACE_ERROR)
        assertEquals(s.getValue("EDL_STRENGTH").jsonPrimitive.double, SceneLook.EDL_STRENGTH)
        assertEquals(s.getValue("EDL_RADIUS").jsonPrimitive.double, SceneLook.EDL_RADIUS)
        assertEquals((s.getValue("FRAME_SECONDS").jsonPrimitive.double * 1000).toLong(), SceneLook.FRAME_MS)
        assertEquals((s.getValue("FACE_NORTH_SECONDS").jsonPrimitive.double * 1000).toLong(), SceneLook.FACE_NORTH_MS)
    }

    @Test
    fun `a point size the person did not choose is the first one`() {
        assertEquals(5, SceneLook.pointSizeOrDefault(null))
        assertEquals(2, SceneLook.pointSizeOrDefault(2))
        assertEquals(16, SceneLook.pointSizeOrDefault(16))
        assertEquals(5, SceneLook.pointSizeOrDefault(1))
        assertEquals(5, SceneLook.pointSizeOrDefault(17))
    }

    @Test
    fun `the twenty draws are Cesium's twister's from seed 0, to the last bit`() {
        val draws = fixture.getValue("pointSample").jsonObject.getValue("draws").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(PointShading.SAMPLES, draws.size)
        assertArrayEquals(draws.toDoubleArray(), PointShading.draws)
        // And the twister goes on as MT19937 does: its 10,000th draw from seed 0 (init_genrand) is MT19937's published 4123659995.
        val twister = Twister(5489)
        repeat(9_999) { twister.nextUInt() }
        assertEquals(4123659995L, twister.nextUInt())
    }

    @Test
    fun `a tile's sample is the first twenty draws over its count, fewer for a tile of fewer points`() {
        assertEquals(listOf(548, 592, 715), PointShading.sampleIndices(1000).take(3).toList())
        assertEquals(20, PointShading.sampleIndices(1000).size)
        assertEquals(listOf(1, 1, 2), PointShading.sampleIndices(3).toList())
        assertEquals(0, PointShading.sampleIndices(0).size)
    }

    @Test
    fun `a tile with no error of its own is sized by the cube root of its sample's box over its points`() {
        assertEquals(cbrt(8.0 * 9.0 * 2.0 / 4000), PointShading.estimate(Vec3(8.0, 9.0, 2.0), 4000), 1e-12)
        assertEquals(0.0, PointShading.estimate(Vec3(8.0, 0.0, 2.0), 4000))                      // flat: a pixel, as Cesium's
        assertEquals(0.0, PointShading.estimate(null, 4000))
        assertEquals(0.0, PointShading.estimate(Vec3(1.0, 1.0, 1.0), 0))
    }

    @Test
    fun `the leaf the fixture's tileset ends in is sized from twenty of its points, read where the file has them`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val leaf = generateSequence(doc.root) { it.children.firstOrNull() }.last()
        val bytes = Fixtures.bytes("viewer3d/tileset/${leaf.content!!.path}")
        val points = PntsReader.read(bytes, leaf.world, frame)
        // Worked again here from the file: py3dtiles writes POSITION first in the binary body, after the header and the feature table's JSON.
        val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val body = 28 + head.getInt(12)
        val raw = PointShading.sampleIndices(points.count).map { i -> DoubleArray(3) { k -> head.getFloat(body + 12 * i + 4 * k).toDouble() } }
        val box = DoubleArray(3) { k -> raw.maxOf { it[k] } - raw.minOf { it[k] } }
        assertEquals(cbrt(box[0] * box[1] * box[2] / points.count), points.estimatedError, 1e-12)
        assertEquals(0.0, leaf.geometricError)
        assertEquals(points.estimatedError, PointShading.geometricError(leaf, points.estimatedError))
        assertEquals(doc.root.geometricError, PointShading.geometricError(doc.root, 0.123))
    }

    @Test
    fun `a point's size is its error over its depth in the buffer's pixels, between a CSS pixel and the cap, and its shade the eye-dome lighting's`() {
        val camera = ViewCamera(Vec3.ZERO, 0.0, 0.0, 1080, 2200, 0.5, 1e5, pixelRatio = 2.75)
        val multiplier = PointShading.depthMultiplier(camera)
        assertEquals(2200 / (2 * tan(PI / 6)), multiplier, 1e-9)                                   // a portrait view: the 60° is up and down
        assertEquals(5 * 2.75, PointShading.maximumPx(5, camera))
        assertEquals(0.5 * 2.75, PointShading.edlRadiusPx(camera))
        assertEquals(2.75, PointShading.minimumPx(camera))
        assertEquals(0.3 / 100 * multiplier, PointShading.sizePx(0.3, 100.0, multiplier, 13.75, 2.75), 1e-12)
        assertEquals(13.75, PointShading.sizePx(0.3, 5.0, multiplier, 13.75, 2.75))
        // Far off, a point is a CSS pixel across, as the web's smallest is, not one of the screen's pixels.
        assertEquals(2.75, PointShading.sizePx(0.3, 5_000.0, multiplier, 13.75, 2.75))
        assertEquals(1.0, PointShading.shade(0.0))
        assertEquals(exp(-0.01 * 300 * 0.5), PointShading.shade(0.01), 1e-15)
    }

    @Test
    fun `a quantized tile is sized from its whole volume, as Cesium takes it`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val json = """{"POINTS_LENGTH":2,"POSITION_QUANTIZED":{"byteOffset":0},"QUANTIZED_VOLUME_OFFSET":[0,0,0],"QUANTIZED_VOLUME_SCALE":[20,40,60]}"""
        var table = json.encodeToByteArray()
        while ((28 + table.size) % 8 != 0) table += ' '.code.toByte()
        val body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(6) { putShort(0) } }.array()
        val head = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        head.put("pnts".encodeToByteArray()).putInt(1).putInt(28 + table.size + body.size).putInt(table.size).putInt(body.size).putInt(0).putInt(0)
        val points = PntsReader.read(head.array() + table + body, frame.toEcef, frame)
        assertEquals(cbrt(20.0 * 40 * 60 / 2), points.estimatedError, 1e-12)
    }
}
