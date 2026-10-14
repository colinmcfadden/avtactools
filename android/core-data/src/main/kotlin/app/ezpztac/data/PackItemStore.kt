package app.ezpztac.data

import app.ezpztac.missionpacks.PackBaseline
import app.ezpztac.missionpacks.PackDiffException
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackItemRef
import app.ezpztac.missionpacks.PackItemSync
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackKind
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an editor's documents of one kind are read and written when they are a mission pack's items: an LZ/PZ in [DiagramSession], a route set
 * in [RouteSession], open under an id naming the pack and the item ([PackRef]). Opening one gives the open pack's item as [kind] makes it a
 * document, with this person's own fields from the device. Saving one sends the pack the change since the version the editor was last in step
 * with (the web's `usePackItemSync` flush, [PackItemSync.flushPlan]): a rename, then whatever brings an item in an older shape to today's, then
 * the change, every operation with one sentence for the history. [PackEditorSync] keeps the open document in step the other way, through the
 * same baselines.
 *
 * The session's own pause before a save stands for the web's 400 ms, so a drag is one change. Nothing of the library's is ever done for a pack's
 * item: a save gives back the same document, so the session never takes a new identity for it, and nothing is written to the library.
 *
 * A change is measured from nothing newer than its document: each document remembers the version of the item it was made from, so its first
 * look, or a save made before that ([DocumentSession.update] on an item that is not open), measures the change from that version and never
 * undoes someone else's change that arrived meanwhile. A change the pack cannot take now (paused for the account's sake, its client still reading
 * the device's copy, the pack closed or gone, the item removed) is held back, never put back: it stays in the document, goes when it can, and is
 * kept in the library if the document leaves the editor first ([takeUndelivered]).
 *
 * Baselines are moved on and read on [main], the thread that edits, so a save and a pass of the editor's sync never see each other half done;
 * what a change is sent as is worked out on [work], off it.
 */
class PackItemStore<D : Any>(
    private val engine: PackEngine,
    internal val kind: PackKind<D>,
    private val store: PackStore,
    internal val idOf: (D) -> String,
    internal val main: CoroutineDispatcher,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : DocumentStore<D> {
    // What each document open in an editor was last in step with, by its local id. Set by the editor's sync; moved on by a send here.
    private val baselines = ConcurrentHashMap<String, PackBaseline>()

    // The version of the item each document was made from, until its first look or a save measures from it.
    private val madeFrom = ConcurrentHashMap<String, PackItemView>()

    // Whose each document is: the account the packs were for when it was opened.
    private val owners = ConcurrentHashMap<String, Int>()

    // The documents being saved now, by local id, counted: the editor's sync leaves one alone until its save is over.
    private val saving = ConcurrentHashMap<String, Int>()

    // Each document's own fields as last written to the device, so they are written again only when they change.
    private val owns = ConcurrentHashMap<String, JsonObject>()

    // Changes the pack could not take when they were saved, by local id: the latest document of each, what it is measured from, and why.
    private val held = ConcurrentHashMap<String, Undelivered<D>>()

    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** The local id of each document whose save is over (sent, nothing to send, held back, or put back): the editor's sync looks again then. */
    internal val saved: SharedFlow<String> = _saved.asSharedFlow()

    private val _putBack = MutableSharedFlow<PutBack>(extraBufferCapacity = 64)

    /** A change the pack will never take (read-only, malformed), to put back to the pack's version in the editor and tell the person of. */
    internal val putBack: SharedFlow<PutBack> = _putBack.asSharedFlow()

    /** What [localId] was last in step with; null before the editor's sync has first seen it. On [main]. */
    internal fun baseline(localId: String): PackBaseline? = baselines[localId]

    /** On [main]. Null forgets it. */
    internal fun setBaseline(localId: String, baseline: PackBaseline?) {
        if (baseline == null) baselines.remove(localId) else baselines[localId] = baseline
    }

    /** Forgets every baseline but [localId]'s: a document no longer open starts again from the pack's version. On [main]. */
    internal fun forgetAllBut(localId: String?) {
        baselines.keys.retainAll { it == localId }
    }

    /** Whether [localId] is being saved. On [main]. */
    internal fun isSaving(localId: String): Boolean = saving.containsKey(localId)

    /** The version of the item [localId] was made from, let go of: its first look measures from it. On [main]. */
    internal fun takeMadeFrom(localId: String): PackItemView? = madeFrom.remove(localId)

    /** Whose document [localId] is (the account the packs were for when it was opened), or null when that is not known. */
    internal fun owner(localId: String): Int? = owners[localId]

    /** The change held back for [localId], let go of. On [main]. */
    internal fun takeUndelivered(localId: String): Undelivered<D>? = held.remove(localId)

    /** The changes held back for every document but [localId], let go of: documents that left the editor with them. On [main]. */
    internal fun takeUndeliveredExcept(localId: String?): List<Pair<String, Undelivered<D>>> =
        held.keys.filter { it != localId }.mapNotNull { id -> held.remove(id)?.let { id to it } }

    /** Holds back again a change [takeUndeliveredExcept] gave, that could not be kept yet (nobody the packs are for). On [main]. */
    internal fun holdAgain(localId: String, undelivered: Undelivered<D>) {
        held.putIfAbsent(localId, undelivered)
    }

    /**
     * The document for pack item [uuid] (a local id): only while its pack is the open one, holds the item as this kind, and is neither loading
     * nor gone. Never opens a pack, and settles no baseline: it remembers the version it was made from, which the editor's sync settles on when
     * it first sees the document (a newer one may have arrived by then), and which a save before that measures from.
     */
    override suspend fun open(uuid: String): D? {
        val ref = PackRef.parse(uuid) ?: return null
        val item = itemOf(engine.open.value, ref) ?: return null
        val owner = engine.me.value?.id
        val document = kind.fromItem(uuid, item, store.own(ref.pack, ref.item))
        // A document made anew starts again: nothing it was in step with before applies to it.
        baselines.remove(uuid)
        madeFrom[uuid] = item
        if (owner == null) owners.remove(uuid) else owners[uuid] = owner
        owns[uuid] = kind.ownOf(document)
        return document
    }

    /**
     * Sends the change [document] makes to its item, and gives back the same document. Done whole once begun: the session cancels a save under way
     * when it flushes or another edit comes in, and a save cut off between the pack taking its operations and its baseline moving on would send the
     * same change again.
     */
    override suspend fun save(document: D): D = withContext(NonCancellable) {
        val localId = idOf(document)
        val ref = checkNotNull(PackRef.parse(localId)) { "Not a pack item's document: $localId" }
        withContext(main) { saving.merge(localId, 1, Int::plus) }
        try {
            send(document, localId, ref)
        } finally {
            withContext(main) {
                saving.computeIfPresent(localId) { _, count -> (count - 1).takeIf { it > 0 } }
                _saved.tryEmit(localId)
            }
        }
        document
    }

    /** The document is closed: what it was in step with is forgotten, and it starts again from the pack's version when opened. A change held back stays. */
    override fun release(uuid: String) {
        baselines.remove(uuid)
        madeFrom.remove(uuid)
        owners.remove(uuid)
        owns.remove(uuid)
    }

    private class Begun(val state: PackState?, val base: PackBaseline?, val madeFrom: PackItemView?, val owner: Int?)

    private suspend fun send(document: D, localId: String, ref: PackItemRef) {
        val begun = withContext(main) {
            val base = baselines[localId]
            Begun(engine.open.value, base, if (base == null) madeFrom.remove(localId) else null, owners[localId] ?: engine.me.value?.id)
        }
        val state = begun.state
        val item = itemOf(state, ref)
        // With no baseline (a document saved before the editor first looked at it, or one not open at all, as an analysis that came back), the
        // version it was made from: the change is then exactly the one made to it, whatever arrived since. The editor's first look measures from
        // the same.
        val base = begun.base ?: (begun.madeFrom ?: item)?.let { from ->
            val made = withContext(work) { PackItemSync.settled(kind, localId, from) }
            withContext(main) { baselines.putIfAbsent(localId, made) ?: made }
        }
        if (base == null) return                                     // nothing to measure a change from: this document was never opened here
        if (state == null || item == null) {
            // Not the open pack's item now: removed, the pack closed or gone, or its client still reading the device's copy.
            hold(localId, document, base, begun.owner, awayReason(state, ref))
            return
        }
        keepOwn(document, localId, ref)
        if (state.status == PackStatus.PAUSED) {
            hold(localId, document, base, begun.owner, PAUSED)
            return
        }
        val plan = try {
            withContext(work) { PackItemSync.flushPlan(kind, localId, base, item, document, engine.me.value?.name) }
        } catch (_: PackDiffException) {
            // Only an item whose data is not an object can need replacing whole, which no operation does: it cannot be changed from here.
            refuse(localId, REPLACED_WHOLE)
            return
        }
        if (plan == null) {
            held.remove(localId)                                     // in step: nothing is owed
            return
        }
        if (state.readOnly) {
            refuse(localId, READ_ONLY)
            return
        }
        val refused = engine.edit(ref.pack, plan.ops)
        when {
            refused == null -> {
                held.remove(localId)
                // The next change is measured from what was just sent.
                withContext(main) { baselines.replace(localId, base, PackItemSync.sent(base, plan)) }
            }
            refused in HELD_BACK -> hold(localId, document, base, begun.owner, refused)
            else -> refuse(localId, refused)
        }
    }

    // A change the pack cannot take now: it stays in the document, and the latest of it is noted, to be kept if the document leaves first.
    private fun hold(localId: String, document: D, base: PackBaseline, owner: Int?, reason: String) {
        held[localId] = Undelivered(document, base, owner, reason)
    }

    // A change the pack will never take: put back in the editor, and the person told.
    private suspend fun refuse(localId: String, reason: String) {
        held.remove(localId)
        _putBack.emit(PutBack(localId, reason))
    }

    // Why the open pack does not have [ref]'s item now.
    private fun awayReason(state: PackState?, ref: PackItemRef): String = when {
        state == null || state.uuid != ref.pack -> CLOSED
        state.status == PackStatus.GONE || state.session?.gone != null -> GONE
        state.session == null -> LOADING
        else -> REMOVED
    }

    // This person's own fields of [document] (an LZ/PZ's view, the routes they hid), kept on the device and never sent, only while its pack is
    // open and has the item (never for a pack the device has forgotten): a base map changed in a finished pack is kept too. One that cannot be
    // written costs only the next opening its view, so it never holds the change back.
    private suspend fun keepOwn(document: D, localId: String, ref: PackItemRef) {
        val own = kind.ownOf(document)
        if (owns[localId] == own) return
        try {
            store.putOwn(ref.pack, ref.item, own)
            owns[localId] = own
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // Item [ref] as [state] shows it, if that is [ref]'s pack, it is not gone, and it holds the item as this kind.
    private fun itemOf(state: PackState?, ref: PackItemRef): PackItemView? {
        if (state == null || state.uuid != ref.pack || state.status == PackStatus.GONE || state.session?.gone != null) return null
        return state.item(ref.item)?.takeIf { it.kind == kind.kind }
    }

    internal companion object {
        /** Why a change was put back: the pack takes nothing from this person (finished, or they may only view it). */
        const val READ_ONLY = "read_only"

        /** Why a change was put back: the item's data is not something an operation can change. */
        const val REPLACED_WHOLE = "replaced_whole"

        /** Why a change was held back: the pack is paused for the account's sake (owner decision 4: nothing is dropped). */
        const val PAUSED = "paused"

        /** Why a change was held back: its pack is not the open one any more. */
        const val CLOSED = "closed"

        /** Why a change was held back: the pack is gone. */
        const val GONE = "gone"

        /** Why a change was held back: the pack's client is still reading the device's copy. */
        const val LOADING = "loading"

        /** Why a change was held back: the item is not in the pack any more. */
        const val REMOVED = "removed"

        // The engine's refusals that say the change cannot go now, not that it never can: held back, never put back.
        private val HELD_BACK = setOf(PAUSED, CLOSED, GONE, LOADING)
    }
}

/** A change to pack item [localId] the pack will never take ([reason]), to be put back in the editor ([PackEditorSync]). */
internal data class PutBack(val localId: String, val reason: String)

/** A change to a pack item the pack could not take when it was saved: the [document] then, what it is measured from, whose it is, and why. */
internal class Undelivered<D>(val document: D, val base: PackBaseline, val owner: Int?, val reason: String)

/**
 * A session's store with mission packs: an id naming a pack item ([PackRef]) goes to [packs], every other to [library]. A pack's item never
 * reaches the library, whose save of an id it does not hold would keep the pack's work as a new library record of this person's; nor is anything
 * the library holds let go of when a pack's item closes (an open mission's file). With no [packs], a pack item's id opens nothing.
 */
internal class PackRoutedStore<D : Any>(
    private val library: DocumentStore<D>,
    private val packs: DocumentStore<D>?,
    private val idOf: (D) -> String,
) : DocumentStore<D> {
    override suspend fun open(uuid: String): D? = if (isPack(uuid)) packs?.open(uuid) else library.open(uuid)

    override suspend fun save(document: D): D {
        if (!isPack(idOf(document))) return library.save(document)
        return checkNotNull(packs) { "A pack item's document with no pack store to save it to" }.save(document)
    }

    override fun release(uuid: String) {
        if (isPack(uuid)) packs?.release(uuid) else library.release(uuid)
    }

    private fun isPack(id: String): Boolean = PackRef.parse(id) != null
}
