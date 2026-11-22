/// What the threat form holds while a threat is being made or changed (the web's `ThreatDialog`): the words and numbers as typed, and
/// nothing saved until `check` says they are good. Numbers stay text because that is what a person is in the middle of typing.
///
/// **Not like the web:** the web's `num` turns a blank or unreadable number into 0 silently (a radar with no range is then refused by
/// the server, later). Here each is refused at the field, by name. The ranges are the planner's, not the web's: they keep what is
/// saved inside what an AMPS `.ths` can hold and what a viewshed can be asked for. Band altitudes are whole feet because a `.ths`
/// stores them as integers: a fraction would be cut off in the file without a word.
///
/// A threat's position is not in the draft: the form holds it beside the draft and applies it with the rest on Save; a new threat
/// starts where the crosshair is.
public struct ThreatDraft: Hashable, Sendable {
    public var name: String
    public var milstdId: String
    public var information: String
    public var source: String
    public var radars: [RadarDraft]

    public init(
        name: String = Radars.defaultName,
        milstdId: String = Radars.defaultMilstdId,
        information: String = "",
        source: String = Radars.defaultSource,
        radars: [RadarDraft] = Radars.defaultPair().map(RadarDraft.of)
    ) {
        self.name = name
        self.milstdId = milstdId
        self.information = information
        self.source = source
        self.radars = radars
    }

    /// What `check` found: the threat to keep, or a refusal in words (for the form itself, not the sheet's banner).
    public enum Checked: Hashable, Sendable {
        case valid(Threat)
        case refused(String)
    }

    /// Whether this draft can be kept, and as what. `at` is where the threat is; `base` the threat being changed, whose visibility
    /// and bands' colours stay as they are.
    public func check(at: LatLon, base: Threat? = nil) -> Checked {
        let trimmedName = Kotlin.trim(name)
        if trimmedName.isEmpty { return .refused("A threat needs a name.") }
        let sidc = Kotlin.uppercase(Kotlin.trim(milstdId))
        if !Self.isSidc(sidc) { return .refused("The symbol code must be 1 to 15 letters, digits, hyphens or asterisks.") }
        var checked: [Radar] = []
        checked.reserveCapacity(radars.count)
        for radar in radars {
            switch radar.check() {
            case .made(let made): checked.append(made)
            case .not(let message): return .refused(message)
            }
        }
        let trimmedSource = Kotlin.trim(source)
        return .valid(
            Threat(
                name: TypedNumber.cut(trimmedName, Self.nameMax),
                milstdId: sidc,
                lat: at.lat,
                lon: at.lon,
                information: TypedNumber.cut(Kotlin.trim(information), Self.informationMax),
                source: TypedNumber.cut(trimmedSource.isEmpty ? Radars.defaultSource : trimmedSource, Self.sourceMax),
                showThreat: base?.showThreat ?? true,
                radars: checked
            )
        )
    }

    public static let nameMax = 50
    public static let informationMax = 255
    public static let sourceMax = 32

    /// `[A-Z0-9*-]{1,15}`, matched whole.
    private static func isSidc(_ text: String) -> Bool {
        let u = text.utf16
        return (1...15).contains(u.count)
            && u.allSatisfy { ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2A || $0 == 0x2D }
    }

    /// A new threat's form: the web's defaults, and the name `Threat n` (n counting the threats there already, +1).
    public static func forNew(_ existingCount: Int) -> ThreatDraft { ThreatDraft(name: "\(Radars.defaultName) \(existingCount + 1)") }

    /// The form for a held threat, to change it.
    public static func of(_ threat: Threat) -> ThreatDraft {
        ThreatDraft(
            name: threat.name, milstdId: threat.milstdId, information: threat.information, source: threat.source,
            radars: threat.radars.map(RadarDraft.of)
        )
    }
}

