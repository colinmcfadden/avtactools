import CoreModel
import Foundation

/// A line of what the map says of its charts; `expired` is drawn in the error colour.
public struct ChartLine: Hashable, Sendable {
    public let text: String
    public let expired: Bool

    public init(_ text: String, _ expired: Bool) {
        self.text = text
        self.expired = expired
    }
}

/// What the map says of the charts under it: `lines`, naming what is in view with its edition, and `enlarged`, the quiet line about
/// the chart at the crosshair.
public struct ChartLines: Hashable, Sendable {
    public let lines: [ChartLine]
    public let enlarged: String?

    public init(lines: [ChartLine], enlarged: String?) {
        self.lines = lines
        self.enlarged = enlarged
    }
}

/// The web's `chartWords` (`frontend/src/feature/vfrCharts/vfrCharts.js`), function by function, held to the same
/// `contracts/fixtures/charts/words.json` over the same `coverage.json`. The zoom it takes is the web's (Leaflet's): MapLibre's plus
/// one. `finer` is the screen's `levelsFiner`, which decides which charts are drawn.
///
/// Where the words are shown, and when they are worked out again (once the camera settles, and at the instant a chart runs out), is
/// the screen's (Android's `rememberChartLines`): the view is `MgrsGridScene.viewOf(camera, width, height, density)?.bounds`.
public enum ChartWords {
    /// What the layers from ArcGIS's service are called: it publishes no edition.
    public static let arcgis = "FAA, via ArcGIS"

    private static let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    private static let named = 2  // charts of one edition named before "and n more"
    private static let samples = 5  // points a side of the view tried against a chart's outline
    private static let dayMs: Int64 = 24 * 60 * 60 * 1000

    public static func of(
        live: VfrLive?, coverage: ChartCoverage?, view: ChartBox, center: LatLon, zoom: Double, now: Int64, finer: Int = 0
    ) -> ChartLines {
        guard let live else { return ChartLines(lines: [ChartLine(arcgis, false)], enlarged: nil) }
        let views = viewsOf(view)
        let lines =
            if let coverage {
                linesOf(chartsInView(coverage, views, levelAt(zoom, coverage, finer)), now)
            } else {
                [buildLine(live, now)]
            }
        let overArcgis = live.fallback.contains { box in
            views.contains { $0.west < box.east && box.west < $0.east && $0.south < box.north && box.south < $0.north }
        }
        return ChartLines(
            lines: overArcgis ? lines + [ChartLine(arcgis, false)] : lines,
            enlarged: coverage.flatMap { enlargedWords($0, center, zoom, finer) })
    }

    /// The next instant a chart's edition runs out, after `now`; nil when none will.
    public static func nextExpiry(live: VfrLive?, coverage: ChartCoverage?, now: Int64) -> Int64? {
        let all = [live.flatMap { chartInstant($0.expires) }].compactMap { $0 } + (coverage?.charts ?? []).map(\.edition.expiresAt)
        return all.filter { $0 > now }.min()
    }

    // `2026-12-23` as `23 Dec 2026`. A text that is not such a date, which only a build's own dates past the year 9999 could give, is
    // shown as it is: Kotlin's `toInt` and its month list would throw there.
    private static func day(_ text: String, withYear: Bool = true) -> String {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, let month = Int(parts[1]), (1...12).contains(month), let date = Int(parts[2]) else { return text }
        return "\(date) \(months[month - 1])" + (withYear ? " \(parts[0])" : "")
    }

    private static func span(_ edition: ChartEdition) -> String {
        let sameYear = edition.start.utf16.prefix(4).elementsEqual(edition.end.utf16.prefix(4))
        return "\(day(edition.start, withYear: !sameYear)) – \(day(edition.end))"
    }

