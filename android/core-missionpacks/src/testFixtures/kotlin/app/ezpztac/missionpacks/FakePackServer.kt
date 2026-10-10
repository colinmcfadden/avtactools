package app.ezpztac.missionpacks

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.OtherAccountException
import app.ezpztac.network.PackLiveConnection
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An in-memory mission-pack server with the real one's rules (`backend/routes/pack_routes.py`: `get_pack`, `list_events`,
 * `apply_ops`), for trying the client against failures the real one will not produce on demand: an answer lost after the server
 * applied the batch, a refusal, a pack finished or a person removed while edits were on their way.
 *
 * Its operation stream checks a batch as the server does and in the same order (400 `invalid_batch`, 400 `invalid_op` with its
 * `index` and `reason`, then 404, then under the pack's lock 403 `pack_read_only` and 423 `pack_finished`, each with `taken`), answers
 * an operation it has already logged from the log, applies the rest with [PackOps] as `pack_ops.py` does, rolls the whole batch back
 * with a 413 when an item grows past [maxItemBytes], and pages the log at [pageSize]. Refusals are the exceptions core-network
 * raises, with the body the server writes.
 *
 * One thread, as the tests drive it. The device it answers is signed in as [signedIn] (any account when null) unless [signedOut].
 */
public class FakePackServer(public val liveUrl: String? = null) : PackApi {
    /** Which call, for [failNext] and [onCall]. */
    public enum class Call { PACK, EVENTS, OPS }

    private class Item(
        val uuid: String,
        val kind: String,
        var name: String,
        var data: JsonElement,
        var deleted: Boolean,
        var revision: Int,
        var seq: Long,
        val createdBy: Int,
        var updatedBy: Int,
        val createdAt: String,
        var updatedAt: String,
    )

    private class Pack(val uuid: String, var name: String, val owner: Int, val createdAt: String) {
        var status = "active"
        var finishedAt: String? = null
        var finishedBy: Int? = null
        var deleted = false
        val members = LinkedHashMap<Int, String>()
        val items = LinkedHashMap<String, Item>()
        val events = ArrayList<JsonObject>()
        var headSeq = 0L
        var updatedAt = createdAt
    }

    private val names = HashMap<Int, String>()
    private val featureOff = HashSet<Int>()
    private val packs = HashMap<String, Pack>()
    private val failures = HashMap<Call, ArrayDeque<Throwable>>()
    private var clock = 0
    private var othersOps = 0

    /** The account the device is signed in as; a call made for anyone else is refused before it is sent. Null: whoever asks. */
    public var signedIn: Int? = null

    /** Nobody is signed in on the device: every call ends the session. */
    public var signedOut: Boolean = false

    /** No connection: every call fails before it leaves the device. */
    public var offline: Boolean = false

    /** Apply the next N batches, then lose the answer: the server took them and the device never heard. */
    public var loseAnswers: Int = 0

    /** The most events one page of the log, or one answer, carries (the server's MAX_PAGE). */
    public var pageSize: Int = 500

    /** The largest an item may grow to, as compact JSON (the server's 5 MB). */
    public var maxItemBytes: Int = 5 * 1024 * 1024

    /** Pass every event to the open streams that have been welcomed, as the live service does. */
    public var announce: Boolean = true

    /** Runs when a call reaches the server, before it is answered: a hook to hold a call in flight, or change things meanwhile. */
    public var onCall: (suspend (Call) -> Unit)? = null

    /**
     * Runs once the server has done what a call asks, before it answers: a hook to hold the answer (and the device's batch out) or
     * lose it, after the server took the batch.
     */
    public var onAnswer: (suspend (Call) -> Unit)? = null

    /** Every call that reached the server: `pack p-1`, `events p-1 since=3`, `events p-1 since=3 background`, `ops p-1 base=3 [op-1]`. */
    public val calls: MutableList<String> = mutableListOf()

    /** Every call made, those refused on the device included (no connection, signed out, someone else signed in). */
    public val attempts: MutableList<String> = mutableListOf()

    /** Every batch that reached the server, as it was sent. */
    public val batches: MutableList<JsonObject> = mutableListOf()

    /** Every live stream opened, in order. */
    public val sockets: MutableList<FakeLive> = mutableListOf()

    // -- Setting the scene -------------------------------------------------------------------------------------------------

    /** Someone with an account; Mission Packs on unless [missionPacks] is false. */
    public fun person(id: Int, name: String, missionPacks: Boolean = true) {
        names[id] = name
        setFeature(id, missionPacks)
    }

    /** Mission Packs turned on or off for [id], as an admin does: off, every pack call is refused with 403 `feature_disabled`. */
    public fun setFeature(id: Int, on: Boolean) {
        if (on) featureOff -= id else featureOff += id
    }

    /** A new pack owned by [owner], with [members] beside them (id to role), logged as the server logs it. */
    public fun createPack(uuid: String, name: String, owner: Int, members: Map<Int, String> = emptyMap()) {
        val pack = Pack(uuid, name, owner, now())
        packs[uuid] = pack
        pack.members[owner] = "owner"
        append(pack, owner, obj("type" to text("pack.create"), "name" to text(name), "team_id" to JsonNull), "${who(owner)} created the pack.")
        members.forEach { (user, role) -> addMember(uuid, user, role, owner) }
    }

    /** [ops] made by [actor] from another device, as their client sends them: refused as the server would refuse them. */
    public fun elsewhere(pack: String, actor: Int, vararg ops: JsonObject): JsonObject {
        val sent = ops.map { op -> JsonObject(op + ("client_op_id" to text("u$actor-op-${++othersOps}"))) }
        return apply(visible(pack, actor), actor, sent, baseSeq = null)
    }

    public fun finish(pack: String, by: Int) {
        val p = live(pack, by)
        p.status = "finished"
        val event = append(p, by, obj("type" to text("pack.finish")), "${who(by)} finished the pack.")
        p.finishedAt = (event["created_at"] as JsonPrimitive).content
        p.finishedBy = by
        announce(p, listOf(event))
    }

    public fun reopen(pack: String, by: Int) {
        val p = live(pack, by)
        p.status = "active"
        p.finishedAt = null
        p.finishedBy = null
        announce(p, listOf(append(p, by, obj("type" to text("pack.reopen")), "${who(by)} reopened the pack.")))
    }

    public fun addMember(pack: String, user: Int, role: String, by: Int) {
        val p = live(pack, by)
        p.members[user] = role
        val op = obj("type" to text("member.add"), "user_id" to JsonPrimitive(user), "name" to text(who(user)), "role" to text(role))
        announce(p, listOf(append(p, by, op, "${who(by)} added ${who(user)}.")))
    }

    public fun setRole(pack: String, user: Int, role: String, by: Int) {
        val p = live(pack, by)
        p.members[user] = role
        val op = obj("type" to text("member.role"), "user_id" to JsonPrimitive(user), "name" to text(who(user)), "role" to text(role))
        announce(p, listOf(append(p, by, op, "${who(by)} made ${who(user)} ${role}.")))
    }

    /** [user] taken out of the pack; the live service then closes their streams with 4404, as it does once access is refused. */
    public fun remove(pack: String, user: Int, by: Int) {
        val p = live(pack, by)
        p.members.remove(user)
        val op = obj("type" to text("member.remove"), "user_id" to JsonPrimitive(user), "name" to text(who(user)))
        announce(p, listOf(append(p, by, op, "${who(by)} removed ${who(user)}.")))
        sockets.filter { it.pack == pack && it.user == user }.forEach { it.drop(4404) }
    }

    /** The pack deleted: a 404 for everyone, and its streams closed with 4410. */
    public fun deletePack(pack: String) {
        packs.getValue(pack).deleted = true
        sockets.filter { it.pack == pack }.forEach { it.drop(4410) }
    }

    /** The next call of [call] fails with [error] once it has reached the server, before the server does anything. */
    public fun failNext(call: Call, error: Throwable) {
        failures.getOrPut(call) { ArrayDeque() }.addLast(error)
    }

    /** A refusal as core-network raises it: [status], [code] and the server's body, with [extra] beside them. */
    public fun refusal(status: Int, code: String, vararg extra: Pair<String, JsonElement>): ApiException {
        val body = obj("error" to text("Refused ($code)."), "code" to text(code), *extra)
        return ApiException(status, code, "Refused ($code).", body = body)
    }

    // -- Looking at it -----------------------------------------------------------------------------------------------------

    public fun headSeq(pack: String): Long = packs.getValue(pack).headSeq

    /** The pack's log, oldest first, as `GET …/events` gives it. */
    public fun log(pack: String): List<JsonObject> = packs.getValue(pack).events.toList()

    /** Item [item]'s data as the server holds it; null if it has none or it was deleted. */
    public fun data(pack: String, item: String): JsonElement? = packs.getValue(pack).items[item]?.takeIf { !it.deleted }?.data

    /** Item [item]'s name as the server holds it. */
    public fun name(pack: String, item: String): String? = packs.getValue(pack).items[item]?.takeIf { !it.deleted }?.name

    public fun status(pack: String): String = packs.getValue(pack).status

    // -- The routes --------------------------------------------------------------------------------------------------------

    override suspend fun getPack(uuid: String, asUser: Int): JsonObject {
        reach(Call.PACK, asUser, "pack $uuid")
        val pack = visible(uuid, asUser)
        return body(pack, asUser).also { onAnswer?.invoke(Call.PACK) }
    }

    override suspend fun events(uuid: String, since: Long, limit: Int, background: Boolean, asUser: Int): JsonObject {
        reach(Call.EVENTS, asUser, "events $uuid since=$since" + if (background) " background" else "")
        val pack = visible(uuid, asUser)
        if (since < 0 || limit < 1) throw refusal(400, "invalid_cursor")
        val cap = minOf(limit, pageSize)
        val after = pack.events.filter { seqOf(it) > since }
        val page = after.take(cap)
        return obj(
            "events" to JsonArray(page),
            "cursor" to JsonPrimitive(page.lastOrNull()?.let(::seqOf) ?: since),
            "has_more" to JsonPrimitive(after.size > cap),
            "head_seq" to JsonPrimitive(pack.headSeq),
        ).also { onAnswer?.invoke(Call.EVENTS) }
    }

    override suspend fun sendOps(uuid: String, batch: JsonObject, asUser: Int): JsonObject {
        val ids = (batch["ops"] as? JsonArray)?.map { ((it as? JsonObject)?.get("client_op_id") as? JsonPrimitive)?.content }
        reach(Call.OPS, asUser, "ops $uuid base=${batch["base_seq"]} $ids", batch)
        val ops = batch["ops"]
        if (ops !is JsonArray || ops.size !in 1..MAX_BATCH) throw refusal(400, "invalid_batch")
        val base = batch["base_seq"]
        val baseSeq = when {
            base == null || base is JsonNull -> null
            base is JsonPrimitive && !base.isString && base.content.toLongOrNull()?.takeIf { it >= 0 } != null -> base.content.toLong()
            else -> throw refusal(400, "invalid_batch")
        }
        val seen = HashSet<String>()
        ops.forEachIndexed { index, op ->
            fun malformed(reason: String): Nothing = throw refusal(400, "invalid_op", "index" to JsonPrimitive(index), "reason" to text(reason))
            if (op !is JsonObject) malformed("bad_op")
            val id = (op["client_op_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || !CLIENT_OP_ID.matches(id) || !seen.add(id)) malformed("bad_client_op_id")
            if ((op["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content !in PackOps.CLIENT_OP_TYPES) malformed("unknown_type")
            PackOps.validate(op)?.let { malformed(it) }
            val summary = op["summary"]
            if (summary != null && !(summary is JsonPrimitive && summary.isString)) malformed("bad_summary")
        }
        val pack = visible(uuid, asUser)
        val answer = apply(pack, asUser, ops.map { it as JsonObject }, baseSeq)
        onAnswer?.invoke(Call.OPS)
        if (loseAnswers > 0) {
            loseAnswers--
            throw NetworkException("connection reset", requestMayHaveBeenSent = true)
        }
        return answer
    }

    override suspend fun openLive(liveUrl: String, uuid: String, scope: CoroutineScope, asUser: Int): PackLiveConnection? {
        if (signedOut || signedIn?.let { it != asUser } == true) return null
        val socket = FakeLive(uuid, liveUrl, asUser)
        sockets += socket
        // What the service does before its welcome: it asks the API whether this person may see the pack.
        val pack = packs[uuid]
        when {
            offline -> socket.drop(1006)
            asUser in featureOff -> socket.drop(4403)
            pack == null || pack.deleted || asUser !in pack.members -> socket.drop(4404)
        }
        return socket
    }

    // -- How it is done ----------------------------------------------------------------------------------------------------

    private suspend fun reach(call: Call, asUser: Int, line: String, batch: JsonObject? = null) {
        attempts += line
        // Refused on the device, before anything is sent.
        if (offline) throw NetworkException("offline", requestMayHaveBeenSent = false)
        if (signedOut) throw SessionEndedException(SignedOutReason.SESSION_ENDED, null, "Signed out.")
        signedIn?.let { if (it != asUser) throw OtherAccountException("Signed in as someone else.") }
        calls += line
        batch?.let { batches += it }
        onCall?.invoke(call)
        failures[call]?.removeFirstOrNull()?.let { throw it }
        if (asUser in featureOff) throw refusal(403, "feature_disabled")
    }

    private fun visible(uuid: String, asUser: Int): Pack {
        val pack = packs[uuid]
        if (pack == null || pack.deleted || asUser !in pack.members) throw refusal(404, "pack_not_found")
        return pack
    }

    private fun live(uuid: String, user: Int): Pack = packs[uuid]?.takeIf { !it.deleted } ?: error("No pack $uuid for $user")

    // pack_routes.apply_ops, under the pack's lock.
    private fun apply(pack: Pack, actor: Int, ops: List<JsonObject>, baseSeq: Long?): JsonObject {
        val ids = ops.map { textOf(it["client_op_id"]) }.toSet()
        val done = pack.events.filter { textOf(it["client_op_id"]) in ids }.associateBy { textOf(it["client_op_id"]) }
        val taken = JsonArray(done.values.sortedBy(::seqOf).filter { actorOf(it) == actor }.map(::result))
        if (pack.members[actor] !in EDIT_ROLES) throw refusal(403, "pack_read_only", "taken" to taken)
        if (pack.status == "finished") {
            throw refusal(
                423, "pack_finished",
                "finished_at" to (pack.finishedAt?.let(::text) ?: JsonNull),
                "finished_by" to (pack.finishedBy?.let(::person) ?: JsonNull),
                "taken" to taken,
            )
        }
        val headBefore = pack.headSeq
        val logged = pack.events.size
        var state: Map<String, PackItemState> = pack.items.mapValues { (_, item) -> PackItemState(item.kind, item.name, item.data, item.deleted) }
        val results = ArrayList<JsonObject>()
        val created = ArrayList<JsonObject>()
        val applied = LinkedHashMap<String, Int>()
        val itemSeq = HashMap<String, Long>()
        for (op in ops) {
            done[textOf(op["client_op_id"])]?.let { event ->
                results += result(event)
                continue
            }
            val payload = JsonObject(op.filterKeys { it != "client_op_id" && it != "summary" })
            val item = textOf(op["item"])!!
            val nameBefore = state[item]?.name
            val outcome = PackOps.apply(state, payload)
            if (outcome.status == OpStatus.APPLIED) state = outcome.items
            val summary = textOf(op["summary"])?.trim()?.take(MAX_SUMMARY)?.takeIf { it.isNotEmpty() } ?: defaultSummary(actor, payload, nameBefore)
            val status = if (outcome.status == OpStatus.APPLIED) "applied" else "skipped"
            val event = append(pack, actor, payload, summary, item, textOf(op["client_op_id"]), status, outcome.reason)
            if (outcome.status == OpStatus.APPLIED) {
                applied[item] = (applied[item] ?: 0) + 1
                itemSeq[item] = pack.headSeq
            }
            results += result(event)
            created += event
        }
        for (item in applied.keys) {
            val after = state.getValue(item)
            if (!after.deleted && after.data.toString().length > maxItemBytes) {
                pack.headSeq = headBefore
                while (pack.events.size > logged) pack.events.removeAt(pack.events.lastIndex)
                throw refusal(413, "item_too_large", "item" to text(item), "taken" to taken)
            }
        }
        val at = now()
        for ((item, count) in applied) {
            val after = state.getValue(item)
            val row = pack.items.getOrPut(item) { Item(item, after.kind, after.name, after.data, false, 0, 0, actor, actor, at, at) }
            row.name = after.name
            row.data = after.data
            row.deleted = after.deleted
            row.revision += count
            row.seq = itemSeq.getValue(item)
            row.updatedBy = actor
            row.updatedAt = at
        }
        pack.updatedAt = at
        announce(pack, created)
        var hasMore = false
        val events = if (baseSeq != null) {
            val after = pack.events.filter { seqOf(it) > baseSeq }
            hasMore = after.size > pageSize
            after.take(pageSize)
        } else {
            created
        }
        return obj(
            "head_seq" to JsonPrimitive(pack.headSeq),
            "results" to JsonArray(results),
            "events" to JsonArray(events),
            "has_more" to JsonPrimitive(hasMore),
        )
    }

    private fun append(
        pack: Pack,
        actor: Int,
        payload: JsonObject,
        summary: String,
        item: String? = null,
        clientOpId: String? = null,
        status: String = "applied",
        reason: String? = null,
    ): JsonObject {
        pack.headSeq += 1
        val event = obj(
            "seq" to JsonPrimitive(pack.headSeq),
            "type" to payload.getValue("type"),
            "item" to (item?.let(::text) ?: JsonNull),
            "actor" to person(actor),
            "summary" to text(summary),
            "status" to text(status),
            "reason" to (reason?.let(::text) ?: JsonNull),
            "client_op_id" to (clientOpId?.let(::text) ?: JsonNull),
            "op" to payload,
            "created_at" to text(now()),
        )
        pack.events += event
        return event
    }

    private fun announce(pack: Pack, events: List<JsonObject>) {
        if (!announce) return
        sockets.filter { it.pack == pack.uuid && it.welcomed && it.open }.forEach { socket ->
            events.forEach { socket.event(seqOf(it), it) }
        }
    }

    // pack_support.pack_full, for the person asking.
    private fun body(pack: Pack, asUser: Int): JsonObject {
        val items = pack.items.values.filter { !it.deleted }
        val counts = PackOps.ITEM_KINDS.associateWith { kind -> JsonPrimitive(items.count { it.kind == kind }) }
        return obj(
            "uuid" to text(pack.uuid),
            "name" to text(pack.name),
            "description" to text(""),
            "status" to text(pack.status),
            "role" to text(pack.members.getValue(asUser)),
            "owner" to person(pack.owner),
            "team" to JsonNull,
            "head_seq" to JsonPrimitive(pack.headSeq),
            "seen_seq" to JsonPrimitive(0),
            "member_count" to JsonPrimitive(pack.members.size),
            "audience_count" to JsonPrimitive(pack.members.size),
            "item_count" to JsonPrimitive(items.size),
            "item_counts" to JsonObject(counts),
            "finished_at" to (pack.finishedAt?.let(::text) ?: JsonNull),
            "finished_by" to (pack.finishedBy?.let(::person) ?: JsonNull),
            "created_at" to text(pack.createdAt),
            "updated_at" to text(pack.updatedAt),
            "members" to JsonArray(
                pack.members.map { (user, role) ->
                    obj(
                        "user_id" to JsonPrimitive(user), "name" to text(who(user)), "email" to text(""), "role" to text(role),
                        "added_at" to text(pack.createdAt), "seen_at" to JsonNull,
                    )
                },
            ),
            "items" to JsonArray(
                items.map { item ->
                    obj(
                        "uuid" to text(item.uuid), "kind" to text(item.kind), "name" to text(item.name),
                        "revision" to JsonPrimitive(item.revision), "seq" to JsonPrimitive(item.seq),
                        "created_by" to person(item.createdBy), "updated_by" to person(item.updatedBy),
                        "created_at" to text(item.createdAt), "updated_at" to text(item.updatedAt), "source" to JsonNull, "data" to item.data,
                    )
                },
            ),
            "live_url" to (liveUrl?.let(::text) ?: JsonNull),
        )
    }

    // pack_routes._default_summary: what the log says when a client sends no sentence.
    private fun defaultSummary(actor: Int, op: JsonObject, nameBefore: String?): String {
        val name = nameBefore ?: "an item"
        return when (textOf(op["type"])) {
            "item.create" -> "${who(actor)} added the ${KIND_WORDS[textOf(op["kind"])]} \"${textOf(op["name"])}\"."
            "item.delete" -> "${who(actor)} removed \"$name\"."
            "item.rename" -> "${who(actor)} renamed \"$name\" to \"${textOf(op["name"])}\"."
            else -> "${who(actor)} edited \"$name\"."
        }
    }

    private fun result(event: JsonObject): JsonObject = obj(
        "client_op_id" to (event["client_op_id"] ?: JsonNull),
        "seq" to (event["seq"] ?: JsonNull),
        "status" to (event["status"] ?: JsonNull),
        "reason" to (event["reason"] ?: JsonNull),
    )

    private fun person(user: Int): JsonObject = obj("id" to JsonPrimitive(user), "name" to text(who(user)))

    private fun who(user: Int): String = names[user] ?: "User $user"

    private fun actorOf(event: JsonObject): Int? = ((event["actor"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.toIntOrNull()

    // A minute of the pack's day for each thing that happens, so times differ and never depend on the clock.
    private fun now(): String {
        val tick = clock++
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "2026-10-05T12:${two(tick / 60 % 60)}:${two(tick % 60)}"
    }

    private companion object {
        const val MAX_BATCH = 200
        const val MAX_SUMMARY = 300
        val EDIT_ROLES = setOf("owner", "editor")
        val CLIENT_OP_ID = Regex("[\\x21-\\x7e]{1,64}")
        val KIND_WORDS = mapOf("lz" to "LZ", "route" to "routes", "pointset" to "points")

        fun text(value: String): JsonPrimitive = JsonPrimitive(value)

        fun textOf(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun seqOf(event: JsonObject): Long = (event["seq"] as JsonPrimitive).content.toLong()

        fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))
    }
}
