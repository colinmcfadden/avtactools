package app.ezpztac.android.frame

import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.network.ApiUser
import org.junit.Assert.assertEquals
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
        assertEquals(Panel.entries, panelsFor(user(emptyMap())))
        assertEquals(listOf(Panel.Lz, Panel.Threats, Panel.Imports), panelsFor(user(mapOf("routes" to false))))
        assertEquals(listOf(Panel.Lz, Panel.Routes, Panel.Imports), panelsFor(user(mapOf("threats" to false, "routes" to true))))
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
