package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * The UH-60's picture (`assets/aircraft/uh60.svg`) held to the aircraft's published dimensions (UH-60L/M), as the file is: one unit is a decimetre and
 * the main rotor's mast is at 0, 0. Each is compared to the published figure in feet and inches within half a centimetre, the file being drawn to the
 * millimetre. What the map draws is held to the same file: the frame the size maths uses is its viewBox and its rotor.
 */
class Uh60SvgTest {
    private val svg: Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/assets/${AircraftIcons.UH60_ASSET}"))
        .documentElement

    private fun elements(tag: String): List<Element> = svg.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Element.number(name: String) = getAttribute(name).toDouble()

    /** Every point a path's data names (it uses only absolute M, L, C and Z, each taking pairs), as decimetres. */
    private fun points(path: Element): List<Pair<Double, Double>> =
        Regex("-?\\d+(\\.\\d+)?").findAll(path.getAttribute("d")).map { it.value.toDouble() }.toList().chunked(2) { (x, y) -> x to y }

    private fun feet(feet: Int, inches: Double = 0.0) = (feet + inches / 12) * 0.3048

    private val viewBox = svg.getAttribute("viewBox").trim().split(Regex("\\s+")).map { it.toDouble() }

    /** The main rotor: the two circles drawn on its outline (the green ring on its black edge). */
    private val rotor = elements("circle").filter { it.number("r") > 50 }

    /** The fuselage: the one shape filled black. */
    private val fuselage = elements("path").single { it.getAttribute("fill") == "#000000" }

    /** The loop at the tail, drawn twice (green on black), as the original drew the tail rotor: the paths left open that are not the blades. */
    private val loop = elements("path").filter { it.getAttribute("fill") == "none" && !it.hasAttribute("stroke-linecap") }

    @Test
    fun `the picture is square and centred on the mast, so the map turns it about the mast`() {
        val (minX, minY, width, height) = viewBox
        assertEquals(width, height, 0.0)
        assertEquals(-width / 2, minX, 0.0)
        assertEquals(-height / 2, minY, 0.0)
        assertEquals(AircraftIcons.UH60_FRAME.side, width, 0.0)                         // the frame the map's size maths uses
        assertEquals(0.0 to 0.0, AircraftIcons.UH60_FRAME.cx to AircraftIcons.UH60_FRAME.cy)
    }

    @Test
    fun `the main rotor is 53 ft 8 in across, about the mast`() {
        assertEquals(2, rotor.size)
        for (circle in rotor) {
            assertEquals(0.0, circle.number("cx"), 0.0)
            assertEquals(0.0, circle.number("cy"), 0.0)
            assertEquals(feet(53, 8.0), 2 * circle.number("r") / 10, 0.005)
            assertEquals(AircraftIcons.UH60_FRAME.rotorSpan, 2 * circle.number("r"), 0.0)   // what the frame scales to the profile's rotor diameter
        }
    }

    @Test
    fun `the fuselage is 50 ft 0 3-4 in long and 7 ft 9 in wide`() {
        val points = points(fuselage)
        assertEquals(feet(50, 0.75), (points.maxOf { it.second } - points.minOf { it.second }) / 10, 0.005)
        assertEquals(feet(7, 9.0), (points.maxOf { it.first } - points.minOf { it.first }) / 10, 0.005)
        assertEquals(0.0, points.maxOf { it.first } + points.minOf { it.first }, 1e-9)    // on the centre line
    }

    @Test
    fun `the stabilator is 14 ft 4 1-2 in across`() {
        val stabilator = elements("rect").single()
        assertEquals(feet(14, 4.5), stabilator.number("width") / 10, 0.005)
        assertEquals(0.0, stabilator.number("x") + stabilator.number("width") / 2, 1e-9)  // on the centre line
    }

    @Test
    fun `the tail loop ends where the turning tail rotor does, 64 ft 10 in from the main rotor's tip, and its outline is inside the picture`() {
        assertEquals(2, loop.size)
        for (path in loop) {
            val aft = points(path).maxOf { it.second }
            val ahead = rotor.first().number("r")                                          // the main rotor's tip, ahead of the mast
            assertEquals(feet(64, 10.0), (aft + ahead) / 10, 0.005)
            // The picture ends half the widest outline beyond the loop's end, the black one under the green, so that outline is drawn whole.
            assertTrue(aft + path.number("stroke-width") / 2 <= viewBox[1] + viewBox[3] + 1e-9)
        }
        assertEquals(viewBox[1] + viewBox[3], loop.maxOf { points(it).maxOf { p -> p.second } + it.number("stroke-width") / 2 }, 1e-9)
    }

    /** What [shape] covers, half its stroke included, as left, top, right, bottom. A curve stays inside its control points, so a path's points bound it. */
    private fun bounds(shape: Element): List<Double> {
        val half = shape.getAttribute("stroke-width").takeIf { it.isNotEmpty() }?.toDouble()?.div(2) ?: 0.0
        return when (shape.tagName) {
            "circle" -> (shape.number("r") + half).let { listOf(shape.number("cx") - it, shape.number("cy") - it, shape.number("cx") + it, shape.number("cy") + it) }
            "rect" -> listOf(shape.number("x") - half, shape.number("y") - half, shape.number("x") + shape.number("width") + half, shape.number("y") + shape.number("height") + half)
            // Round caps reach half the width past each end, and round joins no further; the loop's joins meet smoothly, so they have no point.
            else -> points(shape).let { p -> listOf(p.minOf { it.first } - half, p.minOf { it.second } - half, p.maxOf { it.first } + half, p.maxOf { it.second } + half) }
        }
    }

    @Test
    fun `every stroke is drawn whole, and the picture is no wider than that needs`() {
        val shapes = listOf("circle", "path", "rect").flatMap(::elements)
        val (minX, minY, width, height) = viewBox
        for (shape in shapes) {
            val (left, top, right, bottom) = bounds(shape)
            assertTrue("${shape.tagName} ${shape.getAttribute("d")}", left >= minX - 1e-9 && top >= minY - 1e-9 && right <= minX + width + 1e-9 && bottom <= minY + height + 1e-9)
        }
        // The least margin that keeps every stroke whole: what reaches farthest from the mast reaches the edge, so the rotor is as large as it can be drawn.
        assertEquals(width / 2, shapes.maxOf { shape -> bounds(shape).maxOf { abs(it) } }, 1e-9)
    }

    @Test
    fun `it is drawn in black and the one green, so a violation can turn every green part red`() {
        val colours = listOf("circle", "path", "rect").flatMap(::elements).flatMap { listOf(it.getAttribute("fill"), it.getAttribute("stroke")) }
            .filter { it.isNotEmpty() && it != "none" }.map { it.uppercase() }.toSet()
        assertEquals(setOf("#000000", IconState.NORMAL.hex), colours)
        assertTrue(listOf("circle", "path", "rect").flatMap(::elements).none { it.hasAttribute("style") || it.hasAttribute("color") })
    }
}
