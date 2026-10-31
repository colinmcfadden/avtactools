package app.ezpztac.model

import kotlinx.serialization.Serializable

/*
 * AMPS local points, as the web app holds them after importing an `.LPS` file
 * (`parseLps.js`). The set's identity on a device (an id) is the app's business;
 * what is here is what the file said.
 */

/** One local point. [elevationFt] is the feet AMPS recorded, or null if the file gave no number. */
@Serializable
public data class LocalPoint(
    val name: String,
    val description: String,
    val group: String,
    val icon: String,
    val elevationFt: Double?,
    val lat: Double,
    val lon: Double,
)

/** A named set of points, as read from one file. */
@Serializable
public data class LocalPointSet(
    val name: String,
    val points: List<LocalPoint>,
)
