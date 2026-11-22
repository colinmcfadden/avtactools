import CoreJS

/// An airframe's footprint and planning defaults, as `/api/aircraft-profiles` sends it (snake_case, which is why the names are
/// mapped).
///
/// Defaults are the built-in UH-60L (`FALLBACK_PROFILE` on the web), used until the API answers and whenever a saved map names a
/// profile that no longer exists, so an unresolved profile behaves like the original hard-coded build.
public struct AircraftProfile: Hashable, Sendable, ModelJSONCodable {
    public var id: Int64?
    public var slug: String
    public var name: String
    public var designation: String
    public var iconKey: String
    public var isSystem: Bool
    public var rotorDiameterM: Double
    public var rotorTipClearanceM: Double
    public var defaultAirspeedKts: Double
    public var defaultAirspeedType: String
    public var maxIndicatedKts: Double
    public var defaultAltitudeFt: Double
    public var defaultAltitudeRef: String
    public var minAltitudeFtMsl: Double
    public var maxAltitudeFtMsl: Double
    public var defaultFuelFlowLbHr: Double
    public var defaultGrossWeightLb: Double
    /// `vidx` (from a real AMPS install), `published` (spec sheet, unverified) or `custom`.
    public var perfSource: String
    public var ampsVehicleDescription: String?
    public var hasTemplate: Bool
    public var templateKind: String?

    public init(
        id: Int64? = nil,
        slug: String = "uh60l",
        name: String = "UH-60L Black Hawk",
        designation: String = "UH-60L",
        iconKey: String = "uh60",
        isSystem: Bool = true,
        rotorDiameterM: Double = 16.357,
        rotorTipClearanceM: Double = 60.0,
        defaultAirspeedKts: Double = 100.0,
        defaultAirspeedType: String = "ground",
        maxIndicatedKts: Double = 193.0,
        defaultAltitudeFt: Double = 50.0,
        defaultAltitudeRef: String = "agl",
        minAltitudeFtMsl: Double = -2000.0,
        maxAltitudeFtMsl: Double = 20000.0,
        defaultFuelFlowLbHr: Double = 960.0,
        defaultGrossWeightLb: Double = 16000.0,
        perfSource: String = "vidx",
        ampsVehicleDescription: String? = "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L",
        hasTemplate: Bool = false,
        templateKind: String? = nil
    ) {
        self.id = id
        self.slug = slug
        self.name = name
        self.designation = designation
        self.iconKey = iconKey
        self.isSystem = isSystem
        self.rotorDiameterM = rotorDiameterM
        self.rotorTipClearanceM = rotorTipClearanceM
        self.defaultAirspeedKts = defaultAirspeedKts
        self.defaultAirspeedType = defaultAirspeedType
        self.maxIndicatedKts = maxIndicatedKts
        self.defaultAltitudeFt = defaultAltitudeFt
        self.defaultAltitudeRef = defaultAltitudeRef
        self.minAltitudeFtMsl = minAltitudeFtMsl
        self.maxAltitudeFtMsl = maxAltitudeFtMsl
        self.defaultFuelFlowLbHr = defaultFuelFlowLbHr
        self.defaultGrossWeightLb = defaultGrossWeightLb
        self.perfSource = perfSource
        self.ampsVehicleDescription = ampsVehicleDescription
        self.hasTemplate = hasTemplate
        self.templateKind = templateKind
    }

    /// The built-in UH-60L.
    public static let fallback = AircraftProfile()

