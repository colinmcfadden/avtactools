package app.ezpztac.geo

import app.ezpztac.model.LatLon

/** What typing a place into a search or target field means. */
public sealed interface PlaceResult {
    /** A position, with how it was read, so the person can see the app understood them. */
    public data class Found(val at: LatLon, val description: String) : PlaceResult

    public data class NotUnderstood(val message: String) : PlaceResult
}

/**
 * Reads a place the way the web's search and target fields do: an MGRS grid (any even digit count, spaces and case ignored), or a
 * coordinate in any format [CoordinateParser] knows. A grid is tried first, and a coordinate never reinterprets one (the parser refuses
 * grids), so typing a grid is never read as something else.
 */
public object PlaceSearch {
    public fun resolve(text: String): PlaceResult {
        val typed = text.trim()
        if (typed.isEmpty()) return PlaceResult.NotUnderstood("Enter a grid or a coordinate.")
        if (CoordinateParser.looksLikeMgrs(typed)) {
            val point = MgrsConverter.toLatLon(typed)
            return if (point != null) PlaceResult.Found(point, "MGRS grid") else PlaceResult.NotUnderstood("That grid is not on the map.")
        }
        val parsed = CoordinateParser.parse(typed) ?: return PlaceResult.NotUnderstood("Could not read that as a grid or a coordinate.")
        return PlaceResult.Found(LatLon(parsed.lat, parsed.lon), parsed.label.replaceFirstChar { it.uppercase() })
    }
}
