package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an editor's copy of a pack item was last in step with: the web's baseline in `usePackItemSync.js`.
 *
 * [data] is the pack's data for the item then, compared by instance: the session makes a new instance for whatever it changes, so
 * another instance means "maybe changed", and every decision is then made on the content. It is null right after a send ([justSent])
 * until the next pass compares the editor with the pack again. [name] is the item's name then, and [doc] the editor's shape of it then
 * ([PackKind.docOf]), which the next change is measured from. [carryFrom] is the pack's data the editor last took, which keeps what
 * this version cannot hold (a field a newer web wrote) when the editor's document is made into a shape again.
 */
public class PackBaseline(
    public val data: JsonElement?,
    public val name: String,
    public val doc: JsonElement?,
    public val carryFrom: JsonElement?,
) {
    /** Right after a send: what the pack has is not what it was in step with, until the next pass compares them. */
    public val justSent: Boolean get() = data == null
}

/** What one pass does for an item open in an editor ([PackItemSync.reconcile]). */
public enum class Reconcile {
    /** In step, and nothing waits. */
    NOTHING,

    /** The editor has what the pack has (or sees the item for the first time): remember the item as it is ([PackItemSync.settled]). */
    SETTLE,

    /** A change made here waits: send it once the item has been still (the web's 400 ms; on Android, the document's own pause). */
    SEND_LATER,

    /** Someone else's change arrived while one made here waits: send this one now, and the pass after the send takes theirs. */
    FLUSH_FIRST,

    /** The pack has someone else's change: put its version into the editor ([PackItemSync.takeTheirs]). */
    TAKE_THEIRS,

    /**
     * A change made here in a pack that takes nothing from this person (finished, or they may only view): the pack's version is put
     * back ([PackItemSync.putBack]), and the person told. The web takes the pack's version as for [TAKE_THEIRS], and says nothing.
     */
    PUT_BACK,

    /** The pack has no such item any more (someone removed it, or this person did): it leaves the editor, and its baseline is forgotten. */
    REMOVED,
}

/** What one change made here is sent as ([PackItemSync.flushPlan]): [ops] in order, and the name and shape the editor had then. */
public data class FlushPlan(val ops: List<JsonObject>, val sentName: String, val sentDoc: JsonElement?)

/** How to take the pack's version of an item ([PackItemSync.takeTheirs]): the baseline to remember first, then [apply] to each version. */
public class TakeTheirs<D : Any>(public val baseline: PackBaseline, public val apply: (D) -> D)

/**
 * The decisions of the web's `usePackItemSync.js`, without the timing or the editor: when an item open in an editor sends what was
 * changed here, when it takes someone else's change, when it is put back, and when it leaves. The caller holds each open item's
 * [PackBaseline] (null until the first pass sees it), runs [reconcile] whenever the pack's state, the open document or a send changes,
 * and does what it says: a send is [flushPlan], then [sent] once the pack has the operations (or [putBack] when it refuses them);
 * someone else's change is taken with [takeTheirs]; a change the pack will not take is undone with [putBack]. Pure, so the web's hook
 * tests (usePackLz, usePackRoutes, usePackPoints) can be run against it.
 *
 * What is sent is always the change from the version the editor was last in step with, never from the pack's latest, so it lands on
 * top of whatever others did meanwhile and both stand (on one field, the later). A change waiting here is sent before anyone else's is
 * taken, so nothing made here is lost under it.
 */
public object PackItemSync {
    private const val ITEM = "it"

    /** The baseline for [item] as the pack has it now, [shape] being its shape today ([PackKind.currentShape]): the web's `settle`. */
    public fun settled(item: PackItemView, shape: JsonElement?): PackBaseline = PackBaseline(item.data, item.name, shape, item.data)

    /** Whether the editor's version [mine] differs from what it was last in step with ([base]): its content, or its name trimmed. */
    public fun changedHere(base: PackBaseline, mine: EditVersion): Boolean =
        !PackDiff.sameData(mine.doc, base.doc) || Js.trim(mine.name.orEmpty()) != base.name

