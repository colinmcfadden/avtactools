package app.ezpztac.data

import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.missionpacks.PackLz
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSentences
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramJson
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.RouteSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The open mission pack in the editors (docs/MISSION_PACKS.md §5a): its LZ/PZs open in the one [DiagramSession] and its route sets in the one
 * [RouteSession] every editor already uses, under ids naming the pack and the item ([PackRef]), and kept in step with the pack both ways
 * ([PackItemStore] sends, [PackEditorSync] takes everyone else's). Opening and closing a pack goes through here, so the documents of the pack being
 * left are flushed into it while it still takes them; new items are made here, in the open pack.
 *
 * What the person should hear about is [notices]: the engine's, and the editors' own ([PackNotice.Refused], [PackNotice.ItemRemoved]).
 */
@Singleton
class PackWorkspace @Inject constructor(
    private val engine: PackEngine,
    private val diagrams: DiagramSession,
    private val routes: RouteSession,
    private val lzItems: PackItemStore<Diagram>,
    private val routeItems: PackItemStore<RouteSet>,
    private val keeper: PackKeeper,
) {
    // The thread that edits, where the editors' sessions are changed and their pack items' baselines kept.
    private val main = lzItems.main
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val started = AtomicBoolean(false)
    private val editors = MutableSharedFlow<PackNotice>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What happened to the packs, and to the items open in the editors, that the person should hear about. Never the server's words. */
    val notices: Flow<PackNotice> = merge(engine.notices, editors)

    /** The items of the open pack open in the editors now (an LZ/PZ in the diagram session, a route set in the route session), by uuid. */
    val openItems: Flow<Set<String>> = combine(engine.open, diagrams.active, routes.active) { state, diagram, set ->
        val pack = state?.uuid ?: return@combine emptySet()
        listOfNotNull(diagram?.id, set?.id).mapNotNull(PackRef::parse).filter { it.pack == pack }.map { it.item }.toSet()
    }.distinctUntilChanged()

    /** Joins the editors to whichever pack is open, from now on. Once is enough; again changes nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        PackEditorSync(diagrams, lzItems, engine, keeper, presence = true, tell = ::tell).start(scope)
        PackEditorSync(routes, routeItems, engine, keeper, presence = false, tell = ::tell).start(scope)
    }

    /**
     * Opens pack [uuid], first closing whatever of another pack is open in the editors, each flushed into its pack while that is still open. A
     * save that fails on the way is thrown once both editors are closed and the pack is open: a failure never leaves one half done.
     */
    suspend fun openPack(uuid: String) {
        withContext(main) {
            val failed = leave(staying = uuid)
            engine.openPack(uuid)
            failed?.let { throw it }
        }
    }

    /** Closes the open pack, first closing its items in the editors, each flushed into it; a save that fails is thrown once all that is done. */
    suspend fun closePack() {
        withContext(main) {
            val failed = leave(staying = null)
            engine.closePack()
            failed?.let { throw it }
        }
    }

    /**
     * Packs stop for this account, whatever the reason (signed out, Mission Packs turned off, the `.mil` gate lost): whatever of a pack is
     * open in the editors is written into its pack and closed first, while the engine still takes it, so its last change waits in this
     * account's queue on the device (for its next enable, never anyone else's) rather than being refused and kept as "NAME (my edits)". Then
     * the engine stops. The person's own documents stay open. Nothing here may be cut short, nor keep the engine running: a save that fails
     * is lost with the stop, as at a sign-out ([DocumentSession.close] closes regardless).
     */
    suspend fun stop() {
        withContext(main + NonCancellable) {
            leave(staying = null)
            engine.disable()
        }
    }

    /**
     * Opens item [itemUuid] of the open pack in its editor: an LZ/PZ in the diagram session, a route set in the route session. False when the
     * open pack has no such item, or it is a point set (shown on the map, edited by nobody, as on the web).
     */
    suspend fun openItem(itemUuid: String): Boolean = withContext(main) {
        val state = engine.open.value ?: return@withContext false
        val item = state.item(itemUuid) ?: return@withContext false
        val localId = PackRef.localId(state.uuid, itemUuid)
        when (item.kind) {
            "lz" -> diagrams.active.value?.id == localId || diagrams.open(localId)
            "route" -> routes.active.value?.id == localId || routes.open(localId)
            else -> false
        }
    }

    /**
     * A new LZ/PZ at [target] in the open pack, opened in the diagram session (usePackLz's `createItem`): called [name], or "LZ/PZ n" when that is
     * blank. Its local id; null when no pack is open or the target is not a position, or when the pack would not take it (and the person is told
     * why, [PackNotice.Refused]).
     */
    suspend fun createLz(target: DiagramTarget, name: String? = null): String? = withContext(main) {
        val state = engine.open.value ?: return@withContext null
        val uuid = PackSentences.packItemId("lz", UUID.randomUUID().toString())
        val localId = PackRef.localId(state.uuid, uuid)
        val diagram = DiagramNormalizer.fromTarget(DiagramJson.encode(target), mgrs = target.mgrs, id = localId) ?: return@withContext null
        val count = state.items.count { it.kind == "lz" }
        val op = PackSentences.newItemOp("lz", uuid, name, count, PackLz.lzItemData(diagram), engine.me.value?.name)
        if (!made(state.uuid, op)) return@withContext null
        diagrams.open(localId)
        localId
    }

    /**
     * A new, empty route set in the open pack, opened in the route session (usePackRoutes' `createItem`): called [name], or "ROUTES n" when that is
     * blank. Its local id; null when no pack is open, or when the pack would not take it (and the person is told why).
     */
    suspend fun createRouteSet(name: String? = null): String? = withContext(main) {
        val state = engine.open.value ?: return@withContext null
        val uuid = PackSentences.packItemId("route", UUID.randomUUID().toString())
        val localId = PackRef.localId(state.uuid, uuid)
        val data = JsonObject(linkedMapOf("version" to JsonPrimitive(1), "routes" to JsonArray(emptyList())))
        val count = state.items.count { it.kind == "route" }
        val op = PackSentences.newItemOp("route", uuid, name, count, data, engine.me.value?.name)
        if (!made(state.uuid, op)) return@withContext null
        routes.open(localId)
        localId
    }

    /**
     * Closes item [itemUuid] of the open pack in its editor, if it is open there; it stays in the pack (the web's "Close here"). Its last change
     * goes to the pack first ([DocumentSession.closeIfOpen]), and a save that fails is thrown once it is closed.
     */
    suspend fun closeItem(itemUuid: String) {
        withContext(main) {
            val pack = engine.open.value?.uuid ?: return@withContext
            val localId = PackRef.localId(pack, itemUuid)
            diagrams.closeIfOpen(localId)
            routes.closeIfOpen(localId)
        }
    }

    /**
     * Renames item [itemUuid] of the open pack for everyone in it, with the history's sentence (the web's rename dialog: the name trimmed and in
     * capitals, as every name in a pack is). Null once it is on its way; else why not: `bad_name` for a blank name or one past the server's 100
     * characters (never cut here: the person chose it), `same_name`, `missing` (no pack open, or no such item), or the engine's refusal
     * (`read_only`, `loading`, `paused`, …). An item open in an editor takes the new name from the pack, as it takes anyone's.
     */
    suspend fun renameItem(itemUuid: String, name: String): String? = withContext(main) {
        val state = engine.open.value ?: return@withContext MISSING
        val item = state.item(itemUuid) ?: return@withContext MISSING
        val to = trimJs(name).uppercase()
        if (to.isEmpty() || to.codePointCount(0, to.length) > MAX_NAME) return@withContext BAD_NAME
        if (to == item.name) return@withContext SAME_NAME
        val op = JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("item.rename"),
                "item" to JsonPrimitive(itemUuid),
                "name" to JsonPrimitive(to),
                "summary" to JsonPrimitive(PackSentences.renameSummary(engine.me.value?.name, item.name, to)),
            ),
        )
        engine.edit(state.uuid, listOf(op))
    }

    /**
     * Removes item [itemUuid] from the open pack, for everyone in it (the web's "Remove from pack"): closed in its editor first, so its last change
     * reaches the pack before it goes and nothing of it is kept as the person's own, then deleted with the history's sentence. Null once it is
     * on its way; else why not, as [renameItem] says it.
     */
    suspend fun removeItem(itemUuid: String): String? = withContext(main) {
        val state = engine.open.value ?: return@withContext MISSING
        val item = state.item(itemUuid) ?: return@withContext MISSING
        // What the pack would refuse is refused before anything is closed: closing first would set the item aside, and, paused, keep its last change
        // as the person's own, for a removal that then never happens.
        when {
            state.session == null -> return@withContext "loading"
            state.status == PackStatus.PAUSED -> return@withContext "paused"
            state.status == PackStatus.GONE -> return@withContext "gone"
            state.readOnly -> return@withContext "read_only"
        }
        closeItem(itemUuid)
        engine.edit(state.uuid, listOf(PackSentences.deleteItemOp(itemUuid, item.name, engine.me.value?.name)))
    }

    // [op] made in [pack], on the device once this returns true; false, with the person told why, when the pack would not take it.
    private suspend fun made(pack: String, op: JsonObject): Boolean {
        val refused = engine.edit(pack, listOf(op)) ?: return true
        tell(PackNotice.Refused(pack, null, refused))
        return false
    }

    // Closes what is open in the editors of a pack other than [staying] (any pack, for none): their last change goes to their pack first. Each
    // is closed whatever became of the other's save ([DocumentSession.close] always closes); the first save that failed is given back.
    private suspend fun leave(staying: String?): Exception? {
        var failed: Exception? = null
        suspend fun close(id: String?, session: DocumentSession<*>) {
            if (id == null || !leaving(id, staying)) return
            try {
                session.closeIfOpen(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failed == null) failed = e
            }
        }
        close(diagrams.active.value?.id, diagrams)
        close(routes.active.value?.id, routes)
        return failed
    }

    private fun leaving(id: String, staying: String?): Boolean = PackRef.parse(id)?.let { it.pack != staying } ?: false

    private fun tell(notice: PackNotice) {
        editors.tryEmit(notice)
    }

    companion object {
        /** No pack is open, or it has no such item. */
        const val MISSING = "missing"

        /** A name that is blank, or longer than the server takes. */
        const val BAD_NAME = "bad_name"

        /** The name it has already. */
        const val SAME_NAME = "same_name"

        /** The most characters a pack item's name may have (the server's column). */
        const val MAX_NAME = 100

        // JavaScript's trim, as the web's rename dialog trims: Unicode's spaces and the byte order mark go, the information separators stay.
        private fun trimJs(text: String): String = text.trim { (it.isWhitespace() && it !in '\u001C'..'\u001F') || it == '\uFEFF' }
    }
}
