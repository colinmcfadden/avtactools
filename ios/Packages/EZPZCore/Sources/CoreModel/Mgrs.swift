/// A Military Grid Reference System reference such as `16S GD 66993 52949`.
///
/// The digits stay text because leading zeros are part of the grid: `00011` is eleven metres, and an integer would lose the zeros.
/// `easting` and `northing` always have the same length (1 to 5 digits is 10 km down to 1 m; more refines past a metre).
public struct Mgrs: Hashable, Sendable, CustomStringConvertible {
    public let zone: Int
    public let band: Character
    /// The 100 km square: column letter then row letter, e.g. `GD`.
    public let square: String
    public let easting: String
    public let northing: String

    /// Nil where Android's `require` throws: a zone outside 1…60, or an easting and northing of different lengths.
    public init?(zone: Int, band: Character, square: String, easting: String, northing: String) {
        guard (1...60).contains(zone), easting.count == northing.count else { return nil }
        self.zone = zone
        self.band = band
        self.square = square
        self.easting = easting
        self.northing = northing
    }

    /// Digits per axis.
    public var digits: Int { easting.count }

    /// `16S GD 66993 52949`, with the zone zero-padded as the server writes it (`05R`, not `5R`).
    public func format() -> String {
        (zone < 10 ? "0\(zone)" : "\(zone)") + "\(band) \(square) \(easting) \(northing)"
    }

    public var description: String { format() }
}