    /**
     * What one pass does for an item open in an editor (the effect in `usePackItemSync.js`, branch for branch). [base] is what it was
     * last in step with (null: not seen yet), [item] the pack's version as the person sees it (null: the pack has no such item), [mine]
     * the editor's version (its name, and its document as a shape), and [theirShape] today's shape of [item], asked only when needed.
     */
    public fun reconcile(base: PackBaseline?, item: PackItemView?, mine: EditVersion, readOnly: Boolean, theirShape: () -> JsonElement?): Reconcile {
        if (item == null) return if (base != null) Reconcile.REMOVED else Reconcile.NOTHING
        if (base == null) return Reconcile.SETTLE
        val changed = changedHere(base, mine)
        if (base.justSent) {
            // Still being changed: that goes once it is still, and is compared with the pack after.
            return when {
                changed -> Reconcile.SEND_LATER
                !PackDiff.sameData(theirShape(), base.doc) || item.name != base.name -> Reconcile.TAKE_THEIRS
                else -> Reconcile.SETTLE
            }
        }
        if (item.data !== base.data || item.name != base.name) {
            if (changed) return Reconcile.FLUSH_FIRST
            return if (!PackDiff.sameData(theirShape(), mine.doc) || item.name != mine.name.orEmpty()) Reconcile.TAKE_THEIRS else Reconcile.SETTLE
        }
        if (!changed) return Reconcile.NOTHING
        return if (readOnly) Reconcile.PUT_BACK else Reconcile.SEND_LATER
    }

    /**
     * What the editor's version [mine] of item [uuid] is sent as (usePackItemSync's flush, after its read-only check, which is the
     * caller's: a pack that takes nothing gets its version put back instead), or null when nothing changed since [base]. A rename first;
     * then, only with a change to the content, whatever brings the pack's data to today's shape ([shared] to [theirShape], asked only
     * then); then the change from [base]'s document; each with one sentence ([PackEdit.composeEdit]). Throws [PackDiffException] when
     * the content would have to be replaced whole.
     */
    public fun flushPlan(
        uuid: String,
        base: PackBaseline,
        item: PackItemView,
        mine: EditVersion,
        shared: () -> JsonElement?,
        theirShape: () -> JsonElement?,
        describe: ChangeSentence,
        actor: String?,
    ): FlushPlan? {
        val edit = PackEdit.composeEdit(uuid, EditVersion(base.name, base.doc), mine, item, shared, theirShape, describe, actor) ?: return null
        return FlushPlan(edit.ops, edit.name, mine.doc)
    }

    /** The baseline once the pack has [plan]'s operations: [PackBaseline.justSent], with what was sent as what the next change is measured from. */
    public fun sent(base: PackBaseline, plan: FlushPlan): PackBaseline = PackBaseline(null, plan.sentName, plan.sentDoc, base.carryFrom)

    /**
     * The operations that turn [base]'s document into [theirShape]: someone else's change, as it would be made to the editor's version
     * that was in step. Null when the content would have to be replaced whole (it never is for a document a [PackKind] made).
     */
    public fun takeTheirsDelta(base: PackBaseline, theirShape: JsonElement?): List<JsonObject>? = try {
        PackDiff.diffData(base.doc, theirShape)
    } catch (_: PackDiffException) {
        null
    }

    /**
     * [doc], one version of an editor's document as a shape, with [delta] applied in order as the pack applies operations ([kind] is
     * the item's kind). An operation whose target that version lacks (an older undo step, before a graphic someone else changed was
     * placed) is passed over, as the pack passes over an edit to something gone.
     */
    public fun rebase(doc: JsonElement?, delta: List<JsonObject>, kind: String): JsonElement? {
        if (doc == null) return null
        var items = mapOf(ITEM to PackItemState(kind, "", doc, deleted = false))
        for (op in delta) {
            val result = PackOps.apply(items, JsonObject(op + ("item" to JsonPrimitive(ITEM))))
            if (result.status == OpStatus.APPLIED) items = result.items
        }
        return items.getValue(ITEM).data
    }

