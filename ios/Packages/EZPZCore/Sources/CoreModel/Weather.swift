/// What the nearest station reported, as the mission summary shows it. Every number can be missing, because a station reports what its
/// sensors have and the server passes on what it is given; a missing number is nil, never zero, so a tile says `--` instead of a calm
/// wind or a freezing day.
public struct WeatherObservation: Hashable, Sendable {
    /// The ICAO id (`KRYY`), or `UNKNOWN` when the report has none.
    public var stationId: String
    public var stationName: String
    /// How far the station is from the position asked about, in statute miles.
    public var distanceMiles: Double
    /// Degrees true the wind is from. Nil when it is variable (see `windVariable`) or not reported.
    public var windFromDegrees: Int?
    public var windVariable: Bool
    public var windSpeedKt: Double?
    public var windGustKt: Double?
    public var tempC: Double?
    public var dewpointC: Double?
    /// Inches of mercury.
    public var altimeterInHg: Double?
    /// Statute miles, as the station wrote it (`10`, `10+`, `1 1/2`).
    public var visibility: String?
    /// VFR, MVFR, IFR or LIFR.
    public var flightCategory: String?
    /// The METAR as it was issued.
    public var rawReport: String?

    public init(
        stationId: String, stationName: String, distanceMiles: Double, windFromDegrees: Int?, windVariable: Bool, windSpeedKt: Double?,
        windGustKt: Double?, tempC: Double?, dewpointC: Double?, altimeterInHg: Double?, visibility: String?, flightCategory: String?,
        rawReport: String?
    ) {
        self.stationId = stationId
        self.stationName = stationName
        self.distanceMiles = distanceMiles
        self.windFromDegrees = windFromDegrees
        self.windVariable = windVariable
        self.windSpeedKt = windSpeedKt
        self.windGustKt = windGustKt
        self.tempC = tempC
        self.dewpointC = dewpointC
        self.altimeterInHg = altimeterInHg
        self.visibility = visibility
        self.flightCategory = flightCategory
        self.rawReport = rawReport
    }
}

/// The NOTAMs about one kind of thing (the FAA's `featureName`: `Obstruction`, `Airspace`…), each as the text it was issued in.
public struct NotamGroup: Hashable, Sendable {
    public var title: String
    public var texts: [String]

    public init(title: String, texts: [String]) {
        self.title = title
        self.texts = texts
    }
}

/// What is known of the NOTAMs around a position.
public enum Notams: Hashable, Sendable {
    /// The search was done and there are none.
    case clear
    /// The search was done and these are active.
    case listed([NotamGroup])
    /// The search could not be done, so nothing is known: it is *not* the same as `clear`.
    case unavailable

    /// How many NOTAMs there are in all (none unless listed).
    public var count: Int {
        if case .listed(let groups) = self { groups.reduce(0) { $0 + $1.texts.count } } else { 0 }
    }
}

/// The weather at a position, with the time it was fetched. Kept so the last answer can be shown with no signal, marked with its
/// age: a report from the morning is not today's weather, and the crew must be able to see that.
public struct WeatherSnapshot: Hashable, Sendable {
    public var at: LatLon
    /// When it was fetched, in milliseconds since the epoch.
    public var fetchedAtMillis: Int64
    /// Nil when no station answered.
    public var observation: WeatherObservation?
    public var notams: Notams

    public init(at: LatLon, fetchedAtMillis: Int64, observation: WeatherObservation?, notams: Notams) {
        self.at = at
        self.fetchedAtMillis = fetchedAtMillis
        self.observation = observation
        self.notams = notams
    }

    /// How old this is at `nowMillis`, in words.
    public func age(_ nowMillis: Int64) -> String { WeatherAge.words(nowMillis - fetchedAtMillis) }

    /// Whether this is old enough that it should be shown as stale: a METAR is an hour old when it is issued.
    public func isStale(_ nowMillis: Int64) -> Bool { nowMillis - fetchedAtMillis > WeatherAge.staleAfterMs }
}

/// How old a fetch is, in words a person reads at a glance.
public enum WeatherAge {
    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour

    /// Past this a report is shown as stale.
    public static let staleAfterMs: Int64 = 90 * minute

    /// `just now`, `12 min ago`, `3 h ago`, `2 days ago`. A clock that went backwards (a negative age) is `just now`, not a negative
    /// number.
    public static func words(_ ageMillis: Int64) -> String {
        if ageMillis < minute { return "just now" }
        if ageMillis < hour { return "\(ageMillis / minute) min ago" }
        if ageMillis < 2 * day { return "\(ageMillis / hour) h ago" }
        return "\(ageMillis / day) days ago"
    }
}
