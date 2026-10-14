package app.ezpztac.map

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The two colours an aircraft is drawn in, by role, so a state can turn an aircraft red without redrawing it. */
enum class IconColor {
    /** The airframe, and the edge under each line in [ACCENT] that keeps it clear of whatever the map shows under it. Black in every state. */
    DARK,

    /** The outlines, the rotor's ring and blades, the hub: the original helicopter's green, red when the aircraft is too close to another. */
    ACCENT,
}

/**
 * The original EZ/PZ helicopter's colours (`frontend/public/icons/helicopter.png`, redrawn to scale as [AircraftIcons.UH60_ASSET]): black, with a bright
 * green that reads over imagery, the charts and the topographic map alike, and red in its place when the aircraft is too close to another. [hex] is
 * the accent as an SVG writes it.
 */
enum class IconState(val hex: String) {
    NORMAL("#64F338"),
    VIOLATION("#FF3B30"),
    ;

    /** [role]'s colour, as ARGB. */
    fun colour(role: IconColor): Int = when (role) {
        IconColor.DARK -> OPAQUE
        IconColor.ACCENT -> OPAQUE or hex.substring(1).toInt(16)
    }
}

/** Opaque black, as ARGB. */
private val OPAQUE = 0xFF000000.toInt()

/** A shape in the units its silhouette is drawn in, nose up. */
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
 * How an aircraft's picture is cut from the units its shapes are drawn in, so that it is drawn to scale: a square [side] units across, centred on the
 * middle of its rotors ([cx], [cy]: the mast, or midway between a tandem's two), because the map puts the middle of a picture on the aircraft's point
 * and turns it about that point, which is where the separation maths measures from and the keep-out ring is drawn round; and [rotorSpan], the units
 * that the profile's `rotor_diameter_m` is across (the disc's diameter; for tandem rotors the overall fore-to-aft span, which is what the profile gives).
 */
data class IconFrame(val cx: Double, val cy: Double, val side: Double, val rotorSpan: Double) {
    /** The picture's width on the ground, in the airframe's rotor diameters. */
    val widthPerRotorDiameter: Double get() = side / rotorSpan
}

/**
 * How a picture [points] across is drawn for the map: with [px] pixels, and the bitmap density [densityDpi] that makes MapLibre count them as exactly
 * [points]. MapLibre takes an image's pixel ratio from its bitmap's density (`densityDpi / 160`), so `px * 160 == densityDpi * points`.
 */
