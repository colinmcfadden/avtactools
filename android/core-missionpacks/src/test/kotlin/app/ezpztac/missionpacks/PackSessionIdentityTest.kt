package app.ezpztac.missionpacks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the session leaves as it was. An item can be megabytes, and a caller (the editors, the device's store) tells what
 * changed by instance, as the web's usePackItemSync does with `===`: so an item, an item's info, the members and the edits
 * waiting must stay the very objects they were wherever the web's session keeps them, and be new where it makes new ones.
 * The fixture cannot say this; it compares what things are, not which they are.
 */
class PackSessionIdentityTest {
    private val pack = json(
        """
        {"uuid": "p-1", "name": "OP DK", "status": "active", "role": "editor", "team": null, "head_seq": 3, "member_count": 2,
         "finished_at": null, "finished_by": null, "live_url": null,
         "members": [{"user_id": 1, "name": "Colin", "role": "owner"}, {"user_id": 2, "name": "Sam", "role": "editor"}],
         "items": [
           {"uuid": "lz-1", "kind": "lz", "name": "LZ HAWK", "revision": 1, "seq": 2, "source": null,
            "data": {"flightData": {"landingHeading": 270}, "graphics": {"helicopters": [{"id": "h-1", "heading": 270}]}}},
           {"uuid": "ps-1", "kind": "pointset", "name": "LOCAL", "revision": 1, "seq": 3, "source": null,
            "data": [{"id": "lps-0", "name": "ALPHA"}]}
         ]}
        """,
    )
    private val sam = 2

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun heading(value: Int) = json("""{"type": "set", "item": "lz-1", "path": ["flightData", "landingHeading"], "value": $value}""")

    private fun event(seq: Int, op: JsonObject, actor: Int = 1, clientOpId: String? = "colin-$seq") = json(
        """{"seq": $seq, "type": ${op["type"]}, "item": ${op["item"] ?: "null"}, "actor": {"id": $actor, "name": "X"}, "summary": "",
            "status": "applied", "reason": null, "client_op_id": ${clientOpId?.let { "\"$it\"" } ?: "null"}, "op": $op,
            "created_at": "2026-10-05T13:00:0$seq"}""",
    )

    private fun ids(): () -> String {
        var taken = 0
        return { "op-${++taken}" }
    }

    @Test
    fun `with nothing waiting, what is seen is the confirmed items themselves, the server's own data in them`() {
        val session = PackSessions.open(pack, sam)

        assertSame(session.confirmed, session.view)
        val items = pack.getValue("items").jsonArray
        assertSame(items[0].jsonObject["data"], session.confirmed.getValue("lz-1").data)
        assertSame(items[1].jsonObject["data"], session.confirmed.getValue("ps-1").data)
        // And what is shown carries the view's own data, so an editor can compare it by instance.
        val visible = PackSessions.visibleItems(session)
        assertEquals(listOf("lz-1", "ps-1"), visible.map { it.getValue("uuid").jsonPrimitive.content })
        visible.forEach { assertSame(session.view.getValue(it.getValue("uuid").jsonPrimitive.content).data, it["data"]) }
    }

    @Test
    fun `an edit makes new only the item it changes, and inside it only the way to what it changed`() {
        val before = PackSessions.open(pack, sam)
        val after = PackSessions.edit(before, listOf(heading(90)), ids()).session

        assertSame(before.confirmed, after.confirmed)
        assertSame(before.info, after.info)
        assertSame(before.pack, after.pack)
        assertSame(before.members, after.members)
        assertSame(before.confirmed.getValue("ps-1"), after.view.getValue("ps-1"))
        val was = before.view.getValue("lz-1").data.jsonObject
        val now = after.view.getValue("lz-1").data.jsonObject
        assertNotSame(was, now)
        assertSame(was["graphics"], now["graphics"])
        assertNotSame(was["flightData"], now["flightData"])
    }

    @Test
    fun `sending a batch changes only the state of what goes, and sends the very edits that wait`() {
        val edited = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90), heading(91)), ids()).session
        val next = requireNotNull(PackSessions.nextBatch(edited))

        assertSame(edited.view, next.session.view)
        assertSame(edited.confirmed, next.session.confirmed)
        val ops = next.batch.getValue("ops").jsonArray
        assertSame(edited.pending[0].op, ops[0])
        assertSame(edited.pending[1].op, ops[1])
        assertEquals(listOf(PendingState.SENT, PendingState.SENT), next.session.pending.map { it.state })
    }

    @Test
    fun `someone else's edit to one item leaves every other item, and what waits, as it was`() {
        val edited = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90)), ids()).session
        val received = PackSessions.receive(edited, listOf(event(4, json("""{"type": "item.rename", "item": "lz-1", "name": "LZ EAGLE"}""")))).session

        assertNotSame(edited.confirmed.getValue("lz-1"), received.confirmed.getValue("lz-1"))
        assertSame(edited.confirmed.getValue("ps-1"), received.confirmed.getValue("ps-1"))
        assertSame(edited.info.getValue("ps-1"), received.info.getValue("ps-1"))
        assertSame(edited.view.getValue("ps-1"), received.view.getValue("ps-1"))
        assertSame(edited.pending.single(), received.pending.single())
        assertSame(edited.members, received.members)
        assertSame(edited.dropped, received.dropped)
        assertSame(edited.order, received.order)
        // Its data is not touched by a rename.
        assertSame(edited.confirmed.getValue("lz-1").data, received.confirmed.getValue("lz-1").data)
    }

    @Test
    fun `an event about the pack leaves every item as it was`() {
        val opened = PackSessions.open(pack, sam)
        val received = PackSessions.receive(opened, listOf(event(4, json("""{"type": "pack.update", "name": "OP EAGLE"}"""), clientOpId = null))).session

        assertSame(opened.confirmed, received.confirmed)
        assertSame(opened.info, received.info)
        assertSame(received.confirmed, received.view)
        assertSame(opened.members, received.members)
        assertNotSame(opened.pack, received.pack)
        assertEquals(JsonPrimitive("OP EAGLE"), received.pack["name"])
    }

    @Test
    fun `a batch's answer leaves the items it did not touch as they were`() {
        val edited = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90)), ids()).session
        val sent = requireNotNull(PackSessions.nextBatch(edited)).session
        val answer = json(
            """{"head_seq": 4, "has_more": false, "results": [{"client_op_id": "op-1", "seq": 4, "status": "applied", "reason": null}],
                "events": [${event(4, heading(90), actor = sam, clientOpId = "op-1")}]}""",
        )
        val answered = PackSessions.batchAnswered(sent, answer)

        assertEquals(false, answered.catchUp)
        assertEquals(emptyList<PendingOp>(), answered.session.pending)
        assertSame(sent.confirmed.getValue("ps-1"), answered.session.confirmed.getValue("ps-1"))
        assertSame(sent.info.getValue("ps-1"), answered.session.info.getValue("ps-1"))
        assertSame(answered.session.confirmed, answered.session.view)
    }

    @Test
    fun `a reload keeps the very edits that wait, over the server's new copy`() {
        val edited = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90), json("""{"type": "item.rename", "item": "ps-1", "name": "POINTS"}""")), ids()).session
        val sent = requireNotNull(PackSessions.nextBatch(edited)).session
        val fresh = json(pack.toString())
        val reloaded = PackSessions.reload(sent, fresh)

        assertSame(sent.pending[0], reloaded.pending[0])
        assertSame(sent.pending[1], reloaded.pending[1])
        assertSame(fresh.getValue("items").jsonArray[1].jsonObject["data"], reloaded.confirmed.getValue("ps-1").data)
    }

    @Test
    fun `a lost answer puts the batch back to wait, and an edit the server took keeps its place`() {
        val first = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90)), ids()).session
        val out = requireNotNull(PackSessions.nextBatch(first)).session
        // Taken, its event on a later page.
        val answer = json("""{"head_seq": 9, "has_more": true, "results": [{"client_op_id": "op-1", "seq": 9, "status": "applied", "reason": null}], "events": []}""")
        val acked = PackSessions.batchAnswered(out, answer).session
        val second = PackSessions.edit(acked, listOf(heading(91)), { "op-2" }).session
        val lost = PackSessions.batchFailed(requireNotNull(PackSessions.nextBatch(second)).session, PackFailure(0))

        assertSame(second.pending[0], lost.pending[0])
        assertEquals(listOf(PendingState.ACKED, PendingState.QUEUED), lost.pending.map { it.state })
        assertEquals(9L, lost.pending[0].seq)
        assertSame(second.pending[1].op, lost.pending[1].op)
    }

    @Test
    fun `a session read back from the device sends the batch that was out again, under the same names`() {
        val edited = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90), heading(91)), ids()).session
        val out = requireNotNull(PackSessions.nextBatch(edited))
        val restored = PackSessions.restored(out.session)

        assertEquals(listOf(PendingState.QUEUED, PendingState.QUEUED), restored.pending.map { it.state })
        assertEquals(out.batch, requireNotNull(PackSessions.nextBatch(restored)).batch)
        // A session with nothing out comes back as it was, but for a view worked out again.
        val queued = PackSessions.restored(edited)
        assertSame(edited.pending[0], queued.pending[0])
        assertEquals(edited.view, queued.view)
    }

    @Test
    fun `whether a stored session had a batch out or edits taken is asked before it is restored, which forgets the first`() {
        val opened = PackSessions.open(pack, sam)
        val edited = PackSessions.edit(opened, listOf(heading(90)), ids()).session
        val out = requireNotNull(PackSessions.nextBatch(edited)).session
        val answer = json("""{"head_seq": 9, "has_more": true, "results": [{"client_op_id": "op-1", "seq": 9, "status": "applied", "reason": null}], "events": []}""")
        val acked = PackSessions.batchAnswered(out, answer).session

        assertFalse(PackSessions.hasSentOrAcked(opened))
        assertFalse(PackSessions.hasSentOrAcked(edited))                         // queued only: never seen by the server
        assertTrue(PackSessions.hasSentOrAcked(out))
        assertTrue(PackSessions.hasSentOrAcked(acked))
        // Restoring puts the batch out back in the queue, so asked afterwards the answer is lost; an acked edit stays.
        assertFalse(PackSessions.hasSentOrAcked(PackSessions.restored(out)))
        assertTrue(PackSessions.hasSentOrAcked(PackSessions.restored(acked)))
        assertSame(acked.pending.single(), PackSessions.restored(acked).pending.single())
    }

    @Test
    fun `dropped edits go once kept, by their names, and asking for none leaves the session itself`() {
        val edited = PackSessions.edit(PackSessions.open(pack, sam), listOf(heading(90), heading(91)), ids()).session
        val finished = PackSessions.batchFailed(
            requireNotNull(PackSessions.nextBatch(edited)).session,
            PackFailure(423, "pack_finished", taken = json("""{"taken": []}""")["taken"]),
        )
        assertEquals(listOf("op-1", "op-2"), finished.dropped.map { PackSessions.text(it.op["client_op_id"]) })

        val kept = PackSessions.withoutDropped(finished, setOf("op-1"))
        assertEquals(listOf(finished.dropped[1]), kept.dropped)
        assertSame(finished.dropped[1], kept.dropped.single())
        assertSame(finished, PackSessions.withoutDropped(finished, setOf("op-9")))
        assertSame(finished, PackSessions.withoutDropped(finished, emptySet()))
        assertEquals(emptyList<DroppedOp>(), PackSessions.withoutDropped(kept, setOf("op-2")).dropped)
        // What is left still makes a version.
        assertEquals(listOf("lz-1"), PackSessions.droppedVersions(kept).map { it.uuid })
    }

    @Test
    fun `nothing reached and nothing to ack leaves the session itself`() {
        val opened = PackSessions.open(pack, sam)
        assertSame(opened, PackSessions.abandon(opened, "signed_out"))
        assertNull(PackSessions.nextBatch(opened))
    }
}
