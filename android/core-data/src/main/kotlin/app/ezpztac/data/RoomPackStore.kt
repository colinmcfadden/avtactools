package app.ezpztac.data

import androidx.room.withTransaction
import app.ezpztac.missionpacks.DroppedOp
import app.ezpztac.missionpacks.PackItemState
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackSessions
import app.ezpztac.missionpacks.PackStore
import app.ezpztac.missionpacks.PendingOp
import app.ezpztac.missionpacks.PendingState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [PackStore] on Room: every mission pack this device holds, in the four pack tables, so a pack can be read and edited with no signal and
 * nothing made here is lost when the system ends the app. Held to `PackStoreContract`, as the in-memory store is.
 *
 * A session keeps the instance of everything that did not change, so [write] rewrites only what is not the same instance as in `before`:
 * an event someone else made rewrites one item row, not the pack, and an item can be megabytes. Each write is one transaction, so the
 * copy's place in the log, its confirmed items and the edits those confirm always agree.
 */
internal class RoomPackStore(private val database: EzpzDatabase) : PackStore {
    private val dao get() = database.packDao()

    override suspend fun load(pack: String, me: Int): PackSession? = database.withTransaction {
        val row = dao.pack(pack)?.takeIf { it.userId == me } ?: return@withTransaction null
        val confirmed = LinkedHashMap<String, PackItemState>()
        val info = LinkedHashMap<String, JsonObject>()
        dao.items(pack, READ_PART).forEach { item ->
            val data = item.data ?: readInParts(item.size) { from, count -> dao.itemDataPart(pack, item.uuid, from, count) }
            confirmed[item.uuid] = PackItemState(item.kind, item.name, data.toElement(), item.deleted)
            item.info?.let { info[item.uuid] = it.toObject() }
        }
        val ops = dao.ops(pack, READ_PART).map { read ->
            read to (read.op ?: readInParts(read.size) { from, count -> dao.opPart(pack, read.clientOpId, from, count) }).toObject()
        }
        val pending = ops.filter { (read) -> read.state != PackOpState.DROPPED.name }.map { (read, op) ->
            val state = PendingState.valueOf(read.state)
            PendingOp(op, state, if (state == PendingState.ACKED) read.seq else null)
        }
        val dropped = ops.filter { (read) -> read.state == PackOpState.DROPPED.name }
            .sortedBy { (read) -> read.dropOrder }
            .map { (read, op) -> DroppedOp(op, checkNotNull(read.reason) { "a refused edit without its reason" }) }
        // As written: a batch out stays sent and taken edits acked, for the client to settle after its catch-up. Only the view, which
        // is not stored, is worked out again.
        PackSessions.withView(
            PackSession(
                me = me,
                pack = row.meta.toObject(),
                members = row.members.toArray(),
                confirmed = confirmed,
                info = info,
                order = row.itemOrder.toArray().map { (it as JsonPrimitive).content },
                seq = row.seq,
                pending = pending,
                dropped = dropped,
                readOnly = row.readOnly,
                gone = row.gone,
                diverged = row.diverged,
                view = emptyMap(),
            ),
        )
    }

    override suspend fun write(before: PackSession?, after: PackSession, me: Int) {
        val uuid = requireNotNull(after.uuid) { "A pack's session names its pack" }
        database.withTransaction {
            val stored = dao.pack(uuid)
            // Compared with `before` only when that is what is stored: this person's copy, written before. Otherwise the session is
            // written whole over whatever is there, another account's copy included (its own fields too, which are not this person's).
            val since = before?.takeIf { stored?.userId == me }
            if (since == null && stored != null) {
                dao.deleteAllItems(uuid)
                dao.deleteAllOps(uuid)
                if (stored.userId != me) dao.deleteAllOwn(uuid)
            }
            if (since == null || packChanged(since, after)) {
                dao.upsertPack(
                    PackEntity(
                        uuid = uuid,
                        userId = me,
                        meta = after.pack.toText(),
                        members = after.members.toText(),
                        itemOrder = JsonArray(after.order.map(::JsonPrimitive)).toText(),
                        seq = after.seq,
                        readOnly = after.readOnly,
                        gone = after.gone,
                        diverged = after.diverged,
                        openedAt = stored?.openedAt ?: 0,
                    ),
                )
            }
            writeItems(uuid, since, after)
            writeOps(uuid, me, since, after)
        }
    }

    override suspend fun forget(pack: String) {
        database.withTransaction { forgetIn(pack) }
    }

    override suspend fun owed(me: Int): List<String> = dao.owed(me)

    override suspend fun withDropped(me: Int): List<String> = dao.withDropped(me)

    override suspend fun unsentCount(me: Int): Int = dao.unsentCount(me)

    override suspend fun touch(pack: String, at: Long) = dao.touch(pack, at)

    override suspend fun prune(me: Int, keep: Int) {
        database.withTransaction {
            dao.byLastOpened(me).drop(keep).filter { dao.opCount(it) == 0 }.forEach { forgetIn(it) }
        }
    }

