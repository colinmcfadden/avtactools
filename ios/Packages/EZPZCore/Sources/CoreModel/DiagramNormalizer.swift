import CoreJS
import Foundation

/// Turns whatever was saved into the current diagram: a current v2 diagram, the flat snapshot `App.js` saved before diagrams were
/// versioned, or an API record wrapping either. A port of `normalizeLzDiagram` and its neighbours in `useLzWorkspace.js`, held to
/// `contracts/fixtures/workspace/diagram.json`.
///
/// The native apps have to open every diagram the web has ever saved, so this reads with the web's loose rules (`Js`, `JsNumber`,
/// `JsValue`) rather than strict types. It copies graphics without looking inside them, so fields it does not know survive.
public enum DiagramNormalizer {
    /// The clock and id source, injectable so tests do not depend on either. The web falls back to `new Date().toISOString()` and
    /// `crypto.randomUUID()` when a saved document has no timestamp or id.
    public struct Environment: Sendable {
        public let now: @Sendable () -> String
        public let newId: @Sendable () -> String

        public init(
            now: @escaping @Sendable () -> String = { DiagramNormalizer.isoMillis(Date()) },
            // Lower case, as `crypto.randomUUID()` and Java's `UUID.toString()` write one; Foundation writes capitals.
            newId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }
        ) {
            self.now = now
            self.newId = newId
        }
    }

    /// What the caller knows that overrides the document (`options` in the web). A nil field means "not given".
    public struct Options: Equatable, Sendable {
        public var id: String?
        public var savedId: JSONTree?
        public var name: String?
        public var dirty: Bool?
        public var createdAt: String?
        public var updatedAt: String?

        public init(
            id: String? = nil,
            savedId: JSONTree? = nil,
            name: String? = nil,
            dirty: Bool? = nil,
            createdAt: String? = nil,
            updatedAt: String? = nil
        ) {
            self.id = id
            self.savedId = savedId
            self.name = name
            self.dirty = dirty
            self.createdAt = createdAt
            self.updatedAt = updatedAt
        }
    }

    /// JavaScript's `Date.toISOString`: always milliseconds, always Z.
    public static func isoMillis(_ date: Date) -> String {
        let millis = Int64((date.timeIntervalSince1970 * 1000).rounded(.down))
        let (days, ms) = millis.quotientAndRemainder(dividingBy: 86_400_000)
        let day = ms < 0 ? days - 1 : days
        let inDay = ms < 0 ? ms + 86_400_000 : ms
        // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
        let z = day + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        func pad(_ n: Int64, _ width: Int) -> String { Kotlin.padStart(String(n), width, "0") }
        return "\(pad(y, 4))-\(pad(m, 2))-\(pad(d, 2))T\(pad(inDay / 3_600_000, 2)):\(pad(inDay / 60_000 % 60, 2))"
            + ":\(pad(inDay / 1000 % 60, 2)).\(pad(inDay % 1000, 3))Z"
    }

    /// Normalizes both the existing `[lat, lon]` app state and object-style inputs. `mgrs` is the grid to keep; the target's own
    /// `mgrs` is the fallback. Nil when there is no target or it is not a usable position.
    public static func normalizeTarget(_ target: JSONTree?, mgrs: JSONTree? = .string("")) -> DiagramTarget? {
        guard Js.truthy(target) else { return nil }
        let lat: JSONTree?
        let lon: JSONTree?
        switch target {
        case .array(let a)?:
            lat = a[safe: 0]
            lon = a[safe: 1]
        case .object(let o)?:
            lat = JsValue.firstPresent(o["lat"], o["latitude"])
            lon = JsValue.firstPresent(o["lon"], o["lng"], o["longitude"])
        default:
            // A number or a string has no .lat.
            lat = nil
            lon = nil
        }
        let parsedLat = JsNumber.of(lat)
        let parsedLon = JsNumber.of(lon)
        guard parsedLat.isFinite, parsedLon.isFinite else { return nil }

        let given = mgrs?.stringValue
        let own = target?["mgrs"]?.stringValue
        // `typeof mgrs === "string" && mgrs.trim()` keeps the string as given, untrimmed. JavaScript's trim, not Kotlin's: a grid of
        // only a byte order mark is blank there, and one of only U+001C is not.
        let grid: String = if let given, !Js.trim(given).isEmpty { given } else { own ?? "" }
        return DiagramTarget(lat: parsedLat, lon: parsedLon, mgrs: grid)
    }

    private static func cloneArray(_ value: JSONTree?) -> [JSONTree] { value?.arrayValue?.elements ?? [] }

    private static func normalizeGraphics(_ source: JSONObject) -> DiagramGraphics {
        let graphics = source["graphics"]?.objectValue ?? source
        return DiagramGraphics(
            doghouses: cloneArray(graphics["doghouses"]),
            helicopters: cloneArray(graphics["helicopters"]),
            // `pzMarker` and `goAround` are the field names in the flat App snapshot; new diagrams use the plurals. `??` falls through
            // on null only, so a present but non-array `pzMarkers` is an empty list, not a fallback.
            pzMarkers: cloneArray(JsValue.firstPresent(graphics["pzMarkers"], graphics["pzMarker"])),
            sectorsOfFire: cloneArray(graphics["sectorsOfFire"]),
            goArounds: cloneArray(JsValue.firstPresent(graphics["goArounds"], graphics["goAround"])),
            units: cloneArray(graphics["units"]),
            measurements: cloneArray(graphics["measurements"]),
            backstops: graphics["backstops"]?.arrayValue?.elements,
            exportBox: JsValue.present(graphics["exportBox"]) ?? .null
        )
    }

    private static func normalizeAnalysis(_ source: JSONObject) -> DiagramAnalysis {
        let analysis = source["analysis"]?.objectValue ?? source
        let defaults = DiagramAnalysis()
        return DiagramAnalysis(
            customLZ: JsValue.present(analysis["customLZ"]) ?? defaults.customLZ,
            detectedLZ: JsValue.present(analysis["detectedLZ"]) ?? defaults.detectedLZ,
            terrainData: JsValue.present(analysis["terrainData"]) ?? defaults.terrainData,
            results: JsValue.firstPresent(analysis["results"], analysis["analysisResults"]) ?? defaults.results,
            gridElevation: JsValue.present(analysis["gridElevation"]) ?? defaults.gridElevation,
            latLong: JsValue.present(analysis["latLong"]) ?? defaults.latLong
        )
    }

    static func normalizeView(_ source: JSONObject) -> DiagramView {
        let view = source["view"]?.objectValue ?? source
        let defaults = DiagramView()
        // A flag is read from a primitive only; a list or an object is the default.
        func flag(_ key: String, _ fallback: Bool) -> Bool {
            switch JsValue.present(view[key]) {
            case let value? where value.arrayValue == nil && value.objectValue == nil: Js.truthy(value)
            default: fallback
            }
        }
        return DiagramView(
            mapStyle: JsValue.string(JsValue.present(view["mapStyle"])) ?? defaults.mapStyle,
            showLZOutline: flag("showLZOutline", defaults.showLZOutline),
            showHeatmap: flag("showHeatmap", defaults.showHeatmap)
        )
    }

    private static func deriveStatus(_ requested: JSONTree?, _ target: DiagramTarget?, _ analysis: DiagramAnalysis) -> DiagramStatus {
        guard target != nil else { return .draft }
        let asked = requested?.stringValue == "analyzed"
        // Analysis results (a detected LZ or computed results) make the diagram analysed whatever it claimed.
        return asked || Js.truthy(analysis.detectedLZ) || Js.truthy(analysis.results) ? .analyzed : .targeted
    }

    /// One versioned diagram from a current diagram or a legacy flat snapshot. Anything that is not an object reads as an empty
    /// diagram.
    public static func normalize(_ source: JSONTree?, options: Options = Options(), env: Environment = Environment()) -> Diagram {
        let input = source?.objectValue ?? JSONObject()
        let target = normalizeTarget(
            JsValue.firstPresent(input["target"], input["targetLocation"]),
            mgrs: JsValue.firstPresent(
                input["target"]?.objectValue?["mgrs"],
                input["mapData"]?.objectValue?["mgrs"],
                input["gridInput"],
                .string("")
            )
        )
        let analysis = normalizeAnalysis(input)
        let createdAt =
            options.createdAt ?? JsValue.string(JsValue.firstPresent(input["createdAt"], input["created_at"])) ?? env.now()
        let updatedAt =
            options.updatedAt ?? JsValue.string(JsValue.firstPresent(input["updatedAt"], input["updated_at"])) ?? createdAt
        let id = options.id ?? JsValue.string(input["id"]) ?? env.newId()
        let givenSavedId: JSONTree? = if let saved = options.savedId, !saved.isNull { saved } else { nil }

        return Diagram(
            id: id,
            savedId: givenSavedId ?? JsValue.present(input["savedId"]) ?? .null,
            name: options.name ?? JsValue.string(input["name"]) ?? "",
            dirty: options.dirty ?? Js.truthy(input["dirty"]),
            createdAt: createdAt,
            updatedAt: updatedAt,
            status: deriveStatus(input["status"], target, analysis),
            target: target,
            mapData: input["mapData"]?.objectValue ?? JSONObject(),
            flightData: input["flightData"]?.objectValue ?? JSONObject(),
            analysis: analysis,
            graphics: normalizeGraphics(input),
            view: normalizeView(input)
        )
    }

    /// The current App save shape, or the API record wrapping it (`{ id, name, created_at, updated_at, lz_data }`), as exactly one
    /// versioned diagram.
    public static func normalizeLegacySnapshot(
        _ snapshot: JSONTree?,
        options: Options = Options(),
        env: Environment = Environment()
    ) -> Diagram {
        let wrapper = snapshot?.objectValue ?? JSONObject()
        let record = wrapper["lz_data"]?.objectValue
        let isApiRecord = record != nil
        let givenSavedId: JSONTree? = if let saved = options.savedId, !saved.isNull { saved } else { nil }
        return normalize(
            .object(record ?? wrapper),
            options: Options(
                id: options.id ?? JsValue.string(wrapper["clientId"]),
                savedId: givenSavedId ?? (isApiRecord ? JsValue.present(wrapper["id"]) : JsValue.present(wrapper["savedId"])),
                name: options.name ?? JsValue.string(wrapper["name"]) ?? "",
                dirty: options.dirty ?? false,
                createdAt: options.createdAt ?? (isApiRecord ? JsValue.string(wrapper["created_at"]) : nil),
                updatedAt: options.updatedAt ?? (isApiRecord ? JsValue.string(wrapper["updated_at"]) : nil)
            ),
            env: env
        )
    }

    /// A blank, target-bound diagram. Graphics are deliberately empty: defaults (such as doghouses) are created only after analysis
    /// has succeeded for this particular diagram. Nil when `target` is not a usable position.
    public static func fromTarget(
        _ target: JSONTree?,
        mgrs: String = "",
        id: String? = nil,
        name: String = "",
        savedId: JSONTree? = nil,
        mapData: JSONObject = JSONObject(),
        view: JSONObject? = nil,
        createdAt: String? = nil,
        env: Environment = Environment()
    ) -> Diagram? {
        guard let normalizedTarget = normalizeTarget(target, mgrs: .string(mgrs)) else { return nil }
        let timestamp = createdAt ?? env.now()
        let mapGrid = JsValue.string(mapData["mgrs"]) ?? ""
        let mapMgrs = normalizedTarget.mgrs.isEmpty ? mapGrid : normalizedTarget.mgrs
        var document: [(String, JSONTree)] = [
            ("id", .string(id ?? env.newId())),
            ("savedId", savedId ?? .null),
            ("name", .string(name)),
            ("dirty", .bool(true)),
            ("createdAt", .string(timestamp)),
            ("updatedAt", .string(timestamp)),
            ("target", .object(DiagramJson.encode(normalizedTarget))),
            ("mapData", .object(mapData.setting("mgrs", .string(mapMgrs)))),
        ]
        if let view { document.append(("view", .object(view))) }
        document.append(("status", .string("targeted")))
        return normalize(
            .object(JSONObject(document)),
            options: Options(id: id, createdAt: timestamp, updatedAt: timestamp),
            env: env
        )
    }

    /// What is saved for a diagram: clean, and without the terrain raster, which is regenerated from the analysis boundary when the
    /// diagram is opened.
    public static func serialize(_ diagram: Diagram, includeTerrainData: Bool = false) -> Diagram {
        var copy = diagram
        copy.dirty = false
        if !includeTerrainData { copy.analysis.terrainData = .null }
        return copy
    }

    /// One canonical session shape from anything a session could start from: nothing, a workspace, a list of diagrams, a single
    /// diagram, or a legacy snapshot. A repeated id gets `-2`, `-3` and so on, in order; the active diagram is the one asked for, else
    /// the last.
    public static func createWorkspace(_ source: JSONTree?, env: Environment = Environment()) -> Workspace {
        let object = source?.objectValue
        let raw: [JSONTree]
        if case .array(let a)? = source {
            raw = a.elements
        } else if let diagrams = object?["diagrams"]?.arrayValue {
            raw = diagrams.elements
        } else if let order = object?["diagramOrder"]?.arrayValue, let byId = object?["diagramsById"]?.objectValue {
            // Each id read as the key it names (`diagramsById[id]`, `String(id)` for a primitive); a list or an object names "".
            raw = order.map { id in
                let key = id.arrayValue == nil && id.objectValue == nil ? Js.text(id) : ""
                return byId[key] ?? .null
            }
        } else if let object, Js.truthy(object["targetLocation"]) || Js.truthy(object["target"]) || Js.truthy(object["graphics"]) {
            raw = [.object(object)]
        } else {
            raw = []
        }

        var byId = OrderedMap<Diagram>()
        var order: [String] = []
        // `.filter(Boolean)`.
        for entry in raw where Js.truthy(entry) {
            var normalized = normalize(entry, options: Options(dirty: Js.truthy(entry["dirty"])), env: env)
            var id = normalized.id
            if byId.has(id) {
                var suffix = 2
                var candidate = "\(id)-\(suffix)"
                while byId.has(candidate) {
                    suffix += 1
                    candidate = "\(id)-\(suffix)"
                }
                id = candidate
            }
            normalized.id = id
            byId[id] = normalized
            order.append(id)
        }

        let requested = object?["activeDiagramId"]?.stringValue
        let active = requested.flatMap { byId.has($0) ? $0 : nil } ?? order.last
        return Workspace(diagramsById: byId, diagramOrder: order, activeDiagramId: active)
    }
}
