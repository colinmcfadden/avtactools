package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where an edit made here stands: waiting, in the batch out, or taken by the server with its event not seen yet. */
public enum class PendingState { QUEUED, SENT, ACKED }

/**
 * An edit made here that the server has not confirmed. [op] carries the `client_op_id` it is sent under. [seq] is only
 * an acked one's: the event number its result named, null if it named none.
 */
public data class PendingOp(val op: JsonObject, val state: PendingState, val seq: Long? = null)

/** An edit the pack will never take, and why (`pack_finished`, `read_only`, `gone`, or what a refused batch was named). */
public data class DroppedOp(val op: JsonObject, val reason: String)

/**
 * One open mission pack as this device holds it: the web's session (packSession.js; docs/MISSION_PACKS.md §5). What the
 * server has confirmed ([confirmed], as of event [seq]), the edits made here it has not confirmed yet ([pending]), and what
 * the person sees ([view]: the pending edits applied over the confirmed items, in order).
 *
 * [pack] is the GET body less its items and members, kept current by pack events; [members] as the server listed them;
 * [info] each live confirmed item's body less its data; [order] the confirmed items in the order they were added. All of
 * it is JSON as the server sent it, never decoded into the server's types and written out again: the session builds some
 * of it from events (a member added has no `seen_at`), and a type would add nulls or refuse it.
 *
 * Every function in [PackSessions] returns a new session and changes nothing given. Like the web's, they make new objects
 * only where they change something: an item no event touched keeps its instance, so a caller can tell cheaply what changed
 * (and a store rewrite only that).
 */
public data class PackSession(
    val me: Int,
    val pack: JsonObject,
    val members: JsonArray,
    val confirmed: Map<String, PackItemState>,
    val info: Map<String, JsonObject>,
    val order: List<String>,
    val seq: Long,
    val pending: List<PendingOp>,
    val dropped: List<DroppedOp>,
    val readOnly: Boolean,
    val gone: String?,
    val diverged: Boolean,
    val view: Map<String, PackItemState>,
) {
    public val uuid: String? get() = PackSessions.text(pack["uuid"])
    public val status: String? get() = PackSessions.text(pack["status"])
    public val role: String? get() = PackSessions.text(pack["role"])
    public val liveUrl: String? get() = PackSessions.text(pack["live_url"])
    public val headSeq: Long? get() = PackSessions.seqOf(pack["head_seq"])
}

/** [edit]'s answer: [refused] is why nothing was taken, or null. */
public data class Edited(val session: PackSession, val refused: String?)

/** [PackSessions.nextBatch]'s answer: the session with the batch marked sent, and [batch], the body to POST (`{ops, base_seq}`). */
public data class NextBatch(val session: PackSession, val batch: JsonObject)

/** [PackSessions.receive]'s answer: [gap] means an event is missing before one given, and the caller fetches from `session.seq`. */
public data class Received(val session: PackSession, val gap: Boolean)

/** [PackSessions.batchAnswered]'s answer: [catchUp] means fetch events from `session.seq`. */
public data class Answered(val session: PackSession, val catchUp: Boolean)

/** This person's own version of an item a pack would not take their edits to. */
public data class ItemVersion(val uuid: String, val kind: String, val name: String, val data: JsonElement)

/**
 * The web's packSession.js, function by function, held to every scenario of `contracts/fixtures/packs/session.json`.
 *
 * The server's order is the truth. An edit made here shows at once; when the server's events arrive they are applied to
 * the confirmed items in their order, our own among them, and whatever is still pending is applied again on top. Two people
 * moving the same thing therefore both see the later one in the end.
 */
public object PackSessions {
    public const val MAX_BATCH: Int = 200

    // Events that change an item. Everything else in the log is about the pack itself.
    private val ITEM_EVENTS = setOf("item.create", "item.delete", "item.rename", "item.replace", "set", "patch", "upsert", "insert", "remove")

