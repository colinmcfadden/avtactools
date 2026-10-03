package app.ezpztac.model

import kotlinx.serialization.Serializable

/**
 * A threat in the picture a crew is planning against: the threat as it is exported, with the id the app gives it and whether it is shown. The web's
 * `visible` and `id` (`useThreats`); its colour and its fetched mask are not here, because the colours of a threat's rings are the radar type's.
 *
 * Threats are held in memory and in one sealed, short-lived file on the device (`ThreatStore`), never on a server and never in the database
 * (`AGENTS.md` §2), so nothing about this type is a persistence model for sync.
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
