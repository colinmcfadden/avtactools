package app.ezpztac.data

import app.ezpztac.missionpacks.EditVersion
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.KeptVersion
import app.ezpztac.missionpacks.PackBaseline
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackItemRef
import app.ezpztac.missionpacks.PackItemSync
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSentences
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PackThreatSet
import app.ezpztac.missionpacks.PackThreats
import app.ezpztac.missionpacks.Reconcile
import app.ezpztac.missionpacks.TakeTheirs
import app.ezpztac.model.ThreatSetDocument
import app.ezpztac.model.ThreatSets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps the open mission pack's sets of threats on the map and in step with the pack: the web's `usePackThreats` over the same decisions as an editor's ([PackItemSync.reconcile]).
 * [PackEditorSync] does this for the one document an editor has open; every set of a pack is on the map at once, so this looks at each, and [ThreatStore] holds them in place of the person's own.
 *
 *  - Every `threatset` item of the pack goes on the map unless it was taken off here, which lasts while the pack is open.
 *  - A change goes after the store's pause ([PackItemStore.save]); someone else's is laid over what is here, keeping what is each person's own, which threats they have hidden and the terrain masks.
 *  - An item removed, or a pack that is gone or no longer open, takes its sets off the map, and a change here the pack had not taken is kept in the Library as "NAME (my edits)".
 *  - An account without Threats neither opens nor is shown a pack's sets of them.
 *
 * It looks when the pack's state changes (never for a pointer moving), when a save is over, and when whose packs they are changes, one look at a time on [PackItemStore.main].
 */