    /** A session from GET /api/packs/<uuid> (the web's openSession). [me] is the signed-in user's id. */
    public fun open(pack: JsonObject, me: Int): PackSession {
        val items = pack["items"]
        require(items is JsonArray) { "A pack's body lists its items" }
        val confirmed = LinkedHashMap<String, PackItemState>()
        val info = LinkedHashMap<String, JsonObject>()
        val order = ArrayList<String>()
        items.forEach { item ->
            require(item is JsonObject) { "A pack's item is an object" }
            val uuid = requireNotNull(text(item["uuid"])) { "A pack's item has a uuid" }
            confirmed[uuid] = PackItemState(text(item["kind"]).orEmpty(), text(item["name"]).orEmpty(), item["data"] ?: JsonNull, deleted = false)
            info[uuid] = JsonObject(item - "data")
            order += uuid
        }
        return withView(
            PackSession(
                me = me,
                pack = JsonObject(pack.filterKeys { it != "items" && it != "members" }),
                members = pack["members"] as? JsonArray ?: JsonArray(emptyList()),
                confirmed = confirmed,
                info = info,
                order = order,
                seq = seqOf(pack["head_seq"]) ?: 0,
                pending = emptyList(),
                dropped = emptyList(),
                readOnly = readOnlyFor(pack["role"], pack["status"]),
                gone = null,
                diverged = false,
                view = confirmed,
            ),
        )
    }

    /**
     * The server's copy again (after a gap too long to replay, or if this copy ever disagreed with the server), with the
     * edits still pending kept on top (the web's reloadSession). The copy holds every edit the server took up to its
     * head_seq, ours among them, so an acked one it has reached is done. A copy that is read-only is settled as an event
     * that made it so would be.
     */
    public fun reload(session: PackSession, pack: JsonObject): PackSession {
        val fresh = open(pack, session.me)
        return settle(withoutReached(fresh.copy(pending = session.pending, dropped = session.dropped)))
    }

    /**
     * Edits made here: shown at once, sent in order. [Edited.refused] is why nothing was taken: a pack that is gone or
     * read-only, or a malformed operation, which is a bug in the caller. [newId] names each operation taken, in order, and
     * is not called for a refused edit.
     */
    public fun edit(session: PackSession, ops: List<JsonElement>, newId: () -> String): Edited {
        if (session.gone != null) return Edited(session, "gone")
        if (session.readOnly) return Edited(session, "read_only")
        for (op in ops) {
            val invalid = PackOps.validate(op)
            if (invalid != null) return Edited(session, invalid)
            if (text((op as JsonObject)["type"]) !in PackOps.CLIENT_OP_TYPES) return Edited(session, "unknown_type")
        }
        val added = ops.map { op -> PendingOp(JsonObject((op as JsonObject) + ("client_op_id" to JsonPrimitive(newId()))), PendingState.QUEUED) }
        return Edited(withView(session.copy(pending = session.pending + added)), null)
    }

    /** The next batch to send, or null while one is out or nothing waits. */
    public fun nextBatch(session: PackSession): NextBatch? {
        if (session.pending.any { it.state == PendingState.SENT }) return null
        val queued = session.pending.indices.filter { session.pending[it].state == PendingState.QUEUED }.take(MAX_BATCH)
        if (queued.isEmpty()) return null
        val sending = queued.toHashSet()
        val pending = session.pending.mapIndexed { i, entry -> if (i in sending) entry.copy(state = PendingState.SENT) else entry }
        val batch = buildJsonObject {
            put("ops", JsonArray(queued.map { session.pending[it].op }))
            put("base_seq", session.seq)
        }
        // The view does not change: the same edits are pending, only their state is.
        return NextBatch(session.copy(pending = pending), batch)
    }

    /**
     * Events from the server (the live stream, a catch-up, a batch's answer), oldest first. An event without a number is
     * passed over: the server numbers every one, and the web would apply it and lose its own place.
     */
    public fun receive(session: PackSession, events: List<JsonObject>): Received {
        var next = session
        for (event in events) {
            val seq = seqOf(event["seq"]) ?: continue
            if (seq <= next.seq) continue                                                   // already have it
            if (seq > next.seq + 1) return Received(settle(withHead(next)), gap = true)
            next = if (text(event["type"]) in ITEM_EVENTS) applyItemEvent(next, event) else applyPackEvent(next, event)
            next = next.copy(seq = seq)
            val clientOpId = event["client_op_id"]
            if (Js.truthy(clientOpId)) next = next.copy(pending = next.pending.filter { !Js.strictEquals(it.op["client_op_id"], clientOpId) })
        }
        return Received(settle(withHead(next)), gap = false)
    }

