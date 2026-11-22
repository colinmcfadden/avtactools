package app.ezpztac.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where [ThreatDisplay] keeps which threats' rings and masks are off: two lists of threat ids, ids only. */
interface ThreatDisplayStore {
    fun load(): Pair<List<String>, List<String>>

    fun save(ringsOff: List<String>, masksOff: List<String>)
}

/** A [ThreatDisplayStore] held in memory, for tests and previews. */
class InMemoryThreatDisplayStore(var rings: List<String> = emptyList(), var masks: List<String> = emptyList()) : ThreatDisplayStore {
    override fun load(): Pair<List<String>, List<String>> = rings to masks

    override fun save(ringsOff: List<String>, masksOff: List<String>) {
        rings = ringsOff
        masks = masksOff
    }
}

/**
 * Whether this device draws each threat's range rings and terrain mask (the owner's request of 2026-11-22: both by default, and a way to show and hide each from the
 * threat's bar over the map). **Both are on unless the person turned them off**, so a threat new to this device shows its rings and, once worked out, its mask.
 *
 * The person's own, as whether a threat is hidden is: never saved with a threat, synced or sent in a pack, so it may be changed in a read-only pack and on a threat
 * of someone else's. It only takes away: a radar whose own settings show no ring or mask ([app.ezpztac.model.Radar.showRangeRings], `showMask`, what a `.ths`
 * carries) still shows none. Ids only, which name no place, the newest [MAX] of each kept.
 */
@Singleton
class ThreatDisplay @Inject constructor(private val store: ThreatDisplayStore) {
    private val _ringsOff: MutableStateFlow<Set<String>>
    private val _masksOff: MutableStateFlow<Set<String>>

    init {
        val (rings, masks) = try {
            store.load()
        } catch (_: Exception) {
            emptyList<String>() to emptyList()
        }
        _ringsOff = MutableStateFlow(rings.takeLast(MAX).toSet())
        _masksOff = MutableStateFlow(masks.takeLast(MAX).toSet())
    }

    /** The threats whose rings this device does not draw. */
    val ringsOff: StateFlow<Set<String>> = _ringsOff.asStateFlow()

    /** The threats whose terrain mask this device neither draws nor asks for. */
    val masksOff: StateFlow<Set<String>> = _masksOff.asStateFlow()

    fun ringsOn(id: String): Boolean = id !in _ringsOff.value

    fun maskOn(id: String): Boolean = id !in _masksOff.value

    fun setRings(id: String, on: Boolean) {
        _ringsOff.value = toggled(_ringsOff.value, id, on)
        keep()
    }

    fun setMask(id: String, on: Boolean) {
        _masksOff.value = toggled(_masksOff.value, id, on)
        keep()
    }

    private fun toggled(off: Set<String>, id: String, on: Boolean): Set<String> = if (on) off - id else (off - id) + id

    private fun keep() {
        try {
            store.save(_ringsOff.value.toList().takeLast(MAX), _masksOff.value.toList().takeLast(MAX))
        } catch (_: Exception) {
            // A preference that cannot be written costs the choice at the next launch, never the app.
        }
    }

    companion object {
        /** How many of each are remembered. */
        const val MAX = 500
    }
}
