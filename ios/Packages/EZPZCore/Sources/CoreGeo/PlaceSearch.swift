import CoreJS
import CoreModel
import Foundation

/// What typing a place into a search or target field means.
public enum PlaceResult: Hashable, Sendable {
    /// A position, with how it was read, so the person can see the app understood them.
    case found(at: LatLon, description: String)
    case notUnderstood(message: String)
}

/// One place a text could be: the point, its grid as a target keeps it, its lat/long in words (`PlaceSearch.formatLatLong`) and how
/// the text was read ("MGRS grid, to 1 m", "Read as decimal degrees").
public struct PlaceChoice: Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let mgrs: String
    public let latLong: String
    public let how: String

    public init(lat: Double, lon: Double, mgrs: String, latLong: String, how: String) {
        self.lat = lat
        self.lon = lon
        self.mgrs = mgrs
        self.latLong = latLong
        self.how = how
    }

    public var at: LatLon { LatLon(lat: lat, lon: lon) }

    /// True for a grid that was typed, false for a lat/long read to a grid.
    public var isGrid: Bool { how.hasPrefix("MGRS") }
}

/// What a text in the search could mean, as it is typed: the `choices` (none, one, or an unsigned pair's readings) and a `message` in
/// words when there is not exactly one (why not yet, or why two). Nothing typed is no choices and no message.
public struct PlaceReading: Hashable, Sendable {
    public let choices: [PlaceChoice]
    public let message: String?

    public init(choices: [PlaceChoice], message: String?) {
        self.choices = choices
        self.message = message
    }
}

/// Reads a place the way the web's target field does: an MGRS grid (any even digit count, spaces and case ignored), or a coordinate
/// in any format `CoordinateParser` knows. A grid is tried first, and a coordinate never reinterprets one (the parser refuses grids),
/// so typing a grid is never read as something else.
///
/// `read` is the web's `readTarget` (`frontend/src/utils/targetReading.js`), held to `contracts/fixtures/coords/reading.json`, with
/// `nearestFirst` and `formatLatLong`. Everything is computed on the device.
public enum PlaceSearch {
    public enum Messages {
        public static let incomplete = "Keep typing: a grid, or a latitude and a longitude."
        public static let unreadable =
            "Type a grid, such as 16S GC 28864 55349, or a latitude and longitude, such as 34.5457, -84.1234."
        public static let pair =
            "That is not a latitude and a longitude: give two values, each in degrees, degrees and minutes, or degrees, minutes and seconds."
        public static let minutes = "Minutes and seconds are under 60."
        public static let latitude = "A latitude is at most 90°, north or south."
        public static let longitude = "A longitude is at most 180°, east or west."
        public static let sameAxis = "Both values say the same axis: give one latitude (N or S) and one longitude (E or W)."
        public static let gridSquare = "Keep typing the grid: its 100 km square is two letters."
        public static let gridDigits = "Keep typing: add the grid's easting and northing."
        public static let gridOdd = "Keep typing: a grid has as many northing digits as easting digits."
        public static let gridOff = "That grid is not on the map."
        public static let gridLong = "A grid has at most ten digits: five for the easting and five for the northing."
        public static let gridBand = "That grid's band letter does not hold that square: check the letter after the zone."
        public static let beyond = "That point is beyond the MGRS grid: north of 84° N or south of 80° S."
        public static let eastOrWest = "The longitude has no sign or E/W, so it could be east or west. Choose one."
        /// The apps' own, for a field with no list to choose from (`resolve`): the web has none.
        public static let eastOrWestTyped =
            "The longitude has no sign or E/W, so it could be east or west: add W (or a minus sign) for west, or E for east."
        public static let empty = "Enter a grid or a coordinate."
    }

    private static let problems = [
        "incomplete": Messages.incomplete,
        "unreadable": Messages.unreadable,
        "pair": Messages.pair,
        "minutes": Messages.minutes,
        "latitude": Messages.latitude,
        "longitude": Messages.longitude,
        "same-axis": Messages.sameAxis,
    ]

    // A grid's precision in words, by the digits in each half.
    private static let precision = [1: "10 km", 2: "1 km", 3: "100 m", 4: "10 m", 5: "1 m"]

    private static let formatWords = [
        "decimal": "decimal degrees",
        "ddm": "degrees and decimal minutes",
        "dms": "degrees, minutes and seconds",
        "packed-ddm": "packed degrees and minutes (DDMM.mmm)",
        "packed-dms": "packed degrees, minutes and seconds (DDMMSS)",
        "mixed": "degrees, in two notations",
    ]