    /** The answer to a batch (POST .../ops's 200 body): its events, everything after base_seq, are applied. */
    public fun batchAnswered(session: PackSession, answer: JsonObject): Answered {
        // What the results say the server took is acked before the page is read, so nothing on it (a finish or a removal, on
        // a page that ends there: has_more) can drop it.
        val received = receive(acked(session, answer["results"]), objects(answer["events"]))
        val after = withoutReached(received.session)
        val catchUp = received.gap || Js.truthy(answer["has_more"]) || after.pending.any { it.state == PendingState.ACKED }
        return Answered(withView(after), catchUp)
    }

    /**
     * A batch the server did not take. [failure] is what the refusal said ([PackFailure.of]), or status 0 when nothing came
     * back: sent again later, unchanged, which the server recognises by each operation's client_op_id. Only what the server
     * has not said it took is dropped.
     *
     * A batch whose answer was lost may have been taken. Sent again, it can be refused (the pack was finished, I was made a
     * viewer, or what was added to it is too large), and the refusal's `taken` names what of it the pack has: that is acked,
     * as an answer's results are, and only the rest is dropped. A server from before `taken` says nothing, and the whole
     * batch is dropped (§5, rule 7).
     */
    public fun batchFailed(given: PackSession, failure: PackFailure): PackSession {
        val status = failure.status
        // Read whatever the status (the server sends it on 403 pack_read_only, 413 and 423). One a reload has passed is the
        // pack's already, and its event will not come again.
        val session = withoutReached(acked(given, failure.taken))
        val sent = session.pending.filter { it.state == PendingState.SENT }
        val others = session.pending.filter { it.state != PendingState.SENT }

        // Sent again as it was, and the next answer decides: none came (the server may have taken it), or it was refused
        // before it was looked at (a 401 waits for the person to sign in again). Not settled here, in a pack seen read-only
        // either: the batch may have been taken, and only the server can say.
        if (status == 0 || status == 401 || status == 429 || status >= 500) {
            return withView(session.copy(pending = session.pending.map { if (it.state == PendingState.SENT) it.copy(state = PendingState.QUEUED) else it }))
        }
        // From here the pack takes nothing more of ours, but what it took already (acked) stays.
        if (status == 423) {
            val pack = LinkedHashMap(session.pack)
            pack["status"] = JsonPrimitive("finished")
            // Each the body has, null too: whoever finished it may have deleted their account since, and when still holds.
            failure.finishedBy?.let { pack["finished_by"] = it }
            failure.finishedAt?.let { pack["finished_at"] = it }
            return dropUntaken(session.copy(pack = JsonObject(pack), readOnly = true), "pack_finished")
        }
        if (status == 403 && failure.code == "pack_read_only") {
            return dropUntaken(session.copy(pack = JsonObject(session.pack + ("role" to JsonPrimitive("viewer"))), readOnly = true), "read_only")
        }
        if (status == 404 || status == 403) {
            return dropUntaken(session.copy(gone = if (status == 404) "not_found" else "forbidden"), "gone")
        }
        // 400 (a malformed operation: our bug) or 413 (too large): this batch is not taken; the rest still goes, unless the
        // pack turned read-only while it was out, when the rest is dropped behind it, in order.
        val reason = failure.reason?.takeIf { it.isNotEmpty() } ?: failure.code?.takeIf { it.isNotEmpty() } ?: "http_$status"
        return settle(session.copy(pending = others, dropped = session.dropped + sent.map { DroppedOp(it.op, reason) }))
    }

    /** Every edit not yet taken given up for [reason] (the person who made them signed out). Taken ones stay. */
    public fun abandon(session: PackSession, reason: String): PackSession =
        if (session.pending.any(::untaken)) dropUntaken(session, reason) else session

    /**
     * What the person sees: live items in the order they were added, then the ones made here in the order JavaScript lists
     * the view's keys, each with what the pack knows about it (its info, or `{uuid, pendingCreate: true}` for one the
     * server has not confirmed yet) and the view's kind, name, data and deleted over that. `data` is the view's own
     * instance.
     */
    public fun visibleItems(session: PackSession): List<JsonObject> {
        val listed = session.order.toHashSet()
        val uuids = session.order + Js.orderedKeys(session.view.keys).filter { it !in listed }
        return uuids.filter { session.view[it]?.deleted == false }.map { uuid ->
            val item = session.view.getValue(uuid)
            val fields = LinkedHashMap<String, JsonElement>(
                session.info[uuid] ?: JsonObject(mapOf("uuid" to JsonPrimitive(uuid), "pendingCreate" to JsonPrimitive(true))),
            )
            fields["kind"] = JsonPrimitive(item.kind)
            fields["name"] = JsonPrimitive(item.name)
            fields["data"] = item.data
            fields["deleted"] = JsonPrimitive(item.deleted)
            fields["uuid"] = JsonPrimitive(uuid)
            JsonObject(fields)
        }
    }

