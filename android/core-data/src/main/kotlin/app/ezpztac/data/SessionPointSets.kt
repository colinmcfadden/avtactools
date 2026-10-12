package app.ezpztac.data

import app.ezpztac.model.PointSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets of local points brought in for **this session only** (the import review's *This session*, docs/native-design: ImportReview): shown on the map and
 * used for a route point's name like any set, but held in memory, never saved, never synced, and gone at sign-out ([clear]) or when the system ends the app.
 * *Save to Library* makes one a saved set ([PointSetRepository.save]). Bounded, because a set can be thousands of points: at most [MAX] at a time.
 */
@Singleton
class SessionPointSets @Inject constructor() {
    private val _sets = MutableStateFlow<List<PointSet>>(emptyList())

    /** The sets, in the order they were brought in. */
    val sets: StateFlow<List<PointSet>> = _sets.asStateFlow()

    /** Holds [set] for this session. False when [MAX] are held already. */
    @Synchronized
    fun add(set: PointSet): Boolean {
        if (_sets.value.size >= MAX) return false
        _sets.value = _sets.value + set
        return true
    }

    /** Renames a set held here. False when there is none, or [name] is blank. */
    @Synchronized
    fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _sets.value.none { it.id == id }) return false
        _sets.value = _sets.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        return true
    }

    /** Lets go of a set (removed, or saved to the Library), and says what it was. */
    @Synchronized
    fun remove(id: String): PointSet? {
        val set = _sets.value.firstOrNull { it.id == id } ?: return null
        _sets.value = _sets.value - set
        return set
    }

    /** At sign-out: what one person brought in for their session is not shown to the next. */
    @Synchronized
    fun clear() {
        _sets.value = emptyList()
    }

    /** Whether [id] is a set held here, not a saved one. */
    fun holds(id: String): Boolean = _sets.value.any { it.id == id }

    companion object {
        /** How many sets a session holds at once. */
        const val MAX = 10

        /** Why a set was not held: said to the person in the review. */
        const val FULL = "This session already holds $MAX sets of points. Save one to your Library or remove one first."
    }
}
