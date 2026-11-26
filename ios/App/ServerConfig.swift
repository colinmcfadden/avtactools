import CoreJS
import CoreMap
import FeatureMap
import Foundation

/// `GET /api/config`, public and cached 60 s by the server: the Mapbox public token and the live build of the FAA's charts, which the
/// map draws from. A failure (no signal) leaves what was remembered. CoreNetwork (I2's typed client) takes this over when it lands.
enum ServerConfig {
    @MainActor
    static func load(into map: MapStore) async {
        var request = URLRequest(url: AppInfo.apiRoot.appending(path: "api/config"))
        request.timeoutInterval = 15
        request.setValue("ios/\(AppInfo.version) (\(AppInfo.build))", forHTTPHeaderField: "X-EZPZ-Client")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
            (response as? HTTPURLResponse)?.statusCode == 200, data.count < 1_000_000,
            let config = try? JSONTree.parse(utf8: data)
        else { return }
        let token = config["mapbox"]?["publicToken"]?.stringValue
        map.configure(mapboxToken: token, charts: charts(config["charts"]?["vfr"]))
    }

    /// The charts' build from the config's own fields; none where a field is missing or cannot be drawn (`VfrLive.of` decides).
    static func charts(_ vfr: JSONTree?) -> VfrLive? {
        guard let vfr,
            let build = vfr["build"]?.stringValue, let tiles = vfr["tiles"]?.stringValue,
            let coverage = vfr["coverage"]?.stringValue, let tileSize = int(vfr["tileSize"]),
            let minZoom = int(vfr["minZoom"]), let maxZoom = int(vfr["maxZoom"]),
            let effective = vfr["effective"]?.stringValue, let expires = vfr["expires"]?.stringValue,
            let bounds = numbers(vfr["bounds"])
        else { return nil }
        let fallback = (vfr["fallback"]?.arrayValue?.elements ?? []).compactMap { numbers($0) }
        return VfrLive.of(
            root: AppInfo.apiRoot.absoluteString, build: build, tiles: tiles, coverage: coverage, tileSize: tileSize, minZoom: minZoom,
            maxZoom: maxZoom, effective: effective, expires: expires, bounds: bounds, fallback: fallback)
    }

    private static func int(_ value: JSONTree?) -> Int? {
        guard let n = value?.numberValue?.value, n.rounded() == n, abs(n) < 1e9 else { return nil }
        return Int(n)
    }

    private static func numbers(_ value: JSONTree?) -> [Double]? {
        value?.arrayValue?.compactMap { $0.numberValue?.value }
    }
}
