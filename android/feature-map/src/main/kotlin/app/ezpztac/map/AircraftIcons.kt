package app.ezpztac.map

import kotlin.math.cos
import kotlin.math.sin

/** The colours an aircraft is drawn in; each shape names one by its role, so a palette can turn an aircraft red without redrawing it. */
enum class IconColor { BODY, EDGE, GLASS, DISC, DISC_EDGE, BLADE }

/** One of the web's three palettes (`PALETTES` in `aircraftIcons.js`), as ARGB. A light airframe with a dark outline stays legible over satellite imagery and the dark themes. */
enum class IconState(val palette: Map<IconColor, Int>) {
    NORMAL(
        mapOf(
            IconColor.BODY to 0xFFDBE2EA.toInt(), IconColor.EDGE to 0xFF11161C.toInt(), IconColor.GLASS to argb(0.55, 90, 167, 212),
            IconColor.DISC to argb(0.13, 214, 224, 236), IconColor.DISC_EDGE to argb(0.34, 214, 224, 236), IconColor.BLADE to argb(0.7, 226, 233, 241),
        ),
    ),
    VIOLATION(
        mapOf(
            IconColor.BODY to 0xFFF0C3BF.toInt(), IconColor.EDGE to 0xFF3A1210.toInt(), IconColor.GLASS to argb(0.5, 214, 107, 100),
            IconColor.DISC to argb(0.20, 220, 38, 38), IconColor.DISC_EDGE to argb(0.55, 224, 115, 108), IconColor.BLADE to argb(0.8, 240, 180, 175),
        ),
    ),
    GHOST(
        mapOf(
            IconColor.BODY to 0xFF8D97A3.toInt(), IconColor.EDGE to 0xFF1B2027.toInt(), IconColor.GLASS to argb(0.35, 120, 140, 160),
            IconColor.DISC to argb(0.08, 148, 163, 178), IconColor.DISC_EDGE to argb(0.22, 148, 163, 178), IconColor.BLADE to argb(0.45, 160, 172, 186),
        ),
    ),
}

/** `rgba(r,g,b,a)` as the platform's ARGB int (alpha rounded to a byte). */
private fun argb(alpha: Double, r: Int, g: Int, b: Int): Int = (Math.round(alpha * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

/** A shape in the 100 by 100 box every silhouette is drawn in, nose up, centred on the mast. */
sealed interface IconShape {
    data class Circle(val cx: Double, val cy: Double, val r: Double, val fill: IconColor? = null, val stroke: IconColor? = null, val strokeWidth: Double = 0.0) : IconShape
    data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double, val fill: IconColor? = null, val stroke: IconColor? = null, val strokeWidth: Double = 0.0) : IconShape
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val rx: Double, val fill: IconColor? = null, val stroke: IconColor? = null, val strokeWidth: Double = 0.0) : IconShape
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val stroke: IconColor, val width: Double) : IconShape
    data class Path(val commands: List<PathCommand>, val fill: IconColor? = null, val stroke: IconColor? = null, val strokeWidth: Double = 0.0) : IconShape
}

sealed interface PathCommand {
    data class Move(val x: Double, val y: Double) : PathCommand
    data class Line(val x: Double, val y: Double) : PathCommand
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : PathCommand
    data object Close : PathCommand
}

/**
 * Top-down aircraft silhouettes (`aircraftIcons.js`), as shapes, so what an icon is made of is tested and the platform only has to draw it.
 * Each is drawn in a 100 by 100 box, nose up, centred on the mast, so turning it by the aircraft's heading just works and the rotor disc
 * lines up with the footprint the separation maths uses (the disc fills 92% of the box, as on the web).
 */
object AircraftIcons {
    const val DISC = 46.0

    /** The icon keys a profile can name; anything else is drawn as [GENERIC]. */
    val KEYS: List<String> = listOf("uh60", "ah64", "ch47", "uh72", "mh6", "generic")
    const val GENERIC = "generic"

    fun shapes(iconKey: String?): List<IconShape> = when (iconKey) {
        "uh60" -> conventional(bladeCount = 4, bodyWidth = 22.0, bodyLength = 52.0, tailWidth = 7.0, stabWidth = 22.0)
        "ah64" -> conventional(bladeCount = 4, bodyWidth = 13.0, bodyLength = 58.0, tailWidth = 6.0, stabWidth = 24.0)
        "ch47" -> tandem()
        "uh72" -> conventional(bladeCount = 4, bodyWidth = 20.0, bodyLength = 40.0, tailWidth = 6.0, stabWidth = 18.0)
        "mh6" -> conventional(bladeCount = 5, bodyWidth = 21.0, bodyLength = 30.0, tailWidth = 4.0, stabWidth = 16.0)
        else -> conventional(bladeCount = 4, bodyWidth = 20.0, bodyLength = 48.0, tailWidth = 7.0, stabWidth = 20.0)
    }

