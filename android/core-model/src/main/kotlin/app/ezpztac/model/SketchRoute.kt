package app.ezpztac.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * A route the person sketched on the map, in the shape the web keeps it (`useRouteSketch`): its points in flight order (the AMPS points that
 * legs run between, and the shaping points that only bend the line), the plan for it, and the ground elevations fetched for its points.
 *
 * A mission imported from AMPS has the same points and plan ([MissionRoute]); a sketch also has a colour and can be hidden, and its points have
 * ids made here (a uuid each) rather than AMPS's.
 */
@Serializable
public data class SketchRoute(
    /** `sketch-<time>-<random>` on the web: the prefix is how the web tells a sketch from an imported route. */
    val id: String,
    val name: String,
    /** `#RRGGBB`. */
    val color: String,
    val visible: Boolean = true,
    val points: List<RoutePoint>,
    val plan: RoutePlan = RoutePlan(),
    /** Ground elevation in feet by point id. */
    val elevations: Map<String, Double> = emptyMap(),
    /** AMPS's id for the route's segment, which ties it to its legs in a mission file. Only a route of an imported mission has one; a sketched route never does. */
    val segmentId: String? = null,
    /** Fields a newer release wrote that this one does not know, kept so saving does not drop them ([RouteSets]). Never part of the model's own JSON. */
    @Transient val extras: JsonObject = JsonObject(emptyMap()),
)
