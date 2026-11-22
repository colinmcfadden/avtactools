import CoreJS

// Route planning settings and results, in the shape the web saves them (`route_data.plan` and the output of `computeRoutePlan`).
// Field names are the web's, camelCase, so a plan made on a tablet opens on the web and back.
//
// `type` and `ref` stay text rather than enums so a value a newer web release adds survives a round trip; the planner reads them
// with the same fall-through the web uses (anything that is not "ground" or "indicated" is true airspeed).

/// Airspeed reference: `ground`, `indicated` or `true`.
public struct Airspeed: Hashable, Sendable, ModelJSONCodable {
    public static let typeGround = "ground"
    public static let typeIndicated = "indicated"
    public static let typeTrue = "true"

    public var value: Double
    public var type: String

    public init(_ value: Double, _ type: String = Airspeed.typeGround) {
        self.value = value
        self.type = type
    }

    public static let serialNames = ["value", "type"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        self.init(try r.required("value", r.json.double), try r.optional("type", default: Airspeed.typeGround, r.json.string))
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("value", value)
        w.put("type", type)
    }
}

/// Altitude reference: `agl` or `msl`.
public struct AltitudeSetting: Hashable, Sendable, ModelJSONCodable {
    public static let refAgl = "agl"
    public static let refMsl = "msl"

    public var value: Double
    public var ref: String

    public init(_ value: Double, _ ref: String = AltitudeSetting.refAgl) {
        self.value = value
        self.ref = ref
    }

    public static let serialNames = ["value", "ref"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        self.init(try r.required("value", r.json.double), try r.optional("ref", default: AltitudeSetting.refAgl, r.json.string))
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("value", value)
        w.put("ref", ref)
    }
}

/// Wind as it is reported: the direction it blows FROM, in degrees true.
public struct Wind: Hashable, Sendable, ModelJSONCodable {
    public var dirTrue: Double
    public var speedKts: Double

    public init(dirTrue: Double = 0, speedKts: Double = 0) {
        self.dirTrue = dirTrue
        self.speedKts = speedKts
    }

    public static let serialNames = ["dirTrue", "speedKts"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        self.init(
            dirTrue: try r.optional("dirTrue", default: 0, r.json.double),
            speedKts: try r.optional("speedKts", default: 0, r.json.double)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("dirTrue", dirTrue)
        w.put("speedKts", speedKts)
    }
}

/// Values that apply to the leg *arriving at* one point. Plan values mean "to this point", which is how AMPS stores them. At most
/// one point carries a `clock`; it anchors every other point's clock time.
public struct PointOverride: Hashable, Sendable, ModelJSONCodable {
    public var altitude: AltitudeSetting?
    public var airspeed: Airspeed?
    public var wind: Wind?
    /// `HH:MM` or `HH:MM:SS`.
    public var clock: String?

    public init(altitude: AltitudeSetting? = nil, airspeed: Airspeed? = nil, wind: Wind? = nil, clock: String? = nil) {
        self.altitude = altitude
        self.airspeed = airspeed
        self.wind = wind
        self.clock = clock
    }

    public static let serialNames = ["altitude", "airspeed", "wind", "clock"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            altitude: try r.nullable("altitude", default: nil, j.object),
            airspeed: try r.nullable("airspeed", default: nil, j.object),
            wind: try r.nullable("wind", default: nil, j.object),
            clock: try r.nullable("clock", default: nil, j.string)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("altitude", model: altitude)
        w.put("airspeed", model: airspeed)
        w.put("wind", model: wind)
        w.put("clock", clock)
    }
}

/// Plan settings; defaults are the web's `defaultRoutePlan()` for the UH-60L.
public struct RoutePlan: Equatable, Sendable, ModelJSONCodable {
    public var aircraft: String
    /// The profile's slug, not its id, so a saved route survives a database change.
    public var aircraftProfile: String
    public var airspeed: Airspeed
    public var altitude: AltitudeSetting
    public var wind: Wind?
    public var tempC: Double?
    public var fuelFlowLbHr: Double?
    /// `YYYY-MM-DD`, the day every clock time falls on; empty means today.
    public var date: String
    public var perPoint: OrderedMap<PointOverride>
    /// Fields a newer release wrote that this one does not know, kept so saving does not drop them (`RouteSets`). Never part of the
    /// model's own JSON.
    public var extras: JSONObject

