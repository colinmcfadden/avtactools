import CoreModel
import Foundation

/// Latitude/longitude to MGRS and back, on the device.
///
/// Forward is `frontend/src/utils/mgrs.js` line for line: transverse Mercator by Krueger's series (nanometre accuracy across a UTM
/// zone, so 1 m digits match the server's PyGeodesy exactly). Inverse is the same series run backwards, resolved the way PyGeodesy's
/// `parseMGRS(...).toLatLon()` resolves it, since that is what `/api/convert-grid` answers and what this replaces. Android's
/// MgrsConverter, function by function.
///
/// Covers UTM, 80 S to 84 N. The polar caps use UPS, which no landing zone needs; they have no answer.
public enum MgrsConverter {
    private static let a = 6378137.0  // WGS84 semi-major axis, metres
    private static let f = 1 / 298.257223563  // WGS84 flattening
    private static let k0 = 0.9996  // UTM central scale factor
    private static let falseEasting = 500000.0
    private static let falseNorthingSouth = 10000000.0
    private static let squareM = 100000.0
    private static let rowCycleM = 2000000.0

    private static let n = f / (2 - f)
    private static let e = (f * (2 - f)).squareRoot()  // eccentricity

    /// Rectifying radius, and Krueger's series to sixth order in n.
    private static let rectifying = (a / (1 + n)) * (1 + pow(n, 2) / 4 + pow(n, 4) / 64 + pow(n, 6) / 256)

    /// Forward (lat/lon to UTM) coefficients.
    private static let alpha: [Double] = [
        n / 2 - (2 * pow(n, 2)) / 3 + (5 * pow(n, 3)) / 16 + (41 * pow(n, 4)) / 180 - (127 * pow(n, 5)) / 288
            + (7891 * pow(n, 6)) / 37800,
        (13 * pow(n, 2)) / 48 - (3 * pow(n, 3)) / 5 + (557 * pow(n, 4)) / 1440 + (281 * pow(n, 5)) / 630
            - (1983433 * pow(n, 6)) / 1935360,
        (61 * pow(n, 3)) / 240 - (103 * pow(n, 4)) / 140 + (15061 * pow(n, 5)) / 26880 + (167603 * pow(n, 6)) / 181440,
        (49561 * pow(n, 4)) / 161280 - (179 * pow(n, 5)) / 168 + (6601661 * pow(n, 6)) / 7257600,
        (34729 * pow(n, 5)) / 80640 - (3418889 * pow(n, 6)) / 1995840,
        (212378941 * pow(n, 6)) / 319334400,
    ]

    /// Inverse (UTM to lat/lon) coefficients, Karney 2011.
    private static let beta: [Double] = [
        n / 2 - (2 * pow(n, 2)) / 3 + (37 * pow(n, 3)) / 96 - pow(n, 4) / 360 - (81 * pow(n, 5)) / 512 + (96199 * pow(n, 6)) / 604800,
        pow(n, 2) / 48 + pow(n, 3) / 15 - (437 * pow(n, 4)) / 1440 + (46 * pow(n, 5)) / 105 - (1118711 * pow(n, 6)) / 3870720,
        (17 * pow(n, 3)) / 480 - (37 * pow(n, 4)) / 840 - (209 * pow(n, 5)) / 4480 + (5569 * pow(n, 6)) / 90720,
        (4397 * pow(n, 4)) / 161280 - (11 * pow(n, 5)) / 504 - (830251 * pow(n, 6)) / 7257600,
        (4583 * pow(n, 5)) / 161280 - (108847 * pow(n, 6)) / 3991680,
        (20648693 * pow(n, 6)) / 638668800,
    ]

    /// Conformal to geodetic latitude coefficients.
    private static let delta: [Double] = [
        2 * n - (2 * pow(n, 2)) / 3 - 2 * pow(n, 3) + (116 * pow(n, 4)) / 45 + (26 * pow(n, 5)) / 45 - (2854 * pow(n, 6)) / 675,
        (7 * pow(n, 2)) / 3 - (8 * pow(n, 3)) / 5 - (227 * pow(n, 4)) / 45 + (2704 * pow(n, 5)) / 315 + (2323 * pow(n, 6)) / 945,
        (56 * pow(n, 3)) / 15 - (136 * pow(n, 4)) / 35 - (1262 * pow(n, 5)) / 105 + (73814 * pow(n, 6)) / 2835,
        (4279 * pow(n, 4)) / 630 - (332 * pow(n, 5)) / 35 - (399572 * pow(n, 6)) / 14175,
        (4174 * pow(n, 5)) / 315 - (144838 * pow(n, 6)) / 6237,
        (601676 * pow(n, 6)) / 22275,
    ]

    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")  // 8 degrees each from 80 S; X is 12
    private static let columnSets = [Array("ABCDEFGH"), Array("JKLMNPQR"), Array("STUVWXYZ")]
    private static let rowLetters = Array("ABCDEFGHJKLMNPQRSTUV")  // I and O are never used
    /// Even zones start their rows at F, five letters along.
    private static let evenZoneRows = Array("FGHJKLMNPQRSTUVABCDE")

