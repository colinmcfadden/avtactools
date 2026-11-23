import CoreJS
import Foundation

/// `[west, south, east, north]` in degrees, as the config's `bounds` and `fallback` give a box.
public struct ChartBox: Hashable, Sendable {
    public var west: Double
    public var south: Double
    public var east: Double
    public var north: Double

    public init(_ west: Double, _ south: Double, _ east: Double, _ north: Double) {
        self.west = west
        self.south = south
        self.east = east
        self.north = north
    }

    /// A box MapLibre can be handed as a source's bounds; nil for anything else, which would refuse the whole style.
    public static func of(_ values: [Double]?) -> ChartBox? {
        guard let values, values.count == 4, values.allSatisfy(\.isFinite) else { return nil }
        let (west, south, east, north) = (values[0], values[1], values[2], values[3])
        let inWorld = west >= -180.0 && east <= 180.0 && south >= -90.0 && north <= 90.0
        return inWorld && west < east && south < north ? ChartBox(west, south, east, north) : nil
    }
}

/// The live build of the FAA's charts, as `/api/config` names it (docs/VFR_CHARTS.md §4.5, §8): the web's `liveBuildOf`. A value the
/// server has never sent is no build, and ArcGIS draws the VFR map: it was remembered as text, and a bad box or zoom range would refuse
/// the whole style, leaving no map at all.
///
/// Reading the config's `charts.vfr` into the fields `of` takes is the network client's (Android's `AppConfig.Charts.Vfr` in
/// core-network, read by the app's `ChartConfig`), not this module's.
public struct VfrLive: Hashable, Sendable {
    public let build: String
    /// The tile address, whole.
    public let tiles: String
    /// Where the build's coverage is, relative to the server's root: the app's own client asks for it.
    public let coverage: String
    public let tileSize: Int
    public let minZoom: Int
    public let maxZoom: Int
    public let effective: String
    public let expires: String
    public let bounds: ChartBox
    public let fallback: [ChartBox]

    public init(
        build: String, tiles: String, coverage: String, tileSize: Int, minZoom: Int, maxZoom: Int, effective: String, expires: String,
        bounds: ChartBox, fallback: [ChartBox]
    ) {
        self.build = build
        self.tiles = tiles
        self.coverage = coverage
        self.tileSize = tileSize
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.effective = effective
        self.expires = expires
        self.bounds = bounds
        self.fallback = fallback
    }

    private static let maxLevel = 24
    private static let maxTileSize = 4096

    /// The build named by the config's own fields, `root` being the server's; nil where they cannot be drawn.
    public static func of(
        root: String, build: String, tiles: String, coverage: String, tileSize: Int, minZoom: Int, maxZoom: Int, effective: String,
        expires: String, bounds: [Double], fallback: [[Double]]
    ) -> VfrLive? {
        guard let box = ChartBox.of(bounds) else { return nil }
        let addressed = isOnThisServer(tiles) && containsText(tiles, "{z}") && containsText(tiles, "{x}") && containsText(tiles, "{y}")
        let sized =
            (1...maxTileSize).contains(tileSize) && (0...maxLevel).contains(minZoom) && minZoom <= maxZoom && maxZoom <= maxLevel
        if kotlinIsBlank(build) || !addressed || !isOnThisServer(coverage) || !sized { return nil }
        if chartInstant(effective) == nil || chartInstant(expires) == nil { return nil }
        return VfrLive(
            build: build, tiles: trimEndSlashes(root) + tiles, coverage: coverage, tileSize: tileSize, minZoom: minZoom, maxZoom: maxZoom,
            effective: effective, expires: expires, bounds: box, fallback: fallback.compactMap(ChartBox.of))
    }

    private static func isOnThisServer(_ text: String) -> Bool { startsWith(text, "/") && !startsWith(text, "//") }

    // Kotlin's `trimEnd('/')`, by UTF-16 units.
    private static func trimEndSlashes(_ text: String) -> String {
        var units = Array(text.utf16)
        while units.last == 0x2F { units.removeLast() }
        return String(decoding: units, as: UTF16.self)
    }
}

