package app.ezpztac.missionpacks

import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import org.junit.jupiter.api.Assertions.fail

/**
 * A pack's route set open in an editor on [device], kept in step with the pack by [PackItemSync]'s decisions over [RoutePackKind], as core-data's
 * PackEditorSync keeps the app's route session in step with it, without its timing: a scenario [look]s where the app would (after a change made here,
 * once the session's pause is over, and whenever the pack may have changed). What the map would draw is [document].
 */
public class PackRouteEditor(private val device: PackDevice, pack: String, private val item: String) {
    private val localId = PackRef.localId(pack, item)
    private var base: PackBaseline? = null

    /** The set as the editor has it: what the map draws. Null before [open], and once the pack has no such item. */
    public var document: RouteSet? = null
        private set

    /** Opens the set as the device holds it. */
    public fun open() {
        val view = checkNotNull(device.item(item)) { "${device.label} has no $item" }
        document = RoutePackKind.fromItem(localId, view, null)
        base = PackItemSync.settled(RoutePackKind, localId, view)
    }

    /** A change made here, as a route tool makes one. */
    public fun change(edit: (RouteSet) -> RouteSet) {
        document = edit(checkNotNull(document) { "${device.label} has nothing open" })
    }

    /** Point [pointId] of route [routeId] as the editor has it. */
    public fun at(routeId: String, pointId: String): LatLon? =
        document?.route(routeId)?.points?.firstOrNull { it.id == pointId }?.let { LatLon(it.lat, it.lon) }

    /**
     * What the editor does now, until it is in step: a change made here is sent (its pause over), someone else's is taken, one the pack will not take
     * is put back. Why a send was refused, or null.
     */
    public suspend fun look(): String? {
        repeat(MAX_STEPS) {
            val doc = document ?: return null
            val view = device.item(item)
            when (PackItemSync.reconcile(RoutePackKind, localId, base, view, doc, device.state?.readOnly == true)) {
                Reconcile.NOTHING -> return null
                Reconcile.SETTLE -> base = PackItemSync.settled(RoutePackKind, localId, checkNotNull(view))
                Reconcile.SEND_LATER, Reconcile.FLUSH_FIRST -> {
                    val from = checkNotNull(base)
                    val plan = PackItemSync.flushPlan(RoutePackKind, localId, from, checkNotNull(view), doc, device.person.user.name) ?: return null
                    device.edit(*plan.ops.toTypedArray())?.let { return it }
                    base = PackItemSync.sent(from, plan)
                }
                Reconcile.TAKE_THEIRS -> take(PackItemSync.takeTheirs(RoutePackKind, localId, base, checkNotNull(view)))
                Reconcile.PUT_BACK -> take(PackItemSync.putBack(RoutePackKind, localId, checkNotNull(view)))
                Reconcile.REMOVED -> {
                    document = null
                    return null
                }
            }
        }
        return fail("${device.label}'s editor and the pack never settle")
    }

    // The baseline first, then the document, as an editor here takes a version: the look after finds nothing to send back.
    private fun take(taken: TakeTheirs<RouteSet>) {
        base = taken.baseline
        document = taken.apply(checkNotNull(document))
    }

    private companion object {
        // More steps than any look takes: send, settle, take, settle.
        const val MAX_STEPS = 10
    }
}