    /**
     * The edits this person made that the pack did not take (it was finished, or they were made a viewer, or removed, while
     * the edits were on their way: [PackSession.dropped], in the order they were made), as their own version of each item
     * they touched: the pack's confirmed item with those edits applied in order. What cannot apply any more (its target
     * gone) is left out, as the pack would have left it. In the order the items were first touched.
     */
    public fun droppedVersions(session: PackSession): List<ItemVersion> {
        val versions = LinkedHashMap<String, PackItemState?>()
        session.dropped.forEach { (op) ->
            val uuid = text(op["item"])?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (uuid !in versions) versions[uuid] = session.confirmed[uuid]?.takeIf { !it.deleted }
            // An item made here and never taken starts from its own item.create.
            val result = PackOps.apply(versions[uuid]?.let { mapOf(uuid to it) } ?: emptyMap(), op)
            if (result.status == OpStatus.APPLIED) versions[uuid] = result.items[uuid]
        }
        return versions.mapNotNull { (uuid, item) -> item?.takeIf { !it.deleted }?.let { ItemVersion(uuid, it.kind, it.name, it.data) } }
    }

    // -- Android's own: the web holds a session only in memory -------------------------------------------------------------

    /**
     * Whether a session read back from the device had a batch out (sent) or edits the server took (acked) when the app
     * stopped. Ask it of the stored session itself, before [restored], which turns sent back into queued. When it is true,
     * the engine first catches up from the stored [PackSession.seq] with [receive], before any reload: the log has the
     * batch's own events ahead of a finish or a role change, and they confirm it. A reload first, into a pack that has
     * turned read-only since, would drop an edit the pack has as if it had never gone (session.json's second known
     * exception: on the web it takes a lost answer, here it would follow every restart with a batch out).
     */
    public fun hasSentOrAcked(session: PackSession): Boolean = session.pending.any { it.state != PendingState.QUEUED }

    /**
     * A session read back from the device, ready to send: the batch that was out when the app stopped got no answer here,
     * so it goes again, unchanged (the server answers a repeat from its log), as after any lost answer. The engine applies
     * it only just before that resend, after the catch-up [hasSentOrAcked] asks for: until then the batch stays sent, so
     * a reload that settles the session in a pack now read-only holds it for the server's answer rather than dropping it.
     */
    public fun restored(session: PackSession): PackSession = batchFailed(session, PackFailure(status = 0))

    /**
     * The session without the dropped edits named by their client_op_id, once they have been kept in the library or the
     * person let them go. The web keeps them for as long as the page lives; here they are kept on the device until then.
     */
    public fun withoutDropped(session: PackSession, clientOpIds: Set<String>): PackSession {
        if (session.dropped.none { text(it.op["client_op_id"]) in clientOpIds }) return session
        return session.copy(dropped = session.dropped.filter { text(it.op["client_op_id"]) !in clientOpIds })
    }

    /**
     * The whole session as JSON, as packs/session.json writes it. A pending edit's `seq` is there only once it is acked; a
     * key a JSON value never had stays out.
     */
    public fun toJson(session: PackSession): JsonObject = buildJsonObject {
        put("me", session.me)
        put("pack", session.pack)
        put("members", session.members)
        put("confirmed", JsonObject(session.confirmed.mapValues { itemJson(it.value) }))
        put("info", JsonObject(session.info))
        put("order", JsonArray(session.order.map(::JsonPrimitive)))
        put("seq", session.seq)
        put("pending", JsonArray(session.pending.map(::pendingJson)))
        put("dropped", JsonArray(session.dropped.map { JsonObject(mapOf("op" to it.op, "reason" to JsonPrimitive(it.reason))) }))
        put("readOnly", session.readOnly)
        put("gone", session.gone)
        put("diverged", session.diverged)
        put("view", JsonObject(session.view.mapValues { itemJson(it.value) }))
    }

