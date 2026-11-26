import CoreJS

/// What can be done to one LZ diagram: analyse it, edit its graphics, change its view or flight data, rename it. Each operation is a
/// pure function from a diagram to a diagram, the same transformations the web's `lzWorkspaceReducer` makes (`useLzWorkspace.js`),
/// held to `contracts/fixtures/workspace/ops.json`, so a diagram edited here is the diagram the web would have made.
///
/// Two rules the web keeps are kept here, because they are what stop a document going wrong:
///  - **Nothing is analysed without a target**, so a result always belongs to a place.
///  - **Graphics are edited only after analysis** (`Diagram.canEditGraphics`): until then the diagram has no landing zone for them.
/// An operation that is refused returns the diagram it was given, so a caller can tell nothing happened by comparing.
///
/// `updatedAt` and the dirty flag are not touched: the repository stamps the first when it saves, and whether there are unsaved
/// changes is the record's own business. Graphics stay opaque JSON, so a field a newer web release adds to a helicopter survives.
public enum DiagramOps {
    /// The graphic collections an operation may name (the web's `LZ_GRAPHIC_COLLECTIONS`). `exportBox` is a single value.
    public static let graphicCollections: [String] = [
        "doghouses", "helicopters", "pzMarkers", "sectorsOfFire", "goArounds", "units", "measurements", "backstops",
    ]

    private static func isCollection(_ name: String) -> Bool { graphicCollections.contains { Kotlin.same($0, name) } }

    /// `cloneObject`: a non-object is nothing.
    private static func asObject(_ value: JSONTree?) -> JSONObject { value?.objectValue ?? JSONObject() }

    private static func cloneArray(_ value: JSONTree?) -> [JSONTree] { value?.arrayValue?.elements ?? [] }

    /// `{...base, ...patch}`: the base's keys where they were, the patch's values over them, its new keys after.
    static func merged(_ base: JSONObject, _ patch: JSONObject) -> JSONObject {
        JSONObject(base.entries.map { ($0.key, $0.value) } + patch.entries.map { ($0.key, $0.value) })
    }

    // MARK: Analysis

    /// The analysis came back: the diagram is analysed, and what it found is merged over what it had. Refused with no target.
    public static func completeAnalysis(_ diagram: Diagram, _ analysis: JSONTree?) -> Diagram {
        guard diagram.target != nil else { return diagram }
        var copy = diagram
        copy.status = .analyzed
        copy.analysis = merge(diagram.analysis, analysis)
        return copy
    }

    /// Part of an analysis written in place (a hand-drawn boundary): the status is left as it is. Refused with no target.
    public static func setAnalysisDraft(_ diagram: Diagram, _ analysis: JSONTree?) -> Diagram {
        guard diagram.target != nil else { return diagram }
        var copy = diagram
        copy.analysis = merge(diagram.analysis, analysis)
        return copy
    }

    /// Back to targeted, with no analysis. The graphics stay (the web keeps them). Refused with no target.
    public static func resetAnalysis(_ diagram: Diagram) -> Diagram {
        guard diagram.target != nil else { return diagram }
        var copy = diagram
        copy.status = .targeted
        copy.analysis = DiagramAnalysis()
        return copy
    }

    /// The slope raster, which is runtime only (never saved). Refused with no target.
    public static func setRuntimeTerrainData(_ diagram: Diagram, _ terrainData: JSONTree) -> Diagram {
        guard diagram.target != nil, diagram.analysis.terrainData != terrainData else { return diagram }
        var copy = diagram
        copy.analysis.terrainData = terrainData
        return copy
    }

    /// `{...analysis, ...patch}` over the fields a diagram keeps: a field the patch names replaces it, null included.
    private static func merge(_ current: DiagramAnalysis, _ patch: JSONTree?) -> DiagramAnalysis {
        let p = asObject(patch)
        return DiagramAnalysis(
            customLZ: p["customLZ"] ?? current.customLZ,
            detectedLZ: p["detectedLZ"] ?? current.detectedLZ,
            terrainData: p["terrainData"] ?? current.terrainData,
            results: p["results"] ?? current.results,
            gridElevation: p["gridElevation"] ?? current.gridElevation,
            latLong: p["latLong"] ?? current.latLong
        )
    }

    /// What the web does when an analysis comes back (`handleAnalysisComplete` in `App.js`): complete it, and make the standard SP
    /// and RP doghouses **once**, only if the diagram has none. A later analysis never makes them again.
    public static func afterAnalysis(_ diagram: Diagram, _ analysis: JSONTree?) -> Diagram {
        guard let target = diagram.target else { return diagram }
        let completed = completeAnalysis(diagram, analysis)
        if !diagram.graphics.doghouses.isEmpty { return completed }
        return setGraphicItems(completed, "doghouses", defaultDoghouses(target.lat, target.lon, diagram.id))
    }

    // MARK: Graphics

