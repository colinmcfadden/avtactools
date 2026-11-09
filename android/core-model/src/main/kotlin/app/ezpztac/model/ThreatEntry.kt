package app.ezpztac.model

import kotlinx.serialization.Serializable

/**
 * A threat in the picture a crew is planning against: the threat as it is exported, with the id the app gives it and whether it is shown. The web's
 * `visible` and `id` (`useThreats`); its colour and its fetched mask are not here, because the colours of a threat's rings are the radar type's.
 *
 * What is saved to the Library is the web's own flat threat, read and written by [ThreatSets]; this is what the screens and the map use, and what an account that cannot save threats
 * keeps in its sealed file on the device (`ThreatStore`).
 */
@Serializable
public data class ThreatEntry(
    val id: String,
    val threat: Threat,
    val visible: Boolean = true,
)

/** How threats are told apart. The web's `threat-<millis>-<n>`; here nudged past any taken, so two made in one millisecond do not collide. */
public object ThreatIds {
    public fun next(taken: Collection<String>, nowMillis: Long): String {
        var n = 0
        while (true) {
            val id = "threat-$nowMillis-$n"
            if (id !in taken) return id
            n++
        }
    }
}
