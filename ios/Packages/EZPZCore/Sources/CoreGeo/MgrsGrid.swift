import CoreModel
import Foundation

/// A view's edges in degrees, as a map gives them: the longitudes may run past 180 (across the antimeridian, or several copies of the
/// world).
public struct GridBounds: Hashable, Sendable {
    public let south: Double
    public let west: Double
    public let north: Double
    public let east: Double

    public init(south: Double, west: Double, north: Double, east: Double) {
        self.south = south
        self.west = west
        self.north = north
        self.east = east
    }
}

/// A box in pixels from the view's top-left: `x0`, `y0` its top-left corner, `x1`, `y1` its bottom-right.
public struct GridBox: Hashable, Sendable {
    public let x0: Double
    public let y0: Double
    public let x1: Double
    public let y1: Double

    public init(x0: Double, y0: Double, x1: Double, y1: Double) {
        self.x0 = x0
        self.y0 = y0
        self.x1 = x1
        self.y1 = y1
    }
}

/// How `MgrsGrid.grid` works: `pad` is the fraction of the view's size round it the lines also cover (so a pan shows no bare map before
/// the next redraw), `avoid` the boxes labels keep off (what floats over the map), and the caps on lines, points and labels;
/// `tolerancePx` how close a line's polyline keeps to its true curve.
public struct GridOptions: Hashable, Sendable {
    public var pad: Double
    public var avoid: [GridBox]
    public var maxLines: Int
    public var maxPoints: Int
    public var maxLabels: Int
    public var tolerancePx: Double

    public init(
        pad: Double = 0.0,
        avoid: [GridBox] = [],
        maxLines: Int = 1500,
        maxPoints: Int = 60000,
        maxLabels: Int = 250,
        tolerancePx: Double = 0.25
    ) {
        self.pad = pad
        self.avoid = avoid
        self.maxLines = maxLines
        self.maxPoints = maxPoints
        self.maxLabels = maxLabels
        self.tolerancePx = tolerancePx
    }
}

/// One grid line: a zone or band boundary, a 100 km line, or a finer one.
public struct GridLine: Hashable, Sendable {
    /// `zone`, `major` (100 km) or `minor`.
    public let kind: String
    /// The zone it is worked out in; nil for a zone or band boundary.
    public let zone: Int?
    /// `easting`, `northing`, or for a boundary `parallel` or `meridian`.
    public let axis: String
    /// Its easting or northing in metres (a southern northing with its false northing), or a boundary's latitude or longitude.
    public let value: Double
    public let points: [LatLon]

    public init(kind: String, zone: Int?, axis: String, value: Double, points: [LatLon]) {
        self.kind = kind
        self.zone = zone
        self.axis = axis
        self.value = value
        self.points = points
    }
}

/// One label: a grid zone designator, a 100 km square, or a line's principal digits at an edge of the view.
public struct GridLabel: Hashable, Sendable {
    /// `gzd`, `square`, `easting` or `northing`.
    public let kind: String
    /// Which edge a line's value sits inside: `top`, `bottom`, `left` or `right`; nil for a zone or square.
    public let edge: String?
    public let text: String
    public let zone: Int?
    /// A line's value in metres; nil for a zone or square.
    public let value: Double?
    /// Where it is anchored: a line's value on the edge, a zone's or square's label at its middle.
    public let lat: Double
    public let lon: Double
    /// The pixels it takes, from the view's top-left.
    public let box: GridBox

    public init(kind: String, edge: String?, text: String, zone: Int?, value: Double?, lat: Double, lon: Double, box: GridBox) {
        self.kind = kind
        self.edge = edge
        self.text = text
        self.zone = zone
        self.value = value
        self.lat = lat
        self.lon = lon
        self.box = box
    }
}

/// What to draw: `spacing` the finest line spacing drawn (nil for zone boundaries only), the lines and labels, and the levels
/// `dropped` at the caps.
public struct GridResult: Hashable, Sendable {
    public let spacing: Double?
    public let lines: [GridLine]
    public let labels: [GridLabel]
    public let dropped: [String]

    public init(spacing: Double?, lines: [GridLine], labels: [GridLabel], dropped: [String]) {
        self.spacing = spacing
        self.lines = lines
        self.labels = labels
        self.dropped = dropped
    }

    public static let empty = GridResult(spacing: nil, lines: [], labels: [], dropped: [])
}

/// The MGRS grid for one map view, as plain data: the web's `feature/mgrsGrid/mgrsGrid.js` function by function (by way of Android's
/// MgrsGrid), held to `contracts/fixtures/mgrs/grid.json`.
///
/// Every line is worked out in its own UTM zone and clipped to that zone, so it agrees with the crosshair's readout
/// (`MgrsConverter.toMgrs`) wherever it is drawn: a point on the 66 km easting line of 16S GD reads 66000. That matters most where the
/// zones are not plain 6 degree strips (Norway's 32V and Svalbard's 31X to 37X) and at a zone's edge, where the neighbouring zone's
/// lines stop. The map is Web Mercator, where a line of constant easting or northing is a curve: each line is sampled until its
/// polyline is within `GridOptions.tolerancePx` of the true curve at the view's scale, and its ends are found on the zone's or the
/// view's edge, so the lines of two neighbouring zones meet on their boundary and never cross it.
///
/// **Not like the web**, where it would throw: a point the projection has no answer for (outside 80 S to 84 N) is a line or label that
/// is not drawn, never the app's end. Nothing the grid asks for falls there.
///
/// `min` and `max` are Java's here (`javaMin`, `javaMax`), as in the Kotlin port: a NaN carried through a box must stay one.
public enum MgrsGrid {
    private static let southLimit = -80.0  // UTM's range; the polar caps are UPS, not drawn
    private static let northLimit = 84.0
    private static let mercatorLimit = 85.0511287798  // where Web Mercator's square world ends
    private static let earthRadius = 6378137.0  // Web Mercator's sphere
    private static let squareM = 100000.0
    private static let falseNorthingSouth = 10000000.0
    private static let rad = Double.pi / 180
    private static let maxCopies = 8.0  // copies of the world side by side when zoomed far out
    private static let maxDepth = 14  // halvings of a line while sampling it
    private static let edgeM = 1e-3  // a line's end is found on an edge to a millimetre

