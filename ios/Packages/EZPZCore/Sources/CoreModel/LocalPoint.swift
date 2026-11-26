import CoreJS

// AMPS local points, as the web app holds them after importing an `.LPS` file (`parseLps.js`). The set's identity on a device (an
// id) is the app's business; what is here is what the file said.

/// One local point. `elevationFt` is the feet AMPS recorded, or nil if the file gave no number.
public struct LocalPoint: Hashable, Sendable, ModelJSONCodable {
    public var name: String
    public var description: String
    public var group: String
    public var icon: String
    public var elevationFt: Double?
    public var lat: Double
    public var lon: Double

    public init(name: String, description: String, group: String, icon: String, elevationFt: Double?, lat: Double, lon: Double) {
        self.name = name
        self.description = description
        self.group = group
        self.icon = icon
        self.elevationFt = elevationFt
        self.lat = lat
        self.lon = lon
    }

    public static let serialNames = ["name", "description", "group", "icon", "elevationFt", "lat", "lon"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            name: try r.required("name", j.string),
            description: try r.required("description", j.string),
            group: try r.required("group", j.string),
            icon: try r.required("icon", j.string),
            elevationFt: try r.requiredNullable("elevationFt", j.double),
            lat: try r.required("lat", j.double),
            lon: try r.required("lon", j.double)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("name", name)
        w.put("description", description)
        w.put("group", group)
        w.put("icon", icon)
        w.put("elevationFt", elevationFt)
        w.put("lat", lat)
        w.put("lon", lon)
    }
}

/// A named set of points, as read from one file.
public struct LocalPointSet: Hashable, Sendable, ModelJSONCodable {
    public var name: String
    public var points: [LocalPoint]

    public init(name: String, points: [LocalPoint]) {
        self.name = name
        self.points = points
    }

    public static let serialNames = ["name", "points"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            name: try r.required("name", j.string),
            points: try r.required("points") { (v) throws(ModelJSONError) in try j.decodeList(LocalPoint.self, from: v) }
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("name", name)
        w.put("points", models: points)
    }
}
