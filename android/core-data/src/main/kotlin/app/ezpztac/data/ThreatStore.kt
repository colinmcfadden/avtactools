package app.ezpztac.data

import app.ezpztac.model.StoredThreat
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import app.ezpztac.model.ThreatIds
import app.ezpztac.model.ThreatSetDocument
import app.ezpztac.model.ThreatSets
import app.ezpztac.sync.LocalRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/** The threats a device is holding, and when they were last changed: what an account that saves nothing keeps between launches. */
@Serializable
public data class ThreatPicture(val savedAtMillis: Long, val entries: List<ThreatEntry>)

/**
 * Where the threat picture is kept for an account that cannot save threats (`threats` or `cloud_save` turned off), and where an installed app's picture was kept before threats
 * were saved. One sealed file on the device, never backed up, never sent anywhere; [ThreatStore] decides what is in it, and wipes it at sign-out.
 */
public interface ThreatVault {
    /** What is kept, or null for nothing, or for what cannot be read (which is then removed). */
    public fun load(): ThreatPicture?

    /** Replaces what is kept, whole. Throws when it cannot. */
    public fun save(picture: ThreatPicture)

    /** Removes everything kept. Best effort and never throws: a copy that cannot be removed is overwritten with nothing. */
    public fun wipe()
}

/** How an open set of threats stands against the Library. */
public enum class ThreatSetStanding {
    /** The Library has what is on the map. */
    SAVED,

    /** A change is waiting for the pause to end, or being written. */
    SAVING,

    /** A change could not be written. It is held here and tried again at the next change, or when the app goes to the back. */
    NOT_SAVED,
}

/** A set of threats open on the map. */
public data class ThreatSetView(
    /** The record's identity. */
    val id: String,
    val name: String,
    val entries: List<ThreatEntry>,
    val standing: ThreatSetStanding,
)

/**
 * The threats a crew is planning against: what the screens and the map read, in memory, and where it is kept.
 *
 * **For an account that can save them** ([attach] with `saves`), they are named sets in the person's Library, as local points and routes are (`AGENTS.md` §2, the owner's decision
 * of 2026-10-12): every set open is drawn, a change is saved by itself a moment later and synced like any record, and sign-out leaves them there for the next sign-in. Which are open
 * is kept apart ([OpenThreatSets]). A document another device changed is laid over this one's threat by threat ([ThreatSets.merge]), never in place of it.
 *
 * **For an account that cannot**, there are no sets and no records: the picture lives in one sealed file on the device, as it always did, and is wiped at sign-out. An installed app's
 * picture from before threats were saved becomes a set of that account's at the first [attach].
 *
 * Things that are easy to get wrong:
 * - **A write that is still queued must not bring back what was wiped.** A queued write saves what the picture is *when it runs*, under the lock a wipe takes, so a sign-out cannot
 *   be undone by a save that was already on its way; and an empty picture is removed, not saved.
 * - **A file that cannot be written costs the next launch its picture, not this session its threats**: it is caught, never thrown at whoever edited. So is a record that cannot be
 *   written: the change stays owed.
 * - **A write and the note of what it wrote go together** ([NonCancellable]): cut off between them, the next save would take its own write for someone else's change.
 * - **A set not yet on the device's disk, and with nothing in it, is never saved**, so looking at the panel leaves no empty item in the Library.
 */