    /// Line spacings, finest first. Finer than 100 m is never drawn.
    public static let gridSpacings: [Double] = [100.0, 1000.0, 10000.0, 100000.0]

    /// Neighbouring lines are at least this far apart on screen.
    public static let minLineGapPx = 70.0

    /// Narrower than this on screen, zones are not drawn at all: the map would be all lines.
    public static let minZonePx = 12.0

    // Labels are drawn as 11 px pills; these sizes keep them apart.
    static let charPx = 7.0
    static let padPx = 10.0
    static let heightPx = 17.0
    private static let gapPx = 3.0
    static let edgePx = 4.0
    private static let cornerPx = 36.0
    private static let sidePx = 40.0
    private static let principalDigitsBySpacing: [Double: Int] = [10000.0: 1, 1000.0: 2, 100.0: 3]

    private static func clamp(_ value: Double, _ lo: Double, _ hi: Double) -> Double { javaMin(hi, javaMax(lo, value)) }

    /// The finest spacing whose lines are at least `minLineGapPx` apart, or nil for zones only.
    public static func gridSpacing(_ metersPerPixel: Double) -> Double? {
        if !metersPerPixel.isFinite || metersPerPixel <= 0 { return nil }
        return gridSpacings.first { $0 / metersPerPixel >= minLineGapPx }
    }

    /// A line's value in the digits a grid reference gives it at this spacing: 66 km at 1 km is "66".
    public static func principalDigits(_ metres: Double, _ spacing: Double) -> String {
        let within = (metres.truncatingRemainder(dividingBy: squareM) + squareM).truncatingRemainder(dividingBy: squareM)
        return padded(String(kotlinLong(floor(within / spacing))), principalDigitsBySpacing[spacing] ?? 1)
    }

    /// A label's width in pixels for its text.
    static func labelWidth(_ text: String) -> Double { Double(text.utf16.count) * charPx + padPx }

    // MARK: Grid zones

    // Band edges: 8 degrees from 80 S, with band X running 72 to 84 N.
    private static let bandEdges: [Double] = (0..<20).map { -80.0 + 8 * Double($0) } + [84.0]

    private struct Cell {
        let zone: Int
        let band: Character
        let south: Double
        let north: Double
        let west: Double
        let east: Double
    }

    /// Every grid zone cell (a zone within one latitude band) with its longitude range, read off `MgrsConverter.zoneFor` itself so the
    /// Norway and Svalbard exceptions are exactly the readout's. Their edges all fall on whole degrees, and within a band the zone
    /// depends only on longitude.
    private static let gridZones: [Cell] = {
        var cells: [Cell] = []
        for b in 0..<20 {
            let south = bandEdges[b]
            let north = bandEdges[b + 1]
            let middle = (south + north) / 2
            var west = -180.0
            var zone: Int? = MgrsConverter.zoneFor(middle, -179.5)
            for lon in -179...180 {
                let next = lon < 180 ? MgrsConverter.zoneFor(middle, Double(lon) + 0.5) : nil
                if next != zone {
                    cells.append(
                        Cell(zone: zone!, band: MgrsConverter.bandFor(middle), south: south, north: north, west: west, east: Double(lon)))
                    west = Double(lon)
                    zone = next
                }
            }
        }
        return cells
    }()

    private struct Strip {
        let zone: Int
        let south: Double
        var north: Double
        let west: Double
        let east: Double
    }

    // A zone's cells merged where the longitude range runs unchanged from band to band: the stretches a grid line can run along
    // without leaving its zone.
    private static let strips: [Strip] = {
        var strips: [Strip] = []
        var last: [Int: Int] = [:]
        for cell in gridZones {
            if let at = last[cell.zone] {
                let previous = strips[at]
                if previous.north == cell.south && previous.west == cell.west && previous.east == cell.east {
                    strips[at].north = cell.north
                    continue
                }
            }
            last[cell.zone] = strips.count
            strips.append(Strip(zone: cell.zone, south: cell.south, north: cell.north, west: cell.west, east: cell.east))
        }
        return strips
    }()

    // The meridians that are zone boundaries in each band.
    private static let bandMeridians: [[Double]] = bandEdges.dropLast().map { south in
        Array(Set(gridZones.filter { $0.south == south }.map(\.west) + [180.0])).sorted()
    }

    // MARK: The view

    private static func mercatorY(_ lat: Double) -> Double {
        log(tan(Double.pi / 4 + (clamp(lat, -mercatorLimit, mercatorLimit) * rad) / 2))
    }

    private static func mercatorLat(_ y: Double) -> Double { (2 * atan(exp(y)) - Double.pi / 2) / rad }

    /// Pixels for the view, from its bounds and its ground scale at the centre: Web Mercator keeps a degree of longitude the same width
    /// everywhere.
    private struct Frame {
        private let west: Double
        private let top: Double
        private let bottom: Double
        let pxPerRad: Double
        let pxPerDeg: Double
        let width: Double
        let height: Double