class PackThreatSync(
    private val store: ThreatStore,
    private val items: PackItemStore<PackThreatSet>,
    private val engine: PackEngine,
    private val keeper: PackKeeper,
    /** Whether the signed-in account has Threats. */
    private val threatsOn: () -> Boolean,
) {
    private val kind = items.kind
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val putBacks = ArrayDeque<PutBack>()
    private var tell: (PackNotice) -> Unit = {}

    // The pack whose sets the store holds. Only on the main thread.
    private var entered: String? = null

    // What each set was last looked at with, by instance: a look at a pack where one item changed does not work out every other set's shape again.
    private val seen = HashMap<String, Seen>()

    private class Seen(val item: PackItemView?, val set: PackThreatSet, val base: PackBaseline?, val readOnly: Boolean)

    private val link = object : PackThreatLink {
        override fun newSet(name: String?): PackThreatSet? {
            val state = engine.open.value ?: return null
            val count = state.items.count { it.kind == kind.kind }
            val uuid = PackSentences.packItemId(kind.kind, UUID.randomUUID().toString())
            return PackThreatSet(PackRef.localId(state.uuid, uuid), PackSentences.newItemName(kind.kind, name, count), ThreatSetDocument())
        }

        override suspend fun create(set: PackThreatSet): String? {
            val ref = checkNotNull(PackRef.parse(set.id)) { "Not a pack's set: ${set.id}" }
            val threats = (ThreatSets.write(set.document, maskBudget = 0)["threats"] as? JsonArray).orEmpty()
            val made = PackThreats.newThreatSetOp(set.name, threats, engine.me.value?.name, 0) { ref.item.removePrefix("ts-") }
            val refused = engine.edit(ref.pack, listOf(made))
            if (refused == null) {
                withContext(items.main) { items.open(set.id) }                          // whose it is, and that this is what it was made from
                wake.trySend(Unit)
            }
            return refused
        }

        override suspend fun save(set: PackThreatSet) {
            items.save(set)
        }

        override fun closed(id: String) {
            wake.trySend(Unit)
        }

        override fun refused(set: PackThreatSet, reason: String) {
            PackRef.parse(set.id)?.let { tell(PackNotice.Refused(it.pack, null, reason)) }
        }
    }

    fun start(scope: CoroutineScope, tell: (PackNotice) -> Unit) {
        this.tell = tell
        val main = items.main
        scope.launch(main) { for (signal in wake) look() }
        scope.launch(main) { engine.open.distinctUntilChanged(::sameForALook).collect { wake.trySend(Unit) } }
        scope.launch(main) { engine.me.collect { wake.trySend(Unit) } }
        scope.launch(main) { items.saved.collect { wake.trySend(Unit) } }
        scope.launch(main) {
            items.putBack.collect {
                putBacks.addLast(it)
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun look() {
        try {
            pass()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One look that could not be finished: the next reason to look tries again.
        }
    }

    private suspend fun pass() {
        while (putBacks.isNotEmpty()) putBack(putBacks.removeFirst())
        val state = engine.open.value?.takeIf { threatsOn() && !gone(it) }
        if (state?.uuid != entered) {
            leaveEntered()
            if (state != null) {
                store.enterPack(link)
                entered = state.uuid
            }
        }
        val onMap = store.packSets().mapTo(HashSet()) { it.id }
        seen.keys.retainAll(onMap)
        items.forgetAllBut(onMap)
        keepLeftBehind(onMap)
        if (state == null || state.session == null || state.status == PackStatus.PAUSED) return
        for (item in state.items) {
            if (item.kind != kind.kind) continue
            val localId = PackRef.localId(state.uuid, item.uuid)
            if (localId !in onMap && !store.isClosedHere(localId)) items.open(localId)?.let(store::takePackSet)
        }
        store.packSets().forEach { look(it, state) }
    }

    private fun gone(state: PackState): Boolean = state.status == PackStatus.GONE || state.session?.gone != null

    private suspend fun look(set: PackThreatSet, state: PackState) {
        val localId = set.id
        val ref = PackRef.parse(localId) ?: return
        if (items.isSaving(localId)) return
        if (store.unmade(localId)) {
            store.owePackSet(localId, now = false)                                      // it waited for the pack to take it: not for a change
            return
        }
        val item = state.item(ref.item)?.takeIf { it.kind == kind.kind }
        val before = seen[localId]
        if (before != null && before.item === item && before.set.document.threats === set.document.threats && before.set.name == set.name &&
            before.base === items.baseline(localId) && before.readOnly == state.readOnly
        ) {
            return
        }
        var base = items.baseline(localId)
        val firstLook = base == null
        if (base == null) {
            val from = items.takeMadeFrom(localId) ?: item ?: return
            base = PackItemSync.settled(from, kind.currentShape(localId, from))
            items.setBaseline(localId, base)
        }
        val mine = EditVersion(set.name, kind.docOf(set, base.carryFrom ?: item?.data))
        when (PackItemSync.reconcile(base, item, mine, state.readOnly) { item?.let { kind.currentShape(localId, it) } }) {
            Reconcile.NOTHING -> Unit
            Reconcile.SETTLE -> {
                val now = checkNotNull(item)
                items.setBaseline(localId, PackItemSync.settled(now, kind.currentShape(localId, now)))
            }
            Reconcile.SEND_LATER -> store.owePackSet(localId, now = false)
            Reconcile.FLUSH_FIRST -> store.owePackSet(localId, now = true)
            Reconcile.TAKE_THEIRS -> take(localId, PackItemSync.takeTheirs(kind, localId, base, checkNotNull(item)))
            Reconcile.PUT_BACK -> {
                take(localId, PackItemSync.putBack(kind, localId, checkNotNull(item)))
                if (!firstLook) tell(PackNotice.Refused(ref.pack, localId, PackItemStore.READ_ONLY))
            }
            Reconcile.REMOVED -> {
                leaveSet(localId, ref, base.name)
                return
            }
        }
        seen[localId] = Seen(item, store.packSet(localId) ?: set, items.baseline(localId), state.readOnly)
    }

    // The pack's version over what is here: the baseline first, and back again if it cannot be laid over.
    private fun take(localId: String, taken: TakeTheirs<PackThreatSet>) {
        val before = items.baseline(localId)
        items.setBaseline(localId, taken.baseline)
        try {
            store.layOverPackSet(localId, taken.apply)
        } catch (e: Exception) {
            items.setBaseline(localId, before)
            throw e
        }
    }

    private fun putBack(request: PutBack) {
        val ref = PackRef.parse(request.localId) ?: return
        val state = engine.open.value
        if (state != null && state.uuid == ref.pack) {
            state.item(ref.item)?.takeIf { it.kind == kind.kind }?.let { take(request.localId, PackItemSync.putBack(kind, request.localId, it)) }
        }
        tell(PackNotice.Refused(ref.pack, request.localId, request.reason))
    }

    // An item removed: off the map, and a change here the pack had not taken kept as the person's own.
    private suspend fun leaveSet(localId: String, ref: PackItemRef, name: String) {
        val last = store.forgetPackSet(localId)
        seen.remove(localId)
        val kept = last?.let { deliverLast(it) }?.let { keepUndelivered(items, engine, keeper, localId, ref, it) }
        items.release(localId)
        tell(PackNotice.ItemRemoved(ref.pack, localId, name, kept))
    }

    // Its last version offered to the pack, which holds back whatever it cannot take: what it held back, to keep.
    private suspend fun deliverLast(set: PackThreatSet): Undelivered<PackThreatSet>? {
        withContext(NonCancellable) { items.save(set) }
        return items.takeUndelivered(set.id)
    }

    // Changes held back for sets that have left the map (taken off it while the pack could not take them): kept in the library.
    private suspend fun keepLeftBehind(onMap: Set<String>) {
        for ((id, held) in items.takeUndeliveredExcept(onMap)) {
            val ref = PackRef.parse(id) ?: continue
            val saved = keepUndelivered(items, engine, keeper, id, ref, held) ?: continue
            tell(PackNotice.Kept(ref.pack, packName(ref.pack), listOf(saved), setOf(held.reason)))
        }
    }

    /**
     * The sets of the pack being left, sent what waits and off the map, the person's own back in their place; a pack other than [staying] only. Before the engine lets the pack go, while it
     * still takes them.
     */
    internal suspend fun leaving(staying: String?) {
        if (entered == null || entered == staying) return
        leaveEntered()
    }

    private suspend fun leaveEntered() {
        val pack = entered ?: return
        entered = null
        val left = store.leavePack()
        seen.clear()
        for (gone in left) {
            val set = gone.set
            val ref = PackRef.parse(set.id) ?: continue
            if (gone.unmade) {
                keepMade(pack, ref, set)
            } else {
                val held = items.takeUndelivered(set.id)
                val saved = held?.let { keepUndelivered(items, engine, keeper, set.id, ref, it) }
                if (saved != null) tell(PackNotice.Kept(pack, packName(pack), listOf(saved), setOf(held.reason)))
            }
            items.release(set.id)
        }
    }

    // A set made here that never reached its pack: it has no baseline to measure from, so all of it is what was changed.
    private suspend fun keepMade(pack: String, ref: PackItemRef, set: PackThreatSet) {
        if (engine.me.value == null || set.document.threats.isEmpty()) return
        val version = KeptVersion("${ref.pack}:${ref.item}:unsent:${UUID.randomUUID()}", kind.kind, PackSentences.myEditsName(set.name), kind.docOf(set, null))
        val saved = try {
            keeper.keep(ref.pack, listOf(version)).filterIsInstance<KeptOutcome.Saved>().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        tell(PackNotice.Kept(pack, packName(pack), listOf(saved), setOf(PackItemStore.CLOSED)))
    }

    private fun packName(pack: String): String? =
        (engine.open.value?.takeIf { it.uuid == pack }?.session?.pack?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The sets of the pack on the map, by local id. */
    val onMap: Flow<Set<String>> = store.sets.map { sets -> sets.mapTo(HashSet()) { it.id } }.distinctUntilChanged()

    /** Puts set [localId] of the open pack on the map again. On the main thread. False when the open pack has no such set. */
    internal suspend fun show(localId: String): Boolean {
        val set = items.open(localId) ?: return false
        store.reopenPackSet(localId)
        store.takePackSet(set)
        wake.trySend(Unit)
        return true
    }

    /** Takes set [localId] off the map here; it stays in the pack. */
    internal suspend fun hide(localId: String) {
        store.close(localId)
    }

    /** Whatever waits in the pack's sets goes to it now. */
    internal suspend fun flush() {
        store.flush()
    }

    /** Set [localId] as this person sees it, as the pack's data; null when it is not on the map. */
    internal fun shown(localId: String, item: PackItemView): JsonElement? =
        store.packSet(localId)?.let { kind.docOf(it, items.baseline(localId)?.carryFrom ?: item.data) }
}
