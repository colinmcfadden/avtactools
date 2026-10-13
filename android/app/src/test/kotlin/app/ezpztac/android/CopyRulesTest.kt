package app.ezpztac.android

import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.sync.RecordKind
import app.ezpztac.workspace.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who the frame offers copies into a mission pack to, and where. */
class CopyRulesTest {
    private fun pack(role: String? = "editor", finished: Boolean = false, readOnly: Boolean = false, status: PackStatus = PackStatus.LIVE) =
        OpenPackUi("dk", "OP DK", emptyList(), status, finished, readOnly, pending = 0, role = role)

    @Test
    fun `Add to Mission Pack is asked outside a pack, while packs run here`() {
        assertTrue(CopyRules.asksWhere(packsHere = true, open = null))
        assertFalse(CopyRules.asksWhere(packsHere = false, open = null))
        assertFalse(CopyRules.asksWhere(packsHere = true, open = pack()))
    }

    @Test
    fun `a Library item is added to the pack open only while this person can change it`() {
        assertEquals("OP DK", CopyRules.addingTo(true, pack()))
        assertEquals("OP DK", CopyRules.addingTo(true, pack(role = "owner")))
        assertEquals("OP DK", CopyRules.addingTo(true, pack(role = null)))                         // nothing says yet: the copy asks the server
        assertNull(CopyRules.addingTo(true, pack(role = "viewer")))                                  // known from the list before the pack's own state
        assertNull(CopyRules.addingTo(true, pack(readOnly = true)))
        assertNull(CopyRules.addingTo(true, pack(finished = true)))
        assertNull(CopyRules.addingTo(true, pack(status = PackStatus.GONE)))
        assertNull(CopyRules.addingTo(false, pack()))
        assertNull(CopyRules.addingTo(true, null))
    }

    @Test
    fun `the Pack panel's foot is for its owner or anyone who can add to it, once loaded and not finished`() {
        assertTrue(CopyRules.footShown(loaded = true, open = pack(role = "owner"), canAdd = false))
        assertTrue(CopyRules.footShown(loaded = true, open = pack(), canAdd = true))
        assertFalse(CopyRules.footShown(loaded = true, open = pack(), canAdd = false))
        assertFalse(CopyRules.footShown(loaded = false, open = pack(role = "owner"), canAdd = true))
        assertFalse(CopyRules.footShown(loaded = true, open = pack(role = "owner", finished = true), canAdd = false))
        assertFalse(CopyRules.footShown(loaded = true, open = pack(role = "owner", status = PackStatus.GONE), canAdd = true))
        assertFalse(CopyRules.footShown(loaded = true, open = null, canAdd = true))
    }

    @Test
    fun `a Library tab's items are records of their kind`() {
        assertEquals(RecordKind.LZ, CopyRules.recordKindOf(LibraryTab.LZ))
        assertEquals(RecordKind.ROUTE, CopyRules.recordKindOf(LibraryTab.ROUTES))
        assertEquals(RecordKind.POINT_SET, CopyRules.recordKindOf(LibraryTab.POINTS))
    }

    @Test
    fun `a pack made to start with items from the Library opens it once it is open, waits meanwhile, and another pack lets it go`() {
        assertEquals(CopyRules.LibraryAfter.WAIT, CopyRules.libraryAfter("new", null))
        assertEquals(CopyRules.LibraryAfter.OPEN_LIBRARY, CopyRules.libraryAfter("new", "new"))
        assertEquals(CopyRules.LibraryAfter.LET_GO, CopyRules.libraryAfter("new", "dk"))
    }
}
