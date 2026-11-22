import CoreJS

// An AMPS mission as the app reads it from a `.msnx` file: the routes in it, with the plan AMPS recorded for each, and the airframe
// it was planned for. The route shape is the web's (`parseMsnx.js`), so a mission imported on one client saves and opens on the
// other.

/// The airframe a mission was planned for, as AMPS writes it into the file.
public struct MissionAircraft: Hashable, Sendable, ModelJSONCodable {
    /// The full AMPS vehicle description, e.g. `Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L`.
    public var description: String
    /// Its last field, the designation (`UH-60L`), or nil if there is none.
    public var designation: String?

    public init(description: String, designation: String? = nil) {
        self.description = description
        self.designation = designation
    }

    public static let serialNames = ["description", "designation"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        self.init(
            description: try r.required("description", r.json.string),
            designation: try r.nullable("designation", default: nil, r.json.string)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("description", description)
        w.put("designation", designation)
    }
}

/// One route in a mission.
public struct MissionRoute: Equatable, Sendable, ModelJSONCodable {
    public var name: String
    /// AMPS's id for the route's segment, which ties it to its legs.
    public var segmentId: String?
    /// Every point in flight order, shaping points included.
    public var points: [RoutePoint]
    /// Planned altitudes, airspeeds, winds and clock times, read back out of the file.
    public var plan: RoutePlan
    /// Ground elevation in feet by AMPS point id.
    public var elevations: OrderedMap<Double>

    public init(name: String, segmentId: String? = nil, points: [RoutePoint], plan: RoutePlan, elevations: OrderedMap<Double> = [:]) {
        self.name = name
        self.segmentId = segmentId
        self.points = points
        self.plan = plan
        self.elevations = elevations
    }

    public static let serialNames = ["name", "segmentId", "points", "plan", "elevations"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            name: try r.required("name", j.string),
            segmentId: try r.nullable("segmentId", default: nil, j.string),
            points: try r.required("points") { (v) throws(ModelJSONError) in try j.decodeList(RoutePoint.self, from: v) },
            plan: try r.required("plan", j.object),
            elevations: try r.optional("elevations", default: [:]) { (v) throws(ModelJSONError) in try j.map(v, j.double) }
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("name", name)
        w.put("segmentId", segmentId)
        w.put("points", models: points)
        w.put("plan", model: plan)
        w.put("elevations", map: elevations)
    }
}

public struct Mission: Equatable, Sendable, ModelJSONCodable {
    public var routes: [MissionRoute]
    public var aircraft: MissionAircraft?

    public init(routes: [MissionRoute], aircraft: MissionAircraft? = nil) {
        self.routes = routes
        self.aircraft = aircraft
    }

    public static let serialNames = ["routes", "aircraft"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            routes: try r.required("routes") { (v) throws(ModelJSONError) in try j.decodeList(MissionRoute.self, from: v) },
            aircraft: try r.nullable("aircraft", default: nil, j.object)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("routes", models: routes)
        w.put("aircraft", model: aircraft)
    }
}
