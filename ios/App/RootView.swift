import DesignSystem
import FeatureMap
import SwiftUI

/// The map at the root, and over it the frame the window has room for: the iPad's at least 840 pt wide and 480 tall (Android's
/// expanded width, and not a phone on its side), else the iPhone's. One map view under both, so turning a window across the line
/// (Split View, Stage Manager, a rotation) moves nothing and builds no second map (AGENTS.md §17, *The tablet*).
struct RootView: View {
    let map: MapStore
    let display: DisplaySettings
    @State private var frame = FrameState()
    @Environment(\.palette) private var palette

    var body: some View {
        GeometryReader { geo in
            let inset = geo.safeAreaInsets
            let full = CGSize(
                width: geo.size.width + inset.leading + inset.trailing, height: geo.size.height + inset.top + inset.bottom)
            let pad = full.width >= 840 && full.height >= 480
            // Everything is laid out in the window's own coordinates, the safe areas included: the map's points, its labels, the
            // crosshair and the frame's boxes are then one space. Each layer is the window's size, so none can grow the stack and
            // move the map off the window (it once stood 62 pt taller, its middle 31 pt below the crosshair).
            ZStack(alignment: .topLeading) {
                MapLibreMap(store: map) { _ in
                    // A tap on the map that is the frame's hides it, and the next shows it again (the owner's, 2026-10-13).
                    // It also ends typing in the search, whose keyboard would otherwise stay up over the map (the iPad's search is
                    // always open; the phone's folds as the frame hides).
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    if map.pressed != nil { map.pressed = nil } else if !pad { frame.toggleHidden() }
                }
                .frame(width: full.width, height: full.height)
                // The dim goes over the grid's labels too (Android's order): bright pills would light a cockpit at night.
                GridLabels(store: map).frame(width: full.width, height: full.height)
                NightDim().frame(width: full.width, height: full.height)
                if pad {
                    PadFrame(map: map, display: display, frame: frame, window: full, safe: inset)
                } else {
                    PhoneFrame(map: map, display: display, frame: frame, window: full, safe: inset)
                }
            }
            .frame(width: full.width, height: full.height)
            .background(palette.background)
            .ignoresSafeArea()
        }
        // The keyboard is not a safe area here: counted in the insets it moved the map's padding, the crosshair and the iPad's
        // panes each time it came up. It covers the map as the sheet at full height does.
        .ignoresSafeArea(.keyboard)
    }
}

/// What the frame shows, kept across a change of frame: the panel chosen, whether the phone's frame is hidden.
@MainActor @Observable
final class FrameState {
    var panel: Panel = .lz
    var hidden = false
    var searchOpen = false
    var searchText = ""
    var inspector = false
    var sidebar = true

    func toggleHidden() {
        hidden.toggle()
        if hidden { searchOpen = false }
    }

    func choose(_ panel: Panel) {
        self.panel = panel
        hidden = false
    }
}

/// The panels, in the bar's order (Pack comes first in a pack, I5).
enum Panel: String, CaseIterable, Identifiable {
    case lz, routes, threats, imports

    var id: String { rawValue }

    var title: String {
        switch self {
        case .lz: "LZ/PZ"
        case .routes: "Routes"
        case .threats: "Threats"
        case .imports: "Imports"
        }
    }

    var symbol: String {
        switch self {
        case .lz: "scope"
        case .routes: "point.topleft.down.to.point.bottomright.curvepath"
        case .threats: "exclamationmark.triangle"
        case .imports: "tray.and.arrow.down"
        }
    }

    /// What the panel will hold, said while it holds nothing yet: the steps that bring each are in docs/IOS_PLAN.md §11.
    var later: String {
        switch self {
        case .lz: "LZ/PZs, their analysis and planning graphics arrive in the next build (I4)."
        case .routes: "Sketching, planning and AMPS export arrive with routes (I6)."
        case .threats: "Threats, their rings and terrain masks arrive with threats (I7)."
        case .imports: "AMPS missions, local points and threat files arrive with imports (I6)."
        }
    }
}

/// A panel's contents. Empty in I3, saying what comes; the panels themselves are I4 onwards.
struct PanelContent: View {
    let panel: Panel
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(panel.title, systemImage: panel.symbol).font(.title3.weight(.semibold))
            Text(panel.later).font(.body).foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }
}

/// The account's menu: who is signed in (I2's sign-in), night mode, the version.
struct AccountMenu: View {
    let display: DisplaySettings

    var body: some View {
        Menu {
            Section("Not signed in") {
                Toggle("Night mode", systemImage: "moon", isOn: Binding(get: { display.night }, set: { display.night = $0 }))
            }
            Section { Text("EZ/PZ \(AppInfo.version) (\(AppInfo.build))") }
        } label: {
            Image(systemName: "person.crop.circle")
                .font(.system(size: 20, weight: .semibold))
                .frame(width: Metrics.mapControl, height: Metrics.mapControl)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .mapGlass(Circle())
        .accessibilityLabel("Account")
    }
}

/// The workspace: where edits go, the Library (Personal) or a Mission Pack (I5's switcher).
struct WorkspaceCapsule: View {
    var compact = false
    @Environment(\.palette) private var palette

    var body: some View {
        Menu {
            Section("Workspace") {
                Label("Personal", systemImage: "checkmark")
            }
            // A menu drops a section with nothing in it, so the note is a row of its own.
            Section { Text("Mission Packs arrive with I5.") }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "person.fill").font(.system(size: 13, weight: .semibold))
                Text("Personal").font(.subheadline.weight(.semibold)).lineLimit(1)
                if !compact { Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold)) }
            }
            .padding(.horizontal, 14)
            .frame(height: 40)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .mapGlass()
        .accessibilityLabel("Workspace: Personal")
    }
}

/// Import and the Library, each a button on the row (their screens are I6 and I4).
struct RowButton: View {
    let title: String
    let symbol: String
    let showTitle: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 15, weight: .semibold))
                if showTitle { Text(title).font(.subheadline.weight(.semibold)).lineLimit(1) }
            }
            .padding(.horizontal, showTitle ? 14 : 0)
            .frame(minWidth: 40, minHeight: 40)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .mapGlass()
        .frame(minHeight: Metrics.mapControl)
        .accessibilityLabel(title)
    }
}
