package app.ezpztac.model

/**
 * What a long press on the map can pick up and drag: a planning graphic, a point of a route, a threat, a backstop's arrow, or a point of a FLOT or phase line. (A local point is somebody else's data and is not moved here.)
 * A tap on the same thing holds it, which is how it is turned or its other options are reached.
 */
public sealed interface DragTarget {
    public data class Graphic(val ref: GraphicRef) : DragTarget

    /** The end of a PZ marker's arrow: dragged on its own, it sets how far the marker reaches and which way it points. The marker's anchor stays. */
    public data class PzTip(val ref: GraphicRef) : DragTarget

    /** A point of a route of the open set, named or shaping. */
    public data class RoutePoint(val routeId: String, val pointId: String) : DragTarget

    public data class Threat(val id: String) : DragTarget

    /** A backstop's arrow, moved whole (the web's: the arrow is what moves, never the line). Its tip goes with it, off the target if it was on it. */
    public data class BackstopArrow(val ref: GraphicRef) : DragTarget

    /** The end of a backstop's arrow: its length and heading change and its tail stays. Let go within 20 px of the target, it goes back onto it. */
    public data class BackstopTip(val ref: GraphicRef) : DragTarget

    /** A point of a FLOT or a phase line ([key]: `flots` or `phaseLines`) of the set [setId] open in a route set's session. */
    public data class LineVertex(val setId: String, val key: String, val lineId: String, val index: Int) : DragTarget
}
