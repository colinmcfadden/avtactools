package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatSets
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.ThreatMaskDto
import app.ezpztac.network.threatMask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The server, as the terrain mask needs it. [ApiClientThreatMaskApi] is the real one; tests script their own. */
public interface ThreatMaskApi {
    public suspend fun mask(at: LatLon, radars: List<Radar>): ThreatMaskDto
}

/** [ThreatMaskApi] over the real client. */
public class ApiClientThreatMaskApi(private val client: ApiClient) : ThreatMaskApi {
    override suspend fun mask(at: LatLon, radars: List<Radar>): ThreatMaskDto = client.threatMask(at, radars)
}

/** One radar's mask, as a picture to lay over the map. */
public data class ThreatMaskImage(val type: Int, val dataUri: String)

/**
 * What a threat's radars could see over the terrain, at the moment it was asked: where the pictures go ([south] and the rest) and the pictures. [askedFor] is what the
 * answer is *for*: a threat that has since been moved, or whose radars were changed, is not what this shows ([isFor]).
 */
public data class ThreatMask(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val images: List<ThreatMaskImage>,
    val askedFor: Inputs,
) {
    /** What the server is told: where the threat is and what its radars are. Nothing else about it (its name, its notes) leaves the device. */
    public data class Inputs(val lat: Double, val lon: Double, val radars: List<Radar>)

    /** Whether this is still the mask of [threat]: the same place and the same radars. */
    public fun isFor(threat: Threat): Boolean = askedFor == Inputs(threat.lat, threat.lon, threat.radars)
}

/** Where a threat's mask is. */
public sealed interface MaskState {
    /** Asked for, and the server is working. */
    public data object Working : MaskState

    public data class Ready(val mask: ThreatMask) : MaskState

    /** It could not be had; [message] is in the app's words (a server's own text is never shown). */
    public data class Failed(val message: String) : MaskState
}

/**
 * The terrain masks of the threats: what each threat's radars can see, worked out by the server **only when the person asks for a threat's mask**. Threats are otherwise never
 * sent anywhere, so nothing here asks on its own: not when a threat is made, not when it is moved, not at launch. A mask that was asked for and then the threat moved is kept but not
 * drawn ([MaskState.Ready] with a mask that is no longer [ThreatMask.isFor] it), and the person is told it is out of date and asks again if they want it.
 *
 * A mask goes with its threat: kept in the threat's saved set, with what it was worked out for, so it comes back when the set is opened and is not asked for again ([ThreatStore.masks],
 * the owner's decision of 2026-11-07). For an account that cannot save threats it is held in memory only.
 */