    public init(
        aircraft: String = "UH-60L Black Hawk",
        aircraftProfile: String = "uh60l",
        airspeed: Airspeed = Airspeed(100, Airspeed.typeGround),
        altitude: AltitudeSetting = AltitudeSetting(50, AltitudeSetting.refAgl),
        wind: Wind? = Wind(),
        tempC: Double? = 15,
        fuelFlowLbHr: Double? = 960,
        date: String = "",
        perPoint: OrderedMap<PointOverride> = [:],
        extras: JSONObject = JSONObject()
    ) {
        self.aircraft = aircraft
        self.aircraftProfile = aircraftProfile
        self.airspeed = airspeed
        self.altitude = altitude
        self.wind = wind
        self.tempC = tempC
        self.fuelFlowLbHr = fuelFlowLbHr
        self.date = date
        self.perPoint = perPoint
        self.extras = extras
    }

    /// `defaultRoutePlan(profile)`: a fresh plan seeded from an aircraft profile.
    public static func forAircraft(_ profile: AircraftProfile = .fallback) -> RoutePlan {
        RoutePlan(
            aircraft: profile.name,
            aircraftProfile: profile.slug,
            airspeed: Airspeed(profile.defaultAirspeedKts, profile.defaultAirspeedType),
            altitude: AltitudeSetting(profile.defaultAltitudeFt, profile.defaultAltitudeRef),
            wind: Wind(dirTrue: 0, speedKts: 0),
            tempC: 15,
            fuelFlowLbHr: profile.defaultFuelFlowLbHr,
            date: "",
            perPoint: [:]
        )
    }

    // By hand because JSONObject has no Equatable of its own; every field counts, `extras` too, as in Kotlin's data class.
    public static func == (a: RoutePlan, b: RoutePlan) -> Bool {
        Kotlin.same(a.aircraft, b.aircraft) && Kotlin.same(a.aircraftProfile, b.aircraftProfile) && a.airspeed == b.airspeed
            && a.altitude == b.altitude && a.wind == b.wind && a.tempC == b.tempC && a.fuelFlowLbHr == b.fuelFlowLbHr
            && Kotlin.same(a.date, b.date) && a.perPoint == b.perPoint && JSONTree.object(a.extras) == .object(b.extras)
    }

    public static let serialNames = [
        "aircraft", "aircraftProfile", "airspeed", "altitude", "wind", "tempC", "fuelFlowLbHr", "date", "perPoint",
    ]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        let d = RoutePlan()
        self.init(
            aircraft: try r.optional("aircraft", default: d.aircraft, j.string),
            aircraftProfile: try r.optional("aircraftProfile", default: d.aircraftProfile, j.string),
            airspeed: try r.optional("airspeed", default: d.airspeed, j.object),
            altitude: try r.optional("altitude", default: d.altitude, j.object),
            wind: try r.nullable("wind", default: d.wind, j.object),
            tempC: try r.nullable("tempC", default: d.tempC, j.double),
            fuelFlowLbHr: try r.nullable("fuelFlowLbHr", default: d.fuelFlowLbHr, j.double),
            date: try r.optional("date", default: d.date, j.string),
            perPoint: try r.optional("perPoint", default: d.perPoint) { (v) throws(ModelJSONError) in
                try j.map(v) { (e) throws(ModelJSONError) in try j.decode(PointOverride.self, from: e) }
            }
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("aircraft", aircraft)
        w.put("aircraftProfile", aircraftProfile)
        w.put("airspeed", model: airspeed)
        w.put("altitude", model: altitude)
        w.put("wind", model: wind)
        w.put("tempC", tempC)
        w.put("fuelFlowLbHr", fuelFlowLbHr)
        w.put("date", date)
        w.put("perPoint", models: perPoint)
    }
}

/// A point on a sketched route. Shaping points only bend the leg between route points.
public struct RoutePoint: Equatable, Sendable, ModelJSONCodable {
    public static let kindShaping = "shaping"
    /// A designated AMPS point: it becomes a real route point, and legs run between these.
    public static let kindAmps = "amps"

    /// The AMPS point id. A point without one cannot hold a per-point override, so it is left out of the plan.
    public var id: String?
    public var uiId: String?
    public var lat: Double
    public var lon: Double
    public var kind: String?
    public var ptType: String?
    public var name: String?
    /// Charted elevation (a snapped local point); authoritative over the DEM.
    public var chartElevationFt: Double?
    /// Ground elevation in metres, as a mission file's GPX records it. Imported points only.
    public var ele: Double?
    /// What the point's name says it is (`start`, `release`, `target`, `waypoint`). Imported points only.
    public var role: String?
    /// Fields a newer release wrote that this one does not know (`RouteSets`). Never part of the model's own JSON.
    public var extras: JSONObject

