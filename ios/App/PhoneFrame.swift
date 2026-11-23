import DesignSystem
import FeatureMap
import SwiftUI

/// The iPhone's frame (docs/IOS_PLAN.md §7.1, Android's `PhoneFrame`): a row of glass along the top (the folded search, the workspace,
/// Import, the Library, the account), the map's buttons at the trailing edge, the crosshair and its readout, and the panel as a sheet
/// with three detents whose tab control sits in its bottom inset. A tap on the map hides the row and the sheet; the next brings them back.
struct PhoneFrame: View {
    let map: MapStore
    let display: DisplaySettings
    @Bindable var frame: FrameState
    /// The whole window, the safe areas included: the frame is laid out over all of it, as the map is drawn.
    let window: CGSize
    let safe: EdgeInsets
    @State private var sheetTop: CGFloat?
    @State private var toast: Toast?
    @Environment(\.palette) private var palette

    /// The row's bottom under the status bar: the top of the part of the map the crosshair marks the middle of (`MapChrome.ROW_BOTTOM`).
    private var rowBottom: CGFloat { safe.top + 8 + Metrics.mapControl }

    /// The strip the sheet covers, never more than half's: a sheet at full height slides over the map rather than moving it.
    private var covered: CGFloat {
        guard !frame.hidden, let sheetTop else { return 0 }
        return min(max(0, window.height - sheetTop), window.height / 2)
    }

    var body: some View {
        ZStack(alignment: .top) {
            Crosshair()
                .position(x: window.width / 2, y: crosshairY)
            // The map's buttons stand under the row, at the end, wherever the row's search list reaches.
            MapButtons(store: map)
                .mapChrome(map, "phone.buttons")
                .padding(.top, rowBottom + 16)
                .padding(.trailing, 12 + safe.trailing)
                .frame(maxWidth: .infinity, alignment: .trailing)
            controls
            if let press = map.pressed {
                MapMenu(store: map, press: press)
                    .padding(.bottom, covered)
            }
            if let toast {
                ToastView(toast)
                    .padding(.top, (frame.hidden ? safe.top : rowBottom) + 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .frame(width: window.width, height: window.height)
        .sheet(isPresented: Binding(get: { !frame.hidden }, set: { _ in })) {
            PhoneSheet(frame: frame, display: display) { top in sheetTop = top }
        }
        .onChange(of: covered, initial: true) { setPadding() }
        .onDisappear { map.setAvoid("phone.status", nil) }
        .onChange(of: frame.hidden) { setPadding() }
        .onChange(of: window) { setPadding() }
        .onChange(of: map.toast) { _, words in show(words) }
    }

    private var crosshairY: CGFloat {
        let top = frame.hidden ? safe.top : rowBottom
        let bottom = window.height - (covered > 0 ? covered : safe.bottom)
        return top + (bottom - top) / 2
    }

    @ViewBuilder private var controls: some View {
        VStack(spacing: 0) {
            row
                .padding(.leading, 12 + safe.leading)
                .padding(.trailing, 12 + safe.trailing)
                .padding(.top, safe.top + 8)
                .opacity(frame.hidden ? 0 : 1)
                .offset(y: frame.hidden ? -(rowBottom + 20) : 0)
                .allowsHitTesting(!frame.hidden)
                .accessibilityHidden(frame.hidden)
                .mapChrome(map, "phone.row", shown: !frame.hidden)
            Spacer()
            ReadoutPill(store: map)
                .mapChrome(map, "phone.readout")
                .padding(.horizontal, 24 + max(safe.leading, safe.trailing))
                .padding(.bottom, (covered > 0 ? covered : safe.bottom) + 12)
        }
        .animation(.easeInOut(duration: 0.3), value: frame.hidden)
    }

    private var row: some View {
        HStack(spacing: 8) {
            if frame.searchOpen {
                MapSearchField(store: map, text: $frame.searchText, open: $frame.searchOpen, foldable: true)
            } else {
                MapSearchField(store: map, text: $frame.searchText, open: $frame.searchOpen, foldable: true)
                WorkspaceCapsule().layoutPriority(1)
                Spacer(minLength: 0)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        RowButton(title: "Import", symbol: "square.and.arrow.down", showTitle: true) { soon("Import") }
                        RowButton(title: "Library", symbol: "books.vertical", showTitle: true) { soon("The Library") }
                    }
                    HStack(spacing: 8) {
                        RowButton(title: "Import", symbol: "square.and.arrow.down", showTitle: false) { soon("Import") }
                        RowButton(title: "Library", symbol: "books.vertical", showTitle: false) { soon("The Library") }
                    }
                }
                AccountMenu(display: display)
            }
        }
        .frame(minHeight: Metrics.mapControl, alignment: .top)
        // The row's controls are a fixed 48 pt; past this their words would push the account off the screen.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    private func soon(_ what: String) { map.toast = "\(what) arrives in a later build." }

    private func setPadding() {
        // The camera's padding: the row (or the status bar alone with the frame hidden) and the sheet, so the crosshair is the
        // middle of the map that shows. The map is drawn under the status bar, so the top counts it.
        map.setPadding(MapInsets(top: frame.hidden ? safe.top : rowBottom, bottom: covered > 0 ? covered : safe.bottom))
        // The status bar is told on its own: the row's box goes with the frame, the status bar's never does.
        map.setAvoid("phone.status", CGRect(x: 0, y: 0, width: window.width, height: safe.top))
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

/// The phone's sheet: the panel chosen, over the tab control in its bottom inset (a system `TabView` cannot sit over a presented sheet).
/// It never closes by a swipe; a tap on the map hides it.
private struct PhoneSheet: View {
    @Bindable var frame: FrameState
    let display: DisplaySettings
    let top: (CGFloat) -> Void
    @State private var detent: PresentationDetent = PhoneSheet.peek
    /// Lowered: the panel's header over the tab control, the rest of the map free (Android's `PanelSheet`, lowered to its header).
    static let peek = PresentationDetent.custom(Peek.self)

    /// 200 pt at the default text size, growing with the person's text so the header stays clear of the tabs; never past half.
    private struct Peek: CustomPresentationDetent {
        static func height(in context: Context) -> CGFloat? {
            let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(context.dynamicTypeSize))
            return min(UIFontMetrics(forTextStyle: .body).scaledValue(for: 200, compatibleWith: traits), context.maxDetentValue / 2)
        }
    }
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            PanelContent(panel: frame.panel)
        }
        .safeAreaInset(edge: .bottom) { PanelTabs(frame: frame) }
        .onGeometryChange(for: CGFloat.self, of: { $0.frame(in: .global).minY }) { top($0) }
        .presentationDetents([Self.peek, .medium, .large], selection: $detent)
        .presentationBackgroundInteraction(.enabled(upThrough: .medium))
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled()
        .presentationBackground { if palette.mode == .night { palette.surfaceContainerLow } }
        .appTheme(night: display.night)
    }
}

/// The panels' tab control: a button for each in one glass container, the chosen one tinted.
struct PanelTabs: View {
    @Bindable var frame: FrameState
    @Environment(\.palette) private var palette

    var body: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 4) {
                ForEach(Panel.allCases) { panel in
                    let chosen = frame.panel == panel
                    Button {
                        frame.choose(panel)
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: panel.symbol).font(.system(size: 18, weight: .semibold)).frame(height: 24)
                            Text(panel.title).font(.caption2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                        }
                        .foregroundStyle(chosen ? palette.accent : palette.onSurfaceVariant)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(chosen ? [.isSelected, .isButton] : .isButton)
                }
            }
            .padding(4)
            .glassEffect(.regular, in: Capsule())
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }
}
