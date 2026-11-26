import CoreJS

/// One point of a saved set of local points, as the web keeps it after importing an AMPS `.LPS` (`{ id, name, description, group,
/// icon, elevationFt, lat, lon }`). `extras` are the fields a newer release adds, kept so an edit never drops them.
public struct SetPoint: Equatable, Sendable {
    /// The point's own id within its set (the web makes `lps-<n>-<random>`): how a map tap or a list row names it.
    public var id: String
    public var name: String
    public var description: String
    public var group: String
    public var icon: String
    /// The feet AMPS recorded, or nil if the file gave no number.
    public var elevationFt: Double?
    public var lat: Double
    public var lon: Double
    public var extras: JSONObject

    public init(
        id: String,
        name: String,
        description: String,
        group: String,
        icon: String,
        elevationFt: Double?,
        lat: Double,
        lon: Double,
        extras: JSONObject = JSONObject()
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.group = group
        self.icon = icon
        self.elevationFt = elevationFt
        self.lat = lat
        self.lon = lon
        self.extras = extras
    }

    public var at: LatLon { LatLon(lat: lat, lon: lon) }

    // By hand because JSONObject has no Equatable of its own; `extras` counts, as in Kotlin's data class.
    public static func == (a: SetPoint, b: SetPoint) -> Bool {
        Kotlin.same(a.id, b.id) && Kotlin.same(a.name, b.name) && Kotlin.same(a.description, b.description)
            && Kotlin.same(a.group, b.group) && Kotlin.same(a.icon, b.icon) && a.elevationFt == b.elevationFt && a.lat == b.lat
            && a.lon == b.lon && JSONTree.object(a.extras) == .object(b.extras)
    }
}

/// A saved set of local points: what one `.LPS` import made, under a name. The server keeps only the name and the list of points
/// (`/api/pointsets`), so the colour a set is drawn in and whether it is shown are each device's own and are not here.
///
/// Nothing is dropped on the way through. A field a newer release adds to a point is kept in its `extras`; an entry that cannot be
/// read as a point at all (no position) is kept in `unreadable` and written back after the others.
public struct PointSet: Equatable, Sendable {
    /// The record's identity (its `client_uuid`), which is how the set is found whatever else changes.
    public var id: String
    /// The server's id once it has one; nil for a set made here that has not synced.
    public var savedId: Int?
    public var name: String
    public var points: [SetPoint]
    /// Entries of the list that could not be read as a point, as they were.
    public var unreadable: [JSONTree]

    public init(id: String, savedId: Int? = nil, name: String, points: [SetPoint], unreadable: [JSONTree] = []) {
        self.id = id
        self.savedId = savedId
        self.name = name
        self.points = points
        self.unreadable = unreadable
    }

    /// How many points the set holds, the ones this version cannot read included (they are kept, so they count).
    public var pointCount: Int { points.count + unreadable.count }

    /// The point named `pointId`, if it is in the set.
    public func point(_ pointId: String) -> SetPoint? { points.first { Kotlin.same($0.id, pointId) } }
}

/// Reading a `PointSet` from the server's list of points and writing it back, and making one from an `.LPS` import.
public enum PointSets {
    private static let known = Set(["id", "name", "description", "group", "icon", "elevationFt", "lat", "lon"].map(UTF16Key.init))

    /// The set a record holds: its document is `{"points": [...]}`. Tolerant: a document with no `points` is an empty set, and an
    /// entry that has no position is kept as it was (`PointSet.unreadable`) instead of being thrown away or failing the whole set. A
    /// text field that is missing or not text is empty, and an elevation that is not a number is none.
    public static func parse(id: String, savedId: Int?, name: String, data: JSONObject) -> PointSet {
        let raw = data["points"]?.arrayValue?.elements ?? []
        var points: [SetPoint] = []
        var unreadable: [JSONTree] = []
        for (index, element) in raw.enumerated() {
            if let point = readPoint(element, index) { points.append(point) } else { unreadable.append(element) }
        }
        return PointSet(id: id, savedId: savedId, name: name, points: points, unreadable: unreadable)
    }

