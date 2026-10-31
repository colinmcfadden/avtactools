package app.ezpztac.map

import app.ezpztac.model.DragTarget
import app.ezpztac.model.LatLon

/**
 * What a long press on the map picks up, worked out on the screen with [MapProjection] and so tried without a GPU. It looks at the same things a tap does, in the same order
 * (a planning graphic, then a threat, then a route), so the thing a finger would *hold* is the thing it would *drag*; but only what has a place to move can be dragged:
 * a press on the line between two points of a route, or on nothing, picks nothing up, and the touch goes on as a pan of the map.
 */
object DragHitTest {
    fun pick(
        graphics: GraphicsScene,
        threats: ThreatScene,
        routes: RouteScene,
        view: MapProjection,
        at: LatLon,
        touchRadiusPx: Double,
        footprints: UnitFootprints? = null,
    ): DragTarget? {
        // The end of a PZ marker's arrow is its own handle, looked at before the marker it belongs to.
        GraphicHitTest.pickPzTip(graphics, view, at, touchRadiusPx)?.let { return DragTarget.PzTip(it) }
        GraphicHitTest.pick(graphics, view, at, touchRadiusPx, footprints)?.let { return DragTarget.Graphic(it) }
        ThreatHitTest.pick(threats, view, at, touchRadiusPx)?.let { return DragTarget.Threat(it) }
        val route = RouteHitTest.pick(routes, view, at, touchRadiusPx) ?: return null
        val point = route.pointId ?: return null
        return DragTarget.RoutePoint(route.routeId, point)
    }
}
