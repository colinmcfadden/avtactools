import CoreJS

/// The routes saved together under one name: what the web keeps in a saved route's `route_data` (`{ version: 1, routes: [...] }`,
/// the routes drawn in one sitting) and what one `kind: sketch` record on the server holds. One record is one `RouteSet`, whole, so
/// a set made here opens on the web and back.
///
/// Nothing is dropped on the way through. A field a newer web release adds to the document, a route, a point or the plan is kept in
/// `extras` and written back; a route this version cannot read at all is kept in `unreadable` and written back after the others. (A
/// field *inside* a per-point override, or inside an airspeed, altitude or wind, is the one place not kept: those have no room.)
public struct RouteSet: Equatable, Sendable {
    /// The record's identity (its `client_uuid`), which is how the set is found whatever else changes.
    public var id: String
    /// The server's id once it has one; nil for a set made here that has not synced.
    public var savedId: Int?
    public var name: String
    public var routes: [SketchRoute]
    /// Top-level fields of the document other than `routes`, `version` included: a version this release does not know is written
    /// back as it was found.
    public var extras: JSONObject
    /// Routes in the document that could not be read as a route, as they were.
    public var unreadable: [JSONTree]
    /// Set when this is an imported AMPS mission and not a set drawn here. A mission's **file** is its document; the link is never
    /// part of `RouteSets`' JSON (a mission's `route_data` is only a summary).
    public var mission: MissionLink?

    public init(
        id: String,
        savedId: Int? = nil,
        name: String,
        routes: [SketchRoute],
        extras: JSONObject = JSONObject(),
        unreadable: [JSONTree] = [],
        mission: MissionLink? = nil
    ) {
        self.id = id
        self.savedId = savedId
        self.name = name
        self.routes = routes
        self.extras = extras
        self.unreadable = unreadable
        self.mission = mission
    }

    public static func == (a: RouteSet, b: RouteSet) -> Bool {
        Kotlin.same(a.id, b.id) && a.savedId == b.savedId && Kotlin.same(a.name, b.name) && a.routes == b.routes
            && JSONTree.object(a.extras) == .object(b.extras) && a.unreadable == b.unreadable && a.mission == b.mission
    }

    /// The route named `routeId`, if it is in the set.
    public func route(_ routeId: String) -> SketchRoute? { routes.first { Kotlin.same($0.id, routeId) } }

    /// The set with `routeId` changed by `change`; the same set when there is no such route or `change` leaves it as it was.
    public func mapRoute(_ routeId: String, _ change: (SketchRoute) -> SketchRoute) -> RouteSet {
        var found = false
        let next = routes.map { r -> SketchRoute in
            guard Kotlin.same(r.id, routeId) else { return r }
            found = true
            return change(r)
        }
        guard found, next != routes else { return self }
        var copy = self
        copy.routes = next
        return copy
    }

    /// The set with `route` added at the end.
    public func plus(_ route: SketchRoute) -> RouteSet {
        var copy = self
        copy.routes.append(route)
        return copy
    }

    /// The set without `routeId`.
    public func without(_ routeId: String) -> RouteSet {
        guard routes.contains(where: { Kotlin.same($0.id, routeId) }) else { return self }
        var copy = self
        copy.routes.removeAll { Kotlin.same($0.id, routeId) }
        return copy
    }
}

/// What makes a `RouteSet` an imported mission: `fileName` is what its `.msnx` is called.
public struct MissionLink: Hashable, Sendable {
    public var fileName: String
    public init(fileName: String) { self.fileName = fileName }
}

/// Reading a `RouteSet` from the web's `route_data` and writing it back.
public enum RouteSets {
    /// The document's own version, which the web writes as 1. A document made with no version is given this one.
    public static let version = 1

    private static let json = ModelJSON.routeSets

    /// The set a record holds. Tolerant: a document with no `routes` is an empty set, a ground elevation or a point's "to" values
    /// that are null are none (as the web reads them, and as a mission pack leaves a key that went), and a route that cannot be read
    /// as one is kept as it was (`RouteSet.unreadable`) instead of being thrown away or failing the whole set.
    public static func parse(id: String, savedId: Int?, name: String, data: JSONObject) -> RouteSet {
        let raw = data["routes"]?.arrayValue?.elements ?? []
        var routes: [SketchRoute] = []
        var unreadable: [JSONTree] = []
        for element in raw {
            if let route = readRoute(element) { routes.append(route) } else { unreadable.append(element) }
        }
        let extras = data.removing("routes")
        return RouteSet(id: id, savedId: savedId, name: name, routes: routes, extras: extras, unreadable: unreadable)
    }

