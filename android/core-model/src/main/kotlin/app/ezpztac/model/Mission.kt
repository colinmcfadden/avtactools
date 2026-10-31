package app.ezpztac.model

import kotlinx.serialization.Serializable

/*
 * An AMPS mission as the app reads it from a `.msnx` file: the routes in it, with
 * the plan AMPS recorded for each, and the airframe it was planned for. The route
 * shape is the web's (`parseMsnx.js`), so a mission imported on one client saves and
 * opens on the other.
 */

/** The airframe a mission was planned for, as AMPS writes it into the file. */
@Serializable
public data class MissionAircraft(
    /** The full AMPS vehicle description, e.g. `Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L`. */
    val description: String,
    /** Its last field, the designation (`UH-60L`), or null if there is none. */
    val designation: String? = null,
)

/** One route in a mission. */
@Serializable
public data class MissionRoute(
    val name: String,
    /** AMPS's id for the route's segment, which ties it to its legs. */
    val segmentId: String? = null,
    /** Every point in flight order, shaping points included. */
    val points: List<RoutePoint>,
    /** Planned altitudes, airspeeds, winds and clock times, read back out of the file. */
    val plan: RoutePlan,
    /** Ground elevation in feet by AMPS point id. */
    val elevations: Map<String, Double> = emptyMap(),
)

@Serializable
public data class Mission(
    val routes: List<MissionRoute>,
    val aircraft: MissionAircraft? = null,
)
