import CoreGeo
import CoreJS
import CoreModel

/// A line kept with a set of routes, beside its routes: a FLOT (`Flots`) or a phase line (`PhaseLines`). Each is
/// `{id, points: [{lat, lon}, …]}` with whatever its kind adds, under its kind's key of the set's document (`flots`, `phaseLines`),
/// the key left out when there are none. None of them is a route, so none is ever written to an AMPS or ForeFlight file.
///
/// Control measures (the owner, 2026-11-15): a FLOT and a phase line belong to no route set. A line is drawn into a set that holds
/// lines and never a route. What is stored is unchanged, a route set's document with no routes in it (`{version: 1, flots?,
/// phaseLines?, routes: []}`), so the server, the sync and the older apps know nothing new. The web's `feature/setLines/setLines.js`,
/// held to contracts/fixtures/planning/controlMeasures.json.
public enum SetLines {
    static let feetPerKm = 3280.84

    /// The id of this session's own set of control measures, and the prefix of one opened from the Library beside it.
    public static let measures = "measures"
    /// What a set of control measures is called in the Library and in a mission pack.
    public static let measuresName = "CONTROL MEASURES"

    /// Whether `setId` is a set of control measures in this session: its own, or one opened from the Library.
    public static func isMeasuresSetId(_ setId: JSONTree?) -> Bool {
        if case .string(let s)? = setId, s == measures { return true }
        // `String(setId ?? "")`: a number's text, `null` and nothing as "".
        let text = JsValue.present(setId).map { Js.text($0) } ?? ""
        return text.utf16.starts(with: (measures + "-").utf16)
    }

    /// `value?.length ?? 0`, for a list (or anything with a length, as JavaScript reads one).
    static func length(_ value: JSONTree?) -> Double {
        if case .number(let n)? = Js.prop(value, "length") { return n.value }
        return 0
    }

    /// How many lines a set holds, of every kind.
    public static func lineCount(_ set: JSONTree?) -> Double {
        length(Js.prop(set, "flots")) + length(Js.prop(set, "phaseLines"))
    }

    /// Whether a set (`{routes, flots?, phaseLines?}`) is control measures and no route set: it holds no routes, and either holds lines
    /// or was made to (`made`: its id, or in a pack its name, says so). A set with no routes and no lines that was not made for them is
    /// an empty route set.
    public static func isMeasuresSet(_ set: JSONTree?, made: Bool = false) -> Bool {
        length(Js.prop(set, "routes")) == 0 && (made || lineCount(set) > 0)
    }

    /// `Number.isFinite(point?.lat) && Number.isFinite(point?.lon)`: JSON numbers only.
    static func place(_ point: JSONTree) -> LatLon? {
        guard case .number(let lat)? = Js.prop(point, "lat"), case .number(let lon)? = Js.prop(point, "lon"),
            lat.value.isFinite, lon.value.isFinite
        else { return nil }
        return LatLon(lat: lat.value, lon: lon.value)
    }

    /// The line's points that are places.
    public static func linePoints(_ line: JSONTree?) -> [LatLon] {
        guard case .array(let list)? = Js.prop(line, "points") else { return [] }
        return list.compactMap(place)
    }

    /// Points as a line keeps them: a place each, and nothing else.
    public static func plainPoints(_ points: [LatLon]) -> JSONTree {
        .array(points.map { .object(["lat": .number($0.lat), "lon": .number($0.lon)]) })
    }

    /// The lines under `key` of a set's saved data; whatever is not an object with an id is none (an id of null is one).
    public static func linesFromData(_ data: JSONTree?, key: String) -> [JSONTree] {
        guard case .array(let list)? = Js.prop(data, key) else { return [] }
        return list.filter { line in
            if case .object(let o) = line { return o.has("id") }
            return false
        }
    }

    /// What is saved of lines: without the set they are filed under here.
    public static func savedLines(_ lines: [JSONTree]?) -> [JSONTree] {
        (lines ?? []).map { line in
            if case .object(let o) = line { return .object(o.removing("setId")) }
            return line
        }
    }

    /// The line's length along its points, in km, as the web adds it up (leg by leg from 0, the web's haversine on its sphere).
    public static func lineLengthKm(_ line: JSONTree?) -> Double {
        let points = linePoints(line)
        var feet = 0.0
        for (a, b) in zip(points, points.dropFirst()) { feet += GreatCircle.distanceFeet(a.lat, a.lon, b.lat, b.lon) }
        return feet / feetPerKm
    }

    /// `line.setId ?? null`.
    static func filedUnder(_ line: JSONTree) -> JSONTree { JsValue.present(Js.prop(line, "setId")) ?? .null }

    /// The sketched sets with their lines, and a set that has lines and no routes; this session's own first. `lists` is the lines of
    /// each kind under the name a set has them by (`flots`, `phaseLines`), in order, and each set gets every one of them. A set's id
    /// is its `setId`, null for this session's own.
    public static func setsWithLines(
        _ sketchSets: [JSONObject], lists: [(name: String, lines: [JSONTree])], isOpenedSetId: (JSONTree) -> Bool
    ) -> [JSONObject] {
        // A JavaScript Set of ids, in the order first seen: SameValueZero, which for ids (text, null) is ===.
        func contains(_ ids: [JSONTree], _ id: JSONTree) -> Bool { ids.contains { Js.strictEquals($0, id) } }
        let known: [JSONTree] = sketchSets.map { $0["setId"] ?? .null }
        var seen: [JSONTree] = []
        for line in lists.flatMap(\.lines) {
            let id = filedUnder(line)
            if !contains(seen, id) { seen.append(id) }
        }
        let extra = seen.filter { id in !contains(known, id) && (id.isNull || isOpenedSetId(id)) }
            .map { JSONObject([("setId", $0), ("routes", .array([]))]) }
        let session = extra.filter { $0["setId"]?.isNull == true }
        let opened = extra.filter { $0["setId"]?.isNull != true }
        return (session + sketchSets + opened).map { set in
            var result = set
            for (name, lines) in lists {
                // `(line.setId ?? null) === set.setId`: a set with no setId key matches no line.
                let mine = lines.filter { line in Js.strictEquals(filedUnder(line), set["setId"]) }
                result = result.setting(name, .array(mine))
            }
            return result
        }
    }
}
