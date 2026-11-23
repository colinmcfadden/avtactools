import CoreGeo
import CoreMap
import CoreModel
import Foundation
import MapLibre
import SwiftUI
import UIKit

/// MapLibre's view, for SwiftUI: the base map `store` chose, the MGRS grid's lines over it, and the camera told back to the store as
/// it moves. One per window, moved between the frames rather than made again (Android's movable-content lesson: a map made twice adds
/// its sources twice and flies back to the launch's camera).
public struct MapLibreMap: UIViewRepresentable {
    let store: MapStore
    /// A tap on the map that MapLibre took as a single tap (never a double-tap zoom), where it landed.
    let onTap: (LatLon) -> Void

    public init(store: MapStore, onTap: @escaping (LatLon) -> Void = { _ in }) {
        self.store = store
        self.onTap = onTap
    }

    public func makeCoordinator() -> Coordinator { Coordinator(store: store) }

    public func makeUIView(context: Context) -> MLNMapView {
        let view = MLNMapView(frame: .zero)
        let coordinator = context.coordinator
        view.delegate = coordinator
        view.backgroundColor = UIColor(red: 0x09 / 255, green: 0x21 / 255, blue: 0x37 / 255, alpha: 1)
        // The frame draws its own north button and scale is not wanted; the attribution stays, as Mapbox's terms ask.
        view.compassView.compassVisibility = .hidden
        view.logoView.isHidden = true
        view.attributionButtonPosition = .bottomLeft
        view.allowsTilting = false
        // The padding is the frame's alone. Left on, MapLibre sets it to the safe area itself and the middle it reports (the
        // readout, where things are put) is no longer the crosshair's (AGENTS.md §17, *The map*).
        view.automaticallyAdjustsContentInset = false
        view.contentInset = store.padding.edgeInsets
        if let camera = CameraMemory.load() {
            view.setCenter(
                CLLocationCoordinate2D(latitude: camera.lat, longitude: camera.lon), zoomLevel: camera.zoom, animated: false)
        } else {
            view.setCenter(CLLocationCoordinate2D(latitude: 34.7, longitude: -84.4), zoomLevel: 9, animated: false)
        }

        let tap = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.tapped(_:)))
        // A tap is the frame's only once it cannot be the first of a double tap (MapLibre's zoom): Android's onSingleTapConfirmed.
        for case let other as UITapGestureRecognizer in view.gestureRecognizers ?? [] where other.numberOfTapsRequired == 2 {
            tap.require(toFail: other)
        }
        view.addGestureRecognizer(tap)
        let press = UILongPressGestureRecognizer(target: coordinator, action: #selector(Coordinator.pressed(_:)))
        view.addGestureRecognizer(press)

        coordinator.view = view
        coordinator.onTap = onTap
        store.view = view
        store.gridLines = { [weak coordinator] grid in coordinator?.showGrid(grid) }
        coordinator.applyStyle()
        return view
    }

    public func updateUIView(_ view: MLNMapView, context: Context) {
        context.coordinator.onTap = onTap
        context.coordinator.applyStyle()
        // Read here so a new padding updates the view: the frame may set it before the view exists.
        context.coordinator.applyPadding(store.padding)
    }

    @MainActor
    public final class Coordinator: NSObject, @MainActor MLNMapViewDelegate {
        let store: MapStore
        weak var view: MLNMapView?
        var onTap: (LatLon) -> Void = { _ in }
        private var drawnStyle: String?
        private var drawnURL: URL?
        private var styleGeneration = 0
        private var gridSource: MLNShapeSource?
        private var lastGrid: GridResult = .empty
        private var movingByHand = false

        init(store: MapStore) { self.store = store }

        /// The camera's padding, eased to so what is under the crosshair stays under it; at once before the view has a size.
        func applyPadding(_ padding: MapInsets) {
            guard let view, view.contentInset != padding.edgeInsets else { return }
            // MapLibre keeps the map where it is and moves its middle under the crosshair, telling no region change: the readout
            // and the grid's covered strip are worked out again here.
            view.setContentInset(padding.edgeInsets, animated: view.bounds.height > 0) { [weak self] in
                self?.store.cameraMoved(byHand: false)
                self?.store.settleGrid()
            }
            store.cameraMoved(byHand: false)
            if let flight = store.flight, flight.asked.timeIntervalSinceNow > -1 { flight.go(view) }
        }

        /// Sets the style document when the base map (or what it is drawn from: the token, the charts) changed. Written to a file in
        /// the caches, since MapLibre iOS takes a style by URL.
        func applyStyle() {
            guard let view else { return }
            let style = store.style
            let json = MapStyles.styleJson(style, density: view.traitCollection.displayScale)
            guard json != drawnStyle else { return }
            drawnStyle = json
            // A new name for each document: MapLibre does not load a URL it already shows again.
            styleGeneration += 1
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("ezpz-style-\(styleGeneration).json")
            if let previous = drawnURL { try? FileManager.default.removeItem(at: previous) }
            drawnURL = url
            do {
                try Data(json.utf8).write(to: url, options: .atomic)
                view.styleURL = url
            } catch {
                store.toast = "The map could not be drawn."
            }
        }

        public func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            installGrid(in: style)
            store.cameraMoved(byHand: false)
            store.settleGrid()
        }

        public func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            movingByHand = !reason.isDisjoint(with: [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut])
        }

        public func mapViewRegionIsChanging(_ mapView: MLNMapView) { store.cameraMoved(byHand: movingByHand) }

        public func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            store.cameraMoved(byHand: movingByHand)
            store.settleGrid()
            CameraMemory.save(mapView)
        }

        public func mapView(_ mapView: MLNMapView, didChange mode: MLNUserTrackingMode, animated: Bool) {
            if mode == .none { store.trackingStopped() }
        }

        public func mapView(_ mapView: MLNMapView, didFailToLocateUserWithError error: any Error) {
            store.locationFailed("Your position is not available. Location may be off for EZ/PZ in Settings.")
        }

        @objc func tapped(_ recognizer: UITapGestureRecognizer) {
            guard let view, recognizer.state == .ended else { return }
            let at = view.convert(recognizer.location(in: view), toCoordinateFrom: view)
            onTap(LatLon(lat: at.latitude, lon: at.longitude))
        }

        @objc func pressed(_ recognizer: UILongPressGestureRecognizer) {
            guard let view, recognizer.state == .began else { return }
            let point = recognizer.location(in: view)
            let at = view.convert(point, toCoordinateFrom: view)
            // The map repeats the world across the date line: a longitude past ±180 is the same place.
            var lon = at.longitude.truncatingRemainder(dividingBy: 360)
            if lon > 180 { lon -= 360 } else if lon < -180 { lon += 360 }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            store.pressed = MapPress(at: LatLon(lat: at.latitude, lon: lon), point: point)
        }

        // -- The MGRS grid's lines ---------------------------------------------------------------------------------

        /// The grid's lines directly above the base map, under everything else (Android's `MgrsGridOverlay`, the web's mgrsGrid.css):
        /// halos, then lines, finest first and zone boundaries last; light lines on a thin dark halo, never a colour.
        private func installGrid(in style: MLNStyle) {
            let source = MLNShapeSource(identifier: "mgrs-grid", shape: nil, options: nil)
            style.addSource(source)
            gridSource = source
            var below = style.layer(withIdentifier: "base")
            func add(_ layer: MLNStyleLayer) {
                if let below { style.insertLayer(layer, above: below) } else { style.insertLayer(layer, at: 0) }
                below = layer
            }
            for kind in Self.kinds {
                let halo = line("mgrs-grid-halo-\(kind)", kind, source)
                halo.lineColor = NSExpression(forConstantValue: UIColor.black.withAlphaComponent(kind == "zone" ? 0.6 : 0.45))
                halo.lineWidth = NSExpression(forConstantValue: Self.haloWidths[kind]!)
                add(halo)
            }
            for kind in Self.kinds {
                let layer = line("mgrs-grid-line-\(kind)", kind, source)
                layer.lineColor = NSExpression(forConstantValue: UIColor.white.withAlphaComponent(Self.lineAlpha[kind]!))
                layer.lineWidth = NSExpression(forConstantValue: Self.lineWidths[kind]!)
                // Dashes are measured in line widths: the web's 12 px dash and 6 px gap at 2.5 px wide.
                if kind == "zone" { layer.lineDashPattern = NSExpression(forConstantValue: [12.0 / 2.5, 6.0 / 2.5]) }
                add(layer)
            }
            showGrid(lastGrid)
        }

        private func line(_ id: String, _ kind: String, _ source: MLNSource) -> MLNLineStyleLayer {
            let layer = MLNLineStyleLayer(identifier: id, source: source)
            layer.predicate = NSPredicate(format: "kind == %@", kind)
            layer.lineJoin = NSExpression(forConstantValue: "round")
            layer.lineCap = NSExpression(forConstantValue: "butt")
            return layer
        }

        func showGrid(_ grid: GridResult) {
            lastGrid = grid
            guard let gridSource else { return }
            let json = MgrsGridScene.geoJson(grid)
            gridSource.shape = try? MLNShape(data: Data(json.utf8), encoding: String.Encoding.utf8.rawValue)
        }

        private static let kinds = ["minor", "major", "zone"]
        private static let haloWidths: [String: Double] = ["minor": 3, "major": 4, "zone": 5]
        private static let lineWidths: [String: Double] = ["minor": 1, "major": 1.75, "zone": 2.5]
        private static let lineAlpha: [String: CGFloat] = ["minor": 0.85, "major": 0.92, "zone": 1]
    }
}

/// Where the person was looking, kept on the device only (never a position of theirs: the camera), so a launch opens there.
enum CameraMemory {
    struct Saved {
        let lat: Double
        let lon: Double
        let zoom: Double
    }

    private static let key = "ezpz.lastCamera"

    @MainActor static func save(_ view: MLNMapView) {
        let c = view.centerCoordinate
        UserDefaults.standard.set([c.latitude, c.longitude, view.zoomLevel], forKey: key)
    }

    static func load() -> Saved? {
        // Numbers as the app keeps them, or text as a launch argument gives them (`-ezpz.lastCamera "(34.76, -84.38, 13)"`, how a
        // simulator check opens on a place).
        let values = (UserDefaults.standard.array(forKey: key) ?? []).compactMap { value -> Double? in
            (value as? NSNumber)?.doubleValue ?? (value as? String).flatMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        }
        guard values.count == 3,
            values.allSatisfy(\.isFinite), abs(values[0]) <= 85, abs(values[1]) <= 180, (0...22).contains(values[2])
        else { return nil }
        return Saved(lat: values[0], lon: values[1], zoom: values[2])
    }
}
