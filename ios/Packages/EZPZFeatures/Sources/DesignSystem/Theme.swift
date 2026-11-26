import DesignTokens
import SwiftUI

/// Which of the redesign's palettes is drawn. Dark by default; night is the red-shifted palette for NVGs and a dark cockpit, a device
/// choice (Android's `DisplaySettings`), never saved with a plan. Light is generated and tested but not offered yet, as on Android.
public enum ThemeMode: String, CaseIterable, Sendable {
    case dark, light, night

    public var palette: Tokens.Colors.Palette {
        switch self {
        case .dark: Tokens.Colors.dark
        case .light: Tokens.Colors.light
        case .night: Tokens.Colors.night
        }
    }

    /// What the system's own controls and sheets are drawn in. Night is dark, so a system sheet never flashes white in a cockpit.
    public var colorScheme: ColorScheme { self == .light ? .light : .dark }
}

/// The palette's roles as SwiftUI colours, read by every screen from the environment (`@Environment(\.palette)`).
public struct Palette: Sendable {
    public let mode: ThemeMode
    private let p: Tokens.Colors.Palette

    public init(_ mode: ThemeMode) {
        self.mode = mode
        p = mode.palette
    }

    public var background: Color { Color(argb: p.background) }
    public var surface: Color { Color(argb: p.surface) }
    public var surfaceContainerLow: Color { Color(argb: p.surfaceContainerLow) }
    public var surfaceContainer: Color { Color(argb: p.surfaceContainer) }
    public var surfaceContainerHigh: Color { Color(argb: p.surfaceContainerHigh) }
    public var onSurface: Color { Color(argb: p.onSurface) }
    public var onSurfaceVariant: Color { Color(argb: p.onSurfaceVariant) }
    public var primary: Color { Color(argb: p.primary) }
    public var onPrimary: Color { Color(argb: p.onPrimary) }
    public var accent: Color { Color(argb: p.accent) }
    public var outline: Color { Color(argb: p.outline) }
    public var outlineVariant: Color { Color(argb: p.outlineVariant) }
    public var success: Color { Color(argb: p.success) }
    public var warning: Color { Color(argb: p.warning) }
    public var error: Color { Color(argb: p.error) }
    public var tertiary: Color { Color(argb: p.tertiary) }
    public var inverseSurface: Color { Color(argb: p.inverseSurface) }
    public var inverseOnSurface: Color { Color(argb: p.inverseOnSurface) }
    public var inversePrimary: Color { Color(argb: p.inversePrimary) }
}

extension Color {
    /// A token's `0xAARRGGBB`, in sRGB as the tokens are written.
    public init(argb: UInt32) {
        self.init(
            .sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255, blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

extension EnvironmentValues {
    @Entry public var palette = Palette(.dark)
}

extension View {
    /// Draws what is under it in `mode`'s palette: its roles in the environment, the system's controls in its colour scheme, and its
    /// accent as the tint.
    public func theme(_ mode: ThemeMode) -> some View {
        let palette = Palette(mode)
        return environment(\.palette, palette).preferredColorScheme(mode.colorScheme).tint(palette.accent)
    }
}

extension View {
    /// The app's theme: night when the person asked for it (dark for the system, so a system sheet never flashes white), else the
    /// system's own appearance, dark or light (Android follows the system the same way).
    public func appTheme(night: Bool) -> some View { modifier(AppTheme(night: night)) }
}

private struct AppTheme: ViewModifier {
    let night: Bool
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        // Night alone is forced on the window; otherwise nothing is, so the scheme read here is the system's.
        let palette = Palette(night ? .night : scheme == .light ? .light : .dark)
        content.environment(\.palette, palette).tint(palette.accent).preferredColorScheme(night ? .dark : nil)
    }
}

/// The token sizes as points, so a screen never retypes a number the tokens hold.
public enum Metrics {
    public static let touchTarget = CGFloat(Tokens.Size.touchTarget)
    public static let spacingXS = CGFloat(Tokens.Spacing.xs)
    public static let spacingSM = CGFloat(Tokens.Spacing.sm)
    public static let spacingMD = CGFloat(Tokens.Spacing.md)
    public static let spacingLG = CGFloat(Tokens.Spacing.lg)
    public static let radiusMD = CGFloat(Tokens.Radius.md)
    public static let radiusLG = CGFloat(Tokens.Radius.lg)
    /// The least a control over the map is, for gloves: the platform's 44 pt is not enough (docs/IOS_PLAN.md §7.3).
    public static let mapControl: CGFloat = 48
}
