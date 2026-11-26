package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.LatLon
import app.ezpztac.planning.LzLegs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The legs that fly into and out of an LZ/PZ ([legs]), with the LZ/PZ they were worked out for ([diagramId]). */
public data class OpenLzLegs(val diagramId: String?, val legs: LzLegs) {
    /** The legs, for [diagram] only: another's (one just opened, before its own are worked out) gets none, so one LZ/PZ's legs never hide another's doghouses. */
    public fun of(diagram: Diagram?): LzLegs = if (diagram != null && diagram.id == diagramId) legs else LzLegs.NONE

    public companion object {
        public val NONE: OpenLzLegs = OpenLzLegs(null, LzLegs.NONE)
    }
}

/**
 * The legs of the routes on the map (the open set's, visible ones) that fly into and out of the open LZ/PZ (the owner's, 2026-11-26; [LzLegs]): while one does,
 * the LZ/PZ's own doghouse for it is neither drawn nor held, and its slope is judged on the leg's course. Read by the map and the LZ/PZ panel alike.
 */
public interface RouteLegs {
    public val open: Flow<OpenLzLegs>

    public companion object {
        /** No route stands in for anything: for a panel tried without the routes. */
        public val None: RouteLegs = object : RouteLegs {
            override val open: Flow<OpenLzLegs> = flowOf(OpenLzLegs.NONE)
        }

        /** The open LZ/PZ's legs among the open set's routes, worked out again only when its target or the routes change. */
        public fun of(diagrams: DiagramSession, routes: RouteSession): RouteLegs = object : RouteLegs {
            override val open: Flow<OpenLzLegs> = combine(
                diagrams.active.map { it?.id to it?.target }.distinctUntilChanged(),
                routes.active.map { it?.routes }.distinctUntilChanged(),
            ) { (id, target), shown -> OpenLzLegs(id, LzLegs.of(target?.let { LatLon(it.lat, it.lon) }, shown.orEmpty())) }.distinctUntilChanged()
        }
    }
}