        init(_ bounds: GridBounds, _ metersPerPixel: Double) {
            west = bounds.west
            top = mercatorY(bounds.north)
            bottom = mercatorY(bounds.south)
            pxPerRad = earthRadius * cos(mercatorLat((top + bottom) / 2) * rad) / metersPerPixel
            pxPerDeg = pxPerRad * rad
            width = (bounds.east - bounds.west) * pxPerDeg
            height = (top - bottom) * pxPerRad
        }

        func x(_ lon: Double) -> Double { (lon - west) * pxPerDeg }
        func y(_ lat: Double) -> Double { (top - mercatorY(lat)) * pxPerRad }
        func lon(_ x: Double) -> Double { west + x / pxPerDeg }
        func lat(_ y: Double) -> Double { mercatorLat(top - y / pxPerRad) }

        // Map pixels for a point anywhere, for measuring how far a polyline strays from its curve.
        func px(_ p: Pt) -> (Double, Double) { (p.lon * pxPerDeg, -mercatorY(p.lat) * pxPerRad) }
    }

    private struct Piece {
        let west: Double
        let east: Double
        let offset: Double
    }

    /// A map's bounds run past 180 once the view takes in the antimeridian or more than one world: each copy in view is worked out in
    /// -180..180 and moved by its offset.
    private static func worldPieces(_ west: Double, _ east: Double) -> [Piece] {
        let middle = (west + east) / 2
        let from = javaMax(west, middle - 180.0 * maxCopies)
        let to = javaMin(east, middle + 180.0 * maxCopies)
        var pieces: [Piece] = []
        var k = floor((from + 180) / 360)
        let last = floor((to + 180) / 360)
        while k <= last {
            let offset = 360 * k
            let pieceWest = javaMax(from, offset - 180) - offset
            let pieceEast = javaMin(to, offset + 180) - offset
            if pieceEast > pieceWest { pieces.append(Piece(west: pieceWest, east: pieceEast, offset: offset)) }
            k += 1
        }
        return pieces
    }

    private struct View {
        let south: Double
        let north: Double
        let west: Double
        let east: Double
    }

    private enum Side { case south, north, west, east }

    private struct Region {
        let zone: Int
        let offset: Double
        let south: Double
        let north: Double
        let west: Double
        let east: Double
        let zoneEast: Double

        func bound(_ side: Side) -> Double {
            switch side {
            case .south: south
            case .north: north
            case .west: west
            case .east: east
            }
        }
    }

    /// The parts of each zone's strips inside a view, one per world copy.
    private static func regionsIn(_ view: View, _ pieces: [Piece]) -> [Region] {
        var regions: [Region] = []
        for piece in pieces {
            for strip in strips {
                let region = Region(
                    zone: strip.zone, offset: piece.offset,
                    south: javaMax(strip.south, view.south), north: javaMin(strip.north, view.north),
                    west: javaMax(strip.west, piece.west), east: javaMin(strip.east, piece.east),
                    zoneEast: strip.east
                )
                if region.north > region.south && region.east > region.west { regions.append(region) }
            }
        }
        return regions
    }

    // MARK: UTM, with a signed northing

    private struct Pt {
        var lat: Double
        var lon: Double
    }

    private struct Projected {
        let e: Double
        let y: Double
    }

    // Grid lines run across the equator, so they are worked out with the northing signed (south negative); a southern line's value
    // adds the false northing back. A point with no answer is NaN, which no comparison passes: nothing is drawn for it.
    private static func project(_ zone: Int, _ lat: Double, _ lon: Double) -> Projected {
        guard let utm = MgrsConverter.toUtmInZone(lat, lon, zone) else { return Projected(e: .nan, y: .nan) }
        return Projected(e: utm.easting, y: lat < 0 ? utm.northing - falseNorthingSouth : utm.northing)
    }

    private static func unproject(_ zone: Int, _ e: Double, _ y: Double) -> Pt {
        guard let point = MgrsConverter.fromUtm(zone, southern: y < 0, easting: e, northing: y < 0 ? y + falseNorthingSouth : y) else {
            return Pt(lat: .nan, lon: .nan)
        }
        // fromUtm folds into 180; keep the longitude on its zone's side of the antimeridian.
        let meridian = MgrsConverter.centralMeridian(zone)
        var lon = point.lon
        if lon - meridian > 180 {
            lon -= 360
        } else if lon - meridian < -180 {
            lon += 360
        }
        return Pt(lat: point.lat, lon: lon)
    }

    private static func northingValue(_ y: Double) -> Double { y < 0 ? y + falseNorthingSouth : y }

    private struct UtmBox {
        var eMin: Double
        var eMax: Double
        var yMin: Double
        var yMax: Double
    }

    /// The eastings and northings a region spans. For a fixed latitude easting and northing only grow away from the central meridian,
    /// and for a fixed longitude only change one way with latitude (easting peaking at the equator), so the corners and those points
    /// bound them.
    private static func utmBox(_ region: Region) -> UtmBox {
        let meridian = clamp(MgrsConverter.centralMeridian(region.zone), region.west, region.east)
        let equator = clamp(0.0, region.south, region.north)
        var box = UtmBox(eMin: .infinity, eMax: -.infinity, yMin: .infinity, yMax: -.infinity)
        let corners = [
            (region.south, region.west), (region.south, region.east), (region.north, region.west), (region.north, region.east),
            (region.south, meridian), (region.north, meridian), (equator, region.west), (equator, region.east),
        ]
        for (lat, lon) in corners {
            let p = project(region.zone, lat, lon)
            box.eMin = javaMin(box.eMin, p.e)
            box.eMax = javaMax(box.eMax, p.e)
            box.yMin = javaMin(box.yMin, p.y)
            box.yMax = javaMax(box.yMax, p.y)
        }
        return box
    }

