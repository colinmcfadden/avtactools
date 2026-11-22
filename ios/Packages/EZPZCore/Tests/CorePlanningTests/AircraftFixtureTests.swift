import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/aircraft.json, which records what the web's aircraftProfiles.js answers.
struct AircraftFixtureTests {
    let fixture: JSONObject
    let tolerance: Double
    let byKey: [(key: String, profile: AircraftProfile)]

    init() throws {
        fixture = try Fixtures.load("planning/aircraft.json")
        tolerance = fixture["tolerance"]!.numberValue!.value
        byKey = fixture["profiles"]!.arrayValue!.map { ($0["key"]!.stringValue!, AircraftProfile.normalize($0["profile"]!.objectValue!)) }
    }

    func profile(_ key: String) -> AircraftProfile { byKey.first { same($0.key, key) }!.profile }
    func bySlug(_ slug: String) -> AircraftProfile { byKey.first { same($0.profile.slug, slug) }!.profile }
    var everyProfile: [AircraftProfile] { byKey.map(\.profile) }

    func close(_ expected: Double, _ actual: Double) -> Bool { abs(expected - actual) <= tolerance * max(1, abs(expected)) }

    @Test func theBuiltInProfileIsTheWebsFallback() {
        let differences = JSONCompare.differences(
            fixture["fallbackProfile"]!, ModelJSON().encode(AircraftProfile.fallback), tolerance: tolerance)
        #expect(differences.isEmpty, "\(differences)")
    }

    @Test func normalizingFillsInWhatIsMissingAsTheWebDoes() {
        for c in fixture["normalize"]!.arrayValue! {
            let raw = c["raw"]!
            let actual = AircraftProfile.normalize(raw.objectValue)
            let differences = JSONCompare.differences(c["expected"]!, ModelJSON().encode(actual), tolerance: tolerance)
            #expect(differences.isEmpty, "normalize(\(raw)): \(differences)")
        }
    }

    @Test func geometryMatchesTheWeb() {
        for g in fixture["geometry"]!.arrayValue! {
            let key = g["key"]!.stringValue!
            let p = profile(key)
            func check(_ name: String, _ actual: Double) {
                #expect(close(g[name]!.numberValue!.value, actual), "\(key).\(name): expected \(g[name]!), got \(actual)")
            }
            check("rotorRadiusM", AircraftGeometry.rotorRadiusM(p))
            check("rotorRadiusFt", AircraftGeometry.rotorRadiusFt(p))
            check("tipClearanceM", AircraftGeometry.tipClearanceM(p))
            check("tipClearanceFt", AircraftGeometry.tipClearanceFt(p))
            check("centerSpacingM", AircraftGeometry.centerSpacingM(p))
            check("centerSpacingFt", AircraftGeometry.centerSpacingFt(p))
            check("spotSizeSqFt", AircraftGeometry.spotSizeSqFt(p))
        }
    }

    @Test func capacityMatchesTheWebIncludingAtTheEdgesOfACell() {
        let cases = fixture["capacity"]!.arrayValue!
        #expect(cases.count > 50)
        for c in cases {
            // The fixture writes a non-finite area (NaN, infinity) as null.
            let area = c["areaSqFt"]!.numberValue?.value ?? .nan
            let expected = Int(c["expected"]!.numberValue!.value)
            let actual =
                switch c["fn"]!.stringValue! {
                case "calculateUH60Capacity": AircraftGeometry.calculateUH60Capacity(area)
                default: AircraftGeometry.capacityForArea(area, profile(c["profile"]!.stringValue!))
                }
            #expect(expected == actual, "\(c)")
        }
    }

    @Test func separationAndTheAlertThresholdMatchTheWebExactly() {
        let cases = fixture["separation"]!.arrayValue!
        // 4 profiles make 10 pairs (each with itself too), at 7 distances around the limit.
        #expect(cases.count == 70)
        for c in cases {
            let a = bySlug(c["a"]!.stringValue!)
            let b = bySlug(c["b"]!.stringValue!)
            let d = c["centerDistanceFt"]!.numberValue!.value
            let pair = AircraftGeometry.pairSeparation(a, b)
            #expect(close(c["radiiFt"]!.numberValue!.value, pair.radiiFt), "radii \(c)")
            #expect(close(c["requiredClearanceFt"]!.numberValue!.value, pair.requiredClearanceFt), "clearance \(c)")
            #expect(close(c["minCenterDistanceFt"]!.numberValue!.value, pair.minCenterDistanceFt), "min centre \(c)")
            #expect(close(c["edgeGapFt"]!.numberValue!.value, AircraftGeometry.edgeGapFt(d, a, b)), "edge gap \(c)")
            // The alert flips a hundredth of a foot either side of the limit; no tolerance on the verdict.
            #expect(c["violation"]!.boolValue! == AircraftGeometry.isSeparationViolation(d, a, b), "violation \(c)")
        }
    }

    @Test func findingAProfileByIdOrSlugMatchesTheWeb() {
        for c in fixture["lookups"]!["findProfile"]!.arrayValue! {
            let ref = primitiveText(c["ref"])
            #expect(
                primitiveText(c["expected"]) == AircraftLookup.findProfile(everyProfile, ref)?.slug,
                "findProfile(\(String(describing: ref)))")
        }
    }

    @Test func aPlacedAircraftFallsBackToTheMissionDefaultAndThenTheUH60L() {
        for c in fixture["lookups"]!["profileForAsset"]!.arrayValue! {
            let profiles = c["profiles"]!.stringValue! == "all" ? everyProfile : []
            let defaultProfile = primitiveText(c["defaultProfile"]).map(profile)
            let actual = AircraftLookup.profileForAsset(primitiveText(c["assetProfileId"]), profiles, defaultProfile)
            #expect(c["expected"]!.stringValue! == actual.slug, "\(c)")
        }
    }

    @Test func anImportedAirframeIsMatchedByDescriptionThenByDesignation() {
        for c in fixture["lookups"]!["matchProfileToAircraft"]!.arrayValue! {
            let aircraft = c["aircraft"]!
            let actual = AircraftLookup.matchProfileToAircraft(
                everyProfile, primitiveText(aircraft["description"]), primitiveText(aircraft["designation"]))
            #expect(primitiveText(c["expected"]) == actual?.slug, "\(c)")
        }
    }
}