/// A chart's edition: its printed dates and the instant it runs out.
public struct ChartEdition: Hashable, Sendable {
    public let start: String
    public let end: String
    public let expires: String
    public let expiresAt: Int64

    public init(start: String, end: String, expires: String, expiresAt: Int64) {
        self.start = start
        self.end = end
        self.expires = expires
        self.expiresAt = expiresAt
    }
}

/// A place on a chart's outline.
public struct ChartPoint: Hashable, Sendable {
    public let lon: Double
    public let lat: Double

    public init(lon: Double, lat: Double) {
        self.lon = lon
        self.lat = lat
    }
}

/// One chart of a build, as its `coverage.json` says.
public struct CoverageChart: Hashable, Sendable {
    public let title: String
    public let tier: String
    public let scale: Double
    public let edition: ChartEdition
    public let shownFrom: Int
    /// The level this chart's own pixels fall on: 10.58, say, so not a whole one.
    public let nativeLevel: Double
    public let ring: [ChartPoint]

    public init(
        title: String, tier: String, scale: Double, edition: ChartEdition, shownFrom: Int, nativeLevel: Double, ring: [ChartPoint]
    ) {
        self.title = title
        self.tier = tier
        self.scale = scale
        self.edition = edition
        self.shownFrom = shownFrom
        self.nativeLevel = nativeLevel
        self.ring = ring
    }
}

/// What a build holds: the charts it draws from, which the map names (`coverage.json`, the web's `isCoverage`).
public struct ChartCoverage: Hashable, Sendable {
    public let build: String
    public let minLevel: Int
    public let charts: [CoverageChart]

    public init(build: String, minLevel: Int, charts: [CoverageChart]) {
        self.build = build
        self.minLevel = minLevel
        self.charts = charts
    }

    /// Read from what came, or from the device's copy; nil where it cannot be. A chart that cannot be read is left out of it.
    public static func parse(_ text: String) -> ChartCoverage? {
        guard let root = try? JSONTree.parse(text) else { return nil }
        return of(root)
    }

    public static func of(_ root: JSONTree) -> ChartCoverage? {
        guard let document = root.objectValue, let build = text(document["build"]), let minLevel = integer(document["minLevel"]),
            let charts = document["charts"]?.arrayValue
        else { return nil }
        return ChartCoverage(build: build, minLevel: minLevel, charts: charts.compactMap(chartOf))
    }

    private static func chartOf(_ element: JSONTree) -> CoverageChart? {
        guard let chart = element.objectValue, let edition = chart["edition"]?.objectValue.flatMap(editionOf),
            let outline = chart["outline"]?.objectValue, let pairs = outline["coordinates"]?.arrayValue?.first?.arrayValue
        else { return nil }
        var ring: [ChartPoint] = []
        for pair in pairs {
            guard let point = pointOf(pair) else { return nil }
            ring.append(point)
        }
        guard ring.count >= 3, let title = text(chart["title"]), let tier = text(chart["tier"]), let scale = number(chart["scale"]),
            let shownFrom = integer(chart["shownFrom"]), let nativeLevel = number(chart["nativeLevel"])
        else { return nil }
        return CoverageChart(
            title: title, tier: tier, scale: scale, edition: edition, shownFrom: shownFrom, nativeLevel: nativeLevel, ring: ring)
    }

    private static func editionOf(_ edition: JSONObject) -> ChartEdition? {
        guard let start = text(edition["start"]), isDate(start), let end = text(edition["end"]), isDate(end),
            let expires = text(edition["expires"]), let expiresAt = chartInstant(expires)
        else { return nil }
        return ChartEdition(start: start, end: end, expires: expires, expiresAt: expiresAt)
    }

    private static func pointOf(_ element: JSONTree) -> ChartPoint? {
        guard let pair = element.arrayValue, pair.count >= 2, let lon = number(pair[0]), let lat = number(pair[1]) else { return nil }
        return ChartPoint(lon: lon, lat: lat)
    }

