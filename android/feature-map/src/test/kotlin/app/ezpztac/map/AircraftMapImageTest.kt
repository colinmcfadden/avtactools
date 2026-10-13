package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The picture an aircraft is handed to the map as, drawn for real (Robolectric's native graphics) on a phone's screen (xxhdpi): a bitmap is made with
 * the screen's density, and MapLibre takes an image's pixel ratio from that density (`Style.toImage`: `density / 160`), so these are the tests a
 * picture sized in pixels fails on a device and passes on the JVM's default screen of one pixel to the point.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxhdpi")
class AircraftMapImageTest {
    private val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
    private val screens = listOf(1f, 2f, 2.625f, 3f, 3.5f, 4f)

    private fun forMap(key: String, state: IconState, screen: Float) = AircraftIconRenderer.forMap(assets, key, state, screen)

    @Test
    fun `the bitmap the map is handed says it is as many points across as its size on the map assumes, on any screen`() {
        for (screen in screens) for (key in AircraftIcons.KEYS) for (state in IconState.entries) {
            val b = forMap(key, state, screen)
            assertEquals("$key $state, screen $screen", b.width, b.height)
            // MapLibre counts the picture as width / (density / 160) points.
            assertEquals("$key $state, screen $screen: density ${b.density}, width ${b.width}", b.width * 160, b.density * LzScene.AIRCRAFT_ICON_PT)
        }
    }

    @Test
    fun `it is drawn with as many pixels as the screen has for it, so it is as sharp as the screen`() {
        assertEquals(672, forMap("uh60", IconState.NORMAL, 3.5f).width)                                     // 192 points at 3.5 pixels each
        assertEquals(384, forMap("ah64", IconState.NORMAL, 2f).width)
        assertEquals(504, forMap("ch47", IconState.VIOLATION, 2.625f).width)
    }

    private val uh60 = AircraftProfile()
    private val ah64 = AircraftProfile(slug = "ah64d", designation = "AH-64D", iconKey = "ah64", rotorDiameterM = 14.63)
    private val ch47 = AircraftProfile(slug = "ch47f", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 30.14)
    private val mh6 = AircraftProfile(slug = "mh6", designation = "MH-6M", iconKey = "mh6", rotorDiameterM = 8.38)
    private val odd = AircraftProfile(slug = "odd", designation = "ODD", iconKey = "generic", rotorDiameterM = 12.0)

    private fun k0(profile: AircraftProfile, latitude: Double): Double {
        val aircraft = SceneAircraft(
            GraphicRef("helicopters", "1"), LatLon(latitude, 10.0), 0.0, profile.rotorDiameterM, profile.rotorTipClearanceM,
            AircraftIcons.keyFor(profile.iconKey), profile.designation, violating = false, selected = false,
        )
        val scene = LzScene(null, emptyList(), emptyList(), null, GraphicsScene(aircraft = listOf(aircraft)))
        val heli = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.single().jsonObject
        return heli.getValue("properties").jsonObject.getValue("k0").jsonPrimitive.doubleOrNull!!
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Within a few levels of [colour] and opaque: the middle of a line in it, not the anti-aliased edge where it meets black. */
    private fun isColour(pixel: Int, colour: Int) = Color.alpha(pixel) > 200 &&
        abs(Color.red(pixel) - Color.red(colour)) + abs(Color.green(pixel) - Color.green(colour)) + abs(Color.blue(pixel) - Color.blue(colour)) < 90

    /**
     * How far from ([cx], [cy]) the rotor's ring is drawn in the accent, in pixels, looking along [degrees] (clockwise from the nose): the middle of the
     * first band of the accent met coming in from beyond the ring, which is the ring's outline, its stroke being centred on it.
     */
    private fun ringRadius(b: Bitmap, cx: Double, cy: Double, degrees: Double, accent: Int): Double {
        val angle = Math.toRadians(degrees)
        val at = { s: Double -> b.getPixel((cx + sin(angle) * s).roundToInt().coerceIn(0, b.width - 1), (cy - cos(angle) * s).roundToInt().coerceIn(0, b.height - 1)) }
        var s = b.width.toDouble()
        while (s > 0 && !isColour(at(s), accent)) s -= 0.25
        val outer = s
        while (s > 0 && isColour(at(s), accent)) s -= 0.25
        return (outer + s) / 2
    }

    /** Where to look for each rotor's ring, in the picture's own units: its masts, its radius, and the directions clear of its blades and its airframe. */
    private class Rotor(val cx: Double, val cy: Double, val r: Double, val directions: List<Double>)

    private fun rotors(key: String): List<Rotor> = when (key) {
        "uh60" -> listOf(Rotor(0.0, 0.0, 81.8, listOf(0.0, 90.0, 270.0)))                    // the blades are an X; the tail crosses the ring behind
        "mh6" -> listOf(Rotor(50.0, 42.0, 46.0, listOf(36.0, 108.0, 252.0, 324.0)))          // five blades, one up the nose; the tail behind
        "ch47" -> listOf(Rotor(50.0, 26.0, 25.0, listOf(60.0, 300.0)), Rotor(50.0, 74.0, 25.0, listOf(60.0, 180.0, 300.0)))
        else -> listOf(Rotor(50.0, 42.0, 46.0, listOf(0.0, 90.0, 270.0)))                    // four blades, an X; the tail behind
    }

    @Test
    fun `each rotor in the picture is where its frame says, and as wide on the ground as the airframe's rotor, at any zoom and latitude`() {
        for ((profile, screen) in listOf(uh60 to 3.5f, ah64 to 2f, ch47 to 2.625f, mh6 to 3f, odd to 1f)) for (state in IconState.entries) {
            val key = profile.iconKey
            val b = forMap(key, state, screen)
            val frame = AircraftIcons.frame(key)
            val pxPerUnit = b.width / frame.side
            // What the profile's rotor diameter measures, from the rotors themselves and not the frame: a disc's diameter, or a tandem's span from the
            // front disc's tip to the back one's.
            val span = rotors(key).maxOf { it.cy + it.r } - rotors(key).minOf { it.cy - it.r }
            for (rotor in rotors(key)) {
                // The picture's middle is the frame's, which the map puts on the aircraft's point.
                val cx = b.width / 2.0 + (rotor.cx - frame.cx) * pxPerUnit
                val cy = b.height / 2.0 + (rotor.cy - frame.cy) * pxPerUnit
                for (direction in rotor.directions) {
                    val radiusPx = ringRadius(b, cx, cy, direction, state.colour(IconColor.ACCENT))
                    assertEquals("$key $state at $direction degrees", rotor.r * pxPerUnit, radiusPx, 1.5)
                    for (latitude in listOf(0.0, 34.5, 64.8)) for (zoom in listOf(14.0, 18.0, 21.0)) {
                        // Ground metres in one pixel of the picture, as the map draws it: points per pixel, times the layer's size, times metres per point.
                        val metresPerPixel = 160.0 / b.density * k0(profile, latitude) * 2.0.pow(zoom) * metersPerPixel(latitude, zoom)
                        val expected = profile.rotorDiameterM * rotor.r / span                                  // this disc's radius on the ground
                        assertEquals("$key at $latitude, zoom $zoom", expected, radiusPx * metresPerPixel, 1.5 * metresPerPixel)
                    }
                }
            }
        }
    }

    @Test
    fun `a violation is the same picture in red, no green left and the red where the green was`() {
        val green = IconState.NORMAL.colour(IconColor.ACCENT)
        val red = IconState.VIOLATION.colour(IconColor.ACCENT)
        for (key in AircraftIcons.KEYS) {
            val normal = pixels(forMap(key, IconState.NORMAL, 2f))
            val violation = pixels(forMap(key, IconState.VIOLATION, 2f))
            var greens = 0
            for (i in normal.indices) {
                val n = normal[i]
                val v = violation[i]
                assertTrue("$key: green left at pixel $i", !isColour(v, green))
                assertTrue("$key: red in the normal picture at pixel $i", !isColour(n, red))
                if (isColour(n, green)) { greens++; assertTrue("$key: pixel $i", isColour(v, red)) }
                assertEquals("$key: only the colour changes at pixel $i", Color.alpha(n), Color.alpha(v))
            }
            assertTrue("$key has green to turn red", greens > 1000)
        }
    }

    @Test
    fun `nothing is cut off at the picture's edge, the UH-60's tail loop included`() {
        for (key in AircraftIcons.KEYS) {
            val b = forMap(key, IconState.NORMAL, 3f)
            val last = b.width - 1
            val sides = (0 until b.width).flatMap { i -> listOf(b.getPixel(i, 0), b.getPixel(0, i), b.getPixel(last, i)) }
            assertTrue("$key touches its top or sides", sides.all { Color.alpha(it) == 0 })
            val bottom = (0 until b.width).filter { Color.alpha(b.getPixel(it, last)) > 0 }
            if (key != "uh60") assertTrue("$key touches its bottom", bottom.isEmpty())
            else {
                // The outer half of the loop's 5-decimetre outline is the picture's edge, the least margin that keeps it whole: the bottom row is painted only there.
                val pxPerDm = b.width / AircraftIcons.UH60_FRAME.side
                assertTrue("the UH-60 touches its bottom only at its loop: $bottom", bottom.all { abs(it + 0.5 - b.width / 2.0) <= (4.2 + 2.5) * pxPerDm + 1 })
            }
        }
        // That row being painted cannot show the loop is whole, so the UH-60 is drawn again with room round its frame, at the same pixels to the decimetre:
        // nothing is painted beyond the frame, so nothing the frame cuts out is lost.
        val frame = AircraftIcons.UH60_FRAME
        val svg = assets.open(AircraftIcons.UH60_ASSET).use { it.readBytes().decodeToString() }
        val room = 10.0
        val side = frame.side + 2 * room
        val wider = Regex("viewBox=\"[^\"]*\"").replace(svg) { "viewBox=\"${-side / 2} ${-side / 2} $side $side\"" }
        val pxPerDm = 3.0 * LzScene.AIRCRAFT_ICON_PT / frame.side
        val size = (side * pxPerDm).roundToInt()
        val b = AircraftIconRenderer.renderSvg(wider, IconState.NORMAL, size)
        val scale = size / side
        val inside = (room * scale).toInt() until kotlin.math.ceil((room + frame.side) * scale).toInt()
        val beyond = (0 until size).flatMap { y -> (0 until size).filter { x -> (x !in inside || y !in inside) && Color.alpha(b.getPixel(x, y)) > 0 }.map { x -> x to y } }
        assertTrue("the UH-60 is painted beyond its frame at ${beyond.take(5)}", beyond.isEmpty())
    }
}