    private static func insideRing(_ ring: [ChartPoint], _ lon: Double, _ lat: Double) -> Bool {
        var inside = false
        var j = ring.count - 1
        for i in ring.indices {
            let a = ring[i]
            let b = ring[j]
            if (a.lat > lat) != (b.lat > lat) && lon < ((b.lon - a.lon) * (lat - a.lat)) / (b.lat - a.lat) + a.lon { inside.toggle() }
            j = i
        }
        return inside
    }

    // Kotlin's `%` on a double is `truncatingRemainder`.
    private static func wrapped(_ lon: Double) -> Double {
        ((lon + 180).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) - 180
    }

    // The view with its west within ±180°, and once more a whole turn west where it crosses the date line: a map panned round
    // the world gives longitudes past 180°, and the charts' never are.
    private static func viewsOf(_ view: ChartBox) -> [ChartBox] {
        let shift = wrapped(view.west) - view.west
        let shifted = ChartBox(view.west + shift, view.south, view.east + shift, view.north)
        return shifted.east > 180 ? [shifted, ChartBox(shifted.west - 360, shifted.south, shifted.east - 360, shifted.north)] : [shifted]
    }

    private static func samplesOf(_ view: ChartBox) -> [ChartPoint] {
        var points: [ChartPoint] = []
        for i in 0..<samples {
            for j in 0..<samples {
                points.append(
                    ChartPoint(
                        lon: view.west + (view.east - view.west) * Double(i) / Double(samples - 1),
                        lat: view.south + (view.north - view.south) * Double(j) / Double(samples - 1)))
            }
        }
        return points
    }

    private static func touches(_ ring: [ChartPoint], _ view: ChartBox) -> Bool {
        ring.contains { $0.lon >= view.west && $0.lon <= view.east && $0.lat >= view.south && $0.lat <= view.north }
            || samplesOf(view).contains { insideRing(ring, $0.lon, $0.lat) }
    }

    private static func covers(_ ring: [ChartPoint], _ view: ChartBox) -> Bool {
        samplesOf(view).allSatisfy { insideRing(ring, $0.lon, $0.lat) }
    }

    private static func finestFirst(_ charts: [CoverageChart]) -> [CoverageChart] { stableSorted(charts, by: \.scale) }

    // Android's `RouteCalc.jsRound`: `floor(x + 0.5)`, which is what it rounds a zoom with (not quite JavaScript's Math.round at
    // 0.49999999999999994, which no zoom is).
    private static func jsRound(_ x: Double) -> Double { (x + 0.5).rounded(.down) }

    // The level whose tiles the map is drawn from: the web's map rounds its zoom, and shrinks the first level's tiles below it.
    private static func levelAt(_ zoom: Double, _ coverage: ChartCoverage, _ finer: Int) -> Int {
        max(kotlinAdd(kotlinAdd(kotlinInt(jsRound(zoom)), -1), finer), coverage.minLevel)
    }

    // The charts drawn in the view, finest first: those shown at this level whose outlines reach it, less any wholly under a finer one.
    private static func chartsInView(_ coverage: ChartCoverage, _ views: [ChartBox], _ level: Int) -> [CoverageChart] {
        let reach = finestFirst(coverage.charts.filter { $0.shownFrom <= level }).filter { chart in
            views.contains { touches(chart.ring, $0) }
        }
        return reach.filter { chart in
            !reach.contains { finer in finer.scale < chart.scale && views.contains { covers(finer.ring, $0) } }
        }
    }

    private static func lineOf(_ titles: [String], _ edition: ChartEdition, _ now: Int64) -> ChartLine {
        let more = titles.count - named
        let names = titles.prefix(named).joined(separator: ", ") + (more > 0 ? " and \(more) more" : "")
        let expired = now >= edition.expiresAt
        return ChartLine(expired ? "FAA \(names) · Expired \(day(edition.end)) — not current" : "FAA \(names), \(span(edition))", expired)
    }