/// One radar's part of the form: its range and antenna as typed, and the three bands' altitudes. The bands' colours stay as the
/// radar had them.
public struct RadarDraft: Hashable, Sendable {
    public var base: Radar
    public var rangeNmi: String
    public var antennaHeightFt: String
    public var aglNotMsl: Bool
    public var showMask: Bool
    public var showRangeRings: Bool
    public var bandAltitudesFt: [String]
    public var bandsViewable: [Bool]

    public init(
        base: Radar, rangeNmi: String, antennaHeightFt: String, aglNotMsl: Bool, showMask: Bool, showRangeRings: Bool,
        bandAltitudesFt: [String], bandsViewable: [Bool]
    ) {
        self.base = base
        self.rangeNmi = rangeNmi
        self.antennaHeightFt = antennaHeightFt
        self.aglNotMsl = aglNotMsl
        self.showMask = showMask
        self.showRangeRings = showRangeRings
        self.bandAltitudesFt = bandAltitudesFt
        self.bandsViewable = bandsViewable
    }

    /// `Detection` or `Engagement`: what the form and its refusals call this radar.
    public var label: String { base.type == Radars.engagement ? "Engagement" : "Detection" }

    enum Result {
        case made(Radar)
        case not(String)
    }

    /// The radar these fields make, or why they do not.
    func check() -> Result {
        let range: Double
        switch number("\(label) range", rangeNmi, Self.minRangeNmi, Self.maxRangeNmi) {
        case .success(let v): range = v
        case .failure(let e): return .not(e.message)
        }
        let antenna: Double
        switch number("\(label) antenna height", antennaHeightFt, 0, Self.maxAntennaFt) {
        case .success(let v): antenna = v
        case .failure(let e): return .not(e.message)
        }
        var bands: [RadarBand] = []
        for (i, band) in base.bands.enumerated() {
            let typed = i < bandAltitudesFt.count ? bandAltitudesFt[i] : TypedNumber.plain(band.altFt)
            let name = "\(label) band \(i + 1) altitude"
            let altitude: Double
            switch number(name, typed, 0, Self.maxBandFt) {
            case .success(let v): altitude = v
            case .failure(let e): return .not(e.message)
            }
            if altitude != altitude.rounded(.down) { return .not("\(name) must be a whole number of feet.") }
            var next = band
            next.altFt = altitude
            next.viewable = i < bandsViewable.count ? bandsViewable[i] : band.viewable
            bands.append(next)
        }
        var radar = base
        radar.rangeNmi = range
        radar.antennaHeightFt = antenna
        radar.aglNotMsl = aglNotMsl
        radar.showMask = showMask
        radar.showRangeRings = showRangeRings
        radar.bands = bands
        return .made(radar)
    }

    private struct Refusal: Error {
        let message: String
    }

    private func number(_ name: String, _ typed: String, _ min: Double, _ max: Double) -> Swift.Result<Double, Refusal> {
        guard let value = TypedNumber.parse(typed) else {
            return .failure(Refusal(message: Kotlin.isBlank(typed) ? "\(name) needs a number." : "\(name) is not a number."))
        }
        if value < min || value > max {
            return .failure(Refusal(message: "\(name) must be between \(TypedNumber.plain(min)) and \(TypedNumber.plain(max))."))
        }
        return .success(value)
    }

    public static let minRangeNmi = 0.1
    public static let maxRangeNmi = 500.0
    public static let maxAntennaFt = 10_000.0
    public static let maxBandFt = 50_000.0

    public static func of(_ radar: Radar) -> RadarDraft {
        RadarDraft(
            base: radar,
            rangeNmi: TypedNumber.plain(radar.rangeNmi),
            antennaHeightFt: TypedNumber.plain(radar.antennaHeightFt),
            aglNotMsl: radar.aglNotMsl,
            showMask: radar.showMask,
            showRangeRings: radar.showRangeRings,
            bandAltitudesFt: radar.bands.map { TypedNumber.plain($0.altFt) },
            bandsViewable: radar.bands.map(\.viewable)
        )
    }
}
