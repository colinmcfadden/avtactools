import CoreJS

/// The colours a sketched route is drawn in, in the web's order (`ROUTE_COLORS`). The web hands them out from a counter that runs
/// for the length of the session; a phone's app is opened and closed all day, so a counter would give two routes the same colour as
/// often as not. A new route here takes the first colour no route of its set is using, and only once all eight are used does it
/// start over.
public enum RouteColors {
    public static let palette: [String] = ["#FF453A", "#0A84FF", "#32D74B", "#FFD60A", "#BF5AF2", "#FF9F0A", "#64D2FF", "#FF375F"]

    /// The colour for a new route among routes already drawn in `taken`. Colours are compared without regard to case.
    public static func next(_ taken: some Collection<String>) -> String {
        // Keyed by UTF-16 units, as Kotlin's set of Strings is: a Swift Set<String> would take canonically equivalent text for one.
        let used = Set(taken.map { UTF16Key($0.uppercased()) })
        return palette.first { !used.contains(UTF16Key($0)) } ?? palette[taken.count % palette.count]
    }
}
