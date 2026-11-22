import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CoreGeo

/// Held to contracts/fixtures/mgrs/grid.json, the web's mgrsGrid.js run over the awkward views: every spacing, Norway and Svalbard, the
/// equator, the antimeridian, several world copies, covered edges, the caps and the views that draw nothing. Android's MgrsGridTest,
/// with its allowances.
///
/// Everything is compared as the web wrote it, but for what Darwin's maths library and a browser's may decide differently in a
/// double's last digit (AGENTS.md §11):
/// - a line's polyline is sampled until it is within a quarter of a pixel of its curve, which could tip one sample's test the other
///   way, so one with another count of points passes only if every point of each lies within a hundredth of a pixel of the other (the
///   same count is compared point for point, to `pointDeg`: a line's end is found on its edge to a millimetre, so the two may land a
///   millimetre apart);
/// - a line's label is placed where its line crosses the edge, found to 1e-10 degrees, so its box is compared to a thousandth of a
///   pixel;
/// - the 100 km squares are labelled largest first, and squares mirrored across a zone boundary (16 and 17 either side of 84 W) have
///   equal areas in exact arithmetic, so their order is the last digit's: the labels are compared as a set, which still fails if a
///   different order placed a different label.
struct MgrsGridTests {
    // About a centimetre: a line's end is found to a millimetre either side.
    private static let pointDeg = 1e-7

    let cases: [JSONObject]

    init() throws { cases = try Fixtures.load("mgrs/grid.json").cases("cases") }

    private func bounds(_ tree: JSONTree?) -> GridBounds? {
        tree?.objectValue.map {
            GridBounds(south: $0["south"]!.double, west: $0["west"]!.double, north: $0["north"]!.double, east: $0["east"]!.double)
        }
    }

    private func box(_ tree: JSONTree) -> GridBox {
        let a = tree.array
        return GridBox(x0: a[0].double, y0: a[1].double, x1: a[2].double, y1: a[3].double)
    }

    private func options(_ o: JSONObject) -> GridOptions {
        let defaults = GridOptions()
        return GridOptions(
            pad: o["pad"]?.double ?? defaults.pad,
            avoid: o["avoid"]?.array.map(box) ?? [],
            maxLines: o["maxLines"]?.int ?? defaults.maxLines,
            maxPoints: o["maxPoints"]?.int ?? defaults.maxPoints,
            maxLabels: o["maxLabels"]?.int ?? defaults.maxLabels
        )
    }

    // A label's place in the set's order, by what it says: the same key for the web's and the port's.
    private func key(_ kind: String, _ edge: String?, _ text: String, _ zone: Int?, _ value: Double?) -> [String] {
        [kind, edge ?? "", text, zone.map(String.init) ?? "", value.map(JsNumber.toText) ?? ""]
    }

    private func key(_ label: GridLabel) -> [String] { key(label.kind, label.edge, label.text, label.zone, label.value) }

    private func key(_ label: JSONObject) -> [String] {
        key(label["kind"]!.string, label["edge"]?.stringValue, label["text"]!.string, label["zone"]?.int, label["value"]?.double)
    }

    // Kotlin's String.compareTo, by UTF-16 units, the first that differs deciding.
    private func before(_ a: [String], _ b: [String]) -> Bool {
        for (x, y) in zip(a, b) where !x.utf16.elementsEqual(y.utf16) {
            return x.utf16.lexicographicallyPrecedes(y.utf16)
        }
        return false
    }

    // The result as the web writes it, but for each line's points, which are compared on their own.
    private func json(_ result: GridResult) -> JSONTree {
        let lines = result.lines.map { line -> JSONTree in
            var entries: [(String, JSONTree)] = [("kind", .string(line.kind))]
            if let zone = line.zone { entries.append(("zone", .number(Double(zone)))) }
            entries += [("axis", .string(line.axis)), ("value", .number(line.value))]
            return .object(JSONObject(entries))
        }
        let labels = result.labels.sorted { before(key($0), key($1)) }.map { label -> JSONTree in
            var entries: [(String, JSONTree)] = [("kind", .string(label.kind))]
            if let edge = label.edge { entries.append(("edge", .string(edge))) }
            entries.append(("text", .string(label.text)))
            if let zone = label.zone { entries.append(("zone", .number(Double(zone)))) }
            if let value = label.value { entries.append(("value", .number(value))) }
            entries += [("lat", .number(label.lat)), ("lon", .number(label.lon))]
            return .object(JSONObject(entries))
        }
        return .object(
            JSONObject([
                ("spacing", result.spacing.map { .number($0) } ?? .null),
                ("lines", .array(lines)),
                ("labels", .array(labels)),
                ("dropped", .array(result.dropped.map { .string($0) })),
            ]))
    }

