package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AircraftIconsTest {
    /** The airframes drawn as silhouettes: every one but the UH-60, which is its SVG. */
    private val silhouetteKeys = AircraftIcons.KEYS - "uh60"

    private fun shapes(key: String?) = (AircraftIcons.picture(key) as AircraftPicture.Silhouette).shapes

    private fun lines(key: String, colour: IconColor) = shapes(key).filterIsInstance<IconShape.Line>().filter { it.stroke == colour }

    /** A silhouette's rotor rings: the accent's circles, the line round each disc. */
    private fun rings(key: String) = shapes(key).filterIsInstance<IconShape.Circle>().filter { it.stroke == IconColor.ACCENT }

    /** What a shape covers, half its stroke included, as left, top, right, bottom. A curve stays inside its control points, so a path's points bound it. */
    private fun bounds(shape: IconShape): List<Double> = when (shape) {
        is IconShape.Circle -> (shape.r + shape.strokeWidth / 2).let { listOf(shape.cx - it, shape.cy - it, shape.cx + it, shape.cy + it) }
        is IconShape.Ellipse -> (shape.strokeWidth / 2).let { listOf(shape.cx - shape.rx - it, shape.cy - shape.ry - it, shape.cx + shape.rx + it, shape.cy + shape.ry + it) }
        is IconShape.Rect -> (shape.strokeWidth / 2).let { listOf(shape.x - it, shape.y - it, shape.x + shape.w + it, shape.y + shape.h + it) }
        // Round caps reach half the width past each end.
        is IconShape.Line -> (shape.width / 2).let { listOf(min(shape.x1, shape.x2) - it, min(shape.y1, shape.y2) - it, max(shape.x1, shape.x2) + it, max(shape.y1, shape.y2) + it) }
        is IconShape.Path -> {
            val points = shape.commands.flatMap {
                when (it) {
                    is PathCommand.Move -> listOf(it.x to it.y)
                    is PathCommand.Line -> listOf(it.x to it.y)
                    is PathCommand.Cubic -> listOf(it.x1 to it.y1, it.x2 to it.y2, it.x to it.y)
                    PathCommand.Close -> emptyList()
                }
            }
            val half = shape.strokeWidth / 2
            listOf(points.minOf { it.first } - half, points.minOf { it.second } - half, points.maxOf { it.first } + half, points.maxOf { it.second } + half)
        }
    }

    @Test
    fun `the UH-60 is drawn from its SVG, framed by its viewBox, and every other airframe from its silhouette`() {
        assertEquals(AircraftPicture.Svg(AircraftIcons.UH60_ASSET, AircraftIcons.UH60_FRAME), AircraftIcons.picture("uh60"))
        assertEquals(AircraftIcons.UH60_FRAME, AircraftIcons.frame("uh60"))
        for (key in silhouetteKeys) {
            val picture = AircraftIcons.picture(key)
            assertTrue(key, picture is AircraftPicture.Silhouette && picture.shapes.isNotEmpty())
            assertEquals(key, AircraftIcons.frame(key), picture.frame)
        }
    }

    @Test
    fun `a name nobody knows is the generic silhouette`() {
        assertEquals(AircraftIcons.picture("generic"), AircraftIcons.picture("nope"))
        assertEquals(AircraftIcons.picture("generic"), AircraftIcons.picture(null))
        assertEquals("generic", AircraftIcons.keyFor("nope"))
        assertEquals("uh60", AircraftIcons.keyFor("uh60"))
        assertEquals("generic", AircraftIcons.keyFor(null))
        assertEquals(AircraftIcons.frame("generic"), AircraftIcons.frame("flying-saucer"))
        assertEquals(AircraftIcons.frame("generic"), AircraftIcons.frame(null))
    }

    @Test
    fun `every silhouette has room for everything drawn in it, so nothing is cut off`() {
        for (key in silhouetteKeys) {
            val frame = AircraftIcons.frame(key)
            val half = frame.side / 2
            for (shape in shapes(key)) {
                val (left, top, right, bottom) = bounds(shape)
                assertTrue("$key: $shape", left >= frame.cx - half && right <= frame.cx + half && top >= frame.cy - half && bottom <= frame.cy + half)
            }
        }
    }

    @Test
    fun `a single rotor's picture is centred on its mast, where the map puts the aircraft's point, and its rotor is the disc`() {
        for (key in silhouetteKeys - "ch47") {
            val frame = AircraftIcons.frame(key)
            val ring = rings(key).single()
            val hub = shapes(key).last() as IconShape.Circle                                      // the hub, last, over the blades
            assertEquals(key, frame.cx to frame.cy, ring.cx to ring.cy)
            assertEquals(key, frame.cx to frame.cy, hub.cx to hub.cy)
            assertEquals(key, 2 * ring.r, frame.rotorSpan, 0.0)
        }
    }

    @Test
    fun `a tandem's picture is centred between its masts, and its rotor is the span from the front disc's tip to the back one's, as its profile gives it`() {
        val frame = AircraftIcons.frame("ch47")
        val (fore, aft) = rings("ch47")
        assertEquals((fore.cy + aft.cy) / 2, frame.cy, 0.0)
        assertEquals(fore.cx, frame.cx, 0.0)
        assertEquals(aft.cy + aft.r - (fore.cy - fore.r), frame.rotorSpan, 0.0)
    }

    @Test
    fun `a picture is as many rotor diameters across as its frame is`() {
        assertEquals(236.6 / 163.6, AircraftIcons.frame("uh60").widthPerRotorDiameter, 1e-12)
        assertEquals(114.0 / 92.0, AircraftIcons.frame("ah64").widthPerRotorDiameter, 1e-12)
        assertEquals(104.0 / 98.0, AircraftIcons.frame("ch47").widthPerRotorDiameter, 1e-12)
    }

    @Test
    fun `a blade is drawn for each blade, not each opposed pair, each over a dark edge drawn first`() {
        assertEquals(4, lines("ah64", IconColor.ACCENT).size)
        assertEquals(4, lines("generic", IconColor.ACCENT).size)
        assertEquals(5, lines("mh6", IconColor.ACCENT).size)
        assertEquals(6, lines("ch47", IconColor.ACCENT).size)                               // three on each of two heads
        for (key in silhouetteKeys) {
            val all = shapes(key).filterIsInstance<IconShape.Line>()
            val edges = lines(key, IconColor.DARK)
            assertEquals(key, edges.map { listOf(it.x1, it.y1, it.x2, it.y2) }, lines(key, IconColor.ACCENT).map { listOf(it.x1, it.y1, it.x2, it.y2) })
            assertEquals(key, edges, all.take(edges.size))                                  // every edge under every line
        }
    }

    @Test
    fun `four blades are an X, as the original's, leaving the fuselage clear, and an odd count has one up the nose and none down the tail`() {
        val reach = (AircraftIcons.DISC - 2) / Math.sqrt(2.0)                                 // the disc's edge less two, at 45 degrees
        val tips = listOf(50.0 + reach to 42.0 - reach, 50.0 + reach to 42.0 + reach, 50.0 - reach to 42.0 + reach, 50.0 - reach to 42.0 - reach)
        assertEquals(tips.map { (x, y) -> round(x) to round(y) }, lines("generic", IconColor.ACCENT).map { round(it.x2) to round(it.y2) })   // clockwise from the nose's right
        for (key in listOf("ah64", "uh72", "generic")) assertTrue(key, lines(key, IconColor.ACCENT).none { abs(it.x2 - 50.0) < 1e-9 })      // none along the fuselage
        val five = lines("mh6", IconColor.ACCENT)
        assertEquals(50.0 to 42.0 - (AircraftIcons.DISC - 2), round(five[0].x2) to round(five[0].y2))       // straight up the nose...
        assertTrue(five.none { abs(it.x2 - 50.0) < 1e-9 && it.y2 > 42.0 })                   // ...and none down the tail
        assertTrue(five[1].x2 > 50.0)                                                         // the rest follow clockwise
        assertTrue(lines("ch47", IconColor.ACCENT).none { abs(it.x2 - 50.0) < 1e-9 && it.y2 > it.y1 })   // a tandem's three likewise
    }

    /** To a millionth, so two ways to the same point compare equal. */
    private fun round(v: Double) = Math.round(v * 1e6) / 1e6

    @Test
    fun `a single rotor's silhouette keeps the web's proportions`() {
        val shapes = shapes("generic")
        val ring = rings("generic").single()
        assertEquals(AircraftIcons.DISC, ring.r, 0.0)
        assertEquals(42.0, ring.cy, 0.0)                                                     // the mast is forward of the centre
        val boom = shapes.filterIsInstance<IconShape.Rect>().first()
        assertEquals(42.0 + 24.0 * 0.55, boom.y, 1e-9)                                       // the tail starts 55% of the way back from the mast
        assertEquals(94.0 - boom.y, boom.h, 1e-9)
        assertEquals(3.0, (shapes.last() as IconShape.Circle).r, 0.0)
    }

    @Test
    fun `the Chinook has two rotor discs and a fuselage between them`() {
        assertEquals(listOf(26.0, 74.0), rings("ch47").map { it.cy })
        assertEquals(listOf(25.0, 25.0), rings("ch47").map { it.r })
    }

    @Test
    fun `a silhouette's lines are as bold for its rotor as the UH-60's are for its own`() {
        // The UH-60's SVG: a rotor of radius 81.8, its ring 10 wide on an edge 14 wide, its blades 8 on 12, its outline 4.
        for (key in silhouetteKeys) for (ring in rings(key)) {
            val edge = shapes(key).filterIsInstance<IconShape.Circle>().single { it.stroke == IconColor.DARK && it.cy == ring.cy && it.r == ring.r }
            assertEquals(key, 10 / 81.8, ring.strokeWidth / ring.r, 1e-12)
            assertEquals(key, 14 / 81.8, edge.strokeWidth / ring.r, 1e-12)
            assertTrue(key, shapes(key).indexOf(edge) < shapes(key).indexOf(ring))           // the edge under the ring
            val r = ring.r
            assertTrue(key, lines(key, IconColor.ACCENT).all { abs(it.width / r - 8 / 81.8) < 1e-12 })
            assertTrue(key, lines(key, IconColor.DARK).all { abs(it.width / r - 12 / 81.8) < 1e-12 })
        }
        val body = shapes("ah64").filterIsInstance<IconShape.Path>().single()
        assertEquals(4 / 81.8, body.strokeWidth / AircraftIcons.DISC, 1e-12)
    }

    @Test
    fun `the colours are the original helicopter's, black with a green that turns red`() {
        assertEquals(0xFF000000.toInt(), IconState.NORMAL.colour(IconColor.DARK))
        assertEquals(0xFF000000.toInt(), IconState.VIOLATION.colour(IconColor.DARK))
        assertEquals(0xFF64F338.toInt(), IconState.NORMAL.colour(IconColor.ACCENT))
        assertEquals(0xFFFF3B30.toInt(), IconState.VIOLATION.colour(IconColor.ACCENT))
        assertEquals("#64F338", IconState.NORMAL.hex)                                        // the green the SVG is drawn in
        assertEquals("#FF3B30", IconState.VIOLATION.hex)
    }

    @Test
    fun `nothing is white, and every shape paints something`() {
        for (key in silhouetteKeys) for (shape in shapes(key)) {
            val used = when (shape) {
                is IconShape.Circle -> listOfNotNull(shape.fill, shape.stroke)
                is IconShape.Ellipse -> listOfNotNull(shape.fill, shape.stroke)
                is IconShape.Rect -> listOfNotNull(shape.fill, shape.stroke)
                is IconShape.Line -> listOf(shape.stroke)
                is IconShape.Path -> listOfNotNull(shape.fill, shape.stroke)
            }
            assertTrue("$key $shape paints something", used.isNotEmpty())
        }
        for (state in IconState.entries) for (role in IconColor.entries) {
            val c = state.colour(role)
            assertTrue("$state $role", (c shr 24 and 0xFF) == 0xFF && minOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) < 0x80)
        }
    }
}

