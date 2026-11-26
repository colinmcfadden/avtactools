import CoreJS

// The saved LZ/PZ diagram, in the web's shape (`useLzWorkspace.js`, schema 2).
//
// A diagram made on a tablet opens on the web and back, so field names are the web's, camelCase. What the model does not need to
// understand stays raw JSON: each graphic is an opaque object, so a field a newer web release adds to a helicopter or a sector
// survives a round trip through an older app.

public let diagramSchemaVersion: Int = 2

public enum DiagramStatus: String, Sendable, Hashable {
    case draft
    case targeted
    case analyzed
}

/// The point the LZ is planned around. `mgrs` is whatever the user typed, not recomputed.
public struct DiagramTarget: Hashable, Sendable, ModelJSONEncodable {
    public var lat: Double
    public var lon: Double
    public var mgrs: String

    public init(lat: Double, lon: Double, mgrs: String = "") {
        self.lat = lat
        self.lon = lon
        self.mgrs = mgrs
    }

    public func encode(to writer: inout ModelJSON.ObjectWriter) {
        writer.put("lat", lat)
        writer.put("lon", lon)
        writer.put("mgrs", mgrs)
    }
}

/// Analysis inputs and results. Every value is opaque JSON: the boundary polygons and results have shapes only the analysis code
/// reads, and `terrainData` is a (possibly base64) raster that is runtime-only and never saved.
public struct DiagramAnalysis: Equatable, Sendable, ModelJSONEncodable {
    public var customLZ: JSONTree
    public var detectedLZ: JSONTree
    public var terrainData: JSONTree
    public var results: JSONTree
    public var gridElevation: JSONTree
    public var latLong: JSONTree

    public init(
        customLZ: JSONTree = .null,
        detectedLZ: JSONTree = .null,
        terrainData: JSONTree = .null,
        results: JSONTree = .null,
        gridElevation: JSONTree = .string(""),
        latLong: JSONTree = .string("")
    ) {
        self.customLZ = customLZ
        self.detectedLZ = detectedLZ
        self.terrainData = terrainData
        self.results = results
        self.gridElevation = gridElevation
        self.latLong = latLong
    }

    public func encode(to writer: inout ModelJSON.ObjectWriter) {
        writer.put("customLZ", customLZ)
        writer.put("detectedLZ", detectedLZ)
        writer.put("terrainData", terrainData)
        writer.put("results", results)
        writer.put("gridElevation", gridElevation)
        writer.put("latLong", latLong)
    }
}

/// Each collection holds opaque graphics (non-object entries too: the web keeps them).
public struct DiagramGraphics: Equatable, Sendable, ModelJSONEncodable {
    public var doghouses: [JSONTree]
    public var helicopters: [JSONTree]
    public var pzMarkers: [JSONTree]
    public var sectorsOfFire: [JSONTree]
    public var goArounds: [JSONTree]
    public var units: [JSONTree]
    public var measurements: [JSONTree]
    /// Drawn on the web alone so far. Kept as it came so a save here does not lose one, and left out of a diagram that has none,
    /// as the web leaves it.
    public var backstops: [JSONTree]?
    public var exportBox: JSONTree

    public init(
        doghouses: [JSONTree] = [],
        helicopters: [JSONTree] = [],
        pzMarkers: [JSONTree] = [],
        sectorsOfFire: [JSONTree] = [],
        goArounds: [JSONTree] = [],
        units: [JSONTree] = [],
        measurements: [JSONTree] = [],
        backstops: [JSONTree]? = nil,
        exportBox: JSONTree = .null
    ) {
        self.doghouses = doghouses
        self.helicopters = helicopters
        self.pzMarkers = pzMarkers
        self.sectorsOfFire = sectorsOfFire
        self.goArounds = goArounds
        self.units = units
        self.measurements = measurements
        self.backstops = backstops
        self.exportBox = exportBox
    }

    public func encode(to writer: inout ModelJSON.ObjectWriter) {
        writer.put("doghouses", .array(doghouses))
        writer.put("helicopters", .array(helicopters))
        writer.put("pzMarkers", .array(pzMarkers))
        writer.put("sectorsOfFire", .array(sectorsOfFire))
        writer.put("goArounds", .array(goArounds))
        writer.put("units", .array(units))
        writer.put("measurements", .array(measurements))
        // Never written as null (kotlinx's EncodeDefault NEVER): a diagram with none has no such field.
        if let backstops { writer.put("backstops", .array(backstops)) }
        writer.put("exportBox", exportBox)
    }
}

public struct DiagramView: Hashable, Sendable, ModelJSONEncodable {
    public var mapStyle: String
    public var showLZOutline: Bool
    public var showHeatmap: Bool

