package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the LZ/PZs hidden from the map are kept between launches: their ids (a record's uuid, or a Mission Pack item's local id), oldest first. A seam: the
 * app keeps them in a preference, a test in memory.
 */
interface HiddenDiagramStore {
    fun load(): List<String>

    fun save(ids: List<String>)
}

/** A [HiddenDiagramStore] held in memory, for tests and previews. */
class InMemoryHiddenDiagramStore(var kept: List<String> = emptyList()) : HiddenDiagramStore {
    override fun load(): List<String> = kept

    override fun save(ids: List<String>) {
        kept = ids
    }
}

/**
 * The open LZ/PZs on the map besides the one being worked on (the owner's request of 2026-10-21: a new LZ/PZ no longer takes the others off the map). Which of
 * them the person hid with the eye on its row ([hidden]), and which of them is held after a tap on it ([held], whose bar offers *Open* and *Hide*).
 *
 * Hidden is this device's own choice, as whether a set of local points is shown: never saved with an LZ/PZ, never synced, never sent in a Mission Pack. The one
 * being worked on is always drawn, whatever it says, and opening one shows it again ([shown]): it was looked at. Ids only, which name no place, and at most
 * [MAX], the oldest going first, so ids of LZ/PZs long closed do not gather.
 */
@Singleton
class DiagramsOnMap @Inject constructor(private val store: HiddenDiagramStore) {
    private val _hidden = MutableStateFlow(clean(store.load()))

    /** The LZ/PZs not drawn while another is being worked on, by id. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _held = MutableStateFlow<String?>(null)

    /** The other open LZ/PZ a tap on the map holds: its bar is over the map. Never the one being worked on, whose graphics are held instead. */
    val held: StateFlow<String?> = _held.asStateFlow()

    /** [id] is drawn ([shown]) or not. Hiding the one held puts it down, since it is no longer there to be seen. */
    @Synchronized
    fun setShown(id: String, shown: Boolean) {
        if (id.isBlank()) return
        val now = _hidden.value
        val next = if (shown) now - id else (now - id + id).toList().takeLast(MAX).toSet()
        if (!shown && _held.value == id) _held.value = null
        if (next == now) return
        store.save(next.toList())
        _hidden.value = next
    }

    /** Hides [id] if it is drawn, and draws it again if it is hidden: the eye on its row. */
    fun toggle(id: String) = setShown(id, id in _hidden.value)

    /** [id] is opened: it is on the map now, and stays there when another is opened after it. */
    fun shown(id: String) = setShown(id, true)

    fun hold(id: String) {
        _held.value = id
    }

    /** Nothing is held (another thing on the map was, or the person put it down). */
    fun release() {
        _held.value = null
    }

    companion object {
        /** Past this the ones hidden longest ago are forgotten (drawn again, if they are still open). */
        const val MAX = 50

        private fun clean(ids: List<String>) = ids.filter { it.isNotBlank() }.distinct().takeLast(MAX).toSet()
    }
}