public class ThreatMasks(
    private val api: ThreatMaskApi,
    private val store: ThreatStore,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, MaskState>>(emptyMap())

    /** Each threat's mask, by the threat's id. A threat that has none asked for is not in it. */
    public val states: StateFlow<Map<String, MaskState>> = _states.asStateFlow()

    init {
        // A threat that is gone takes its mask with it, however it went: removed, or wiped at sign-out. One that came back with its set shows, unasked.
        val ids = store.entries.map { entries -> entries.mapTo(HashSet()) { it.id } as Set<String> }.distinctUntilChanged()
        scope.launch {
            var seen = emptyMap<String, JsonObject>()
            // Read afresh, not from what combine hands over: that can be a step behind a mask just hidden, and would put it back.
            combine(ids, store.masks) { _, _ -> }.collect {
                val present = store.entries.value.mapTo(HashSet()) { it.id }
                val masks = store.masks.value
                _states.update { states -> settled(states, present, masks, seen) }
                seen = masks
            }
        }
    }

    private fun settled(states: Map<String, MaskState>, present: Set<String>, masks: Map<String, JsonObject>, seen: Map<String, JsonObject>): Map<String, MaskState> {
        val out = HashMap<String, MaskState>()
        for ((id, state) in states) if (id in present && (state !is MaskState.Ready || id in masks)) out[id] = state
        for ((id, raw) in masks) {
            if (id !in present) continue
            val now = out[id]
            if (now is MaskState.Working || now is MaskState.Failed || (now is MaskState.Ready && seen[id] === raw)) continue
            maskOf(raw)?.let { out[id] = MaskState.Ready(it) }
        }
        return out
    }

    /**
     * Asks the server what the threat [id]'s radars can see. Does nothing if one is being asked for already, or the threat is not there. A threat with no radar that shows a mask is
     * told so **without** asking the server: there is nothing to ask for.
     */
    public suspend fun request(id: String) {
        val entry = store.entries.value.firstOrNull { it.id == id } ?: return
        if (_states.value[id] == MaskState.Working) return
        val threat = entry.threat
        if (threat.radars.none { it.showMask && it.bands.any { band -> band.viewable } }) {
            _states.update { it + (id to MaskState.Failed(NOTHING_TO_SHOW)) }
            return
        }
        _states.update { it + (id to MaskState.Working) }
        val asked = ThreatMask.Inputs(threat.lat, threat.lon, threat.radars)
        var saved: JsonObject? = null
        val outcome = try {
            val answer = api.mask(LatLon(asked.lat, asked.lon), asked.radars)
            val box = answer.bounds
            if (box.size < 2 || box[0].size < 2 || box[1].size < 2) {
                MaskState.Failed(COULD_NOT)
            } else {
                val mask = ThreatMask(box[0][0], box[0][1], box[1][0], box[1][1], answer.radars.map { ThreatMaskImage(it.type, it.png) }, asked)
                saved = savedForm(mask, threat)
                MaskState.Ready(mask)
            }
        } catch (e: CancellationException) {
            _states.update { it - id }
            throw e
        } catch (_: NetworkException) {
            MaskState.Failed("There is no connection to the server, so the mask could not be worked out.")
        } catch (e: ApiException) {
            // The server's own words are not shown: a failure inside it can be a Python exception's text.
            MaskState.Failed(if (e.status == 502) "There is no terrain data for this area." else COULD_NOT)
        } catch (_: Exception) {
            MaskState.Failed(COULD_NOT)
        }
        // A threat taken away while the server worked has no mask to keep.
        if (store.entries.value.none { it.id == id }) {
            _states.update { it - id }
        } else {
            _states.update { it + (id to outcome) }
            saved?.let { store.setMask(id, it) }
        }
    }

    /** Puts a threat's mask away (the button's *Hide*): nothing is drawn, and nothing is asked. */
    public fun hide(id: String) {
        _states.update { it - id }
        store.clearMask(id)
    }

    /** Puts every mask away. */
    public fun clear() {
        _states.value = emptyMap()
    }

    /** A mask as the web keeps it: `{bounds, radars, for}`, `for` being what it was worked out for. */
    private fun savedForm(mask: ThreatMask, threat: Threat): JsonObject = buildJsonObject {
        put("bounds", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(mask.south)); add(JsonPrimitive(mask.west)) }); add(buildJsonArray { add(JsonPrimitive(mask.north)); add(JsonPrimitive(mask.east)) }) })
        put("radars", JsonArray(mask.images.map { image -> buildJsonObject { put("type", image.type); put("png", image.dataUri) } }))
        put("for", ThreatSets.subjectOf(threat))
    }

    /** A saved mask as one to draw, or null for one that cannot be read. */
    private fun maskOf(saved: JsonObject): ThreatMask? {
        val bounds = saved["bounds"] as? JsonArray ?: return null
        fun at(row: Int, column: Int) = ((bounds.getOrNull(row) as? JsonArray)?.getOrNull(column) as? JsonPrimitive)?.doubleOrNull
        val images = (saved["radars"] as? JsonArray ?: return null).map { element ->
            val radar = element as? JsonObject ?: return null
            ThreatMaskImage((radar["type"] as? JsonPrimitive)?.intOrNull ?: return null, (radar["png"] as? JsonPrimitive)?.content ?: return null)
        }
        val subject = saved["for"] as? JsonObject ?: return null
        val inputs = ThreatMask.Inputs(
            (subject["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null,
            (subject["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null,
            ThreatSets.radarsOf(subject["radars"] ?: return null) ?: return null,
        )
        return ThreatMask(at(0, 0) ?: return null, at(0, 1) ?: return null, at(1, 0) ?: return null, at(1, 1) ?: return null, images, inputs)
    }

    public companion object {
        public const val NOTHING_TO_SHOW: String = "None of this threat's radars shows a mask. Turn on a radar's mask and an altitude band in its settings."
        public const val COULD_NOT: String = "The mask could not be worked out."
    }
}
