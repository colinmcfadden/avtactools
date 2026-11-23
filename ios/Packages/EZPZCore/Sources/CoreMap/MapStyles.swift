import CoreJS
import Foundation

/// One base map. The same three the web offers (`frontend/src/feature/mapStyles/mapStyles.js`), under the same ids, because a
/// diagram saves its id.
public struct MapBaseStyle: Hashable, Sendable {
    public let id: String
    public let label: String
    /// An XYZ tile template. The tokens may come in any order (ArcGIS puts `{y}` before `{x}`).
    public let tiles: String
    public let attribution: String
    /// The size the tiles are *drawn* at, in points. Mapbox's `@2x` 512-point tiles are 1024-pixel images.
    public let tileSize: Int
    /// The zoom range the server actually has tiles for; outside it the map stretches what it has instead of going blank.
    public let minZoom: Int
    public let maxZoom: Int
    /// Mapbox's styles need the public token the server hands out (`/api/config`); the FAA chart does not.
    public let needsMapboxToken: Bool
    /// How far it reaches, so that no tile is asked for past it; nil: the whole world.
    public let bounds: ChartBox?
    /// Drawn under it, each within its own bounds: the ArcGIS sectional where a build of the FAA's charts has none
    /// (docs/VFR_CHARTS.md §4.6).
    public let under: [MapBaseStyle]
    /// The size they are drawn at on a dense screen (`levelsFiner`). Half for a build's tiles, which hold only their size in pixels;
    /// the same for the rest.
    public let denseTileSize: Int

    public init(
        id: String, label: String, tiles: String, attribution: String, tileSize: Int, minZoom: Int, maxZoom: Int, needsMapboxToken: Bool,
        bounds: ChartBox? = nil, under: [MapBaseStyle] = [], denseTileSize: Int? = nil
    ) {
        self.id = id
        self.label = label
        self.tiles = tiles
        self.attribution = attribution
        self.tileSize = tileSize
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.needsMapboxToken = needsMapboxToken
        self.bounds = bounds
        self.under = under
        // Kotlin's default is `tileSize`, which a Swift default cannot name.
        self.denseTileSize = denseTileSize ?? tileSize
    }
}

public enum MapStyles {
    /// What a diagram that saved nothing, or a name this version has never heard of, is drawn on.
    public static let defaultId = "satellite"

    /// The VFR map's id, however it is drawn.
    public static let vfrId = "vfr-sectional"

    private static let mapboxAttribution = "&copy; <a href=\"https://www.mapbox.com/about/maps/\">Mapbox</a>"

    private static func mapbox(_ id: String, _ label: String, _ styleId: String, _ token: String) -> MapBaseStyle {
        MapBaseStyle(
            id: id, label: label,
            tiles: "https://api.mapbox.com/styles/v1/mapbox/\(styleId)/tiles/512/{z}/{x}/{y}@2x?access_token=\(token)",
            attribution: mapboxAttribution, tileSize: 512, minZoom: 0, maxZoom: 20, needsMapboxToken: true)
    }

    private static func arcgisVfr(bounds: ChartBox? = nil) -> MapBaseStyle {
        MapBaseStyle(
            id: vfrId, label: "VFR",
            tiles: "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/{z}/{y}/{x}",
            attribution: "FAA Aeronautical Information Services", tileSize: 256,
            // FAA only publishes levels 8 to 12.
            minZoom: 8, maxZoom: 12, needsMapboxToken: false, bounds: bounds)
    }

    // The live build where it reaches, over ArcGIS in each region it has no chart of (Hawaii, Alaska); with no build, ArcGIS
    // everywhere (§4.6).
    private static func vfr(_ live: VfrLive?) -> MapBaseStyle {
        guard let live else { return arcgisVfr() }
        let arcgis = arcgisVfr()
        return MapBaseStyle(
            id: arcgis.id, label: arcgis.label, tiles: live.tiles, attribution: arcgis.attribution, tileSize: live.tileSize,
            minZoom: live.minZoom, maxZoom: live.maxZoom, needsMapboxToken: arcgis.needsMapboxToken, bounds: live.bounds,
            under: live.fallback.map { arcgisVfr(bounds: $0) }, denseTileSize: live.tileSize / 2)
    }

    /// The base maps that can be drawn right now. Without a Mapbox token (the server was never reached and none was remembered) only
    /// the FAA chart can be, and the switcher shows just that rather than a style that would draw nothing.
    public static func available(_ mapboxToken: String?, charts: VfrLive? = nil) -> [MapBaseStyle] {
        var styles: [MapBaseStyle] = []
        if let token = mapboxToken, startsWith(token, "pk.") {
            styles.append(mapbox("satellite", "Satellite", "satellite-v9", token))
            styles.append(mapbox("topo", "Topo", "outdoors-v12", token))
        }
        styles.append(vfr(charts))
        return styles
    }

    /// The style a saved id names, or the first one that can be drawn if it names none (a stale or unknown id must not blank the map).
    public static func byId(_ id: String?, _ mapboxToken: String?, charts: VfrLive? = nil) -> MapBaseStyle {
        let all = available(mapboxToken, charts: charts)
        return all.first { style in id.map { sameText(style.id, $0) } ?? false } ?? all[0]
    }

    /// A MapLibre style document for `style`: its tiles over a background in the app's dark navy (so the first frame, and a tile that
    /// has not arrived, are not white). The layer on top is always `base`, which the overlays are put above; those it stands over go
    /// under it. Overlays are added to the loaded style afterwards. `density` is the screen's pixels to the point, which says how fine
    /// its charts are drawn.
    ///
    /// Its numbers are written as JavaScript writes them (`-125`), where kotlinx writes a Kotlin double's text (`-125.0`): the same
    /// value to MapLibre, and the keys in the same order.
    public static func styleJson(_ style: MapBaseStyle, density: Double = 1.0) -> String {
        var sources: [(String, JSONTree)] = style.under.enumerated().map { ("\(under)\($0.offset)", sourceOf($0.element, density)) }
        sources.append(("base", sourceOf(style, density)))
        var layers: [JSONTree] = [
            .object([
                "id": .string("background"), "type": .string("background"), "paint": .object(["background-color": .string(background)]),
            ])
        ]
        for index in style.under.indices {
            layers.append(.object(["id": .string("\(under)\(index)"), "type": .string("raster"), "source": .string("\(under)\(index)")]))
        }
        layers.append(.object(["id": .string("base"), "type": .string("raster"), "source": .string("base")]))
        let document: JSONTree = .object([
            "version": .number(8),
            "name": .string(style.id),
            "sources": .object(JSONObject(sources)),
            "layers": .array(layers),
        ])
        return document.serialized
    }

    private static func sourceOf(_ style: MapBaseStyle, _ density: Double) -> JSONTree {
        var entries: [(String, JSONTree)] = [
            ("type", .string("raster")),
            ("tiles", .array([.string(style.tiles)])),
            ("tileSize", .number(Double(levelsFiner(density) > 0 ? style.denseTileSize : style.tileSize))),
            ("minzoom", .number(Double(style.minZoom))),
            ("maxzoom", .number(Double(style.maxZoom))),
            ("attribution", .string(style.attribution)),
        ]
        if let box = style.bounds {
            entries.append(("bounds", .array([.number(box.west), .number(box.south), .number(box.east), .number(box.north)])))
        }
        return .object(JSONObject(entries))
    }

    private static let under = "fallback-"
    private static let background = "#092137"
}