public class ThreatStore(
    private val vault: ThreatVault,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val library: ThreatSetRepository? = null,
    private val openSets: OpenThreatSets? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Held(
        val id: String,
        val name: String,
        val threats: List<StoredThreat> = emptyList(),
        val unreadable: List<JsonElement> = emptyList(),
        val version: JsonElement = JsonPrimitive(ThreatSets.DOCUMENT_VERSION),
        val extras: JsonObject = JsonObject(emptyMap()),
        /** What the record says, as last read or written; null for a set not saved yet. */
        val savedDoc: JsonObject? = null,
        val savedName: String? = null,
        val owed: Boolean = false,
        val failed: Boolean = false,
        /** Counts the changes made, so a save can tell one made while it wrote. */
        val changes: Long = 0,
    ) {
        fun changed(how: (Held) -> Held): Held = how(this).copy(owed = true, changes = changes + 1)

        val hasContent: Boolean get() = threats.isNotEmpty() || unreadable.isNotEmpty()

        val document: JsonObject get() = ThreatSets.write(ThreatSetDocument(threats, unreadable, version, extras))
    }

    private val lock = ReentrantLock()

    /** The sets, oldest first: for an account that saves nothing, one with no name. Changed under [lock], and published by [publish]. */
    private var held: List<Held> = emptyList()

    /** Whose sets [held] is, or null while the picture is the device's alone. */
    private var account: String? = null
    private var active: String? = null
    private var lastChangeAt: Long = 0
    private val saveJobs = HashMap<String, Job>()

    /** Every write to or read of a record, one at a time, and the note of what it was. */
    private val saving = Mutex()
    private val attaching = Mutex()
    private var follower: Job? = null
    private val offeredCopies = HashSet<String>()

    private val _entries = MutableStateFlow<List<ThreatEntry>>(emptyList())

    /** The threats of every open set, each set's in the order they were made, the sets in the order they were opened. */
    public val entries: StateFlow<List<ThreatEntry>> = _entries.asStateFlow()

    private val _sets = MutableStateFlow<List<ThreatSetView>>(emptyList())

    /** The sets open now. Empty for an account that cannot save threats, whose picture has no sets. */
    public val sets: StateFlow<List<ThreatSetView>> = _sets.asStateFlow()

    private val _activeSet = MutableStateFlow<String?>(null)

    /** The set a new threat goes into: the one chosen, else the last opened. */
    public val activeSet: StateFlow<String?> = _activeSet.asStateFlow()

    private val _masks = MutableStateFlow<Map<String, JsonObject>>(emptyMap())

    /** The terrain masks held with the threats, whole, by the threat's id. One may be out of date; [ThreatSets.maskFits] says so. */
    public val masks: StateFlow<Map<String, JsonObject>> = _masks.asStateFlow()

    private val _wipes = MutableStateFlow(0)

    /** How many times the picture has been emptied ([wipe], [removeAll], a sign-out): what was being done to it (a form, a held threat) is to be let go. */
    public val wipes: StateFlow<Int> = _wipes.asStateFlow()

    private val _saves = MutableStateFlow(false)

    /** Whether the threats are this account's saved sets, rather than the device's own picture. */
    public val saves: StateFlow<Boolean> = _saves.asStateFlow()

    private val openIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        val kept = try {
            vault.load()
        } catch (_: Exception) {
            null
        }
        if (kept != null) {
            lastChangeAt = kept.savedAtMillis
            held = listOf(Held(DEVICE, ThreatSets.FIRST_SET_NAME, kept.entries.map { StoredThreat(it) }))
            publish()
        }
    }

    private fun publish() {
        val now = held
        _entries.value = now.flatMap { set -> set.threats.map { it.entry } }
        _sets.value = if (account == null) {
            emptyList()
        } else {
            now.map { set ->
                val standing = when {
                    set.failed -> ThreatSetStanding.NOT_SAVED
                    set.owed -> ThreatSetStanding.SAVING
                    else -> ThreatSetStanding.SAVED
                }
                ThreatSetView(set.id, set.name, set.threats.map { it.entry }, standing)
            }
        }
        _masks.value = now.asSequence().flatMap { it.threats.asSequence() }.mapNotNull { t -> t.mask?.let { t.entry.id to it } }.toMap()
        _activeSet.value = active?.takeIf { id -> now.any { it.id == id } } ?: now.lastOrNull()?.id
        openIds.value = now.mapTo(HashSet()) { it.id }
        _saves.value = account != null
    }

    private fun takenIds(): HashSet<String> = held.flatMapTo(HashSet()) { set -> set.threats.map { it.entry.id } }

    // -- What is done to the threats ---------------------------------------------------------------------------------

    /** Makes a threat of [threat], at the end of the set new threats go into, and says what it was called. */
    public fun add(threat: Threat): String = addAll(listOf(threat)).single()

    /** Makes a threat of each, in order, as one change, and says what each was called. With no set open, one is made. */
    public fun addAll(threats: List<Threat>): List<String> {
        if (threats.isEmpty()) return emptyList()
        val now = clock()
        val ids = ArrayList<String>(threats.size)
        val into = lock.withLock {
            val taken = takenIds()
            val made = threats.map { t -> StoredThreat(ThreatEntry(ThreatIds.next(taken, now).also { id -> taken.add(id); ids.add(id) }, t)) }
            val target = _activeSet.value?.takeIf { id -> held.any { it.id == id } } ?: held.lastOrNull()?.id
            if (target == null) {
                val id = if (account == null) DEVICE else UUID.randomUUID().toString()
                held = held + Held(id, ThreatSets.FIRST_SET_NAME, made, owed = true, changes = 1)
                if (account != null) openSets?.add(id)
                lastChangeAt = now
                publish()
                id
            } else {
                held = held.map { set -> if (set.id == target) set.changed { it.copy(threats = it.threats + made) } else set }
                lastChangeAt = now
                publish()
                target
            }
        }
        written(into)
        return ids
    }

    /** Brings a file's threats in as a set of their own called [name]. For an account that saves nothing there are no sets, and they join the picture. */
    public fun addAsSet(threats: List<Threat>, name: String): List<String> {
        if (threats.isEmpty()) return emptyList()
        val now = clock()
        val ids = ArrayList<String>(threats.size)
        val id = lock.withLock {
            if (account == null) return addAll(threats)
            val taken = takenIds()
            val made = threats.map { t -> StoredThreat(ThreatEntry(ThreatIds.next(taken, now).also { id -> taken.add(id); ids.add(id) }, t)) }
            val fresh = UUID.randomUUID().toString()
            held = held + Held(fresh, ThreatSets.cleanName(name).ifEmpty { ThreatSets.FIRST_SET_NAME }, made, owed = true, changes = 1)
            openSets?.add(fresh)
            lastChangeAt = now
            publish()
            fresh
        }
        written(id)
        return ids
    }

    /** Puts [threat] in place of the one called [id], keeping whether it is shown. False if there is no such threat. */
    public fun replace(id: String, threat: Threat): Boolean = edit(id) { it.copy(entry = it.entry.copy(threat = threat)) }

    /** Moves a threat. False if there is no such threat. */
    public fun move(id: String, lat: Double, lon: Double): Boolean = edit(id) { it.moved(lat, lon) }

    private fun StoredThreat.moved(lat: Double, lon: Double) = copy(entry = entry.copy(threat = entry.threat.copy(lat = lat, lon = lon)))

    /**
     * Moves a threat as a finger drags it: shown at once but not yet written, since what it is written to is rewritten whole for every change and would be written dozens of times a
     * second. [settle] writes it when the finger lifts.
     */
    public fun previewMove(id: String, lat: Double, lon: Double): Boolean = edit(id, writes = false) { it.moved(lat, lon) }

    /** Writes the picture as it is now, after a drag ([previewMove]). */
    public fun settle() {
        val sets = lock.withLock {
            lastChangeAt = clock()
            if (account == null) listOf(DEVICE) else held.filter { it.owed }.map { it.id }
        }
        sets.forEach(::written)
    }

    /** Shows or hides a threat on the map. False if there is no such threat. */
    public fun setVisible(id: String, visible: Boolean): Boolean = edit(id) { it.copy(entry = it.entry.copy(visible = visible)) }

    /** Removes a threat. False if there is no such threat. */
    public fun remove(id: String): Boolean {
        val owner = lock.withLock {
            val set = held.firstOrNull { s -> s.threats.any { it.entry.id == id } } ?: return false
            held = held.map { s -> if (s === set) s.changed { c -> c.copy(threats = c.threats.filterNot { it.entry.id == id }) } else s }
            lastChangeAt = clock()
            publish()
            set.id
        }
        written(owner)
        return true
    }

    /** Keeps [mask] with a threat, in the saved form the web keeps it. False if there is no such threat. */
    public fun setMask(id: String, mask: JsonObject): Boolean = edit(id, device = false) { it.copy(mask = mask) }

    /** Lets a threat's mask go. False if it has none to let go. */
    public fun clearMask(id: String): Boolean {
        if (lock.withLock { held.none { s -> s.threats.any { it.entry.id == id && it.mask != null } } }) return false
        return edit(id, device = false) { it.copy(mask = null) }
    }

    /** [device] false: nothing in the device's sealed file changes, since it holds no masks. */
    private fun edit(id: String, writes: Boolean = true, device: Boolean = true, how: (StoredThreat) -> StoredThreat): Boolean {
        val owner = lock.withLock {
            val set = held.firstOrNull { s -> s.threats.any { it.entry.id == id } } ?: return false
            held = held.map { s -> if (s === set) s.changed { c -> c.copy(threats = c.threats.map { if (it.entry.id == id) how(it) else it }) } else s }
            if (device) lastChangeAt = clock()
            publish()
            set.id
        }
        if (writes && (device || lock.withLock { account != null })) written(owner)
        return true
    }

    /** Empties every open set (the panel's *Remove all*): the sets stay, with nothing in them. For an account that saves nothing, the same as [wipe]. */
    public fun removeAll() {
        val ids = lock.withLock {
            if (account == null) {
                null
            } else {
                held = held.map { set -> set.changed { it.copy(threats = emptyList()) } }
                publish()
                _wipes.value += 1
                held.map { it.id }
            }
        }
        if (ids == null) wipe() else ids.forEach(::written)
    }

    /** Forgets every threat in memory and in the sealed file. What is in the Library stays. Never throws. */
    public fun wipe() {
        lock.withLock {
            held = emptyList()
            active = null
            lastChangeAt = 0
            saveJobs.values.forEach { it.cancel() }
            saveJobs.clear()
            publish()
            try {
                vault.wipe()
            } catch (_: Exception) {
                // The vault's contract is that it does not throw; a vault that does is not allowed to stop the memory having been cleared.
            }
            _wipes.value += 1
        }
    }

    // -- The sets ----------------------------------------------------------------------------------------------------

    /** Chooses the set new threats go into. */
    public fun setActive(id: String) {
        lock.withLock {
            if (held.none { it.id == id }) return
            active = id
            publish()
        }
    }

    /** Names a set, in capitals, as the web does. False for a name with nothing in it, or a set that is not open. */
    public fun rename(id: String, name: String): Boolean {
        val clean = ThreatSets.cleanName(name)
        if (clean.isEmpty()) return false
        lock.withLock {
            if (held.none { it.id == id }) return false
            held = held.map { set -> if (set.id == id) set.changed { it.copy(name = clean) } else set }
            publish()
        }
        written(id)
        return true
    }

    /** Names a saved set, open or not. False for a name with nothing in it, or a set that is no longer there. */
    public suspend fun renameSaved(uuid: String, name: String): Boolean {
        val clean = ThreatSets.cleanName(name)
        if (clean.isEmpty()) return false
        if (current(uuid) != null) return rename(uuid, clean)
        val repo = library ?: return false
        return saving.withLock {
            if (repo.record(uuid) == null) return@withLock false
            repo.rename(uuid, clean)
            true
        }
    }

    /** Opens a saved set beside those open. False when it is not in the Library. A set open already stays as it is. */
    public suspend fun open(uuid: String): Boolean {
        val repo = library ?: return false
        return saving.withLock {
            if (lock.withLock { account == null }) return@withLock false
            if (lock.withLock { held.any { it.id == uuid } }) return@withLock true
            val record = repo.record(uuid) ?: return@withLock false
            take(record)
            true
        }
    }

    private fun take(record: LocalRecord) {
        lock.withLock {
            held = held + heldOf(record, takenIds())
            publish()
        }
        openSets?.add(record.uuid)
    }

    private fun heldOf(record: LocalRecord, used: Set<String>): Held {
        val read = ThreatSets.read(record.data, used, prefixOf(record.uuid))
        return Held(record.uuid, record.name, read.threats, read.unreadable, read.version, read.extras, savedDoc = record.data, savedName = record.name)
    }

    private fun prefixOf(uuid: String) = "threat-${uuid.take(8)}"

    /** Takes a set off the map, once what was changed is saved. False when a change could not be saved, and the set stays open. */
    public suspend fun close(id: String): Boolean {
        save(id)
        lock.withLock {
            val set = held.firstOrNull { it.id == id } ?: return true
            if (set.owed && (set.hasContent || set.savedDoc != null)) return false
            saveJobs.remove(id)?.cancel()
            held = held.filterNot { it.id == id }
            publish()
        }
        openSets?.remove(id)
        return true
    }

    /** Deletes a saved set. One open comes off the map, and nothing more of it is saved. */
    public suspend fun delete(uuid: String) {
        val repo = library ?: return
        saving.withLock {
            forget(uuid)
            repo.delete(uuid)
        }
    }

    private fun forget(id: String) {
        lock.withLock {
            saveJobs.remove(id)?.cancel()
            held = held.filterNot { it.id == id }
            publish()
        }
        openSets?.remove(id)
    }

    // -- Being one account's ------------------------------------------------------------------------------------------

    /**
     * The threats are now this account's. With [saves] (the account has `threats` and `cloud_save`), its open sets come back, and a picture left on the device from before threats were
     * saved becomes a set in its Library. Without it, the picture stays the device's alone. Asked again for the same account, it does nothing.
     */
    public suspend fun attach(account: String, saves: Boolean) {
        val repo = library ?: return
        val open = openSets ?: return
        attaching.withLock {
            if (!saves) {
                detach()
                return
            }
            if (lock.withLock { this.account } == account) return
            detach()
            saving.withLock {
                val carried = carryOver(repo, open, account)
                val opened = ArrayList<Held>()
                val used = HashSet<String>()
                for (id in open.ids.value) {
                    val record = repo.record(id)
                    if (record == null) {
                        open.remove(id) // deleted, or another account's: an id names nothing else
                    } else {
                        val set = heldOf(record, used)
                        used += set.threats.map { it.entry.id }
                        opened += set
                    }
                }
                lock.withLock {
                    held = opened
                    this.account = account
                    active = null
                    publish()
                }
                if (carried) repo.requestSync()
            }
            follower = scope.launch(io) { follow(repo) }
        }
    }

    /** Makes the device's picture a set of [account]'s. Named by what is in it, so a crash before the file is wiped cannot make a second. */
    private suspend fun carryOver(repo: ThreatSetRepository, open: OpenThreatSets, account: String): Boolean {
        val threats = lock.withLock { held.flatMap { it.threats } }
        if (threats.isEmpty()) return false
        val document = ThreatSets.write(ThreatSetDocument(threats))
        val uuid = UUID.nameUUIDFromBytes("threats:carry:$account:$document".toByteArray()).toString()
        if (repo.record(uuid) == null) repo.create(uuid, repo.freeName(ThreatSets.FIRST_SET_NAME), document)
        open.add(uuid)
        lock.withLock {
            held = emptyList()
            try {
                vault.wipe()
            } catch (_: Exception) {
                // See [wipe].
            }
        }
        return true
    }

    private suspend fun detach() {
        if (lock.withLock { account } == null) return
        flush()
        follower?.cancel()
        follower = null
        lock.withLock {
            account = null
            held = emptyList()
            active = null
            publish()
            _wipes.value += 1
        }
    }

    /** Signing out: what was changed is saved first, then memory and the sealed file are cleared. The Library's sets stay for the next sign-in. */
    public suspend fun signOut() {
        attaching.withLock {
            flush()
            follower?.cancel()
            follower = null
            lock.withLock { account = null }
            offeredCopies.clear()
            wipe()
        }
    }

    // -- Writing, and following what others write -------------------------------------------------------------------

    private fun written(id: String) {
        if (lock.withLock { account == null }) {
            scope.launch(io) { writeVault() }
            return
        }
        lock.withLock {
            saveJobs.remove(id)?.cancel()
            saveJobs[id] = scope.launch(io) {
                delay(SAVE_PAUSE_MS)
                withContext(NonCancellable) { save(id) }
            }
        }
    }

    private fun writeVault() {
        lock.withLock {
            if (account != null) return
            val now = _entries.value
            try {
                if (now.isEmpty()) vault.wipe() else vault.save(ThreatPicture(lastChangeAt, now))
            } catch (_: Exception) {
                // See the class: a file that cannot be written costs the next launch its picture.
            }
        }
    }

    /** Writes every change waiting, now: the app is going to the back, or the person is signing out. */
    public suspend fun flush() {
        val owed = lock.withLock {
            if (account == null) return
            saveJobs.values.forEach { it.cancel() }
            saveJobs.clear()
            held.filter { it.owed }.map { it.id }
        }
        withContext(NonCancellable) { owed.forEach { save(it) } }
    }

    private fun current(id: String): Held? = lock.withLock { held.firstOrNull { it.id == id } }

    private suspend fun save(id: String) {
        val repo = library ?: return
        saving.withLock {
            val first = current(id) ?: return
            if (!first.owed || lock.withLock { account == null }) return
            if (first.savedDoc == null && !first.hasContent) {
                note(id) { it.copy(owed = false, failed = false) }
                return
            }
            try {
                write(repo, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                note(id) { it.copy(failed = true) }
            }
        }
    }

    private suspend fun write(repo: ThreatSetRepository, id: String) {
        var workingId = id
        val before = current(id) ?: return
        if (before.savedDoc != null) {
            val record = repo.record(id)
            if (record == null) {
                // Deleted elsewhere while this was being changed: the work is kept, under an identity of its own.
                workingId = UUID.randomUUID().toString()
                adopt(id, workingId) { it.copy(savedDoc = null, savedName = null) }
            } else if (differs(record, before)) {
                layOver(id, record)
            }
        }
        val set = current(workingId) ?: return
        val document = set.document
        val named: String
        if (set.savedDoc == null) {
            named = repo.freeName(set.name)
            repo.create(workingId, named, document)
        } else {
            named = set.name
            val sameName = set.name == set.savedName
            if (!sameName || !ThreatSets.sameValue(document, set.savedDoc)) repo.edit(workingId, set.name.takeUnless { sameName }, document)
        }
        val changesWritten = set.changes
        note(workingId) { now ->
            val still = now.changes != changesWritten
            now.copy(name = if (now.name == set.name) named else now.name, savedDoc = document, savedName = named, owed = still, failed = false)
        }
        repo.requestSync()
        if (current(workingId)?.owed == true) written(workingId)
    }

    private fun note(id: String, how: (Held) -> Held) {
        lock.withLock {
            held = held.map { if (it.id == id) how(it) else it }
            publish()
        }
    }

    private fun adopt(from: String, to: String, how: (Held) -> Held = { it }) {
        lock.withLock {
            saveJobs.remove(from)?.cancel()
            if (active == from) active = to
            held = held.map { if (it.id == from) how(it).copy(id = to) else it }
            publish()
        }
        openSets?.remove(from)
        openSets?.add(to)
    }

    private fun differs(record: LocalRecord, set: Held): Boolean = record.name != set.savedName || !ThreatSets.sameValue(record.data, set.savedDoc)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun follow(repo: ThreatSetRepository) {
        openIds.flatMapLatest { ids -> if (ids.isEmpty()) emptyFlow() else repo.observe(ids) }.collect { records ->
            try {
                saving.withLock { followed(repo, records) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A read that failed is tried again at the next change; nothing was altered.
            }
        }
    }

    private suspend fun followed(repo: ThreatSetRepository, records: List<LocalRecord>) {
        for (set in lock.withLock { held }) {
            if (set.savedDoc == null) continue
            val listed = records.firstOrNull { it.uuid == set.id }
            if (listed != null && !differs(listed, set)) continue
            // What the feed says may be behind what this device wrote a moment ago, and laid over the threats it would take them back: asked again first.
            val record = repo.record(set.id)
            when {
                record == null -> gone(repo, set)
                differs(record, set) -> {
                    layOver(set.id, record)
                    offerCopy(repo, set.id)
                }
            }
        }
    }

    /** Someone else's version laid over this device's: what they changed comes in, what was changed here and not yet written stays. */
    private fun layOver(id: String, record: LocalRecord) {
        lock.withLock {
            val set = held.firstOrNull { it.id == id } ?: return
            val base = set.savedDoc ?: return
            val used = held.filterNot { it.id == id }.flatMapTo(HashSet()) { s -> s.threats.map { it.entry.id } }
            val theirs = ThreatSets.read(record.data, used, prefixOf(id))
            val merged = ThreatSets.merge(ThreatSets.read(base, used, prefixOf(id)).threats, set.threats, theirs.threats)
            held = held.map {
                if (it.id == id) {
                    it.copy(
                        threats = merged, unreadable = theirs.unreadable, version = theirs.version, extras = theirs.extras, savedDoc = record.data, savedName = record.name,
                        name = if (it.name == it.savedName) record.name else it.name,
                    )
                } else {
                    it
                }
            }
            publish()
        }
    }

    /** After a conflict the record is the server's, and this device's version is a copy beside it: shown too, so nothing seems to have vanished. Once. */
    private suspend fun offerCopy(repo: ThreatSetRepository, id: String) {
        val records = repo.records()
        val copy = records.firstOrNull { it.conflictOf == id && it.uuid !in offeredCopies && current(it.uuid) == null } ?: return
        offeredCopies += copy.uuid
        take(copy)
    }

    /** A record the feed no longer lists: gone for good, moved to a new identity by the sync engine, or the feed a step behind what was just opened. */
    private suspend fun gone(repo: ThreatSetRepository, set: Held) {
        val twin = repo.records().firstOrNull {
            it.conflictOf == null && it.name == set.savedName && current(it.uuid) == null && ThreatSets.sameValue(it.data, set.savedDoc)
        }
        when {
            twin != null -> adopt(set.id, twin.uuid)
            set.owed -> Unit // the next save keeps the work
            else -> forget(set.id)
        }
    }

    public companion object {
        /** How long a change waits for the next before it is saved, as an LZ/PZ's does. */
        public const val SAVE_PAUSE_MS: Long = 600

        private const val DEVICE = "device"
    }
}