    /// `normalizeProfile` from the web: fills in whatever `raw` leaves out so callers never guard for missing values. No profile at
    /// all is the UH-60L; a profile with no `icon_key` gets the generic silhouette.
    public static func normalize(_ raw: JSONObject?) -> AircraftProfile {
        guard let raw else { return fallback }
        let f = fallback
        func number(_ key: String, _ fallback: Double) -> Double { finiteOr(raw[key], fallback) }
        func text(_ key: String, _ fallback: String) -> String { raw[key]?.stringValue ?? fallback }
        func optionalText(_ key: String, _ fallback: String?) -> String? {
            switch raw[key] {
            case nil: fallback
            case .string(let s)?: s
            // A primitive that is not text, null among them, is no text.
            case .null?, .bool?, .number?: nil
            case .array?, .object?: fallback
            }
        }
        func flag(_ key: String, _ fallback: Bool) -> Bool {
            // kotlinx's `booleanOrNull` reads the primitive's text, so a quoted "true" is true too.
            guard let value = raw[key], let content = ModelJSON.content(value) else { return fallback }
            return content == "true" ? true : content == "false" ? false : fallback
        }
        let iconKey = text("icon_key", "")
        return AircraftProfile(
            id: raw["id"].flatMap(ModelJSON.content).flatMap(Kotlin.toLongOrNull),
            slug: text("slug", f.slug),
            name: text("name", f.name),
            designation: text("designation", f.designation),
            // `raw.icon_key || "generic"`: a profile that arrives without one is generic, not a Black Hawk.
            iconKey: iconKey.isEmpty ? "generic" : iconKey,
            isSystem: flag("is_system", f.isSystem),
            rotorDiameterM: number("rotor_diameter_m", f.rotorDiameterM),
            rotorTipClearanceM: number("rotor_tip_clearance_m", f.rotorTipClearanceM),
            defaultAirspeedKts: number("default_airspeed_kts", f.defaultAirspeedKts),
            defaultAirspeedType: text("default_airspeed_type", f.defaultAirspeedType),
            maxIndicatedKts: number("max_indicated_kts", f.maxIndicatedKts),
            defaultAltitudeFt: number("default_altitude_ft", f.defaultAltitudeFt),
            defaultAltitudeRef: text("default_altitude_ref", f.defaultAltitudeRef),
            minAltitudeFtMsl: number("min_altitude_ft_msl", f.minAltitudeFtMsl),
            maxAltitudeFtMsl: number("max_altitude_ft_msl", f.maxAltitudeFtMsl),
            defaultFuelFlowLbHr: number("default_fuel_flow_lb_hr", f.defaultFuelFlowLbHr),
            defaultGrossWeightLb: number("default_gross_weight_lb", f.defaultGrossWeightLb),
            perfSource: text("perf_source", f.perfSource),
            ampsVehicleDescription: optionalText("amps_vehicle_description", f.ampsVehicleDescription),
            hasTemplate: flag("has_template", f.hasTemplate),
            templateKind: optionalText("template_kind", f.templateKind)
        )
    }

    /// Android's `JsNumber.finiteOr`, which CoreJS has no counterpart of: a finite number from a JSON number, or from text that is
    /// a plain decimal (`\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*`, Java's ASCII `\s` and `\d`); `fallback` for anything else.
    static func finiteOr(_ value: JSONTree?, _ fallback: Double) -> Double {
        guard let value, !value.isNull, let text = ModelJSON.content(value) else { return fallback }
        if case .string = value, !isPlainDecimal(text) { return fallback }
        guard let number = Kotlin.toDoubleOrNull(Kotlin.trim(text)), number.isFinite else { return fallback }
        return number
    }

    static func isPlainDecimal(_ text: String) -> Bool {
        let u = Array(text.utf16)
        func isSpace(_ c: UInt16) -> Bool { c == 0x20 || (c >= 0x09 && c <= 0x0D) }
        func isDigit(_ i: Int) -> Bool { i < u.count && u[i] >= 0x30 && u[i] <= 0x39 }
        var i = 0
        while i < u.count, isSpace(u[i]) { i += 1 }
        if i < u.count, u[i] == 0x2B || u[i] == 0x2D { i += 1 }
        let start = i
        while isDigit(i) { i += 1 }
        if i > start {
            if i < u.count, u[i] == 0x2E {
                i += 1
                while isDigit(i) { i += 1 }
            }
        } else {
            guard i < u.count, u[i] == 0x2E, isDigit(i + 1) else { return false }
            i += 1
            while isDigit(i) { i += 1 }
        }
        if i < u.count, u[i] | 0x20 == 0x65 {
            var j = i + 1
            if j < u.count, u[j] == 0x2B || u[j] == 0x2D { j += 1 }
            let expStart = j
            while isDigit(j) { j += 1 }
            // A regular expression would give back the `e` it could not finish; the rest must then be space, which `e` is not.
            guard j > expStart else { return false }
            i = j
        }
        while i < u.count, isSpace(u[i]) { i += 1 }
        return i == u.count
    }

