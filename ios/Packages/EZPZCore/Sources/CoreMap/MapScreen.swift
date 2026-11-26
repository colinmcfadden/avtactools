/// Where the map's own controls sit, in points from the top of the screen below the status bar (Android's `MapChrome` in MapScreen.kt,
/// in dp), so both frames share one set of sizes. **A phone** (the owner's decision of 2026-10-13, departing from docs/native-design's
/// search bar over a row of chips): one row `rowTop` down and `rowHeight` tall, the folded search, the workspace, Import, the Library
/// and the account on it, each drawn `chipsHeight` tall; the map's buttons `buttonsGap` under the row (`buttonsTop`); a sheet over the
/// map rises no higher than `sheetTopLimit`, so the row always shows. **A tablet**: its search bar `searchTop` down and `searchHeight`
/// tall, and the map's buttons at its foot.
public enum MapChrome {
    public static let searchTop = 8.0
    public static let searchHeight = 56.0

    /// The bottom of a tablet's search bar: the top of the part of the map the crosshair marks the middle of.
    public static let searchBottom = searchTop + searchHeight

    public static let rowTop = 8.0

    /// As tall as a touch target (crews may wear gloves): what is drawn on it is `chipsHeight` tall, each touch target 48 points.
    public static let rowHeight = 48.0

    /// The bottom of the phone's row: the top of the part of the map the crosshair marks the middle of.
    public static let rowBottom = rowTop + rowHeight

    /// The height every control on the phone's row is drawn at: the folded search, the chips and the account (and the open search).
    public static let chipsHeight = 40.0

    /// The gap between the phone's row and a sheet at full height.
    public static let sheetGap = 12.0
    public static let buttonsGap = 16.0

    /// The bottom of the row, plus the gap left between it and a sheet at full height.
    public static let sheetTopLimit = rowBottom + sheetGap

    /// The top of the phone's map buttons, at the end under the row.
    public static let buttonsTop = rowBottom + buttonsGap

    /// The gap between the readout pill and the sheet's edge, and between the pill and a tool floating above it.
    public static let pillGap = 12.0

    /// The map's buttons: this square, at the end.
    public static let button = 48.0

    /// A tablet's map buttons stand this far above the map's foot (the design's AT screens).
    public static let footGap = 24.0

    /// How far the LZ/PZ tools stand apart from the map's own buttons: a group of their own (the owner's request, 2026-10-22).
    public static let toolsApart = 24.0

    /// The room the tools keep from what ends theirs: the sheet's edge, the keyboard, a tablet's search bar or its buttons.
    public static let toolsGap = 16.0
}
