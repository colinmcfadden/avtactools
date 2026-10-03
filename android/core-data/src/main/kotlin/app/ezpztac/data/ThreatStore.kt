package app.ezpztac.data

import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import app.ezpztac.model.ThreatIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/** The threats a device is holding, and when they were last changed: what is kept between launches. */
@Serializable
public data class ThreatPicture(val savedAtMillis: Long, val entries: List<ThreatEntry>)

/**
 * Where the threat picture is kept so that an app the system ended mid-plan does not lose it (`docs/NATIVE_APPS_PLAN.md`, "Threats on the device"). One
 * sealed file on the device, never backed up, never sent anywhere; [ThreatStore] decides what is in it and for how long.
 */
public interface ThreatVault {
    /** What is kept, or null for nothing, or for what cannot be read (which is then removed). */
    public fun load(): ThreatPicture?

    /** Replaces what is kept, whole. Throws when it cannot. */
    public fun save(picture: ThreatPicture)

    /** Removes everything kept. Best effort and never throws: a copy that cannot be removed is overwritten with nothing. */
    public fun wipe()
}

/**
 * The threats a crew is planning against. **Threats are never persisted** (`AGENTS.md` §2), so this is memory first: a list that screens and the map read.
 * The one exception the owner approved is that the list is also kept in one sealed file, so that a phone that ends a backgrounded app does not lose a
 * threat picture mid-planning; the file is never backed up, is wiped at sign-out ([wipe]) and is gone [RETAIN_MS] after the picture was last changed.
 *
 * Things that are easy to get wrong:
 * - **The 48 hours run from the last change**, not from the first threat: a picture someone is working in stays, a forgotten one goes. It is checked when
 *   the store is made (a launch) and whenever [expireIfOld] is called (the app coming to the front), because nothing runs while the app is not.
 * - **A write that is still queued must not bring back what was wiped.** A queued write saves what the picture is *when it runs*, under the lock a wipe
 *   takes, so a sign-out cannot be undone by a save that was already on its way; and an empty picture is removed, not saved.
 * - **A file that cannot be written costs the next launch its picture, not this session its threats**: it is caught, never thrown at whoever edited.
 * - Nothing here goes to a server, into the database or into the sync engine.
 */
public class ThreatStore(
    private val vault: ThreatVault,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private var lastChangeAt: Long = 0

    private val _entries = MutableStateFlow<List<ThreatEntry>>(emptyList())

    /** The threats, in the order they were made. */
    public val entries: StateFlow<List<ThreatEntry>> = _entries.asStateFlow()

    init {
        val kept = try {
            vault.load()
        } catch (_: Exception) {
            null
        }
        if (kept != null) {
            if (expired(kept.savedAtMillis)) {
                wipe()
            } else {
                lastChangeAt = kept.savedAtMillis
                _entries.value = kept.entries
            }
        }
    }

    /** Makes a threat of [threat], at the end of the list, and says what it was called. */
    public fun add(threat: Threat): String = addAll(listOf(threat)).single()

    /** Makes a threat of each, in order (an import), as one change, and says what each was called. */
    public fun addAll(threats: List<Threat>): List<String> {
        if (threats.isEmpty()) return emptyList()
        val now = clock()
        val ids = ArrayList<String>(threats.size)
        change {
            val taken = HashSet(it.map { e -> e.id })
            val made = threats.map { t -> ThreatIds.next(taken, now).also { id -> taken.add(id); ids.add(id) }.let { id -> ThreatEntry(id, t) } }
            it + made
        }
        return ids
    }

    /** Puts [threat] in place of the one called [id], keeping whether it is shown. False if there is no such threat. */
    public fun replace(id: String, threat: Threat): Boolean = edit(id) { it.copy(threat = threat) }

    /** Moves a threat. False if there is no such threat. */
    public fun move(id: String, lat: Double, lon: Double): Boolean = edit(id) { it.copy(threat = it.threat.copy(lat = lat, lon = lon)) }

    /** Shows or hides a threat on the map. False if there is no such threat. */
    public fun setVisible(id: String, visible: Boolean): Boolean = edit(id) { it.copy(visible = visible) }

    /** Removes a threat. False if there is no such threat. */
    public fun remove(id: String): Boolean {
        if (_entries.value.none { it.id == id }) return false
        change { list -> list.filterNot { it.id == id } }
        return true
    }

    /** Forgets every threat, here and in the file: at sign-out, where a crew's threats do not stay for the next person. Never throws. */
    public fun wipe() {
        lock.withLock {
            _entries.value = emptyList()
            lastChangeAt = 0
            try {
                vault.wipe()
            } catch (_: Exception) {
                // The vault's contract is that it does not throw; a vault that does is not allowed to stop the memory having been cleared.
            }
        }
    }

    /** Forgets the picture if it is [RETAIN_MS] old. True if it did. Call when the app comes to the front. */
    public fun expireIfOld(): Boolean {
        val stale = lock.withLock { _entries.value.isNotEmpty() && expired(lastChangeAt) }
        if (stale) wipe()
        return stale
    }

    private fun expired(changedAt: Long): Boolean = clock() - changedAt > RETAIN_MS

    private fun edit(id: String, how: (ThreatEntry) -> ThreatEntry): Boolean {
        if (_entries.value.none { it.id == id }) return false
        change { list -> list.map { if (it.id == id) how(it) else it } }
        return true
    }

    private fun change(how: (List<ThreatEntry>) -> List<ThreatEntry>) {
        lock.withLock {
            _entries.update(how)
            lastChangeAt = clock()
        }
        scope.launch(io) { write() }
    }

    private fun write() {
        lock.withLock {
            val now = _entries.value
            try {
                if (now.isEmpty()) vault.wipe() else vault.save(ThreatPicture(lastChangeAt, now))
            } catch (_: Exception) {
                // See the class: a file that cannot be written costs the next launch its picture.
            }
        }
    }

    public companion object {
        /** How long a picture is kept after it was last changed. */
        public const val RETAIN_MS: Long = 48 * 60 * 60 * 1000L
    }
}
