import CoreJS

/// What the aircraft form holds while a profile is being made or changed (the web's `AircraftProfileModal`): the words and numbers as
/// typed, and nothing saved until `check` says they are good.
///
/// The numbers are kept as text because that is what a person is in the middle of typing ("1." is not yet a number, and must not be
/// turned into one under their finger). `check` holds them to the server's own limits (`routes/aircraft_routes.py`, `_apply_fields`),
/// so a profile made with no signal is never one the server later refuses: a refused create would sit in the outbox for good.
///
/// **Not like the web:** the web's `num` turns a blank or unreadable number into the form's default, silently. Here that is refused
/// with the field named, because a rotor diameter nobody typed is a footprint nobody chose.
public struct AircraftDraft: Hashable, Sendable {
    public var name: String
    public var designation: String
    public var iconKey: String
    public var rotorDiameterM: String
    public var rotorTipClearanceM: String
    public var defaultAirspeedKts: String
    public var defaultAirspeedType: String
    public var maxIndicatedKts: String
    public var defaultAltitudeFt: String
    public var defaultAltitudeRef: String
    public var defaultFuelFlowLbHr: String
    public var defaultGrossWeightLb: String

    public init(
        name: String = "",
        designation: String = "",
        iconKey: String = "generic",
        rotorDiameterM: String = "16.36",
        rotorTipClearanceM: String = "60",
        defaultAirspeedKts: String = "100",
        defaultAirspeedType: String = "ground",
        maxIndicatedKts: String = "160",
        defaultAltitudeFt: String = "50",
        defaultAltitudeRef: String = "agl",
        defaultFuelFlowLbHr: String = "960",
        defaultGrossWeightLb: String = "16000"
    ) {
        self.name = name
        self.designation = designation
        self.iconKey = iconKey
        self.rotorDiameterM = rotorDiameterM
        self.rotorTipClearanceM = rotorTipClearanceM
        self.defaultAirspeedKts = defaultAirspeedKts
        self.defaultAirspeedType = defaultAirspeedType
        self.maxIndicatedKts = maxIndicatedKts
        self.defaultAltitudeFt = defaultAltitudeFt
        self.defaultAltitudeRef = defaultAltitudeRef
        self.defaultFuelFlowLbHr = defaultFuelFlowLbHr
        self.defaultGrossWeightLb = defaultGrossWeightLb
    }

    /// The planner's centre-to-centre spacing these two numbers make (the web shows it under the footprint), or nil while either is
    /// not a number.
    public var spacingM: Double? {
        guard let rotor = Self.parse(rotorDiameterM), let clearance = Self.parse(rotorTipClearanceM) else { return nil }
        return rotor + clearance
    }

    /// What `check` found: the name and the document to save, or a refusal in words.
    public enum Checked: Equatable, Sendable {
        /// `data` is what the record carries: the settable fields (and `perf_source`), with anything in the profile being edited that
        /// the form does not show kept as it was.
        case valid(name: String, data: JSONObject)
        case refused(String)

        // By hand because JSONObject has no Equatable of its own.
        public static func == (a: Checked, b: Checked) -> Bool {
            switch (a, b) {
            case (.valid(let n1, let d1), .valid(let n2, let d2)): Kotlin.same(n1, n2) && JSONTree.object(d1) == .object(d2)
            case (.refused(let m1), .refused(let m2)): Kotlin.same(m1, m2)
            default: false
            }
        }
    }

    /// Whether this draft can be saved. `existing` is the document of the profile being edited, so what the form does not show (the
    /// altitude limits, the server's slug and id) stays as it is; nil for a new profile.
    public func check(_ existing: JSONObject? = nil) -> Checked {
        let trimmedName = Kotlin.trim(name)
        let trimmedDesignation = Kotlin.trim(designation)
        if trimmedName.isEmpty || trimmedDesignation.isEmpty { return .refused("Name and designation are required.") }
        if !Self.airspeedTypes.contains(where: { Kotlin.same($0, defaultAirspeedType) }) {
            return .refused("Airspeed reference must be ground, indicated or true.")
        }
        if !Self.altitudeRefs.contains(where: { Kotlin.same($0, defaultAltitudeRef) }) {
            return .refused("Altitude reference must be AGL or MSL.")
        }

        var fields: [(String, JSONTree)] = []
        for field in Self.numbers {
            let typed = field.read(self)
            guard let value = Self.parse(typed) else {
                return .refused(Kotlin.isBlank(typed) ? "\(field.label) needs a number." : "\(field.label) is not a number.")
            }
            if value < field.min || value > field.max {
                return .refused("\(field.label) must be between \(Self.plain(field.min)) and \(Self.plain(field.max)).")
            }
            fields.append((field.key, .number(value)))
        }
        // A LinkedHashMap's puts: a key the profile has keeps its place.
        var document: [(String, JSONTree)] = existing?.entries.map { ($0.key, $0.value) } ?? []
        document.append(("designation", .string(Self.cut(trimmedDesignation, Self.maxDesignation))))
        let icon = Self.cut(Kotlin.trim(iconKey), Self.maxIconKey)
        document.append(("icon_key", .string(icon.isEmpty ? "generic" : icon)))
        document += fields
        document.append(("default_airspeed_type", .string(defaultAirspeedType)))
        document.append(("default_altitude_ref", .string(defaultAltitudeRef)))
        // What the server records for a profile a user made; the sync leaves it out of what it sends, but the app reads it before the
        // server has.
        if existing == nil || existing?.has("perf_source") == false { document.append(("perf_source", .string("custom"))) }
        return .valid(name: Self.cut(trimmedName, Self.maxName), data: JSONObject(document))
    }