    /// The document for `set`: the points as the web writes them, then what could not be read.
    public static func serialize(_ set: PointSet) -> JSONObject {
        JSONObject([("points", .array(set.points.map(writePoint) + set.unreadable))])
    }

    /// A set from the points of an `.LPS` file. `newId` makes each point's id from its place in the file; the web's are
    /// `lps-<n>-<random>`, which a caller can reproduce, and nothing else depends on their shape.
    public static func fromLps(id: String, parsed: LocalPointSet, newId: (Int) -> String) -> PointSet {
        PointSet(
            id: id,
            name: parsed.name,
            points: parsed.points.enumerated().map { i, p in
                SetPoint(
                    id: newId(i), name: p.name, description: p.description, group: p.group, icon: p.icon, elevationFt: p.elevationFt,
                    lat: p.lat, lon: p.lon
                )
            }
        )
    }

    private static func readPoint(_ element: JSONTree, _ index: Int) -> SetPoint? {
        guard let obj = element.objectValue, let lat = number(obj["lat"]), let lon = number(obj["lon"]) else { return nil }
        let id = text(obj["id"])
        return SetPoint(
            // A point the web saved with no id still has to be named: by its place, which is stable while the set is not reordered.
            id: id.isEmpty ? "pt-\(index)" : id,
            name: text(obj["name"]),
            description: text(obj["description"]),
            group: text(obj["group"]),
            icon: text(obj["icon"]),
            elevationFt: number(obj["elevationFt"]),
            lat: lat,
            lon: lon,
            extras: JSONObject(obj.entries.filter { !known.contains(UTF16Key($0.key)) }.map { ($0.key, $0.value) })
        )
    }

    private static func writePoint(_ p: SetPoint) -> JSONTree {
        let typed = JSONObject([
            ("id", .string(p.id)),
            ("name", .string(p.name)),
            ("description", .string(p.description)),
            ("group", .string(p.group)),
            ("icon", .string(p.icon)),
            ("elevationFt", p.elevationFt.map { .number($0) } ?? .null),
            ("lat", .number(p.lat)),
            ("lon", .number(p.lon)),
        ])
        // A field this version knows is never overwritten by a stale extra.
        let extras = p.extras.entries.filter { !typed.has($0.key) }.map { ($0.key, $0.value) }
        return .object(JSONObject(typed.entries.map { ($0.key, $0.value) } + extras))
    }

    /// A finite JSON number (text, even numeric text, is no number here).
    private static func number(_ value: JSONTree?) -> Double? {
        guard case .number(let n)? = value, n.value.isFinite else { return nil }
        return n.value
    }

    private static func text(_ value: JSONTree?) -> String { value?.stringValue ?? "" }
}

/// What typing `typed` as a route point's name does: the name in capitals, and the local point it names, if any.
public struct LocalPointMatch: Hashable, Sendable {
    public var name: String
    /// The position of the local point the name refers to, or nil when it names none.
    public var at: LatLon?
    /// That point's charted elevation in feet, or nil when it has none.
    public var chartElevationFt: Double?

    public init(name: String, at: LatLon?, chartElevationFt: Double?) {
        self.name = name
        self.at = at
        self.chartElevationFt = chartElevationFt
    }
}

/// The loaded local points by name, the way the web's `RoutePlanSection` finds them: a name is a local point's (capitals, one leading
/// dot of what is typed ignored). A point with no name cannot be found, and of two with one name the later wins. Held to
/// `contracts/fixtures/localpoints/match.json`.
public struct LocalPointNames: Sendable {
    private var byName: [UTF16Key: SetPoint] = [:]

    public init(_ points: some Sequence<SetPoint>) {
        for p in points where !p.name.isEmpty { byName[UTF16Key(Kotlin.uppercase(p.name))] = p }
    }

    public func match(_ typed: String) -> LocalPointMatch {
        let name = Kotlin.uppercase(typed)
        let bare = name.utf16.first == 0x2E ? String(decoding: name.utf16.dropFirst(), as: UTF16.self) : name
        let found = byName[UTF16Key(bare)]
        return LocalPointMatch(name: name, at: found?.at, chartElevationFt: found?.elevationFt)
    }
}
