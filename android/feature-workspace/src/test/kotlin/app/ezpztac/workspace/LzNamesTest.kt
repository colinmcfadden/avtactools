package app.ezpztac.workspace

import app.ezpztac.model.DiagramStatus
import app.ezpztac.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Made-up names, names kept apart, when an LZ/PZ last changed, and its chips: plain logic, no screen. */
class LzNamesTest {
    @Test
    fun `the made-up name is the web's own title, and nothing else`() {
        assertTrue(LzNames.isMadeUp("LZ/PZ 3"))
        assertTrue(LzNames.isMadeUp("  LZ/PZ 12 "))
        assertFalse(LzNames.isMadeUp("LZ HAWK"))
        assertFalse(LzNames.isMadeUp("LZ/PZ 3 north"))
        assertFalse(LzNames.isMadeUp("LZ 16S GD 66993 52949"))                  // what the older form named one for its grid
    }

    @Test
    fun `the next made-up name is the first not taken`() {
        assertEquals("LZ/PZ 1", LzNames.next(emptyList()))
        assertEquals("LZ/PZ 2", LzNames.next(listOf("LZ/PZ 1", "LZ HAWK")))
        assertEquals("LZ/PZ 2", LzNames.next(listOf("lz/pz 1", "LZ/PZ 3")))     // a gap is filled, and case does not make a name new
    }

    @Test
    fun `a name already in the Library is kept apart as the web's keep both does`() {
        assertEquals("LZ HAWK", LzNames.free("LZ HAWK", listOf("LZ RAZORBILL")))
        assertEquals("LZ HAWK 2", LzNames.free("LZ HAWK", listOf("lz hawk")))
        assertEquals("LZ HAWK 3", LzNames.free("LZ HAWK", listOf("LZ HAWK", "LZ HAWK 2")))
    }

    @Test
    fun `when it last changed reads as the design writes it, and an unreadable time is nothing`() {
        assertEquals("Aug 25 · 17:34", LzNames.updated("2026-08-25T17:34:12.000Z", ZoneOffset.UTC))
        assertEquals("Jan 1 · 09:05", LzNames.updated("2027-01-01T14:05:00.000Z", ZoneOffset.ofHours(-5)))
        assertNull(LzNames.updated("yesterday", ZoneOffset.UTC))
        assertNull(LzNames.updated(null, ZoneOffset.UTC))
    }

    @Test
    fun `the chips say how far along it is, then whether the server has it`() {
        assertEquals(
            listOf(LzChip("Analyzed", ChipTone.SUCCESS), LzChip("Synced", ChipTone.SUCCESS)),
            lzChips(DiagramStatus.ANALYZED, SyncStatus.SYNCED, null, madeUp = false),
        )
        assertEquals(
            listOf(LzChip("Target set", ChipTone.WARNING), LzChip("Waiting to sync", ChipTone.WARNING)),
            lzChips(DiagramStatus.TARGETED, SyncStatus.PENDING, null, madeUp = false),
        )
        assertEquals(LzChip("No target", ChipTone.NEUTRAL), lzChips(DiagramStatus.DRAFT, null, null, madeUp = false).first())
    }

    @Test
    fun `one still to be named says it must be analysed first, and a conflict copy says so above all`() {
        assertEquals(LzChip("Analyze to save", ChipTone.INFO), lzChips(DiagramStatus.TARGETED, SyncStatus.SYNCED, null, madeUp = true).last())
        assertEquals(LzChip("Synced", ChipTone.SUCCESS), lzChips(DiagramStatus.ANALYZED, SyncStatus.SYNCED, null, madeUp = true).last())
        assertEquals(LzChip("Conflict copy", ChipTone.ERROR), lzChips(DiagramStatus.TARGETED, SyncStatus.PENDING, "other", madeUp = true).last())
    }
}
