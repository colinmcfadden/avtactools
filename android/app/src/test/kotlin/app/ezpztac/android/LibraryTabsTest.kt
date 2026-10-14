package app.ezpztac.android

import app.ezpztac.android.frame.Panel
import app.ezpztac.workspace.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the Library opens, and which of its tabs an account has. */
class LibraryTabsTest {
    @Test
    fun `the Library chip opens at the tab of the panel showing, the LZ-PZs otherwise`() {
        assertEquals(LibraryTab.LZ, libraryTabFor(Panel.Lz))
        assertEquals(LibraryTab.ROUTES, libraryTabFor(Panel.Routes))
        assertEquals(LibraryTab.POINTS, libraryTabFor(Panel.Imports))
        assertEquals(LibraryTab.LZ, libraryTabFor(Panel.Threats))                                  // threats are not saved yet (step T)
    }

    @Test
    fun `an account has the tabs of the panels it is offered`() {
        assertEquals(LibraryTab.entries, libraryTabsFor(Panel.entries))
        assertEquals(listOf(LibraryTab.LZ, LibraryTab.POINTS), libraryTabsFor(listOf(Panel.Lz, Panel.Threats, Panel.Imports)))
    }
}