    static func multiplesIn(_ lo: Double, _ hi: Double, _ step: Double) -> [Double] {
        var values: [Double] = []
        var k = ceil(lo / step)
        while k * step <= hi {
            // Plus zero turns -0.0 (ceil of a small negative) into 0.0: the web keys a line by its value's text, where -0 is "0".
            values.append(k * step + 0.0)
            k += 1
        }
        return values
    }

    // MARK: Tracing a line

    private struct Edge {
        let latitude: Bool
        let side: Side
        let sign: Double
    }

    private static let edges = [
        Edge(latitude: true, side: .south, sign: 1.0), Edge(latitude: true, side: .north, sign: -1.0),
        Edge(latitude: false, side: .west, sign: 1.0), Edge(latitude: false, side: .east, sign: -1.0),
    ]

    /// Where a function changes sign, between `a0` (f >= 0) and `b0` (f < 0), to within `within`: the Illinois form of false position,
    /// which keeps the root bracketed like bisection but takes a handful of steps on these smooth, nearly straight curves. Returns the
    /// end on the f >= 0 side.
    private static func crossing(
        _ f: (Double) -> Double, _ a0: Double, _ fa0: Double, _ b0: Double, _ fb0: Double, _ within: Double
    ) -> Double {
        var a = a0
        var fa = fa0
        var b = b0
        var fb = fb0
        var kept = 0
        var i = 0
        while i < 100 && abs(b - a) > within {
            var m = (a * fb - b * fa) / (fb - fa)
            if !(m > javaMin(a, b) && m < javaMax(a, b)) { m = (a + b) / 2 }
            let fm = f(m)
            if fm == 0.0 { return m }
            if fm >= 0 {
                a = m
                fa = fm
                if kept == 1 { fb /= 2 }
                kept = 1
            } else {
                b = m
                fb = fm
                if kept == -1 { fa /= 2 }
                kept = -1
            }
            i += 1
        }
        return a
    }

    private struct EdgeHit {
        let latitude: Bool
        let bound: Double
    }

    private struct Clip {
        let lo: Double
        let hi: Double
        let loEdge: EdgeHit?
        let hiEdge: EdgeHit?
    }

    /// The part of a stretch of line inside a region. On a stretch where latitude and longitude each only rise or only fall, that part
    /// is one interval, and each of its ends is found on the edge that cuts it. Nil when no part is inside.
    private static func clipStretch(_ pointAt: (Double) -> Pt, _ t0: Double, _ t1: Double, _ region: Region) -> Clip? {
        let p0 = pointAt(t0)
        let p1 = pointAt(t1)
        var lo = t0
        var hi = t1
        var loEdge: EdgeHit? = nil
        var hiEdge: EdgeHit? = nil
        for edge in edges {
            let bound = region.bound(edge.side)
            func inside(_ p: Pt) -> Double { edge.sign * ((edge.latitude ? p.lat : p.lon) - bound) }
            let in0 = inside(p0) >= 0
            let in1 = inside(p1) >= 0
            if !in0 && !in1 { return nil }
            if in0 != in1 {
                let a = crossing(
                    { t in inside(pointAt(t)) },
                    in0 ? t0 : t1, inside(in0 ? p0 : p1),
                    in0 ? t1 : t0, inside(in0 ? p1 : p0),
                    edgeM
                )
                if in0 && a < hi {
                    hi = a
                    hiEdge = EdgeHit(latitude: edge.latitude, bound: bound)
                } else if in1 && a > lo {
                    lo = a
                    loEdge = EdgeHit(latitude: edge.latitude, bound: bound)
                }
            }
        }
        return hi > lo ? Clip(lo: lo, hi: hi, loEdge: loEdge, hiEdge: hiEdge) : nil
    }

    // Pixels from a point to a segment.
    private static func offSegment(_ p: (Double, Double), _ p0: (Double, Double), _ p1: (Double, Double)) -> Double {
        let dx = p1.0 - p0.0
        let dy = p1.1 - p0.1
        let length2 = dx * dx + dy * dy
        let t = length2 == 0.0 ? 0.0 : clamp(((p.0 - p0.0) * dx + (p.1 - p0.1) * dy) / length2, 0.0, 1.0)
        return hypot(p.0 - (p0.0 + t * dx), p.1 - (p0.1 + t * dy))
    }

    /// Points along [lo, hi], halving each piece until its middle is within tolerance of the chord.
    private static func sampleStretch(
        _ pointAt: (Double) -> Pt, _ lo: Double, _ hi: Double, _ frame: Frame, _ tolerancePx: Double
    ) -> [Pt] {
        let first = pointAt(lo)
        let last = pointAt(hi)
        var points = [first]
        func refine(_ t0: Double, _ q0: (Double, Double), _ t1: Double, _ q1: (Double, Double), _ depth: Int) {
            let tm = (t0 + t1) / 2
            let pm = pointAt(tm)
            let qm = frame.px(pm)
            // Always halve once, so a gently curving line is never judged by its ends alone.
            if depth > 0 && (depth >= maxDepth || offSegment(qm, q0, q1) <= tolerancePx) { return }
            refine(t0, q0, tm, qm, depth + 1)
            points.append(pm)
            refine(tm, qm, t1, q1, depth + 1)
        }
        refine(lo, frame.px(first), hi, frame.px(last), 0)
        points.append(last)
        return points
    }

