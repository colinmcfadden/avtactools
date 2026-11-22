/// What a long press on the map can pick up and drag: a planning graphic, a point of a route, or a threat. (A local point is
/// somebody else's data and is not moved here.) A tap on the same thing holds it, which is how it is turned or its other options
/// are reached.
public enum DragTarget: Hashable, Sendable {
    case graphic(GraphicRef)
    /// The end of a PZ marker's arrow: dragged on its own, it sets how far the marker reaches and which way it points. The marker's
    /// anchor stays.
    case pzTip(GraphicRef)
    /// A point of a route of the open set, named or shaping.
    case routePoint(routeId: String, pointId: String)
    case threat(id: String)
}