    public init(
        id: String? = nil,
        uiId: String? = nil,
        lat: Double,
        lon: Double,
        kind: String? = nil,
        ptType: String? = nil,
        name: String? = nil,
        chartElevationFt: Double? = nil,
        ele: Double? = nil,
        role: String? = nil,
        extras: JSONObject = JSONObject()
    ) {
        self.id = id
        self.uiId = uiId
        self.lat = lat
        self.lon = lon
        self.kind = kind
        self.ptType = ptType
        self.name = name
        self.chartElevationFt = chartElevationFt
        self.ele = ele
        self.role = role
        self.extras = extras
    }

    public static func == (a: RoutePoint, b: RoutePoint) -> Bool {
        Kotlin.same(a.id, b.id) && Kotlin.same(a.uiId, b.uiId) && a.lat == b.lat && a.lon == b.lon && Kotlin.same(a.kind, b.kind)
            && Kotlin.same(a.ptType, b.ptType) && Kotlin.same(a.name, b.name) && a.chartElevationFt == b.chartElevationFt
            && a.ele == b.ele && Kotlin.same(a.role, b.role) && JSONTree.object(a.extras) == .object(b.extras)
    }

    public static let serialNames = ["id", "uiId", "lat", "lon", "kind", "ptType", "name", "chartElevationFt", "ele", "role"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            id: try r.nullable("id", default: nil, j.string),
            uiId: try r.nullable("uiId", default: nil, j.string),
            lat: try r.required("lat", j.double),
            lon: try r.required("lon", j.double),
            kind: try r.nullable("kind", default: nil, j.string),
            ptType: try r.nullable("ptType", default: nil, j.string),
            name: try r.nullable("name", default: nil, j.string),
            chartElevationFt: try r.nullable("chartElevationFt", default: nil, j.double),
            ele: try r.nullable("ele", default: nil, j.double),
            role: try r.nullable("role", default: nil, j.string)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("id", id)
        w.put("uiId", uiId)
        w.put("lat", lat)
        w.put("lon", lon)
        w.put("kind", kind)
        w.put("ptType", ptType)
        w.put("name", name)
        w.put("chartElevationFt", chartElevationFt)
        w.put("ele", ele)
        w.put("role", role)
    }
}

public struct PlanLeg: Hashable, Sendable, ModelJSONCodable {
    public var fromId: String?
    public var toId: String?
    public var fromName: String
    public var toName: String
    public var distNm: Double
    public var courseTrueDeg: Double
    public var airspeed: Airspeed
    public var wind: Wind
    public var tasKts: Double?
    public var gsKts: Double?
    public var windCorrectionDeg: Double
    public var timeSec: Double?
    public var fuelLb: Double?

    public init(
        fromId: String?, toId: String?, fromName: String, toName: String, distNm: Double, courseTrueDeg: Double, airspeed: Airspeed,
        wind: Wind, tasKts: Double?, gsKts: Double?, windCorrectionDeg: Double, timeSec: Double?, fuelLb: Double?
    ) {
        self.fromId = fromId
        self.toId = toId
        self.fromName = fromName
        self.toName = toName
        self.distNm = distNm
        self.courseTrueDeg = courseTrueDeg
        self.airspeed = airspeed
        self.wind = wind
        self.tasKts = tasKts
        self.gsKts = gsKts
        self.windCorrectionDeg = windCorrectionDeg
        self.timeSec = timeSec
        self.fuelLb = fuelLb
    }

    public static let serialNames = [
        "fromId", "toId", "fromName", "toName", "distNm", "courseTrueDeg", "airspeed", "wind", "tasKts", "gsKts",
        "windCorrectionDeg", "timeSec", "fuelLb",
    ]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            fromId: try r.requiredNullable("fromId", j.string),
            toId: try r.requiredNullable("toId", j.string),
            fromName: try r.required("fromName", j.string),
            toName: try r.required("toName", j.string),
            distNm: try r.required("distNm", j.double),
            courseTrueDeg: try r.required("courseTrueDeg", j.double),
            airspeed: try r.required("airspeed", j.object),
            wind: try r.required("wind", j.object),
            tasKts: try r.requiredNullable("tasKts", j.double),
            gsKts: try r.requiredNullable("gsKts", j.double),
            windCorrectionDeg: try r.required("windCorrectionDeg", j.double),
            timeSec: try r.requiredNullable("timeSec", j.double),
            fuelLb: try r.requiredNullable("fuelLb", j.double)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("fromId", fromId)
        w.put("toId", toId)
        w.put("fromName", fromName)
        w.put("toName", toName)
        w.put("distNm", distNm)
        w.put("courseTrueDeg", courseTrueDeg)
        w.put("airspeed", model: airspeed)
        w.put("wind", model: wind)
        w.put("tasKts", tasKts)
        w.put("gsKts", gsKts)
        w.put("windCorrectionDeg", windCorrectionDeg)
        w.put("timeSec", timeSec)
        w.put("fuelLb", fuelLb)
    }
}