    // One line for each edition in view (a chart held back keeps its own), so a set of charts of one cycle is one line. Grouped as
    // Kotlin's `groupBy` groups: in the order each edition is first met. The key's texts are compared by UTF-16 units.
    private static func linesOf(_ charts: [CoverageChart], _ now: Int64) -> [ChartLine] {
        var groups: [(start: String, expires: String, charts: [CoverageChart])] = []
        for chart in charts {
            if let at = groups.firstIndex(where: { sameText($0.start, chart.edition.start) && sameText($0.expires, chart.edition.expires) })
            {
                groups[at].charts.append(chart)
            } else {
                groups.append((chart.edition.start, chart.edition.expires, [chart]))
            }
        }
        let lines = groups.map { group in lineOf(group.charts.map(\.title), group.charts[0].edition, now) }
        return lines.filter(\.expired) + lines.filter { !$0.expired }
    }

    // Before coverage.json has come, the build's own edition: its expiry instant is 0901Z the day after its printed end date.
    private static func buildLine(_ live: VfrLive, _ now: Int64) -> ChartLine {
        guard let expiresAt = chartInstant(live.expires) else { return ChartLine("FAA VFR charts", false) }
        let end = take(instantText(expiresAt - dayMs), 10)
        let start = take(live.effective, 10)
        return lineOf(
            ["VFR charts"], ChartEdition(start: start, end: end, expires: live.expires, expiresAt: expiresAt), now)
    }

    // The first ten characters of `Instant.ofEpochMilli(millis).toString()`, the UTC date as ISO_INSTANT writes it: four digits
    // for the years 0 to 9999, `+` and the digits after, `-` and the digits (four at least) before.
    private static func instantText(_ millis: Int64) -> String {
        let days = millis >= 0 ? millis / 86_400_000 : (millis - 86_399_999) / 86_400_000
        let (year, month, date) = civil(days)
        let digits = String(abs(year))
        let paddedYear = String(repeating: "0", count: max(0, 4 - digits.count)) + digits
        let yearText = year > 9999 ? "+" + digits : year < 0 ? "-" + paddedYear : paddedYear
        return yearText + "-" + two(month) + "-" + two(date)
    }

    private static func two(_ value: Int64) -> String { value < 10 ? "0\(value)" : "\(value)" }

    // The proleptic Gregorian date of a day counted from 1970-01-01 (java.time's LocalDate.ofEpochDay).
    private static func civil(_ epochDay: Int64) -> (Int64, Int64, Int64) {
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let dayOfEra = z - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let mp = (5 * dayOfYear + 2) / 153
        let date = dayOfYear - (153 * mp + 2) / 5 + 1
        let month = mp < 10 ? mp + 3 : mp - 9
        return (yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month, date)
    }

    private static func tierWords(_ tier: String) -> String {
        switch Array(tier.utf16) {
        case Array("sectional".utf16): "Sectional"
        case Array("tac".utf16): "TAC"
        case Array("helicopter".utf16): "Helicopter chart"
        case Array("inset".utf16): "Inset"
        default: "Chart"
        }
    }

    // Past the chart's own detail, by the whole power of two nearest: a line that changed with every tenth of a zoom would not be
    // quiet. Counted from the chart's native zoom, so the same on every screen; only which chart is drawn there follows the screen.
    private static func enlargedWords(_ coverage: ChartCoverage, _ center: LatLon, _ zoom: Double, _ finer: Int) -> String? {
        let level = levelAt(zoom, coverage, finer)
        let lon = wrapped(center.lon)
        guard let chart = finestFirst(coverage.charts).first(where: { $0.shownFrom <= level && insideRing($0.ring, lon, center.lat) })
        else { return nil }
        let power = kotlinInt(jsRound(zoom - 1 - chart.nativeLevel))
        if power < 1 { return nil }
        // Kotlin's `1L shl power` takes the count modulo 64, where Swift's `<<` past 63 gives 0.
        return "\(tierWords(chart.tier)) enlarged \(Int64(1) << Int64(power & 63))× — no finer chart here"
    }
}