data class IconRaster(val px: Int, val densityDpi: Int) {
    companion object {
        /**
         * As many pixels as a screen of [screenDensity] pixels to the point has for [points], so the picture is as sharp as the screen, though never
         * fewer than [points] nor more than [maxPx]. The density is the whole number nearest the screen's that makes the pixels whole too, so the size
         * is exact, not nearly so.
         */
        fun of(points: Int, screenDensity: Double, maxPx: Int): IconRaster {
            require(points > 0 && maxPx >= points) { "$points points cannot be drawn in $maxPx pixels" }
            val step = DEFAULT_DPI / gcd(points, DEFAULT_DPI)                    // a density that is a multiple of this makes points * density / 160 whole
            val most = maxPx * DEFAULT_DPI / points / step * step
            val density = ((screenDensity * DEFAULT_DPI / step).roundToInt() * step).coerceIn(DEFAULT_DPI, most)
            return IconRaster(points * density / DEFAULT_DPI, density)
        }

        /** Android's baseline density, one pixel to the point (`DisplayMetrics.DENSITY_DEFAULT`). */
        private const val DEFAULT_DPI = 160

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/** What an airframe's picture is drawn from, and how it is cut out ([frame]). */
sealed interface AircraftPicture {
    val frame: IconFrame

    /** An SVG among the app's assets, in [IconState.NORMAL]'s colours: another state's picture is the same with its accent in their place. */
    data class Svg(val asset: String, override val frame: IconFrame) : AircraftPicture

    /** A silhouette ported from the web's `aircraftIcons.js`, as shapes in its 100-unit box, so what it is made of is tested and the platform only draws it. */
    data class Silhouette(val shapes: List<IconShape>, override val frame: IconFrame) : AircraftPicture
}

/**
 * The aircraft's top views, nose up. The UH-60 is the original EZ/PZ helicopter, redrawn to the aircraft's published dimensions ([UH60_ASSET]). Every
 * other airframe is its silhouette from the web's `aircraftIcons.js`, in the UH-60's colours and line weights: each is drawn in a 100 by 100 box with
 * its rotor in the upper middle (the mast at 50, 42; a tandem's two at 50, 26 and 50, 74), and its frame cuts the picture from that box with the rotor
 * in the middle, so it turns about the mast and is drawn to scale. The web centres the box itself and makes it the rotor's diameter across, so its
 * rotor is drawn 92% of its size and a little ahead of the aircraft's point.
 */
object AircraftIcons {
    const val DISC = 46.0

    /** The icon keys a profile can name; anything else is drawn as [GENERIC]. */
    val KEYS: List<String> = listOf("uh60", "ah64", "ch47", "uh72", "mh6", "generic")
    const val GENERIC = "generic"
    const val UH60 = "uh60"

    /** The UH-60's picture, among the app's assets: its header says what it is drawn to. */
    const val UH60_ASSET = "aircraft/uh60.svg"

    /**
     * The UH-60's picture is its SVG's viewBox, a square 236.6 decimetres across centred on the mast (the tail rotor's tip is 115.8 behind it, and the
     * outer half of the loop's outline reaches 118.3, the edge, so no stroke is cut), and its rotor 163.6 across: the profile's rotor diameter scales the
     * whole picture, so the published 16.36 m draws it at its published size.
     */
    val UH60_FRAME = IconFrame(0.0, 0.0, 236.6, 163.6)

    /** Where a single rotor's mast is in the box. */
    private const val MAST_X = 50.0
    private const val MAST_Y = 42.0

    /** A tandem's two masts, fore and aft, and their discs' radius. */
    private const val FORE_Y = 26.0
    private const val AFT_Y = 74.0
    private const val TANDEM_DISC = 25.0

    /** A single rotor's picture: 57 units each way from the mast, for the tail rotor, the farthest thing drawn, reaches 56.6 behind it. */
    private val CONVENTIONAL = IconFrame(MAST_X, MAST_Y, 114.0, 2 * DISC)

    /** A tandem's picture is centred midway between its masts, 52 units each way: the rings' edges reach 51.1 from there, fore and aft. */
    private val TANDEM = IconFrame(MAST_X, (FORE_Y + AFT_Y) / 2, 104.0, AFT_Y - FORE_Y + 2 * TANDEM_DISC)

    /**
     * The UH-60's line weights, as parts of its rotor's radius (81.8 in its SVG), so a silhouette's lines are as bold for the size of its rotor:
     * the ring 10 on a dark edge 14 wide, the blades 8 on 12, the airframe's outline 4, and 2 for the dark edge of the hub and the stabilator.
     */
    private object Weight {
        const val RING = 10 / 81.8
        const val RING_EDGE = 14 / 81.8
        const val BLADE = 8 / 81.8
        const val BLADE_EDGE = 12 / 81.8
        const val OUTLINE = 4 / 81.8
        const val FINE_EDGE = 2 / 81.8
    }

    /** What [iconKey]'s picture is drawn from, and how it is cut out. */
    fun picture(iconKey: String?): AircraftPicture = when (val key = keyFor(iconKey)) {
        UH60 -> AircraftPicture.Svg(UH60_ASSET, UH60_FRAME)
        else -> AircraftPicture.Silhouette(silhouette(key), frame(key))
    }

    /** The key an icon is stored under: a name this version does not know is the generic one. */
    fun keyFor(iconKey: String?): String = if (iconKey in KEYS) iconKey!! else GENERIC

    /** How [iconKey]'s picture is cut out, and how big its rotor is in it. */
    fun frame(iconKey: String?): IconFrame = when (keyFor(iconKey)) {
        UH60 -> UH60_FRAME
        "ch47" -> TANDEM
        else -> CONVENTIONAL
    }

    private fun silhouette(key: String): List<IconShape> = when (key) {
        "ah64" -> conventional(bladeCount = 4, bodyWidth = 13.0, bodyLength = 58.0, tailWidth = 6.0, stabWidth = 24.0)
        "ch47" -> tandem()
        "uh72" -> conventional(bladeCount = 4, bodyWidth = 20.0, bodyLength = 40.0, tailWidth = 6.0, stabWidth = 18.0)
        "mh6" -> conventional(bladeCount = 5, bodyWidth = 21.0, bodyLength = 30.0, tailWidth = 4.0, stabWidth = 16.0)
        else -> conventional(bladeCount = 4, bodyWidth = 20.0, bodyLength = 48.0, tailWidth = 7.0, stabWidth = 20.0)
    }

    /** A rotor's ring of radius [r], in the accent on a dark edge, both on the rotor's outline, as the UH-60's is drawn. */
    private fun ring(cx: Double, cy: Double, r: Double): List<IconShape> = listOf(
        IconShape.Circle(cx, cy, r, stroke = IconColor.DARK, strokeWidth = Weight.RING_EDGE * r),
        IconShape.Circle(cx, cy, r, stroke = IconColor.ACCENT, strokeWidth = Weight.RING * r),
    )

    /**
     * One line per blade, not per opposed pair, so a four-blade head reads as four blades, on each of [masts] (a disc of radius [r] each): the dark edges
     * of them all first, then the accent over them, so no blade's edge crosses another's line. Lines this bold would hide the fuselage under a blade up
     * the nose and its twin down the tail, so an even count is turned half a gap off the nose, an X like the original helicopter's; an odd count, one
     * up the nose as on the web, leaves the tail clear.
     */
    private fun blades(count: Int, masts: List<Pair<Double, Double>>, length: Double, r: Double): List<IconShape> {
        val turn = if (count % 2 == 0) Math.PI / count else 0.0
        val lines = masts.flatMap { (cx, cy) ->
            (0 until count).map { i ->
                val angle = turn + 2 * Math.PI * i / count
                listOf(cx, cy, cx + sin(angle) * length, cy - cos(angle) * length)
            }
        }
        return lines.map { (x1, y1, x2, y2) -> IconShape.Line(x1, y1, x2, y2, IconColor.DARK, Weight.BLADE_EDGE * r) } +
            lines.map { (x1, y1, x2, y2) -> IconShape.Line(x1, y1, x2, y2, IconColor.ACCENT, Weight.BLADE * r) }
    }

    private fun hub(cx: Double, cy: Double, radius: Double, r: Double) = IconShape.Circle(cx, cy, radius, IconColor.ACCENT, IconColor.DARK, Weight.FINE_EDGE * r)

    /** Single main rotor with a tail boom, drawn back to front: the ring, the airframe, the blades over everything, then the hub. */
    private fun conventional(bladeCount: Int, bodyWidth: Double, bodyLength: Double, tailWidth: Double, stabWidth: Double): List<IconShape> {
        val cx = MAST_X
        val cy = MAST_Y                                             // the mast sits forward of centre, leaving room for the boom
        val r = DISC
        val halfBody = bodyLength / 2
        val noseY = cy - halfBody
        val tailY = cy + halfBody * 0.55
        val halfW = bodyWidth / 2
        val outline = Weight.OUTLINE * r
        return buildList {
            addAll(ring(cx, cy, r))
            add(IconShape.Rect(cx - tailWidth / 2, tailY, tailWidth, 94 - tailY, tailWidth / 2, IconColor.DARK, IconColor.ACCENT, outline))
            // The stabilator and the tail rotor in the accent on a dark edge, as the UH-60's stabilator is.
            add(IconShape.Rect(cx - stabWidth / 2, 86.0, stabWidth, 4.0, 2.0, IconColor.ACCENT, IconColor.DARK, Weight.FINE_EDGE * r))
            add(IconShape.Ellipse(cx + tailWidth, 93.0, 2.0, 5.0, IconColor.ACCENT, IconColor.DARK, Weight.FINE_EDGE * r))
            add(
                IconShape.Path(
                    listOf(
                        PathCommand.Move(cx, noseY),
                        PathCommand.Cubic(cx + halfW, noseY + halfBody * 0.35, cx + halfW, cy + halfBody * 0.25, cx + halfW * 0.62, tailY + 2),
                        PathCommand.Line(cx - halfW * 0.62, tailY + 2),
                        PathCommand.Cubic(cx - halfW, cy + halfBody * 0.25, cx - halfW, noseY + halfBody * 0.35, cx, noseY),
                        PathCommand.Close,
                    ),
                    IconColor.DARK, IconColor.ACCENT, outline,
                ),
            )
            addAll(blades(bladeCount, listOf(cx to cy), DISC - 2, r))
            add(hub(cx, cy, 3.0, r))
        }
    }

    /** Tandem rotors: two discs fore and aft over one long fuselage. */
    private fun tandem(): List<IconShape> {
        val cx = MAST_X
        val fore = FORE_Y
        val aft = AFT_Y
        val r = TANDEM_DISC
        val outline = Weight.OUTLINE * r
        return buildList {
            addAll(ring(cx, fore, r))
            addAll(ring(cx, aft, r))
            add(IconShape.Rect(cx - 12, 14.0, 24.0, 74.0, 9.0, IconColor.DARK, IconColor.ACCENT, outline))
            add(IconShape.Rect(cx - 6, 8.0, 12.0, 9.0, 3.0, IconColor.DARK, IconColor.ACCENT, outline))
            add(IconShape.Rect(cx - 7, 84.0, 14.0, 10.0, 3.0, IconColor.DARK, IconColor.ACCENT, outline))
            addAll(blades(3, listOf(cx to fore, cx to aft), r - 1.5, r))
            add(hub(cx, fore, 2.6, r))
            add(hub(cx, aft, 2.6, r))
        }
    }
}