    private static func toRad(_ deg: Double) -> Double { deg * Double.pi / 180 }
    private static func toDeg(_ rad: Double) -> Double { rad * 180 / Double.pi }

    /// A UTM position. `northing` includes the 10,000 km false northing in the southern hemisphere.
    public struct Utm: Hashable, Sendable {
        public let zone: Int
        public let band: Character
        public let easting: Double
        public let northing: Double

        public init(zone: Int, band: Character, easting: Double, northing: Double) {
            self.zone = zone
            self.band = band
            self.easting = easting
            self.northing = northing
        }
    }

    /// A zone's central meridian, in degrees.
    public static func centralMeridian(_ zone: Int) -> Double { Double((zone - 1) * 6 - 180 + 3) }

    /// The latitude band letter for a latitude in 80 S to 84 N (band X runs to 84). Past either end it is the end's band, never an
    /// index off the list: the map's grid asks for a label's text before it knows the label is on the grid (the web gets `undefined`
    /// there, and that label is not placed).
    public static func bandFor(_ lat: Double) -> Character {
        bands[Swift.min(Swift.max(kotlinInt(floor((lat + 80) / 8)), 0), bands.count - 1)]
    }

    /// The UTM zone, including the Norway and Svalbard exceptions. The longitude is not wrapped: -180 to 180.
    public static func zoneFor(_ lat: Double, _ lon: Double) -> Int {
        var zone = kotlinInt(floor((lon + 180) / 6)) + 1
        if zone > 60 { zone = 60 }  // lon == 180
        if lat >= 56 && lat < 64 && lon >= 3 && lon < 12 { zone = 32 }
        if lat >= 72 && lat < 84 {
            if lon >= 0 && lon < 9 {
                zone = 31
            } else if lon >= 9 && lon < 21 {
                zone = 33
            } else if lon >= 21 && lon < 33 {
                zone = 35
            } else if lon >= 33 && lon < 42 {
                zone = 37
            }
        }
        return zone
    }

    /// The longitude folded into -180 up to 180 as the web folds it, with JavaScript's (and Kotlin's) remainder.
    private static func wrapped(_ lon: Double) -> Double {
        ((lon + 180).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) - 180
    }

    /// Latitude/longitude to UTM. Nil outside 80 S to 84 N or for a non-finite input.
    public static func toUtm(_ lat: Double, _ lon: Double) -> Utm? {
        if !lat.isFinite || !lon.isFinite { return nil }
        if lat < -80 || lat >= 84 { return nil }
        let lon = wrapped(lon)
        let zone = zoneFor(lat, lon)
        return transverseMercator(zone, lat, lon - centralMeridian(zone))
    }

    /// Latitude/longitude projected into `zone`, which need not be the zone the point is in: a grid line near a zone's edge, or in the
    /// Norway and Svalbard exceptions, belongs to its own zone and is computed there (the web's `toUtmInZone`, which the map's grid
    /// uses). Nil outside 80 S to 84 N (84 itself included, as the top of band X), for a zone outside 1 to 60, or for a non-finite input.
    public static func toUtmInZone(_ lat: Double, _ lon: Double, _ zone: Int) -> Utm? {
        if !lat.isFinite || !lon.isFinite { return nil }
        if !(1...60).contains(zone) { return nil }
        if lat < -80 || lat > 84 { return nil }
        var fromMeridian = wrapped(lon) - centralMeridian(zone)
        // Zone 60's east edge is +180, which wraps to -180: measure it from the near side.
        if fromMeridian < -180 {
            fromMeridian += 360
        } else if fromMeridian >= 180 {
            fromMeridian -= 360
        }
        return transverseMercator(zone, lat, fromMeridian)
    }

