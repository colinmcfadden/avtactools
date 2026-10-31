package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Threat
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * A mask is a picture and is **held in memory only**: it is not written to the threat's sealed file, and it goes when the threat does (a sign-out, a removal, a wipe).
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
        // A threat that is gone takes its mask with it, however it went: removed, or wiped at sign-out.
        scope.launch {
            store.entries.collect { entries ->
                val ids = entries.map { it.id }.toSet()
                _states.update { states -> states.filterKeys { it in ids } }
            }
        }
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
        val outcome = try {
            val answer = api.mask(LatLon(asked.lat, asked.lon), asked.radars)
            val box = answer.bounds
            if (box.size < 2 || box[0].size < 2 || box[1].size < 2) {
                MaskState.Failed(COULD_NOT)
            } else {
                MaskState.Ready(ThreatMask(box[0][0], box[0][1], box[1][0], box[1][1], answer.radars.map { ThreatMaskImage(it.type, it.png) }, asked))
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
        if (store.entries.value.none { it.id == id }) _states.update { it - id } else _states.update { it + (id to outcome) }
    }

    /** Puts a threat's mask away (the button's *Hide*): nothing is drawn, and nothing is asked. */
    public fun hide(id: String) {
        _states.update { it - id }
    }

    /** Puts every mask away. */
    public fun clear() {
        _states.value = emptyMap()
    }

    public companion object {
        public const val NOTHING_TO_SHOW: String = "None of this threat's radars shows a mask. Turn on a radar's mask and an altitude band in its settings."
        public const val COULD_NOT: String = "The mask could not be worked out."
    }
}