    private static func items(_ graphics: DiagramGraphics, _ collection: String) -> [JSONTree]? {
        switch collection {
        case "doghouses": graphics.doghouses
        case "helicopters": graphics.helicopters
        case "pzMarkers": graphics.pzMarkers
        case "sectorsOfFire": graphics.sectorsOfFire
        case "goArounds": graphics.goArounds
        case "units": graphics.units
        case "measurements": graphics.measurements
        case "backstops": graphics.backstops ?? []
        default: nil
        }
    }

    private static func withItems(_ graphics: DiagramGraphics, _ collection: String, _ items: [JSONTree]) -> DiagramGraphics {
        var g = graphics
        switch collection {
        case "doghouses": g.doghouses = items
        case "helicopters": g.helicopters = items
        case "pzMarkers": g.pzMarkers = items
        case "sectorsOfFire": g.sectorsOfFire = items
        case "goArounds": g.goArounds = items
        case "units": g.units = items
        case "measurements": g.measurements = items
        case "backstops": g.backstops = items
        default: break
        }
        return g
    }

    private static func withGraphics(_ diagram: Diagram, _ graphics: DiagramGraphics) -> Diagram {
        var copy = diagram
        copy.graphics = graphics
        return copy
    }

    /// Replaces the collections the patch names, and only those. The web's older names (`pzMarker`, `goAround`) are read as a
    /// fallback for a *named* plural, as the normalizer does, but a patch that names only an old name changes nothing. Refused before
    /// analysis.
    public static func setGraphics(_ diagram: Diagram, _ patch: JSONTree?) -> Diagram {
        guard diagram.canEditGraphics else { return diagram }
        let p = asObject(patch)
        var graphics = diagram.graphics
        for name in graphicCollections where p.has(name) {
            let source: JSONTree? =
                switch name {
                case "pzMarkers": JsValue.firstPresent(p["pzMarkers"], p["pzMarker"])
                case "goArounds": JsValue.firstPresent(p["goArounds"], p["goAround"])
                default: p[name]
                }
            graphics = withItems(graphics, name, cloneArray(source))
        }
        if p.has("exportBox") { graphics.exportBox = JsValue.present(p["exportBox"]) ?? .null }
        return withGraphics(diagram, graphics)
    }

    /// One collection set whole. A collection that does not exist, or a diagram not yet analysed, is refused. Items that are not
    /// objects are kept.
    public static func setGraphicCollection(_ diagram: Diagram, _ collection: String, _ items: JSONTree?) -> Diagram {
        guard diagram.canEditGraphics, isCollection(collection) else { return diagram }
        return withGraphics(diagram, withItems(diagram.graphics, collection, cloneArray(items)))
    }

    /// The same, for items already in hand.
    public static func setGraphicItems(_ diagram: Diagram, _ collection: String, _ items: [JSONTree]) -> Diagram {
        guard diagram.canEditGraphics, isCollection(collection) else { return diagram }
        return withGraphics(diagram, withItems(diagram.graphics, collection, items))
    }

    /// Adds a graphic, or merges into the one with the same `id` (fields it does not mention are kept). An item with no `id` is
    /// always added. An item that is not an object is refused.
    public static func upsertGraphic(_ diagram: Diagram, _ collection: String, _ item: JSONTree) -> Diagram {
        guard diagram.canEditGraphics, let object = item.objectValue, let current = items(diagram.graphics, collection) else {
            return diagram
        }
        let index = object["id"].flatMap { id in current.firstIndex { sameId($0, id) } }
        var next = current
        if let index, let existing = current[index].objectValue {
            next[index] = .object(merged(existing, object))
        } else {
            next.append(item)
        }
        return withGraphics(diagram, withItems(diagram.graphics, collection, next))
    }

    /// Merges `patch` into the graphic with `id`. A missing id, or a patch that is not an object, changes nothing.
    public static func patchGraphic(_ diagram: Diagram, _ collection: String, _ id: JSONTree?, _ patch: JSONTree?) -> Diagram {
        guard diagram.canEditGraphics, let id, let current = items(diagram.graphics, collection) else { return diagram }
        let changes = asObject(patch)
        let next = current.map { item -> JSONTree in
            guard sameId(item, id), let object = item.objectValue else { return item }
            return .object(merged(object, changes))
        }
        return withGraphics(diagram, withItems(diagram.graphics, collection, next))
    }

    public static func removeGraphic(_ diagram: Diagram, _ collection: String, _ id: JSONTree?) -> Diagram {
        guard diagram.canEditGraphics, let id, let current = items(diagram.graphics, collection) else { return diagram }
        return withGraphics(diagram, withItems(diagram.graphics, collection, current.filter { !sameId($0, id) }))
    }

    /// The id of a saved graphic as JavaScript writes it as text (`String(id)`): `"1700000000000"` for the number the web makes from
    /// the clock, `"pz-17"` for text, `"null"` for none. It is how the app names a graphic it holds (a selection), and the same text is
    /// what the web builds a pair's id from.
    ///
    /// `Js.text`, which is `String(id)` for every value. Kotlin's approximation agrees for text, null and whole numbers below 2^63; it
    /// differed for a fraction written with trailing zeros (`1.50`), a whole number past a Long, a list and an object.
    public static func idText(_ id: JSONTree?) -> String { Js.text(id) }

