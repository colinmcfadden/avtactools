import CoreGeo
import CoreMap
import CoreModel
import DesignSystem
import SwiftUI

/// The crosshair, at the middle of the map that shows (the camera's padding): what the readout names and where things are put.
public struct Crosshair: View {
    @Environment(\.palette) private var palette
    public init() {}

    public var body: some View {
        ZStack {
            Circle().stroke(.black.opacity(0.55), lineWidth: 4).frame(width: 22, height: 22)
            Circle().stroke(palette.mode == .night ? palette.primary : .white, lineWidth: 2).frame(width: 22, height: 22)
            Circle().fill(palette.mode == .night ? palette.primary : .white).frame(width: 3, height: 3)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The pill under the crosshair: the grid there, monospaced (crews work in MGRS); a tap copies it. Grows with the person's text size.
public struct ReadoutPill: View {
    let store: MapStore
    @Environment(\.palette) private var palette

    public init(store: MapStore) { self.store = store }

    public var body: some View {
        if let readout = store.readout, let center = store.camera?.center {
            Button {
                store.copyGrid(at: center)
            } label: {
                Text(readout.mgrs ?? readout.latLon)
                    .font(.system(.body, design: .monospaced).weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 40)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .mapGlass()
            .accessibilityLabel("Grid at the crosshair: \(readout.mgrs ?? readout.latLon)")
            .accessibilityHint("Copies it")
        }
    }
}

/// The night palette dims the map itself (Android's NightDim): bright imagery would light a cockpit and bloom under NVGs.
public struct NightDim: View {
    @Environment(\.palette) private var palette
    public init() {}

    public var body: some View {
        if palette.mode == .night {
            Color.black.opacity(0.45).allowsHitTesting(false).accessibilityHidden(true)
        }
    }
}

/// The MGRS grid's labels over the map: an 11 pt pill on a translucent dark ground, monospaced, at a fixed size (the grid keeps them
/// apart by that size; the readout is what grows with the text). Placed where the map is now, so they follow it between settles.
/// Hidden from VoiceOver: they are a picture of what the readout says in words.
public struct GridLabels: View {
    let store: MapStore

    public init(store: MapStore) { self.store = store }

    public var body: some View {
        // Read so every move of the map redraws the labels where it now is.
        let _ = store.drawn
        ZStack(alignment: .topLeading) {
            if store.gridShown {
                ForEach(Array(store.grid.labels.enumerated()), id: \.offset) { _, label in
                    if let anchor = store.point(of: LatLon(lat: label.lat, lon: label.lon)) {
                        GridPill(label: label, anchor: anchor)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct GridPill: View {
    let label: GridLabel
    let anchor: CGPoint
    @State private var size = CGSize.zero

    var body: some View {
        let at = MgrsGridScene.pillTopLeft(label, ScreenPoint(x: anchor.x, y: anchor.y), size.width, size.height, 4)
        Text(label.text)
            .font(.system(size: 11, weight: .semibold, design: .monospaced))
            .dynamicTypeSize(.large)
            .foregroundStyle(label.kind == "gzd" ? Color.white : Color(argb: 0xFFE7_EAEE))
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 5)
            .padding(.vertical, 3)
            .background(Color(red: 12 / 255, green: 16 / 255, blue: 22 / 255).opacity(0.62), in: RoundedRectangle(cornerRadius: 9))
            .overlay {
                if label.kind == "square" { RoundedRectangle(cornerRadius: 9).stroke(.white.opacity(0.35), lineWidth: 1) }
            }
            .onGeometryChange(for: CGSize.self, of: \.size) { size = $0 }
            .offset(x: at.x, y: at.y)
            .opacity(size == .zero ? 0 : 1)
    }
}

/// The Layers button's menu: the base maps that can be drawn now, and the MGRS grid.
public struct LayersButton: View {
    let store: MapStore
    @Environment(\.palette) private var palette

    public init(store: MapStore) { self.store = store }

    public var body: some View {
        Menu {
            Picker("Base map", selection: Binding(get: { store.style.id }, set: { store.selectStyle($0) })) {
                ForEach(store.styles, id: \.id) { style in Text(style.label).tag(style.id) }
            }
            .pickerStyle(.inline)
            Toggle("MGRS grid", systemImage: "grid", isOn: Binding(get: { store.gridShown }, set: { _ in store.toggleGrid() }))
        } label: {
            Image(systemName: "square.3.layers.3d")
                .font(.system(size: 19, weight: .semibold))
                .frame(width: Metrics.mapControl, height: Metrics.mapControl)
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .mapGlass(Circle())
        .accessibilityLabel("Layers")
    }
}

/// The map's own buttons, at the trailing edge: Layers, My location, and north once the map is turned.
public struct MapButtons: View {
    let store: MapStore

    public init(store: MapStore) { self.store = store }

    public var body: some View {
        VStack(spacing: 12) {
            LayersButton(store: store)
            MapButton(
                store.locating ? "Stop following my position" : "My location", symbol: store.locating ? "location.fill" : "location",
                selected: store.locating
            ) { store.toggleLocating() }
            if let bearing = store.camera?.bearingDegrees, abs(bearing) > 0.5, abs(bearing - 360) > 0.5 {
                MapButton("Face north", symbol: "location.north.line") { store.faceNorth() }
                    .rotationEffect(.degrees(-bearing))
            }
        }
    }
}

/// The long press's menu (Android's `MapMenu`, the design's map menu): headed by the grid under the finger, which a tap copies, then
/// what can be done there. Stands above and to the trailing side of the press, never off the window.
public struct MapMenu: View {
    let store: MapStore
    let press: MapPress
    /// What the frame adds for this place (New LZ/PZ here, Add threat here…: I4 onwards).
    let extra: [MapMenuItem]
    @Environment(\.palette) private var palette
    @State private var size = CGSize.zero

    public init(store: MapStore, press: MapPress, extra: [MapMenuItem] = []) {
        self.store = store
        self.press = press
        self.extra = extra
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // A touch anywhere else closes it.
                Color.clear.contentShape(Rectangle())
                    .onTapGesture { store.pressed = nil }
                    .accessibilityHidden(true)
                Circle().stroke(palette.accent, lineWidth: 2).frame(width: 28, height: 28)
                    .position(press.point)
                    .accessibilityHidden(true)
                card
                    .onGeometryChange(for: CGSize.self, of: \.size) { size = $0 }
                    .offset(Self.placement(press.point, size, geo.size))
                    .opacity(size == .zero ? 0 : 1)
            }
        }
    }

    private var card: some View {
        let grid = press.grid ?? PlaceSearch.formatLatLong(press.at.lat, press.at.lon)
        return VStack(alignment: .leading, spacing: 0) {
            Button {
                store.copyGrid(at: press.at)
                store.pressed = nil
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(grid)
                        .font(.system(.headline, design: .monospaced))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(PlaceSearch.formatLatLong(press.at.lat, press.at.lon))
                        .font(.caption)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(grid). Copies it")
            Divider()
            ForEach(extra) { item in row(item.title, item.symbol, enabled: item.enabled) { item.action() } }
            row("Copy grid", "doc.on.doc") { store.copyGrid(at: press.at) }
            if let mgrs = press.grid {
                ShareLink(item: GridShare.shareText(mgrs, press.at.lat, press.at.lon)) {
                    Label("Share grid…", systemImage: "square.and.arrow.up").rowStyle()
                }
                .buttonStyle(.plain)
            }
        }
        .frame(width: 280)
        .mapGlass(RoundedRectangle(cornerRadius: 22), interactive: false)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    private func row(_ title: String, _ symbol: String, enabled: Bool = true, _ action: @escaping () -> Void) -> some View {
        Button {
            action()
            store.pressed = nil
        } label: {
            Label(title, systemImage: symbol).rowStyle()
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }

    /// Above and to the right of the press, else to its left, else below; never past the window's edges (Android's `mapMenuPosition`).
    static func placement(_ press: CGPoint, _ card: CGSize, _ window: CGSize) -> CGSize {
        let gap: CGFloat = 16
        var x = press.x + gap
        if x + card.width > window.width - 8 { x = press.x - gap - card.width }
        x = min(max(8, x), max(8, window.width - card.width - 8))
        var y = press.y - gap - card.height
        if y < 8 { y = press.y + gap }
        y = min(max(8, y), max(8, window.height - card.height - 8))
        return CGSize(width: x, height: y)
    }
}

/// A choice the frame adds to the map's menu.
public struct MapMenuItem: Identifiable {
    public let id: String
    public let title: String
    public let symbol: String
    public let enabled: Bool
    public let action: () -> Void

    public init(_ title: String, symbol: String, enabled: Bool = true, action: @escaping () -> Void) {
        id = title
        self.title = title
        self.symbol = symbol
        self.enabled = enabled
        self.action = action
    }
}

extension View {
    fileprivate func rowStyle() -> some View {
        self.font(.body)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: Metrics.mapControl, alignment: .leading)
            .contentShape(Rectangle())
    }
}

extension View {
    /// Tells the MGRS grid where this piece of what floats over the map is, so its labels keep off it (Android's `chromeBox`). In the
    /// window's coordinates, the map's own: the frames lay the map and themselves out over the whole window. Taken away when it goes.
    public func mapChrome(_ store: MapStore, _ key: String, shown: Bool = true) -> some View {
        onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { rect in
            store.setAvoid(key, shown && rect.width > 0 && rect.height > 0 ? rect : nil)
        }
        .onChange(of: shown) { _, now in if !now { store.setAvoid(key, nil) } }
        .onDisappear { store.setAvoid(key, nil) }
    }
}
