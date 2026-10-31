package app.ezpztac.data

import app.ezpztac.brief.BriefFormats
import app.ezpztac.missionpacks.PackDiff
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackSentences
import app.ezpztac.missionpacks.PackState
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.cutToCharacters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** The brief opened last on this device, for each account (the web's `ezpz.brief.<user>`): a Library record's uuid, on the device only, never another account's. */
interface LastBrief {
    fun id(user: Int): String?

    fun remember(user: Int, uuid: String?)
}

/** [LastBrief] in memory, for tests. */
class InMemoryLastBrief : LastBrief {
    private val kept = mutableMapOf<Int, String>()

    override fun id(user: Int): String? = kept[user]

    override fun remember(user: Int, uuid: String?) {
        if (uuid == null) kept.remove(user) else kept[user] = uuid
    }
}

/** How a brief kept in the Library stands, as the setup's foot says it. */
enum class BriefSave {
    /** A change waits to be written, or is being written. */
    SAVING,

    /** Written to the Library on this device; the sync takes it to the server. */
    SAVED,

    /** It could not be written: the answers are kept here and tried again with the next change. */
    FAILED,

    /** The account cannot keep records in its Library (cloud save is off for it): the answers last only while the app runs. */
    NOT_KEPT,
}

/**
 * The brief document as it stands: what the person has answered ([doc]; null before anything is), the pack it is kept in ([pack]; null: the Library), how its
 * saving stands ([save]; null in a pack, where a change goes to the pack at once, and before anything is answered), and whether it is still being read
 * ([loading]: the Library's record as the setup opens, or a pack the device does not hold yet), when nothing of it should be shown.
 */
data class BriefDocument(val doc: JsonObject?, val pack: String?, val save: BriefSave?, val loading: Boolean = false)

/**
 * Writes what of the brief waits now ([BriefKeeper.flush]): the shell calls it as the app goes to the back and before packs stop at a sign-out, as it writes the
 * open documents then, so a waiting change is written under its account and a pack's goes to the pack while it still takes it.
 */
fun interface BriefFlush {
    suspend fun flush()
}

/** A change to a brief: the document it makes of the one there is. Pure, so it can be laid again over a newer version of the brief. */
private typealias BriefChange = (JsonObject) -> JsonObject

/**
 * The brief being set up (docs/MISSION_BRIEF.md §3), the document half of the web's `useBrief`: the person's answers and where they are kept.
 *
 * A change is kept as what it does ([BriefChange]), not as the document it made, and laid over the brief as it is when it is written or sent, never as it was
 * when the person began: a version another device or member made meanwhile is kept, and only what this person changed goes over it. What the person sees in
 * the meantime is the brief with their changes laid over it, at once.
 *
 * - **In a pack** it is the pack's brief item, the first live one (made at the first change, as `br-<uuid>`), open to every member in every state of the pack:
 *   changes go as the pack's own operations (core-missionpacks' [PackDiff]), with their one sentence, in the order they were made, the pack sending edits to
 *   briefs in batches of their own. What the pack cannot take now (it is still opening, or paused for the account's sake) waits, shown, and goes when it can
 *   (owner decision 4, as the editors hold back a change); what it will never take (it closed, or is gone) is kept in the person's Library as "NAME (my
 *   edits)" and they are told. **Not like the web**, which drops it.
 * - **Outside a pack** it is a record in the person's Library ([RecordKind.BRIEF]), the one this device opened last for the account ([LastBrief]), read again
 *   each time the setup opens ([open]) so a version a sync brought is the one shown, made at the first save under an identity chosen here and written once
 *   the person has been still for [SAVE_DELAY_MS], or at [flush]. A record not on the device (deleted elsewhere, or not synced here yet) is started again
 *   under a new identity at the next save, as an LZ/PZ deleted elsewhere is kept as a new one.
 *
 * One account at a time ([use]): another account's brief is never opened, and a change waiting for the one before is written (or kept) first.
 */
class BriefKeeper(
    private val sync: SyncRepository,
    private val scheduler: SyncScheduler,
    private val engine: PackEngine,
    private val last: LastBrief,
    /** Where a delayed save and a pack's edits run: never cancelled with a screen. */
    private val scope: CoroutineScope,
    /** Whether the signed-in account may keep records in its Library (`cloud_save`): the server refuses a brief's create without it, and the outbox would hold it for good. */
    private val canKeep: () -> Boolean = { true },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * The Library's brief as this device holds it: the account it is for, the record kept for it ([uuid], remembered; null until the first save makes one,
     * and not always on the device), the record's document as last read or written here ([base]), the person's changes not written yet ([waiting], in order),
     * what the person sees ([doc]: [waiting] laid over [base]), how saving stands, and whether the record is being read.
     */
    private data class Library(
        val user: Int? = null,
        val uuid: String? = null,
        val base: JsonObject? = null,
        val waiting: List<BriefChange> = emptyList(),
        val doc: JsonObject? = null,
        val save: BriefSave? = null,
        val loading: Boolean = false,
    )

    /**
     * This person's changes to a pack's brief that the pack has not taken yet ([changes], in order), shown over its brief meanwhile and sent as one batch
     * once it can take them. [base] is the pack's brief as it was when they were last laid over it and [name] its name (or the one it is made with): what
     * is kept in the Library if the pack never takes them, when the account may keep records there ([keepable]). [told]: the person heard that they wait for
     * a pause; [failedAt]: how many of them the person heard could not be kept on the device, so each is said once.
     */
    private data class Held(
        val user: Int,
        val pack: String,
        val name: String,
        val base: JsonObject,
        val changes: List<BriefChange>,
        val keepable: Boolean,
        val told: Boolean = false,
        val failedAt: Int = 0,
    )

    /** The open pack as the brief reads it: whether the device holds it yet, its brief item, and the brief's name (the one a new brief is made with). */
    private class PackBrief(val uuid: String, val holding: Boolean, val item: PackItemView?, val name: String)

    private val library = MutableStateFlow(Library())
    private val held = MutableStateFlow<Held?>(null)
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val lock = Mutex()                 // the Library's reads and writes, in order
    private val packEdits = Mutex()            // what is sent to a pack, one batch at a time
    private val timer = Any()
    private var saveJob: Job? = null           // under [timer]

    /** What the person is told about a change to a pack's brief: that it waits, that it was kept in the Library, that it could not be kept. */
    val refusals: SharedFlow<String> = notices.asSharedFlow()

    // A pack's state is published for every presence frame, many a second (AGENTS.md §14): narrowed to what the brief reads before anything is worked out.
    private val packBrief: Flow<PackBrief?> = engine.open.map { state -> state?.let(::packBriefOf) }.distinctUntilChanged(::sameBrief)

    /** The brief as it stands, following the open pack (its brief item, with this person's changes it has not taken laid over it) and the Library's. */
    val document: Flow<BriefDocument> = combine(packBrief, library, held) { pack, lib, mine -> documentOf(pack, lib, mine) }.distinctUntilChanged()

    init {
        // What waits for a pack goes when the pack can take it (the device holds it, it is no longer paused), and is kept in the Library if it closes first.
        scope.launch {
            engine.open.map { state -> state?.let { Triple(it.uuid, it.session != null, it.status) } }
                .distinctUntilChanged()
                .collect { if (held.value != null) pump() }
        }
    }

    /** The brief as it stands now, for the open pack [state] (null: the Library). */
    fun documentNow(state: PackState? = engine.open.value): BriefDocument = documentOf(state?.let(::packBriefOf), library.value, held.value)

    private fun documentOf(pack: PackBrief?, lib: Library, mine: Held?): BriefDocument {
        if (pack == null) return BriefDocument(lib.doc, null, lib.save, lib.loading)
        // The device does not hold the pack yet (its client opening, or replaced after a pause): nothing is shown, rather than a brief with nothing in it.
        if (!pack.holding) return BriefDocument(null, pack.uuid, null, loading = true)
        val item = pack.item?.data as? JsonObject
        val changes = mine?.takeIf { it.pack == pack.uuid }?.changes
        return BriefDocument(if (changes.isNullOrEmpty()) item else laid(changes, item ?: newDocument()), pack.uuid, null)
    }

    private fun packBriefOf(state: PackState): PackBrief {
        val item = briefItemOf(state)
        val packName = (state.session?.pack?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "PACK"
        return PackBrief(state.uuid, state.session != null, item, item?.name ?: cutToCharacters("$packName BRIEF", MAX_NAME))
    }

    private fun sameBrief(a: PackBrief?, b: PackBrief?): Boolean {
        if (a == null || b == null) return a === b
        return a.uuid == b.uuid && a.holding == b.holding && a.name == b.name && a.item?.uuid == b.item?.uuid && a.item?.data === b.item?.data
    }

    /**
     * Whose brief is kept ([user]; null for nobody: signed out, or Mission briefs off). A change still waiting for the account before is written first, under
     * that account, as the shell writes the open documents before a sign-out, and what a pack has not taken of theirs is kept in their Library; then the
     * brief this device opened last for the new account is read.
     */
    suspend fun use(user: Int?) {
        if (library.value.user == user) return
        flush()
        held.value?.let { keep(it) }
        lock.withLock { library.value = Library(user = user, uuid = user?.let(last::id), loading = user != null) }
        if (user != null) load()
    }

    /**
     * The setup opening: the Library's brief is read again, so a version a sync brought since (another device's change, a conflict settled) is the one shown,
     * with any change of the person's still waiting laid over it. Loading until it is read.
     */
    fun open() {
        val user = library.value.user ?: return
        library.update { if (it.user == user) it.copy(loading = true) else it }
        scope.launch { load() }
    }

    private suspend fun load() = lock.withLock {
        val user = library.value.user ?: return@withLock
        val uuid = library.value.uuid ?: last.id(user)
        val record = try {
            uuid?.let { sync.record(RecordKind.BRIEF, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // It could not be read: what is shown stays, and the next save is laid over the record as it is then.
            library.update { if (it.user == user) it.copy(loading = false) else it }
            return@withLock
        }
        library.update { now ->
            if (now.user != user) return@update now
            // Not on the device (deleted elsewhere, or not synced here yet): a new brief, kept under a new identity at the next save; the one remembered is
            // still asked for next time, as a sync may bring it.
            val base = record?.data
            now.copy(
                uuid = uuid,
                base = base,
                doc = if (now.waiting.isEmpty()) base else laid(now.waiting, base ?: newDocument()),
                save = if (now.waiting.isNotEmpty()) now.save else base?.let { BriefSave.SAVED },
                loading = false,
            )
        }
    }

    /** Changes the brief where it is kept now: the open pack's, else the Library's ([change]). Called on the main thread, where the screens make their changes. */
    fun change(transform: (JsonObject) -> JsonObject) = change(engine.open.value, transform)

    /**
     * Changes the brief: [transform] of the document as it is shown (a new one of the default format before anything is answered); a transform that changes
     * nothing is no change. In the open pack [state] it is sent at once; outside one (null) it is written once the person has been still for [SAVE_DELAY_MS].
     */
    fun change(state: PackState?, transform: (JsonObject) -> JsonObject) {
        if (state != null) return changeInPack(state, transform)
        var save = false
        library.update { lib ->
            save = false
            if (lib.user == null) return@update lib
            val current = lib.doc ?: newDocument()
            val value = applied(transform, current) ?: return@update lib
            if (value == current) return@update lib
            save = true
            lib.copy(doc = value, waiting = lib.waiting + transform, save = if (canKeep()) BriefSave.SAVING else BriefSave.NOT_KEPT)
        }
        if (save) {
            synchronized(timer) {
                saveJob?.cancel()
                saveJob = scope.launch {
                    delay(SAVE_DELAY_MS)
                    saveNow()
                }
            }
        }
    }

    private fun changeInPack(state: PackState, transform: BriefChange) {
        val user = library.value.user ?: return
        val pack = packBriefOf(state)
        val shown = documentOf(pack, library.value, held.value).doc ?: newDocument()
        val value = applied(transform, shown) ?: return
        if (value == shown) return
        val base = pack.item?.data as? JsonObject ?: newDocument()
        val keepable = canKeep()
        var other: Held? = null
        held.update { now ->
            val mine = now?.takeIf { it.pack == pack.uuid && it.user == user }
            other = now?.takeIf { mine == null }
            Held(user, pack.uuid, pack.name, base, mine?.changes.orEmpty() + transform, keepable, mine?.told ?: false, mine?.failedAt ?: 0)
        }
        // Another pack's that was still waiting (it closed as this one opened): kept, never sent here.
        other?.let { scope.launch { keep(it) } }
        scope.launch { pump() }
    }

    // Sends what waits for the open pack, in the order it was made, as one batch worked out against the pack's brief as it is now; again while more came.
    private suspend fun pump() = packEdits.withLock {
        try {
            pumpLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notices.tryEmit(NOT_ON_DEVICE)
        }
    }

    private suspend fun pumpLocked() {
        while (true) {
            val h = held.value ?: return
            val state = engine.open.value
            if (state == null || state.uuid != h.pack) return keep(h)                // closed, or another opened: it can go nowhere now
            if (state.session == null) return                                          // still opening: sent once the device holds it
            val item = briefItemOf(state)
            val base = item?.data as? JsonObject ?: newDocument()
            val target = laid(h.changes, base)
            val ops = if (target == base) emptyList() else opsFor(state, item, target)
            // A change the pack's rules call no change (null for missing, 1 for 1.0) is none, and nothing is sent: a read-only pack refuses an empty batch.
            val refused = if (ops.isEmpty()) {
                null
            } else {
                try {
                    engine.edit(state.uuid, ops)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    FAILED_HERE                                                         // not on the device (the disk full): tried again with the next change
                }
            }
            when (refused) {
                null -> held.update { now ->
                    when {
                        now == null || now.pack != h.pack || now.user != h.user -> now
                        now.changes.size <= h.changes.size -> null
                        else -> now.copy(changes = now.changes.drop(h.changes.size), base = target, failedAt = maxOf(0, now.failedAt - h.changes.size))
                    }
                }
                "loading", "paused", FAILED_HERE -> {
                    val failed = refused == FAILED_HERE
                    held.update { now ->
                        if (now != null && now.pack == h.pack) {
                            now.copy(base = base, told = now.told || refused == "paused", failedAt = if (failed) maxOf(now.failedAt, h.changes.size) else now.failedAt)
                        } else {
                            now
                        }
                    }
                    if (refused == "paused" && !h.told) notices.tryEmit(PAUSED_WAITS)
                    if (failed && h.changes.size > h.failedAt) notices.tryEmit(NOT_ON_DEVICE)              // each change said once, however often it is tried
                    return
                }
                else -> return keep(h)                                                 // gone, or closed meanwhile: it never will
            }
        }
    }

    /** What a pack will never take ([h]'s changes, over its brief as it last was), kept in the Library as "NAME (my edits)", and the person told. */
    private suspend fun keep(h: Held) {
        var taken: Held? = null
        held.update { now ->
            taken = now?.takeIf { it.pack == h.pack && it.user == h.user }
            if (taken != null) null else now
        }
        val mine = taken ?: return
        val name = PackSentences.myEditsName(mine.name)
        val kept = mine.keepable && withContext(NonCancellable) {
            try {
                sync.create(RecordKind.BRIEF, name, laid(mine.changes, mine.base), uuid = newId())
                scheduler.requestSync()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        notices.tryEmit(if (kept) keptWords(name) else NOT_TAKEN)
    }

    private fun opsFor(state: PackState, item: PackItemView?, target: JsonObject): List<JsonObject> {
        val actor = engine.me.value?.name?.takeIf { it.isNotEmpty() } ?: "Someone"
        if (item != null) {
            val summary = "$actor changed the brief \"${item.name}\"."
            return PackDiff.diffItem(item.uuid, item.data, target).map { JsonObject(it + ("summary" to JsonPrimitive(summary))) }
        }
        val name = packBriefOf(state).name
        return listOf(
            buildJsonObject {
                put("type", "item.create")
                put("item", "br-${newId()}")
                put("kind", "brief")
                put("name", name)
                put("data", target)
                put("summary", "$actor added the brief \"$name\".")
            },
        )
    }

    /** Writes a change still waiting now (the setup closing, the app going to the back, another account), and sends what waits for the open pack. */
    suspend fun flush() {
        synchronized(timer) {
            saveJob?.cancel()
            saveJob = null
        }
        saveNow()
        pump()
    }

    private suspend fun saveNow() = withContext(NonCancellable) {
        lock.withLock {
            val lib = library.value
            val user = lib.user ?: return@withLock
            if (lib.waiting.isEmpty() || !canKeep()) return@withLock
            val taken = lib.waiting
            var wrote = false
            val saved = try {
                val record = lib.uuid?.let { sync.record(RecordKind.BRIEF, it) }
                // Laid over the record as it is now: a version a sync brought since it was read is kept, and only what the person changed goes over it.
                val target = laid(taken, record?.data ?: lib.base ?: newDocument())
                val uuid = when {
                    // The first save, or a record no longer on the device: a new identity, so a deleted one is never written again.
                    record == null -> sync.create(RecordKind.BRIEF, nameOf(target), target, uuid = newId()).uuid.also { last.remember(user, it); wrote = true }
                    target != record.data -> record.uuid.also { sync.edit(RecordKind.BRIEF, it, name = nameOf(target), data = target); wrote = true }
                    else -> record.uuid
                }
                uuid to target
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            library.update { now ->
                when {
                    now.user != user -> now
                    saved == null -> now.copy(save = BriefSave.FAILED)
                    else -> {
                        // A change made while this was written is still to be saved (its own timer runs), so it is not called saved.
                        val rest = now.waiting.drop(taken.size)
                        val doc = if (rest.isEmpty()) saved.second else laid(rest, saved.second)
                        now.copy(uuid = saved.first, base = saved.second, waiting = rest, doc = doc, save = if (rest.isEmpty()) BriefSave.SAVED else BriefSave.SAVING)
                    }
                }
            }
            if (wrote) scheduler.requestSync()
        }
    }

    private fun nameOf(doc: JsonObject): String = cutToCharacters((doc["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "MISSION BRIEF", MAX_NAME)

    // A change that fails (a bug, or a document no client writes) changes nothing, rather than end the app or the brief: the screens make their changes on the
    // main thread, and the brief is compiled from what this lays.
    private fun applied(change: BriefChange, doc: JsonObject): JsonObject? = try {
        change(doc)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun laid(changes: List<BriefChange>, base: JsonObject): JsonObject = changes.fold(base) { doc, change -> applied(change, doc) ?: doc }

    /** A brief with nothing answered yet, of the default format. */
    fun newDocument(): JsonObject = newDocument(DEFAULT_FORMAT)

    companion object {
        const val SAVE_DELAY_MS = 800L
        const val DEFAULT_FORMAT = "amb"

        /** The longest name the server keeps for a brief or a pack item. */
        const val MAX_NAME = 100

        private const val FAILED_HERE = "failed_here"

        internal const val NOT_TAKEN = "The pack would not take that change to the brief."
        internal const val PAUSED_WAITS = "The pack is paused: your changes to the brief wait here, and go once it can take them."
        internal const val NOT_ON_DEVICE = "Your change to the brief could not be kept on this device: it is tried again with your next change."

        internal fun keptWords(name: String) = "The pack would not take your change to the brief: it is kept in your Library as \"$name\"."

        /** A brief with nothing answered yet, of [format] (the web's `newBriefDocument`, docs/MISSION_BRIEF.md §2.2). */
        fun newDocument(format: String): JsonObject {
            val version = BriefFormats.format(format)?.template?.version ?: 1
            return buildJsonObject {
                put("version", 1)
                put("template", buildJsonObject { put("id", format); put("version", version) })
                put("answers", JsonObject(emptyMap()))
            }
        }

        /** The pack's brief: the first item of kind `brief` with a document (the web's `briefItemOf`). */
        fun briefItemOf(state: PackState): PackItemView? = state.items.firstOrNull { it.kind == "brief" && it.data is JsonObject }
    }
}
