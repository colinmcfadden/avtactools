package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reading a `.pnts` tile: py3dtiles's own (the fixture tileset), the other encodings the format allows, and a file that is not one. */
class PntsReaderTest {
    private val frame = LocalFrame(34.591552, -84.128225)

    /** A `.pnts` as the format lays one out: the header, the feature table's JSON padded to eight bytes, then its binary body. */
    private fun pnts(table: String, body: ByteArray = ByteArray(0), version: Int = 1, magic: String = "pnts", byteLength: Int? = null): ByteArray {
        var json = table.encodeToByteArray()
        while ((28 + json.size) % 8 != 0) json += ' '.code.toByte()
        val total = 28 + json.size + body.size
        val head = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        head.put(magic.encodeToByteArray()).putInt(version).putInt(byteLength ?: total).putInt(json.size).putInt(body.size).putInt(0).putInt(0)
        return ByteArrayOutputStream().apply { write(head.array()); write(json); write(body) }.toByteArray()
    }

    private fun floats(vararg v: Float): ByteArray = ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

    private fun shorts(vararg v: Int): ByteArray = ByteBuffer.allocate(2 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putShort(it.toShort()) } }.array()

    // A tile whose frame is the scene's, so what is read is what was written.
    private val here = frame.toEcef

    @Test
    fun `py3dtiles's tiles read as the points the fixture was made from`() {
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val leaf = generateSequence(doc.root) { it.children.firstOrNull() }.last()
        val points = PntsReader.read(Fixtures.bytes("viewer3d/tileset/${leaf.content!!.path}"), leaf.world, frame)
        assertTrue(points.count > 100)
        for (i in 0 until points.count) {
            val p = frame.toGeodetic(Vec3(points.positions[3 * i].toDouble(), points.positions[3 * i + 1].toDouble(), points.positions[3 * i + 2].toDouble()))
            // contracts/scripts/tileset_fixture.py: ±30 m round the LZ, ground at 369.65 m above the ellipsoid ±5, trees to 25 m on it.
            assertTrue(p.lat in 34.5912..34.5919 && p.lon in -84.1286..-84.1278, "$p is not near the LZ")
            assertTrue(p.height in 363.0..398.0, "$p is not on the ground or a tree")
            val colour = Triple(points.rgb[3 * i].toInt() and 0xFF, points.rgb[3 * i + 1].toInt() and 0xFF, points.rgb[3 * i + 2].toInt() and 0xFF)
            assertTrue(colour == Triple(40, 110, 40) || colour == Triple(150, 130, 100), "colour $colour")
        }
    }

    @Test
    fun `float positions are moved by the tile's frame and its RTC centre`() {
        val shifted = Mat4.translation(frame.origin) * Mat4.fromAxes(Vec3(10.0, 0.0, 0.0), Vec3(0.0, 10.0, 0.0), Vec3(0.0, 0.0, 10.0))
        val file = pnts("""{"POINTS_LENGTH":2,"RTC_CENTER":[1,2,3],"POSITION":{"byteOffset":0}}""", floats(0f, 0f, 0f, 1f, 0f, 0f))
        val points = PntsReader.read(file, shifted, frame)
        assertEquals(2, points.count)
        val first = frame.toLocal(frame.origin + Vec3(10.0, 20.0, 30.0))
        val second = frame.toLocal(frame.origin + Vec3(20.0, 20.0, 30.0))
        assertEquals(first.x, points.positions[0].toDouble(), 1e-4)
        assertEquals(first.z, points.positions[2].toDouble(), 1e-4)
        assertEquals(second.y, points.positions[4].toDouble(), 1e-4)
        assertArrayEquals(ByteArray(6) { 169.toByte() }, points.rgb)                                  // no colour: Cesium's dark grey
    }

    @Test
    fun `quantized positions are the volume's offset and scale`() {
        val file = pnts(
            """{"POINTS_LENGTH":2,"POSITION_QUANTIZED":{"byteOffset":0},"QUANTIZED_VOLUME_OFFSET":[-10,-20,-30],"QUANTIZED_VOLUME_SCALE":[20,40,60],"RGB565":{"byteOffset":12}}""",
            shorts(0, 0, 0, 65535, 32768, 65535) + shorts(0xF800, 0x07E0),
        )
        val points = PntsReader.read(file, here, frame)
        assertEquals(-10.0, points.positions[0].toDouble(), 1e-4)
        assertEquals(-20.0, points.positions[1].toDouble(), 1e-4)
        assertEquals(-30.0, points.positions[2].toDouble(), 1e-4)
        assertEquals(10.0, points.positions[3].toDouble(), 1e-4)
        assertEquals(-20.0 + 32768 * 40.0 / 65535, points.positions[4].toDouble(), 1e-4)
        assertEquals(30.0, points.positions[5].toDouble(), 1e-4)
        assertArrayEquals(byteArrayOf(-1, 0, 0, 0, -1, 0), points.rgb)                                // red, then green
    }

    @Test
    fun `RGBA keeps its colour and drops its alpha, and one colour may stand for all`() {
        val rgba = pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"RGBA":{"byteOffset":12}}""", floats(0f, 0f, 0f) + byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3), PntsReader.read(rgba, here, frame).rgb)
        val constant = pnts("""{"POINTS_LENGTH":2,"POSITION":{"byteOffset":0},"CONSTANT_RGBA":[9,8,7,255]}""", floats(0f, 0f, 0f, 1f, 1f, 1f))
        assertArrayEquals(byteArrayOf(9, 8, 7, 9, 8, 7), PntsReader.read(constant, here, frame).rgb)
        val binaryCentre = pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"RTC_CENTER":{"byteOffset":12}}""", floats(1f, 1f, 1f, 2f, 3f, 4f))
        val p = PntsReader.read(binaryCentre, here, frame)
        assertEquals(3.0, p.positions[0].toDouble(), 1e-4)
        assertEquals(5.0, p.positions[2].toDouble(), 1e-4)
    }

    @Test
    fun `a point that is not a number is dropped, never drawn somewhere made up`() {
        val file = pnts("""{"POINTS_LENGTH":3,"POSITION":{"byteOffset":0},"RGB":{"byteOffset":36}}""",
                        floats(1f, 1f, 1f, Float.NaN, 0f, 0f, 2f, 2f, 2f) + byteArrayOf(1, 1, 1, 2, 2, 2, 3, 3, 3))
        val points = PntsReader.read(file, here, frame)
        assertEquals(2, points.count)
        assertEquals(6, points.positions.size)
        assertArrayEquals(byteArrayOf(1, 1, 1, 3, 3, 3), points.rgb)
    }

    @Test
    fun `RGBA is read four bytes a point, each point its own colour`() {
        val file = pnts(
            """{"POINTS_LENGTH":3,"POSITION":{"byteOffset":0},"RGBA":{"byteOffset":36}}""",
            floats(0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f) + byteArrayOf(1, 2, 3, 99, 4, 5, 6, 99, 7, 8, 9, 99),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), PntsReader.read(file, here, frame).rgb)
    }

    @Test
    fun `a point a float cannot hold is dropped too, its double finite or not`() {
        // In a frame ten times larger than the scene, 3e38 is past a float's range once it is moved into the scene.
        val tenTimes = here * Mat4.fromAxes(Vec3(10.0, 0.0, 0.0), Vec3(0.0, 10.0, 0.0), Vec3(0.0, 0.0, 10.0))
        val file = pnts("""{"POINTS_LENGTH":2,"POSITION":{"byteOffset":0}}""", floats(1f, 1f, 1f, 3e38f, 0f, 0f))
        val points = PntsReader.read(file, tenTimes, frame)
        assertEquals(1, points.count)
        assertTrue(points.positions.all { it.isFinite() })
        assertEquals(10.0, points.positions[0].toDouble(), 1e-3)
    }

    // -- What is refused --------------------------------------------------------------------------------------------------

    private fun refused(bytes: ByteArray): String = assertThrows<PntsException> { PntsReader.read(bytes, here, frame) }.message!!

    @Test
    fun `a file that is not a point cloud is refused, saying why`() {
        val good = """{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0}}"""
        assertTrue("shorter than a header" in refused(ByteArray(10)))
        assertTrue("Not a .pnts" in refused(pnts(good, floats(0f, 0f, 0f), magic = "b3dm")))
        assertTrue("Version 2" in refused(pnts(good, floats(0f, 0f, 0f), version = 2)))
        assertTrue("there are" in refused(pnts(good, floats(0f, 0f, 0f), byteLength = 10_000)))
        assertTrue("not JSON" in refused(pnts("{", floats(0f, 0f, 0f))))
        assertTrue("not an object" in refused(pnts("[1]", floats(0f, 0f, 0f))))
        assertTrue("No POINTS_LENGTH" in refused(pnts("""{"POSITION":{"byteOffset":0}}""", floats(0f, 0f, 0f))))
        assertTrue("not a whole number" in refused(pnts("""{"POINTS_LENGTH":1.5,"POSITION":{"byteOffset":0}}""", floats(0f, 0f, 0f))))
        assertTrue("${PntsReader.MAX_POINTS + 1} points" in refused(pnts("""{"POINTS_LENGTH":${PntsReader.MAX_POINTS + 1},"POSITION":{"byteOffset":0}}""")))
        assertTrue("No POSITION" in refused(pnts("""{"POINTS_LENGTH":1}""", floats(0f, 0f, 0f))))
        assertTrue("POSITION runs past" in refused(pnts("""{"POINTS_LENGTH":2,"POSITION":{"byteOffset":0}}""", floats(0f, 0f, 0f))))
        assertTrue("POSITION runs past" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":-4}}""", floats(0f, 0f, 0f))))
        assertTrue("POSITION has no byteOffset" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{}}""", floats(0f, 0f, 0f))))
        assertTrue("RGB runs past" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"RGB":{"byteOffset":12}}""", floats(0f, 0f, 0f) + byteArrayOf(1, 2))))
        assertTrue("No QUANTIZED_VOLUME_OFFSET" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION_QUANTIZED":{"byteOffset":0}}""", shorts(0, 0, 0))))
        assertTrue("RTC_CENTER is not finite" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"RTC_CENTER":{"byteOffset":0}}""", floats(Float.NaN, 0f, 0f))))
        assertTrue("RTC_CENTER has 2 values" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"RTC_CENTER":[1,2]}""", floats(0f, 0f, 0f))))
        assertTrue("CONSTANT_RGBA is not bytes" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"CONSTANT_RGBA":[1,2,3,300]}""", floats(0f, 0f, 0f))))
    }

    @Test
    fun `a feature table nested past any a tile needs is refused before it is parsed, never ending in a stack overflow`() {
        // Ten kilobytes of brackets overflow the parser's stack, an Error no caller catches; refused, it is an exception the view can say.
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        assertTrue("nests deeper" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"EXTRA":$deep}""", floats(0f, 0f, 0f))))
        val justTooDeep = "[".repeat(PntsReader.TABLE_NESTING) + "]".repeat(PntsReader.TABLE_NESTING)
        assertTrue("nests deeper" in refused(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"EXTRA":$justTooDeep}""", floats(0f, 0f, 0f))))
        // Brackets inside a string are text, not nesting.
        val inText = pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"EXTRA":"${"[".repeat(50)}\"]"}""", floats(0f, 0f, 0f))
        assertEquals(1, PntsReader.read(inText, here, frame).count)
        // An escaped quote does not end a string, so brackets after it are still text.
        val escaped = pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},"EXTRA":"\"${"[".repeat(20)}"}""", floats(0f, 0f, 0f))
        assertEquals(1, PntsReader.read(escaped, here, frame).count)
        // What closes is counted off: many objects side by side nest no deeper than one.
        val siblings = (1..20).joinToString(",") { "\"E$it\":{\"x\":[1]}" }
        assertEquals(1, PntsReader.read(pnts("""{"POINTS_LENGTH":1,"POSITION":{"byteOffset":0},$siblings}""", floats(0f, 0f, 0f)), here, frame).count)
    }

    @Test
    fun `a real tile cut short at any length is refused, never read past its end`() {
        val tile = Fixtures.bytes("viewer3d/tileset/points/r1.pnts")
        val step = maxOf(1, tile.size / 300)
        for (length in 0 until tile.size step step) {
            assertThrows<PntsException>("cut at $length") { PntsReader.read(tile.copyOf(length), here, frame) }
        }
    }

    @Test
    fun `a real tile with its lengths changed is refused`() {
        val tile = Fixtures.bytes("viewer3d/tileset/points/r1.pnts")
        for (field in listOf(8, 12, 16)) {
            val changed = tile.copyOf()
            ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(field, Int.MAX_VALUE)
            assertThrows<PntsException>("length at $field") { PntsReader.read(changed, here, frame) }
        }
    }
}
