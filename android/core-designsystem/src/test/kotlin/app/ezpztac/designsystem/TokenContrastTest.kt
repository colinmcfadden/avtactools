package app.ezpztac.designsystem

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen on a cockpit display in sunlight or at night has to be readable, and nothing in an agent session can
 * look at it, so the palettes are held to WCAG contrast ratios: 4.5:1 for text (AA), 3:1 for large text, icons and borders.
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

    private fun check(palette: Palette, what: String, foreground: Long, background: Long, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("${palette.name}: $what is ${"%.2f".format(ratio)}:1, needs $minimum:1", ratio >= minimum)
    }

    private fun eachPalette(block: (Palette, Roles) -> Unit) = Palette.entries.forEach { block(it, rolesOf(it)) }

    @Test
    fun `text on its background meets AA`() = eachPalette { p, r ->
        check(p, "onBackground on background", r.onBackground, r.background, 4.5)
        check(p, "onSurface on surface", r.onSurface, r.surface, 4.5)
        check(p, "onSurface on surfaceVariant", r.onSurface, r.surfaceVariant, 4.5)
        check(p, "onPrimary on primary", r.onPrimary, r.primary, 4.5)
        check(p, "onSecondary on secondary", r.onSecondary, r.secondary, 4.5)
        check(p, "onError on error", r.onError, r.error, 4.5)
    }

    @Test
    fun `secondary text is still readable`() = eachPalette { p, r ->
        // Captions and hints: AA for small text, over both the screen and a card.
        check(p, "onSurfaceVariant on surface", r.onSurfaceVariant, r.surface, 4.5)
        check(p, "onSurfaceVariant on background", r.onSurfaceVariant, r.background, 4.5)
    }

    // The redesign draws on its surface containers: the sheet on the low one, cards on the high one, menus and dialogs on the high one too.
    // Every kind of text it puts there is small text.
    @Test
    fun `the text the redesign puts on each surface container meets AA`() = eachPalette { p, r ->
        listOf(
            "lowest" to r.surfaceContainerLowest, "low" to r.surfaceContainerLow, "container" to r.surfaceContainer,
            "high" to r.surfaceContainerHigh, "highest" to r.surfaceContainerHighest,
        ).forEach { (name, container) ->
            check(p, "onSurface on $name", r.onSurface, container, 4.5)
            check(p, "onSurfaceVariant on $name", r.onSurfaceVariant, container, 4.5)
            check(p, "accent on $name", r.accent, container, 4.5)
            check(p, "success (Synced) on $name", r.success, container, 4.5)
            check(p, "warning (Waiting to sync) on $name", r.warning, container, 4.5)
        }
    }

    @Test
    fun `text on each tonal container meets AA`() = eachPalette { p, r ->
        check(p, "onPrimaryContainer on primaryContainer", r.onPrimaryContainer, r.primaryContainer, 4.5)
        check(p, "onSecondaryContainer on secondaryContainer", r.onSecondaryContainer, r.secondaryContainer, 4.5)
        check(p, "onTertiary on tertiary", r.onTertiary, r.tertiary, 4.5)
        check(p, "onTertiaryContainer on tertiaryContainer", r.onTertiaryContainer, r.tertiaryContainer, 4.5)
        check(p, "onErrorContainer on errorContainer", r.onErrorContainer, r.errorContainer, 4.5)
    }

    @Test
    fun `a toast's words, close button and action are readable on its card, in every palette`() = Palette.entries.forEach { palette ->
        val toast = toastColorsFor(palette)
        check(palette, "toast words", toast.content, toast.container, 4.5)
        check(palette, "toast action", toast.action, toast.container, 4.5)          // 14 sp bold is not large text: AA, not 3:1
    }

    @Test
    fun `status colours, accents and borders stand out from the screen`() = eachPalette { p, r ->
        // Icons, large text and the edges of controls: 3:1.
        check(p, "warning on background", r.warning, r.background, 3.0)
        check(p, "success on background", r.success, r.background, 3.0)
        check(p, "error on background", r.error, r.background, 3.0)
        check(p, "primary on background", r.primary, r.background, 3.0)
        check(p, "warning on surface", r.warning, r.surface, 3.0)
        check(p, "outline on surface", r.outline, r.surface, 3.0)
        check(p, "outline on the sheet", r.outline, r.surfaceContainerLow, 3.0)
        check(p, "a pack's colour on the sheet", r.tertiary, r.surfaceContainerLow, 3.0)
    }
}