    // The forward series: a latitude and a longitude from the central meridian to metres. Conformal latitude, then the transverse
    // Mercator series.
    private static func transverseMercator(_ zone: Int, _ lat: Double, _ fromMeridian: Double) -> Utm {
        let phi = toRad(lat)
        let lambda = toRad(fromMeridian)
        let t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        let xiPrime = atan2(t, cos(lambda))
        let etaPrime = atanh(sin(lambda) / (1 + t * t).squareRoot())
        var xi = xiPrime
        var eta = etaPrime
        for i in alpha.indices {
            let j = Double(2 * (i + 1))
            xi += alpha[i] * sin(j * xiPrime) * cosh(j * etaPrime)
            eta += alpha[i] * cos(j * xiPrime) * sinh(j * etaPrime)
        }

        let easting = falseEasting + k0 * rectifying * eta
        var northing = k0 * rectifying * xi
        if lat < 0 { northing += falseNorthingSouth }
        return Utm(zone: zone, band: bandFor(lat), easting: easting, northing: northing)
    }

    /// The 100 km square's two letters: column letters cycle every three zones, rows every two million metres, with even zones' rows
    /// offset by five letters. A column a zone does not have is `?`.
    public static func squareLetters(_ zone: Int, _ easting: Double, _ northing: Double) -> String {
        let set = columnSets[(zone - 1) % 3]
        let columnIndex = kotlinInt(floor(easting / squareM)) - 1
        let column = set.indices.contains(columnIndex) ? set[columnIndex] : "?"
        let rowIndex = (kotlinInt(floor(northing / squareM)) + (zone % 2 == 0 ? 5 : 0)) % 20
        return "\(column)\(rowLetters[rowIndex])"
    }

    /// Latitude/longitude to a grid such as `16S GD 66993 52949`.
    ///
    /// `digits` per coordinate: 5 is 1 m, 4 is 10 m. Digits are truncated, not rounded, as MGRS specifies: a grid names the square the
    /// point is in.
    public static func toMgrs(_ lat: Double, _ lon: Double, digits: Int = 5) -> Mgrs? {
        precondition((1...5).contains(digits), "digits must be 1..5 (10 km to 1 m)")
        guard let utm = toUtm(lat, lon) else { return nil }

        // The 100 km square: column letters cycle every three zones, rows every two million metres, with even zones' rows offset by
        // five letters.
        let set = columnSets[(utm.zone - 1) % 3]
        let columnIndex = kotlinInt(floor(utm.easting / squareM)) - 1
        guard set.indices.contains(columnIndex) else { return nil }
        let rowIndex = (kotlinInt(floor(utm.northing / squareM)) + (utm.zone % 2 == 0 ? 5 : 0)) % 20
        let square = "\(set[columnIndex])\(rowLetters[rowIndex])"

        let scale = pow(10.0, Double(5 - digits))
        func within(_ metres: Double) -> String {
            padded(String(kotlinLong(floor(metres.truncatingRemainder(dividingBy: squareM) / scale))), digits)
        }

        return Mgrs(zone: utm.zone, band: utm.band, square: square, easting: within(utm.easting), northing: within(utm.northing))
    }

    // [0-9], never \d: Swift Regex's \d, like Android's ICU, takes other scripts' digits, which the web's does not.
    // ([0-9]{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-Z])([0-9]*), matched whole.
    private static let gridPattern = ScalarPattern(
        (.digits, 1, 2), (.bandsUpper, 1, 1), (.squaresUpper, 1, 1), (.squaresUpper, 1, 1), (.digits, 0, Int.max)
    )

    /// A grid, as typed, to the point at the centre of the square it names, or nil when it is not a usable grid. Spaces and case are
    /// ignored and the digits split evenly into easting and northing, so any even count of two or more works; each pair past ten
    /// refines the square tenfold below a metre.
    public static func parse(_ text: String) -> Mgrs? {
        let clean = Array(
            String(String.UnicodeScalarView(text.unicodeScalars.filter { !isKotlinWhitespace($0) })).uppercased().unicodeScalars)
        guard let counts = gridPattern.match(clean, whole: true) else { return nil }
        let zoneEnd = counts[0]
        let zone = Int(String(String.UnicodeScalarView(clean[0..<zoneEnd])))!
        if !(1...60).contains(zone) { return nil }
        let digits = String(String.UnicodeScalarView(clean[(zoneEnd + 3)...]))
        if digits.count < 2 || digits.count % 2 != 0 { return nil }
        let half = digits.count / 2
        return Mgrs(
            zone: zone,
            band: Character(clean[zoneEnd]),
            square: String(Character(clean[zoneEnd + 1])) + String(Character(clean[zoneEnd + 2])),
            easting: String(digits.prefix(half)),
            northing: String(digits.dropFirst(half))
        )
    }

