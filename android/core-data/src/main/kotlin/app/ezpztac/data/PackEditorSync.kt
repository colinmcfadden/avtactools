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
import app.ezpztac.missionpacks.Reconcile
import app.ezpztac.missionpacks.TakeTheirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Keeps the document open in one editor ([session]) in step with the open mission pack when it is one of the pack's items: the web's
 * `usePackItemSync` effect, whose decisions are [PackItemSync.reconcile]'s. Sending is [PackItemStore.save]'s; this is the other way, and the
 * moments around a send:
 *
 *  - The first look at a document settles on the version of the item it was made from, then compares: someone else's change that arrived since
 *    is taken, and a change made as it was opened (the headings an LZ/PZ's doghouses give) is owed to the pack ([DocumentSession.owe]), as the
 *    web's doghouse effect sends it.
 *  - A change made here waits for the session's own pause; when someone else's arrives meanwhile, this one goes first ([DocumentSession.flush]),
 *    and the look after it is sent takes theirs.
 *  - Someone else's change is put into every version the session holds, the one showing and each undo step, through
 *    [DocumentSession.setQuietly] with the baseline moved on first ([PackItemSync.takeTheirs]): undo then takes back only this person's own steps,
 *    and the save the quiet change brings finds nothing to send back. Each person's own fields (an LZ/PZ's view, the routes they hid) are kept.
 *  - A change the pack will never take (finished, a viewer) is put back to the pack's version ([PackItemSync.putBack]), and the person told
 *    ([PackNotice.Refused]); what the pack dropped on the way is the engine's to keep. One it cannot take now (paused for the account's sake, its
 *    client reading the device's copy) stays in the document and goes when it can (owner decision 4: nothing is dropped).
 *  - An item someone removed, a pack that is gone, or a pack that is no longer the open one closes the document, and a change here that had not
 *    gone is kept in the library as "NAME (my edits)" ([PackNotice.ItemRemoved]); so is one left behind in a document closed while the pack
 *    could not take it ([PackNotice.Kept]). The web loses both. Never into another account's library: such a change waits while nobody's packs are
 *    on, and is dropped once another account's are.
 *
 * It looks when the open pack's state changes, when another document is opened, when a save is over, and when whose packs they are changes:
 * never for each frame of a drag, which changes only the document. Every look runs on [PackItemStore.main], the thread that edits, one at a time,
 * and passes over a document being saved (the look after its save sees the pack as the save left it). It holds nothing while the session
 * flushes, and a quiet change it makes as a save ends cuts nothing off: a save to the pack runs whole ([PackItemStore.save]) whatever cancels the
 * session's save meanwhile.
 */