    // -- How it is done ----------------------------------------------------------------------------------------------------

    private fun itemJson(item: PackItemState): JsonObject = JsonObject(
        mapOf("kind" to JsonPrimitive(item.kind), "name" to JsonPrimitive(item.name), "data" to item.data, "deleted" to JsonPrimitive(item.deleted)),
    )

    private fun pendingJson(entry: PendingOp): JsonObject = buildJsonObject {
        put("op", entry.op)
        put("state", entry.state.name.lowercase())
        if (entry.state == PendingState.ACKED) put("seq", entry.seq)
    }

    internal fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    // An event number: a whole number, as the server writes every one.
    internal fun seqOf(value: JsonElement?): Long? = Js.numberOf(value)?.takeIf { it.isFinite() && it == Math.rint(it) }?.toLong()

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun readOnlyFor(role: JsonElement?, status: JsonElement?): Boolean = text(status) == "finished" || text(role) == "viewer"

    // Every pending edit applied over the confirmed items, in order. With none, the view is the confirmed items themselves.
    private fun withView(session: PackSession): PackSession =
        session.copy(view = session.pending.fold(session.confirmed) { items, entry -> PackOps.apply(items, entry.op).items })

    // JavaScript's `key: value` in an object literal or a spread: a value that is undefined (a key the source did not have)
    // leaves the key out of the JSON, so here it is taken away.
    private fun MutableMap<String, JsonElement>.assign(key: String, value: JsonElement?) {
        if (value == null) remove(key) else this[key] = value
    }

    // `count + 1` for a count the server sends as an integer. Anything else counts as 0, as null does (the web would give
    // NaN for a missing one, or join text).
    private fun increment(value: JsonElement?): JsonPrimitive = number((Js.numberOf(value) ?: 0.0) + 1)

    private fun number(value: Double): JsonPrimitive =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 9.007199254740992E15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun applyPackEvent(session: PackSession, event: JsonObject): PackSession {
        val op = event["op"] as? JsonObject ?: JsonObject(emptyMap())
        val me = JsonPrimitive(session.me)
        val pack = LinkedHashMap(session.pack)
        var members = session.members
        var gone = session.gone
        var readOnly = session.readOnly
        val type = text(event["type"])
        fun isMember(member: JsonElement, user: JsonElement?) = Js.strictEquals((member as? JsonObject)?.get("user_id"), user)
        fun withRole(member: JsonElement, role: JsonElement?) = JsonObject(LinkedHashMap((member as? JsonObject).orEmpty()).apply { assign("role", role) })
        when (type) {
            "pack.update" -> {
                op["name"]?.let { pack["name"] = it }
                op["description"]?.let { pack["description"] = it }
            }
            "pack.share" -> pack["team"] = if (op["team_id"].let { it == null || it is JsonNull }) {
                JsonNull
            } else {
                // A team that is not an object (the server's always is, or null) is no team to keep the name and size of.
                val team = LinkedHashMap((pack["team"] as? JsonObject).orEmpty())
                team["id"] = op.getValue("team_id")
                team.assign("role", op["team_role"])
                JsonObject(team)
            }
            "pack.finish" -> {
                pack["status"] = JsonPrimitive("finished")
                pack.assign("finished_at", event["created_at"])
                pack.assign("finished_by", event["actor"])
            }
            "pack.reopen" -> {
                pack["status"] = JsonPrimitive("active")
                pack["finished_at"] = JsonNull
                pack["finished_by"] = JsonNull
            }
            "pack.transfer" -> {
                pack["owner"] = JsonObject(LinkedHashMap<String, JsonElement>().apply { assign("id", op["user_id"]); assign("name", op["name"]) })
                members = JsonArray(
                    members.map { m ->
                        when {
                            isMember(m, op["user_id"]) -> withRole(m, JsonPrimitive("owner"))
                            text((m as? JsonObject)?.get("role")) == "owner" -> withRole(m, JsonPrimitive("editor"))
                            else -> m
                        }
                    },
                )
                if (Js.strictEquals(op["user_id"], me)) pack["role"] = JsonPrimitive("owner")
                else if (text(pack["role"]) == "owner") pack["role"] = JsonPrimitive("editor")
            }
            "member.add", "member.join" -> if (members.none { isMember(it, op["user_id"]) }) {
                val added = LinkedHashMap<String, JsonElement>()
                added.assign("user_id", op["user_id"])
                added.assign("name", op["name"])
                added["email"] = JsonPrimitive("")
                added.assign("role", op["role"])
                added.assign("added_at", event["created_at"])
                members = JsonArray(members + JsonObject(added))
                pack["member_count"] = increment(pack["member_count"].takeIf(Js::truthy))
            }
            "member.role" -> {
                members = JsonArray(members.map { if (isMember(it, op["user_id"])) withRole(it, op["role"]) else it })
                if (Js.strictEquals(op["user_id"], me)) pack.assign("role", op["role"])
            }
            "member.remove" -> {
                members = JsonArray(members.filter { !isMember(it, op["user_id"]) })
                val count = pack["member_count"].takeIf(Js::truthy)?.let(Js::numberOf) ?: 1.0
                pack["member_count"] = number(maxOf(0.0, count - 1))
                // Still in the pack through a shared team, perhaps; the next request says.
                if (Js.strictEquals(op["user_id"], me) && !Js.truthy(pack["team"])) gone = "removed"
            }
            else -> Unit                                    // pack.create, invites, and types this version does not know
        }
        if (type == "pack.finish" || type == "pack.reopen" || type == "member.role" || type == "pack.transfer") {
            readOnly = readOnlyFor(pack["role"], pack["status"])
        }
        return session.copy(pack = JsonObject(pack), members = members, gone = gone, readOnly = readOnly)
    }

