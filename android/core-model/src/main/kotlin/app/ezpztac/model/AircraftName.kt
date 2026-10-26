package app.ezpztac.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A helicopter's name on an LZ/PZ: a chalk or tail number the crew calls it by (the owner's request, 2026-10-22), shown in its title, its bar and under it on
 * the map. Kept as the aircraft's `label`; the web copies every field of an aircraft, so it survives the web's saves, though the web does not show it yet
 * (AGENTS.md §15).
 */
public object AircraftName {
    /** The field of a saved aircraft that holds it. */
    public const val FIELD: String = "label"

    /** The longest it may be, in characters: room for `CHALK 12` or a tail number such as `20-27345`, and short enough to sit under it on the map. */
    public const val MAX: Int = 20

    /** The name of the aircraft [saved], or null with none (absent, blank, or not text). */
    public fun of(saved: JsonObject): String? = (saved[FIELD] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