internal class PackEditorSync<D : Any>(
    private val session: DocumentSession<D>,
    private val items: PackItemStore<D>,
    private val engine: PackEngine,
    private val keeper: PackKeeper,
    /** Whether this editor tells everyone else which item this person has open (an LZ/PZ's does, `{item}`). */
    private val presence: Boolean,
    private val tell: (PackNotice) -> Unit,
) {
    private val kind = items.kind
    private val idOf = items.idOf

    // Each reason to look, run as one look (any number of them while a look runs are one more).
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Changes the pack would not take, to put back at the start of the next look. Only on the main thread.
    private val putBacks = ArrayDeque<PutBack>()
    private lateinit var scope: CoroutineScope
    private var flushing: Job? = null
    private var flushFailed = false

    // The shapes last worked out and what they were worked out from, by instance: a look after someone else's change does not work out again the
    // shape of a document that has not changed, nor of an item that has not.
    private var mineOf: D? = null
    private var mineCarry: JsonElement? = null
    private var mineShape: JsonElement? = null
    private var theirsOf: JsonElement? = null
    private var theirsId: String? = null
    private var theirsShape: JsonElement? = null

    // The open item last told to the pack for presence, as its local id, and the pack's status then: none at first, as the engine has none.
    private var focused: String? = null
    private var focusedOn: PackStatus? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        val main = items.main
        scope.launch(main) { for (signal in wake) look() }
        scope.launch(main) { engine.open.collect { wake.trySend(Unit) } }
        scope.launch(main) { engine.me.collect { wake.trySend(Unit) } }
        scope.launch(main) { session.active.map { it?.let(idOf) }.distinctUntilChanged().collect { wake.trySend(Unit) } }
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
            // One look that could not be finished (a version that could not be laid over the document): the next reason to look tries again.
            // Ending here would leave the editor out of step with the pack for good.
        }
    }

    private suspend fun pass() {
        while (putBacks.isNotEmpty()) putBack(putBacks.removeFirst())
        val document = session.active.value
        val localId = document?.let(idOf)
        val ref = localId?.let(PackRef::parse)
        items.forgetAllBut(localId)
        keepLeftBehind(localId)
        val open = engine.open.value
        val state = open?.takeIf { ref != null && it.uuid == ref.pack }
        if (presence) focus(if (state != null && state.session != null && !gone(state)) localId else null, open?.status)
        if (document == null || localId == null || ref == null) return
        // The look after its save sees the pack as the save left it: a change on its way is never kept as well as sent.
        if (items.isSaving(localId)) return
        if (state == null || gone(state)) {
            // Its pack is gone, or no longer the open one (closed or turned off under it): it leaves the editor as an item removed does.
            leave(localId, ref, items.baseline(localId)?.name ?: kind.nameOf(document))
            return
        }
        // Nothing can be compared while the pack's client reads the device's copy, nor sent while it is paused for the account's sake: a change
        // made meanwhile stays in the document, measured from the old baseline, and the first look after goes on from there.
        if (state.session == null || state.status == PackStatus.PAUSED) return
        val item = state.item(ref.item)?.takeIf { it.kind == kind.kind }
        var base = items.baseline(localId)
        val firstLook = base == null
        if (base == null) {
            // Settled on the version it was made from: someone else's change that arrived since is then theirs to take, never one to undo.
            val from = items.takeMadeFrom(localId) ?: item ?: return
            base = PackItemSync.settled(from, theirShape(localId, from))
            items.setBaseline(localId, base)
        }
        when (PackItemSync.reconcile(base, item, mine(document, base, item), state.readOnly) { item?.let { theirShape(localId, it) } }) {
            Reconcile.NOTHING -> Unit
            Reconcile.SETTLE -> {
                val seen = checkNotNull(item)
                items.setBaseline(localId, PackItemSync.settled(seen, theirShape(localId, seen)))
            }
            Reconcile.SEND_LATER -> session.owe()
            Reconcile.FLUSH_FIRST -> flushFirst()
            Reconcile.TAKE_THEIRS -> take(localId, PackItemSync.takeTheirs(kind, localId, base, checkNotNull(item)))
            Reconcile.PUT_BACK -> {
                take(localId, PackItemSync.putBack(kind, localId, checkNotNull(item)))
                // What came with opening it in a pack that takes nothing (headings its doghouses give) was not the person's to be told of.
                if (!firstLook) tell(PackNotice.Refused(ref.pack, localId, PackItemStore.READ_ONLY))
            }
            Reconcile.REMOVED -> leave(localId, ref, base.name)
        }
    }

    private fun gone(state: PackState): Boolean = state.status == PackStatus.GONE || state.session?.gone != null

    // The editor's version as the decisions read it: its name, and its shape keeping what the pack's data carries that this version cannot hold.
    private fun mine(document: D, base: PackBaseline?, item: PackItemView?): EditVersion {
        val carry = base?.carryFrom ?: item?.data
        if (document !== mineOf || carry !== mineCarry) {
            mineShape = kind.docOf(document, carry)
            mineOf = document
            mineCarry = carry
        }
        return EditVersion(kind.nameOf(document), mineShape)
    }

    private fun theirShape(localId: String, item: PackItemView): JsonElement? {
        if (item.data !== theirsOf || localId != theirsId) {
            theirsShape = kind.currentShape(localId, item)
            theirsOf = item.data
            theirsId = localId
        }
        return theirsShape
    }

    // Someone else's change arrived while one made here waits: this one goes now, and the look after its save takes theirs. One at a time; and
    // after a flush that failed, the session's own pause paces the next try rather than every look.
    private fun flushFirst() {
        session.owe()
        if (flushing?.isActive == true) return
        if (flushFailed) {
            flushFailed = false
            return
        }
        flushing = scope.launch(items.main) {
            flushFailed = try {
                session.flush()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true                                        // not saved: the session says so, and its next save tries again
            }
        }
    }

    // The pack's version into the editor: the baseline first, then every version the session holds, in one turn of the thread that edits. If it
    // cannot be laid over the document, the baseline goes back too: one that claimed their change was taken would have the next save undo it.
    private fun take(localId: String, taken: TakeTheirs<D>) {
        val before = items.baseline(localId)
        items.setBaseline(localId, taken.baseline)
        try {
            session.setQuietly(taken.apply)
        } catch (e: Exception) {
            items.setBaseline(localId, before)
            throw e
        }
    }

    // A change the pack will never take, put back if its document is still the one open, and the person told either way.
    private fun putBack(request: PutBack) {
        val ref = PackRef.parse(request.localId) ?: return
        val document = session.active.value
        val state = engine.open.value
        if (document != null && idOf(document) == request.localId && state != null && state.uuid == ref.pack) {
            state.item(ref.item)?.takeIf { it.kind == kind.kind }?.let { take(request.localId, PackItemSync.putBack(kind, request.localId, it)) }
        }
        tell(PackNotice.Refused(ref.pack, request.localId, request.reason))
    }

    // The item is not in the pack any more, the pack is gone, or it is not the open one: the document is closed (unless another was opened
    // meanwhile), and its last change, which its save on closing could not deliver, is kept in the library, the latest of it.
    private suspend fun leave(localId: String, ref: PackItemRef, name: String) {
        try {
            session.closeIfOpen(localId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Closed all the same ([DocumentSession.close]); the save that failed had nowhere to go.
        }
        val kept = items.takeUndelivered(localId)?.let { keep(localId, ref, it) }
        tell(PackNotice.ItemRemoved(ref.pack, localId, name, kept))
    }

    // Changes held back in documents that have left the editor (closed or replaced while their pack could not take them): kept in the library, as
    // the engine keeps what a pack refused.
    private suspend fun keepLeftBehind(openId: String?) {
        for ((id, held) in items.takeUndeliveredExcept(openId)) {
            val ref = PackRef.parse(id) ?: continue
            val saved = keep(id, ref, held) ?: continue
            tell(PackNotice.Kept(ref.pack, packName(ref.pack), listOf(saved), setOf(held.reason)))
        }
    }

    // [held]'s change, saved to the library as this person's own version, "NAME (my edits)", named as they had it. Only into its owner's library:
    // while nobody's packs are on it waits (held again), and once another account's are it is dropped. Null when it was not kept (nothing was
    // changed, or it waits, or the library could not be written, in which case it waits too).
    private suspend fun keep(localId: String, ref: PackItemRef, held: Undelivered<D>): KeptOutcome.Saved? {
        val me = engine.me.value?.id
        if (me == null) {
            items.holdAgain(localId, held)
            return null
        }
        if (me != held.owner) return null
        val mine = PackItemSync.mine(kind, held.base, null, held.document)
        if (!PackItemSync.changedHere(held.base, mine)) return null
        val data = mine.doc ?: return null
        val name = kind.nameOf(held.document).trim().ifEmpty { held.base.name }
        // Its own record each time: an item can leave the editor with a change more than once.
        val version = KeptVersion("${ref.pack}:${ref.item}:unsent:${UUID.randomUUID()}", kind.kind, PackSentences.myEditsName(name), data)
        return try {
            keeper.keep(ref.pack, listOf(version)).filterIsInstance<KeptOutcome.Saved>().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            items.holdAgain(localId, held)
            null
        }
    }

    private fun packName(pack: String): String? =
        (engine.open.value?.takeIf { it.uuid == pack }?.session?.pack?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content

    // What this person has open, for everyone else's presence: the item open here while it is one of the open pack's, else nothing. Said again
    // when the pack's status changes, so a client that replaced the one told (after a pause) hears it too.
    private fun focus(localId: String?, status: PackStatus?) {
        if (localId == focused && (localId == null || status == focusedOn)) return
        focused = localId
        focusedOn = status
        engine.setFocus(localId?.let { PackRef.parse(it) }?.let { JsonObject(mapOf("item" to JsonPrimitive(it.item))) })
    }
}
