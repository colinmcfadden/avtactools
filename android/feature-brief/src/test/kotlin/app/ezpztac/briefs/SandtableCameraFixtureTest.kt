package app.ezpztac.briefs

import app.ezpztac.brief.SandtableSteps
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/fixtures/brief/sandtable-fit.json's `views`: where the web's map, Leaflet itself, flies for a step's area with the sandtable's insets as its padding
 * (`flyToBounds(bounds, { paddingTopLeft, paddingBottomRight, maxZoom: 17 })` at `zoomSnap` 0.1), for every area of the briefs' figures on three maps with two
 * sets of insets. [SandtableCamera] works it out itself for MapLibre: the same place, and Leaflet's zoom less one (512-point tiles), never below MapLibre's 0
 * (Leaflet's 1), where Leaflet would go on out to show more than the world.
 */
class SandtableCameraFixtureTest {
    private val views = Fixtures.load("brief/sandtable-fit.json")["views"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `flies where Leaflet flies, for every area, map and inset`() {
        assertTrue(views.size >= 100)
        val wrong = views.mapIndexedNotNull { i, v ->
            val b = v["bounds"]!!.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
            val (x, y) = v["size"]!!.jsonArray.map { it.jsonPrimitive.double }
            val insets = v["insets"]!!.jsonObject.let { SandtableSteps.Insets(it.d("top"), it.d("right"), it.d("bottom"), it.d("left")) }
            val leaflet = v["leaflet"]!!.jsonObject
            val (lat, lon) = leaflet["center"]!!.jsonArray.map { it.jsonPrimitive.double }
            val zoom = maxOf(0.0, leaflet.d("zoom") - 1)
            val view = SandtableCamera.fit(SandtableSteps.Bounds(b[0][0], b[0][1], b[1][0], b[1][1]), x, y, insets)
            when {
                view == null -> "$i: no view, expected $lat, $lon at $zoom"
                Math.abs(view.zoom - zoom) > 1e-9 -> "$i: zoom ${view.zoom}, expected $zoom"
                // The middle as Leaflet puts it, wherever Leaflet's own zoom was clamped to MapLibre's least.
                leaflet.d("zoom") >= 1 && (Math.abs(view.center.lat - lat) > 1e-9 || Math.abs(view.center.lon - SandtableCamera.wrap(lon)) > 1e-9) ->
                    "$i: middle ${view.center}, expected $lat, ${SandtableCamera.wrap(lon)}"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), wrong.take(20))
    }

    @Test
    fun `the fixture reaches every way a fit ends, far out, close in and between`() {
        val zooms = views.map { it["leaflet"]!!.jsonObject.d("zoom") }
        assertTrue(zooms.any { it == 17.0 })
        assertTrue(zooms.any { it < 1.0 })
        assertTrue(zooms.any { it > 5 && it < 15 })
    }

    private fun JsonObject.d(key: String) = this[key]!!.jsonPrimitive.double
}
