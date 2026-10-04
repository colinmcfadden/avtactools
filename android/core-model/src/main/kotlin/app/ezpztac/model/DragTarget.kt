package app.ezpztac.model

/**
 * What a long press on the map can pick up and drag: a planning graphic, a point of a route, or a threat. (A local point is somebody else's data and is not moved here.)
 * A tap on the same thing holds it, which is how it is turned or its other options are reached.
 */
public sealed interface DragTarget {
    public data class Graphic(val ref: GraphicRef) : DragTarget

    /** A point of a route of the open set, named or shaping. */
    public data class RoutePoint(val routeId: String, val pointId: String) : DragTarget

    public data class Threat(val id: String) : DragTarget
}