    /// The document for `set`: the routes as the web writes them, then what could not be read, with the fields this version does not
    /// know kept.
    ///
    /// The keys come out in JavaScript's order (CoreJS's `JSONObject` puts array-index keys first), where Kotlin's map kept them as
    /// added: the same document to every reader, the web's `JSON.parse` included.
    public static func serialize(_ set: RouteSet) -> JSONObject {
        let routes = set.routes.map(writeRoute) + set.unreadable
        var entries: [(String, JSONTree)] = [("version", .number(Double(version)))]
        entries += set.extras.entries.map { ($0.key, $0.value) }
        entries.append(("routes", .array(routes)))
        return JSONObject(entries)
    }

    /// What the server keeps beside a mission's file, for lists: `{version: 1, routes: [{name, color}]}`, as the web saves it.
    public static func missionSummary(_ routes: [SketchRoute]) -> JSONObject {
        JSONObject([
            ("version", .number(Double(version))),
            ("routes", .array(routes.map { .object(JSONObject([("name", .string($0.name)), ("color", .string($0.color))])) })),
        ])
    }

    /// The colour each route of a mission was given, by its place in the file; nil where the summary names none. The summary is the
    /// server's text and a colour reaches the map as a style value, so anything that is not `#RRGGBB` is none.
    public static func missionColors(_ summary: JSONObject) -> [String?] {
        (summary["routes"]?.arrayValue?.elements ?? []).map { route in
            guard let color = route["color"]?.stringValue, isColor(color) else { return nil }
            return color
        }
    }

    /// `^#[0-9A-Fa-f]{6}$`, matched whole.
    static func isColor(_ text: String) -> Bool {
        let u = Array(text.utf16)
        guard u.count == 7, u[0] == 0x23 else { return false }
        return u.dropFirst().allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 | 0x20 >= 0x61 && $0 | 0x20 <= 0x66) }
    }

    private static func readRoute(_ element: JSONTree) -> SketchRoute? {
        guard let obj = element.objectValue else { return nil }
        guard var route = try? json.decode(SketchRoute.self, from: .object(withoutNullEntries(obj))) else { return nil }
        let rawPoints = obj["points"]?.arrayValue
        route.points = route.points.enumerated().map { i, p in
            guard let raw = rawPoints?[safe: i]?.objectValue else { return p }
            var point = p
            point.extras = unknown(raw, RoutePoint.serialNames)
            return point
        }
        if let rawPlan = obj["plan"]?.objectValue { route.plan.extras = unknown(rawPlan, RoutePlan.serialNames) }
        route.extras = unknown(obj, SketchRoute.serialNames)
        return route
    }

    private static func writeRoute(_ route: SketchRoute) -> JSONTree {
        let points = route.points.map { p in JSONTree.object(withExtras(json.encodeObject(p), p.extras)) }
        let plan = withExtras(json.encodeObject(route.plan), route.plan.extras)
        let typed = json.encodeObject(route).setting("points", .array(points)).setting("plan", .object(plan))
        return .object(withExtras(typed, route.extras))
    }

    /// `route` with the null entries of its two maps taken out, for the types to read: a ground elevation, or a point's "to" values,
    /// that is null is none, as the web reads them (`elevationsFt[p.id] ?? null`, `plan.perPoint?.[p.id]?.altitude`). A mission
    /// pack's route has them, because no operation deletes a key: one that went is sent as null. Read strictly, one such entry made
    /// the whole route one this version could not read, and a pack's editor then wrote the route a second time.
    private static func withoutNullEntries(_ route: JSONObject) -> JSONObject {
        let elevations = route["elevations"]?.objectValue.flatMap(withoutNulls)
        let plan = route["plan"]?.objectValue
        let perPoint = plan?["perPoint"]?.objectValue.flatMap(withoutNulls)
        if elevations == nil && perPoint == nil { return route }
        var fields = route
        if let elevations { fields = fields.setting("elevations", .object(elevations)) }
        if let plan, let perPoint { fields = fields.setting("plan", .object(plan.setting("perPoint", .object(perPoint)))) }
        return fields
    }

    /// This object without its entries that are null; nil when it has none.
    private static func withoutNulls(_ object: JSONObject) -> JSONObject? {
        guard object.entries.contains(where: { $0.value.isNull }) else { return nil }
        return JSONObject(object.entries.filter { !$0.value.isNull }.map { ($0.key, $0.value) })
    }

    /// The keys of `obj` that the type does not have: what a newer release added.
    private static func unknown(_ obj: JSONObject, _ known: [String]) -> JSONObject {
        let names = Set(known.map(UTF16Key.init))
        return JSONObject(obj.entries.filter { !names.contains(UTF16Key($0.key)) }.map { ($0.key, $0.value) })
    }

    /// `typed` with `extras` added, `typed` winning: a field this version knows is never overwritten by a stale copy.
    private static func withExtras(_ typed: JSONObject, _ extras: JSONObject) -> JSONObject {
        if extras.isEmpty { return typed }
        return JSONObject(
            typed.entries.map { ($0.key, $0.value) } + extras.entries.filter { !typed.has($0.key) }.map { ($0.key, $0.value) })
    }
}
