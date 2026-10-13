package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.pow

/**
 * The picture an aircraft is handed to the map as, drawn for real (Robolectric's native graphics) on a phone's screen (xxhdpi): a bitmap is made with
 * the screen's density, and MapLibre takes an image's pixel ratio from that density (`Style.toImage`: `density / 160`), so these are the tests a
 * picture sized in pixels fails on a device and passes on the JVM's default screen of one pixel to the point.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxhdpi")
class AircraftMapImageTest {
    private val screens = listOf(1f, 2f, 2.625f, 3f, 3.5f, 4f)

    @Test
    fun `the bitmap the map is handed says it is as many points across as its size on the map assumes, on any screen`() {
        for (screen in screens) for (key in AircraftIcons.KEYS) for (state in IconState.entries) {
            val b = AircraftIconRenderer.forMap(key, state, screen)
            assertEquals("$key $state, screen $screen", b.width, b.height)
            // MapLibre counts the picture as width / (density / 160) points.
            assertEquals("$key $state, screen $screen: density ${b.density}, width ${b.width}", b.width * 160, b.density * LzScene.AIRCRAFT_ICON_PT)
        }
    }

    @Test
    fun `it is drawn with as many pixels as the screen has for it, so it is as sharp as the screen`() {
        assertEquals(672, AircraftIconRenderer.forMap("uh60", IconState.NORMAL, 3.5f).width)                 // 192 points at 3.5 pixels each
        assertEquals(384, AircraftIconRenderer.forMap("ah64", IconState.NORMAL, 2f).width)
        assertEquals(504, AircraftIconRenderer.forMap("ch47", IconState.VIOLATION, 2.625f).width)
    }

    private val uh60 = AircraftProfile()
    private val ah64 = AircraftProfile(slug = "ah64d", designation = "AH-64D", iconKey = "ah64", rotorDiameterM = 14.63)
    private val ch47 = AircraftProfile(slug = "ch47f", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 30.14)
    private val mh6 = AircraftProfile(slug = "mh6", designation = "MH-6M", iconKey = "mh6", rotorDiameterM = 8.38)

    private fun k0(profile: AircraftProfile, latitude: Double): Double {
        val aircraft = SceneAircraft(
            GraphicRef("helicopters", "1"), LatLon(latitude, 10.0), 0.0, profile.rotorDiameterM, profile.rotorTipClearanceM,
            AircraftIcons.keyFor(profile.iconKey), profile.designation, violating = false, selected = false,
        )
        val scene = LzScene(null, emptyList(), emptyList(), null, GraphicsScene(aircraft = listOf(aircraft)))
        val heli = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.single().jsonObject
        return heli.getValue("properties").jsonObject.getValue("k0").jsonPrimitive.doubleOrNull!!
    }

    /**
     * Where the rotor is drawn, in pixels: from the outer edge of its ring on one side to the outer edge on the other, along the line through the
     * picture's middle that crosses it whole (across a single rotor; nose to tail over a tandem's two).
     */
    private fun rotorEdges(b: Bitmap, alongNoseToTail: Boolean): Pair<Int, Int> {
        val middle = b.width / 2
        val drawn = { i: Int -> Color.alpha(if (alongNoseToTail) b.getPixel(middle, i) else b.getPixel(i, middle)) > 0 }
        val first = (0 until b.width).first(drawn)
        val last = (b.width - 1 downTo 0).first(drawn)
        return first to last
    }

    @Test
    fun `the rotor in the picture is centred on the aircraft's point and as wide as the airframe's rotor on the ground`() {
        for ((profile, screen) in listOf(uh60 to 3.5f, ah64 to 2f, ch47 to 2.625f, mh6 to 3f)) {
            val tandem = profile.iconKey == "ch47"
            val b = AircraftIconRenderer.forMap(profile.iconKey, IconState.NORMAL, screen)
            val (first, last) = rotorEdges(b, alongNoseToTail = tandem)
            // The map puts the middle of the picture on the aircraft's point, which is where the rotor's middle must be.
            assertEquals("${profile.designation}: the rotor's middle", b.width / 2.0, (first + last + 1) / 2.0, 1.5)
            // The ring is drawn on the rotor's outline, so its outer edge is half its stroke beyond.
            val ring = AircraftIcons.shapes(profile.iconKey).filterIsInstance<IconShape.Circle>().maxOf { it.strokeWidth }
            val frame = AircraftIcons.frame(profile.iconKey)
            for (latitude in listOf(0.0, 34.5, 64.8)) for (zoom in listOf(14.0, 18.0)) {
                // Ground metres in one pixel of the picture, as the map draws it: points per pixel, times the layer's size, times metres per point.
                val metresPerPixel = 160.0 / b.density * k0(profile, latitude) * 2.0.pow(zoom) * metersPerPixel(latitude, zoom)
                val expected = profile.rotorDiameterM * (frame.rotorSpan + ring) / frame.rotorSpan
                assertEquals("${profile.designation} at $latitude, zoom $zoom", expected, (last + 1 - first) * metresPerPixel, 2 * metresPerPixel)
            }
        }
    }

    @Test
    fun `nothing is drawn past the picture's edge`() {
        for (key in AircraftIcons.KEYS) {
            val b = AircraftIconRenderer.forMap(key, IconState.NORMAL, 3f)
            val edge = (0 until b.width).flatMap { i -> listOf(b.getPixel(i, 0), b.getPixel(i, b.height - 1), b.getPixel(0, i), b.getPixel(b.width - 1, i)) }
            assertTrue("$key touches its edge", edge.all { Color.alpha(it) == 0 })
        }
    }
}