    /// The saved graphic in `collection` whose id reads as `idText`, if there is one.
    public static func graphic(_ diagram: Diagram, _ collection: String, _ idText: String) -> JSONObject? {
        items(diagram.graphics, collection)?.lazy.compactMap { $0.objectValue }.first { Kotlin.same(Self.idText($0["id"]), idText) }
    }

    /// `item?.id === id`: only an object with that very id, as JavaScript's strict equality sees it (the number 1 is not the text
    /// "1"), and two parsed objects are never the same reference.
    private static func sameId(_ item: JSONTree, _ id: JSONTree) -> Bool {
        guard let own = item.objectValue?["id"] else { return false }
        return Js.sameValue(own, id)
    }

    // MARK: Flight data, view, name: allowed at any stage

    /// Merges into the flight data. A patch that changes nothing returns the same diagram.
    public static func setFlightData(_ diagram: Diagram, _ patch: JSONTree?) -> Diagram {
        let next = merged(diagram.flightData, asObject(patch))
        guard JSONTree.object(next) != .object(diagram.flightData) else { return diagram }
        var copy = diagram
        copy.flightData = next
        return copy
    }

    /// Merges into the view (base map and overlays). A value the document cannot hold is read as the normalizer reads it.
    public static func setView(_ diagram: Diagram, _ patch: JSONTree?) -> Diagram {
        let current = JSONObject([
            ("mapStyle", .string(diagram.view.mapStyle)),
            ("showLZOutline", .bool(diagram.view.showLZOutline)),
            ("showHeatmap", .bool(diagram.view.showHeatmap)),
        ])
        var copy = diagram
        copy.view = DiagramNormalizer.normalizeView(merged(current, asObject(patch)))
        return copy
    }

    public static func setName(_ diagram: Diagram, _ name: String?) -> Diagram {
        var copy = diagram
        copy.name = name ?? ""
        return copy
    }

    // MARK: Default doghouses

    private static let doghouseOffset = 0.003

    /// The standard SP and RP doghouses, a little west of the target, with the ids namespaced by `namespace` (the diagram's id).
    public static func defaultDoghouses(_ lat: Double, _ lon: Double, _ namespace: String) -> [JSONTree] {
        defaultDoghouses(.array([.number(lat), .number(lon)]), .string(namespace))
    }

    /// `createDefaultDoghouses`, taking the target in any of the shapes the web accepts (`[lat, lon]`, `{lat, lon}`, `{lat, lng}`)
    /// and an optional namespace. A target that is not a position gives none. With no namespace the ids are made from the position.
    public static func defaultDoghouses(_ target: JSONTree?, _ namespace: JSONTree?) -> [JSONTree] {
        let latSource: JSONTree?
        let lonSource: JSONTree?
        switch target {
        case .array(let a)?:
            latSource = a[safe: 0]
            lonSource = a[safe: 1]
        case .object(let o)?:
            latSource = o["lat"]
            lonSource = JsValue.firstPresent(o["lng"], o["lon"])
        default:
            latSource = nil
            lonSource = nil
        }
        let lat = JsNumber.of(latSource)
        let lon = JsNumber.of(lonSource)
        guard lat.isFinite, lon.isFinite else { return [] }

        let fallback = "doghouse-\(JsNumber.toFixed(lat, 6))-\(JsNumber.toFixed(lon, 6))"
        // `String(namespace)`.
        let chosen = Js.truthy(namespace) ? Js.text(namespace) : fallback
        let prefix = safe(chosen)
        func doghouse(
            _ id: String, _ role: String, _ lat: Double, _ lon: Double, _ label: String, _ time: String, _ dist: String, _ speed: String
        )
            -> JSONTree
        {
            .object(
                JSONObject([
                    ("id", .string("\(prefix)-\(id)")), ("role", .string(role)), ("lat", .number(lat)), ("lon", .number(lon)),
                    ("id_val", .string(label)), ("heading", .string("000°")), ("time", .string(time)), ("dist", .string(dist)),
                    ("airspeed", .string(speed)),
                ])
            )
        }
        return [
            doghouse("sp1", "takeoff", lat, lon - doghouseOffset, "[SP1]", "01+57", "3.13km", "60 KIAS"),
            doghouse("rp1", "landing", lat - doghouseOffset / 2, lon - doghouseOffset, "[RP1]", "02+10", "5.20km", "40 KIAS"),
        ]
    }

    /// `replace(/[^a-zA-Z0-9_-]/g, "_")`. JavaScript's pattern works on UTF-16 units, so a character outside the basic plane becomes
    /// two.
    private static func safe(_ text: String) -> String {
        String(
            text.utf16.map { c -> Character in
                let ok = (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A) || (c >= 0x30 && c <= 0x39) || c == 0x5F || c == 0x2D
                return ok ? Character(Unicode.Scalar(c)!) : "_"
            }
        )
    }
}
