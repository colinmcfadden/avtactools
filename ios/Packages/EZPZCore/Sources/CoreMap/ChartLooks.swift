/// When the VFR map asks which build is live (docs/VFR_CHARTS.md §4.5, §8), the web's `useVfrCharts`: every 5 minutes while it
/// shows, just after the live build runs out, and after a chart tile fails, at most once a minute.
///
/// Android's `ChartLooks` runs these decisions in coroutines over its chart source and watches MapLibre's tile requests through
/// OkHttp (`ChartTileWatch`); both are the app's to build on iOS. What is ported here is the plain part: the three intervals and
/// the decisions they make, so the app's task takes them from one place.
public enum ChartLooks {
    public static let lookEveryMs: Int64 = 5 * 60 * 1000
    public static let afterAFailedTileMs: Int64 = 60 * 1000

    /// A look at the very moment a build runs out would find the server's answer of the minute before (it keeps it for 60 s).
    public static let pastExpiryMs: Int64 = 61 * 1000

    /// As the VFR map shows: look at once when the last look was a while ago, or the coverage an earlier look had no reason to
    /// fetch is not the live build's.
    public static func looksOnShow(lookedAt: Int64, now: Int64, charts: VfrCharts) -> Bool {
        if now &- lookedAt >= lookEveryMs { return true }
        guard let live = charts.live else { return false }
        return charts.coverage.map { !sameText($0.build, live.build) } ?? true
    }

    /// After a chart tile failed: look only when the last look was at least a minute ago.
    public static func looksAfterFailedTile(lookedAt: Int64, now: Int64) -> Bool { now &- lookedAt >= afterAFailedTileMs }

    /// How long to wait for the next look, given when the live build runs out (`chartInstant` of its `expires`; nil for none): every
    /// five minutes, or sooner just after it runs out.
    public static func nextLookIn(expires: Int64?, now: Int64) -> Int64 {
        guard let expires else { return lookEveryMs }
        let toExpiry = expires &+ pastExpiryMs &- now
        return toExpiry > 0 ? min(lookEveryMs, toExpiry) : lookEveryMs
    }
}
