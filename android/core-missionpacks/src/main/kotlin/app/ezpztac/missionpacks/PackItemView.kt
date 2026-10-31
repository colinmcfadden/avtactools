package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One item of an open pack as the editors see it: the web's `{uuid, kind, name, data}` that useMissionPack lists. [data] is the
 * pack's JSON for the item as it is (an LZ/PZ's document, a route set's `{version, routes}`, a set's list of points), JSON null
 * when the pack has none, and the same instance as the session's view of it, so a caller can tell cheaply whether it changed.
 * [info] is what the server says about the item besides its content (who made it, where it was copied from), and [pendingCreate]
 * says it was made here and the server has not taken it yet.
 */
public data class PackItemView(
    val uuid: String,
    val kind: String,
    val name: String,
    val data: JsonElement,
    val info: JsonObject? = null,
    val pendingCreate: Boolean = false,
)
