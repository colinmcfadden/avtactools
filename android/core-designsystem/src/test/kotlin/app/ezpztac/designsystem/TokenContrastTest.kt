package app.ezpztac.designsystem

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen on a cockpit display in sunlight or at night has to be readable, and nothing in an agent session can
 * look at it, so the palettes are held to WCAG contrast ratios: 4.5:1 for text (AA), 3:1 for large text and icons.
 */
class TokenContrastTest {
    private fun channel(value: Long, shift: Int): Double {
        val c = ((value shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long) = 0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la > lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private class Theme(
        val name: String,
        val background: Long, val surface: Long, val surfaceVariant: Long,
        val onBackground: Long, val onSurface: Long, val onSurfaceVariant: Long,
        val primary: Long, val onPrimary: Long, val secondary: Long, val onSecondary: Long,
        val error: Long, val onError: Long, val warning: Long, val success: Long,
    )

    private val themes = listOf(
        Tokens.Colors.Dark.let {
            Theme("dark", it.background, it.surface, it.surfaceVariant, it.onBackground, it.onSurface, it.onSurfaceVariant,
                it.primary, it.onPrimary, it.secondary, it.onSecondary, it.error, it.onError, it.warning, it.success)
        },
        Tokens.Colors.Light.let {
            Theme("light", it.background, it.surface, it.surfaceVariant, it.onBackground, it.onSurface, it.onSurfaceVariant,
                it.primary, it.onPrimary, it.secondary, it.onSecondary, it.error, it.onError, it.warning, it.success)
        },
        Tokens.Colors.Night.let {
            Theme("night", it.background, it.surface, it.surfaceVariant, it.onBackground, it.onSurface, it.onSurfaceVariant,
                it.primary, it.onPrimary, it.secondary, it.onSecondary, it.error, it.onError, it.warning, it.success)
        },
    )

    private fun check(theme: Theme, what: String, foreground: Long, background: Long, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("${theme.name}: $what is ${"%.2f".format(ratio)}:1, needs $minimum:1", ratio >= minimum)
    }

    @Test
    fun `text on its background meets AA`() = themes.forEach { t ->
        check(t, "onBackground on background", t.onBackground, t.background, 4.5)
        check(t, "onSurface on surface", t.onSurface, t.surface, 4.5)
        check(t, "onSurface on surfaceVariant", t.onSurface, t.surfaceVariant, 4.5)
        check(t, "onPrimary on primary", t.onPrimary, t.primary, 4.5)
        check(t, "onSecondary on secondary", t.onSecondary, t.secondary, 4.5)
        check(t, "onError on error", t.onError, t.error, 4.5)
    }

    @Test
    fun `secondary text is still readable`() = themes.forEach { t ->
        // Captions and hints: AA for small text, over both the screen and a card.
        check(t, "onSurfaceVariant on surface", t.onSurfaceVariant, t.surface, 4.5)
        check(t, "onSurfaceVariant on background", t.onSurfaceVariant, t.background, 4.5)
    }

    @Test
    fun `a toast's words, close button and action are readable on its card, in every palette`() = Palette.entries.forEach { palette ->
        val toast = toastColorsFor(palette)
        fun readable(what: String, foreground: Long) {
            val ratio = contrast(foreground, toast.container)
            assertTrue("${palette.name} toast: $what is ${"%.2f".format(ratio)}:1, needs 4.5:1", ratio >= 4.5)
        }
        readable("words", toast.content)
        readable("action", toast.action)                                                 // 14 sp bold is not large text: AA, not 3:1
    }

    @Test
    fun `status colours and accents stand out from the screen`() = themes.forEach { t ->
        // Icons and large text: 3:1.
        check(t, "warning on background", t.warning, t.background, 3.0)
        check(t, "success on background", t.success, t.background, 3.0)
        check(t, "error on background", t.error, t.background, 3.0)
        check(t, "primary on background", t.primary, t.background, 3.0)
        check(t, "warning on surface", t.warning, t.surface, 3.0)
    }
}
