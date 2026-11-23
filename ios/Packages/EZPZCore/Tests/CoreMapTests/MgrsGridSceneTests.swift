import CoreGeo
import CoreJS
import CoreModel
import Foundation
import Testing

@testable import CoreMap

/// Android's MgrsGridSceneTest, case for case: the bounds and scale the grid is asked for, its labels on the screen, and its lines for
/// MapLibre.
struct MgrsGridSceneTests {
    let density = 2.625
    var width: Double { 412 * density }
    var height: Double { 800 * density }

    func mercator(_ lat: Double) -> Double { log(tan(Double.pi / 4 + lat * Double.pi / 180 / 2)) }

    @Test func facingNorthTheGridsViewIsTheMapFromItsTopToTheSheetAGridPointBeingAScreenPoint() throws {
        let camera = CameraState(
            center: LatLon(lat: 34.62, lon: -84.18), zoom: 14.0, paddingTopPx: 64 * density, paddingBottomPx: 300 * density)
        let view = try #require(MgrsGridScene.viewOf(camera, width, height, density))
        #expect(!view.turned)
        let projection = MapProjection(camera: camera, widthPx: width, heightPx: height, density: density)
        let topLeft = projection.toLatLon(0.0, 0.0)
        let bottomRight = projection.toLatLon(width, height - 300 * density)
        #expect(near(view.bounds.north, topLeft.lat, 1e-9))
        #expect(near(view.bounds.west, topLeft.lon, 1e-9))
        #expect(near(view.bounds.south, bottomRight.lat, 1e-9))
        #expect(near(view.bounds.east, bottomRight.lon, 1e-9))
        // The grid's frame is as wide in its units as the map is in points: its 70-point gap is 70 points on the screen.
        let middle = (2 * atan(exp((mercator(view.bounds.north) + mercator(view.bounds.south)) / 2)) - Double.pi / 2) * 180 / Double.pi
        let pxPerDeg = 6378137 * cos(middle * Double.pi / 180) / view.metersPerPixel * Double.pi / 180
        #expect(near((view.bounds.east - view.bounds.west) * pxPerDeg, 412.0, 1e-6))
        #expect(view.options.pad == MgrsGridScene.pad)
    }

    @Test func acrossTheAntimeridianTheBoundsRunPast180AsTheGridExpects() throws {
        let camera = CameraState(center: LatLon(lat: -17.0, lon: 179.99), zoom: 9.0)
        let view = try #require(MgrsGridScene.viewOf(camera, width, height, density, coveredBottomPx: 0.0))
        #expect(view.bounds.east > 180)
        #expect(view.bounds.west < view.bounds.east)
        let grid = MgrsGridScene.gridFor(view)
        #expect(grid.lines.contains { line in line.points.contains { $0.lon > 180 } })
    }