    /** The key an icon is stored under: a name this version does not know is the generic one. */
    fun keyFor(iconKey: String?): String = if (iconKey in KEYS) iconKey!! else GENERIC

    private fun disc(cx: Double, cy: Double, r: Double) = IconShape.Circle(cx, cy, r, IconColor.DISC, IconColor.DISC_EDGE, 1.2)

    /** One line per blade, not per opposed pair, so a four-blade head reads as four blades. */
    private fun blades(count: Int, cx: Double, cy: Double, radius: Double, width: Double = 2.4): List<IconShape> = (0 until count).map { i ->
        val angle = 2 * Math.PI * i / count
        IconShape.Line(cx, cy, cx + sin(angle) * radius, cy - cos(angle) * radius, IconColor.BLADE, width)
    }

    /** Single main rotor with a tail boom, drawn back to front: disc, airframe, blades over everything, then the hub. */
    private fun conventional(bladeCount: Int, bodyWidth: Double, bodyLength: Double, tailWidth: Double, stabWidth: Double): List<IconShape> {
        val cx = 50.0
        val cy = 42.0                                               // the mast sits forward of centre, leaving room for the boom
        val halfBody = bodyLength / 2
        val noseY = cy - halfBody
        val tailY = cy + halfBody * 0.55
        val halfW = bodyWidth / 2
        return buildList {
            add(disc(cx, cy, DISC))
            add(IconShape.Rect(cx - tailWidth / 2, tailY, tailWidth, 94 - tailY, tailWidth / 2, IconColor.BODY, IconColor.EDGE, 1.0))
            add(IconShape.Rect(cx - stabWidth / 2, 86.0, stabWidth, 4.0, 2.0, IconColor.BODY, IconColor.EDGE, 0.8))
            add(IconShape.Ellipse(cx + tailWidth, 93.0, 2.0, 5.0, IconColor.BLADE, IconColor.EDGE, 0.8))
            add(
                IconShape.Path(
                    listOf(
                        PathCommand.Move(cx, noseY),
                        PathCommand.Cubic(cx + halfW, noseY + halfBody * 0.35, cx + halfW, cy + halfBody * 0.25, cx + halfW * 0.62, tailY + 2),
                        PathCommand.Line(cx - halfW * 0.62, tailY + 2),
                        PathCommand.Cubic(cx - halfW, cy + halfBody * 0.25, cx - halfW, noseY + halfBody * 0.35, cx, noseY),
                        PathCommand.Close,
                    ),
                    IconColor.BODY, IconColor.EDGE, 1.2,
                ),
            )
            add(
                IconShape.Path(
                    listOf(
                        PathCommand.Move(cx, noseY + 2),
                        PathCommand.Cubic(cx + halfW * 0.7, noseY + halfBody * 0.4, cx + halfW * 0.7, noseY + halfBody * 0.6, cx, noseY + halfBody * 0.62),
                        PathCommand.Cubic(cx - halfW * 0.7, noseY + halfBody * 0.6, cx - halfW * 0.7, noseY + halfBody * 0.4, cx, noseY + 2),
                        PathCommand.Close,
                    ),
                    IconColor.GLASS,
                ),
            )
            addAll(blades(bladeCount, cx, cy, DISC - 2))
            add(IconShape.Circle(cx, cy, 3.0, IconColor.EDGE))
        }
    }

    /** Tandem rotors: two discs fore and aft over one long fuselage. */
    private fun tandem(): List<IconShape> {
        val cx = 50.0
        val fore = 26.0
        val aft = 74.0
        val r = 25.0
        return buildList {
            add(disc(cx, fore, r))
            add(disc(cx, aft, r))
            add(IconShape.Rect(cx - 12, 14.0, 24.0, 74.0, 9.0, IconColor.BODY, IconColor.EDGE, 1.2))
            add(IconShape.Path(listOf(PathCommand.Move(cx - 12, 26.0), PathCommand.Cubic(cx - 12, 16.0, cx + 12, 16.0, cx + 12, 26.0), PathCommand.Close), IconColor.GLASS))
            add(IconShape.Rect(cx - 6, 8.0, 12.0, 9.0, 3.0, IconColor.BODY, IconColor.EDGE, 1.0))
            add(IconShape.Rect(cx - 7, 84.0, 14.0, 10.0, 3.0, IconColor.BODY, IconColor.EDGE, 1.0))
            addAll(blades(3, cx, fore, r - 1.5))
            addAll(blades(3, cx, aft, r - 1.5))
            add(IconShape.Circle(cx, fore, 2.6, IconColor.EDGE))
            add(IconShape.Circle(cx, aft, 2.6, IconColor.EDGE))
        }
    }
}
