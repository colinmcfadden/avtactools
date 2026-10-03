package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftIconsTest {
    private fun lines(key: String) = AircraftIcons.shapes(key).filterIsInstance<IconShape.Line>()

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
