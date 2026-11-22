import Testing

@testable import CorePlanning

struct RouteColorsTests {
    @Test func thePaletteIsTheWebsEightColoursInOrder() {
        #expect(RouteColors.palette.count == 8)
        #expect(RouteColors.palette.first == "#FF453A")
        #expect(RouteColors.palette.last == "#FF375F")
        #expect(Set(RouteColors.palette).count == RouteColors.palette.count)
    }

    @Test func theFirstRouteIsRedAndEachNextOneTakesTheNextColour() {
        #expect(RouteColors.next([String]()) == "#FF453A")
        #expect(RouteColors.next(["#FF453A"]) == "#0A84FF")
        #expect(RouteColors.next(["#FF453A", "#0A84FF"]) == "#32D74B")
    }

    @Test func aColourThatWasFreedIsUsedAgainBeforeANewOne() {
        #expect(RouteColors.next(["#0A84FF", "#32D74B"]) == "#FF453A")
        #expect(RouteColors.next(["#FF453A", "#32D74B"]) == "#0A84FF")
    }

    @Test func aColourIsRecognisedHoweverItsHexIsWritten() {
        #expect(RouteColors.next(["#ff453a"]) == "#0A84FF")
    }

    @Test func aColourOfItsOwnIsNotOneOfThePalettesSoItTakesNothingAway() {
        #expect(RouteColors.next(["#123456"]) == "#FF453A")
    }

    @Test func whenAllEightAreUsedItStartsOverByHowManyThereAre() {
        #expect(RouteColors.next(RouteColors.palette) == "#FF453A")
        #expect(RouteColors.next(RouteColors.palette + ["#123456"]) == "#0A84FF")
    }
}
