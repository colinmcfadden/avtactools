package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AircraftIconsTest {
    private fun lines(key: String) = AircraftIcons.shapes(key).filterIsInstance<IconShape.Line>()

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
    fun `every picture has room for everything drawn in it, so nothing is cut off`() {
        for (key in AircraftIcons.KEYS) {
            val frame = AircraftIcons.frame(key)
            val half = frame.side / 2
            for (shape in AircraftIcons.shapes(key)) {
                val (left, top, right, bottom) = bounds(shape)
                assertTrue("$key: $shape", left >= frame.cx - half && right <= frame.cx + half && top >= frame.cy - half && bottom <= frame.cy + half)
            }
        }
    }

    @Test
    fun `a single rotor's picture is centred on its mast, where the map puts the aircraft's point, and its rotor is the disc`() {
        for (key in AircraftIcons.KEYS - "ch47") {
            val frame = AircraftIcons.frame(key)
            val disc = AircraftIcons.shapes(key).first() as IconShape.Circle                       // drawn first, under everything
            val hub = AircraftIcons.shapes(key).last() as IconShape.Circle                         // and the hub last, over the blades
            assertEquals(key, frame.cx to frame.cy, disc.cx to disc.cy)
            assertEquals(key, frame.cx to frame.cy, hub.cx to hub.cy)
            assertEquals(key, 2 * disc.r, frame.rotorSpan, 0.0)
        }
    }

    @Test
    fun `a tandem's picture is centred between its masts, and its rotor is the span from the front disc's tip to the back one's, as its profile gives it`() {
        val frame = AircraftIcons.frame("ch47")
        val discs = AircraftIcons.shapes("ch47").filterIsInstance<IconShape.Circle>().filter { it.fill == IconColor.DISC }
        assertEquals((discs[0].cy + discs[1].cy) / 2, frame.cy, 0.0)
        assertEquals(discs[0].cx, frame.cx, 0.0)
        assertEquals(discs[1].cy + discs[1].r - (discs[0].cy - discs[0].r), frame.rotorSpan, 0.0)
    }

    @Test
    fun `a picture is as many rotor diameters across as its frame is, and a name nobody knows is framed as the generic one`() {
        assertEquals(114.0 / 92.0, AircraftIcons.frame("ah64").widthPerRotorDiameter, 1e-12)
        assertEquals(100.0 / 98.0, AircraftIcons.frame("ch47").widthPerRotorDiameter, 1e-12)
        assertEquals(AircraftIcons.frame("generic"), AircraftIcons.frame("flying-saucer"))
        assertEquals(AircraftIcons.frame("generic"), AircraftIcons.frame(null))
    }

    @Test
    fun `every profile icon key has a silhouette, and a name nobody knows is the generic one`() {
        for (key in AircraftIcons.KEYS) assertTrue(key, AircraftIcons.shapes(key).isNotEmpty())
        assertEquals(AircraftIcons.shapes("generic"), AircraftIcons.shapes("nope"))
        assertEquals(AircraftIcons.shapes("generic"), AircraftIcons.shapes(null))
        assertEquals("generic", AircraftIcons.keyFor("nope"))
        assertEquals("uh60", AircraftIcons.keyFor("uh60"))
        assertEquals("generic", AircraftIcons.keyFor(null))
    }

    @Test
    fun `a blade is drawn for each blade, not each opposed pair`() {
        assertEquals(4, lines("uh60").size)
        assertEquals(4, lines("ah64").size)
        assertEquals(5, lines("mh6").size)
        assertEquals(6, lines("ch47").size)                                                  // three on each of two heads
    }

    @Test
    fun `the first blade points up the nose and the rest follow clockwise`() {
        val blades = lines("uh60")
        assertEquals(50.0, blades[0].x2, 1e-9)                                                // straight up from the mast...
        assertEquals(42.0 - (AircraftIcons.DISC - 2), blades[0].y2, 1e-9)                     // ...to the disc's edge less two
        assertTrue(blades[1].x2 > 50.0)                                                       // the next is to the right
    }

    @Test
    fun `the Black Hawk's proportions are the web's`() {
        val shapes = AircraftIcons.shapes("uh60")
        val disc = shapes.first() as IconShape.Circle
        assertEquals(AircraftIcons.DISC, disc.r, 0.0)
        assertEquals(42.0, disc.cy, 0.0)                                                      // the mast is forward of the centre
        val boom = shapes[1] as IconShape.Rect
        assertEquals(42.0 + 26.0 * 0.55, boom.y, 1e-9)                                        // the tail starts 55% of the way back from the mast
        assertEquals(94.0 - boom.y, boom.h, 1e-9)
        val hub = shapes.last() as IconShape.Circle
        assertEquals(3.0, hub.r, 0.0)
    }

    @Test
    fun `the Chinook has two rotor discs and a fuselage between them`() {
        val discs = AircraftIcons.shapes("ch47").filterIsInstance<IconShape.Circle>().filter { it.fill == IconColor.DISC }
        assertEquals(listOf(26.0, 74.0), discs.map { it.cy })
        assertEquals(listOf(25.0, 25.0), discs.map { it.r })
    }

    @Test
    fun `the three palettes are the web's, and a violation reads red`() {
        assertEquals(0xFFDBE2EA.toInt(), IconState.NORMAL.palette.getValue(IconColor.BODY))
        assertEquals(0xFFF0C3BF.toInt(), IconState.VIOLATION.palette.getValue(IconColor.BODY))
        assertEquals(0xFF8D97A3.toInt(), IconState.GHOST.palette.getValue(IconColor.BODY))
        // rgba(90,167,212,0.55): alpha 0.55 of 255 is 140.25, a byte of 140.
        assertEquals(0x8C5AA7D4.toInt(), IconState.NORMAL.palette.getValue(IconColor.GLASS))
        for (state in IconState.entries) for (colour in IconColor.entries) assertTrue("$state $colour", state.palette.containsKey(colour))
        val red = IconState.VIOLATION.palette.getValue(IconColor.DISC)
        assertTrue((red shr 16 and 0xFF) > (red shr 8 and 0xFF))                              // more red than green
    }

    @Test
    fun `every shape names a colour that its palette has`() {
        for (key in AircraftIcons.KEYS) for (shape in AircraftIcons.shapes(key)) {
            val used = when (shape) {
                is IconShape.Circle -> listOfNotNull(shape.fill, shape.stroke)
                is IconShape.Ellipse -> listOfNotNull(shape.fill, shape.stroke)
                is IconShape.Rect -> listOfNotNull(shape.fill, shape.stroke)
                is IconShape.Line -> listOf(shape.stroke)
                is IconShape.Path -> listOfNotNull(shape.fill, shape.stroke)
            }
            assertTrue("$key $shape paints something", used.isNotEmpty())
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
