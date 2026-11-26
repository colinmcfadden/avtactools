import CoreJS
import CoreTesting
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/graphicScale.json, written by the web's scaleRule.js. Both answers are rounded by the web, so
/// they are compared exactly.
struct GraphicScaleTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("planning/graphicScale.json") }

    @Test func everyReferenceZoomIsTheWebs() throws {
        let cases = try #require(fixture["references"]?.arrayValue)
        #expect(cases.count == 13)
        for c in cases {
            #expect(GraphicScale.referenceZoom(c["boundary"]) == c["zoom"]?.numberValue?.value, "\(c["name"]!.stringValue!)")
        }
    }

    @Test func everyScaleIsTheWebs() throws {
        let cases = try #require(fixture["scales"]?.arrayValue)
        #expect(cases.count > 60)
        for c in cases {
            let zoom = c["zoom"]?.numberValue?.value ?? .nan
            let got =
                if let reference = c["reference"]?.numberValue?.value { GraphicScale.scale(zoom: zoom, reference: reference) } else {
                    GraphicScale.scale(zoom: zoom)
                }
            #expect(got.scale == c["scale"]?.numberValue?.value, "zoom \(zoom)")
            #expect(got.hidden == c["hidden"]?.boolValue, "zoom \(zoom)")
        }
    }
}
