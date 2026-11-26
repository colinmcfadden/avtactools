import CoreModel
import Foundation
import Testing

@testable import CoreMap

/// Android's MapProjectionTest, case for case.
struct MapProjectionTests {
    let here = LatLon(lat: 34.78, lon: -84.08)

    func view(zoom: Double = 17.0, bearing: Double = 0.0, density: Double = 1.0, center: LatLon? = nil) -> MapProjection {
        MapProjection(
            camera: CameraState(center: center ?? here, zoom: zoom, bearingDegrees: bearing), widthPx: 1000.0, heightPx: 2000.0,
            density: density)
    }

    @Test func theCamerasCentreIsTheMiddleOfTheView() {
        let p = view().toScreen(here)
        #expect(near(p.x, 500.0, 1e-6))
        #expect(near(p.y, 1000.0, 1e-6))
    }

    @Test func northIsUpAndEastIsRight() {
        let centre = view().toScreen(here)
        let north = view().toScreen(LatLon(lat: here.lat + 0.0005, lon: here.lon))
        let east = view().toScreen(LatLon(lat: here.lat, lon: here.lon + 0.0005))
        #expect(north.y < centre.y)
        #expect(near(north.x, centre.x, 1e-6))
        #expect(east.x > centre.x)
        #expect(near(east.y, centre.y, 1e-6))
    }

    @Test func aDegreeOfLongitudeIsTheWorldOver360AtTheEquatorAndTheSameEverywhereAlongAParallel() {
        let zoom = 10.0
        let v = view(zoom: zoom, center: LatLon(lat: 0.0, lon: 0.0))
        let worldPoints = 512.0 * pow(2.0, zoom)
        let east = v.toScreen(LatLon(lat: 0.0, lon: 1.0))
        #expect(near(east.x - 500.0, worldPoints / 360, 1e-6))
    }

    @Test func itAgreesWithTheMetersPerPixelTheRestOfTheMapUses() {
        let v = view(zoom: 17.0)
        // 100 m east, at this latitude, is 100 / metersPerPixel pixels (to the precision of a flat step).
        let metres = 100.0
        let degrees = metres / (111_319.49 * cos(here.lat * Double.pi / 180))
        let east = v.toScreen(LatLon(lat: here.lat, lon: here.lon + degrees))
        #expect(near(east.x - 500.0, v.metersToPixels(metres, here.lat), 0.05))
        #expect(near(v.metersToPixels(metres, here.lat), metres / metersPerPixel(here.lat, 17.0), 1e-9))
    }

    @Test func densityScalesTheWorldAndNothingElse() {
        let one = view(density: 1.0).toScreen(LatLon(lat: here.lat, lon: here.lon + 0.001))
        let three = view(density: 3.0).toScreen(LatLon(lat: here.lat, lon: here.lon + 0.001))
        #expect(near(three.x - 500.0, (one.x - 500.0) * 3, 1e-6))
        #expect(near(view(density: 3.0).metersToPixels(10.0, 34.0), 3 * view(density: 1.0).metersToPixels(10.0, 34.0), 1e-9))
    }

    @Test func facingEastPutsTheEastOfTheCentreAtTheTop() {
        let v = view(bearing: 90.0)
        let centre = v.toScreen(here)
        let east = v.toScreen(LatLon(lat: here.lat, lon: here.lon + 0.0005))
        let north = v.toScreen(LatLon(lat: here.lat + 0.0005, lon: here.lon))
        #expect(east.y < centre.y)
        #expect(near(east.x, centre.x, 1e-6))
        #expect(north.x < centre.x)  // and north is now to the left
    }

    @Test func aTurnedMapIsTheSameMapTurned() {
        let flat = view().toScreen(LatLon(lat: here.lat + 0.001, lon: here.lon + 0.002))
        let turned = view(bearing: 180.0).toScreen(LatLon(lat: here.lat + 0.001, lon: here.lon + 0.002))
        #expect(near(turned.x, 500.0 - (flat.x - 500.0), 1e-6))
        #expect(near(turned.y, 1000.0 - (flat.y - 1000.0), 1e-6))
    }

