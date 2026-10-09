package app.ezpztac.data

import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.missionpacks.PackLz
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSentences
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramJson
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.RouteSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

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
}