public struct PlanTotals: Hashable, Sendable, ModelJSONCodable {
    public var distNm: Double
    public var timeSec: Double?
    public var fuelLb: Double?

    public init(distNm: Double, timeSec: Double?, fuelLb: Double?) {
        self.distNm = distNm
        self.timeSec = timeSec
        self.fuelLb = fuelLb
    }

    public static let serialNames = ["distNm", "timeSec", "fuelLb"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        self.init(
            distNm: try r.required("distNm", r.json.double),
            timeSec: try r.requiredNullable("timeSec", r.json.double),
            fuelLb: try r.requiredNullable("fuelLb", r.json.double)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("distNm", distNm)
        w.put("timeSec", timeSec)
        w.put("fuelLb", fuelLb)
    }
}

/// A date and a time of day with no zone, as `java.time.LocalDateTime` holds one: what an AMPS file records beside a clock time.
/// Foundation's `Date` is an instant, which this is not. Only what the model carries; the planner works it out.
public struct LocalDateTime: Hashable, Sendable, Comparable, CustomStringConvertible {
    public var year: Int
    public var month: Int
    public var day: Int
    public var hour: Int
    public var minute: Int
    public var second: Int
    public var nanosecond: Int

    public init(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, nanosecond: Int = 0) {
        self.year = year
        self.month = month
        self.day = day
        self.hour = hour
        self.minute = minute
        self.second = second
        self.nanosecond = nanosecond
    }

    private var ordinal: [Int] { [year, month, day, hour, minute, second, nanosecond] }

    public static func < (a: LocalDateTime, b: LocalDateTime) -> Bool { a.ordinal.lexicographicallyPrecedes(b.ordinal) }

    /// ISO-8601 as `LocalDateTime.toString` writes it: `2026-03-01T14:05`, with seconds when there are any and a fraction when there
    /// is one.
    public var description: String {
        func two(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
        let y =
            abs(year) < 1000 ? (year < 0 ? "-" : "") + Kotlin.padStart(String(abs(year)), 4, "0") : (year > 9999 ? "+" : "") + String(year)
        var text = "\(y)-\(two(month))-\(two(day))T\(two(hour)):\(two(minute))"
        if second > 0 || nanosecond > 0 {
            text += ":\(two(second))"
            if nanosecond > 0 {
                let digits = Kotlin.padStart(String(nanosecond), 9, "0")
                let width = nanosecond % 1_000_000 == 0 ? 3 : nanosecond % 1000 == 0 ? 6 : 9
                text += "." + digits.prefix(width)
            }
        }
        return text
    }
}

/// One AMPS point in the computed plan, with the leg that arrives at it.
public struct PlanPoint: Hashable, Sendable, ModelJSONCodable {
    public var id: String?
    public var uiId: String
    public var name: String?
    public var ptType: String?
    public var lat: Double
    public var lon: Double
    public var ref: String
    public var value: Double
    public var mslFt: Double?
    public var aglFt: Double?
    public var groundFt: Double?
    public var airspeed: Airspeed?
    public var wind: Wind?
    public var legDistNm: Double?
    public var legCourseTrueDeg: Double?
    public var legGsKts: Double?
    public var legTimeSec: Double?
    public var legFuelLb: Double?
    /// Seconds since the first point; null once any earlier leg has no ground speed.
    public var elapsedSec: Double?
    /// Time of day at this point, `HH:MM:SS`, when a point carries a clock.
    public var clock: String?
    public var hasClock: Bool
    public var isTotAnchor: Bool
    /// The same moment with its date (the web's `clockTime`, a `Date`), which an AMPS file records; a route that runs past midnight
    /// is on the next day. Not part of the saved plan or the fixture, which hold only `clock`.
    public var clockTime: LocalDateTime?

