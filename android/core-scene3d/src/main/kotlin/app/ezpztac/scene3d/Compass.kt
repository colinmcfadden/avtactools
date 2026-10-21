package app.ezpztac.scene3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * How the 3D view's compass looks for the camera (the web's `compassPose`, held to `contracts/fixtures/viewer3d/compass.json`): a dial lying on the
 * ground, turned so its N points north on the screen ([rotateDeg]) and leaned back as far as the view is ([tiltDeg]: flat looking straight down,
 * never past [MAX_TILT_DEG], where it is too edge-on to read), with the heading under it in degrees true ([headingText], `330°T`).
 */
public data class CompassPose(val rotateDeg: Double, val tiltDeg: Double, val headingText: String) {
    /**
     * What a screen reader says of the compass button: "Facing 330 degrees true", the web's label less its "Face north", which the button's own
     * action says ("double-tap to face north"), where a web button has none.
     */
    public val spoken: String get() = "Facing ${headingText.removeSuffix("°T")} degrees true"

    /**
     * Where the dial's points land on the screen, as the web draws it: its SVG (a viewBox [DIAL_UNITS] across, centred on 0, y down) turned by
     * [rotateDeg] and then leaned back by [tiltDeg] (`rotateX(tilt) rotateZ(rotate)`) under a `perspective` of [DIAL_PERSPECTIVE] (the web's
     * 220 px over its 72 px dial), drawn [sizePx] across with its centre at ([cx], [cy]). A plane seen in perspective is a homography: the nine
     * values row by row, as `android.graphics.Matrix` takes them, mapping (x, y) to ((a x + b y + c) / w, (d x + e y + f) / w), w = g x + h y + i.
     */
    public fun dialMatrix(sizePx: Double, cx: Double, cy: Double): DoubleArray {
        val turn = rotateDeg * PI / 180
        val lean = tiltDeg * PI / 180
        val k = sizePx / DIAL_UNITS
        // Turned in the dial's plane, then leaned back about its middle: the top goes away from the eye, and the eye sees it smaller.
        val a = cos(turn)
        val b = -sin(turn)
        val d = cos(lean) * sin(turn)
        val e = cos(lean) * cos(turn)
        val g = -(sin(lean) / DIAL_PERSPECTIVE) * sin(turn)
        val h = -(sin(lean) / DIAL_PERSPECTIVE) * cos(turn)
        return doubleArrayOf(k * a + cx * g, k * b + cx * h, cx, k * d + cy * g, k * e + cy * h, cy, g, h, 1.0)
    }

    public companion object {
        /** Past this the dial is too edge-on to read (the web's, where Google Earth stops too). */
        public const val MAX_TILT_DEG: Double = 62.0

        /** The dial's SVG is this many of its units across (`viewBox="-50 -50 100 100"`). */
        public const val DIAL_UNITS: Double = 100.0

        /** The web's perspective, 220 px over a dial 72 px across, in the dial's units. */
        public const val DIAL_PERSPECTIVE: Double = 220.0 / 72.0 * DIAL_UNITS

        /** For a camera's [heading] (radians clockwise from north) and [pitch] (radians up from level, −π/2 straight down). */
        public fun of(heading: Double, pitch: Double): CompassPose {
            val degrees = ((toDegrees(heading) % 360) + 360) % 360
            val tilt = minOf(MAX_TILT_DEG, maxOf(0.0, 90 + toDegrees(pitch)))
            // 359.6° reads as 000, not 360; halves up, as JavaScript's Math.round.
            val shown = floor(degrees + 0.5).toInt() % 360
            return CompassPose(-degrees, tilt, shown.toString().padStart(3, '0') + "°T")
        }

        // The web's arithmetic, times 180 and then over π, so the last digit agrees with its fixture.
        private fun toDegrees(radians: Double): Double = radians * 180 / PI
    }
}
