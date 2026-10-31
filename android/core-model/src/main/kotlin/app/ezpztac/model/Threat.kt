package app.ezpztac.model

import kotlinx.serialization.Serializable

/*
 * A threat, as the web sends it to the server and writes it into an AMPS `.ths`
 * file (`threatToPayload` in `threatModel.js`). Threats are never stored on a
 * server and never saved to the device's database (AGENTS.md, section 2): they live
 * in memory and are exported, so nothing here is a persistence model.
 *
 * A threat has a position, a MIL-STD-2525C symbol id, and up to two radars: type 0 is
 * Detection, type 1 is Engagement. Each radar has a range, an antenna height, an AGL or
 * MSL flag and up to three aircraft-altitude bands whose terrain mask is drawn as an overlay.
 */

/** One aircraft-altitude band of a radar: the mask is computed for an aircraft at [altFt]. */
@Serializable
public data class RadarBand(
    val altFt: Double,
    /** The overlay colour as `#rrggbb`. Not stored in a `.ths`: reading one gives the type's default. */
    val color: String,
    val alpha: Double,
    /** AMPS's colour number for the band, which is what a `.ths` stores. */
    val colorIndex: Int,
    val viewable: Boolean,
)

@Serializable
public data class Radar(
    /** [Radars.DETECTION] or [Radars.ENGAGEMENT]. */
    val type: Int,
    val rangeNmi: Double,
    val antennaHeightFt: Double,
    val aglNotMsl: Boolean,
    val showMask: Boolean,
    val showRangeRings: Boolean,
    val bands: List<RadarBand>,
)

@Serializable
public data class Threat(
    val name: String,
    val milstdId: String,
    val lat: Double,
    val lon: Double,
    val information: String,
    val source: String,
    val showThreat: Boolean = true,
    val radars: List<Radar>,
)

/** The defaults the web gives a new radar (`defaultRadar` in `threatModel.js`). */
public object Radars {
    public const val DETECTION: Int = 0
    public const val ENGAGEMENT: Int = 1

    public const val DEFAULT_MILSTD_ID: String = "SHGPEWMAI------"
    public const val DEFAULT_SOURCE: String = "SOF"
    public const val DEFAULT_NAME: String = "Threat"

    private fun band(altFt: Double, color: String, alpha: Double, colorIndex: Int) =
        RadarBand(altFt, color, alpha, colorIndex, viewable = true)

    private fun detectionBands() = listOf(
        band(50.0, "#fbbf24", 0.32, 1),
        band(250.0, "#f97316", 0.28, 3),
        band(500.0, "#facc15", 0.24, 5),
    )

    private fun engagementBands() = listOf(
        band(50.0, "#ef4444", 0.42, 2),
        band(250.0, "#dc2626", 0.34, 4),
        band(500.0, "#b91c1c", 0.26, 0),
    )

    /**
     * A new radar of [type]. As on the web, anything that is not Detection gets the
     * Engagement defaults and keeps the number it was given.
     */
    public fun default(type: Int): Radar = Radar(
        type = type,
        rangeNmi = if (type == DETECTION) 25.0 else 15.0,
        antennaHeightFt = 20.0,
        aglNotMsl = true,
        showMask = true,
        showRangeRings = true,
        bands = if (type == DETECTION) detectionBands() else engagementBands(),
    )

    /** What a threat with no radar rows in its file gets: a Detection and an Engagement radar. */
    public fun defaultPair(): List<Radar> = listOf(default(DETECTION), default(ENGAGEMENT))
}
