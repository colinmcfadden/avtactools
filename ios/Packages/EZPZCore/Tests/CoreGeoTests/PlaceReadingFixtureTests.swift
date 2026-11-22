import CoreJS
import CoreModel
import CoreTesting
import Testing

@testable import CoreGeo

/// Held to contracts/fixtures/coords/reading.json, which records what the web's target field (utils/targetReading.js) offers.
/// Android's PlaceReadingFixtureTest, case for case.
struct PlaceReadingFixtureTests {
    let fixture: JSONObject
    let tolerance: Double

    init() throws {
        fixture = try Fixtures.load("coords/reading.json")
        tolerance = fixture["toleranceDeg"]!.double
    }

    @Test func offersWhatTheWebOffersForEveryTextChoicesAndSentence() {
        let cases = fixture.cases("read")
        #expect(cases.count >= 60, "the fixture shrank to \(cases.count)")
        let misses = cases.compactMap { c -> String? in
            let text = c["text"]!.string
            let expected = c["expected"]!.object
            let actual = PlaceSearch.read(text)
            let message = expected["message"]!.optionalString
            let choices = expected.cases("choices")
            var problems: [String] = []
            if actual.message != message { problems.append("message '\(actual.message ?? "nil")' vs '\(message ?? "nil")'") }
            if actual.choices.count != choices.count {
                problems.append("\(actual.choices.count) choices vs \(choices.count)")
            } else {
                for (i, (a, e)) in zip(actual.choices, choices).enumerated() {
                    if abs(a.lat - e["lat"]!.double) > tolerance { problems.append("[\(i)] lat \(a.lat) vs \(e["lat"]!.double)") }
                    if abs(a.lon - e["lon"]!.double) > tolerance { problems.append("[\(i)] lon \(a.lon) vs \(e["lon"]!.double)") }
                    if a.mgrs != e["mgrs"]!.string { problems.append("[\(i)] mgrs \(a.mgrs) vs \(e["mgrs"]!.string)") }
                    if a.latLong != e["latLong"]!.string { problems.append("[\(i)] latLong \(a.latLong) vs \(e["latLong"]!.string)") }
                    if a.how != e["how"]!.string { problems.append("[\(i)] how \(a.how) vs \(e["how"]!.string)") }
                }
            }
            return problems.isEmpty ? nil : "'\(text)': \(problems.joined(separator: "; "))"
        }
        #expect(misses.isEmpty, "\(misses.count) of \(cases.count) differ: \(misses)")
    }

    @Test func ordersTheReadingsNearestTheMapFirstAsTheWebDoes() {
        for c in fixture.cases("nearestFirst") {
            let text = c["text"]!.string
            let near = c["near"]!.objectValue.map { LatLon(lat: $0["lat"]!.double, lon: $0["lon"]!.double) }
            let expected = c["expected"]!.array.map(\.string)
            #expect(
                PlaceSearch.nearestFirst(PlaceSearch.read(text).choices, near).map(\.latLong) == expected,
                "\(text) near \(String(describing: near))")
        }
    }

    @Test func writesALatAndLongInWordsAsTheWebDoes() {
        for c in fixture.cases("formatLatLong") {
            let lat = c["lat"]!.double
            let lon = c["lon"]!.double
            #expect(PlaceSearch.formatLatLong(lat, lon) == c["expected"]!.string, "\(lat), \(lon)")
        }
    }

    @Test func readsBackTheLatAndLongItWrites() {
        for choice in fixture.cases("read").flatMap({ PlaceSearch.read($0["text"]!.string).choices }) {
            let again = PlaceSearch.read(choice.latLong).choices
            #expect(again.count == 1, "\(choice.latLong)")
            if let again = again.first {
                #expect(abs(again.lat - choice.lat) < 6e-6 && abs(again.lon - choice.lon) < 6e-6, "\(choice.latLong)")
            }
        }
    }

    @Test func aByteOrderMarkInsideAGridIsASpaceAsJavaScriptsWhitespaceHasIt() {
        #expect(PlaceSearch.read("16S GC\u{FEFF}28864 55349").choices.map(\.mgrs) == ["16S GC 28864 55349"])
    }
}
