package app.ezpztac.workspace

import app.ezpztac.data.DocumentStore
import app.ezpztac.data.OpenPack
import app.ezpztac.missionpacks.LzPackKind
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackLz
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PendingOp
import app.ezpztac.missionpacks.RoutePackKind
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramJson
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.RouteSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The open pack as the panels' tests set it: what it holds, and a log of what the panels asked of it. With [opener] set (a rig's session), an item opened or made
 * is really opened, read from the pack's state through [lzStore] or [routeStore], as the app's sessions read a pack's items.
 */
internal class FakeOpenPack : OpenPack {
    override val state = MutableStateFlow<PackState?>(null)
    val log = mutableListOf<String>()
    var opens = true
    var madeLz: String? = "pack:p:lz-new"
    var madeSet: String? = "pack:p:rt-new"

    /** Opens a local id in the right session; null: nothing is opened, only logged. */
    var opener: (suspend (String) -> Boolean)? = null

    /** Closes a local id in the right session, if it is open there. */
    var closer: (suspend (String) -> Unit)? = null

    /** Called as a set is made, before it is opened: a test's way of having something take the map's taps meanwhile. */
    var whileMaking: () -> Unit = {}

    /** What [removeItem] answers: null, it is on its way. */
    var removeRefusal: String? = null

    override suspend fun createLz(target: DiagramTarget, name: String?): String? {
        log += "createLz ${target.mgrs}"
        return madeLz
    }

    override suspend fun createRouteSet(name: String?): String? {
        log += "createRouteSet"
        val made = madeSet ?: return null
        val now = state.value ?: return made
        val ref = PackRef.parse(made) ?: return made
        state.value = now.copy(items = now.items + routes(ref.item, "ROUTES ${now.items.count { it.kind == "route" } + 1}", routes = 0))
        whileMaking()
        opener?.invoke(made)
        return made
    }

    override suspend fun openItem(itemUuid: String): Boolean {
        log += "open $itemUuid"
        if (!opens) return false
        val pack = state.value?.uuid ?: return opens
        return opener?.invoke(PackRef.localId(pack, itemUuid)) ?: opens
    }

    override suspend fun closeItem(itemUuid: String) {
        log += "close $itemUuid"
        state.value?.uuid?.let { closer?.invoke(PackRef.localId(it, itemUuid)) }
    }

    override suspend fun removeItem(itemUuid: String): String? {
        log += "remove $itemUuid"
        return removeRefusal
    }

    private fun itemFor(localId: String): PackItemView? {
        val ref = PackRef.parse(localId) ?: return null
        return state.value?.takeIf { it.uuid == ref.pack }?.item(ref.item)
    }

    /** The pack's LZ/PZs as a session reads them. */
    val lzStore = object : DocumentStore<Diagram> {
        override suspend fun open(uuid: String): Diagram? = itemFor(uuid)?.takeIf { it.kind == "lz" }?.let { LzPackKind().fromItem(uuid, it, null) }

        override suspend fun save(document: Diagram): Diagram = document
    }

    /** The pack's route sets as a session reads them. */
    val routeStore = object : DocumentStore<RouteSet> {
        override suspend fun open(uuid: String): RouteSet? = itemFor(uuid)?.takeIf { it.kind == "route" }?.let { RoutePackKind.fromItem(uuid, it, null) }

        override suspend fun save(document: RouteSet): RouteSet = document
    }

    companion object {
        /** Pack p, "OP DK", Colin (1) this person, with Sam (2) in it and a team, so 4 can see it. */
        fun session(readOnly: Boolean = false, status: String = "active", pending: List<PendingOp> = emptyList()) = PackSession(
            1,
            buildJsonObject {
                put("uuid", "p")
                put("name", "OP DK")
                put("status", status)
                put("role", "editor")
                put("audience_count", 4)
                put("team", buildJsonObject { put("id", 3) })
            },
            buildJsonArray {
                add(buildJsonObject { put("user_id", 1); put("name", "Colin McFadden") })
                add(buildJsonObject { put("user_id", 2); put("name", "Sam Bell") })
            },
            emptyMap(), emptyMap(), emptyList(), 10, pending, emptyList(), readOnly, null, false, emptyMap(),
        )

        /** An LZ/PZ item of the pack at [target], as the pack holds it. */
        fun lz(uuid: String, name: String, target: DiagramTarget, source: JsonObject? = null): PackItemView {
            val diagram = checkNotNull(DiagramNormalizer.fromTarget(DiagramJson.encode(target), mgrs = target.mgrs, id = "pack:p:$uuid"))
            return PackItemView(uuid, "lz", name, PackLz.lzItemData(diagram), buildJsonObject { put("source", source ?: JsonNull) })
        }

        /** A route set item of the pack with [routes] routes of [points] points each. */
        fun routes(uuid: String, name: String, routes: Int, points: Int = 3) = PackItemView(
            uuid, "route", name,
            buildJsonObject {
                put("version", 1)
                put(
                    "routes",
                    buildJsonArray {
                        repeat(routes) { r ->
                            add(
                                buildJsonObject {
                                    put("id", "sketch-$r")
                                    put("name", "ROUTE $r")
                                    put("points", buildJsonArray { repeat(points) { add(buildJsonObject { put("lat", 34.0); put("lon", -84.0) }) } })
                                },
                            )
                        }
                    },
                )
            },
            buildJsonObject { put("source", JsonNull) },
        )

        /** Someone here, with [item] open, and their pointer [at] on the map. */
        fun here(id: Int, name: String, item: String?, at: Double? = null) = buildJsonObject {
            put("user_id", id)
            put("name", name)
            put(
                "focus",
                buildJsonObject {
                    item?.let { put("item", it) }
                    at?.let { put("at", buildJsonArray { add(it); add(-84.0) }) }
                },
            )
        }

        fun open(items: List<PackItemView>, people: List<JsonObject> = emptyList(), session: PackSession? = session(), status: PackStatus = PackStatus.LIVE) =
            PackState("p", status, session, items, people, null)
    }
}
