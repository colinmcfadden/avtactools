import CoreJS

/// What the unit builder, or a preset, hands over to make a unit. Every part is optional, as on the web.
public struct UnitConfig: Hashable, Sendable {
    public var id: String?
    public var path: String?
    public var sidc: String?
    public var uniqueDesignation: String?
    public var higherFormation: String?

    public init(
        id: String? = nil,
        path: String? = nil,
        sidc: String? = nil,
        uniqueDesignation: String? = nil,
        higherFormation: String? = nil
    ) {
        self.id = id
        self.path = path
        self.sidc = sidc
        self.uniqueDesignation = uniqueDesignation
        self.higherFormation = higherFormation
    }
}

/// A unit on the map: a MIL-STD symbol with a designation and the formation above it, kept as the web keeps it (`units` of a
/// diagram).
public enum UnitMarkers {
    /// A new unit (`createUnit`): it sits `offset` degrees north and east of `center`, so it does not land on the target itself. Parts
    /// that were not given are left out, as `JSON.stringify` leaves out what is undefined; `type` is the preset's id.
    public static func create(_ config: UnitConfig, center: LatLon, offset: Double, id: String) -> JSONObject {
        var fields: [(String, JSONTree)] = [("id", .string(id))]
        if let v = config.id { fields.append(("type", .string(v))) }
        if let v = config.path { fields.append(("path", .string(v))) }
        if let v = config.sidc { fields.append(("sidc", .string(v))) }
        if let v = config.uniqueDesignation { fields.append(("uniqueDesignation", .string(v))) }
        if let v = config.higherFormation { fields.append(("higherFormation", .string(v))) }
        fields.append(("lat", .number(center.lat + offset)))
        fields.append(("lon", .number(center.lon + offset)))
        return JSONObject(fields)
    }
}