    private struct Traced {
        var hi: Double
        var points: [Pt]
    }

    /// One easting or northing line in a region, as polylines. A line of constant easting turns back in longitude at the equator, and
    /// one of constant northing in latitude at the central meridian, so each is traced as two stretches either side and joined again.
    private static func traceLine(
        _ region: Region, _ easting: Bool, _ value: Double, _ box: UtmBox, _ frame: Frame, _ tolerancePx: Double
    ) -> [[LatLon]] {
        let pointAt: (Double) -> Pt =
            easting ? { t in unproject(region.zone, value, t) } : { t in unproject(region.zone, t, value) }
        let from = easting ? box.yMin : box.eMin
        let to = easting ? box.yMax : box.eMax
        let turn = easting ? 0.0 : 500000.0
        let stretches = turn > from && turn < to ? [(from, turn), (turn, to)] : [(from, to)]

        var pieces: [Traced] = []
        for (t0, t1) in stretches {
            guard let clip = clipStretch(pointAt, t0, t1, region) else { continue }
            var points = sampleStretch(pointAt, clip.lo, clip.hi, frame, tolerancePx)
            // The ends sit exactly on the edge that cut them.
            if let hit = clip.loEdge {
                if hit.latitude { points[0].lat = hit.bound } else { points[0].lon = hit.bound }
            }
            if let hit = clip.hiEdge {
                if hit.latitude { points[points.count - 1].lat = hit.bound } else { points[points.count - 1].lon = hit.bound }
            }
            if let previous = pieces.last, previous.hi == clip.lo {
                pieces[pieces.count - 1].points += points.dropFirst()
                pieces[pieces.count - 1].hi = clip.hi
            } else {
                pieces.append(Traced(hi: clip.hi, points: points))
            }
        }
        return pieces.map { piece in piece.points.map { LatLon(lat: $0.lat, lon: $0.lon + region.offset) } }
    }

    /// Zone 31's central meridian is its east edge in band V (Norway's 32V takes 3 to 12 E), so its 500 km easting runs along the
    /// boundary, which is zone 32's: the readout puts 3 E in 32. The boundary is drawn as one; the line is not.
    private static func alongEastEdge(_ zone: Int, _ zoneEast: Double, _ easting: Double) -> Bool {
        easting == 500000.0 && MgrsConverter.centralMeridian(zone) == zoneEast
    }

    private struct Values {
        let eastings: [Double]
        let northings: [Double]
    }

    private static func levelValues(_ regions: [Region], _ boxes: [UtmBox], _ spacing: Double, _ skipSquares: Bool) -> [Values] {
        regions.enumerated().map { i, region in
            func keep(_ value: Double) -> Bool { !(skipSquares && value.truncatingRemainder(dividingBy: squareM) == 0.0) }
            return Values(
                eastings: multiplesIn(boxes[i].eMin, boxes[i].eMax, spacing).filter {
                    keep($0) && !alongEastEdge(region.zone, region.zoneEast, $0)
                },
                northings: multiplesIn(boxes[i].yMin, boxes[i].yMax, spacing).filter(keep)
            )
        }
    }

    private static func traceLevel(
        _ regions: [Region], _ boxes: [UtmBox], _ values: [Values], _ kind: String, _ frame: Frame, _ tolerancePx: Double
    ) -> [GridLine] {
        var lines: [GridLine] = []
        for (i, region) in regions.enumerated() {
            for e in values[i].eastings {
                for points in traceLine(region, true, e, boxes[i], frame, tolerancePx) {
                    lines.append(GridLine(kind: kind, zone: region.zone, axis: "easting", value: e, points: points))
                }
            }
            for y in values[i].northings {
                for points in traceLine(region, false, y, boxes[i], frame, tolerancePx) {
                    lines.append(GridLine(kind: kind, zone: region.zone, axis: "northing", value: northingValue(y), points: points))
                }
            }
        }
        return lines
    }

    /// Band edges as parallels across the view, and zone edges as meridians in each band.
    private static func zoneLines(_ view: View, _ pieces: [Piece]) -> [GridLine] {
        var lines: [GridLine] = []
        for lat in bandEdges where lat >= view.south && lat <= view.north {
            lines.append(
                GridLine(
                    kind: "zone", zone: nil, axis: "parallel", value: lat,
                    points: [LatLon(lat: lat, lon: view.west), LatLon(lat: lat, lon: view.east)]))
        }
        for piece in pieces {
            // Kotlin's LinkedHashMap: each meridian's spans, in the order the meridians were first met.
            var order: [Double] = []
            var spans: [Double: [(lo: Double, hi: Double)]] = [:]
            for (b, meridians) in bandMeridians.enumerated() {
                let lo = javaMax(bandEdges[b], view.south)
                let hi = javaMin(bandEdges[b + 1], view.north)
                if hi <= lo { continue }
                for lon in meridians {
                    if lon < piece.west || lon > piece.east { continue }
                    if spans[lon] == nil {
                        order.append(lon)
                        spans[lon] = []
                    }
                    if let previous = spans[lon]!.last, previous.hi == lo {
                        spans[lon]![spans[lon]!.count - 1].hi = hi
                    } else {
                        spans[lon]!.append((lo, hi))
                    }
                }
            }
            for lon in order {
                for (lo, hi) in spans[lon]! {
                    lines.append(
                        GridLine(
                            kind: "zone", zone: nil, axis: "meridian", value: lon,
                            points: [LatLon(lat: lo, lon: lon + piece.offset), LatLon(lat: hi, lon: lon + piece.offset)]))
                }
            }
        }
        return lines
    }

