import CoreJS

/// A route the person sketched on the map, in the shape the web keeps it (`useRouteSketch`): its points in flight order (the AMPS
/// points that legs run between, and the shaping points that only bend the line), the plan for it, and the ground elevations fetched
/// for its points.
///
/// A mission imported from AMPS has the same points and plan (`MissionRoute`); a sketch also has a colour and can be hidden, and its
/// points have ids made here (a uuid each) rather than AMPS's.
public struct SketchRoute: Equatable, Sendable, ModelJSONCodable {
    /// `sketch-<time>-<random>` on the web: the prefix is how the web tells a sketch from an imported route.
    public var id: String
    public var name: String
    /// `#RRGGBB`.
    public var color: String
    public var visible: Bool
    public var points: [RoutePoint]
    public var plan: RoutePlan
    /// Ground elevation in feet by point id.
    public var elevations: OrderedMap<Double>
    /// AMPS's id for the route's segment, which ties it to its legs in a mission file. Only a route of an imported mission has one.
    public var segmentId: String?
    /// Fields a newer release wrote that this one does not know (`RouteSets`). Never part of the model's own JSON.
    public var extras: JSONObject

    public init(
        id: String,
        name: String,
        color: String,
        visible: Bool = true,
        points: [RoutePoint],
        plan: RoutePlan = RoutePlan(),
        elevations: OrderedMap<Double> = [:],
        segmentId: String? = nil,
        extras: JSONObject = JSONObject()
    ) {
        self.id = id
        self.name = name
        self.color = color
        self.visible = visible
        self.points = points
        self.plan = plan
        self.elevations = elevations
        self.segmentId = segmentId
        self.extras = extras
    }

    public static func == (a: SketchRoute, b: SketchRoute) -> Bool {
        Kotlin.same(a.id, b.id) && Kotlin.same(a.name, b.name) && Kotlin.same(a.color, b.color) && a.visible == b.visible
            && a.points == b.points && a.plan == b.plan && a.elevations == b.elevations && Kotlin.same(a.segmentId, b.segmentId)
            && JSONTree.object(a.extras) == .object(b.extras)
    }

    public static let serialNames = ["id", "name", "color", "visible", "points", "plan", "elevations", "segmentId"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            id: try r.required("id", j.string),
            name: try r.required("name", j.string),
            color: try r.required("color", j.string),
            visible: try r.optional("visible", default: true, j.bool),
            points: try r.required("points") { (v) throws(ModelJSONError) in try j.decodeList(RoutePoint.self, from: v) },
            plan: try r.optional("plan", default: RoutePlan(), j.object),
            elevations: try r.optional("elevations", default: [:]) { (v) throws(ModelJSONError) in try j.map(v, j.double) },
            segmentId: try r.nullable("segmentId", default: nil, j.string)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("id", id)
        w.put("name", name)
        w.put("color", color)
        w.put("visible", visible)
        w.put("points", models: points)
        w.put("plan", model: plan)
        w.put("elevations", map: elevations)
        w.put("segmentId", segmentId)
    }
}