    // An item.replace is an update from the original, which the server lets only whoever copied the item in make, and only
    // of a copy that names its original. Afterwards the source says what the server would then say (pack_support.source_body).
    // To them the original is the same again, and while it is the same the server counts nothing an update would replace:
    // pack_changes and last_pack_change are null. The event does not say when the original last changed
    // (original_updated_at), which is not known here (null) until the pack is loaded again. Everyone else is never told
    // anything about another person's original: all four are null.
    private fun replacedSource(session: PackSession, source: JsonElement?, event: JsonObject): JsonElement? {
        if (!Js.truthy(source)) return source
        val actor = event["actor"] as? JsonObject
        val mine = Js.strictEquals(actor?.get("id"), JsonPrimitive(session.me)) && Js.truthy((source as? JsonObject)?.get("uuid"))
        val fields = LinkedHashMap((source as? JsonObject).orEmpty())
        fields.assign("revision", (event["op"] as? JsonObject)?.get("source_revision"))
        fields["original"] = if (mine) JsonPrimitive("same") else JsonNull
        fields["original_updated_at"] = JsonNull
        fields["pack_changes"] = JsonNull
        fields["last_pack_change"] = JsonNull
        return JsonObject(fields)
    }

    private fun applyItemEvent(session: PackSession, event: JsonObject): PackSession {
        if (text(event["status"]) != "applied") return session
        val op = event["op"] ?: JsonNull
        val result = PackOps.apply(session.confirmed, op)
        if (result.status != OpStatus.APPLIED) return session.copy(diverged = true)
        // It applied, so it is a well-formed operation on this item.
        val uuid = text((op as JsonObject)["item"]).orEmpty()
        val info = LinkedHashMap(session.info)
        var order = session.order
        val item = result.items.getValue(uuid)
        val type = text(event["type"])
        if (type == "item.create") {
            val given = op["source"]
            val source = if (Js.truthy(given)) JsonObject((given as? JsonObject).orEmpty() + ("original" to JsonNull)) else JsonNull
            info[uuid] = JsonObject(
                LinkedHashMap<String, JsonElement>().apply {
                    put("uuid", JsonPrimitive(uuid))
                    put("kind", JsonPrimitive(item.kind))
                    put("name", JsonPrimitive(item.name))
                    put("revision", JsonPrimitive(1))
                    assign("seq", event["seq"])
                    assign("created_by", event["actor"])
                    assign("updated_by", event["actor"])
                    assign("created_at", event["created_at"])
                    assign("updated_at", event["created_at"])
                    put("source", source)
                },
            )
            order = order + uuid
        } else if (type == "item.delete") {
            info.remove(uuid)
            order = order.filter { it != uuid }
        } else {
            val before = info[uuid]
            if (before != null) {
                val fields = LinkedHashMap(before)
                fields["name"] = JsonPrimitive(item.name)
                fields["revision"] = increment(before["revision"])
                fields.assign("seq", event["seq"])
                fields.assign("updated_by", event["actor"])
                fields.assign("updated_at", event["created_at"])
                fields.assign("source", if (type == "item.replace") replacedSource(session, before["source"], event) else before["source"])
                info[uuid] = JsonObject(fields)
            }
        }
        return session.copy(confirmed = result.items, info = info, order = order)
    }

