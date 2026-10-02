package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The boundaries a diagram holds, as points. They are saved the way the web keeps them, `[[lat, lon], …]` in JSON of uncertain shape, so
 * reading one is the one place a bad point is dealt with: it is skipped, never guessed at.
 */
public object DiagramGeometry {
    /** `[[lat, lon], …]` as points. An entry that is not a pair of numbers is left out; anything that is not an array is no points. */
    public fun points(saved: JsonElement?): List<LatLon> = (saved as? JsonArray).orEmpty().mapNotNull { point ->
        val pair = point as? JsonArray ?: return@mapNotNull null
        val lat = number(pair.getOrNull(0)) ?: return@mapNotNull null
        val lon = number(pair.getOrNull(1)) ?: return@mapNotNull null
        LatLon(lat, lon)
    }

    /** A finite number, from a number or from numeric text (the web's map reads `"34.5"` as 34.5); NaN, infinity and everything else are nothing. */
    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    /** The analysis boundary: a polygon of three points or more, or nothing. Not closed (the first point is not repeated at the end). */
    public fun boundary(diagram: Diagram): List<LatLon> = polygon(diagram.analysis.detectedLZ)

    /** A boundary the person drew and has not analysed: two points (a line being drawn) or more, or nothing. */
    public fun drawn(diagram: Diagram): List<LatLon> = points(diagram.analysis.customLZ).takeIf { it.size >= 2 }.orEmpty()

    /** [saved] as a polygon: three points or more, or nothing. */
    public fun polygon(saved: JsonElement?): List<LatLon> = points(saved).takeIf { it.size >= 3 }.orEmpty()
}
