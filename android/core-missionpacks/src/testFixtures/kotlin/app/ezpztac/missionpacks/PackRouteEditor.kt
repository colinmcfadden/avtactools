package app.ezpztac.missionpacks

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.jupiter.api.Assertions.fail

/**
 * A pack's item open in an editor on [device], kept in step with the pack by [PackItemSync]'s decisions over [kind], as core-data's PackEditorSync keeps the
 * app's sessions in step with it, without their timing: a scenario [look]s where the app would (after a change made here, once the session's pause is over,
 * and whenever the pack may have changed). What the map would draw is [document].
 */
public open class PackItemEditor<D : Any>(private val device: PackDevice, pack: String, private val item: String, private val kind: PackKind<D>) {
    private val localId = PackRef.localId(pack, item)
    private var base: PackBaseline? = null

    /** The document as the editor has it: what the map draws. Null before [open], and once the pack has no such item. */
    public var document: D? = null
        private set

    /** Opens the item as the device holds it. */
    public fun open() {
        val view = checkNotNull(device.item(item)) { "${device.label} has no $item" }
        document = kind.fromItem(localId, view, null)
        base = PackItemSync.settled(kind, localId, view)
    }

    /** A change made here, as the app's tools make one. */
    public fun change(edit: (D) -> D) {
        document = edit(checkNotNull(document) { "${device.label} has nothing open" })
    }

    /**
     * What the editor does now, until it is in step: a change made here is sent (its pause over), someone else's is taken, one the pack will not take
     * is put back. Why a send was refused, or null.
     */
    public suspend fun look(): String? {
        repeat(MAX_STEPS) {
            val doc = document ?: return null
            val view = device.item(item)
            when (PackItemSync.reconcile(kind, localId, base, view, doc, device.state?.readOnly == true)) {
                Reconcile.NOTHING -> return null
                Reconcile.SETTLE -> base = PackItemSync.settled(kind, localId, checkNotNull(view))
                Reconcile.SEND_LATER, Reconcile.FLUSH_FIRST -> {
                    val from = checkNotNull(base)
                    val plan = PackItemSync.flushPlan(kind, localId, from, checkNotNull(view), doc, device.person.user.name) ?: return null
                    device.edit(*plan.ops.toTypedArray())?.let { return it }
                    base = PackItemSync.sent(from, plan)
                }
                Reconcile.TAKE_THEIRS -> take(PackItemSync.takeTheirs(kind, localId, base, checkNotNull(view)))
                Reconcile.PUT_BACK -> take(PackItemSync.putBack(kind, localId, checkNotNull(view)))
                Reconcile.REMOVED -> {
                    document = null
                    return null
                }
            }
        }
        return fail("${device.label}'s editor and the pack never settle")
    }

    // The baseline first, then the document, as an editor here takes a version: the look after finds nothing to send back.
    private fun take(taken: TakeTheirs<D>) {
        base = taken.baseline
        document = taken.apply(checkNotNull(document))
    }

    private companion object {
        // More steps than any look takes: send, settle, take, settle.
        const val MAX_STEPS = 10
    }
}

/** A pack's route set open in an editor on [device] ([PackItemEditor] over [RoutePackKind]). */
public class PackRouteEditor(device: PackDevice, pack: String, item: String) : PackItemEditor<RouteSet>(device, pack, item, RoutePackKind) {
    /** Point [pointId] of route [routeId] as the editor has it. */
    public fun at(routeId: String, pointId: String): LatLon? =
        document?.route(routeId)?.points?.firstOrNull { it.id == pointId }?.let { LatLon(it.lat, it.lon) }
}

/** A pack's LZ/PZ open in an editor on [device] ([PackItemEditor] over [LzPackKind]). */
public class PackLzEditor(device: PackDevice, pack: String, item: String) : PackItemEditor<Diagram>(device, pack, item, LzPackKind()) {
    /** Where graphic [id] of [collection] is, as the editor has it. */
    public fun at(collection: String, id: JsonElement): LatLon? {
        val graphic = document?.let { DiagramOps.graphic(it, collection, DiagramOps.idText(id)) } ?: return null
        val lat = (graphic["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (graphic["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return LatLon(lat, lon)
    }

    /** Graphic [id] of [collection] as the editor has it. */
    public fun graphic(collection: String, id: JsonElement): JsonObject? = document?.let { DiagramOps.graphic(it, collection, DiagramOps.idText(id)) }
}
