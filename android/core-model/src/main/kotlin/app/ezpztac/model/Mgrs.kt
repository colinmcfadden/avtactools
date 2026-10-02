package app.ezpztac.model

/**
 * A Military Grid Reference System reference such as `16S GD 66993 52949`.
 *
 * The digits stay text because leading zeros are part of the grid: `00011` is
 * eleven metres, and an integer would lose the zeros. [easting] and [northing]
 * always have the same length (1 to 5 digits is 10 km down to 1 m; more refines
 * past a metre).
 */
public data class Mgrs(
    val zone: Int,
    val band: Char,
    /** The 100 km square: column letter then row letter, e.g. `GD`. */
    val square: String,
    val easting: String,
    val northing: String,
) {
    init {
        require(zone in 1..60) { "UTM zone $zone is outside 1..60" }
        require(easting.length == northing.length) { "easting and northing must have the same number of digits" }
    }

    /** Digits per axis. */
    public val digits: Int get() = easting.length

    /** `16S GD 66993 52949`, with the zone zero-padded as the server writes it (`05R`, not `5R`). */
    public fun format(): String = "${zone.toString().padStart(2, '0')}$band $square $easting $northing"

    override fun toString(): String = format()
}