    @Test func turnedTheGridCoversTheBoxRoundTheViewAndDrawsNoValuesAlongItsEdgesWhichAreNotTheScreens() throws {
        let north = CameraState(center: LatLon(lat: 34.62, lon: -84.18), zoom: 14.0)
        var turned = north
        turned.bearingDegrees = 30.0
        let flat = try #require(MgrsGridScene.viewOf(north, width, height, density, coveredBottomPx: 0.0))
        let view = try #require(
            MgrsGridScene.viewOf(
                turned, width, height, density, coveredBottomPx: 0.0, avoidPx: [GridBox(x0: 0.0, y0: 0.0, x1: 100.0, y1: 100.0)]))
        #expect(view.turned)
        #expect(view.bounds.east - view.bounds.west > flat.bounds.east - flat.bounds.west)
        #expect(view.options.avoid.isEmpty)
        let projection = MapProjection(camera: turned, widthPx: width, heightPx: height, density: density)
        for (x, y) in [(0.0, 0.0), (width, 0.0), (0.0, height), (width, height)] {
            let corner = projection.toLatLon(x, y)
            #expect((view.bounds.south - 1e-9...view.bounds.north + 1e-9).contains(corner.lat))
            #expect((view.bounds.west - 1e-9...view.bounds.east + 1e-9).contains(corner.lon))
        }
        #expect(MgrsGridScene.gridFor(flat).labels.contains { $0.edge != nil })
        #expect(MgrsGridScene.gridFor(view).labels.allSatisfy { $0.edge == nil })
    }

    @Test func turnedTheSquaresAndZonesAreLabelledOnTheScreenClearOfWhatFloatsOverTheMap() throws {
        for bearing in [30.0, 45.0, -120.0] {
            for zoom in [12.0, 7.5, 4.0] {
                let camera = CameraState(center: LatLon(lat: 34.62, lon: -84.18), zoom: zoom, bearingDegrees: bearing)
                let button = GridBox(x0: width - 64 * density, y0: 140 * density, x1: width - 16 * density, y1: 400 * density)
                let view = try #require(MgrsGridScene.viewOf(camera, width, height, density, coveredBottomPx: 0.0, avoidPx: [button]))
                let labels = MgrsGridScene.gridFor(view).labels
                #expect(!labels.isEmpty, "labelled at \(bearing), zoom \(zoom)")
                let projection = MapProjection(camera: camera, widthPx: width, heightPx: height, density: density)
                for label in labels {
                    let at = projection.toScreen(LatLon(lat: label.lat, lon: label.lon))
                    #expect((0.0...width).contains(at.x) && (0.0...height).contains(at.y), "\(label.text) at \(at), \(bearing) degrees")
                    #expect(
                        !((button.x0...button.x1).contains(at.x) && (button.y0...button.y1).contains(at.y)),
                        "\(label.text) under the button")
                }
            }
        }
    }

    func round6(_ value: Double) -> Double { (value * 1e6).rounded(.toNearestOrAwayFromZero) / 1e6 }

    @Test func whatFloatsOverTheMapIsKeptOffInPointsWithTheWebsMarginRoundIt() throws {
        let camera = CameraState(center: LatLon(lat: 34.62, lon: -84.18), zoom: 14.0)
        let view = try #require(
            MgrsGridScene.viewOf(
                camera, width, height, density, avoidPx: [GridBox(x0: 16 * density, y0: 8 * density, x1: 396 * density, y1: 64 * density)]
            ))
        #expect(
            view.options.avoid.map { GridBox(x0: round6($0.x0), y0: round6($0.y0), x1: round6($0.x1), y1: round6($0.y1)) }
                == [GridBox(x0: 12.0, y0: 4.0, x1: 400.0, y1: 68.0)])
        // Covered along the top, the eastings go to the bottom edge instead.
        let labels = MgrsGridScene.gridFor(view).labels
        #expect(labels.contains { $0.edge == "bottom" })
        #expect(!labels.contains { $0.edge == "top" })
    }

    @Test func aMapWithNoSizeHasNoGrid() {
        let camera = CameraState(center: LatLon(lat: 34.62, lon: -84.18), zoom: 14.0, paddingBottomPx: 900 * density)
        #expect(MgrsGridScene.viewOf(camera, 0.0, height, density) == nil)
        #expect(MgrsGridScene.viewOf(camera, width, height, density) == nil)  // the sheet covers it all
    }

    /// Kotlin's `max(0.0, covered)` and `minOf` are Java's, so a NaN in the camera is carried into the bounds rather than a corner
    /// winning over it, and the grid draws nothing for them.
    @Test func aCameraThatIsNotANumberGivesBoundsThatAreNot() throws {
        let camera = CameraState(center: LatLon(lat: .nan, lon: -84.18), zoom: 14.0)
        let view = try #require(MgrsGridScene.viewOf(camera, width, height, density))
        #expect(view.bounds.north.isNaN && view.bounds.south.isNaN)
        #expect(MgrsGridScene.gridFor(view).lines.isEmpty)
    }

    @Test func eachLineIsAFeatureCarryingItsKindInLongitudeAndLatitude() throws {
        let camera = CameraState(center: LatLon(lat: 34.62, lon: -84.18), zoom: 12.0)
        let grid = MgrsGridScene.gridFor(try #require(MgrsGridScene.viewOf(camera, width, height, density)))
        let doc = parsed(MgrsGridScene.geoJson(grid))
        #expect(doc.keys == ["type", "features"])
        let features = doc["features"]!.array
        #expect(features.count == grid.lines.count)
        let first = features[0].object
        #expect(first["properties"]!.object["kind"]!.string == grid.lines[0].kind)
        let point = first["geometry"]!.object["coordinates"]!.array[0].array
        #expect(point[0].double == grid.lines[0].points[0].lon)
        #expect(point[1].double == grid.lines[0].points[0].lat)
    }

    @Test func aLinesValueSitsJustInsideItsEdgeAndAZoneOrSquareCentredOnItsPoint() {
        func label(_ edge: String?) -> GridLabel {
            GridLabel(
                kind: edge == nil ? "square" : "easting", edge: edge, text: "66", zone: 16, value: nil, lat: 0.0, lon: 0.0,
                box: GridBox(x0: 0.0, y0: 0.0, x1: 0.0, y1: 0.0))
        }
        let at = ScreenPoint(x: 100.0, y: 200.0)
        #expect(MgrsGridScene.pillTopLeft(label("top"), at, 40.0, 20.0, 10.0) == ScreenPoint(x: 80.0, y: 210.0))
        #expect(MgrsGridScene.pillTopLeft(label("bottom"), at, 40.0, 20.0, 10.0) == ScreenPoint(x: 80.0, y: 170.0))
        #expect(MgrsGridScene.pillTopLeft(label("left"), at, 40.0, 20.0, 10.0) == ScreenPoint(x: 110.0, y: 190.0))
        #expect(MgrsGridScene.pillTopLeft(label("right"), at, 40.0, 20.0, 10.0) == ScreenPoint(x: 50.0, y: 190.0))
        #expect(MgrsGridScene.pillTopLeft(label(nil), at, 40.0, 20.0, 10.0) == ScreenPoint(x: 80.0, y: 190.0))
    }
}
