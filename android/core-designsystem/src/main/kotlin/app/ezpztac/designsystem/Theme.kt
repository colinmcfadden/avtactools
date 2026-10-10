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

/**
 * The colours Material 3 has no slot for: a warning (separation, a change waiting to sync), a success (synced, analysed), and the accent
 * the redesign draws text buttons and icons in on its dark surfaces.
 */
@Immutable
data class StatusColors(val warning: Color, val success: Color, val accent: Color)

private val LocalStatusColors = staticCompositionLocalOf { statusColorsFor(Palette.Dark) }

/** Read the status colours the way a Material colour is read: `EzpzTheme.status.warning`. */
object EzpzTheme {
    val status: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current
}

/** One palette's colour roles, as the tokens give them (0xAARRGGBB). The three palettes are the same names with their own values. */
internal class Roles(
    val background: Long, val surface: Long, val surfaceVariant: Long, val onBackground: Long, val onSurface: Long, val onSurfaceVariant: Long,
    val primary: Long, val onPrimary: Long, val secondary: Long, val onSecondary: Long, val outline: Long, val error: Long, val onError: Long,
    val warning: Long, val success: Long, val scrim: Long,
    val surfaceContainerLowest: Long, val surfaceContainerLow: Long, val surfaceContainer: Long, val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long, val outlineVariant: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondaryContainer: Long, val onSecondaryContainer: Long, val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long,
    val onTertiaryContainer: Long, val errorContainer: Long, val onErrorContainer: Long, val inverseSurface: Long, val inverseOnSurface: Long,
    val inversePrimary: Long, val accent: Long,
)

internal enum class Palette { Dark, Light, Night }

internal fun rolesOf(palette: Palette): Roles = when (palette) {
    Palette.Dark -> Tokens.Colors.Dark.run {
        Roles(
            background, surface, surfaceVariant, onBackground, onSurface, onSurfaceVariant, primary, onPrimary, secondary, onSecondary, outline,
            error, onError, warning, success, scrim, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh,
            surfaceContainerHighest, outlineVariant, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer, tertiary,
            onTertiary, tertiaryContainer, onTertiaryContainer, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface,
            inversePrimary, accent,
        )
    }
    Palette.Light -> Tokens.Colors.Light.run {
        Roles(
            background, surface, surfaceVariant, onBackground, onSurface, onSurfaceVariant, primary, onPrimary, secondary, onSecondary, outline,
            error, onError, warning, success, scrim, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh,
            surfaceContainerHighest, outlineVariant, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer, tertiary,
            onTertiary, tertiaryContainer, onTertiaryContainer, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface,
            inversePrimary, accent,
        )
    }
    Palette.Night -> Tokens.Colors.Night.run {
        Roles(
            background, surface, surfaceVariant, onBackground, onSurface, onSurfaceVariant, primary, onPrimary, secondary, onSecondary, outline,
            error, onError, warning, success, scrim, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh,
            surfaceContainerHighest, outlineVariant, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer, tertiary,
            onTertiary, tertiaryContainer, onTertiaryContainer, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface,
            inversePrimary, accent,
        )
    }
}

private fun color(value: Long) = Color(value)

/**
 * The Material 3 colour scheme for a palette, from the tokens and nothing else: every role is set, so no component falls back to
 * Material's stock lavender (a near-white flash on the night palette). `surfaceTint` is transparent: the redesign separates surfaces by
 * their container colours, never by tinting them with the primary.
 */
