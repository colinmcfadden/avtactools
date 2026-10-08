package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The mission-pack, team, invitation and people-search calls against the **real** routes (`backend/tests/live_server.py`): a pack
 * made, edited through operations, read back from its log, finished and reopened; people invited by email, by link and by name;
 * teams. The mock tests prove each call says what the recordings say; these prove the calls and the server agree, nulls and all.
 * Skipped without `EZPZ_LIVE_PYTHON`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LivePackCallsTest {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 3600)
    }

    @AfterAll
    fun stop() { server?.close() }

    private fun live(): LiveServer = server ?: error("no live server")

    private class Person(val email: String, val id: Int, val api: ApiClient)

    /** A new account, signed in on a device of its own. Mission packs are off for an account unless an admin ticks them. */
    private suspend fun person(name: String, packs: Boolean = true): Person {
        val email = "${name.lowercase().filter { it.isLetter() }}-${UUID.randomUUID()}@example.com"
        val id = live().makeAccount(email, features = if (packs) mapOf("mission_packs" to true) else null, name = name)
        live().clearRateLimits()
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val api = ApiClient(live().baseUrl, http, ClientInfo.android("1.4.0", 212), InMemorySessionStore())
        api.login(email, LiveServer.PASSWORD)
        return Person(email, id, api)
    }

    private fun json(text: String): JsonObject = ApiClient.JSON.parseToJsonElement(text).jsonObject

    private fun ops(baseSeq: Long?, vararg ops: String): JsonObject =
        json("""{"ops": [${ops.joinToString(",")}]${if (baseSeq != null) ", \"base_seq\": $baseSeq" else ""}}""")

    private fun JsonObject.events(): List<JsonObject> = getValue("events").jsonArray.map { it.jsonObject }

    @Test
    fun `a pack is made, edited by operations, read back from its log, finished and reopened`() = runBlocking<Unit> {
        val colin = person("Colin P.")
        val api = colin.api
        val uuid = UUID.randomUUID().toString()

        val made = api.createPack("OP EAGLE", "Night air assault", uuid = uuid)
        assertTrue(made.created)
        assertEquals(uuid, made.value.uuid)
        assertEquals("owner", made.value.role)
        assertNull(made.value.liveUrl)                                                  // no live service here: clients poll
        val again = api.createPack("OP EAGLE", uuid = uuid)
        assertFalse(again.created, "a retry with the device's uuid is the first pack, not a second")
        assertEquals(made.value.headSeq, again.value.headSeq)
        assertEquals(listOf(uuid), api.listPacks().map { it.uuid })

        // Operations, with the nulls that mean something: a value cleared and an element put first.
        val batch = ops(
            made.value.headSeq,
            """{"type": "item.create", "item": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": {"note": "x", "list": [{"id": "a", "v": 1}]},
                "client_op_id": "op-1", "summary": "Colin P. added the LZ \"LZ HAWK\"."}""",
            """{"type": "set", "item": "lz-1", "path": ["note"], "value": null, "client_op_id": "op-2"}""",
            """{"type": "insert", "item": "lz-1", "path": ["list"], "after": null, "value": {"id": "b", "v": 2}, "client_op_id": "op-3"}""",
            """{"type": "set", "item": "lz-9", "path": ["note"], "value": 1, "client_op_id": "op-4"}""",
        )
        val answer = api.sendPackOps(uuid, batch, asUser = colin.id)
        val results = answer.getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(listOf("applied", "applied", "applied", "skipped"), results.map { it.getValue("status").jsonPrimitive.content })
        assertEquals(5L, answer.getValue("head_seq").jsonPrimitive.long)
        val events = answer.events().associateBy { it.getValue("client_op_id").jsonPrimitive.content }
        assertEquals(listOf("op-1", "op-2", "op-3", "op-4"), answer.events().map { it.getValue("client_op_id").jsonPrimitive.content })
        assertEquals(JsonNull, events.getValue("op-2").getValue("op").jsonObject["value"])
        assertEquals(JsonNull, events.getValue("op-3").getValue("op").jsonObject["after"])
        assertEquals("Colin P. added the LZ \"LZ HAWK\".", events.getValue("op-1").getValue("summary").jsonPrimitive.content)

        // The same batch again, as after a lost answer: answered from the log, applied once.
        val replay = api.sendPackOps(uuid, batch, asUser = colin.id)
        assertEquals(answer.getValue("results"), replay.getValue("results"))
        assertEquals(5L, replay.getValue("head_seq").jsonPrimitive.long)

        val item = api.packItem(uuid, "lz-1")
        assertEquals(json("""{"note": null, "list": [{"id": "b", "v": 2}, {"id": "a", "v": 1}]}"""), item.data)
        val document = api.packDocument(uuid, asUser = colin.id)
        assertEquals(JsonNull, document["live_url"])
        assertEquals(JsonNull, document.getValue("items").jsonArray.single().jsonObject.getValue("data").jsonObject["note"])

        // The log, a page at a time.
        val first = api.packEventsDocument(uuid, since = 0, limit = 2, background = false, asUser = colin.id)
        assertEquals(listOf(1L, 2L), first.events().map { it.getValue("seq").jsonPrimitive.long })
        assertTrue(first.getValue("has_more").jsonPrimitive.content.toBoolean())
        val rest = api.packEvents(uuid, since = first.getValue("cursor").jsonPrimitive.long)
        assertEquals(listOf(3L, 4L, 5L), rest.events.map { it.seq })
        assertFalse(rest.hasMore)
        assertEquals("skipped", rest.events.last().status)
        assertEquals(5L, api.markPackSeen(uuid, 5).seenSeq)
        assertEquals(5L, api.pack(uuid).seenSeq)

        assertEquals("OP HAWK", api.updatePack(uuid, name = "OP HAWK", description = "Changed").name)

        // A malformed operation refuses the whole batch, and says which and why.
        val malformed = assertThrows<ApiException> {
            api.sendPackOps(
                uuid,
                ops(null, """{"type": "set", "item": "lz-1", "path": ["note"], "value": 1, "client_op_id": "op-10"}""", """{"type": "set", "item": "lz-1", "path": ["note"], "client_op_id": "op-11"}"""),
            )
        }
        assertEquals("invalid_op", malformed.code)
        val which = malformed.body!!
        assertEquals(1, which.getValue("index").jsonPrimitive.int)
        assertEquals("bad_value", which.getValue("reason").jsonPrimitive.content)
        assertEquals(JsonNull, api.packItem(uuid, "lz-1").data!!.jsonObject["note"])          // nothing of it applied

        // A call made for someone else never reaches the server.
        val head = api.pack(uuid).headSeq
        assertThrows<OtherAccountException> { api.sendPackOps(uuid, ops(null, """{"type": "item.delete", "item": "lz-1", "client_op_id": "op-12"}"""), asUser = colin.id + 1) }
        assertEquals(head, api.pack(uuid).headSeq)

        // Finished: every edit is refused, and the refusal says who finished it and when.
        val finished = api.finishPack(uuid)
        assertEquals("finished", finished.status)
        assertEquals("Colin P.", finished.finishedBy?.name)
        val locked = assertThrows<ApiException> { api.sendPackOps(uuid, ops(null, """{"type": "item.delete", "item": "lz-1", "client_op_id": "op-20"}"""), asUser = colin.id) }
        assertEquals(423, locked.status)
        assertEquals("pack_finished", locked.code)
        val by = locked.body!!.getValue("finished_by").jsonObject
        assertEquals(colin.id, by.getValue("id").jsonPrimitive.int)
        assertEquals("Colin P.", by.getValue("name").jsonPrimitive.content)
        assertEquals(finished.finishedAt, locked.body.getValue("finished_at").jsonPrimitive.content)
        assertEquals(423, assertThrows<ApiException> { api.updatePack(uuid, name = "OP X") }.status)

        val reopened = api.reopenPack(uuid)
        assertEquals("active", reopened.status)
        assertNull(reopened.finishedAt)
        val after = api.sendPackOps(uuid, ops(null, """{"type": "item.rename", "item": "lz-1", "name": "LZ HAWK 2", "client_op_id": "op-21"}"""), asUser = colin.id)
        assertEquals("applied", after.getValue("results").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `items come in from the library and go back to it, and a pack is duplicated and deleted`() = runBlocking<Unit> {
        val colin = person("Colin P.")
        val api = colin.api
        val uuid = api.createPack("OP FALCON").value.uuid
        api.sendPackOps(uuid, ops(null, """{"type": "item.create", "item": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": {"note": "x"}, "client_op_id": "op-1"}"""))

        val saved = api.savePackItemToLibrary(uuid, "lz-1", name = "LZ HAWK (OP FALCON)")
        assertEquals("lz", saved.kind)
        assertTrue(api.listLzs().any { it.clientUuid == saved.clientUuid && it.name == "LZ HAWK (OP FALCON)" })

        val original = UUID.randomUUID().toString()
        val record = api.createLz("LZ FALCON", json("""{"schemaVersion": 2, "name": "LZ FALCON", "note": "first"}"""), original).value
        val copied = api.copyIntoPack(uuid, PackSource.ClientUuid("lz", original), item = "lz-2")
        assertTrue(copied.created)
        assertEquals(original, copied.value.item.source?.uuid)
        assertNotNull(copied.value.event)
        val again = api.copyIntoPack(uuid, PackSource.ClientUuid("lz", original), item = "lz-2")
        assertFalse(again.created, "the same copy asked for again is the first")
        assertNull(again.value.event)
        val byId = api.copyIntoPack(uuid, PackSource.Id("lz", record.id), item = "lz-3", name = "LZ FALCON TOO", summary = "Colin P. copied it again.")
        assertEquals("LZ FALCON TOO", byId.value.item.name)
        assertEquals("Colin P. copied it again.", byId.value.event?.summary)

        api.updateLz(record.id, baseRevision = null, lzData = json("""{"schemaVersion": 2, "name": "LZ FALCON", "note": "second"}"""))
        assertEquals("changed", api.packItem(uuid, "lz-2").source?.original)
        val updated = api.updateFromOriginal(uuid, "lz-2", summary = "Colin P. updated LZ FALCON.")
        assertEquals("second", updated.item.data!!.jsonObject.getValue("note").jsonPrimitive.content)
        assertEquals("Colin P. updated LZ FALCON.", updated.event?.summary)

        val copy = api.duplicatePack(uuid, name = "OP FALCON 2")
        assertEquals("OP FALCON 2", copy.name)
        assertEquals(listOf("lz-1", "lz-2", "lz-3"), copy.items.map { it.uuid }.sorted())
        assertEquals("OP FALCON (copy)", api.duplicatePack(uuid).name)
        assertEquals("deleted", api.deletePack(copy.uuid).status)
        assertEquals("pack_not_found", assertThrows<ApiException> { api.pack(copy.uuid) }.code)
    }

    @Test
    fun `people join by an emailed invitation, by a team's link and by name, and leave`() = runBlocking<Unit> {
        val colin = person("Colin P.")
        val sam = person("Sam B.")
        val uuid = colin.api.createPack("OP EAGLE").value.uuid
        val edit = ops(null, """{"type": "item.create", "item": "pts-1", "kind": "pointset", "name": "PTS", "data": [], "client_op_id": "op-${UUID.randomUUID()}"}""")

        // By email, accepted from the link.
        val invited = colin.api.inviteToPack(uuid, sam.email)
        assertTrue(invited.created)
        assertEquals(uuid, invited.value.invite.pack?.uuid)
        val invitedAgain = colin.api.inviteToPack(uuid, sam.email, role = "viewer")
        assertFalse(invitedAgain.created, "the same address again is the same invitation, with a new link")
        assertEquals(invited.value.invite.id, invitedAgain.value.invite.id)
        assertEquals(listOf(invited.value.invite.id), colin.api.packInvites(uuid).map { it.id })
        assertEquals("pending", colin.api.resendPackInvite(uuid, invited.value.invite.id).invite.status)
        assertEquals(listOf(invited.value.invite.id), sam.api.myInvites().map { it.id })
        val joined = sam.api.acceptInviteLink(live().emailed("pack_invite", sam.email))
        assertEquals(uuid, joined.pack?.uuid)
        assertEquals("viewer", joined.pack?.role)

        // A viewer is refused an edit; an editor is not.
        assertEquals("pack_read_only", assertThrows<ApiException> { sam.api.sendPackOps(uuid, edit, asUser = sam.id) }.code)
        assertEquals("editor", colin.api.changePackMember(uuid, sam.id, "editor").role)
        assertEquals("applied", sam.api.sendPackOps(uuid, edit, asUser = sam.id).getValue("results").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("removed", colin.api.removePackMember(uuid, sam.id).status)
        assertEquals("pack_not_found", assertThrows<ApiException> { sam.api.pack(uuid) }.code)

        // Declined, then accepted, by the invitation's id.
        val second = colin.api.inviteToPack(uuid, sam.email).value.invite
        assertEquals("declined", sam.api.declineInvite(second.id).status)
        val third = colin.api.inviteToPack(uuid, sam.email).value.invite
        assertEquals("editor", sam.api.acceptInvite(third.id).pack?.role)
        assertEquals("removed", colin.api.removePackMember(uuid, sam.id).status)

        val kim = colin.api.inviteToPack(uuid, "kim-${UUID.randomUUID()}@example.com").value.invite
        assertEquals("revoked", colin.api.revokePackInvite(uuid, kim.id).status)
        assertEquals(emptyList<Invite>(), colin.api.packInvites(uuid))

        // A team, joined by its single-use link. Only then can Colin find Sam by name and add him directly.
        val team = colin.api.createTeam("B Co 2-10 AVN")
        assertEquals("owner", team.role)
        assertEquals(emptyList<UserBrief>(), colin.api.searchPeople("Sam B"), "nobody outside a shared team is ever found")
        assertEquals("not_a_teammate", assertThrows<ApiException> { colin.api.addPackMember(uuid, sam.id) }.code)
        val link = colin.api.inviteToTeam(team.id)
        assertNull(link.invite.email)
        assertEquals(team.id, sam.api.acceptInviteLink(link.token!!).team?.id)
        assertEquals(listOf(sam.id), colin.api.searchPeople("Sam B").map { it.id })
        assertEquals(listOf(sam.id), colin.api.searchPeople(sam.email.take(12)).map { it.id })
        assertEquals("viewer", colin.api.addPackMember(uuid, sam.id, "viewer").role)

        assertEquals(listOf(team.id), colin.api.listTeams().map { it.id })
        assertEquals(setOf(colin.id, sam.id), colin.api.team(team.id).members.map { it.userId }.toSet())
        assertEquals("B Co", colin.api.renameTeam(team.id, "B Co").name)
        assertEquals("admin", colin.api.changeTeamMember(team.id, sam.id, "admin").members.single { it.userId == sam.id }.role)

        // Shared with the team, then not.
        assertEquals(team.id, colin.api.updatePack(uuid, share = TeamShare.With(team.id, "viewer")).team?.id)
        assertNull(colin.api.updatePack(uuid, share = TeamShare.None).team)

        val alex = "alex-${UUID.randomUUID()}@example.com"
        val byEmail = colin.api.inviteToTeam(team.id, alex)
        assertEquals(true, byEmail.emailSent)
        assertNull(byEmail.token)
        assertEquals(listOf(byEmail.invite.id), colin.api.teamInvites(team.id).map { it.id })
        assertEquals("revoked", colin.api.revokeTeamInvite(team.id, byEmail.invite.id).status)

        assertEquals("removed", colin.api.removeTeamMember(team.id, sam.id).status)
        assertEquals("deleted", colin.api.deleteTeam(team.id).status)
        assertEquals(emptyList<TeamSummary>(), colin.api.listTeams())
    }

    @Test
    fun `an account without mission packs is refused, and nothing else about it changes`() = runBlocking<Unit> {
        val kim = person("Kim N.", packs = false)
        val refused = assertThrows<ApiException> { kim.api.listPacks() }
        assertEquals(403, refused.status)
        assertEquals("feature_disabled", refused.code)
        assertFalse(refused is AffiliationRequiredException)
        assertEquals(0, kim.api.listLzs().size)                                            // the rest of the account works as ever
    }
}
