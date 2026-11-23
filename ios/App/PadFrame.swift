import DesignSystem
import FeatureMap
import SwiftUI

/// The iPad's frame (docs/IOS_PLAN.md §7.2): the map full bleed, a floating glass sidebar (340 pt) with the workspace, the panel picker
/// and the panel; a trailing inspector (320 pt) for the open LZ/PZ's plan or a route's nav log; and a toolbar over the map with the
/// search, Import, the Library and the account. Drawn over the one map view at the root rather than as a `NavigationSplitView`, whose
/// detail column would own a map of its own: crossing 840 pt must move nothing.
struct PadFrame: View {
    let map: MapStore
    let display: DisplaySettings
    @Bindable var frame: FrameState
    let window: CGSize
    let safe: EdgeInsets
    @State private var toast: Toast?
    @Environment(\.palette) private var palette

    private let sidebarWidth: CGFloat = 340
    private let inspectorWidth: CGFloat = 320
    private let gap: CGFloat = 12

    private var leading: CGFloat { frame.sidebar ? safe.leading + gap + sidebarWidth : safe.leading }
    private var trailing: CGFloat { frame.inspector ? safe.trailing + gap + inspectorWidth : safe.trailing }
    private var toolbarBottom: CGFloat { safe.top + gap + Metrics.mapControl }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Crosshair()
                .position(x: leading + (window.width - leading - trailing) / 2, y: toolbarBottom + (window.height - toolbarBottom) / 2)
            // The map's own buttons stand at the foot of the map, the readout at its middle.
            VStack {
                Spacer()
                ZStack(alignment: .bottomTrailing) {
                    ReadoutPill(store: map).mapChrome(map, "readout").frame(maxWidth: .infinity)
                    MapButtons(store: map).mapChrome(map, "buttons")
                }
                .padding(.bottom, safe.bottom + 24)
            }
            .padding(.leading, leading + gap)
            .padding(.trailing, trailing + gap)
            toolbar
                .padding(.top, safe.top + gap)
                .padding(.leading, leading + gap)
                .padding(.trailing, trailing + gap)
            if frame.sidebar {
                sidebar
                    .frame(width: sidebarWidth)
                    .padding(.top, safe.top + gap)
                    .padding(.bottom, safe.bottom + gap)
                    .padding(.leading, safe.leading + gap)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
            if frame.inspector {
                inspector
                    .frame(width: inspectorWidth)
                    .padding(.top, safe.top + gap)
                    .padding(.bottom, safe.bottom + gap)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, safe.trailing + gap)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
            if let press = map.pressed { MapMenu(store: map, press: press) }
            if let toast {
                ToastView(toast)
                    .frame(maxWidth: .infinity)
                    .padding(.top, toolbarBottom + gap)
                    .padding(.leading, leading)
                    .padding(.trailing, trailing)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .frame(width: window.width, height: window.height)
        .animation(.easeInOut(duration: 0.3), value: frame.sidebar)
        .animation(.easeInOut(duration: 0.3), value: frame.inspector)
        .onChange(of: leading, initial: true) { setPadding() }
        .onChange(of: trailing) { setPadding() }
        .onChange(of: window) { setPadding() }
        .onChange(of: map.toast) { _, words in show(words) }
    }

    private var toolbar: some View {
        HStack(alignment: .top, spacing: 8) {
            MapButton(frame.sidebar ? "Hide the sidebar" : "Show the sidebar", symbol: "sidebar.leading") { frame.sidebar.toggle() }
            MapSearchField(store: map, text: $frame.searchText, open: .constant(true), foldable: false)
                .frame(maxWidth: 420)
            Spacer(minLength: 0)
            RowButton(title: "Import", symbol: "square.and.arrow.down", showTitle: true) { soon("Import") }
                .keyboardShortcut("i", modifiers: .command)
            RowButton(title: "Library", symbol: "books.vertical", showTitle: true) { soon("The Library") }
                .keyboardShortcut("l", modifiers: .command)
            MapButton(frame.inspector ? "Hide the inspector" : "Show the inspector", symbol: "sidebar.trailing", selected: frame.inspector)
            {
                frame.inspector.toggle()
            }
            AccountMenu(display: display)
        }
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 12) {
            WorkspaceCapsule()
            Picker("Panel", selection: $frame.panel) {
                ForEach(Panel.allCases) { panel in Text(panel.title).tag(panel) }
            }
            .pickerStyle(.segmented)
            ScrollView { PanelContent(panel: frame.panel).padding(-8) }
        }
        .padding(16)
        .frame(maxHeight: .infinity, alignment: .top)
        .mapGlass(RoundedRectangle(cornerRadius: 28), interactive: false)
    }

    private var inspector: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Inspector").font(.title3.weight(.semibold))
            Text("The open LZ/PZ's plan, and a route's nav log as a table, are shown here once there is one (I4 and I6).")
                .foregroundStyle(palette.onSurfaceVariant)
            Spacer()
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .mapGlass(RoundedRectangle(cornerRadius: 28), interactive: false)
    }

    private func soon(_ what: String) { map.toast = "\(what) arrives in a later build." }

    private func setPadding() {
        map.setPadding(MapInsets(top: toolbarBottom, bottom: safe.bottom, leading: leading, trailing: trailing))
        map.setAvoid("status", CGRect(x: 0, y: 0, width: window.width, height: safe.top))
        map.setAvoid("row", CGRect(x: 0, y: 0, width: window.width, height: toolbarBottom))
        map.setAvoid("sidebar", frame.sidebar ? CGRect(x: 0, y: 0, width: leading, height: window.height) : nil)
        map.setAvoid("inspector", frame.inspector ? CGRect(x: window.width - trailing, y: 0, width: trailing, height: window.height) : nil)
    }

    private func show(_ words: String?) {
        guard let words else { return }
        map.toast = nil
        let next = Toast(words)
        withAnimation { toast = next }
        Task {
            try? await Task.sleep(for: .seconds(3))
            if toast == next { withAnimation { toast = nil } }
        }
    }
}