fun colorSchemeFor(mode: ThemeMode, systemDark: Boolean = true): ColorScheme {
    val palette = resolve(mode, systemDark)
    val r = rolesOf(palette)
    return if (palette == Palette.Light) {
        lightColorScheme(
            primary = color(r.primary), onPrimary = color(r.onPrimary), primaryContainer = color(r.primaryContainer),
            onPrimaryContainer = color(r.onPrimaryContainer), inversePrimary = color(r.inversePrimary), secondary = color(r.secondary),
            onSecondary = color(r.onSecondary), secondaryContainer = color(r.secondaryContainer), onSecondaryContainer = color(r.onSecondaryContainer),
            tertiary = color(r.tertiary), onTertiary = color(r.onTertiary), tertiaryContainer = color(r.tertiaryContainer),
            onTertiaryContainer = color(r.onTertiaryContainer), background = color(r.background), onBackground = color(r.onBackground),
            surface = color(r.surface), onSurface = color(r.onSurface), surfaceVariant = color(r.surfaceVariant),
            onSurfaceVariant = color(r.onSurfaceVariant), surfaceTint = Color.Transparent, inverseSurface = color(r.inverseSurface),
            inverseOnSurface = color(r.inverseOnSurface), error = color(r.error), onError = color(r.onError), errorContainer = color(r.errorContainer),
            onErrorContainer = color(r.onErrorContainer), outline = color(r.outline), outlineVariant = color(r.outlineVariant), scrim = color(r.scrim),
            surfaceBright = color(r.surfaceContainerLowest), surfaceContainer = color(r.surfaceContainer),
            surfaceContainerHigh = color(r.surfaceContainerHigh), surfaceContainerHighest = color(r.surfaceContainerHighest),
            surfaceContainerLow = color(r.surfaceContainerLow), surfaceContainerLowest = color(r.surfaceContainerLowest),
            surfaceDim = color(r.surfaceContainerHighest),
        )
    } else {
        darkColorScheme(
            primary = color(r.primary), onPrimary = color(r.onPrimary), primaryContainer = color(r.primaryContainer),
            onPrimaryContainer = color(r.onPrimaryContainer), inversePrimary = color(r.inversePrimary), secondary = color(r.secondary),
            onSecondary = color(r.onSecondary), secondaryContainer = color(r.secondaryContainer), onSecondaryContainer = color(r.onSecondaryContainer),
            tertiary = color(r.tertiary), onTertiary = color(r.onTertiary), tertiaryContainer = color(r.tertiaryContainer),
            onTertiaryContainer = color(r.onTertiaryContainer), background = color(r.background), onBackground = color(r.onBackground),
            surface = color(r.surface), onSurface = color(r.onSurface), surfaceVariant = color(r.surfaceVariant),
            onSurfaceVariant = color(r.onSurfaceVariant), surfaceTint = Color.Transparent, inverseSurface = color(r.inverseSurface),
            inverseOnSurface = color(r.inverseOnSurface), error = color(r.error), onError = color(r.onError), errorContainer = color(r.errorContainer),
            onErrorContainer = color(r.onErrorContainer), outline = color(r.outline), outlineVariant = color(r.outlineVariant), scrim = color(r.scrim),
            surfaceBright = color(r.surfaceContainerHighest), surfaceContainer = color(r.surfaceContainer),
            surfaceContainerHigh = color(r.surfaceContainerHigh), surfaceContainerHighest = color(r.surfaceContainerHighest),
            surfaceContainerLow = color(r.surfaceContainerLow), surfaceContainerLowest = color(r.surfaceContainerLowest),
            surfaceDim = color(r.surfaceContainerLowest),
        )
    }
}

/** The colours of a toast ([ToastHost]): its card, its words and close button, and its one action. */
internal data class ToastColors(val container: Long, val content: Long, val action: Long)

/**
 * A toast's colours for a palette: the redesign's snackbar, Material's inverse roles (a light card on the dark palette, a dark one on the
 * light). At night the inverse roles are themselves dark, red on near black, so a toast never lights a night cockpit.
 */
internal fun toastColorsFor(palette: Palette): ToastColors = rolesOf(palette).let { ToastColors(it.inverseSurface, it.inverseOnSurface, it.inversePrimary) }

internal val LocalToastColors = staticCompositionLocalOf { toastColorsFor(Palette.Dark) }

/** The default is dark, as the plan says: a bright screen in a cockpit costs the crew their night vision. */
internal fun resolve(mode: ThemeMode, systemDark: Boolean): Palette = when (mode) {
    ThemeMode.System -> if (systemDark) Palette.Dark else Palette.Light
    ThemeMode.Dark -> Palette.Dark
    ThemeMode.Light -> Palette.Light
    ThemeMode.Night -> Palette.Night
}

internal fun statusColorsFor(palette: Palette): StatusColors = rolesOf(palette).let {
    StatusColors(warning = color(it.warning), success = color(it.success), accent = color(it.accent))
}

@Composable
fun EzpzTheme(
    mode: ThemeMode = ThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = resolve(mode, systemDark)
    CompositionLocalProvider(
        LocalStatusColors provides statusColorsFor(palette),
        LocalToastColors provides toastColorsFor(palette),
    ) {
        MaterialTheme(
            colorScheme = colorSchemeFor(mode, systemDark),
            typography = ezpzTypography(),
            shapes = ezpzShapes(),
            content = content,
        )
    }
}
