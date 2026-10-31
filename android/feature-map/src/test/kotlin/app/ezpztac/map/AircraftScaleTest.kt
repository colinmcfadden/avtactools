package app.ezpztac.map

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * An aircraft on the map is its own size on the ground, as the web means it to be (`calculateSizePx` in `Helicopter.jsx`): the rotor drawn is the
 * profile's rotor diameter at every zoom and latitude. The layer's size is `k0 * 2^zoom` (an exponential interpolation, base 2, from `k0` at zoom 0),
 * times the points the picture is across; the rotor is the part of the picture its frame says.
 */
class AircraftScaleTest {
    private val uh60 = AircraftProfile()                                                                              // the UH-60L, 16.357 m
    private val ah64 = AircraftProfile(slug = "ah64d", designation = "AH-64D", iconKey = "ah64", rotorDiameterM = 14.63)
    private val ch47 = AircraftProfile(slug = "ch47f", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 30.14)  // the overall fore-to-aft span
    private val mh6 = AircraftProfile(slug = "mh6", designation = "MH-6M", iconKey = "mh6", rotorDiameterM = 8.38)
    private val odd = AircraftProfile(slug = "odd", designation = "ODD", iconKey = "generic", rotorDiameterM = 12.0)

    /** The `k0` the map is given for an aircraft of [profile] at [latitude]. */
    private fun k0(profile: AircraftProfile, latitude: Double): Double {
        val aircraft = SceneAircraft(
            GraphicRef("helicopters", "1"), LatLon(latitude, -84.1), 0.0, profile.rotorDiameterM, profile.rotorTipClearanceM,
            AircraftIcons.keyFor(profile.iconKey), profile.designation, violating = false, selected = false,
        )
        val scene = LzScene(null, emptyList(), emptyList(), null, GraphicsScene(aircraft = listOf(aircraft)))
        val heli = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.single().jsonObject
        return heli.getValue("properties").jsonObject.getValue("k0").jsonPrimitive.doubleOrNull!!
    }

    @Test
    fun `the rotor drawn is the airframe's rotor diameter on the ground, at every zoom and latitude`() {
        for (profile in listOf(uh60, ah64, ch47, mh6, odd)) for (latitude in listOf(0.0, 34.5, -45.0, 64.8)) for (zoom in listOf(12.0, 15.5, 18.0, 21.0)) {
            val frame = AircraftIcons.frame(profile.iconKey)
            val picturePoints = k0(profile, latitude) * 2.0.pow(zoom) * LzScene.AIRCRAFT_ICON_PT
            val rotorPoints = picturePoints * frame.rotorSpan / frame.side
            val groundM = rotorPoints * metersPerPixel(latitude, zoom)
            assertEquals("${profile.designation} at $latitude, zoom $zoom", profile.rotorDiameterM, groundM, profile.rotorDiameterM * 0.001)
        }
    }

    @Test
    fun `a bigger rotor is drawn bigger, by as much as it is bigger`() {
        val ratio = (k0(ch47, 34.5) * AircraftIcons.frame("ch47").rotorSpan / AircraftIcons.frame("ch47").side) /
            (k0(mh6, 34.5) * AircraftIcons.frame("mh6").rotorSpan / AircraftIcons.frame("mh6").side)
        assertEquals(30.14 / 8.38, ratio, 1e-9)
    }
}
