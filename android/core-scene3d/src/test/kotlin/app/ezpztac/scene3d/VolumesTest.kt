package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/** The bounding volumes' own arithmetic, on cases worked by hand: what culling and the screen-space error are measured with. */
class VolumesTest {
    @Test
    fun `a box's distance is the offset beyond its half-length along each axis, and none inside it`() {
        val box = OrientedBox(Vec3(10.0, 0.0, 0.0), listOf(Vec3(2.0, 0.0, 0.0), Vec3(0.0, 3.0, 0.0), Vec3(0.0, 0.0, 4.0)))
        assertEquals(0.0, box.distanceTo(Vec3(11.0, 1.0, -2.0)), 1e-12)
        assertEquals(5.0, box.distanceTo(Vec3(17.0, 0.0, 0.0)), 1e-12)
        assertEquals(sqrt(1.0 + 4.0 + 9.0), box.distanceTo(Vec3(13.0, -5.0, 7.0)), 1e-12)
        assertEquals(sqrt(2.0 * 2.0 + 3.0 * 3.0 + 4.0 * 4.0), box.radius, 1e-12)
    }

    @Test
    fun `a flat box is measured off its plane too, as Cesium's distanceSquaredTo does with an axis of no length`() {
        // A square of 20 m with no depth: a point straight above its middle is as far as it is high.
        val square = OrientedBox(Vec3.ZERO, listOf(Vec3(10.0, 0.0, 0.0), Vec3(0.0, 10.0, 0.0), Vec3.ZERO))
        assertEquals(5.0, square.distanceTo(Vec3(0.0, 0.0, 5.0)), 1e-12)
        assertEquals(sqrt(25.0 + 25.0), square.distanceTo(Vec3(15.0, 0.0, -5.0)), 1e-12)
        // A line: everything off it is distance.
        val line = OrientedBox(Vec3.ZERO, listOf(Vec3(10.0, 0.0, 0.0), Vec3.ZERO, Vec3.ZERO))
        assertEquals(sqrt(9.0 + 16.0), line.distanceTo(Vec3(5.0, 3.0, 4.0)), 1e-12)
        // A turned square: its plane holds the point, so none.
        val turned = OrientedBox(Vec3.ZERO, listOf(Vec3(0.0, 10.0, 0.0), Vec3(0.0, 0.0, 10.0), Vec3.ZERO))
        assertEquals(0.0, turned.distanceTo(Vec3(0.0, 4.0, -4.0)), 1e-12)
        assertEquals(2.0, turned.distanceTo(Vec3(2.0, 4.0, -4.0)), 1e-12)
    }

    @Test
    fun `a sphere moved by a frame that stretches is that much larger, by the most it stretches`() {
        val sphere = Sphere(Vec3(1.0, 0.0, 0.0), 2.0)
        val stretched = sphere.transformed(Mat4.translation(Vec3(0.0, 5.0, 0.0)) * Mat4.fromAxes(Vec3(3.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 10.0)))
        assertEquals(20.0, stretched.radius, 1e-12)
        assertEquals(3.0, stretched.center.x, 1e-12)
        assertEquals(5.0, stretched.center.y, 1e-12)
        assertEquals(0.0, Sphere(Vec3.ZERO, 1.0).distanceTo(Vec3(0.5, 0.0, 0.0)), 1e-12)
        assertEquals(4.0, Sphere(Vec3.ZERO, 1.0).distanceTo(Vec3(0.0, 5.0, 0.0)), 1e-12)
    }

    @Test
    fun `a plane puts a volume outside, inside or across it by how far its centre is against its reach`() {
        val up = Plane(Vec3(0.0, 0.0, 1.0), 0.0)                                              // in is above z = 0
        val box = OrientedBox(Vec3(0.0, 0.0, 5.0), listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 2.0)))
        assertEquals(Visibility.INSIDE, box.side(up))
        assertEquals(Visibility.INTERSECTING, OrientedBox(Vec3(0.0, 0.0, 1.0), box.halfAxes).side(up))
        assertEquals(Visibility.OUTSIDE, OrientedBox(Vec3(0.0, 0.0, -3.0), box.halfAxes).side(up))
        assertEquals(Visibility.OUTSIDE, Sphere(Vec3(0.0, 0.0, -3.0), 2.0).side(up))
        assertEquals(Visibility.INTERSECTING, Sphere(Vec3(0.0, 0.0, -1.0), 2.0).side(up))
    }
}