    /// Kotlin's `Char.isWhitespace()`, which `parse` strips: Java's whitespace (the ASCII controls \t to \r and U+001C to U+001F) and
    /// every Unicode space separator, the no-break ones included, with the line and paragraph separators. Not the byte order mark.
    static func isKotlinWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0D, 0x1C...0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: true
        default: false
        }
    }

    /// The centre of the square a grid names, or nil for a grid `parse` refuses or the squares a zone does not have.
    public static func toLatLon(_ text: String) -> LatLon? { parse(text).flatMap { toLatLon($0) } }

    public static func toLatLon(_ grid: Mgrs) -> LatLon? {
        let zone = grid.zone
        let square = Array(grid.square)
        // A square that is not two letters (an Mgrs made by hand) is no grid; Kotlin's index would throw.
        guard square.count >= 2 else { return nil }
        guard let columnIndex = columnSets[(zone - 1) % 3].firstIndex(of: square[0]) else { return nil }
        let rows = zone % 2 == 0 ? evenZoneRows : rowLetters
        guard let rowIndex = rows.firstIndex(of: square[1]) else { return nil }
        guard let bandIndex = bands.firstIndex(of: grid.band) else { return nil }
        guard let eastingDigits = Double(grid.easting), let northingDigits = Double(grid.northing) else { return nil }

        // Position within the square: the digits name its lower-left corner at 10^(5 - digits) metres, and the answer is its centre.
        let resolution = pow(10.0, Double(5 - grid.digits))
        let cornerEasting = Double(columnIndex + 1) * squareM + eastingDigits * resolution
        var cornerNorthing = Double(rowIndex) * squareM + northingDigits * resolution

        // The row letters repeat every 2,000 km north; add blocks until the northing reaches the band's floor. PyGeodesy takes the
        // floor from the band's southern edge on the 0 degree meridian, truncated to 100 km, and works from the square's corner,
        // centring afterwards.
        let bandFloor = toUtm(Double(bandIndex * 8 - 80), 0.0)!.northing
        let northingBottom = floor(bandFloor / squareM) * squareM
        let blocks = (northingBottom - cornerNorthing) / rowCycleM
        if blocks > 0 {
            cornerNorthing += Double(Swift.min(kotlinInt(blocks) + 1, grid.band == "W" ? 3 : 4)) * rowCycleM
        }

        return inverse(
            zone,
            southern: grid.band < "N",
            easting: cornerEasting + resolution / 2,
            northing: cornerNorthing + resolution / 2
        )
    }

    /// UTM to latitude/longitude (the web's `fromUtm`); a `southern` northing carries the 10,000 km false northing, as `toUtm` gives it.
    /// Nil for a zone outside 1 to 60 or a non-finite coordinate.
    public static func fromUtm(_ zone: Int, southern: Bool, easting: Double, northing: Double) -> LatLon? {
        if !(1...60).contains(zone) || !easting.isFinite || !northing.isFinite { return nil }
        return inverse(zone, southern: southern, easting: easting, northing: northing)
    }

    private static func inverse(_ zone: Int, southern: Bool, easting: Double, northing: Double) -> LatLon {
        let xi0 = (northing - (southern ? falseNorthingSouth : 0.0)) / (k0 * rectifying)
        let eta0 = (easting - falseEasting) / (k0 * rectifying)
        var xi = xi0
        var eta = eta0
        for i in beta.indices {
            let j = Double(2 * (i + 1))
            xi -= beta[i] * sin(j * xi0) * cosh(j * eta0)
            eta -= beta[i] * cos(j * xi0) * sinh(j * eta0)
        }
        let chi = asin(sin(xi) / cosh(eta))
        var phi = chi
        for i in delta.indices { phi += delta[i] * sin(Double(2 * (i + 1)) * chi) }
        let lambda = atan2(sinh(eta), cos(xi))
        return LatLon(lat: toDeg(phi), lon: wrap180(toDeg(lambda) + centralMeridian(zone)))
    }

    /// Longitude folded into +-180 the way PyGeodesy's `wrap180` does: a value already in range, +-180 included, is left alone. A
    /// square on the far side of its zone's edge (zone 60's east, zone 1's west) otherwise comes back as 180.0003 where the server
    /// says -179.9997.
    private static func wrap180(_ lon: Double) -> Double {
        (-180.0...180.0).contains(lon) ? lon : wrapped(lon)
    }
}
