/// A WGS84 position in decimal degrees. Longitude is east-positive.
public struct LatLon: Hashable, Sendable {
    public var lat: Double
    public var lon: Double

    public init(lat: Double, lon: Double) {
        self.lat = lat
        self.lon = lon
    }
}
