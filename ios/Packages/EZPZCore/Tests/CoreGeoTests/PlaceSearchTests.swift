import CoreModel
import Testing

@testable import CoreGeo

/// Android's PlaceSearchTest, case for case.
struct PlaceSearchTests {
    private func found(_ text: String) -> (at: LatLon, description: String)? {
        if case .found(let at, let description) = PlaceSearch.resolve(text) { (at, description) } else { nil }
    }

    private func notUnderstood(_ text: String) -> String? {
        if case .notUnderstood(let message) = PlaceSearch.resolve(text) { message } else { nil }
    }

    @Test func aGridInAnySpacingAndCaseIsFoundWhereTheGridIs() throws {
        for typed in ["16S GD 66993 52949", "16sgd6699352949", "  16S  GD  66993  52949 "] {
            let place = try #require(found(typed), "\(typed)")
            #expect(abs(place.at.lat - 34.783817) < 0.0001 && abs(place.at.lon - -84.08219) < 0.0001, "\(typed)")
            #expect(place.description == "MGRS grid, to 1 m")
        }
    }

    @Test func aLessExactGridIsTheMiddleOfItsSquare() throws {
        let place = try #require(found("16S GD 66 52"))
        #expect(abs(place.at.lat - 34.78) < 0.05)
    }

    @Test func coordinatesInTheFormatsCrewsPasteAreUnderstoodAndThePersonIsToldWhich() throws {
        let decimal = try #require(found("34.545678, -84.123456"))
        #expect(abs(decimal.at.lat - 34.545678) <= 1e-9)
        #expect(abs(decimal.at.lon - -84.123456) <= 1e-9)
        #expect(decimal.description == "Read as decimal degrees")
        let ddm = try #require(found("34°32.740'N 084°07.407'W"))
        #expect(abs(ddm.at.lat - 34.5456667) <= 1e-6)
        #expect(ddm.description == "Read as degrees and decimal minutes")
    }

    @Test func whatCannotBeReadSaysSo() {
        #expect(notUnderstood("") != nil)
        #expect(notUnderstood("   ") != nil)
        #expect(notUnderstood("somewhere nice") != nil)
        #expect(notUnderstood("99.0, 200.0") != nil)
        #expect(notUnderstood("16S ZZ 123") != nil)
        #expect(notUnderstood("somewhere nice") == PlaceSearch.Messages.unreadable)
        #expect(notUnderstood("  ") == PlaceSearch.Messages.empty)
    }

    @Test func anUnsignedPairIsNotGuessedAtWhereThereIsNoListToChooseFrom() {
        #expect(notUnderstood("34 84") == PlaceSearch.Messages.eastOrWestTyped)
        #expect(found("34 -84") != nil)
    }
}
