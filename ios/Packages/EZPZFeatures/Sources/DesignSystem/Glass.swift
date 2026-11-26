import SwiftUI

/// The controls that float over the map, in Liquid Glass (docs/IOS_PLAN.md §7.1): the map shows through them, and the system lifts
/// them off whatever is behind. At night the glass is tinted with the night surface, so no part of it is lit by bright imagery under it.
extension View {
    public func mapGlass<S: Shape>(_ shape: S, interactive: Bool = true) -> some View {
        modifier(MapGlass(shape: shape, interactive: interactive))
    }

    /// A capsule of glass, the shape most map controls take.
    public func mapGlass(interactive: Bool = true) -> some View { mapGlass(Capsule(), interactive: interactive) }
}

private struct MapGlass<S: Shape>: ViewModifier {
    let shape: S
    let interactive: Bool
    @Environment(\.palette) private var palette

    func body(content: Content) -> some View {
        var glass = Glass.regular
        if palette.mode == .night { glass = glass.tint(palette.surface.opacity(0.85)) }
        if interactive { glass = glass.interactive() }
        return content.foregroundStyle(palette.onSurface).glassEffect(glass, in: shape)
    }
}

/// A round button over the map: an SF Symbol in a 48 pt circle of glass, named for what it does.
public struct MapButton: View {
    let symbol: String
    let label: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.palette) private var palette

    public init(_ label: String, symbol: String, selected: Bool = false, action: @escaping () -> Void) {
        self.symbol = symbol
        self.label = label
        self.selected = selected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(selected ? palette.accent : palette.onSurface)
                .frame(width: Metrics.mapControl, height: Metrics.mapControl)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .mapGlass(Circle())
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A toast: words in the palette's inverse roles (dark ones at night), at the top of whatever shows it.
public struct Toast: Equatable, Identifiable, Sendable {
    public let id = UUID()
    public let text: String
    public init(_ text: String) { self.text = text }
}

public struct ToastView: View {
    let toast: Toast
    @Environment(\.palette) private var palette

    public init(_ toast: Toast) { self.toast = toast }

    public var body: some View {
        Text(toast.text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(palette.inverseOnSurface)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(palette.inverseSurface, in: Capsule())
            .shadow(color: .black.opacity(0.3), radius: 8, y: 2)
            .accessibilityAddTraits(.isStaticText)
    }
}