    // ^[0-9]{1,2}[A-Za-z]{3}, taken off the front.
    private static let gridPrefix = ScalarPattern((.digits, 1, 2), (.letters, 3, 3))
    // [0-9]{1,2}[C-HJ-NP-Xc-hj-np-x][A-HJ-NP-Za-hj-np-z], matched whole.
    private static let halfSquare = ScalarPattern((.digits, 1, 2), (.bands, 1, 1), (.squares, 1, 1))
    // [0-9]{1,2}[C-HJ-NP-Xc-hj-np-x][A-HJ-NP-Za-hj-np-z]{2}[0-9]{11,}, matched whole.
    private static let tooLong = ScalarPattern((.digits, 1, 2), (.bands, 1, 1), (.squares, 2, 2), (.digits, 11, Int.max))

    // Quotes, brackets and a sentence's last mark round a grid pasted from a message: ^["'“”‘’(\[]+ and ["'“”‘’)\].,;:]+$.
    private static func wrapsStart(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x22, 0x27, 0x201C, 0x201D, 0x2018, 0x2019, 0x28, 0x5B: true
        default: false
        }
    }

    private static func wrapsEnd(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x22, 0x27, 0x201C, 0x201D, 0x2018, 0x2019, 0x29, 0x5D, 0x2E, 0x2C, 0x3B, 0x3A: true
        default: false
        }
    }

    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")
    // How far past its band's edge a square's centre may fall: a coarse square straddles the edge.
    private static let bandMarginDeg = 0.5

    private static func unwrap(_ text: String) -> String {
        var s = Array(text.unicodeScalars)
        let start = s.firstIndex { !wrapsStart($0) } ?? s.count
        s.removeFirst(start)
        let end = (s.lastIndex { !wrapsEnd($0) }).map { $0 + 1 } ?? 0
        s.removeLast(s.count - end)
        var view = String.UnicodeScalarView()
        view.append(contentsOf: s)
        return Js.trim(String(view))
    }

    // JavaScript's \s, which the web's MGRS patterns ignore: wider than Kotlin's isWhitespace (the byte order mark).
    private static func squeeze(_ text: String) -> String {
        String(String.UnicodeScalarView(text.unicodeScalars.filter { !Js.isSpace($0) }))
    }

    /// A lat/long in words: "34.54568° N, 84.12346° W". Five places, rounded as JavaScript's `toFixed`; a value that rounds to zero is N
    /// or E.
    public static func formatLatLong(_ lat: Double, _ lon: Double) -> String {
        "\(hemisphere(lat, "N", "S")), \(hemisphere(lon, "E", "W"))"
    }

    private static func hemisphere(_ value: Double, _ positive: String, _ negative: String) -> String {
        let text = JsNumber.toFixed(abs(value), 5)
        let letter = value < 0 && (Double(text) ?? .nan) != 0.0 ? negative : positive
        return "\(text)° \(letter)"
    }

    private static func choiceAt(_ lat: Double, _ lon: Double, _ mgrs: String, _ how: String) -> PlaceChoice {
        PlaceChoice(lat: lat, lon: lon, mgrs: mgrs, latLong: formatLatLong(lat, lon), how: how)
    }

    private static func readGrid(_ typed: String) -> PlaceReading {
        let squeezed = squeeze(typed)
        guard let grid = MgrsConverter.parse(squeezed) else {
            let scalars = Array(squeezed.unicodeScalars)
            let prefix = gridPrefix.match(scalars, whole: false).map { $0.reduce(0, +) } ?? 0
            let digits = String(String.UnicodeScalarView(scalars[prefix...]))
            let message =
                if digits.isEmpty {
                    Messages.gridDigits
                } else if digits.utf16.count % 2 != 0 {
                    Messages.gridOdd
                } else {
                    Messages.gridOff
                }
            return PlaceReading(choices: [], message: message)
        }
        let point = MgrsConverter.toLatLon(grid)
        // A square the zone has can still name a place past the grid's ends (16X GC is at 87° N): it is no target. Nor is a zone
        // Svalbard does not have (32X, 34X, 36X).
        guard let point, MgrsConverter.toMgrs(point.lat, point.lon) != nil else {
            return PlaceReading(choices: [], message: Messages.gridOff)
        }
        if grid.band == "X" && [32, 34, 36].contains(grid.zone) { return PlaceReading(choices: [], message: Messages.gridOff) }
        // A band letter mistyped (16T for 16S) still names a square, 900 km from the one meant: refuse a square whose centre is not in
        // or beside the band typed.
        let south = Double(bands.firstIndex(of: grid.band) ?? -1) * 8.0 - 80
        let north = grid.band == "X" ? 84.0 : south + 8
        if point.lat < south - bandMarginDeg || point.lat > north + bandMarginDeg {
            return PlaceReading(choices: [], message: Messages.gridBand)
        }
        let zone = padded(String(grid.zone), 2)
        let mgrs = "\(zone)\(grid.band) \(grid.square) \(grid.easting) \(grid.northing)"
        let precision = precision[grid.easting.utf16.count] ?? "under 1 m"
        return PlaceReading(choices: [choiceAt(point.lat, point.lon, mgrs, "MGRS grid, to \(precision)")], message: nil)
    }

    /// What `text` could mean: see the type's head.
    public static func read(_ text: String?) -> PlaceReading {
        let typed = Js.trim(text ?? "")
        if typed.isEmpty { return PlaceReading(choices: [], message: nil) }

        let unwrapped = unwrap(typed)
        if CoordinateParser.looksLikeMgrs(unwrapped) { return readGrid(unwrapped) }
        // A zone, band and one square letter is a grid half typed ("16S G").
        if halfSquare.matches(squeeze(typed)) { return PlaceReading(choices: [], message: Messages.gridSquare) }
        if tooLong.matches(squeeze(typed)) { return PlaceReading(choices: [], message: Messages.gridLong) }

        let reading = CoordinateParser.read(typed)
        // "34S" is a band being typed as much as a latitude: "incomplete" says both.
        guard let parsed = reading.parsed else {
            return PlaceReading(choices: [], message: reading.problem.flatMap { problems[$0] } ?? Messages.unreadable)
        }

        let how = "Read as \(formatWords[parsed.format] ?? "degrees")"
        func at(_ lat: Double, _ lon: Double) -> PlaceChoice? {
            MgrsConverter.toMgrs(lat, lon).map { choiceAt(lat, lon, $0.description, how) }
        }
        guard let literal = at(parsed.lat, parsed.lon) else { return PlaceReading(choices: [], message: Messages.beyond) }

        // With no sign or E/W on the longitude (whatever the latitude says), it is east as written, but crews west of Greenwich drop
        // the minus all the time: offer both rather than guess. Latitude first stays the rule (see CoordinateParser).
        if !reading.signed && parsed.lon != 0.0 {
            // 180° east is 180° west, and a longitude that rounds to 0° reads the same both ways: one place to offer.
            if let west = at(parsed.lat, -parsed.lon), west.latLong != literal.latLong, west.mgrs != literal.mgrs {
                return PlaceReading(choices: [literal, west], message: Messages.eastOrWest)
            }
        }
        return PlaceReading(choices: [literal], message: nil)
    }

    /// The `choices` nearest `near` (where the map is) first, so the reading most likely meant is offered first. Stable: equals keep
    /// their order. Flat distance in degrees, the longitude the short way round, is enough to rank an east and a west reading.
    public static func nearestFirst(_ choices: [PlaceChoice], _ near: LatLon?) -> [PlaceChoice] {
        guard let near, near.lat.isFinite, near.lon.isFinite, choices.count >= 2 else { return choices }
        func distance(_ c: PlaceChoice) -> Double {
            // JavaScript's %, which keeps the dividend's sign.
            let dLon = abs((c.lon - near.lon + 540).truncatingRemainder(dividingBy: 360) - 180)
            return (c.lat - near.lat) * (c.lat - near.lat) + dLon * dLon
        }
        // Kotlin's compareBy: Double.compareTo, where NaN is above everything and -0 below 0, then the index.
        return choices.enumerated()
            .map { (distance: distance($0.element), index: $0.offset, choice: $0.element) }
            .sorted { a, b in
                let order = kotlinCompare(a.distance, b.distance)
                return order != 0 ? order < 0 : a.index < b.index
            }
            .map(\.choice)
    }

    /// The one place a typed grid or coordinate is, for a field with no list under it (a point moved to a typed grid): `found` for
    /// exactly one choice, else what is wrong in words. An unsigned pair is not guessed at: the person is asked to say east or west.
    public static func resolve(_ text: String) -> PlaceResult {
        let reading = read(text)
        if reading.choices.count == 1, let only = reading.choices.first { return .found(at: only.at, description: only.how) }
        let message = reading.choices.count > 1 ? Messages.eastOrWestTyped : (reading.message ?? Messages.empty)
        return .notUnderstood(message: message)
    }
}

/// `Double.compareTo` as Kotlin (Java) orders doubles: -0 below 0, and every NaN equal and above everything.
func kotlinCompare(_ a: Double, _ b: Double) -> Int {
    if a.isNaN || b.isNaN { return a.isNaN ? (b.isNaN ? 0 : 1) : -1 }
    if a < b { return -1 }
    if a > b { return 1 }
    if a == 0, b == 0, a.sign != b.sign { return a.sign == .minus ? -1 : 1 }
    return 0
}
