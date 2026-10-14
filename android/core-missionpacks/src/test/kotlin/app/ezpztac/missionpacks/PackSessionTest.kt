package app.ezpztac.missionpacks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What the session does that packs/session.json cannot pin: how a session read back from the device is brought up to date
 * (only the device keeps one), and what it does with input the server never sends. Every input in the fixture is shaped as
 * the server sends it, so where the port narrows the web's answer to something else (an event with no number, a count that
 * is text) only a test here holds it.
 */
class PackSessionTest {
    private val sam = 2

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // The pack as GET /api/packs/<uuid> gives it, at event [head], with LZ HAWK's landing heading [heading]; [item] is the
    // item's revision and seq as the body writes them.
    private fun pack(head: Int, heading: Int = 270, item: String = """"revision": 1, "seq": 2,""") = json(
        """
        {"uuid": "p-1", "name": "OP DK", "status": "active", "role": "editor", "team": null, "head_seq": $head, "member_count": 2,
         "finished_at": null, "finished_by": null, "live_url": null,
         "members": [{"user_id": 1, "name": "Colin", "role": "owner"}, {"user_id": 2, "name": "Sam", "role": "editor"}],
         "items": [{"uuid": "lz-1", "kind": "lz", "name": "LZ HAWK", $item "source": null, "data": {"flightData": {"landingHeading": $heading}}}]}
        """,
    )

    // The same, finished by Colin at event [head].
    private fun finished(head: Int, heading: Int) = JsonObject(
        pack(head, heading) + json("""{"status": "finished", "finished_at": "2026-10-05T13:00:0$head", "finished_by": {"id": 1, "name": "Colin"}}"""),
    )

    private fun heading(value: Int) = json("""{"type": "set", "item": "lz-1", "path": ["flightData", "landingHeading"], "value": $value}""")

    private fun event(seq: Int, op: JsonObject, actor: Int = 1, clientOpId: String? = null) = json(
        """{"seq": $seq, "type": ${op["type"]}, "item": ${op["item"] ?: "null"}, "actor": {"id": $actor, "name": "X"}, "summary": "",
            "status": "applied", "reason": null, "client_op_id": ${clientOpId?.let { "\"$it\"" } ?: "null"}, "op": $op,
            "created_at": "2026-10-05T13:00:0$seq"}""",
    )

    private fun finish(seq: Int) = event(seq, json("""{"type": "pack.finish"}"""))

    private fun ids(): () -> String {
        var taken = 0
        return { "op-${++taken}" }
    }

    // One edit to LZ HAWK's heading, sent and not answered yet: what the device holds if the app stops now.
    private fun batchOut(newId: () -> String = ids()): PackSession =
        requireNotNull(PackSessions.nextBatch(PackSessions.edit(PackSessions.open(pack(4), sam), listOf(heading(90)), newId).session)).session

    private fun confirmedHeading(session: PackSession) =
        session.confirmed.getValue("lz-1").data.jsonObject.getValue("flightData").jsonObject["landingHeading"]

    // -- A session read back from the device ---------------------------------------------------------------------------

    @Test
    fun `a stored batch that was out is caught up before a reload, so an edit taken before the pack was finished is not offered back`() {
        // The server took op-1 as event 5 and the owner finished the pack at 6, while the app was stopped with no answer.
        val stored = batchOut()
        val finishedPack = finished(6, heading = 90)
        assertTrue(PackSessions.hasSentOrAcked(stored))

        // Restored first and then reloaded, the edit looks never sent, and the finished pack drops it: it would be kept to
        // the Library as "LZ HAWK (my edits)", saying the pack refused what it has. The web does this only after a lost
        // answer (session.json's second known exception).
        val restoredFirst = PackSessions.reload(PackSessions.restored(stored), finishedPack)
        assertEquals(listOf("pack_finished"), restoredFirst.dropped.map { it.reason })
        assertEquals(listOf("lz-1"), PackSessions.droppedVersions(restoredFirst).map { it.uuid })

        // Caught up from where it stopped: its own event comes before the finish and confirms it.
        val caughtUp = PackSessions.receive(stored, listOf(event(5, heading(90), actor = sam, clientOpId = "op-1"), finish(6))).session
        assertEquals(emptyList<PendingOp>(), caughtUp.pending)
        assertEquals(emptyList<DroppedOp>(), caughtUp.dropped)
        assertTrue(caughtUp.readOnly)
        assertEquals(JsonPrimitive(90), confirmedHeading(caughtUp))
        assertFalse(PackSessions.hasSentOrAcked(caughtUp))
    }

    @Test
    fun `a reload before the resend holds the batch out, and the refusal of the resend says the pack has it`() {
        val stored = batchOut()
        val finishedPack = finished(6, heading = 90)

        // Not yet restored, the batch is still out, and a read-only pack waits for its answer before it drops anything.
        val held = PackSessions.reload(stored, finishedPack)
        assertEquals(listOf(PendingState.SENT), held.pending.map { it.state })
        assertEquals(emptyList<DroppedOp>(), held.dropped)

        // Restored only for the resend, which goes under the same name; the 423 says the pack took it as event 5.
        val resent = requireNotNull(PackSessions.nextBatch(PackSessions.restored(held)))
        assertEquals(listOf(JsonPrimitive("op-1")), resent.batch.getValue("ops").jsonArray.map { it.jsonObject["client_op_id"] })
        val taken = json("""{"taken": [{"client_op_id": "op-1", "seq": 5, "status": "applied", "reason": null}]}""")["taken"]
        val refused = PackSessions.batchFailed(resent.session, PackFailure(423, "pack_finished", taken = taken))
        assertEquals(emptyList<PendingOp>(), refused.pending)
        assertEquals(emptyList<DroppedOp>(), refused.dropped)
    }

