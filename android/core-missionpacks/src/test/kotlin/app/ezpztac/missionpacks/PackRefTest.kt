package app.ezpztac.missionpacks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** An editor's id for a pack item reads back as the web's `packLocalRef` reads it. */
class PackRefTest {
    @Test
    fun `an id made for a pack item names the pack and the item`() {
        val id = PackRef.localId("6f1c-22", "lz-1")
        assertEquals("pack:6f1c-22:lz-1", id)
        assertEquals(PackItemRef("6f1c-22", "lz-1"), PackRef.parse(id))
    }

    @Test
    fun `the pack ends at the first colon and the item may hold more`() {
        assertEquals(PackItemRef("p", "a:b"), PackRef.parse("pack:p:a:b"))
        assertEquals(PackItemRef("p", ":"), PackRef.parse("pack:p::"))
    }

    @Test
    fun `anything else is not a pack item`() {
        listOf("", "d-1", "6f1c", "pack:", "pack:p", "pack:p:", "pack::lz-1", "PACK:p:lz-1", " pack:p:lz-1", "x pack:p:lz-1").forEach {
            assertNull(PackRef.parse(it), it)
        }
        assertNull(PackRef.parse(null))
    }

    @Test
    fun `a line break stops the item as JavaScript's dot stops, and nothing else does`() {
        listOf('\n', '\r', Char(0x2028), Char(0x2029)).forEach { stop ->
            assertNull(PackRef.parse("pack:p:lz-1$stop"), "U+" + stop.code.toString(16))
            assertNull(PackRef.parse("pack:p:lz${stop}1"), "U+" + stop.code.toString(16))
        }
        // Java's dot stops at U+0085 too; JavaScript's does not.
        val nel = Char(0x85)
        assertEquals(PackItemRef("p", "lz${nel}1"), PackRef.parse("pack:p:lz${nel}1"))
        // The pack part is "anything but a colon", line breaks included, in both.
        assertEquals(PackItemRef("p\nq", "lz-1"), PackRef.parse("pack:p\nq:lz-1"))
    }
}
