package app.ezpztac.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Which palette to draw with. [Night] is the dimmed, red-shifted palette for a night cockpit (the plan's "Night
 * mode"); it is chosen by the crew, never by the system, because the right brightness depends on the aircraft.
 */
enum class ThemeMode { System, Dark, Light, Night }

/** The colours Material 3 has no slot for: a warning (separation, a stale pack) and a success. */
@Immutable
data class StatusColors(val warning: Color, val success: Color)

private val LocalStatusColors = staticCompositionLocalOf { statusColorsFor(dark = true, night = false) }

/** Read the status colours the way a Material colour is read: `EzpzTheme.status.warning`. */
object EzpzTheme {
    val status: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current
}

private fun color(value: Long) = Color(value)

/** The Material 3 colour scheme for a palette, from the tokens and nothing else. */
fun colorSchemeFor(mode: ThemeMode, systemDark: Boolean = true): ColorScheme = when (resolve(mode, systemDark)) {
    Palette.Dark -> darkColorScheme(
        background = color(Tokens.Colors.Dark.background),
        surface = color(Tokens.Colors.Dark.surface),
        surfaceVariant = color(Tokens.Colors.Dark.surfaceVariant),
        onBackground = color(Tokens.Colors.Dark.onBackground),
        onSurface = color(Tokens.Colors.Dark.onSurface),
        onSurfaceVariant = color(Tokens.Colors.Dark.onSurfaceVariant),
        primary = color(Tokens.Colors.Dark.primary),
        onPrimary = color(Tokens.Colors.Dark.onPrimary),
        secondary = color(Tokens.Colors.Dark.secondary),
        onSecondary = color(Tokens.Colors.Dark.onSecondary),
        outline = color(Tokens.Colors.Dark.outline),
        error = color(Tokens.Colors.Dark.error),
        onError = color(Tokens.Colors.Dark.onError),
        scrim = color(Tokens.Colors.Dark.scrim),
    )
    Palette.Light -> lightColorScheme(
        background = color(Tokens.Colors.Light.background),
        surface = color(Tokens.Colors.Light.surface),
        surfaceVariant = color(Tokens.Colors.Light.surfaceVariant),
        onBackground = color(Tokens.Colors.Light.onBackground),
        onSurface = color(Tokens.Colors.Light.onSurface),
        onSurfaceVariant = color(Tokens.Colors.Light.onSurfaceVariant),
        primary = color(Tokens.Colors.Light.primary),
        onPrimary = color(Tokens.Colors.Light.onPrimary),
        secondary = color(Tokens.Colors.Light.secondary),
        onSecondary = color(Tokens.Colors.Light.onSecondary),
        outline = color(Tokens.Colors.Light.outline),
        error = color(Tokens.Colors.Light.error),
        onError = color(Tokens.Colors.Light.onError),
        scrim = color(Tokens.Colors.Light.scrim),
    )
    Palette.Night -> darkColorScheme(
        background = color(Tokens.Colors.Night.background),
        surface = color(Tokens.Colors.Night.surface),
        surfaceVariant = color(Tokens.Colors.Night.surfaceVariant),
        onBackground = color(Tokens.Colors.Night.onBackground),
        onSurface = color(Tokens.Colors.Night.onSurface),
        onSurfaceVariant = color(Tokens.Colors.Night.onSurfaceVariant),
        primary = color(Tokens.Colors.Night.primary),
        onPrimary = color(Tokens.Colors.Night.onPrimary),
        secondary = color(Tokens.Colors.Night.secondary),
        onSecondary = color(Tokens.Colors.Night.onSecondary),
        outline = color(Tokens.Colors.Night.outline),
        error = color(Tokens.Colors.Night.error),
        onError = color(Tokens.Colors.Night.onError),
        scrim = color(Tokens.Colors.Night.scrim),
    )
}

internal enum class Palette { Dark, Light, Night }

/** The colours of a toast ([ToastHost]): its card, its words and close button, and its one action. */
internal data class ToastColors(val container: Long, val content: Long, val action: Long)

/**
 * A toast's colours for a palette. In light and dark alike it is the dark palette's raised card, which stands out from a light screen as
 * Material's inverse toast does, without a bright one ever lighting a dark cockpit; at night it is the night palette's own, red on near
 * black. The theme sets none of Material's inverse colours, which a toast would otherwise take: its stock lavender and purple, a near-white
 * flash on the night palette.
 */
internal fun toastColorsFor(palette: Palette): ToastColors = when (palette) {
    Palette.Night -> Tokens.Colors.Night.let { ToastColors(it.surfaceVariant, it.onSurface, it.primary) }
    Palette.Dark, Palette.Light -> Tokens.Colors.Dark.let { ToastColors(it.surfaceVariant, it.onSurface, it.primary) }
}

internal val LocalToastColors = staticCompositionLocalOf { toastColorsFor(Palette.Dark) }

/** The default is dark, as the plan says: a bright screen in a cockpit costs the crew their night vision. */
internal fun resolve(mode: ThemeMode, systemDark: Boolean): Palette = when (mode) {
    ThemeMode.System -> if (systemDark) Palette.Dark else Palette.Light
    ThemeMode.Dark -> Palette.Dark
    ThemeMode.Light -> Palette.Light
    ThemeMode.Night -> Palette.Night
}

internal fun statusColorsFor(dark: Boolean, night: Boolean): StatusColors = when {
    night -> StatusColors(
        warning = color(Tokens.Colors.Night.warning),
        success = color(Tokens.Colors.Night.success),
    )
    dark -> StatusColors(
        warning = color(Tokens.Colors.Dark.warning),
        success = color(Tokens.Colors.Dark.success),
    )
    else -> StatusColors(
        warning = color(Tokens.Colors.Light.warning),
        success = color(Tokens.Colors.Light.success),
    )
}

@Composable
fun EzpzTheme(
    mode: ThemeMode = ThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = resolve(mode, systemDark)
    CompositionLocalProvider(
        LocalStatusColors provides statusColorsFor(dark = palette != Palette.Light, night = palette == Palette.Night),
        LocalToastColors provides toastColorsFor(palette),
    ) {
        MaterialTheme(
            colorScheme = colorSchemeFor(mode, systemDark),
            typography = ezpzTypography(),
            content = content,
        )
    }
}
