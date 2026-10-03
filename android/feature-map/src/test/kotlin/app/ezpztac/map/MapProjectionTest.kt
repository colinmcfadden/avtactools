package app.ezpztac.map

import app.ezpztac.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {
    private val here = LatLon(34.78, -84.08)
    private fun view(zoom: Double = 17.0, bearing: Double = 0.0, density: Double = 1.0, center: LatLon = here) =
        MapProjection(CameraState(center, zoom, bearing), 1000.0, 2000.0, density)

    @Test
    fun `the camera's centre is the middle of the view`() {
        val p = view().toScreen(here)
        assertEquals(500.0, p.x, 1e-6)
        assertEquals(1000.0, p.y, 1e-6)
    }

    @Test
    fun `north is up and east is right`() {
        val centre = view().toScreen(here)
        val north = view().toScreen(LatLon(here.lat + 0.0005, here.lon))
        val east = view().toScreen(LatLon(here.lat, here.lon + 0.0005))
        assertTrue(north.y < centre.y)
        assertEquals(centre.x, north.x, 1e-6)
        assertTrue(east.x > centre.x)
        assertEquals(centre.y, east.y, 1e-6)
    }

    @Test
    fun `a degree of longitude is the world over 360 at the equator and the same everywhere along a parallel`() {
        val zoom = 10.0
        val v = view(zoom = zoom, center = LatLon(0.0, 0.0))
        val worldPoints = 512.0 * Math.pow(2.0, zoom)
        val east = v.toScreen(LatLon(0.0, 1.0))
        assertEquals(worldPoints / 360, east.x - 500.0, 1e-6)
    }

    @Test
    fun `it agrees with the meters-per-pixel the rest of the map uses`() {
        val v = view(zoom = 17.0)
        // 100 m east, at this latitude, is 100 / metersPerPixel pixels (to the precision of a flat step).
        val metres = 100.0
        val degrees = metres / (111_319.49 * Math.cos(Math.toRadians(here.lat)))
        val east = v.toScreen(LatLon(here.lat, here.lon + degrees))
        assertEquals(v.metersToPixels(metres, here.lat), east.x - 500.0, 0.05)
        assertEquals(metres / metersPerPixel(here.lat, 17.0), v.metersToPixels(metres, here.lat), 1e-9)
    }

    @Test
    fun `density scales the world and nothing else`() {
        val one = view(density = 1.0).toScreen(LatLon(here.lat, here.lon + 0.001))
        val three = view(density = 3.0).toScreen(LatLon(here.lat, here.lon + 0.001))
        assertEquals((one.x - 500.0) * 3, three.x - 500.0, 1e-6)
        assertEquals(view(density = 3.0).metersToPixels(10.0, 34.0), 3 * view(density = 1.0).metersToPixels(10.0, 34.0), 1e-9)
    }

    @Test
    fun `facing east puts the east of the centre at the top`() {
        val v = view(bearing = 90.0)
        val centre = v.toScreen(here)
        val east = v.toScreen(LatLon(here.lat, here.lon + 0.0005))
        val north = v.toScreen(LatLon(here.lat + 0.0005, here.lon))
        assertTrue(east.y < centre.y)
        assertEquals(centre.x, east.x, 1e-6)
        assertTrue(north.x < centre.x)                                   // and north is now to the left
    }

    @Test
    fun `a turned map is the same map turned`() {
        val flat = view().toScreen(LatLon(here.lat + 0.001, here.lon + 0.002))
        val turned = view(bearing = 180.0).toScreen(LatLon(here.lat + 0.001, here.lon + 0.002))
        assertEquals(500.0 - (flat.x - 500.0), turned.x, 1e-6)
        assertEquals(1000.0 - (flat.y - 1000.0), turned.y, 1e-6)
    }

    @Test
    fun `the screen and the ground agree both ways`() {
        for (bearing in listOf(0.0, 37.0, 90.0, 215.0, 359.0)) {
            for (zoom in listOf(4.0, 12.0, 17.0, 20.0)) {
                val v = view(zoom = zoom, bearing = bearing, density = 2.625)
                val p = LatLon(here.lat + 0.0003, here.lon - 0.0004)
                val back = v.toScreen(p).let { v.toLatLon(it.x, it.y) }
                if (zoom >= 12.0) {
                    assertEquals("lat at zoom $zoom bearing $bearing", p.lat, back.lat, 1e-9)
                    assertEquals("lon at zoom $zoom bearing $bearing", p.lon, back.lon, 1e-9)
                }
            }
        }
    }

    @Test
    fun `a tap at the middle of the view is the camera's centre`() {
        val at = view(bearing = 123.0).toLatLon(500.0, 1000.0)
        assertEquals(here.lat, at.lat, 1e-9)
        assertEquals(here.lon, at.lon, 1e-9)
    }

    @Test
    fun `across the date line a point is placed on the nearer side`() {
        val v = view(zoom = 10.0, center = LatLon(10.0, 179.99))
        val across = v.toScreen(LatLon(10.0, -179.99))
        assertTrue(across.x > 500.0)
        assertTrue(across.x - 500.0 < 500.0)                             // near, not a world away
    }

    @Test
    fun `pixels between two points`() {
        val v = view()
        val a = here
        val b = LatLon(here.lat, here.lon + 0.001)
        assertEquals(v.toScreen(b).x - v.toScreen(a).x, v.pixelsBetween(a, b), 1e-9)
    }

    @Test
    fun `with the bottom reserved for the sheet the camera's centre is the middle of what is above it`() {
        val v = MapProjection(CameraState(here, 17.0), 1000.0, 2000.0, 1.0, bottomInsetPx = 300.0)
        val p = v.toScreen(here)
        assertEquals(500.0, p.x, 1e-6)
        assertEquals(850.0, p.y, 1e-6)                                                       // (2000 - 300) / 2, not 1000
        val back = v.toLatLon(p.x, p.y)
        assertEquals(here.lat, back.lat, 1e-9)
        assertEquals(here.lon, back.lon, 1e-9)
    }

    @Test
    fun `reserving the bottom moves the picture by half of it and does not change its scale`() {
        val plain = MapProjection(CameraState(here, 17.0), 1000.0, 2000.0)
        val padded = MapProjection(CameraState(here, 17.0), 1000.0, 2000.0, 1.0, bottomInsetPx = 300.0)
        val a = LatLon(here.lat + 0.0004, here.lon + 0.0003)
        assertEquals(plain.toScreen(a).y - 150.0, padded.toScreen(a).y, 1e-6)
        assertEquals(plain.toScreen(a).x, padded.toScreen(a).x, 1e-6)
        assertEquals(plain.pixelsBetween(here, a), padded.pixelsBetween(here, a), 1e-6)
    }
}
