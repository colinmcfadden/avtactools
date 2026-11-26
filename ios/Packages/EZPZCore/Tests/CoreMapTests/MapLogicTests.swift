import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CoreMap

/// Android's MapLogicTest, case for case: the base maps and their style documents, the readout, the scale and the same view.
struct MapLogicTests {
    let token = "pk.test-token"

    let live = VfrLive(
        build: "2026-10-29.1", tiles: "https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp",
        coverage: "/charts/vfr/2026-10-29.1/coverage.json", tileSize: 512, minZoom: 5, maxZoom: 14, effective: "2026-10-29T09:01Z",
        expires: "2026-12-24T09:01Z", bounds: ChartBox(-125.0, 24.0, -66.0, 50.0),
        fallback: [ChartBox(-158.9, 18.6, -154.7, 22.6), ChartBox(165.9, 12.9, 180.0, 21.9)])

    func styleDoc(_ style: MapBaseStyle, density: Double = 1.0) -> JSONObject { parsed(MapStyles.styleJson(style, density: density)) }

    func source(_ doc: JSONObject, _ name: String) -> JSONObject { doc["sources"]!.object[name]!.object }

    func boundsOf(_ doc: JSONObject, _ name: String) -> [Double]? { source(doc, name)["bounds"].map { $0.array.map(\.double) } }

    // -- Base styles ------------------------------------------------------------------------------------------------------------

    @Test func theVfrMapIsTheLiveBuildItsZoomsAndSizeTheConfigsWithinItsBounds() {
        let doc = styleDoc(MapStyles.byId(MapStyles.vfrId, token, charts: live))
        let base = source(doc, "base")
        #expect(base["tiles"]!.array.map(\.string) == [live.tiles])
        #expect(base["tileSize"]!.numberValue!.text == "512")
        #expect(base["minzoom"]!.numberValue!.text == "5")
        #expect(base["maxzoom"]!.numberValue!.text == "14")
        #expect(boundsOf(doc, "base") == [-125.0, 24.0, -66.0, 50.0])
    }

    @Test func onADenseScreenTheChartsAreLaidOverHalfThePointsOneLevelFinerAndEveryOtherTileIsAsItWas() {
        let vfr = MapStyles.byId(MapStyles.vfrId, token, charts: live)
        let dense = source(styleDoc(vfr, density: 3.0), "base")
        #expect(dense["tileSize"]!.numberValue!.text == "256")
        #expect(dense["minzoom"]!.numberValue!.text == "5")
        #expect(dense["maxzoom"]!.numberValue!.text == "14")
        #expect(source(styleDoc(vfr, density: 1.0), "base")["tileSize"]!.numberValue!.text == "512")
        #expect(source(styleDoc(vfr, density: 3.0), "fallback-0")["tileSize"]!.numberValue!.text == "256")
        #expect(source(styleDoc(MapStyles.byId("satellite", token), density: 3.0), "base")["tileSize"]!.numberValue!.text == "512")
        #expect(source(styleDoc(MapStyles.byId(MapStyles.vfrId, token), density: 3.0), "base")["tileSize"]!.numberValue!.text == "256")
    }

    @Test func aScreenDrawsALevelFinerOnlyFromTheDensityWhereThatLevelIsTheNearerOfTheTwoAndNeverTwo() {
        #expect([1.0, 1.33, 1.5, 2.0, 3.0, 4.0].map(levelsFiner) == [0, 0, 1, 1, 1, 1])
    }

    @Test func eachRegionWithNoChartOfOursIsArcgisWithinItsOwnBoundsUnderTheChartLayer() {
        let doc = styleDoc(MapStyles.byId(MapStyles.vfrId, token, charts: live))
        #expect(boundsOf(doc, "fallback-0") == [-158.9, 18.6, -154.7, 22.6])
        #expect(boundsOf(doc, "fallback-1") == [165.9, 12.9, 180.0, 21.9])
        let under = source(doc, "fallback-1")
        #expect(under["tiles"]!.array.map(\.string).first!.hasPrefix("https://tiles.arcgis.com/"))
        #expect(under["tiles"]!.array.count == 1)
        #expect(under["tileSize"]!.numberValue!.text == "256")
        // On top is `base`, which the MGRS grid and everything else is put above.
        #expect(doc["layers"]!.array.map { $0.object["id"]!.string } == ["background", "fallback-0", "fallback-1", "base"])
    }

