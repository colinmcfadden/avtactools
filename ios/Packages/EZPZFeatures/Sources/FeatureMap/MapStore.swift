import CoreGeo
import CoreMap
import CoreModel
import Foundation
import MapLibre
import Observation
import UIKit

/// The 2D map's state and what can be asked of it: Android's `MapViewModel` with `MapHost`'s commands, for SwiftUI. One per window;
/// the frames read it, and `MapLibreMap` draws it. What is worked out from the camera (the readout, the grid) is CoreMap's plain code.
@MainActor @Observable
public final class MapStore {
    /// Where the map is looking, as MapLibre last said (its zoom is Leaflet's less one: AGENTS.md §17, *The map*).
    public private(set) var camera: CameraState?
    /// Counts every report of the map's view, the end of a padding's easing included, where the camera's centre is the same but
    /// every point has moved under it: what is drawn over the map at a place (the grid's labels) follows this, not `camera`.
    public private(set) var drawn = 0
    /// What the pill under the crosshair says, worked out on the device as often as the camera moves.
    public private(set) var readout: Readout?

    /// The base map the person chose (a diagram's saved id later): kept even when it cannot be drawn, as `chosenStyleId` on Android.
    public private(set) var chosenStyleId: String
    /// The public Mapbox token from `/api/config`, remembered so a launch with no signal still draws satellite. Only a `pk.` token.
    public private(set) var mapboxToken: String?
    /// The live build of the FAA's charts the server named, or none (the ArcGIS sectional everywhere).
    public private(set) var charts: VfrLive?

    /// The MGRS grid over the map: off until turned on, then remembered on the device, never saved with a plan.
    public private(set) var gridShown: Bool
    /// The grid fitted to the view as it last settled; its lines are on the map, its labels drawn over it.
    public private(set) var grid: GridResult = .empty

    /// The strips the frame's controls cover, in points: the crosshair is the middle of the rest (the camera's `contentInset`).
    public private(set) var padding = MapInsets()

    /// Whether the map follows the person's position (the GPS button): MapLibre's own location, nothing stored or sent.
    public private(set) var locating = false
    /// The words for why the position cannot be shown, or nil.
    public private(set) var locationProblem: String?

    /// A long press on nothing: the map's menu, at this place.
    public var pressed: MapPress?
    /// Said once and then let go of: copied, a search refused.
    public var toast: String?

    /// The base maps that can be drawn now (without a token, only the FAA chart).
    public var styles: [MapBaseStyle] { MapStyles.available(mapboxToken, charts: charts) }
    /// The one drawn: the chosen one, or the first that can be drawn when it cannot.
    public var style: MapBaseStyle { MapStyles.byId(chosenStyleId, mapboxToken, charts: charts) }

