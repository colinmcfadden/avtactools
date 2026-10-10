package app.ezpztac.missionpacks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * What every [PackStore] must do, as plain suspend functions any test framework can run, as core-sync's scenarios are: JUnit 5
 * against [InMemoryPackStore] here, JUnit 4 under Robolectric against Room's in core-data. Each case is given a new, empty store.
 *
 * The first is the one the client leans on hardest: a session comes back as it was written ([PackStore.load]). The client asks
 * a session read back whether a batch was out or edits were taken before anything else, and puts that batch back in the queue only
 * after a catch-up has said what of it the server took. A store that did either itself would have the client drop as never sent,
 * or send again, edits the pack has; one that settled what it read would drop the edits a read-only pack holds for an answer.
 */
public object PackStoreContract {
    /** One rule, run against a new, empty store. */
    public class Case(public val name: String, public val run: suspend (PackStore) -> Unit) {
        override fun toString(): String = name
    }

    private const val COLIN = 1
    private const val SAM = 2

    public val cases: List<Case> = listOf(
        Case("a session comes back as it was written: a batch out still sent, taken edits still acked with their seq or none, refused ones still dropped, and every flag") { store ->
            val written = Sessions().all()
            written.forEach { store.write(null, it, SAM) }
            written.forEach { assertComesBack(it, store.load(it.uuid!!, SAM)) }
        },
        Case("each change written over the last comes back whole, as the client writes them") { store ->
            val made = Sessions()
            var before: PackSession? = null
            suspend fun write(after: PackSession) {
                store.write(before, after, SAM)
                assertComesBack(after, store.load("p-1", SAM))
                before = after
            }
            var session = PackSessions.open(made.body("p-1"), SAM)
            write(session)
            session = made.edit(session, made.heading(90))
            write(session)
            session = PackSessions.nextBatch(session)!!.session
            write(session)
            // Someone else changes one item and adds another: one item rewritten, one added.
            session = PackSessions.receive(
                session,
                listOf(
                    made.event(4, made.note("lz-2", "dry"), actor = COLIN),
                    made.event(5, made.create("lz-3", "LZ DOVE"), actor = COLIN),
                ),
            ).session
            write(session)
            // The answer: the batch confirmed by its own event, and nothing pending.
            val taken = made.event(6, made.heading(90), actor = SAM, clientOpId = "op-1")
            session = PackSessions.batchAnswered(
                session,
                json("""{"head_seq": 6, "results": [{"client_op_id": "op-1", "seq": 6, "status": "applied", "reason": null}], "events": [$taken], "has_more": false}"""),
            ).session
            write(session)
            assertTrue(session.pending.isEmpty())
            // A batch the server refuses as too large: dropped, then kept and let go.
            session = made.edit(session, made.note("lz-1", "x".repeat(40)))
            session = PackSessions.batchFailed(PackSessions.nextBatch(session)!!.session, PackFailure(413, "item_too_large", item = "lz-1"))
            write(session)
            session = PackSessions.withoutDropped(session, setOf("op-2"))
            write(session)
            // The server's copy again, which no longer has LZ CROW at all: gone from the store too.
            session = PackSessions.reload(session, made.body("p-1", head = 9, items = listOf("lz-1", "lz-3")))
            write(session)
            assertEquals(listOf("lz-1", "lz-3"), store.load("p-1", SAM)!!.confirmed.keys.sorted())
        },
        Case("a copy is never read back for another account, and a fresh copy for another replaces it whole") { store ->
            val made = Sessions()
            val colins = made.edit(PackSessions.open(made.body("p-1", role = "owner"), COLIN), made.heading(90))
            store.write(null, colins, COLIN)
            assertNull(store.load("p-1", SAM))
            assertComesBack(colins, store.load("p-1", COLIN))

            val sams = PackSessions.open(made.body("p-1"), SAM)
            store.write(null, sams, SAM)
            assertComesBack(sams, store.load("p-1", SAM))                   // nothing of Colin's in it: his edit is not Sam's to send
            assertNull(store.load("p-1", COLIN))
            assertTrue(store.owed(COLIN).isEmpty())
            assertEquals(0, store.unsentCount(COLIN))
        },
        Case("what a drain has to deliver, what waits to be kept, and how many of the person's edits have not reached a pack") { store ->
            val made = Sessions()
            val all = made.all().associateBy { it.uuid!! }
            all.values.forEach { store.write(null, it, SAM) }
            store.write(null, made.edit(PackSessions.open(made.body("p-colin", role = "owner"), COLIN), made.heading(90)), COLIN)

            // Queued or sent: the batch out, and edits a read-only pack holds for the server's answer.
            assertEquals(setOf("p-sent", "p-held-sent", "p-held-queued", "p-diverged"), store.owed(SAM).toSet())
            assertEquals(setOf("p-refused", "p-gone"), store.withDropped(SAM).toSet())
            // Taken ones are the pack's; the rest count, dropped ones until they are kept.
            val expected = all.values.sumOf { s -> s.pending.count { it.state != PendingState.ACKED } + s.dropped.size }
            assertEquals(expected, store.unsentCount(SAM))
            assertEquals(listOf("p-colin"), store.owed(COLIN))
            assertEquals(1, store.unsentCount(COLIN))
        },
        Case("pruning forgets the packs opened longest ago that hold nothing of the person's, and never one that does") { store ->
            val made = Sessions()
            (1..13).forEach { n ->
                val uuid = "p-$n"
                val opened = PackSessions.open(made.body(uuid), SAM)
                val session = when (n) {
                    1 -> made.edit(opened, made.heading(90))                    // a queued edit
                    2 -> made.acked(opened)                                      // one the server took, its event not seen yet
                    else -> opened
                }
                store.write(null, session, SAM)
                store.touch(uuid, n * 1_000L)
            }
            store.write(null, PackSessions.open(made.body("p-colin", role = "owner"), COLIN), COLIN)
            store.putOwn("p-3", "lz-1", json("""{"view": {"baseMap": "topo"}}"""))

            store.prune(SAM, keep = 10)

            assertNotNull(store.load("p-1", SAM))                              // among the oldest, but each holds an edit
            assertNotNull(store.load("p-2", SAM))
            assertNull(store.load("p-3", SAM))
            assertNull(store.own("p-3", "lz-1"))                                // its own fields go with it
            (4..13).forEach { assertNotNull(store.load("p-$it", SAM), "p-$it") }
            assertNotNull(store.load("p-colin", COLIN))                         // another account's is not this one's to prune
        },
        Case("each person's own fields are kept per item, and forgetting a pack forgets them and nothing else") { store ->
            val made = Sessions()
            store.write(null, PackSessions.open(made.body("p-1"), SAM), SAM)
            store.write(null, PackSessions.open(made.body("p-2"), SAM), SAM)
            val view = json("""{"view": {"baseMap": "topo", "zoom": 17.0}}""")
            val hidden = json("""{"hidden": ["r-2", "r-1"]}""")
            store.putOwn("p-1", "lz-1", view)
            store.putOwn("p-2", "rs-1", hidden)
            assertEquals(view.toString(), store.own("p-1", "lz-1").toString())
            assertNull(store.own("p-1", "lz-2"))
            store.forget("p-1")
            assertNull(store.load("p-1", SAM))
            assertNull(store.own("p-1", "lz-1"))
            assertNotNull(store.load("p-2", SAM))
            assertEquals(hidden.toString(), store.own("p-2", "rs-1").toString())
        },
    )