    public init(
        id: String?, uiId: String, name: String?, ptType: String?, lat: Double, lon: Double, ref: String, value: Double,
        mslFt: Double?, aglFt: Double?, groundFt: Double?, airspeed: Airspeed?, wind: Wind?, legDistNm: Double?,
        legCourseTrueDeg: Double?, legGsKts: Double?, legTimeSec: Double?, legFuelLb: Double?, elapsedSec: Double?,
        clock: String?, hasClock: Bool, isTotAnchor: Bool, clockTime: LocalDateTime? = nil
    ) {
        self.id = id
        self.uiId = uiId
        self.name = name
        self.ptType = ptType
        self.lat = lat
        self.lon = lon
        self.ref = ref
        self.value = value
        self.mslFt = mslFt
        self.aglFt = aglFt
        self.groundFt = groundFt
        self.airspeed = airspeed
        self.wind = wind
        self.legDistNm = legDistNm
        self.legCourseTrueDeg = legCourseTrueDeg
        self.legGsKts = legGsKts
        self.legTimeSec = legTimeSec
        self.legFuelLb = legFuelLb
        self.elapsedSec = elapsedSec
        self.clock = clock
        self.hasClock = hasClock
        self.isTotAnchor = isTotAnchor
        self.clockTime = clockTime
    }

    public static let serialNames = [
        "id", "uiId", "name", "ptType", "lat", "lon", "ref", "value", "mslFt", "aglFt", "groundFt", "airspeed", "wind",
        "legDistNm", "legCourseTrueDeg", "legGsKts", "legTimeSec", "legFuelLb", "elapsedSec", "clock", "hasClock", "isTotAnchor",
    ]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            id: try r.requiredNullable("id", j.string),
            uiId: try r.required("uiId", j.string),
            name: try r.requiredNullable("name", j.string),
            ptType: try r.requiredNullable("ptType", j.string),
            lat: try r.required("lat", j.double),
            lon: try r.required("lon", j.double),
            ref: try r.required("ref", j.string),
            value: try r.required("value", j.double),
            mslFt: try r.requiredNullable("mslFt", j.double),
            aglFt: try r.requiredNullable("aglFt", j.double),
            groundFt: try r.requiredNullable("groundFt", j.double),
            airspeed: try r.requiredNullable("airspeed", j.object),
            wind: try r.requiredNullable("wind", j.object),
            legDistNm: try r.requiredNullable("legDistNm", j.double),
            legCourseTrueDeg: try r.requiredNullable("legCourseTrueDeg", j.double),
            legGsKts: try r.requiredNullable("legGsKts", j.double),
            legTimeSec: try r.requiredNullable("legTimeSec", j.double),
            legFuelLb: try r.requiredNullable("legFuelLb", j.double),
            elapsedSec: try r.requiredNullable("elapsedSec", j.double),
            clock: try r.requiredNullable("clock", j.string),
            hasClock: try r.required("hasClock", j.bool),
            isTotAnchor: try r.required("isTotAnchor", j.bool)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("id", id)
        w.put("uiId", uiId)
        w.put("name", name)
        w.put("ptType", ptType)
        w.put("lat", lat)
        w.put("lon", lon)
        w.put("ref", ref)
        w.put("value", value)
        w.put("mslFt", mslFt)
        w.put("aglFt", aglFt)
        w.put("groundFt", groundFt)
        w.put("airspeed", model: airspeed)
        w.put("wind", model: wind)
        w.put("legDistNm", legDistNm)
        w.put("legCourseTrueDeg", legCourseTrueDeg)
        w.put("legGsKts", legGsKts)
        w.put("legTimeSec", legTimeSec)
        w.put("legFuelLb", legFuelLb)
        w.put("elapsedSec", elapsedSec)
        w.put("clock", clock)
        w.put("hasClock", hasClock)
        w.put("isTotAnchor", isTotAnchor)
    }
}

public struct PlanResult: Hashable, Sendable, ModelJSONCodable {
    public var points: [PlanPoint]
    public var legs: [PlanLeg]
    public var totals: PlanTotals?
    public var warnings: [String]

    public init(points: [PlanPoint], legs: [PlanLeg], totals: PlanTotals?, warnings: [String]) {
        self.points = points
        self.legs = legs
        self.totals = totals
        self.warnings = warnings
    }

    public static let serialNames = ["points", "legs", "totals", "warnings"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            points: try r.required("points") { (v) throws(ModelJSONError) in try j.decodeList(PlanPoint.self, from: v) },
            legs: try r.required("legs") { (v) throws(ModelJSONError) in try j.decodeList(PlanLeg.self, from: v) },
            totals: try r.requiredNullable("totals", j.object),
            warnings: try r.required("warnings") { (v) throws(ModelJSONError) in try j.list(v, j.string) }
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("points", models: points)
        w.put("legs", models: legs)
        w.put("totals", model: totals)
        w.put("warnings", .array(warnings.map(JSONTree.string)))
    }
}