    @Test func withNoBuildAtAllTheVfrMapIsArcgisEverywhereAndHasNoBounds() {
        let doc = styleDoc(MapStyles.byId(MapStyles.vfrId, token))
        #expect(source(doc, "base")["tiles"]!.array[0].string.hasPrefix("https://tiles.arcgis.com/"))
        #expect(boundsOf(doc, "base") == nil)
        #expect(doc["sources"]!.object.keys == ["base"])
    }

    @Test func aBuildChangesTheVfrMapAloneAndKeepsItsIdAndTheNameADiagramSaves() {
        let with = MapStyles.available(token, charts: live)
        #expect(with.map(\.id) == ["satellite", "topo", MapStyles.vfrId])
        #expect(Array(MapStyles.available(token).prefix(2)) == Array(with.prefix(2)))
        #expect(with.last!.label == "VFR")
    }

    @Test func theThreeBaseMapsHaveTheIdsASavedDiagramUses() {
        #expect(MapStyles.available(token).map(\.id) == ["satellite", "topo", "vfr-sectional"])
    }

    @Test func satelliteIsDrawnFromMapboxs512PointRetinaTilesWithTheTokenInTheUrl() {
        let satellite = MapStyles.byId("satellite", token)
        #expect(satellite.tiles == "https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/512/{z}/{x}/{y}@2x?access_token=\(token)")
        #expect(satellite.tileSize == 512)
        #expect(satellite.needsMapboxToken)
        #expect(
            MapStyles.byId("topo", token).tiles
                == "https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/512/{z}/{x}/{y}@2x?access_token=\(token)")
    }

    @Test func theFaaChartOnlyHasLevels8To12AndArcgisPutsYBeforeX() {
        let vfr = MapStyles.byId("vfr-sectional", token)
        #expect(vfr.minZoom == 8)
        #expect(vfr.maxZoom == 12)
        #expect(vfr.tiles.hasSuffix("/tile/{z}/{y}/{x}"))
        #expect(!vfr.needsMapboxToken)
    }

    @Test func aStaleOrUnknownIdFallsBackToTheFirstMapThatCanBeDrawn() {
        #expect(MapStyles.byId("some-removed-style", token).id == "satellite")
        #expect(MapStyles.byId(nil, token).id == "satellite")
        #expect(MapStyles.defaultId == "satellite")
    }

    @Test func withNoTokenOnlyTheFaaChartIsOfferedAndAskingForSatelliteGetsItRatherThanABlankMap() {
        #expect(MapStyles.available(nil).map(\.id) == ["vfr-sectional"])
        #expect(MapStyles.byId("satellite", nil).id == "vfr-sectional")
    }

    @Test func aTokenThatIsNotAPublicOneIsNotUsed() {
        #expect(MapStyles.available("sk.a-secret-token").map(\.id) == ["vfr-sectional"])
        #expect(MapStyles.available("").map(\.id) == ["vfr-sectional"])
    }

    /// Swift's: `hasPrefix` matches Characters, and a `.` followed by a combining mark is one Character, so it would refuse a token
    /// Kotlin's `startsWith` takes. The prefix is matched by UTF-16 units, as Kotlin matches it.
    @Test func aTokenIsTakenByItsUtf16UnitsNotItsCharacters() {
        #expect(MapStyles.available("pk.\u{301}abc").map(\.id) == ["satellite", "topo", "vfr-sectional"])
    }

    @Test func aStyleDocumentIsARasterSourceWithTheRightZoomRangeOverANavyBackground() {
        let doc = styleDoc(MapStyles.byId("vfr-sectional", token))
        #expect(doc["version"]!.numberValue!.text == "8")
        let base = source(doc, "base")
        #expect(base["type"]!.string == "raster")
        #expect(base["minzoom"]!.numberValue!.text == "8")
        #expect(base["maxzoom"]!.numberValue!.text == "12")
        #expect(base["tileSize"]!.numberValue!.text == "256")
        let layers = doc["layers"]!.array
        #expect(layers.map { $0.object["id"]!.string } == ["background", "base"])
        #expect(layers[0].object["paint"]!.object["background-color"]!.string == "#092137")
    }

    /// The document's keys are in the order the Kotlin builds them, and a whole double is JavaScript's text (`-125`), not Kotlin's
    /// (`-125.0`): the same value to MapLibre.
    @Test func theStyleDocumentsKeysAreKotlinsOrderAndItsNumbersJavaScriptsText() {
        let doc = styleDoc(MapStyles.byId(MapStyles.vfrId, token, charts: live))
        #expect(doc.keys == ["version", "name", "sources", "layers"])
        #expect(doc["sources"]!.object.keys == ["fallback-0", "fallback-1", "base"])
        #expect(source(doc, "base").keys == ["type", "tiles", "tileSize", "minzoom", "maxzoom", "attribution", "bounds"])
        #expect(source(doc, "base")["bounds"]!.array.map { $0.numberValue!.text } == ["-125", "24", "-66", "50"])
        #expect(MapStyles.styleJson(MapStyles.byId("topo", token)).contains("\"attribution\":\"&copy; <a href=\\\"https://www.mapbox.com"))
    }

    // -- The readout under the crosshair --------------------------------------------------------------------------------------------

    @Test func theReadoutGivesTheGridPyGeodesyGivesForEveryNamedPlaceInTheFixtures() throws {
        let named = try Fixtures.load("mgrs/forward.json")["named"]!.array
        #expect(named.count >= 3)
        for entry in named {
            let o = entry.object
            let readout = Readout.of(LatLon(lat: o["lat"]!.double, lon: o["lon"]!.double))
            #expect(readout.mgrs == o["mgrs"]!.string, "\(o["name"]!.string)")
        }
    }

    @Test func theReadoutSaysTheDegreesTooAndSaysNoGridWhereThereIsNone() {
        #expect(Readout.of(LatLon(lat: 34.783817, lon: -84.08219)).latLon == "34.78382, -84.08219")
        #expect(Readout.of(LatLon(lat: 89.9, lon: 10.0)).mgrs == nil)  // the polar caps are not UTM
    }

    @Test func scaleShrinksWithLatitudeAndHalvesWithEachZoomLevel() {
        #expect(near(metersPerPixel(0.0, 0.0), 78_271.516964, 1e-6))
        #expect(near(metersPerPixel(0.0, 11.0), metersPerPixel(0.0, 10.0) / 2, 1e-9))
        #expect(near(metersPerPixel(60.0, 10.0), metersPerPixel(0.0, 10.0) * 0.5, 1e-9))  // cos 60 degrees is one half
        #expect((0.2...0.3).contains(metersPerPixel(34.78, 18.0)))  // about a quarter of a metre per point at LZ zoom
    }

    @Test func twoCamerasAreTheSameViewWhenNothingVisibleMoved() {
        let a = CameraState(center: LatLon(lat: 34.0, lon: -84.0), zoom: 15.0)
        var b = a
        b.center = LatLon(lat: 34.00000001, lon: -84.0)
        #expect(a.sameView(b))
        var c = a
        c.zoom = 15.5
        #expect(!a.sameView(c))
        var d = a
        d.center = LatLon(lat: 34.001, lon: -84.0)
        #expect(!a.sameView(d))
    }

    @Test func theChromesSizesAreTheAndroidFramesInPoints() {
        #expect(MapChrome.searchBottom == 64.0)
        #expect(MapChrome.rowBottom == 56.0)
        #expect(MapChrome.sheetTopLimit == 68.0)
        #expect(MapChrome.buttonsTop == 72.0)
        #expect(MapPadding() == MapPadding(top: 0.0, bottom: 0.0))
    }
}