    /**
     * [loaded] is [written] as a store must give it back ([PackStore.load]): the whole session as text, numbers as written and
     * every key in its order, but for the order of the items in the three maps keyed by item, which nothing reads; and what the
     * person sees, in the order they see it.
     */
    public fun assertComesBack(written: PackSession, loaded: PackSession?) {
        assertNotNull(loaded, "Nothing came back for ${written.uuid}")
        assertEquals(text(written), text(loaded!!), "${written.uuid} came back changed")
        assertEquals(
            JsonArray(PackSessions.visibleItems(written)).toString(),
            JsonArray(PackSessions.visibleItems(loaded)).toString(),
            "${written.uuid} shows something else",
        )
    }

    private val BY_ITEM = setOf("confirmed", "info", "view")

    private fun text(session: PackSession): String {
        val json = PackSessions.toJson(session)
        return JsonObject(json.mapValues { (key, value) -> if (key in BY_ITEM) JsonObject((value as JsonObject).toSortedMap()) else value }).toString()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** Sessions as the client makes them, each in a state a store must keep. Operations are named op-1, op-2, … in the order made. */
    private class Sessions {
        private var ids = 0

        // OP DK at event 3: LZ HAWK and LZ CROW, with numbers written as a person's device wrote them (1.0, 2.50) and keys in
        // no sorted order.
        fun body(uuid: String, head: Long = 3, role: String = "editor", items: List<String> = listOf("lz-1", "lz-2")): JsonObject {
            val all = mapOf(
                "lz-1" to """{"uuid": "lz-1", "kind": "lz", "name": "LZ HAWK", "revision": 2, "seq": 2, "source": null,
                    "data": {"schemaVersion": 2, "flightData": {"landingHeading": 270, "speed": 1.0, "ratio": 2.50}, "b": true, "a": null}}""",
                "lz-2" to """{"uuid": "lz-2", "kind": "lz", "name": "LZ CROW", "revision": 1, "seq": 3, "source": null,
                    "data": {"z": [1, "x", {"y": 0.10}], "notes": ""}}""",
                "lz-3" to """{"uuid": "lz-3", "kind": "lz", "name": "LZ DOVE", "revision": 1, "seq": 5, "source": null, "data": {"k": 1.50}}""",
            )
            return json(
                """{"uuid": "$uuid", "name": "OP DK", "description": "", "status": "active", "role": "$role", "head_seq": $head,
                    "live_url": null, "finished_at": null, "finished_by": null, "team": null,
                    "members": [{"user_id": 1, "name": "Colin", "email": "", "role": "owner", "seen_at": null},
                                {"user_id": 2, "name": "Sam", "email": "", "role": "editor", "seen_at": null}],
                    "items": [${items.joinToString(", ") { all.getValue(it) }}]}""",
            )
        }

        fun heading(value: Int): JsonObject =
            json("""{"type": "set", "item": "lz-1", "path": ["flightData", "landingHeading"], "value": $value}""")

        fun note(item: String, value: String): JsonObject = json("""{"type": "set", "item": "$item", "path": ["notes"], "value": "$value"}""")

        fun create(item: String, name: String): JsonObject =
            json("""{"type": "item.create", "item": "$item", "kind": "lz", "name": "$name", "data": {"k": 1.50}}""")

        fun idOf(entry: PendingOp): String = (entry.op.getValue("client_op_id") as JsonPrimitive).content

        fun edit(session: PackSession, vararg ops: JsonObject): PackSession {
            val edited = PackSessions.edit(session, ops.toList()) { "op-${++ids}" }
            check(edited.refused == null) { "Refused: ${edited.refused}" }
            return edited.session
        }

        // An event of the pack's log, as the server writes one.
        fun event(seq: Long, op: JsonObject, actor: Int, clientOpId: String? = null, status: String = "applied"): JsonObject = JsonObject(
            linkedMapOf(
                "seq" to JsonPrimitive(seq),
                "type" to op.getValue("type"),
                "item" to (op["item"] ?: JsonNull),
                "actor" to json("""{"id": $actor, "name": "${if (actor == COLIN) "Colin" else "Sam"}"}"""),
                "summary" to JsonPrimitive("Someone changed something."),
                "status" to JsonPrimitive(status),
                "reason" to JsonNull,
                "client_op_id" to (clientOpId?.let(::JsonPrimitive) ?: JsonNull),
                "op" to op,
                "created_at" to JsonPrimitive("2026-10-05T12:00:${seq.toString().padStart(2, '0')}"),
            ),
        )

        // An edit the server took (its result says so) whose event has not come: acked, waiting at the seq it named.
        fun acked(opened: PackSession): PackSession {
            val sent = PackSessions.nextBatch(edit(opened, heading(100)))!!.session
            val id = idOf(sent.pending.single())
            return PackSessions.batchAnswered(
                sent,
                json("""{"head_seq": 5, "results": [{"client_op_id": "$id", "seq": 5, "status": "applied", "reason": null}], "events": [], "has_more": true}"""),
            ).session
        }

        /**
         * One of each state a store must keep, each its own pack. Each is checked to be in that state as it is made: a store that
         * hands back what it was given passes whatever the sessions are, so they must be what they say.
         */
        fun all(): List<PackSession> = listOf(sent(), takenAndAcked(), refused(), gone(), heldSent(), heldQueued(), diverged())

        private fun states(session: PackSession): List<Pair<PendingState, Long?>> = session.pending.map { it.state to it.seq }

        // A batch out, an edit queued behind it, and an item made here the server has not confirmed yet.
        private fun sent(): PackSession {
            val out = PackSessions.nextBatch(edit(PackSessions.open(body("p-sent"), SAM), heading(90)))!!.session
            return edit(out, create("lz-9", "LZ OWL")).also {
                check(states(it) == listOf(PendingState.SENT to null, PendingState.QUEUED to null) && "lz-9" in it.view && "lz-9" !in it.confirmed)
            }
        }

        // Two edits the server took whose events have not come: one waiting at the seq its result named, one whose result named none.
        private fun takenAndAcked(): PackSession {
            val sent = PackSessions.nextBatch(edit(PackSessions.open(body("p-acked"), SAM), heading(100), note("lz-2", "wet")))!!.session
            val (first, second) = sent.pending.map(::idOf)
            return PackSessions.batchAnswered(
                sent,
                json(
                    """{"head_seq": 6, "events": [], "has_more": true, "results": [
                        {"client_op_id": "$first", "seq": 5, "status": "applied", "reason": null},
                        {"client_op_id": "$second", "seq": null, "status": "applied", "reason": null}]}""",
                ),
            ).session.also { check(states(it) == listOf(PendingState.ACKED to 5L, PendingState.ACKED to null)) }
        }

        // Refused for two reasons, in the order the refusals came (not the order made), and then finished: read-only.
        private fun refused(): PackSession {
            var session = PackSessions.nextBatch(edit(PackSessions.open(body("p-refused"), SAM), heading(110)))!!.session
            session = edit(session, heading(120))
            session = PackSessions.batchFailed(session, PackFailure(400, "invalid_op", reason = "bad_value"))
            session = PackSessions.nextBatch(session)!!.session
            session = edit(session, heading(130))
            return PackSessions.batchFailed(
                session,
                PackFailure(423, "pack_finished", finishedBy = json("""{"id": 1, "name": "Colin"}"""), finishedAt = JsonPrimitive("2026-10-05T12:00:09")),
            ).also {
                check(it.readOnly && it.pending.isEmpty() && it.dropped.map(DroppedOp::reason) == listOf("bad_value", "pack_finished", "pack_finished"))
            }
        }

        // Deleted, or not this person's any more: gone, with what waited dropped as gone.
        private fun gone(): PackSession {
            val out = PackSessions.nextBatch(edit(PackSessions.open(body("p-gone"), SAM), heading(140)))!!.session
            return PackSessions.batchFailed(edit(out, note("lz-2", "mud")), PackFailure(404, "pack_not_found")).also {
                check(it.gone == "not_found" && it.pending.isEmpty() && it.dropped.map(DroppedOp::reason) == listOf("gone", "gone"))
            }
        }

        // Finished while a batch was out: read-only, the batch and what is queued behind it held for the server's answer.
        private fun heldSent(): PackSession {
            val out = PackSessions.nextBatch(edit(PackSessions.open(body("p-held-sent"), SAM), heading(150)))!!.session
            return PackSessions.receive(edit(out, heading(155)), listOf(event(4, json("""{"type": "pack.finish"}"""), actor = COLIN))).session.also {
                check(it.readOnly && states(it) == listOf(PendingState.SENT to null, PendingState.QUEUED to null) && it.dropped.isEmpty())
            }
        }

        // The same after the batch's answer was lost: everything queued again, still held. Settled, it would all be dropped.
        private fun heldQueued(): PackSession {
            val out = PackSessions.nextBatch(edit(PackSessions.open(body("p-held-queued"), SAM), heading(160)))!!.session
            val held = PackSessions.receive(edit(out, heading(165)), listOf(event(4, json("""{"type": "pack.finish"}"""), actor = COLIN))).session
            return PackSessions.batchFailed(held, PackFailure(0)).also {
                check(it.readOnly && states(it) == listOf(PendingState.QUEUED to null, PendingState.QUEUED to null) && it.dropped.isEmpty())
            }
        }

        // A copy that disagreed with the server (an event it could not apply), with an item deleted since and an edit queued.
        private fun diverged(): PackSession {
            val received = PackSessions.receive(
                PackSessions.open(body("p-diverged"), SAM),
                listOf(
                    event(4, note("lz-7", "ghost"), actor = COLIN),
                    event(5, json("""{"type": "item.delete", "item": "lz-2"}"""), actor = COLIN),
                ),
            ).session
            return edit(received, heading(170)).also {
                val crow = it.confirmed.getValue("lz-2")
                check(it.diverged && it.seq == 5L && crow.deleted && "lz-2" !in it.order && states(it) == listOf(PendingState.QUEUED to null))
            }
        }
    }
}
