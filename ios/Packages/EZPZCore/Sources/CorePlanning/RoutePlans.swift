import CoreJS
import CoreModel

/// Plan settings seeded from a profile and restored from older saves (`defaultRoutePlan` and `ensureRoutePlan`).
public enum RoutePlans {
    // A null where a value is required reads as the default; the web would fail later on it, and refusing the whole saved route is
    // worse.
    private static let json = ModelJSON(ignoreUnknownKeys: true, coerceInputValues: true)

    /// A fresh plan seeded from an aircraft profile; without one, the UH-60L values the planner has always used.
    public static func `default`(_ profile: AircraftProfile? = nil) -> RoutePlan { RoutePlan.forAircraft(profile ?? .fallback) }

    /// Backfills plan defaults on a route restored from an older save, and migrates the pre-inline `tot` anchor (a single TOT field)
    /// into a per-point clock.
    ///
    /// Like the web's `{ ...defaultRoutePlan(), ...route.plan }`, saved values win over defaults key by key, and a `perPoint` in the
    /// save replaces the default's wholesale. A clock already on a point wins over a leftover `tot`.
    ///
    /// Throws where kotlinx's decoder throws (a value of the wrong kind, `airspeed: "fast"`): Kotlin's is unchecked, Swift's is typed.
    ///
    /// - Parameters:
    ///   - points: the route's points, to find the first AMPS point when `tot` names none
    ///   - savedPlan: the route's `plan` as it was saved, or nil for a route without one
    public static func ensure(_ points: [RoutePoint], _ savedPlan: JSONObject?) throws(ModelJSONError) -> RoutePlan {
        // A key given twice keeps its first place and its last value, as `LinkedHashMap.put` over the defaults does.
        let defaults = json.encodeObject(Self.default()).entries.map { ($0.key, $0.value) }
        let saved = (savedPlan?.entries ?? []).map { ($0.key, $0.value) }
        var plan = try json.decode(RoutePlan.self, from: .object(JSONObject(defaults + saved).removing("tot")))

        let tot = savedPlan?["tot"]?.objectValue
        if let time = primitiveText(tot?["time"]), !time.isEmpty, !plan.perPoint.values.contains(where: { !($0.clock ?? "").isEmpty }) {
            if let date = primitiveText(tot?["date"]), !date.isEmpty { plan.date = date }
            let named = primitiveText(tot?["pointId"]).flatMap { $0.isEmpty ? nil : $0 }
            if let anchorId = named ?? RouteCalc.planPoints(points).first?.id {
                var anchored = plan.perPoint[anchorId] ?? PointOverride()
                anchored.clock = time
                plan.perPoint = plan.perPoint.setting(anchorId, anchored)
            }
        }
        return plan
    }
}
