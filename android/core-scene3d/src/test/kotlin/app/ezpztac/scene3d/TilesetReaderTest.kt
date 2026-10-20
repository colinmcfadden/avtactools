package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Reading a tileset as py3dtiles 12.1.1 writes it (contracts/fixtures/viewer3d/tileset), and refusing one that is not. */
class TilesetReaderTest {
    private val frame = LocalFrame(34.591552, -84.128225)

    private fun core() = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)

    private fun ring() = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/context/tileset.json"), "context/tileset.json", frame)

    private fun Tile.walk(): Sequence<Tile> = sequenceOf(this) + children.asSequence().flatMap { it.walk() }

    @Test
    fun `the core is read whole, its previews replaced and its octree added to`() {
        val doc = core()
        val tiles = doc.root.walk().toList()
        assertEquals(15, doc.tileCount)
        assertEquals(15, tiles.size)
        assertEquals("preview.pnts", doc.root.content?.path)
        assertEquals(Refine.REPLACE, doc.root.refine)
        val octree = doc.root.children.single()
        assertEquals("points/r.pnts", octree.content?.path)
        assertEquals(Refine.REPLACE, octree.refine)
        assertTrue(tiles.drop(2).all { it.refine == Refine.ADD })
        assertEquals(listOf(0, 1, 2, 3, 4), tiles.map { it.depth }.distinct().sorted())
        assertTrue(tiles.all { it.content is PointsContent })
        // Leaves carry no error; the root's is the tileset's, an inner node's somewhere between.
        assertTrue(tiles.filter { it.children.isEmpty() }.all { it.geometricError == 0.0 })
        assertEquals(doc.geometricError, doc.root.geometricError, 1e-9)
    }

    @Test
    fun `every tile's transform is composed from the root, and its volume is where its points are`() {
        val doc = core()
        for (tile in doc.root.walk()) {
            val points = PntsReader.read(Fixtures.bytes("viewer3d/tileset/${tile.content!!.path}"), tile.world, frame)
            assertTrue(points.count > 0, tile.toString())
            var furthest = 0.0
            for (i in 0 until points.count) {
                val p = Vec3(points.positions[3 * i].toDouble(), points.positions[3 * i + 1].toDouble(), points.positions[3 * i + 2].toDouble())
                furthest = maxOf(furthest, tile.volume.distanceTo(p))
            }
            // Inside its box, to a float's precision a few hundred metres from the origin. py3dtiles writes the preview's positions rounded (to a
            // quarter of a metre here), so they may stand that little way outside the box; the octree's own tiles are exact.
            val allowed = if (tile.content.path == "preview.pnts") 0.25 else 1e-3
            assertTrue(furthest < allowed, "$tile: a point ${furthest} m outside its volume")
        }
    }

    @Test
    fun `the ring's paths are under its own folder, and its scaling transform is kept`() {
        val doc = ring()
        val tiles = doc.root.walk().toList()
        assertEquals("context/preview.pnts", doc.root.content?.path)
        assertTrue(tiles.drop(1).all { it.content!!.path.startsWith("context/points/") })
        // py3dtiles scales the octree's frame by ten for a cloud this wide: the volumes in the scene are what the points need, not a tenth.
        val octree = doc.root.children.single()
        assertEquals(10.0, octree.world.maximumScale, 1e-9)
        val radius = doc.root.volume.radius
        assertTrue(radius > 90.0 && radius < 200.0, "radius $radius")
    }

    @Test
    fun `a tile's error is scaled as far as its frame stretches it, and the tileset's as far as its root's does`() {
        // Cesium's updateGeometricErrorScale: py3dtiles writes the ring's octree ten times smaller, with a transform that scales it back.
        val doc = ring()
        val octree = doc.root.children.single()
        assertEquals(11.225435428159077 * 10, octree.geometricError, 1e-9)
        assertEquals(280.6358911117573, doc.root.geometricError, 1e-9)                         // the preview's frame does not scale
        assertEquals(280.6358911117573, doc.geometricError, 1e-9)
        assertTrue(octree.children.all { it.geometricError == 0.0 })
        // A root that doubles everything: its error, its children's and the tileset's are all doubled.
        val m = (frame.toEcef * Mat4.fromAxes(Vec3(2.0, 0.0, 0.0), Vec3(0.0, 2.0, 0.0), Vec3(0.0, 0.0, 2.0))).toList().joinToString(",")
        val doubled = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":40,"root":{"transform":[$m],"geometricError":20,$box,"children":[{"geometricError":5,$box}]}}"""
                .encodeToByteArray(), "tileset.json", frame,
        )
        assertEquals(80.0, doubled.geometricError, 1e-9)
        assertEquals(40.0, doubled.root.geometricError, 1e-9)
        assertEquals(10.0, doubled.root.children.single().geometricError, 1e-9)
    }

    @Test
    fun `the ring's points land round the LZ through its octree's scaling frame, the parent's frame taken first`() {
        val doc = ring()
        val leaf = doc.root.children.single().children.first()
        val points = PntsReader.read(Fixtures.bytes("viewer3d/tileset/${leaf.content!!.path}"), leaf.world, frame)
        assertTrue(points.count > 0)
        for (i in 0 until points.count) {
            val p = Vec3(points.positions[3 * i].toDouble(), points.positions[3 * i + 1].toDouble(), points.positions[3 * i + 2].toDouble())
            // contracts/scripts/tileset_fixture.py: the ring is ±90 m round the LZ, on ground some 370 m up.
            assertTrue(kotlin.math.hypot(p.x, p.y) < 140.0 && p.z in 300.0..450.0, "$p is not round the LZ")
            assertTrue(leaf.volume.distanceTo(p) < 1e-2, "$p is outside its tile")
        }
    }

    @Test
    fun `a child's frame is its parent's then its own, not the other way about`() {
        // The root's frame turns the scene's a quarter about its up; the child's moves ten metres along its own x, which the turn makes north.
        val turned = (frame.toEcef * Mat4.fromAxes(Vec3(0.0, 1.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))).toList().joinToString(",")
        val doc = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":10,"root":{"transform":[$turned],"geometricError":5,$box,
                "children":[{"transform":[1,0,0,0,0,1,0,0,0,0,1,0,10,0,0,1],"geometricError":0,$box}]}}""".encodeToByteArray(),
            "tileset.json", frame,
        )
        val child = doc.root.children.single().volume.center
        assertEquals(0.0, child.x, 1e-6)
        assertEquals(10.0, child.y, 1e-6)
        assertEquals(0.0, child.z, 1e-6)
    }

    @Test
    fun `a content with an empty uri is no content, as Cesium takes it`() {
        val doc = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"content":{"uri":""}}}""".encodeToByteArray(), "tileset.json", frame,
        )
        assertNull(doc.root.content)
    }

    @Test
    fun `a tileset inside a tile joins it, under its parent's frame`() {
        val parentJson = """{"asset":{"version":"1.0"},"geometricError":100,"root":{"geometricError":50,"refine":"ADD",
            "transform":[1,0,0,0,0,1,0,0,0,0,1,0,${frame.origin.x},${frame.origin.y},${frame.origin.z},1],
            "boundingVolume":{"box":[0,0,0,10,0,0,0,10,0,0,0,10]},"content":{"uri":"sub/tileset.json"}}}"""
        val doc = TilesetReader.read(parentJson.encodeToByteArray(), "tileset.json", frame)
        val holder = doc.root
        assertTrue(holder.hasTilesetContent)
        assertEquals("sub/tileset.json", holder.content?.path)
        val childJson = """{"asset":{"version":"1.0"},"geometricError":50,"root":{"geometricError":0,
            "transform":[1,0,0,0,0,1,0,0,0,0,1,0,5,0,0,1],"boundingVolume":{"sphere":[0,0,0,1]},"content":{"uri":"leaf.pnts"}}}"""
        val sub = TilesetReader.read(childJson.encodeToByteArray(), "sub/tileset.json", frame, parent = holder)
        assertEquals(listOf(sub.root), holder.children)
        assertEquals(1, sub.root.depth)
        assertEquals(Refine.ADD, sub.root.refine)                                            // the parent's, none of its own
        assertEquals("sub/leaf.pnts", sub.root.content?.path)
        // Its sphere sits five metres along the geocentric x axis from the LZ: the parent's transform then its own.
        val expected = frame.toLocal(frame.origin + Vec3(5.0, 0.0, 0.0))
        assertEquals(expected.x, sub.root.volume.center.x, 1e-6)
        assertEquals(expected.y, sub.root.volume.center.y, 1e-6)
        assertEquals(expected.z, sub.root.volume.center.z, 1e-6)
    }

    @Test
    fun `refine reads as Cesium reads it, in any case, else the parent's, else REPLACE at the root`() {
        fun refineOf(refine: String?): Refine {
            val r = refine?.let { "\"refine\":\"$it\"," } ?: ""
            return TilesetReader.read("""{"asset":{"version":"1.0"},"geometricError":1,"root":{$r"geometricError":1,"boundingVolume":{"sphere":[0,0,0,1]}}}"""
                .encodeToByteArray(), "tileset.json", frame).root.refine
        }
        assertEquals(Refine.REPLACE, refineOf("REPLACE"))
        assertEquals(Refine.REPLACE, refineOf("replace"))
        assertEquals(Refine.ADD, refineOf("ADD"))
        assertEquals(Refine.ADD, refineOf("something else"))
        assertEquals(Refine.REPLACE, refineOf(null))
    }

    @Test
    fun `a region holds its corners, its middle and the bulge between them`() {
        val west = Math.toRadians(-84.13)
        val east = Math.toRadians(-84.12)
        val south = Math.toRadians(34.59)
        val north = Math.toRadians(34.60)
        val json = """{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,"boundingVolume":{"region":[$west,$south,$east,$north,300,450]}}}"""
        val volume = TilesetReader.read(json.encodeToByteArray(), "tileset.json", frame).root.volume
        for ((lat, lon) in listOf(34.59 to -84.13, 34.60 to -84.12, 34.595 to -84.125, 34.59 to -84.12)) {
            for (h in listOf(300.0, 375.0, 450.0)) assertEquals(0.0, volume.distanceTo(frame.fromGeodetic(lat, lon, h)), 1e-6, "$lat, $lon, $h")
        }
    }

    // -- What is refused --------------------------------------------------------------------------------------------------

    private fun refused(json: String): String = assertThrows<TilesetException> { TilesetReader.read(json.encodeToByteArray(), "tileset.json", frame) }.message!!

    private val box = """"boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0,1]}"""

    @Test
    fun `a tileset that is not one is refused, saying why`() {
        assertTrue("Not JSON" in refused("{"))
        assertTrue("an object" in refused("[]"))
        assertTrue("No asset" in refused("""{"geometricError":1,"root":{"geometricError":1,$box}}"""))
        assertTrue("No asset version" in refused("""{"asset":{},"geometricError":1,"root":{"geometricError":1,$box}}"""))
        assertTrue("tileset's geometricError" in refused("""{"asset":{"version":"1.0"},"root":{"geometricError":1,$box}}"""))
        assertTrue("tileset's geometricError" in refused("""{"asset":{"version":"1.0"},"geometricError":"1","root":{"geometricError":1,$box}}"""))
        assertTrue("negative" in refused("""{"asset":{"version":"1.0"},"geometricError":-1,"root":{"geometricError":1,$box}}"""))
        assertTrue("No root" in refused("""{"asset":{"version":"1.0"},"geometricError":1}"""))
        assertTrue("a tile's geometricError" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{$box}}"""))
        assertTrue("no boundingVolume" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1}}"""))
        assertTrue("not a box, a sphere or a region" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,"boundingVolume":{}}}"""))
        assertTrue("a box has 11 values" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,"boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0]}}}"""))
        assertTrue("negative radius" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,"boundingVolume":{"sphere":[0,0,0,-1]}}}"""))
        assertTrue("not affine" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"transform":[1,0,0,1,0,1,0,0,0,0,1,0,0,0,0,1]}}"""))
        assertTrue("a transform has 15 values" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"transform":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0]}}"""))
        assertTrue("children is not a list" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"children":{}}}"""))
        assertTrue("not a tile" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"children":[1]}}"""))
        assertTrue("no uri" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"content":{}}}"""))
        assertTrue("outside the tileset" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"content":{"uri":"../../auth/me"}}}"""))
        assertTrue("outside the tileset" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":{"geometricError":1,$box,"content":{"uri":"https://elsewhere/x.pnts"}}}"""))
    }

    @Test
    fun `a tileset nested past any a tree needs is refused before it is parsed, never ending in a stack overflow`() {
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        assertTrue("Nested deeper" in refused("""{"asset":{"version":"1.0"},"extras":$deep,"geometricError":1,"root":{"geometricError":1,$box}}"""))
        // A tree as deep as the reader takes nests far less than that, and is read.
        val tiles = StringBuilder()
        repeat(TilesetReader.MAX_DEPTH) { tiles.append("""{"geometricError":1,$box,"children":[""") }
        tiles.append("""{"geometricError":0,$box}""")
        repeat(TilesetReader.MAX_DEPTH) { tiles.append("]}") }
        val read = TilesetReader.read("""{"asset":{"version":"1.0"},"geometricError":1,"root":$tiles}""".encodeToByteArray(), "tileset.json", frame)
        assertEquals(TilesetReader.MAX_DEPTH + 1, read.tileCount)
    }

    @Test
    fun `numbers that are finite but make a volume or a frame that is not are refused`() {
        fun saysSo(words: String, json: String) = refused(json).let { assertTrue(words in it, it) }
        // A frame a million million times larger and a box of ten thousand million kilometres: infinite in the scene.
        val huge = """[1e300,0,0,0,0,1e300,0,0,0,0,1e300,0,0,0,0,1]"""
        saysSo("not finite in the scene", """{"asset":{"version":"1.0"},"geometricError":1,"root":{"transform":$huge,"geometricError":0,
            "boundingVolume":{"box":[0,0,0,1e10,0,0,0,1e10,0,0,0,1e10]}}}""")
        // Two such frames, one inside the other: infinite once composed.
        saysSo("not finite once composed", """{"asset":{"version":"1.0"},"geometricError":1,"root":{"transform":$huge,"geometricError":0,$box,
            "children":[{"transform":$huge,"geometricError":0,$box}]}}""")
        // An error a frame stretches past a double's range.
        val stretch = """[1e10,0,0,0,0,1e10,0,0,0,0,1e10,0,0,0,0,1]"""
        saysSo("not finite once scaled", """{"asset":{"version":"1.0"},"geometricError":1,"root":{"transform":$stretch,"geometricError":1e300,$box}}""")
    }


    @Test
    fun `a tileset joins only the tile that holds it, once, and never one of its own`() {
        val m = frame.toEcef.toList().joinToString(",")
        val outer = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":100,"root":{"transform":[$m],"geometricError":50,$box,"content":{"uri":"sub/tileset.json"}}}"""
                .encodeToByteArray(), "tileset.json", frame,
        )
        val holder = outer.root
        // Its root holds the tileset it is in: joining it there again would hold itself, for ever.
        val selfish = """{"asset":{"version":"1.0"},"geometricError":50,"root":{"geometricError":10,$box,"content":{"uri":"tileset.json"}}}"""
        assertTrue("does not hold" in assertThrows<TilesetException> { TilesetReader.read(selfish.encodeToByteArray(), "other/tileset.json", frame, parent = holder) }.message!!)
        val sub = TilesetReader.read(selfish.encodeToByteArray(), "sub/tileset.json", frame, parent = holder)
        assertEquals("sub/tileset.json", sub.root.content?.path)
        assertTrue("joined already" in assertThrows<TilesetException> { TilesetReader.read(selfish.encodeToByteArray(), "sub/tileset.json", frame, parent = holder) }.message!!)
        assertTrue("holds itself" in assertThrows<TilesetException> { TilesetReader.read(selfish.encodeToByteArray(), "sub/tileset.json", frame, parent = sub.root) }.message!!)
        assertEquals(listOf(sub.root), holder.children)
        assertTrue(sub.root.children.isEmpty())
    }

    @Test
    fun `the tiles of every tileset joined into a tree are counted together, and one that would pass the limit leaves the tree as it was`() {
        val m = frame.toEcef.toList().joinToString(",")
        val outer = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":100,"root":{"transform":[$m],"geometricError":50,$box,"content":{"uri":"sub/tileset.json"}}}"""
                .encodeToByteArray(), "tileset.json", frame,
        )
        val holder = outer.root
        assertEquals(1, holder.tree.tiles)
        val two = """{"asset":{"version":"1.0"},"geometricError":50,"root":{"geometricError":10,$box,"children":[{"geometricError":0,$box}]}}"""
        holder.tree.tiles = TilesetReader.MAX_TILES - 1
        assertTrue("More than" in assertThrows<TilesetException> { TilesetReader.read(two.encodeToByteArray(), "sub/tileset.json", frame, parent = holder) }.message!!)
        assertEquals(TilesetReader.MAX_TILES - 1, holder.tree.tiles)
        assertTrue(holder.children.isEmpty())
        holder.tree.tiles = 1
        val sub = TilesetReader.read(two.encodeToByteArray(), "sub/tileset.json", frame, parent = holder)
        assertEquals(2, sub.tileCount)
        assertEquals(3, holder.tree.tiles)
    }

    @Test
    fun `a tileset read apart leaves the tree as it was until it is joined, and is refused if the tile joined meanwhile or the tree filled up`() {
        val m = frame.toEcef.toList().joinToString(",")
        fun outer() = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":100,"root":{"transform":[$m],"geometricError":50,$box,"content":{"uri":"sub/tileset.json"}}}"""
                .encodeToByteArray(), "tileset.json", frame,
        )
        val two = """{"asset":{"version":"1.0"},"geometricError":50,"root":{"geometricError":10,$box,"children":[{"geometricError":0,$box}]}}""".encodeToByteArray()
        val holder = outer().root
        val read = TilesetReader.readApart(two, "sub/tileset.json", frame, parent = holder)
        assertTrue(holder.children.isEmpty())
        assertEquals(1, holder.tree.tiles)
        assertEquals(2, read.tileCount)
        TilesetReader.join(holder, read)
        assertEquals(listOf(read.root), holder.children)
        assertEquals(3, holder.tree.tiles)
        // Read twice apart, the second is refused once the first has joined, and leaves the tree as it was.
        val again = outer().root
        val first = TilesetReader.readApart(two, "sub/tileset.json", frame, parent = again)
        val second = TilesetReader.readApart(two, "sub/tileset.json", frame, parent = again)
        TilesetReader.join(again, first)
        assertTrue("has joined already" in assertThrows<TilesetException> { TilesetReader.join(again, second) }.message!!)
        assertEquals(listOf(first.root), again.children)
        assertEquals(3, again.tree.tiles)
        // Read for another tile; or the tree filled up meanwhile.
        assertTrue("not read for this tile" in assertThrows<TilesetException> { TilesetReader.join(outer().root, read) }.message!!)
        val full = outer().root
        val late = TilesetReader.readApart(two, "sub/tileset.json", frame, parent = full)
        full.tree.tiles = TilesetReader.MAX_TILES - 1
        assertTrue("More than" in assertThrows<TilesetException> { TilesetReader.join(full, late) }.message!!)
        assertTrue(full.children.isEmpty())
        assertEquals(TilesetReader.MAX_TILES - 1, full.tree.tiles)
    }

    @Test
    fun `a tree too deep or too large is refused before it is all read`() {
        val deep = StringBuilder()
        repeat(TilesetReader.MAX_DEPTH + 2) { deep.append("""{"geometricError":1,$box,"children":[""") }
        deep.append("""{"geometricError":0,$box}""")
        repeat(TilesetReader.MAX_DEPTH + 2) { deep.append("]}") }
        assertTrue("deeper" in refused("""{"asset":{"version":"1.0"},"geometricError":1,"root":$deep}"""))
        assertTrue("bytes is more" in assertThrows<TilesetException> { TilesetReader.read(ByteArray(TilesetReader.MAX_BYTES + 1), "tileset.json", frame) }.message!!)
    }

    // -- Paths in the store -----------------------------------------------------------------------------------------------

    @Test
    fun `a content's path is under the tileset that names it, and never leaves the store`() {
        assertEquals("points/r0.pnts", TilesetPaths.resolve("tileset.json", "points/r0.pnts"))
        assertEquals("context/points/r0.pnts", TilesetPaths.resolve("context/tileset.json", "points/r0.pnts"))
        assertEquals("points/r0.pnts", TilesetPaths.resolve("context/tileset.json", "../points/r0.pnts"))
        assertEquals("a/b.pnts", TilesetPaths.resolve("tileset.json", "./a/b.pnts"))
        for (bad in listOf("../x.pnts", "/points/r0.pnts", "https://x/y.pnts", "//host/y.pnts", "a?b.pnts", "a#b", "%2e%2e/x", "a\\b.pnts",
                           "data:application/octet-stream;base64,AA", "", "a b.pnts", "a//b.pnts", ".hidden/x.pnts", "..", ".")) {
            assertNull(TilesetPaths.resolve("tileset.json", bad), bad)
        }
        assertNull(TilesetPaths.resolve("context/tileset.json", "../../x.pnts"))
    }
}
