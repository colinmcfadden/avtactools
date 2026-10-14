package app.ezpztac.android.frame

import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.network.ApiUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which panel shows and how high, which are offered, and what the account button and the badges say: plain logic, no screen. */
class PanelsTest {
    @Test
    fun `another panel opens at the height the sheet is at, and from lowered to half`() {
        assertEquals(Panel.Routes to SheetDetent.Half, pick(Panel.Lz, SheetDetent.Lowered, Panel.Routes))
        assertEquals(Panel.Routes to SheetDetent.Half, pick(Panel.Lz, SheetDetent.Half, Panel.Routes))
        assertEquals(Panel.Routes to SheetDetent.Full, pick(Panel.Lz, SheetDetent.Full, Panel.Routes))
    }

    @Test
    fun `the panel already showing goes down to its header, and up to half when it is down`() {
        assertEquals(Panel.Lz to SheetDetent.Lowered, pick(Panel.Lz, SheetDetent.Half, Panel.Lz))
        assertEquals(Panel.Lz to SheetDetent.Lowered, pick(Panel.Lz, SheetDetent.Full, Panel.Lz))
        assertEquals(Panel.Lz to SheetDetent.Half, pick(Panel.Lz, SheetDetent.Lowered, Panel.Lz))
    }

    @Test
    fun `routes and threats are offered only while their features are on, as on the web`() {
        assertEquals(Panel.entries - Panel.Pack, panelsFor(user(emptyMap())))
        assertEquals(listOf(Panel.Lz, Panel.Threats, Panel.Imports), panelsFor(user(mapOf("routes" to false))))
        assertEquals(listOf(Panel.Lz, Panel.Routes, Panel.Imports), panelsFor(user(mapOf("threats" to false, "routes" to true))))
    }

    @Test
    fun `the Pack panel comes first, and only while a pack is open for an account with Mission Packs`() {
        assertEquals(Panel.entries, panelsFor(user(emptyMap()), packOpen = true))
        assertEquals(Panel.Pack, panelsFor(user(emptyMap()), packOpen = true).first())
        assertEquals(Panel.entries - Panel.Pack, panelsFor(user(mapOf("mission_packs" to false)), packOpen = true))
        assertEquals(Panel.Lz, shownPanel(Panel.Pack, panelsFor(user(emptyMap()))))                  // the pack closed: the LZ/PZ panel
        assertEquals("changed since you looked", spokenCount(Panel.Pack, PanelCounts(packNew = 2)))
        assertEquals(null, spokenCount(Panel.Pack, PanelCounts()))
    }

    @Test
    fun `the Pack panel is looked at only while it shows, in sight, with nothing over it`() {
        assertTrue(lookingAtPack(Panel.Pack, tablet = false, listShown = false, detent = SheetDetent.Half, covered = false))
        assertTrue(lookingAtPack(Panel.Pack, tablet = false, listShown = false, detent = SheetDetent.Full, covered = false))
        assertFalse(lookingAtPack(Panel.Pack, tablet = false, listShown = true, detent = SheetDetent.Lowered, covered = false))
        assertFalse(lookingAtPack(Panel.Pack, tablet = false, listShown = true, detent = SheetDetent.Half, covered = true))       // the Library, a plan page
        assertFalse(lookingAtPack(Panel.Lz, tablet = false, listShown = true, detent = SheetDetent.Half, covered = false))
        assertTrue(lookingAtPack(Panel.Pack, tablet = true, listShown = true, detent = SheetDetent.Lowered, covered = false))
        assertFalse(lookingAtPack(Panel.Pack, tablet = true, listShown = false, detent = SheetDetent.Full, covered = false))      // waiting behind a pane
    }

    @Test
    fun `the frame's tap hides the phone's frame and shows it again, and never hides it while a screen reader explores by touch`() {
        val frame = FrameState()
        frame.tapped(exploring = false)
        assertTrue(frame.hidden)
        frame.tapped(exploring = false)
        assertFalse(frame.hidden)
        frame.tapped(exploring = true)
        assertFalse(frame.hidden)
        frame.hide()
        frame.tapped(exploring = true)                                                               // exploring, a tap only ever shows it
        assertFalse(frame.hidden)
    }

    @Test
    fun `a panel chosen, by the person or in code, shows the frame, even the panel already chosen`() {
        val frame = FrameState(Panel.Routes, hidden = true)
        frame.choose(Panel.Routes)
        assertFalse(frame.hidden)
        assertEquals(Panel.Routes, frame.chosen)
        frame.hide()
        frame.choose(Panel.Threats)
        assertFalse(frame.hidden)
        assertEquals(Panel.Threats, frame.chosen)
        frame.hide()
        frame.show()
        assertFalse(frame.hidden)
    }

    @Test
    fun `as the frame hides, the readout goes from the sheet's edge to the foot of the screen with it`() {
        assertEquals(1500f, controlsEdge(1500f, 0f, 2400f, measured = true)!!, 0.01f)
        assertEquals(1950f, controlsEdge(1500f, 0.5f, 2400f, measured = true)!!, 0.01f)
        assertEquals(2400f, controlsEdge(1500f, 1f, 2400f, measured = true)!!, 0.01f)
        assertEquals(2400f, controlsEdge(null, 0.2f, 2400f, measured = true)!!, 0.01f)             // no sheet laid out: the foot
        assertEquals(1500f, controlsEdge(1500f, 1f, 2400f, measured = false)!!, 0.01f)             // nothing measured yet: as before
    }

    @Test
    fun `a panel no longer offered gives way to the first`() {
        assertEquals(Panel.Lz, shownPanel(Panel.Threats, listOf(Panel.Lz, Panel.Routes, Panel.Imports)))
        assertEquals(Panel.Routes, shownPanel(Panel.Routes, Panel.entries))
    }

    @Test
    fun `the account button's letters are the first and last names', else the address's`() {
        assertEquals("CM", initialsOf("Colin McFadden", "c@example.com"))
        assertEquals("JP", initialsOf("Jean-Luc  Picard", "j@example.com"))
        assertEquals("AC", initialsOf("Ann B. Cole", "a@example.com"))
        assertEquals("C", initialsOf("cher", "c@example.com"))
        assertEquals("P", initialsOf("  ", "pilot@example.com"))
        assertEquals("P", initialsOf("— ·", "pilot@example.com"))                          // no letter in the name
        assertEquals("?", initialsOf("", "@"))
    }

    @Test
    fun `a tab says its count in words, in the singular for one`() {
        assertEquals("2 threats", spokenCount(Panel.Threats, PanelCounts(threats = 2)))
        assertEquals("1 threat", spokenCount(Panel.Threats, PanelCounts(threats = 1)))
        assertEquals("1 item", spokenCount(Panel.Imports, PanelCounts(imports = 1)))
        assertEquals(null, spokenCount(Panel.Threats, PanelCounts()))
        assertEquals(null, spokenCount(Panel.Lz, PanelCounts(threats = 4, imports = 4)))
    }

    @Test
    fun `a badge past 99 says 99+`() {
        assertEquals("7", shown(7))
        assertEquals("99", shown(99))
        assertEquals("99+", shown(100))
    }

    private fun user(features: Map<String, Boolean>) = ApiUser(
        id = 1, email = "pilot@example.com", name = "Pilot", role = "user", isAdmin = false, isActive = true, features = features, accessOk = true,
    )
}