    // The pack's head_seq follows the newest event applied here, so what is keyed on it (the History) keeps up.
    private fun withHead(session: PackSession): PackSession {
        val head = session.pack["head_seq"]
        val known = if (head == null || head is JsonNull) 0.0 else Js.numberOf(head) ?: Double.NaN
        return if (session.seq > known) session.copy(pack = JsonObject(session.pack + ("head_seq" to JsonPrimitive(session.seq)))) else session
    }

    // What may be dropped: the edits the server has not said it took. One it took (acked) is the pack's, whatever happens to
    // the pack after, so it is never offered back as the person's own; it stays until it is confirmed.
    private fun untaken(entry: PendingOp): Boolean = entry.state == PendingState.QUEUED || entry.state == PendingState.SENT

    // Dropped in the order they were made (pending's order), all at once: droppedVersions applies them in that order.
    private fun dropUntaken(session: PackSession, reason: String): PackSession = withView(
        session.copy(
            pending = session.pending.filterNot(::untaken),
            dropped = session.dropped + session.pending.filter(::untaken).map { DroppedOp(it.op, reason) },
        ),
    )

    // A pack that turned read-only (finished, or our role lowered) takes nothing more, so what is queued is dropped. Not
    // while a batch is out: the server decides only when the batch reaches the pack, so it may take it yet (it took it
    // before the finish, or takes it after a reopen). Its answer settles it: a refusal drops it with what is queued behind
    // it, a 200 acks it and what is queued is dropped then. Dropping the queued edits first would put them before older ones
    // in `dropped`, and my version would end on the older value. A pack that is gone drops the batch out too: the client
    // stops, and nothing hears its answer.
    private fun settle(session: PackSession): PackSession {
        val waiting = session.pending.any { it.state == PendingState.SENT }
        if (session.gone == null && (!session.readOnly || waiting)) return withView(session)
        if (session.pending.none(::untaken)) return withView(session)
        val reason = when {
            session.gone != null -> "gone"
            text(session.pack["status"]) == "finished" -> "pack_finished"
            else -> "read_only"
        }
        return dropUntaken(session, reason)
    }

    // An edit the server took is in `confirmed` once seq has reached the event its result named, whether or not that event
    // was applied here: a reload, or events that came before the batch's answer, can move seq past it, and receive passes
    // over what it already has. Left pending it would wait for an event that never comes and be applied over the view for
    // good, over a later change to the same field too. One whose result named no seq waits for its event.
    private fun withoutReached(session: PackSession): PackSession {
        fun reached(entry: PendingOp) = entry.state == PendingState.ACKED && entry.seq != null && entry.seq <= session.seq
        return if (session.pending.any(::reached)) session.copy(pending = session.pending.filterNot(::reached)) else session
    }

    // What the server says it took of the batch out ([{client_op_id, seq, status, reason}]: an answer's results, or a
    // refusal's `taken`) is acked: held until its event comes or seq reaches the event it names (a skipped one too: its
    // event changes nothing), and never sent again or dropped. Only the batch out ("sent") is read: a name that is not in it
    // (an edit still queued, which the server has never seen, or one an earlier answer acked) is not about this batch and is
    // left as it was. Anything but a list says nothing.
    private fun acked(session: PackSession, results: JsonElement?): PackSession {
        if (results !is JsonArray || results.isEmpty()) return session
        // By client_op_id, the last result for one winning, as a JavaScript Map built from the list does. Every id made here
        // is text, so a result naming one any other way names none of ours.
        val said = LinkedHashMap<String, JsonObject>()
        results.forEach { result ->
            if (result is JsonObject) text(result["client_op_id"])?.let { said[it] = result }
        }
        return session.copy(
            pending = session.pending.map { entry ->
                val result = if (entry.state == PendingState.SENT) text(entry.op["client_op_id"])?.let(said::get) else null
                if (result != null) entry.copy(state = PendingState.ACKED, seq = seqOf(result["seq"])) else entry
            },
        )
    }
}