    @Test func theScreenAndTheGroundAgreeBothWays() {
        for bearing in [0.0, 37.0, 90.0, 215.0, 359.0] {
            for zoom in [4.0, 12.0, 17.0, 20.0] {
                let v = view(zoom: zoom, bearing: bearing, density: 2.625)
                let p = LatLon(lat: here.lat + 0.0003, lon: here.lon - 0.0004)
                let screen = v.toScreen(p)
                let back = v.toLatLon(screen.x, screen.y)
                if zoom >= 12.0 {
                    #expect(near(back.lat, p.lat, 1e-9), "lat at zoom \(zoom) bearing \(bearing)")
                    #expect(near(back.lon, p.lon, 1e-9), "lon at zoom \(zoom) bearing \(bearing)")
                }
            }
        }
    }

    @Test func aTapAtTheMiddleOfTheViewIsTheCamerasCentre() {
        let at = view(bearing: 123.0).toLatLon(500.0, 1000.0)
        #expect(near(at.lat, here.lat, 1e-9))
        #expect(near(at.lon, here.lon, 1e-9))
    }

    @Test func acrossTheDateLineAPointIsPlacedOnTheNearerSide() {
        let v = view(zoom: 10.0, center: LatLon(lat: 10.0, lon: 179.99))
        let across = v.toScreen(LatLon(lat: 10.0, lon: -179.99))
        #expect(across.x > 500.0)
        #expect(across.x - 500.0 < 500.0)  // near, not a world away
    }

    @Test func pixelsBetweenTwoPoints() {
        let v = view()
        let b = LatLon(lat: here.lat, lon: here.lon + 0.001)
        #expect(near(v.pixelsBetween(here, b), v.toScreen(b).x - v.toScreen(here).x, 1e-9))
    }

    @Test func withTheBottomReservedForTheSheetTheCamerasCentreIsTheMiddleOfWhatIsAboveIt() {
        let v = MapProjection(
            camera: CameraState(center: here, zoom: 17.0, paddingBottomPx: 300.0), widthPx: 1000.0, heightPx: 2000.0, density: 1.0)
        let p = v.toScreen(here)
        #expect(near(p.x, 500.0, 1e-6))
        #expect(near(p.y, 850.0, 1e-6))  // (2000 - 300) / 2, not 1000
        let back = v.toLatLon(p.x, p.y)
        #expect(near(back.lat, here.lat, 1e-9))
        #expect(near(back.lon, here.lon, 1e-9))
    }

    @Test func withTheTopReservedTooTheCentreIsTheMiddleOfWhatIsBetweenThem() {
        // The search bar covers the top and the sheet the bottom: the crosshair is half way between the two, as the map puts the
        // camera's target.
        let v = MapProjection(
            camera: CameraState(center: here, zoom: 17.0, paddingTopPx: 200.0, paddingBottomPx: 600.0), widthPx: 1000.0,
            heightPx: 2000.0, density: 1.0)
        #expect(near(v.toScreen(here).y, 800.0, 1e-6))  // 200 + (2000 - 200 - 600) / 2
        let back = v.toLatLon(500.0, 800.0)
        #expect(near(back.lat, here.lat, 1e-9))
        #expect(near(back.lon, here.lon, 1e-9))
    }

    @Test func reservingTheBottomMovesThePictureByHalfOfItAndDoesNotChangeItsScale() {
        let plain = MapProjection(camera: CameraState(center: here, zoom: 17.0), widthPx: 1000.0, heightPx: 2000.0)
        let padded = MapProjection(
            camera: CameraState(center: here, zoom: 17.0, paddingBottomPx: 300.0), widthPx: 1000.0, heightPx: 2000.0, density: 1.0)
        let a = LatLon(lat: here.lat + 0.0004, lon: here.lon + 0.0003)
        #expect(near(padded.toScreen(a).y, plain.toScreen(a).y - 150.0, 1e-6))
        #expect(near(padded.toScreen(a).x, plain.toScreen(a).x, 1e-6))
        #expect(near(padded.pixelsBetween(here, a), plain.pixelsBetween(here, a), 1e-6))
    }
}