    // -- The same, for an editor's document of a kind ---------------------------------------------------------------------------

    /** The editor's [document] as [reconcile] and [flushPlan] read it: its name, and its shape, keeping what [base] (or [item]) carries. */
    public fun <D : Any> mine(kind: PackKind<D>, base: PackBaseline?, item: PackItemView?, document: D): EditVersion =
        EditVersion(kind.nameOf(document), kind.docOf(document, base?.carryFrom ?: item?.data))

    /** [reconcile] for an editor's [document] of [kind], open under [localId]. */
    public fun <D : Any> reconcile(
        kind: PackKind<D>,
        localId: String,
        base: PackBaseline?,
        item: PackItemView?,
        document: D,
        readOnly: Boolean,
    ): Reconcile = reconcile(base, item, mine(kind, base, item, document), readOnly) { item?.let { kind.currentShape(localId, it) } }

    /** [settled] for an item of [kind] open under [localId]. */
    public fun <D : Any> settled(kind: PackKind<D>, localId: String, item: PackItemView): PackBaseline =
        settled(item, kind.currentShape(localId, item))

    /** [flushPlan] for an editor's [document] of [kind], open under [localId], by [actor]. */
    public fun <D : Any> flushPlan(kind: PackKind<D>, localId: String, base: PackBaseline, item: PackItemView, document: D, actor: String?): FlushPlan? =
        flushPlan(
            uuid = item.uuid,
            base = base,
            item = item,
            mine = mine(kind, base, item, document),
            shared = { kind.shared(item.data) },
            theirShape = { kind.currentShape(localId, item) },
            describe = ChangeSentence(kind::describe),
            actor = actor,
        )

    /**
     * How an editor takes the pack's version of [item] without breaking its undo. Remember [TakeTheirs.baseline] first, then apply
     * [TakeTheirs.apply] to every version of the document the editor holds (the one showing and each undo step): someone else's change
     * since [base] ([takeTheirsDelta]) is laid over each one's own shape ([rebase]), keeping its own fields ([PackKind.withShared]). The
     * version showing, which has not changed since [base], then becomes exactly the pack's; an older one keeps what this person did
     * there, with the other's change carried in, so undo takes back only this person's own steps. A version whose name is the one the
     * pack had takes the pack's new name. With no [base], or a change that would replace the content whole, each version takes the
     * pack's whole shape, still keeping its own fields.
     */
    public fun <D : Any> takeTheirs(kind: PackKind<D>, localId: String, base: PackBaseline?, item: PackItemView): TakeTheirs<D> {
        val theirs = kind.currentShape(localId, item)
        val baseline = settled(item, theirs)
        val delta = base?.let { takeTheirsDelta(it, theirs) }
        if (base == null || delta == null) return TakeTheirs(baseline) { document -> kind.withShared(document, theirs, item.name) }
        return TakeTheirs(baseline) { document ->
            val name = kind.nameOf(document)
            val shape = rebase(kind.docOf(document, base.carryFrom), delta, kind.kind) ?: theirs
            kind.withShared(document, shape, if (Js.trim(name) == base.name) item.name else name)
        }
    }

    /**
     * How an editor puts the pack's version of [item] back over a change the pack will not take ([Reconcile.PUT_BACK], a send it refused,
     * or one in a pack turned read-only): remember [TakeTheirs.baseline] first, then every version of the document takes the pack's whole
     * shape and name, keeping its own fields. Not [takeTheirs]: that lays only the pack's change over each version and would keep this
     * one, which can never be sent. So it is undone in the history too, where nothing could be redone into the pack anyway.
     */
    public fun <D : Any> putBack(kind: PackKind<D>, localId: String, item: PackItemView): TakeTheirs<D> {
        val theirs = kind.currentShape(localId, item)
        return TakeTheirs(settled(item, theirs)) { document -> kind.withShared(document, theirs, item.name) }
    }
}