    public static let serialNames = [
        "id", "slug", "name", "designation", "icon_key", "is_system", "rotor_diameter_m", "rotor_tip_clearance_m",
        "default_airspeed_kts", "default_airspeed_type", "max_indicated_kts", "default_altitude_ft", "default_altitude_ref",
        "min_altitude_ft_msl", "max_altitude_ft_msl", "default_fuel_flow_lb_hr", "default_gross_weight_lb", "perf_source",
        "amps_vehicle_description", "has_template", "template_kind",
    ]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        let f = AircraftProfile.fallback
        self.init(
            id: try r.nullable("id", default: nil, j.int64),
            slug: try r.optional("slug", default: f.slug, j.string),
            name: try r.optional("name", default: f.name, j.string),
            designation: try r.optional("designation", default: f.designation, j.string),
            iconKey: try r.optional("icon_key", default: f.iconKey, j.string),
            isSystem: try r.optional("is_system", default: f.isSystem, j.bool),
            rotorDiameterM: try r.optional("rotor_diameter_m", default: f.rotorDiameterM, j.double),
            rotorTipClearanceM: try r.optional("rotor_tip_clearance_m", default: f.rotorTipClearanceM, j.double),
            defaultAirspeedKts: try r.optional("default_airspeed_kts", default: f.defaultAirspeedKts, j.double),
            defaultAirspeedType: try r.optional("default_airspeed_type", default: f.defaultAirspeedType, j.string),
            maxIndicatedKts: try r.optional("max_indicated_kts", default: f.maxIndicatedKts, j.double),
            defaultAltitudeFt: try r.optional("default_altitude_ft", default: f.defaultAltitudeFt, j.double),
            defaultAltitudeRef: try r.optional("default_altitude_ref", default: f.defaultAltitudeRef, j.string),
            minAltitudeFtMsl: try r.optional("min_altitude_ft_msl", default: f.minAltitudeFtMsl, j.double),
            maxAltitudeFtMsl: try r.optional("max_altitude_ft_msl", default: f.maxAltitudeFtMsl, j.double),
            defaultFuelFlowLbHr: try r.optional("default_fuel_flow_lb_hr", default: f.defaultFuelFlowLbHr, j.double),
            defaultGrossWeightLb: try r.optional("default_gross_weight_lb", default: f.defaultGrossWeightLb, j.double),
            perfSource: try r.optional("perf_source", default: f.perfSource, j.string),
            ampsVehicleDescription: try r.nullable("amps_vehicle_description", default: f.ampsVehicleDescription, j.string),
            hasTemplate: try r.optional("has_template", default: f.hasTemplate, j.bool),
            templateKind: try r.nullable("template_kind", default: f.templateKind, j.string)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("id", id)
        w.put("slug", slug)
        w.put("name", name)
        w.put("designation", designation)
        w.put("icon_key", iconKey)
        w.put("is_system", isSystem)
        w.put("rotor_diameter_m", rotorDiameterM)
        w.put("rotor_tip_clearance_m", rotorTipClearanceM)
        w.put("default_airspeed_kts", defaultAirspeedKts)
        w.put("default_airspeed_type", defaultAirspeedType)
        w.put("max_indicated_kts", maxIndicatedKts)
        w.put("default_altitude_ft", defaultAltitudeFt)
        w.put("default_altitude_ref", defaultAltitudeRef)
        w.put("min_altitude_ft_msl", minAltitudeFtMsl)
        w.put("max_altitude_ft_msl", maxAltitudeFtMsl)
        w.put("default_fuel_flow_lb_hr", defaultFuelFlowLbHr)
        w.put("default_gross_weight_lb", defaultGrossWeightLb)
        w.put("perf_source", perfSource)
        w.put("amps_vehicle_description", ampsVehicleDescription)
        w.put("has_template", hasTemplate)
        w.put("template_kind", templateKind)
    }
}
