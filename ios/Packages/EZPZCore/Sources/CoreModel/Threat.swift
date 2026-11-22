import CoreJS

// A threat, as the web sends it to the server and writes it into an AMPS `.ths` file (`threatToPayload` in `threatModel.js`). What is
// saved to the Library is the web's document, read and written by `ThreatSets`, so nothing here is a persistence model.
//
// A threat has a position, a MIL-STD-2525C symbol id, and up to two radars: type 0 is Detection, type 1 is Engagement. Each radar has
// a range, an antenna height, an AGL or MSL flag and up to three aircraft-altitude bands whose terrain mask is drawn as an overlay.

/// One aircraft-altitude band of a radar: the mask is computed for an aircraft at `altFt`.
public struct RadarBand: Hashable, Sendable, ModelJSONCodable {
    public var altFt: Double
    /// The overlay colour as `#rrggbb`. Not stored in a `.ths`: reading one gives the type's default.
    public var color: String
    public var alpha: Double
    /// AMPS's colour number for the band, which is what a `.ths` stores.
    public var colorIndex: Int
    public var viewable: Bool

    public init(altFt: Double, color: String, alpha: Double, colorIndex: Int, viewable: Bool) {
        self.altFt = altFt
        self.color = color
        self.alpha = alpha
        self.colorIndex = colorIndex
        self.viewable = viewable
    }

    public static let serialNames = ["altFt", "color", "alpha", "colorIndex", "viewable"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            altFt: try r.required("altFt", j.double),
            color: try r.required("color", j.string),
            alpha: try r.required("alpha", j.double),
            colorIndex: try r.required("colorIndex", j.int),
            viewable: try r.required("viewable", j.bool)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("altFt", altFt)
        w.put("color", color)
        w.put("alpha", alpha)
        w.put("colorIndex", colorIndex)
        w.put("viewable", viewable)
    }
}

public struct Radar: Hashable, Sendable, ModelJSONCodable {
    /// `Radars.detection` or `Radars.engagement`.
    public var type: Int
    public var rangeNmi: Double
    public var antennaHeightFt: Double
    public var aglNotMsl: Bool
    public var showMask: Bool
    public var showRangeRings: Bool
    public var bands: [RadarBand]

    public init(
        type: Int, rangeNmi: Double, antennaHeightFt: Double, aglNotMsl: Bool, showMask: Bool, showRangeRings: Bool, bands: [RadarBand]
    ) {
        self.type = type
        self.rangeNmi = rangeNmi
        self.antennaHeightFt = antennaHeightFt
        self.aglNotMsl = aglNotMsl
        self.showMask = showMask
        self.showRangeRings = showRangeRings
        self.bands = bands
    }

    public static let serialNames = ["type", "rangeNmi", "antennaHeightFt", "aglNotMsl", "showMask", "showRangeRings", "bands"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            type: try r.required("type", j.int),
            rangeNmi: try r.required("rangeNmi", j.double),
            antennaHeightFt: try r.required("antennaHeightFt", j.double),
            aglNotMsl: try r.required("aglNotMsl", j.bool),
            showMask: try r.required("showMask", j.bool),
            showRangeRings: try r.required("showRangeRings", j.bool),
            bands: try r.required("bands") { (v) throws(ModelJSONError) in try j.decodeList(RadarBand.self, from: v) }
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("type", type)
        w.put("rangeNmi", rangeNmi)
        w.put("antennaHeightFt", antennaHeightFt)
        w.put("aglNotMsl", aglNotMsl)
        w.put("showMask", showMask)
        w.put("showRangeRings", showRangeRings)
        w.put("bands", models: bands)
    }
}

public struct Threat: Hashable, Sendable, ModelJSONCodable {
    public var name: String
    public var milstdId: String
    public var lat: Double
    public var lon: Double
    public var information: String
    public var source: String
    public var showThreat: Bool
    public var radars: [Radar]

    public init(
        name: String, milstdId: String, lat: Double, lon: Double, information: String, source: String, showThreat: Bool = true,
        radars: [Radar]
    ) {
        self.name = name
        self.milstdId = milstdId
        self.lat = lat
        self.lon = lon
        self.information = information
        self.source = source
        self.showThreat = showThreat
        self.radars = radars
    }

    public static let serialNames = ["name", "milstdId", "lat", "lon", "information", "source", "showThreat", "radars"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            name: try r.required("name", j.string),
            milstdId: try r.required("milstdId", j.string),
            lat: try r.required("lat", j.double),
            lon: try r.required("lon", j.double),
            information: try r.required("information", j.string),
            source: try r.required("source", j.string),
            showThreat: try r.optional("showThreat", default: true, j.bool),
            radars: try r.required("radars") { (v) throws(ModelJSONError) in try j.decodeList(Radar.self, from: v) }
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("name", name)
        w.put("milstdId", milstdId)
        w.put("lat", lat)
        w.put("lon", lon)
        w.put("information", information)
        w.put("source", source)
        w.put("showThreat", showThreat)
        w.put("radars", models: radars)
    }
}

/// The defaults the web gives a new radar (`defaultRadar` in `threatModel.js`).
public enum Radars {
    public static let detection = 0
    public static let engagement = 1

    public static let defaultMilstdId = "SHGPEWMAI------"
    public static let defaultSource = "SOF"
    public static let defaultName = "Threat"

    private static func band(_ altFt: Double, _ color: String, _ alpha: Double, _ colorIndex: Int) -> RadarBand {
        RadarBand(altFt: altFt, color: color, alpha: alpha, colorIndex: colorIndex, viewable: true)
    }

    private static func detectionBands() -> [RadarBand] {
        [band(50, "#fbbf24", 0.32, 1), band(250, "#f97316", 0.28, 3), band(500, "#facc15", 0.24, 5)]
    }

    private static func engagementBands() -> [RadarBand] {
        [band(50, "#ef4444", 0.42, 2), band(250, "#dc2626", 0.34, 4), band(500, "#b91c1c", 0.26, 0)]
    }

    /// A new radar of `type`. As on the web, anything that is not Detection gets the Engagement defaults and keeps the number it was
    /// given.
    public static func `default`(_ type: Int) -> Radar {
        Radar(
            type: type,
            rangeNmi: type == detection ? 25 : 15,
            antennaHeightFt: 20,
            aglNotMsl: true,
            showMask: true,
            showRangeRings: true,
            bands: type == detection ? detectionBands() : engagementBands()
        )
    }

    /// What a threat with no radar rows in its file gets: a Detection and an Engagement radar.
    public static func defaultPair() -> [Radar] { [`default`(detection), `default`(engagement)] }
}