    // MARK: Labels

    private static func zoneText(_ zone: Int, _ lat: Double) -> String { "\(padded(String(zone), 2))\(MgrsConverter.bandFor(lat))" }

    // Where a line of one value crosses a parallel or a meridian (to 1e-10 degrees, about 0.01 mm): easting only grows eastward along
    // a parallel, and northing only grows northward along a meridian, so the line crosses once.
    private static func solve(_ measure: (Double) -> Double, _ lo: Double, _ hi: Double, _ target: Double) -> Double {
        func f(_ x: Double) -> Double { measure(x) - target }
        let flo = f(lo)
        if flo >= 0 { return lo }
        let fhi = f(hi)
        if fhi <= 0 { return hi }
        return crossing(f, hi, fhi, lo, flo, 1e-10)
    }

    private static func stripsAtLatitude(_ lat: Double) -> [Strip] {
        strips.filter { (lat >= $0.south && lat < $0.north) || (lat == northLimit && $0.north == northLimit) }
    }

    // A line labelled along the top or bottom: its world copy, zone and value.
    private struct EastingKey: Hashable {
        let offset: Double
        let zone: Int
        let value: Double
    }

    private struct SquareEntry {
        let region: Region
        let e0: Double
        let e1: Double
        let y0: Double
        let y1: Double
        let clipped: UtmBox
        let area: Double
    }

    /// `list` sorted by `key`, largest first, equals keeping their order: Kotlin's `sortedByDescending`, which is stable.
    private static func largestFirst<T>(_ list: [T], _ key: (T) -> Double) -> [T] {
        list.enumerated()
            .map { (key: key($0.element), index: $0.offset, element: $0.element) }
            .sorted { a, b in
                let order = kotlinCompare(b.key, a.key)
                return order != 0 ? order < 0 : a.index < b.index
            }
            .map(\.element)
    }