    // `[0-9]{4}-(0[1-9]|1[0-2])-[0-9]{2}`, matched whole, spelled by hand over ASCII (never `\d`, which takes every script's digits).
    private static func isDate(_ text: String) -> Bool {
        let units = Array(text.utf16)
        guard units.count == 10, units[4] == 0x2D, units[7] == 0x2D else { return false }
        let digit = { (unit: UInt16) in unit >= 0x30 && unit <= 0x39 }
        guard [0, 1, 2, 3, 5, 6, 8, 9].allSatisfy({ digit(units[$0]) }) else { return false }
        let month = (units[5] - 0x30) * 10 + (units[6] - 0x30)
        return month >= 1 && month <= 12
    }

    private static func text(_ value: JSONTree?) -> String? { value?.stringValue }

    private static func number(_ value: JSONTree?) -> Double? {
        guard let number = value?.numberValue?.value, number.isFinite else { return nil }
        return number
    }

    private static func integer(_ value: JSONTree?) -> Int? {
        guard let number = number(value), number == number.rounded(.toNearestOrEven), number >= -1e9, number <= 1e9 else { return nil }
        return Int(number)
    }
}

/// What the map knows of the FAA's charts: `live`, or nil for ArcGIS, and what that build holds once it has come.
///
/// Android's `VfrChartSource` beside it (a seam of flows over the server and the device's memory) is the app's to port with its
/// network client, not this module's.
public struct VfrCharts: Hashable, Sendable {
    public var live: VfrLive?
    public var coverage: ChartCoverage?

    public init(live: VfrLive? = nil, coverage: ChartCoverage? = nil) {
        self.live = live
        self.coverage = coverage
    }
}

/// How many levels finer than its zoom a screen draws the charts from (the web's `levelsFiner`; docs/VFR_CHARTS.md §8, *A dense
/// screen*): 1 where a point is more than about one pixel across, else 0. A tile holds its size in pixels, no more (Mapbox's `@2x`
/// hold twice that), so drawn over 512 points it is spread over 1,536 pixels on a phone of scale 3; the next level, over 256, gives
/// each tile pixel half as many. From √2 that level is the nearer of the two to one for one.
public func levelsFiner(_ density: Double) -> Int { density >= 2.0.squareRoot() ? 1 : 0 }

/// The instant of a time as the server writes it (`2026-12-24T09:01Z`, with no seconds, which `Instant.parse` refuses), in
/// milliseconds since 1970; nil for anything else.
///
/// Java's `OffsetDateTime.parse` (`ISO_OFFSET_DATE_TIME`), by hand, because Foundation's ISO parser differs from it in what it takes
/// (seconds required, no lower-case `t`, its calendar Julian before 1582). What it takes, as java.time does:
/// - case-insensitive throughout, so `t` and `z` do;
/// - the year in exactly four ASCII digits, or with `+` and five to ten, or with `-` and four to ten (never `-0000`);
/// - the month and day two digits each, the day within its month on the proleptic Gregorian calendar (ResolverStyle.STRICT);
/// - `HH:mm`, then optionally `:ss`, then optionally `.` and up to nine digits (none at all included), hours 00 to 23;
/// - the offset leniently: `Z`, or a sign, two hour digits, and optionally `:mm` and then `:ss`, at most 18 hours.
///
/// **Not like Kotlin**: an instant too far off for a 64-bit count of milliseconds (a year past about 292 million) throws Java's
/// `ArithmeticException`, which the Kotlin does not catch, so it would end the app; here it is nil, like any other time it cannot read.
public func chartInstant(_ text: String) -> Int64? {
    var reader = TimeReader(Array(text.utf16))
    return reader.instant()
}

private struct TimeReader {
    let units: [UInt16]
    var at = 0

    init(_ units: [UInt16]) { self.units = units }