    @ObservationIgnored weak var view: MLNMapView?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var gridTask: Task<Void, Never>?
    /// What floats over the map, in the map's points, which the grid's labels keep off (Android's `chromeBox`).
    @ObservationIgnored var avoid: [String: CGRect] = [:]

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        chosenStyleId = defaults.string(forKey: Keys.style) ?? MapStyles.defaultId
        mapboxToken = defaults.string(forKey: Keys.token).flatMap { $0.hasPrefix("pk.") ? $0 : nil }
        gridShown = defaults.bool(forKey: Keys.grid)
    }

    // -- What the server says -------------------------------------------------------------------------------------

    /// The token and the charts from `/api/config`. A token that is not `pk.` is never stored (it reaches a URL).
    public func configure(mapboxToken token: String?, charts live: VfrLive?) {
        if let token, token.hasPrefix("pk."), token != mapboxToken {
            mapboxToken = token
            defaults.set(token, forKey: Keys.token)
        }
        if live != charts { charts = live }
    }

    // -- What the person asks -------------------------------------------------------------------------------------

    public func selectStyle(_ id: String) {
        chosenStyleId = id
        defaults.set(id, forKey: Keys.style)
    }

    public func toggleGrid() {
        gridShown.toggle()
        defaults.set(gridShown, forKey: Keys.grid)
        if gridShown { settleGrid() } else { showGrid(.empty) }
    }

    /// Flies the map to `at` (a search's choice, a diagram's target), at `zoom` or where it is now.
    public func fly(to at: LatLon, zoom: Double? = nil) {
        guard let view else { return }
        if locating { toggleLocating() }  // a place asked for is not the person's position
        flight = Flight(at: at, zoom: zoom ?? view.zoomLevel, asked: .now)
        flight?.go(view)
    }

    public func faceNorth() { view?.resetNorth() }

    /// Follows the person's position, or stops. The system asks for permission the first time (MapLibre's own location manager).
    public func toggleLocating() {
        guard let view else { return }
        locating.toggle()
        locationProblem = nil
        view.showsUserLocation = locating
        view.setUserTrackingMode(locating ? .follow : .none, animated: true, completionHandler: nil)
        if locating, let at = view.userLocation?.location?.coordinate, CLLocationCoordinate2DIsValid(at) {
            view.setCenter(at, zoomLevel: max(view.zoomLevel, Self.locateZoom), animated: true)
        }
    }

    /// Looks for `text`: true when it is one place, which the map flies to; false when it is none or could be two (never guessed between).
    @discardableResult
    public func search(_ text: String) -> Bool {
        let reading = PlaceSearch.read(text)
        if reading.choices.count == 1, let only = reading.choices.first {
            go(to: only)
            return true
        }
        toast = reading.message ?? PlaceSearch.Messages.empty
        return false
    }

    public func go(to choice: PlaceChoice) { fly(to: choice.at, zoom: Self.searchZoom) }

    /// What a text could be, nearest the map first: the list under the search as it is typed.
    public func reading(_ text: String) -> PlaceReading {
        let reading = PlaceSearch.read(text)
        return PlaceReading(choices: PlaceSearch.nearestFirst(reading.choices, camera?.center), message: reading.message)
    }

    /// Copies the grid at `at` (the readout's, or a pressed place's) and says so.
    public func copyGrid(at: LatLon) {
        let words = MgrsConverter.toMgrs(at.lat, at.lon)?.format() ?? PlaceSearch.formatLatLong(at.lat, at.lon)
        UIPasteboard.general.string = words
        toast = "Copied \(words)"
    }

    // -- What the frame says --------------------------------------------------------------------------------------

    /// The strips the frame covers. The map eases to the new middle, so what was under the crosshair stays under it.
    public func setPadding(_ insets: MapInsets) {
        guard insets != padding else { return }
        padding = insets
    }

    /// What floats over the map at `key`, in the map's coordinates; nil when it goes.
    public func setAvoid(_ key: String, _ rect: CGRect?) {
        if avoid[key] == rect { return }
        avoid[key] = rect
        if gridShown { settleGrid() }
    }

    // -- What the map says ----------------------------------------------------------------------------------------

    func cameraMoved(byHand: Bool) {
        guard let view else { return }
        drawn &+= 1
        let next = CameraState(
            center: LatLon(lat: view.centerCoordinate.latitude, lon: view.centerCoordinate.longitude), zoom: view.zoomLevel,
            bearingDegrees: view.direction, paddingTopPx: view.contentInset.top, paddingBottomPx: view.contentInset.bottom,
            byHand: byHand)
        if let camera, camera.sameView(next), camera.bearingDegrees == next.bearingDegrees,
            camera.paddingTopPx == next.paddingTopPx, camera.paddingBottomPx == next.paddingBottomPx
        {
            return
        }
        camera = next
        readout = Readout.of(next.center)
    }

    func trackingStopped() {
        if locating { locating = false }
    }

    func locationFailed(_ words: String) {
        locating = false
        locationProblem = words
        toast = words
    }

    /// The view settled: the grid is worked out again 200 ms later, off the main actor (a grid at its caps is tens of thousands of points).
    func settleGrid() {
        gridTask?.cancel()
        guard gridShown, let view, var camera else { return }
        let size = view.bounds.size
        // The grid takes the camera's middle across as the view's (Android's map has no side padding); the iPad's sidebar and
        // inspector move MapLibre's middle sideways, so the grid is given the place at the view's own middle across instead.
        let inset = view.contentInset
        if inset.left != inset.right {
            let middle = CGPoint(x: size.width / 2, y: inset.top + (size.height - inset.top - inset.bottom) / 2)
            let at = view.convert(middle, toCoordinateFrom: view)
            camera.center = LatLon(lat: at.latitude, lon: at.longitude)
        }
        // MapLibre's attribution button is chrome too (the map fills the window, so its frame is in the window's points).
        let attribution = view.attributionButton.isHidden ? [] : [view.attributionButton.frame]
        let avoid = (Array(avoid.values) + attribution).map { GridBox(x0: $0.minX, y0: $0.minY, x1: $0.maxX, y1: $0.maxY) }
        gridTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            // A grid point is a screen point (Android's dp), MapLibre's unit on iOS too: density 1.
            guard let gridView = MgrsGridScene.viewOf(camera, size.width, size.height, 1, avoidPx: avoid)
            else { return }
            let result = await Task.detached(priority: .userInitiated) { MgrsGridScene.gridFor(gridView) }.value
            guard !Task.isCancelled else { return }
            self?.showGrid(result)
        }
    }

    private func showGrid(_ result: GridResult) {
        grid = result
        gridLines?(result)
    }

    /// Set by the map view: hands the lines to MapLibre.
    @ObservationIgnored var gridLines: ((GridResult) -> Void)?

    /// The screen point of `at` in the map's view, as the map is drawn now (for labels over it).
    public func point(of at: LatLon) -> CGPoint? {
        guard let view else { return nil }
        return view.convert(CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon), toPointTo: view)
    }

    /// The last flight asked for: a new padding (the keyboard going down, the sheet moving) ends MapLibre's flight where it is,
    /// so one asked for in the last second is flown again with the new padding (Android folds a flight into it the same way).
    @ObservationIgnored var flight: Flight?

    struct Flight {
        let at: LatLon
        let zoom: Double
        let asked: Date

        @MainActor func go(_ view: MLNMapView) {
            view.setCenter(
                CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon), zoomLevel: zoom, direction: view.direction, animated: true)
        }
    }

    static let searchZoom = 16.0
    static let locateZoom = 16.0

    private enum Keys {
        static let style = "ezpz.baseMap"
        static let token = "ezpz.mapboxToken"
        static let grid = "ezpz.mgrsGrid"
    }
}

/// The strips the frame's controls cover, in points.
public struct MapInsets: Equatable, Sendable {
    public var top: CGFloat
    public var bottom: CGFloat
    public var leading: CGFloat
    public var trailing: CGFloat

    public init(top: CGFloat = 0, bottom: CGFloat = 0, leading: CGFloat = 0, trailing: CGFloat = 0) {
        self.top = top
        self.bottom = bottom
        self.leading = leading
        self.trailing = trailing
    }

    var edgeInsets: UIEdgeInsets { UIEdgeInsets(top: top, left: leading, bottom: bottom, right: trailing) }
}

/// A long press on the map: where on the ground, and where on the screen (the menu stands beside it).
public struct MapPress: Equatable, Sendable {
    public let at: LatLon
    public let point: CGPoint

    public var grid: String? { MgrsConverter.toMgrs(at.lat, at.lon)?.format() }
}