    private static func placeLabels(
        _ view: View, _ pieces: [Piece], _ frame: Frame, _ spacing: Double?, _ metersPerPixel: Double, _ options: GridOptions
    ) -> [GridLabel] {
        var placed = options.avoid
        var labels: [GridLabel] = []
        func place(_ label: (GridBox) -> GridLabel, _ box: GridBox) -> Bool {
            if labels.count >= options.maxLabels { return false }
            if box.x0 < 0 || box.y0 < 0 || box.x1 > frame.width || box.y1 > frame.height { return false }
            let g = gapPx
            if placed.contains(where: { box.x0 < $0.x1 + g && box.x1 + g > $0.x0 && box.y0 < $0.y1 + g && box.y1 + g > $0.y0 }) {
                return false
            }
            placed.append(box)
            labels.append(label(box))
            return true
        }
        func centredBox(_ x: Double, _ y: Double, _ text: String) -> GridBox {
            let w = labelWidth(text)
            return GridBox(x0: x - w / 2, y0: y - heightPx / 2, x1: x + w / 2, y1: y + heightPx / 2)
        }
        let minor = spacing.map { $0 < squareM } ?? false

        // Grid zone designators, largest cells first, while the squares are not yet labelled with them.
        if !minor {
            struct Shown {
                let cell: Cell
                let x0: Double
                let x1: Double
                let y0: Double
                let y1: Double
                let area: Double
            }
            var cells: [Shown] = []
            for piece in pieces {
                for cell in gridZones {
                    let s = javaMax(cell.south, view.south)
                    let n = javaMin(cell.north, view.north)
                    let w = javaMax(cell.west, piece.west)
                    let e = javaMin(cell.east, piece.east)
                    if n <= s || e <= w { continue }
                    let x0 = frame.x(w + piece.offset)
                    let x1 = frame.x(e + piece.offset)
                    let y0 = frame.y(n)
                    let y1 = frame.y(s)
                    cells.append(Shown(cell: cell, x0: x0, x1: x1, y0: y0, y1: y1, area: (x1 - x0) * (y1 - y0)))
                }
            }
            for shown in largestFirst(cells, \.area) {
                let text = "\(padded(String(shown.cell.zone), 2))\(shown.cell.band)"
                if shown.x1 - shown.x0 < labelWidth(text) + 24 || shown.y1 - shown.y0 < heightPx + 24 { continue }
                let x = (shown.x0 + shown.x1) / 2
                let y = (shown.y0 + shown.y1) / 2
                _ = place(
                    {
                        GridLabel(
                            kind: "gzd", edge: nil, text: text, zone: shown.cell.zone, value: nil, lat: frame.lat(y), lon: frame.lon(x),
                            box: $0)
                    }, centredBox(x, y, text))
            }
        }

        if minor, let step = spacing {
            // Eastings where each line crosses one edge of the view. Returns the lines whose label did not fit there, so they can try
            // the opposite edge; `only` limits the run to those.
            func eastingsAlong(_ lat: Double, _ labelTop: Double, _ edge: String, _ only: Set<EastingKey>?) -> Set<EastingKey> {
                var missed = Set<EastingKey>()
                for piece in pieces {
                    for strip in stripsAtLatitude(lat) {
                        let a = javaMax(strip.west, piece.west)
                        let b = javaMin(strip.east, piece.east)
                        if b <= a { continue }
                        let eastingAt = { (lon: Double) in project(strip.zone, lat, lon).e }
                        for value in multiplesIn(eastingAt(a), eastingAt(b), step) {
                            if alongEastEdge(strip.zone, strip.east, value) { continue }
                            let key = EastingKey(offset: piece.offset, zone: strip.zone, value: value)
                            if let only, !only.contains(key) { continue }
                            let lon = solve(eastingAt, a, b, value)
                            let text = principalDigits(value, step)
                            let x = frame.x(lon + piece.offset)
                            let w = labelWidth(text)
                            let placedHere = place(
                                {
                                    GridLabel(
                                        kind: "easting", edge: edge, text: text, zone: strip.zone, value: value, lat: lat,
                                        lon: lon + piece.offset, box: $0)
                                },
                                GridBox(x0: x - w / 2, y0: labelTop, x1: x + w / 2, y1: labelTop + heightPx)
                            )
                            if !placedHere { missed.insert(key) }
                        }
                    }
                }
                return missed
            }
            // Along the top first; a line whose label is blocked there (on a phone the search bar covers that strip) is labelled along
            // the bottom instead, so both halves of a reference can be read.
            let missedTop = eastingsAlong(view.north, frame.y(view.north) + edgePx, "top", nil)
            if !missedTop.isEmpty { _ = eastingsAlong(view.south, frame.y(view.south) - edgePx - heightPx, "bottom", missedTop) }

            // Northings down one side of the view, the same way: the left first, then the right. By value only: the two sides of the
            // view can be in different zones, and what the fallback must give is the row's digits.
            func northingsAlong(
                _ piece: Piece?, _ lon: Double, _ labelLeft: (String) -> Double, _ edge: String, _ only: Set<Double>?
            ) -> Set<Double> {
                var missed = Set<Double>()
                guard let piece else { return missed }
                for strip in strips where lon >= strip.west && lon < strip.east {
                    let s = javaMax(strip.south, view.south)
                    let n = javaMin(strip.north, view.north)
                    if n <= s { continue }
                    let northingAt = { (lat: Double) in project(strip.zone, lat, lon).y }
                    for y in multiplesIn(northingAt(s), northingAt(n), step) {
                        let value = northingValue(y)
                        if let only, !only.contains(value) { continue }
                        let lat = solve(northingAt, s, n, y)
                        let text = principalDigits(value, step)
                        let py = frame.y(lat)
                        let x = labelLeft(text)
                        let placedHere = place(
                            {
                                GridLabel(
                                    kind: "northing", edge: edge, text: text, zone: strip.zone, value: value, lat: lat,
                                    lon: lon + piece.offset, box: $0)
                            },
                            GridBox(x0: x, y0: py - heightPx / 2, x1: x + labelWidth(text), y1: py + heightPx / 2)
                        )
                        if !placedHere { missed.insert(value) }
                    }
                }
                return missed
            }
            // The first piece holds the view's left edge, the last its right edge.
            let leftEdge = pieces.first.flatMap { abs($0.west + $0.offset - view.west) < 1e-9 ? $0 : nil }
            let missedLeft = northingsAlong(leftEdge, leftEdge?.west ?? 0.0, { _ in edgePx }, "left", nil)
            let rightEdge = pieces.last.flatMap { abs($0.east + $0.offset - view.east) < 1e-9 ? $0 : nil }
            if let rightEdge, !missedLeft.isEmpty || leftEdge == nil {
                // A hair inside the edge, so the strip it falls in is the one the view ends in.
                _ = northingsAlong(
                    rightEdge, rightEdge.east - 1e-9, { text in frame.width - edgePx - labelWidth(text) }, "right",
                    leftEdge != nil ? missedLeft : nil)
            }
        }

        // 100 km squares: at their middle while they are small, else in a corner with the zone too, so a full reference can be read off
        // the screen.
        if spacing != nil {
            var squares: [SquareEntry] = []
            for region in regionsIn(view, pieces) {
                let box = utmBox(region)
                var c = floor(box.eMin / squareM)
                while c * squareM < box.eMax {
                    var r = floor(box.yMin / squareM)
                    while r * squareM < box.yMax {
                        let e0 = c * squareM
                        let e1 = (c + 1) * squareM
                        let y0 = r * squareM
                        let y1 = (r + 1) * squareM
                        let clipped = UtmBox(
                            eMin: javaMax(e0, box.eMin), eMax: javaMin(e1, box.eMax), yMin: javaMax(y0, box.yMin),
                            yMax: javaMin(y1, box.yMax))
                        // Only the columns a zone has (A to H and so on): the rest is outside every zone.
                        if c >= 1 && c <= 8 && clipped.eMax > clipped.eMin && clipped.yMax > clipped.yMin {
                            squares.append(
                                SquareEntry(
                                    region: region, e0: e0, e1: e1, y0: y0, y1: y1, clipped: clipped,
                                    area: (clipped.eMax - clipped.eMin) * (clipped.yMax - clipped.yMin)))
                        }
                        r += 1
                    }
                    c += 1
                }
            }

            // A label belongs to its square only if all of it is in the square and in the region.
            func fits(_ entry: SquareEntry, _ box: GridBox) -> Bool {
                [(box.x0, box.y0), (box.x1, box.y0), (box.x0, box.y1), (box.x1, box.y1)].allSatisfy { x, y in
                    let region = entry.region
                    let lat = frame.lat(y)
                    let lon = frame.lon(x) - region.offset
                    if lat < region.south || lat > region.north || lon < region.west || lon > region.east { return false }
                    let p = project(region.zone, lat, lon)
                    return p.e >= entry.e0 && p.e <= entry.e1 && p.y >= entry.y0 && p.y <= entry.y1
                }
            }

            for entry in largestFirst(squares, \.area) {
                let region = entry.region
                let clipped = entry.clipped
                let middleE = (clipped.eMin + clipped.eMax) / 2
                let middleY = (clipped.yMin + clipped.yMax) / 2
                let letters = MgrsConverter.squareLetters(region.zone, middleE, northingValue(middleY))
                func toScreen(_ e: Double, _ y: Double) -> (Double, Double) {
                    let point = unproject(region.zone, e, y)
                    return (frame.x(point.lon + region.offset), frame.y(point.lat))
                }
                var candidates: [(Double, Double)] = []
                if !minor {
                    candidates.append(toScreen(middleE, middleY))
                } else {
                    // Clear of the line labels along the top and left: first the view's own corners, top right first, then the corners
                    // of the square's part of the view, then its middle.
                    let halfW = labelWidth("\(zoneText(region.zone, 0.0)) \(letters)") / 2 + sidePx
                    let halfH = heightPx / 2 + cornerPx
                    let dx = halfW * metersPerPixel
                    let dy = halfH * metersPerPixel
                    candidates += [
                        (frame.width - halfW, halfH),
                        (frame.width - halfW, frame.height - halfH),
                        (halfW, halfH),
                        (halfW, frame.height - halfH),
                        toScreen(clipped.eMax - dx, clipped.yMax - dy),
                        toScreen(clipped.eMin + dx, clipped.yMax - dy),
                        toScreen(clipped.eMax - dx, clipped.yMin + dy),
                        toScreen(clipped.eMin + dx, clipped.yMin + dy),
                        toScreen(middleE, middleY),
                    ]
                }
                for (x, y) in candidates {
                    let lat = frame.lat(y)
                    let text = minor ? "\(zoneText(region.zone, lat)) \(letters)" : letters
                    let box = centredBox(x, y, text)
                    if !fits(entry, box) { continue }
                    let placedHere = place(
                        {
                            GridLabel(
                                kind: "square", edge: nil, text: text, zone: region.zone, value: nil, lat: lat, lon: frame.lon(x), box: $0)
                        },
                        box)
                    if placedHere { break }
                }
            }
        }

        return labels
    }