    private struct NumberField: Sendable {
        let key: String
        let label: String
        let min: Double
        let max: Double
        let read: @Sendable (AircraftDraft) -> String
    }

    public static let maxName = 120
    public static let maxDesignation = 40
    private static let maxIconKey = 40

    public static let airspeedTypes = ["ground", "indicated", "true"]
    public static let altitudeRefs = ["agl", "msl"]

    /// The silhouettes the web draws (`ICON_KEYS`); an icon this version cannot name is drawn as the generic one.
    public static let iconKeys = ["uh60", "ah64", "ch47", "uh72", "mh6", "generic"]

    /// The server's limits, in the order the form shows them.
    private static let numbers: [NumberField] = [
        NumberField(key: "rotor_diameter_m", label: "Rotor diameter", min: 1, max: 60) { $0.rotorDiameterM },
        NumberField(key: "rotor_tip_clearance_m", label: "Tip clearance", min: 0, max: 1000) { $0.rotorTipClearanceM },
        NumberField(key: "default_airspeed_kts", label: "Cruise airspeed", min: 1, max: 400) { $0.defaultAirspeedKts },
        NumberField(key: "max_indicated_kts", label: "Max indicated airspeed", min: 1, max: 400) { $0.maxIndicatedKts },
        NumberField(key: "default_altitude_ft", label: "Default altitude", min: -2000, max: 30000) { $0.defaultAltitudeFt },
        NumberField(key: "default_fuel_flow_lb_hr", label: "Fuel flow", min: 0, max: 20000) { $0.defaultFuelFlowLbHr },
        NumberField(key: "default_gross_weight_lb", label: "Gross weight", min: 0, max: 200_000) { $0.defaultGrossWeightLb },
    ]

    private static func parse(_ text: String) -> Double? { TypedNumber.parse(text) }

    /// At most `max` characters, counted as the server counts them (code points), so an emoji at the end is never split.
    private static func cut(_ text: String, _ max: Int) -> String { TypedNumber.cut(text, max) }

    /// A number as it is typed back into a field: no trailing ".0", no exponent, no locale.
    static func plain(_ value: Double) -> String { TypedNumber.plain(value) }

    /// The draft for a saved profile, to change it: the form's fields as it has them (a profile read with `normalize` is never
    /// missing one).
    public static func of(_ profile: AircraftProfile) -> AircraftDraft {
        AircraftDraft(
            name: profile.name,
            designation: profile.designation,
            iconKey: profile.iconKey,
            rotorDiameterM: plain(profile.rotorDiameterM),
            rotorTipClearanceM: plain(profile.rotorTipClearanceM),
            defaultAirspeedKts: plain(profile.defaultAirspeedKts),
            defaultAirspeedType: profile.defaultAirspeedType,
            maxIndicatedKts: plain(profile.maxIndicatedKts),
            defaultAltitudeFt: plain(profile.defaultAltitudeFt),
            defaultAltitudeRef: profile.defaultAltitudeRef,
            defaultFuelFlowLbHr: plain(profile.defaultFuelFlowLbHr),
            defaultGrossWeightLb: plain(profile.defaultGrossWeightLb)
        )
    }

    /// A new profile that starts as a copy of one (the web's "Copy"): its numbers, and a name that says it is a copy.
    public static func copyOf(_ profile: AircraftProfile) -> AircraftDraft {
        var draft = of(profile)
        draft.name = "\(profile.name) (copy)"
        return draft
    }
}