    override suspend fun own(pack: String, item: String): JsonObject? = dao.own(pack, item)?.toObject()

    override suspend fun putOwn(pack: String, item: String, own: JsonObject) = dao.upsertOwn(PackOwnEntity(pack, item, own.toText()))

    // Inside a transaction.
    private suspend fun forgetIn(pack: String) {
        dao.deletePack(pack)
        dao.deleteAllItems(pack)
        dao.deleteAllOps(pack)
        dao.deleteAllOwn(pack)
    }

    private fun packChanged(before: PackSession, after: PackSession): Boolean =
        before.pack !== after.pack || before.members !== after.members || before.order !== after.order || before.seq != after.seq ||
            before.readOnly != after.readOnly || before.gone != after.gone || before.diverged != after.diverged

    // Each confirmed item that is not the instance [since] had, or is not stored at all; and away with those the session no longer has
    // (only a reload drops one: a deleted item otherwise stays, marked deleted).
    private suspend fun writeItems(pack: String, since: PackSession?, after: PackSession) {
        val stored = if (since == null) emptySet() else dao.itemUuids(pack).toHashSet()
        after.confirmed.forEach { (uuid, item) ->
            val info = after.info[uuid]
            val same = since != null && uuid in stored && since.confirmed[uuid] === item && since.info[uuid] === info
            if (!same) dao.upsertItem(PackItemEntity(pack, uuid, item.kind, item.name, item.data.toText(), item.deleted, info?.toText()))
        }
        (stored - after.confirmed.keys).chunked(IN_CHUNK).forEach { dao.deleteItems(pack, it) }
    }

    // Every edit by its client_op_id: those no longer anywhere in the session (confirmed by their event, or kept, or let go) deleted, new
    // ones inserted in the order they were made (pending's order is the order they were made in, and only ever grows at its end, so
    // localSeq keeps it), and one that moved (sent, acked, refused, or a place in the refused list) changed where it stands, without
    // writing the operation again: it never changes once made, and can carry a whole item.
    private suspend fun writeOps(pack: String, me: Int, since: PackSession?, after: PackSession) {
        val stored = if (since == null) emptyMap() else dao.opPlaces(pack).associateBy { it.clientOpId }
        val wanted = LinkedHashMap<String, Pair<JsonObject, PackOpPlace>>()
        fun want(op: JsonObject, place: (String) -> PackOpPlace) {
            val id = checkNotNull((op["client_op_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content) { "an edit without its client_op_id" }
            check(wanted.put(id, op to place(id)) == null) { "two edits named $id" }
        }
        after.pending.forEach { entry ->
            want(entry.op) { PackOpPlace(it, entry.state.name, null, null, if (entry.state == PendingState.ACKED) entry.seq else null) }
        }
        // Refused ones in the order they were refused, which is not the order they were made in.
        after.dropped.forEachIndexed { index, entry ->
            want(entry.op) { PackOpPlace(it, PackOpState.DROPPED.name, entry.reason, index.toLong(), null) }
        }
        (stored.keys - wanted.keys).chunked(IN_CHUNK).forEach { dao.deleteOps(pack, it) }
        wanted.forEach { (id, wantedOp) ->
            val (op, place) = wantedOp
            val was = stored[id]
            if (was == null) {
                dao.insertOp(PackOpEntity(0, pack, me, id, op.toText(), place.state, place.reason, place.dropOrder, place.seq))
            } else if (was != place) {
                dao.moveOp(pack, id, place.state, place.reason, place.dropOrder, place.seq)
            }
        }
    }

    // A value too long to read in one go, read [READ_PART] characters at a time and put together. SQLite counts characters (code points)
    // in a text, so a part never splits one.
    private suspend fun readInParts(size: Int, part: suspend (from: Int, count: Int) -> String?): String {
        val whole = StringBuilder(size)
        var from = 1
        while (from <= size) {
            whole.append(checkNotNull(part(from, READ_PART)) { "a value went while it was read" })
            from += READ_PART
        }
        return whole.toString()
    }

    private companion object {
        // Below SQLite's oldest limit on the values one statement takes (999).
        const val IN_CHUNK = 500

        // Characters read at a time: at most 1 MB in UTF-8 even if every one takes four bytes, well inside a 2 MB cursor window.
        const val READ_PART = 256 * 1024
    }
}

// JSON text as the session holds it: a number exactly as it was written (2.50 stays 2.50; an encoder would write each number again, as
// 2.5), every object's keys in order. Read back with a plain Json, which keeps a literal's text.
private val json = Json

private fun JsonElement.toText(): String = toString()
private fun String.toElement(): JsonElement = json.parseToJsonElement(this)
private fun String.toObject(): JsonObject = json.decodeFromString(JsonObject.serializer(), this)
private fun String.toArray(): JsonArray = json.decodeFromString(JsonArray.serializer(), this)
