import Testing

@testable import CoreGeo

/// Android has no test of its own for these (core-planning's route fixtures hold them); these say what each measures.
struct GreatCircleTests {
    @Test func aDegreeOfLatitudeIsSixtyNauticalMilesOnThePlannersSphere() {
        #expect(abs(GreatCircle.distanceNm(34, -84, 35, -84) - 3440.065 * Double.pi / 180) < 1e-9)
        #expect(GreatCircle.distanceNm(34, -84, 34, -84) == 0)
    }

    @Test func feetAreTheWebsOtherSphere() {
        #expect(abs(GreatCircle.distanceFeet(34, -84, 35, -84) - 20_902_231.0 * Double.pi / 180) < 1e-6)
    }

    @Test func aCourseIsTrueAndFrom0UpTo360() {
        #expect(abs(GreatCircle.trueCourseDeg(34, -84, 35, -84)) < 1e-9)
        #expect(abs(GreatCircle.trueCourseDeg(34, -84, 33, -84) - 180) < 1e-9)
        #expect(abs(GreatCircle.trueCourseDeg(0, -84, 0, -85) - 270) < 1e-9)
    }
}