    private func sortedLabels(_ expected: JSONObject) -> [JSONObject] {
        expected.cases("labels").sorted { before(key($0), key($1)) }
    }

    // The web's result with each line's points and each label's box taken out (compared on their own), and its labels in the set's
    // order.
    private func withoutPoints(_ expected: JSONObject) -> JSONTree {
        let lines = expected.cases("lines").map { JSONTree.object($0.removing("points")) }
        let labels = sortedLabels(expected).map { JSONTree.object($0.removing("box")) }
        return .object(expected.setting("lines", .array(lines)).setting("labels", .array(labels)))
    }

    // Metres-free closeness on the map: how far a point is from a polyline, in degrees scaled to pixels at the case's scale.
    private func offPolyline(_ p: LatLon, _ line: [LatLon], _ pxPerDeg: Double) -> Double {
        if line.count == 1 { return hypot(p.lon - line[0].lon, p.lat - line[0].lat) * pxPerDeg }
        var best = Double.infinity
        for i in 0..<(line.count - 1) {
            let a = line[i]
            let b = line[i + 1]
            let dx = b.lon - a.lon
            let dy = b.lat - a.lat
            let len = dx * dx + dy * dy
            let t = len == 0.0 ? 0.0 : max(0.0, min(1.0, ((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / len))
            best = min(best, hypot(p.lon - (a.lon + t * dx), p.lat - (a.lat + t * dy)) * pxPerDeg)
        }
        return best
    }

    private func pointsDiffer(_ name: String, _ i: Int, _ expected: [LatLon], _ actual: [LatLon], _ metersPerPixel: Double) -> [String] {
        if expected.count == actual.count {
            return expected.indices.compactMap { j in
                let e = expected[j]
                let a = actual[j]
                return abs(e.lat - a.lat) <= Self.pointDeg && abs(e.lon - a.lon) <= Self.pointDeg
                    ? nil : "\(name): line \(i) point \(j): \(e), got \(a)"
            }
        }
        // About a degree of latitude's width in pixels, generous for this check's purpose: a hundredth of a pixel either way.
        let pxPerDeg = 111320.0 / metersPerPixel
        let far = (expected.map { offPolyline($0, actual, pxPerDeg) } + actual.map { offPolyline($0, expected, pxPerDeg) }).max() ?? 0.0
        return far <= 0.01 ? [] : ["\(name): line \(i) has \(actual.count) points for \(expected.count), \(far) px apart"]
    }

    @Test func everyViewsGridIsTheWebs() {
        #expect(cases.count >= 30, "the fixture shrank to \(cases.count)")
        var problems: [String] = []
        var resampled = 0
        for c in cases {
            let name = c["name"]!.string
            let expected = c["expected"]!.object
            let metersPerPixel = c["metersPerPixel"]!.double
            let result = MgrsGrid.grid(bounds(c["bounds"]), metersPerPixel, options: options(c["options"]!.object))
            problems += JSONCompare.differences(withoutPoints(expected), json(result), tolerance: 1e-11, path: name)
            let wantBoxes = sortedLabels(expected).map { box($0["box"]!) }
            let gotBoxes = result.labels.sorted { before(key($0), key($1)) }.map(\.box)
            for (i, (want, got)) in zip(wantBoxes, gotBoxes).enumerated() {
                let off = [want.x0 - got.x0, want.y0 - got.y0, want.x1 - got.x1, want.y1 - got.y1].map(abs).max()!
                if off > 1e-3 { problems.append("\(name): label \(i) box \(want), got \(got)") }
            }
            let lines = expected["lines"]!.array
            if lines.count == result.lines.count {
                for (i, line) in lines.enumerated() {
                    let want = line["points"]!.array.map { LatLon(lat: $0[0]!.double, lon: $0[1]!.double) }
                    if want.count != result.lines[i].points.count { resampled += 1 }
                    problems += pointsDiffer(name, i, want, result.lines[i].points, metersPerPixel)
                }
            }
        }
        #expect(problems.isEmpty, "\(problems.count) differences: \(problems.prefix(20))")
        // Resampling is the allowance above, not the rule: if most lines took another count, something else is wrong.
        #expect(resampled <= 3, "\(resampled) polylines were sampled differently from the web's")
    }

    @Test func theSpacingIsTheFinestWhoseLinesStay70PixelsApartAndDigitsAreALinesValueWithinItsSquare() {
        #expect(MgrsGrid.gridSpacing(1.2) == 100.0)
        #expect(MgrsGrid.gridSpacing(14.2) == 1000.0)
        #expect(MgrsGrid.gridSpacing(14.3) == 10000.0)
        #expect(MgrsGrid.gridSpacing(1500.0) == nil)
        #expect(MgrsGrid.gridSpacing(0.0) == nil)
        #expect(MgrsGrid.gridSpacing(.nan) == nil)
        #expect(MgrsGrid.principalDigits(766000.0, 1000.0) == "66")
        #expect(MgrsGrid.principalDigits(706000.0, 1000.0) == "06")
        #expect(MgrsGrid.principalDigits(766000.0, 100.0) == "660")
        #expect(MgrsGrid.principalDigits(760000.0, 10000.0) == "6")
    }

    @Test func theEquatorIs0NeverMinus0HoweverItsMultiplesAreCountedSoItIsOneLineWhereverItIsLabelled() {
        let counted = MgrsGrid.multiplesIn(-30.0, 50.0, 100.0)
        #expect(counted == [0.0])
        #expect(counted.first?.sign == .plus, "-0.0 is a different key from 0.0 in the web's keys")
        #expect(Set((MgrsGrid.multiplesIn(-130.0, 50.0, 100.0) + counted).filter { $0 == 0.0 }) == [0.0])
    }

    @Test func javasMinAndMaxCarryANaNThrough() {
        // Swift.min(.infinity, .nan) is infinity; Java's, and so the grid's boxes, are NaN.
        #expect(javaMin(.infinity, .nan).isNaN)
        #expect(javaMax(-.infinity, .nan).isNaN)
        #expect(javaMin(0.0, -0.0).sign == .minus)
        #expect(javaMax(-0.0, 0.0).sign == .plus)
    }

    @Test func theHelpersTheGridUsesAgreeWithTheConverter() throws {
        let utm = try #require(MgrsConverter.toUtm(34.62, -84.18))
        let inZone = try #require(MgrsConverter.toUtmInZone(34.62, -84.18, utm.zone))
        #expect(utm == inZone)
        let back = try #require(MgrsConverter.fromUtm(utm.zone, southern: false, easting: utm.easting, northing: utm.northing))
        #expect(abs(back.lat - 34.62) <= 1e-9)
        #expect(abs(back.lon - -84.18) <= 1e-9)
        let grid = try #require(MgrsConverter.toMgrs(34.62, -84.18))
        #expect(MgrsConverter.squareLetters(utm.zone, utm.easting, utm.northing) == grid.square)
        #expect(MgrsConverter.centralMeridian(17) == -81.0)
        #expect(MgrsConverter.bandFor(34.62) == "S")
        #expect(MgrsConverter.bandFor(84.0) == "X")
        #expect(MgrsConverter.bandFor(.nan) == "C")  // Kotlin's toInt of NaN is 0, where Swift's Int(_:) would trap
        #expect(MgrsConverter.toUtmInZone(84.1, 0.0, 31) == nil)
        #expect(MgrsConverter.toUtmInZone(84.0, 0.0, 31) != nil)  // the top of band X is in it, as a grid line's end needs
        #expect(MgrsConverter.toUtm(84.0, 0.0) == nil)  // where toUtm's range stops short of it
        #expect(MgrsConverter.toUtmInZone(34.0, 0.0, 61) == nil)
        #expect(MgrsConverter.fromUtm(0, southern: false, easting: 500000.0, northing: 0.0) == nil)
        // Zone 60's east edge, +180, measured from the near side rather than wrapped to the far one.
        #expect(try #require(MgrsConverter.toUtmInZone(-17.0, 180.0, 60)).easting > 500000)
    }
}