class IconRasterTest {
    @Test
    fun `a picture is drawn with the pixels the screen has for its points, and its density says exactly that many points`() {
        assertEquals(IconRaster(672, 560), IconRaster.of(192, 3.5, 1024))
        assertEquals(IconRaster(384, 320), IconRaster.of(192, 2.0, 1024))
        assertEquals(IconRaster(504, 420), IconRaster.of(192, 2.625, 1024))
        assertEquals(IconRaster(192, 160), IconRaster.of(192, 1.0, 1024))
    }

    @Test
    fun `on any screen the size is exact, within the cap, and never fewer pixels than points`() {
        for (i in 40..640) {
            val screen = i / 80.0                                                                // 0.5 to 8 pixels to the point
            val r = IconRaster.of(192, screen, 1024)
            assertEquals("screen $screen", r.px * 160, r.densityDpi * 192)
            assertTrue("screen $screen", r.px in 192..1024)
            if (screen * 160 in 160.0..850.0) assertTrue("screen $screen: ${r.densityDpi}", abs(r.densityDpi - screen * 160) <= 2.5)   // the nearest multiple of 5
        }
    }

    @Test
    fun `a screen denser than the cap is drawn at the cap, still exactly the size`() {
        assertEquals(IconRaster(1020, 850), IconRaster.of(192, 6.0, 1024))
        assertEquals(IconRaster(1020, 850), IconRaster.of(192, 40.0, 1024))
    }

    @Test
    fun `a picture that would need more pixels than it may have is refused`() {
        assertThrows(IllegalArgumentException::class.java) { IconRaster.of(192, 2.0, 100) }
        assertThrows(IllegalArgumentException::class.java) { IconRaster.of(0, 2.0, 1024) }
    }
}
