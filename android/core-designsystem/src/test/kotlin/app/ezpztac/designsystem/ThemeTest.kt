package app.ezpztac.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun `the default is dark, because a bright screen costs a crew their night vision`() {
        assertEquals(Palette.Dark, resolve(ThemeMode.Dark, systemDark = false))
        assertEquals(Palette.Dark, resolve(ThemeMode.Dark, systemDark = true))
    }

    @Test
    fun `system follows the system, and night never does`() {
        assertEquals(Palette.Dark, resolve(ThemeMode.System, systemDark = true))
        assertEquals(Palette.Light, resolve(ThemeMode.System, systemDark = false))
        assertEquals(Palette.Night, resolve(ThemeMode.Night, systemDark = false))
        assertEquals(Palette.Night, resolve(ThemeMode.Night, systemDark = true))
    }

    @Test
    fun `a colour scheme is made of the tokens and nothing else`() {
        assertEquals(Color(Tokens.Colors.Dark.background), colorSchemeFor(ThemeMode.Dark).background)
        assertEquals(Color(Tokens.Colors.Light.primary), colorSchemeFor(ThemeMode.Light).primary)
        assertEquals(Color(Tokens.Colors.Night.onSurface), colorSchemeFor(ThemeMode.Night).onSurface)
        assertEquals(Color(Tokens.Colors.Night.error), colorSchemeFor(ThemeMode.Night).error)
    }

    @Test
    fun `night is darker than dark`() {
        // Compare the green channel's brightness: the night palette must not light the cockpit up.
        fun brightness(c: Color) = c.red + c.green + c.blue
        assertEquals(true, brightness(colorSchemeFor(ThemeMode.Night).background) < brightness(colorSchemeFor(ThemeMode.Dark).background))
        assertNotEquals(colorSchemeFor(ThemeMode.Night).primary, colorSchemeFor(ThemeMode.Dark).primary)
    }

    @Test
    fun `a toast never lights a cockpit, a dark card in every palette and night's own at night`() {
        fun brightness(argb: Long) = Color(argb).let { it.red + it.green + it.blue }
        Palette.entries.forEach { palette ->
            val card = toastColorsFor(palette).container
            assertEquals("$palette", true, brightness(card) < brightness(Tokens.Colors.Dark.onSurface) / 3)
        }
        assertEquals(Tokens.Colors.Night.surfaceVariant, toastColorsFor(Palette.Night).container)
        assertEquals(true, brightness(toastColorsFor(Palette.Night).container) < brightness(toastColorsFor(Palette.Dark).container))
    }

    @Test
    fun `the touch target is above the platform minimum`() {
        assertEquals(true, Tokens.Size.touchTarget >= 56)
    }
}
