package app.ezpztac.workspace

import app.ezpztac.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a point of a route is to a crew, and the name it takes when it is made something else from its bar's dialog. */
class PointRolesTest {
    private val amps = RoutePoint.KIND_AMPS

    @Test
    fun `a point is what its AMPS type says, an IP point the SP or the RP by its name`() {
        assertEquals(PointRole.TARGET, PointRoles.roleOf(amps, "target", ".TGT"))
        assertEquals(PointRole.CHECKPOINT, PointRoles.roleOf(amps, "turn", "HAWK"))
        assertEquals("a type this version does not know is a checkpoint, as it is drawn", PointRole.CHECKPOINT, PointRoles.roleOf(amps, "RtePtSTTO", ".X"))
        assertEquals(PointRole.CHECKPOINT, PointRoles.roleOf(amps, null, ".CP"))
        assertEquals(PointRole.SP, PointRoles.roleOf(amps, "ip", ".SP"))
        assertEquals(PointRole.SP, PointRoles.roleOf(amps, "ip", "sp 1"))
        assertEquals(PointRole.RP, PointRoles.roleOf(amps, "ip", ".RP"))
        assertEquals(PointRole.RP, PointRoles.roleOf(amps, "ip", "RP HAWK"))
        assertNull("named neither", PointRoles.roleOf(amps, "ip", ".IP"))
        assertNull("a word that starts with the letters is not one", PointRoles.roleOf(amps, "ip", "SPRING"))
        assertNull(PointRoles.roleOf(amps, "ip", "RPM"))
        assertEquals(PointRole.SHAPING, PointRoles.roleOf(RoutePoint.KIND_SHAPING, null, ""))
        assertEquals(PointRole.SHAPING, PointRoles.roleOf(null, null, null))
    }

    @Test
    fun `the SP and the RP take their names, nothing else telling them apart`() {
        assertEquals(".SP", PointRoles.nameFor(PointRole.SP, "HAWK"))
        assertEquals(".RP", PointRoles.nameFor(PointRole.RP, ".SP"))
        assertEquals(".RP", PointRoles.nameFor(PointRole.RP, ""))
    }

    @Test
    fun `a checkpoint or the target keeps a name of the person's own, and takes its own in place of one that only said what the point was`() {
        assertEquals("HAWK", PointRoles.nameFor(PointRole.CHECKPOINT, "HAWK"))
        assertEquals("LZ HAWK", PointRoles.nameFor(PointRole.TARGET, "LZ HAWK"))
        assertEquals(".CP", PointRoles.nameFor(PointRole.CHECKPOINT, ".SP"))
        assertEquals(".CP", PointRoles.nameFor(PointRole.CHECKPOINT, ".TGT"))
        assertEquals(".CP", PointRoles.nameFor(PointRole.CHECKPOINT, "RP 2"))
        assertEquals(".CP", PointRoles.nameFor(PointRole.CHECKPOINT, ""))
        assertEquals("a checkpoint's own stays", ".CP3", PointRoles.nameFor(PointRole.CHECKPOINT, ".CP3"))
        assertEquals(".TGT", PointRoles.nameFor(PointRole.TARGET, ".CP3"))
        assertEquals(".TGT", PointRoles.nameFor(PointRole.TARGET, ".LZ"))
        assertEquals(".TGT", PointRoles.nameFor(PointRole.TARGET, ".NEWPT"))
        assertEquals(".TGT", PointRoles.nameFor(PointRole.TARGET, "  "))
    }

    @Test
    fun `a shaping point has no name`() {
        assertEquals("", PointRoles.nameFor(PointRole.SHAPING, "HAWK"))
    }
}
