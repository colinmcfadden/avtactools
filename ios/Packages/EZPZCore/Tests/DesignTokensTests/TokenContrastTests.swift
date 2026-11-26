import Foundation
import Testing

@testable import DesignTokens

/// A screen on a cockpit display in sunlight or at night has to be readable, and nothing in an agent session can look at it, so the
/// palettes are held to WCAG contrast ratios: 4.5:1 for text (AA), 3:1 for large text, icons and borders. Android's
/// TokenContrastTest, pair for pair, over the same generated numbers.
struct TokenContrastTests {
    typealias Palette = Tokens.Colors.Palette

    /// By name, so a test's arguments print as a word rather than a whole palette.
    static let palettes = ["dark", "light", "night"]

    static func palette(_ name: String) -> Palette {
        switch name {
        case "dark": Tokens.Colors.dark
        case "light": Tokens.Colors.light
        default: Tokens.Colors.night
        }
    }

    /// The tint of a state chip and of a notice (Android's CHIP_TINT and BANNER_TINT), the same on iOS.
    static let chipTint = 0.15
    static let bannerTint = 0.10

    static func channel(_ value: UInt32, _ shift: UInt32) -> Double {
        let c = Double((value >> shift) & 0xFF) / 255
        return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }

    static func luminance(_ argb: UInt32) -> Double {
        0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)
    }

    static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// `foreground` at `alpha` over `background`, as the screen draws it: each channel blended and kept to 8 bits.
    static func over(_ foreground: UInt32, _ alpha: Double, _ background: UInt32) -> UInt32 {
        func mix(_ shift: UInt32) -> UInt32 {
            let f = Double((foreground >> shift) & 0xFF)
            let b = Double((background >> shift) & 0xFF)
            return UInt32((f * alpha + b * (1 - alpha)).rounded(.toNearestOrAwayFromZero))
        }
        return 0xFF00_0000 | mix(16) << 16 | mix(8) << 8 | mix(0)
    }

    func check(_ name: String, _ what: String, _ foreground: UInt32, _ background: UInt32, _ minimum: Double) {
        let ratio = Self.contrast(foreground, background)
        #expect(ratio >= minimum, "\(name): \(what) is \(ratio):1, needs \(minimum):1")
    }

    @Test(arguments: palettes)
    func textOnItsBackgroundMeetsAA(_ name: String) {
        let r = Self.palette(name)
        check(name, "onBackground on background", r.onBackground, r.background, 4.5)
        check(name, "onSurface on surface", r.onSurface, r.surface, 4.5)
        check(name, "onSurface on surfaceVariant", r.onSurface, r.surfaceVariant, 4.5)
        check(name, "onPrimary on primary", r.onPrimary, r.primary, 4.5)
        check(name, "onSecondary on secondary", r.onSecondary, r.secondary, 4.5)
        check(name, "onError on error", r.onError, r.error, 4.5)
    }

    @Test(arguments: palettes)
    func secondaryTextIsStillReadable(_ name: String) {
        let r = Self.palette(name)
        check(name, "onSurfaceVariant on surface", r.onSurfaceVariant, r.surface, 4.5)
        check(name, "onSurfaceVariant on background", r.onSurfaceVariant, r.background, 4.5)
    }

    /// The redesign draws on its surface containers, and every kind of text it puts there is small text.
    @Test(arguments: palettes)
    func theTextOnEachSurfaceContainerMeetsAA(_ name: String) {
        let r = Self.palette(name)
        let containers = [
            ("lowest", r.surfaceContainerLowest), ("low", r.surfaceContainerLow), ("container", r.surfaceContainer),
            ("high", r.surfaceContainerHigh), ("highest", r.surfaceContainerHighest),
        ]
        for (container, color) in containers {
            check(name, "onSurface on \(container)", r.onSurface, color, 4.5)
            check(name, "onSurfaceVariant on \(container)", r.onSurfaceVariant, color, 4.5)
            check(name, "accent on \(container)", r.accent, color, 4.5)
            check(name, "success (Synced) on \(container)", r.success, color, 4.5)
            check(name, "warning (Waiting to sync) on \(container)", r.warning, color, 4.5)
        }
    }

    /// The state chips and notices on every surface container, in the palettes the app draws: dark, and night. Light is not drawn; its
    /// status colours would need darkening by about a quarter for its chips to pass.
    @Test(arguments: ["dark", "night"])
    func aStateChipsWordsAndANoticesWordsMeetAAOnTheirTint(_ name: String) {
        let r = Self.palette(name)
        let tones = [("success", r.success), ("warning", r.warning), ("error", r.error), ("accent", r.accent)]
        let containers = [
            ("surface", r.surface), ("low", r.surfaceContainerLow), ("container", r.surfaceContainer), ("high", r.surfaceContainerHigh),
            ("highest", r.surfaceContainerHighest),
        ]
        for (container, background) in containers {
            for (tone, color) in tones {
                check(name, "a \(tone) chip on \(container)", color, Self.over(color, Self.chipTint, background), 4.5)
                check(
                    name, "a notice's words on a \(tone) notice on \(container)", r.onSurface,
                    Self.over(color, Self.bannerTint, background), 4.5)
            }
        }
    }

    @Test(arguments: palettes)
    func textOnEachTonalContainerMeetsAA(_ name: String) {
        let r = Self.palette(name)
        check(name, "onPrimaryContainer on primaryContainer", r.onPrimaryContainer, r.primaryContainer, 4.5)
        check(name, "onSecondaryContainer on secondaryContainer", r.onSecondaryContainer, r.secondaryContainer, 4.5)
        check(name, "onTertiary on tertiary", r.onTertiary, r.tertiary, 4.5)
        check(name, "onTertiaryContainer on tertiaryContainer", r.onTertiaryContainer, r.tertiaryContainer, 4.5)
        check(name, "onErrorContainer on errorContainer", r.onErrorContainer, r.errorContainer, 4.5)
    }

    /// A toast is drawn in the palette's inverse roles, as on Android.
    @Test(arguments: palettes)
    func aToastsWordsAndActionAreReadable(_ name: String) {
        let r = Self.palette(name)
        check(name, "toast words", r.inverseOnSurface, r.inverseSurface, 4.5)
        check(name, "toast action", r.inversePrimary, r.inverseSurface, 4.5)
    }

    @Test(arguments: palettes)
    func statusColoursAccentsAndBordersStandOut(_ name: String) {
        let r = Self.palette(name)
        check(name, "warning on background", r.warning, r.background, 3)
        check(name, "success on background", r.success, r.background, 3)
        check(name, "error on background", r.error, r.background, 3)
        check(name, "primary on background", r.primary, r.background, 3)
        check(name, "warning on surface", r.warning, r.surface, 3)
        check(name, "outline on surface", r.outline, r.surface, 3)
        check(name, "outline on the sheet", r.outline, r.surfaceContainerLow, 3)
        check(name, "a pack's colour on the sheet", r.tertiary, r.surfaceContainerLow, 3)
    }

    /// The sum of the channels, 0 to 3, as Android's ThemeTest compares brightness.
    static func brightness(_ argb: UInt32) -> Double {
        Double((argb >> 16) & 0xFF) / 255 + Double((argb >> 8) & 0xFF) / 255 + Double(argb & 0xFF) / 255
    }

    @Test func nightIsDarkerThanDark() {
        #expect(Self.brightness(Tokens.Colors.night.background) < Self.brightness(Tokens.Colors.dark.background))
        #expect(Tokens.Colors.night.primary != Tokens.Colors.dark.primary)
    }

    /// A toast is the inverse card by day, and never lights a cockpit at night.
    @Test func aToastNeverLightsACockpitAtNight() {
        let (night, dark) = (Tokens.Colors.night, Tokens.Colors.dark)
        #expect(Self.brightness(night.inverseSurface) < Self.brightness(dark.onSurface) / 3)
        #expect(Self.brightness(night.inverseOnSurface) < Self.brightness(dark.onSurface))
    }

    /// Whatever role a screen draws with, every night surface is darker than a day card.
    @Test func nightIsNeverBright() {
        let n = Tokens.Colors.night
        let surfaces = [
            n.background, n.surface, n.surfaceVariant, n.surfaceContainerLowest, n.surfaceContainerLow, n.surfaceContainer,
            n.surfaceContainerHigh, n.surfaceContainerHighest, n.primaryContainer, n.secondaryContainer, n.tertiaryContainer,
            n.errorContainer, n.inverseSurface, n.outlineVariant,
        ]
        #expect(surfaces.map(Self.brightness).max()! < Self.brightness(Tokens.Colors.dark.surfaceContainerHighest))
    }

    /// Above the platform minimum (44 pt on iOS, 48 dp on Android), for gloves and vibration.
    @Test func theTouchTargetIsAboveThePlatformMinimum() {
        #expect(Tokens.Size.touchTarget >= 56)
    }
}
