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
    fun `a toast is the design's inverse card by day, and never lights a cockpit at night`() {
        fun brightness(argb: Long) = Color(argb).let { it.red + it.green + it.blue }
        assertEquals(ToastColors(Tokens.Colors.Dark.inverseSurface, Tokens.Colors.Dark.inverseOnSurface, Tokens.Colors.Dark.inversePrimary), toastColorsFor(Palette.Dark))
        assertEquals(true, brightness(toastColorsFor(Palette.Night).container) < brightness(Tokens.Colors.Dark.onSurface) / 3)
        assertEquals(true, brightness(toastColorsFor(Palette.Night).content) < brightness(Tokens.Colors.Dark.onSurface))
    }

    @Test
    fun `night is never bright, whatever role a screen draws with`() {
        fun brightness(argb: Long) = Color(argb).let { it.red + it.green + it.blue }
        val night = rolesOf(Palette.Night)
        val brightest = listOf(
            night.background, night.surface, night.surfaceVariant, night.surfaceContainerLowest, night.surfaceContainerLow, night.surfaceContainer,
            night.surfaceContainerHigh, night.surfaceContainerHighest, night.primaryContainer, night.secondaryContainer, night.tertiaryContainer,
            night.errorContainer, night.inverseSurface, night.outlineVariant,
        ).maxOf(::brightness)
        assertEquals(true, brightest < brightness(Tokens.Colors.Dark.surfaceContainerHighest))   // every night surface darker than a day card
    }

    @Test
    fun `no colour role is left to Material's stock values`() {
        Palette.entries.forEach { palette ->
            val mode = when (palette) { Palette.Dark -> ThemeMode.Dark; Palette.Light -> ThemeMode.Light; Palette.Night -> ThemeMode.Night }
            val scheme = colorSchemeFor(mode)
            val r = rolesOf(palette)
            assertEquals("$palette", Color(r.surfaceContainerLow), scheme.surfaceContainerLow)
            assertEquals("$palette", Color(r.secondaryContainer), scheme.secondaryContainer)
            assertEquals("$palette", Color(r.tertiary), scheme.tertiary)
            assertEquals("$palette", Color(r.inverseSurface), scheme.inverseSurface)
            assertEquals("$palette", Color(r.outlineVariant), scheme.outlineVariant)
            assertEquals("$palette", Color.Transparent, scheme.surfaceTint)
        }
    }

    @Test
    fun `the touch target is above the platform minimum`() {
        assertEquals(true, Tokens.Size.touchTarget >= 56)
    }
}