    // -- What the web does with its own defaults, kept ---------------------------------------------------------------------

    @Test
    fun `an edit the server took with no event number waits for its event, whatever seq reaches`() {
        val answer = json(
            """{"head_seq": 9, "has_more": false, "results": [{"client_op_id": "op-1", "seq": null, "status": "applied", "reason": null}], "events": []}""",
        )
        val answered = PackSessions.batchAnswered(batchOut(), answer)

        assertTrue(answered.catchUp)
        val entry = answered.session.pending.single()
        assertEquals(PendingState.ACKED, entry.state)
        assertNull(entry.seq)
        assertEquals(JsonNull, PackSessions.toJson(answered.session).getValue("pending").jsonArray.single().jsonObject["seq"])

        // A copy that holds it does not settle it: nothing says which event was ours.
        val reloaded = PackSessions.reload(answered.session, pack(9, heading = 90))
        assertEquals(listOf(entry), reloaded.pending)

        val received = PackSessions.receive(reloaded, listOf(event(10, heading(90), actor = sam, clientOpId = "op-1"))).session
        assertEquals(emptyList<PendingOp>(), received.pending)
    }

    // -- What the server never sends, narrowed -------------------------------------------------------------------------

    @Test
    fun `an event without a number is passed over, and the next numbered one still applies`() {
        val edited = PackSessions.edit(PackSessions.open(pack(4), sam), listOf(heading(90)), ids()).session
        val rename = json("""{"type": "item.rename", "item": "lz-1", "name": "LZ EAGLE"}""")
        // Named as our own edit, so the web, which applies it and takes its undefined seq, would also let op-1 go.
        val unnumbered = JsonObject(event(5, rename, clientOpId = "op-1") - "seq")

        val passed = PackSessions.receive(edited, listOf(unnumbered))
        assertFalse(passed.gap)
        assertEquals(4L, passed.session.seq)
        assertEquals(edited.confirmed, passed.session.confirmed)
        assertEquals(edited.pending, passed.session.pending)
        assertEquals(edited.pack, passed.session.pack)

        val next = PackSessions.receive(passed.session, listOf(unnumbered, event(5, rename, clientOpId = "colin-5"))).session
        assertEquals(5L, next.seq)
        assertEquals("LZ EAGLE", next.confirmed.getValue("lz-1").name)
        assertEquals(edited.pending, next.pending)
    }

    @Test
    fun `a body without a list of items is refused`() {
        assertThrows<IllegalArgumentException> { PackSessions.open(JsonObject(pack(4) - "items"), sam) }
        assertThrows<IllegalArgumentException> { PackSessions.open(JsonObject(pack(4) + ("items" to JsonObject(emptyMap()))), sam) }
    }

    @Test
    fun `a revision or a count that is not a number is taken as missing`() {
        val rename = json("""{"type": "item.rename", "item": "lz-1", "name": "LZ EAGLE"}""")
        // The web would join text ("two" + 1 is "two1") or give NaN for one missing; here each counts as 0, so it becomes 1.
        listOf(""""revision": "two", "seq": 2,""", """"seq": 2,""").forEach { item ->
            val opened = PackSessions.open(pack(4, item = item), sam)
            val renamed = PackSessions.receive(opened, listOf(event(5, rename))).session
            assertEquals(JsonPrimitive(1), renamed.info.getValue("lz-1")["revision"], item)
        }

        val counted = PackSessions.open(JsonObject(pack(4) + ("member_count" to JsonPrimitive("2"))), sam)
        val add = json("""{"type": "member.add", "user_id": 3, "name": "Kim", "role": "editor"}""")
        val remove = json("""{"type": "member.remove", "user_id": 1}""")
        // As if there were none: none and one more is 1 (the web's "21"), and one fewer than none is 0 (the web's 1).
        assertEquals(JsonPrimitive(1), PackSessions.receive(counted, listOf(event(5, add))).session.pack["member_count"])
        assertEquals(JsonPrimitive(0), PackSessions.receive(counted, listOf(event(5, remove))).session.pack["member_count"])
    }

    @Test
    fun `a result whose seq is not a whole number acks its edit with none, to wait for its event`() {
        listOf("4.5", "\"5\"").forEach { seq ->
            val answer = json(
                """{"head_seq": 5, "has_more": false, "results": [{"client_op_id": "op-1", "seq": $seq, "status": "applied", "reason": null}], "events": []}""",
            )
            val entry = PackSessions.batchAnswered(batchOut(), answer).session.pending.single()
            assertEquals(PendingState.ACKED, entry.state, seq)
            assertNull(entry.seq, seq)
        }
    }

    @Test
    fun `a result naming its edit other than as text acks nothing, as a JavaScript Map keyed by the result would not`() {
        // An edit named "1", and a result naming the number 1: not the same key.
        val out = batchOut(newId = { "1" })
        val answer = json("""{"head_seq": 5, "has_more": false, "results": [{"client_op_id": 1, "seq": 5, "status": "applied", "reason": null}], "events": []}""")
        val answered = PackSessions.batchAnswered(out, answer)

        assertEquals(listOf(PendingOp(out.pending.single().op, PendingState.SENT)), answered.session.pending)
        assertFalse(answered.catchUp)
    }
}