    // MARK: The grid

    private struct Level {
        let kind: String
        let spacing: Double
        let skipSquares: Bool
    }

    /// What to draw for a view: `bounds` as the map gives them (they may run past 180), and the ground scale at the view's centre in
    /// metres a pixel. A level that would pass the caps on lines or points is left out whole, finest first, and named in
    /// `GridResult.dropped`: the grid is then coarser, never wrong.
    public static func grid(_ bounds: GridBounds?, _ metersPerPixel: Double, options: GridOptions = GridOptions()) -> GridResult {
        guard let bounds else { return .empty }
        let (south, west, north, east) = (bounds.south, bounds.west, bounds.north, bounds.east)
        if ![south, west, north, east, metersPerPixel].allSatisfy(\.isFinite) { return .empty }
        if metersPerPixel <= 0 || north <= south || east <= west { return .empty }

        let frame = Frame(bounds, metersPerPixel)
        if 6 * frame.pxPerDeg < minZonePx { return .empty }

        // Lines also cover a margin round the view, so a pan does not show bare map before the redraw; labels go on the view itself.
        let padX = (east - west) * options.pad
        let padY = (mercatorY(north) - mercatorY(south)) * options.pad
        let lineView = View(
            south: javaMax(southLimit, mercatorLat(mercatorY(south) - padY)),
            north: javaMin(northLimit, mercatorLat(mercatorY(north) + padY)),
            west: west - padX,
            east: east + padX
        )
        if lineView.north <= lineView.south { return .empty }
        let linePieces = worldPieces(lineView.west, lineView.east)
        var lines = zoneLines(lineView, linePieces)
        var dropped: [String] = []
        var drawn: Double? = nil

        let spacing = gridSpacing(metersPerPixel)
        var levels: [Level] = []
        if let spacing {
            levels.append(Level(kind: "major", spacing: squareM, skipSquares: false))
            if spacing < squareM { levels.append(Level(kind: "minor", spacing: spacing, skipSquares: true)) }
        }

        let regions = levels.isEmpty ? [] : regionsIn(lineView, linePieces)
        let boxes = regions.map(utmBox)
        var lineCount = lines.count
        var pointCount = lines.count * 2
        for level in levels {
            if !dropped.isEmpty {
                dropped.append(level.kind)
                continue
            }
            let values = levelValues(regions, boxes, level.spacing, level.skipSquares)
            let count = values.reduce(0) { $0 + $1.eastings.count + $1.northings.count }
            if lineCount + count > options.maxLines {
                dropped.append(level.kind)
                continue
            }
            let traced = traceLevel(regions, boxes, values, level.kind, frame, options.tolerancePx)
            let points = traced.reduce(0) { $0 + $1.points.count }
            if pointCount + points > options.maxPoints {
                dropped.append(level.kind)
                continue
            }
            lineCount += traced.count
            pointCount += points
            lines += traced
            drawn = level.spacing
        }

        let labelView = View(south: javaMax(southLimit, south), north: javaMin(northLimit, north), west: west, east: east)
        let labels =
            labelView.north > labelView.south
            ? placeLabels(labelView, worldPieces(west, east), frame, drawn, metersPerPixel, options)
            : []
        return GridResult(spacing: drawn, lines: lines, labels: labels, dropped: dropped)
    }
}