    mutating func instant() -> Int64? {
        guard let year = year(), take(0x2D), let month = digits(2), take(0x2D), let day = digits(2), take(0x54, or: 0x74),
            let hour = digits(2), take(0x3A), let minute = digits(2)
        else { return nil }
        var second: Int64 = 0
        var nanos: Int64 = 0
        if take(0x3A) {
            guard let read = digits(2) else { return nil }
            second = read
            if take(0x2E) {
                var count = 0
                var value: Int64 = 0
                while count < 9, at < units.count, isDigit(units[at]) {
                    value = value * 10 + Int64(units[at] - 0x30)
                    at += 1
                    count += 1
                }
                for _ in count..<9 { value *= 10 }
                nanos = value
            }
        }
        guard let offset = offset(), at == units.count else { return nil }
        guard (1...12).contains(month), day >= 1, day <= daysIn(month, of: year), hour <= 23, minute <= 59, second <= 59 else {
            return nil
        }
        let seconds = epochDay(year, month, day) * 86_400 + hour * 3600 + minute * 60 + second - offset
        // Instant.toEpochMilli: Math.multiplyExact and addExact, which throw past 64 bits.
        let (millis, over) = seconds.multipliedReportingOverflow(by: 1000)
        if over { return nil }
        let (total, overToo) = millis.addingReportingOverflow(nanos / 1_000_000)
        return overToo ? nil : total
    }

    private func isDigit(_ unit: UInt16) -> Bool { unit >= 0x30 && unit <= 0x39 }

    private mutating func take(_ unit: UInt16, or other: UInt16? = nil) -> Bool {
        guard at < units.count, units[at] == unit || units[at] == other else { return false }
        at += 1
        return true
    }

    private mutating func digits(_ count: Int) -> Int64? {
        guard at + count <= units.count else { return nil }
        var value: Int64 = 0
        for unit in units[at..<(at + count)] {
            guard isDigit(unit) else { return nil }
            value = value * 10 + Int64(unit - 0x30)
        }
        at += count
        return value
    }

    // `appendValue(YEAR, 4, 10, SignStyle.EXCEEDS_PAD)`, parsed strictly: four digits with no sign, more than four only after `+`,
    // and a negative year never zero; then YEAR's own range, ±999,999,999.
    private mutating func year() -> Int64? {
        var sign: Int64 = 0
        if take(0x2B) { sign = 1 } else if take(0x2D) { sign = -1 }
        var count = 0
        var value: Int64 = 0
        while count < 10, at < units.count, isDigit(units[at]) {
            value = value * 10 + Int64(units[at] - 0x30)
            at += 1
            count += 1
        }
        if count < 4 { return nil }
        if sign == 0 && count > 4 { return nil }
        if sign == 1 && count <= 4 { return nil }
        if sign == -1 && value == 0 { return nil }
        if value > 999_999_999 { return nil }
        return sign == -1 ? -value : value
    }

    // `appendOffsetId()` under `parseLenient()`, its pattern `+HH:MM:ss` read as `+HH` then optional `:mm` then optional `:ss`.
    private mutating func offset() -> Int64? {
        if take(0x5A, or: 0x7A) { return 0 }
        let negative: Bool
        if take(0x2B) { negative = false } else if take(0x2D) { negative = true } else { return nil }
        guard let hours = digits(2) else { return nil }
        var minutes: Int64 = 0
        var seconds: Int64 = 0
        if colonAndTwoDigits() {
            at += 1
            minutes = digits(2)!
            if colonAndTwoDigits() {
                at += 1
                seconds = digits(2)!
            }
        }
        guard hours <= 18, minutes <= 59, seconds <= 59 else { return nil }
        let total = hours * 3600 + minutes * 60 + seconds
        guard total <= 18 * 3600 else { return nil }
        return negative ? -total : total
    }

    // Java's `parseDigits` for the offset's minute and second: taken only when the colon and both digits are there; otherwise
    // nothing is taken, and the text left over refuses the whole time.
    private func colonAndTwoDigits() -> Bool {
        at + 2 < units.count && units[at] == 0x3A && isDigit(units[at + 1]) && isDigit(units[at + 2])
    }

    private func isLeap(_ year: Int64) -> Bool { (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 }

    private func daysIn(_ month: Int64, of year: Int64) -> Int64 {
        switch month {
        case 2: isLeap(year) ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    // Days from 1970-01-01 on the proleptic Gregorian calendar (java.time's LocalDate.toEpochDay).
    private func epochDay(_ year: Int64, _ month: Int64, _ day: Int64) -> Int64 {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }
}
