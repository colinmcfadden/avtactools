import CoreModel
import Testing

@testable import CorePlanning

struct MissionRoutesTests {
    func point(_ id: String, _ lat: Double, _ lon: Double, _ kind: String = RoutePoint.kindAmps) -> RoutePoint {
        RoutePoint(id: id, lat: lat, lon: lon, kind: kind, ptType: kind == RoutePoint.kindAmps ? "turn" : nil, name: id.uppercased())
    }

    func route(_ name: String, _ points: RoutePoint..., plan: RoutePlan = RoutePlan(), elevations: OrderedMap<Double> = [:]) -> MissionRoute
    {
        MissionRoute(name: name, segmentId: "seg-\(name)", points: points, plan: plan, elevations: elevations)
    }

    func ids() -> () -> String {
        var n = 0
        return {
            n += 1
            return "sketch-\(n)"
        }
    }

    @Test func eachRouteComesAcrossWithItsPointsItsPlanAndItsGroundElevations() throws {
        let plan = RoutePlan(date: "2026-10-03")
        let mission = Mission(routes: [route("NEPTUNE", point("a", 34, -84), point("b", 34.1, -84.1), plan: plan, elevations: ["a": 910])])
        let made = MissionRoutes.toSketchRoutes(mission, newId: ids())
        #expect(made.count == 1)
        let first = try #require(made.first)
        #expect(first.name == "NEPTUNE")
        #expect(first.points.map(\.id) == ["a", "b"])
        #expect(first.plan == plan)
        #expect(first.elevations == ["a": 910])
        #expect(first.visible)
        // The web tells a sketch from an imported route by this prefix.
        #expect(first.id.hasPrefix("sketch-"))
    }

    @Test func routesTakeThePalettesColoursInTurnAfterAnyAlreadyInUse() {
        let mission = Mission(routes: [route("A", point("a", 1, 1)), route("B", point("b", 1, 1)), route("C", point("c", 1, 1))])
        #expect(MissionRoutes.toSketchRoutes(mission, newId: ids()).map(\.color) == Array(RouteColors.palette.prefix(3)))
        #expect(
            MissionRoutes.toSketchRoutes(mission, newId: ids(), takenColors: [RouteColors.palette[0]]).map(\.color)
                == Array(RouteColors.palette.dropFirst().prefix(3)))
    }

    @Test func eachRouteGetsAnIdOfItsOwn() {
        let mission = Mission(routes: [route("A", point("a", 1, 1)), route("B", point("b", 1, 1))])
        #expect(Set(MissionRoutes.toSketchRoutes(mission, newId: ids()).map(\.id)).count == 2)
    }

    @Test func aRouteWithNoPointsHasNothingToDrawAndIsLeftOutAndABlankNameIsCalledByItsPlace() {
        let mission = Mission(routes: [route("EMPTY"), route("   ", point("a", 1, 1)), route(" TRIMMED ", point("b", 1, 1))])
        // The empty one still counts in the numbering.
        #expect(MissionRoutes.toSketchRoutes(mission, newId: ids()).map(\.name) == ["ROUTE 2", "TRIMMED"])
    }

    @Test func aMissionWithNoRoutesMakesNone() {
        #expect(MissionRoutes.toSketchRoutes(Mission(routes: []), newId: ids()).isEmpty)
    }

    @Test func namedPointsAreTheDesignatedOnesNotTheShapingPoints() {
        let mission = Mission(routes: [
            route("A", point("a", 1, 1), point("s", 1, 1.1, RoutePoint.kindShaping), point("b", 1, 1.2)), route("B", point("c", 2, 2)),
        ])
        #expect(MissionRoutes.namedPoints(MissionRoutes.toSketchRoutes(mission, newId: ids())) == 3)
    }

    @Test func theExtentIsTheMiddleOfTheBoxRoundEveryPointAndItsLongerSide() throws {
        let mission = Mission(routes: [route("A", point("a", 34, -84), point("b", 34, -83.9)), route("B", point("c", 34.2, -84))])
        let extent = try #require(MissionRoutes.extentOf(MissionRoutes.toSketchRoutes(mission, newId: ids())))
        #expect(abs(extent.center.lat - 34.1) <= 1e-9)
        #expect(abs(extent.center.lon - -83.95) <= 1e-9)
        // 0.2 degrees of latitude is 22.2 km; 0.1 degree of longitude at 34.1 north is 9.2 km: the north-south side is the longer.
        #expect(abs(extent.spanMeters - 22_240) <= 150)
    }

    @Test func aSinglePointHasAnExtentOfNoLength() throws {
        let extent = try #require(
            MissionRoutes.extentOf(MissionRoutes.toSketchRoutes(Mission(routes: [route("A", point("a", 34, -84))]), newId: ids())))
        #expect(abs(extent.spanMeters) <= 1e-9)
        #expect(abs(extent.center.lat - 34) <= 1e-9)
    }

    @Test func noPointsNoExtent() {
        #expect(MissionRoutes.extentOf([]) == nil)
    }

    @Test func aSavedMissionKeepsEveryRouteItsSegmentAndTheColoursItsSummaryKept() {
        let mission = Mission(routes: [route("EMPTY"), route(" A ", point("a", 1, 1)), route("B", point("b", 1, 1))])
        let made = MissionRoutes.toMissionSketchRoutes(mission, colors: ["#123456", nil], newId: ids())
        #expect(made.map(\.name) == ["EMPTY", "A", "B"])
        #expect(made.map(\.segmentId) == ["seg-EMPTY", "seg- A ", "seg-B"])
        // A colour the summary did not keep is the first of the palette the others have not taken.
        #expect(made.map(\.color) == ["#123456", RouteColors.palette[0], RouteColors.palette[1]])
    }

    @Test func theFileIsTheSameWhateverTheNamesAndColours() {
        let mission = Mission(routes: [route("A", point("a", 1, 1)), route("B", point("b", 1, 1))])
        var routes = MissionRoutes.toMissionSketchRoutes(mission, colors: [], newId: ids())
        let before = MissionRoutes.toMissionRoutes(routes)
        routes[0].name = "RENAMED"
        routes[0].color = "#000000"
        routes[1].visible = false
        #expect(MissionRoutes.sameFileContent(before, MissionRoutes.toMissionRoutes(routes)))
        routes[1].points[0].lat = 2
        #expect(!MissionRoutes.sameFileContent(before, MissionRoutes.toMissionRoutes(routes)))
        #expect(!MissionRoutes.sameFileContent(before, Array(before.prefix(1))))
    }
}