    public init(mapStyle: String = "satellite", showLZOutline: Bool = true, showHeatmap: Bool = false) {
        self.mapStyle = mapStyle
        self.showLZOutline = showLZOutline
        self.showHeatmap = showHeatmap
    }

    public func encode(to writer: inout ModelJSON.ObjectWriter) {
        writer.put("mapStyle", mapStyle)
        writer.put("showLZOutline", showLZOutline)
        writer.put("showHeatmap", showHeatmap)
    }
}

public struct Diagram: Equatable, Sendable, ModelJSONEncodable {
    public var schemaVersion: Int
    /// The client's id, a UUID, assigned at creation so a diagram has an identity before the server has seen it.
    public var id: String
    /// The server's record id once saved: a number, kept as the JSON it arrived as.
    public var savedId: JSONTree
    public var name: String
    public var dirty: Bool
    public var createdAt: String
    public var updatedAt: String
    public var status: DiagramStatus
    public var target: DiagramTarget?
    public var mapData: JSONObject
    public var flightData: JSONObject
    public var analysis: DiagramAnalysis
    public var graphics: DiagramGraphics
    public var view: DiagramView

    public init(
        schemaVersion: Int = diagramSchemaVersion,
        id: String,
        savedId: JSONTree = .null,
        name: String = "",
        dirty: Bool = false,
        createdAt: String,
        updatedAt: String,
        status: DiagramStatus = .draft,
        target: DiagramTarget? = nil,
        mapData: JSONObject = JSONObject(),
        flightData: JSONObject = JSONObject(),
        analysis: DiagramAnalysis = DiagramAnalysis(),
        graphics: DiagramGraphics = DiagramGraphics(),
        view: DiagramView = DiagramView()
    ) {
        self.schemaVersion = schemaVersion
        self.id = id
        self.savedId = savedId
        self.name = name
        self.dirty = dirty
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.status = status
        self.target = target
        self.mapData = mapData
        self.flightData = flightData
        self.analysis = analysis
        self.graphics = graphics
        self.view = view
    }

    /// `canAnalyzeLzDiagram`: a diagram needs a target before it can be analysed.
    public var canAnalyze: Bool { target != nil }

    /// `canEditLzDiagramGraphics`: graphics are placed only after a successful analysis.
    public var canEditGraphics: Bool { target != nil && status == .analyzed }

    // Written by hand because JSONObject is a class with no Equatable of its own: equal as Kotlin's data class is, field by field,
    // the JSON structurally.
    public static func == (a: Diagram, b: Diagram) -> Bool {
        a.schemaVersion == b.schemaVersion && Kotlin.same(a.id, b.id) && a.savedId == b.savedId && Kotlin.same(a.name, b.name)
            && a.dirty == b.dirty && Kotlin.same(a.createdAt, b.createdAt) && Kotlin.same(a.updatedAt, b.updatedAt)
            && a.status == b.status && a.target == b.target && JSONTree.object(a.mapData) == .object(b.mapData)
            && JSONTree.object(a.flightData) == .object(b.flightData) && a.analysis == b.analysis && a.graphics == b.graphics
            && a.view == b.view
    }

    public func encode(to writer: inout ModelJSON.ObjectWriter) {
        writer.put("schemaVersion", schemaVersion)
        writer.put("id", id)
        writer.put("savedId", savedId)
        writer.put("name", name)
        writer.put("dirty", dirty)
        writer.put("createdAt", createdAt)
        writer.put("updatedAt", updatedAt)
        writer.put("status", status.rawValue)
        writer.put("target", model: target)
        writer.put("mapData", .object(mapData))
        writer.put("flightData", .object(flightData))
        writer.put("analysis", writer.json.encode(analysis))
        writer.put("graphics", writer.json.encode(graphics))
        writer.put("view", writer.json.encode(view))
    }
}

/// A session's diagrams, in the order they were opened, and which one is showing.
public struct Workspace: Equatable, Sendable, ModelJSONEncodable {
    public var schemaVersion: Int
    public var diagramsById: OrderedMap<Diagram>
    public var diagramOrder: [String]
    public var activeDiagramId: String?

    public init(
        schemaVersion: Int = diagramSchemaVersion,
        diagramsById: OrderedMap<Diagram> = [:],
        diagramOrder: [String] = [],
        activeDiagramId: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.diagramsById = diagramsById
        self.diagramOrder = diagramOrder
        self.activeDiagramId = activeDiagramId
    }

    public func encode(to writer: inout ModelJSON.ObjectWriter) {
        writer.put("schemaVersion", schemaVersion)
        writer.put("diagramsById", models: diagramsById)
        writer.put("diagramOrder", .array(diagramOrder.map(JSONTree.string)))
        writer.put("activeDiagramId", activeDiagramId)
    }
}
