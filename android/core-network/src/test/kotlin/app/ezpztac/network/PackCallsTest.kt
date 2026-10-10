package app.ezpztac.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * The mission-pack, team, invitation and people-search calls against a mock server replaying the real server's recorded answers
 * (`backend/tests/test_network_fixtures.py`): what each sends, where, and what it makes of the answer.
 */
class PackCallsTest {
    private val pack = "6d1c2e8a-4b3f-4a51-9e07-2f8b1c5d7a90"
    private val me = Recorded.user().id

    private fun json(text: String): JsonElement = ApiClient.JSON.parseToJsonElement(text)

    /** One call: the recorded answer to give it, and the request it must make. [body] null means none is sent. */
    private class Case(
        val name: String,
        val recorded: String,
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val body: String? = null,
        val check: (Any?) -> Unit = {},
        val call: suspend ApiClient.() -> Any?,
    )

    private val p = "/api/packs/$pack"

    private val cases = listOf(
        // Packs
        Case("listPacks", "packs", "GET", "/api/packs", check = { assertEquals(listOf("OP DK"), (it as List<*>).map { s -> (s as PackSummary).name }) }) { listPacks() },
        Case(
            "createPack", "pack: create", "POST", "/api/packs",
            body = """{"name": "OP DK", "description": "Air assault rehearsal", "uuid": "$pack"}""",
            check = { assertTrue((it as Saved<*>).created) },
        ) { createPack("OP DK", "Air assault rehearsal", uuid = pack) },
        Case(
            "createPack, again with its uuid", "pack: create again with the same uuid", "POST", "/api/packs",
            body = """{"name": "OP DK", "uuid": "$pack"}""",
            check = { assertFalse((it as Saved<*>).created) },                     // the first pack, not a second
        ) { createPack("OP DK", uuid = pack) },
        Case(
            "createPack, shared with a team", "pack: create", "POST", "/api/packs",
            body = """{"name": "OP DK", "team_id": 1, "team_role": "viewer"}""",
        ) { createPack("OP DK", share = TeamShare.With(1, "viewer")) },
        Case("createPack, with no team", "pack: create", "POST", "/api/packs", body = """{"name": "OP DK"}""") { createPack("OP DK", share = TeamShare.None) },
        Case("pack", "pack: get", "GET", p, check = { assertEquals(pack, (it as Pack).uuid) }) { pack(pack) },
        Case(
            "updatePack, a name and description", "pack: rename and describe", "PUT", p,
            body = """{"name": "OP EAGLE", "description": "Night air assault"}""",
        ) { updatePack(pack, name = "OP EAGLE", description = "Night air assault") },
        Case(
            "updatePack, shared with a team", "pack: share with a team", "PUT", p, body = """{"team_id": 1, "team_role": "viewer"}""",
            check = { assertEquals(1, (it as Pack).team?.id) },
        ) { updatePack(pack, share = TeamShare.With(1, "viewer")) },
        Case("updatePack, sharing stopped", "pack: share with a team", "PUT", p, body = """{"team_id": null}""") { updatePack(pack, share = TeamShare.None) },
        Case("deletePack", "pack: delete", "DELETE", p, check = { assertEquals("deleted", (it as StatusReply).status) }) { deletePack(pack) },
        Case("finishPack", "pack: finish", "POST", "$p/finish", check = { assertEquals("finished", (it as Pack).status) }) { finishPack(pack) },
        Case("reopenPack", "pack: reopen", "POST", "$p/reopen", check = { assertEquals("active", (it as Pack).status) }) { reopenPack(pack) },
        Case("duplicatePack", "pack: duplicate", "POST", "$p/duplicate", body = "{}") { duplicatePack(pack) },
        Case("duplicatePack, named", "pack: duplicate", "POST", "$p/duplicate", body = """{"name": "OP EAGLE 2"}""") { duplicatePack(pack, "OP EAGLE 2") },
        Case(
            "packEvents", "events: a page", "GET", "$p/events", query = mapOf("since" to "0", "limit" to "3"),
            check = { val page = it as PackEventPage; assertEquals(3L, page.cursor); assertTrue(page.hasMore) },
        ) { packEvents(pack, since = 0, limit = 3) },
        Case("packEvents, a whole page", "events: the rest", "GET", "$p/events", query = mapOf("since" to "3", "limit" to "500")) { packEvents(pack, since = 3) },
        Case("markPackSeen", "seen", "PUT", "$p/seen", body = """{"seq": 3}""", check = { assertEquals(3L, (it as PackSeenDto).seenSeq) }) { markPackSeen(pack, 3) },

        // Items
        Case(
            "copyIntoPack, by the record's uuid", "item: copy in from the library", "POST", "$p/items",
            body = """{"source": {"kind": "lz", "client_uuid": "0c4f8e2b-7a19-4c3d-a6e5-9b1d2f3e4a57"}, "item": "lz-2"}""",
            check = { val saved = it as Saved<*>; assertTrue(saved.created); assertEquals("lz-2", (saved.value as PackItemReply).item.uuid) },
        ) { copyIntoPack(pack, PackSource.ClientUuid("lz", "0c4f8e2b-7a19-4c3d-a6e5-9b1d2f3e4a57"), item = "lz-2") },
        Case(
            "copyIntoPack, by the record's id, named", "item: copy in again with the same item", "POST", "$p/items",
            body = """{"source": {"kind": "route", "id": 4}, "item": "rt-1", "name": "RT GOLD", "summary": "Sam added RT GOLD."}""",
            check = { val saved = it as Saved<*>; assertFalse(saved.created); assertNull((saved.value as PackItemReply).event) },
        ) { copyIntoPack(pack, PackSource.Id("route", 4), item = "rt-1", name = "RT GOLD", summary = "Sam added RT GOLD.") },
        Case(
            "packItem", "item: get", "GET", "$p/items/lz-2",
            check = { val item = it as PackItemDto; assertEquals("same", item.source?.original); assertTrue(item.data is JsonObject) },
        ) { packItem(pack, "lz-2") },
        Case("updateFromOriginal", "item: update from the original", "POST", "$p/items/lz-2/update-from-original", body = "{}") { updateFromOriginal(pack, "lz-2") },
        Case(
            "updateFromOriginal, with its sentence", "item: update from the original", "POST", "$p/items/lz-2/update-from-original",
            body = """{"summary": "Colin updated LZ FALCON."}""",
        ) { updateFromOriginal(pack, "lz-2", summary = "Colin updated LZ FALCON.") },
        Case("savePackItemToLibrary", "item: save to the library", "POST", "$p/items/lz-1/library", body = "{}") { savePackItemToLibrary(pack, "lz-1") },
        Case(
            "savePackItemToLibrary, named", "item: save to the library", "POST", "$p/items/lz-1/library", body = """{"name": "LZ HAWK (OP EAGLE)"}""",
            check = { assertEquals("lz", (it as LibraryCopy).kind) },
        ) { savePackItemToLibrary(pack, "lz-1", name = "LZ HAWK (OP EAGLE)") },

        // Members
        Case(
            "addPackMember", "member: add a teammate", "POST", "$p/members", body = """{"user_id": 7, "role": "editor"}""",
            check = { assertEquals(7, (it as PackMember).userId) },
        ) { addPackMember(pack, 7) },
        Case("changePackMember", "member: change a role", "PUT", "$p/members/7", body = """{"role": "viewer"}""") { changePackMember(pack, 7, "viewer") },
        Case("removePackMember", "member: remove", "DELETE", "$p/members/8", check = { assertEquals("removed", (it as StatusReply).status) }) { removePackMember(pack, 8) },

        // Invitations to a pack
        Case("packInvites", "pack invites", "GET", "$p/invites") { packInvites(pack) },
        Case(
            "inviteToPack", "pack invite: by email", "POST", "$p/invites", body = """{"email": "alex@example.com", "role": "editor"}""",
            check = { val saved = it as Saved<*>; assertTrue(saved.created); assertEquals(true, (saved.value as InviteReply).emailSent) },
        ) { inviteToPack(pack, "alex@example.com") },
        Case(
            "inviteToPack, the same address again", "pack invite: the same address again", "POST", "$p/invites",
            body = """{"email": "alex@example.com", "role": "viewer"}""",
            check = { assertFalse((it as Saved<*>).created) },                     // a new link in place of the old, not a second invitation
        ) { inviteToPack(pack, "alex@example.com", role = "viewer") },
        Case("resendPackInvite", "pack invite: send again", "POST", "$p/invites/5/resend") { resendPackInvite(pack, 5) },
        Case("revokePackInvite", "pack invite: withdraw", "DELETE", "$p/invites/6", check = { assertEquals("revoked", (it as Invite).status) }) { revokePackInvite(pack, 6) },

        // The caller's own invitations
        Case("myInvites", "invites: mine, to a pack", "GET", "/api/invites") { myInvites() },
        Case("acceptInvite", "invite: accept", "POST", "/api/invites/5/accept", check = { assertEquals(pack, (it as InviteAccepted).pack?.uuid) }) { acceptInvite(5) },
        Case("declineInvite", "invite: decline", "POST", "/api/invites/3/decline", check = { assertEquals("declined", (it as Invite).status) }) { declineInvite(3) },
        Case(
            "acceptInviteLink", "invite: accept from a link", "POST", "/api/invites/accept", body = """{"token": "the-link-token"}""",
            check = { assertEquals(1, (it as InviteAccepted).team?.id) },
        ) { acceptInviteLink("the-link-token") },

        // Teams
        Case("listTeams", "teams", "GET", "/api/teams") { listTeams() },
        Case("createTeam", "team: create", "POST", "/api/teams", body = """{"name": "B Co 2-10 AVN"}""", check = { assertEquals("owner", (it as Team).role) }) { createTeam("B Co 2-10 AVN") },
        Case("team", "team: get", "GET", "/api/teams/1") { team(1) },
        Case("renameTeam", "team: rename", "PUT", "/api/teams/1", body = """{"name": "B Co"}""") { renameTeam(1, "B Co") },
        Case("deleteTeam", "team: delete", "DELETE", "/api/teams/1") { deleteTeam(1) },
        Case("changeTeamMember", "team member: change a role", "PUT", "/api/teams/1/members/7", body = """{"role": "admin"}""") { changeTeamMember(1, 7, "admin") },
        Case("removeTeamMember", "team member: remove", "DELETE", "/api/teams/1/members/7") { removeTeamMember(1, 7) },
        Case("teamInvites", "team invites", "GET", "/api/teams/1/invites", check = { assertEquals(2, (it as List<*>).size) }) { teamInvites(1) },
        Case(
            "inviteToTeam, a link", "team invite: a link", "POST", "/api/teams/1/invites", body = """{"role": "member"}""",
            check = { val reply = it as InviteReply; assertEquals("<token>", reply.token); assertNull(reply.invite.email) },
        ) { inviteToTeam(1) },
        Case(
            "inviteToTeam, by email", "team invite: by email", "POST", "/api/teams/1/invites", body = """{"role": "admin", "email": "alex@example.com"}""",
        ) { inviteToTeam(1, "alex@example.com", role = "admin") },
        Case("revokeTeamInvite", "team invite: withdraw", "DELETE", "/api/teams/1/invites/4") { revokeTeamInvite(1, 4) },

        // People
        Case(
            "searchPeople", "users: search", "GET", "/api/users/search", query = mapOf("q" to "sam & co"),
            check = { assertEquals(listOf(7), (it as List<*>).map { u -> (u as UserBrief).id }) },
        ) { searchPeople("sam & co") },

        // The engine's
        Case(
            "packDocument", "pack: get", "GET", p,
            check = { assertEquals(JsonNull, (it as JsonObject)["live_url"]) },          // what the server said, null and all
        ) { packDocument(pack) },
        Case(
            "packEventsDocument", "events: a page", "GET", "$p/events", query = mapOf("since" to "0", "limit" to "500"),
            check = { assertEquals(3L, (it as JsonObject)["cursor"]!!.jsonPrimitive.long) },
        ) { packEventsDocument(pack, since = 0, background = false) },
        Case(
            "sendPackOps", "ops: make an LZ, a route set and a point set", "POST", "$p/ops",
            body = """{"ops": [{"type": "item.rename", "item": "lz-1", "name": "LZ HAWK", "client_op_id": "op-1"}], "base_seq": 1}""",
        ) { sendPackOps(pack, json("""{"ops": [{"type": "item.rename", "item": "lz-1", "name": "LZ HAWK", "client_op_id": "op-1"}], "base_seq": 1}""").jsonObject) },
    )

    @TestFactory
    fun `every call goes to its route, with its method, query and body, and reads the answer`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            runBlocking {
                Rig().use { rig ->
                    rig.serve { Recorded.mock(case.recorded) }
                    val result = case.call(rig.client)

                    val request = rig.requests.single()
                    val url = request.requestUrl!!
                    assertEquals(case.method, request.method)
                    assertEquals(case.path, url.encodedPath)
                    assertEquals(case.query, url.queryParameterNames.associateWith { url.queryParameter(it) })
                    val sent = request.body.readUtf8()
                    if (case.body == null) {
                        assertEquals("", sent, "no body")
                    } else {
                        assertEquals(json(case.body), json(sent))
                        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
                    }
                    assertEquals("Bearer access-1", request.getHeader("Authorization"))
                    assertEquals("android/1.4.0 (212)", request.getHeader(ClientInfo.HEADER))
                    case.check(result)
                }
            }
        }
    }

    @Test
    fun `every pack route in the recordings is called by one of these`() {
        // A route the server answers that no call here reaches is one the app cannot use; the live service's own is the exception.
        val routes = Recorded.all.filter { e -> listOf("/api/packs", "/api/teams", "/api/invites", "/api/users").any { e.path.startsWith(it) } }
            .map { "${it.method} ${shape(it.path)}" }.toSet() - "GET /api/packs/{}/access"
        val called = cases.map { "${it.method} ${shape(it.path)}" }.toSet()
        assertEquals(emptySet<String>(), routes - called)
    }

    /** A path with its ids taken out: `/api/packs/{}/items/{}`. */
    private fun shape(path: String): String = path.split('/').mapIndexed { i, segment ->
        val previous = path.split('/').getOrNull(i - 1)
        if (previous in setOf("packs", "items", "teams", "members", "invites") && segment !in setOf("accept", "")) "{}" else segment
    }.joinToString("/")

    // -- What an operation carries ---------------------------------------------------------------

    @Test
    fun `a null in an operation is sent as null, and the answer keeps the nulls the server wrote`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("ops: edits, one skipped, with the events after base_seq") }
            val batch = json(
                """{"ops": [
                    {"type": "set", "item": "lz-1", "path": ["flightData", "callSign"], "value": null, "client_op_id": "op-5"},
                    {"type": "insert", "item": "lz-1", "path": ["graphics", "helicopters"], "after": null, "value": {"id": 2}, "client_op_id": "op-6"}
                ], "base_seq": 6}""",
            ).jsonObject
            val answer = rig.client.sendPackOps(pack, batch, asUser = me)

            val sent = rig.requests.single().body.readUtf8()
            assertTrue(sent.contains("\"value\":null") && sent.contains("\"after\":null"), sent)
            assertEquals(batch, json(sent))
            val ops = answer["events"]!!.jsonArray.map { it.jsonObject }.associateBy { it["client_op_id"]!!.jsonPrimitive.content }
            assertEquals(JsonNull, ops.getValue("op-5")["op"]!!.jsonObject["value"])
            assertEquals(JsonNull, ops.getValue("op-6")["op"]!!.jsonObject["after"])
            assertEquals(JsonNull, ops.getValue("op-5")["reason"])                       // a null field is there, not dropped
        }
    }

    // -- Ids --------------------------------------------------------------------------------------

    @Test
    fun `an id that could reach another route is refused before anything is sent`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("pack: get") }
            val packs = listOf("..", ".", "a/b", "$pack/../teams", "", "a".repeat(37), "has space", "%2e%2e", "abc\n", "abc?x=1", "abc#x")
            for (bad in packs) {
                assertThrows<IllegalArgumentException>(bad) { rig.client.pack(bad) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.packDocument(bad) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.sendPackOps(bad, JsonObject(emptyMap())) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.packEventsDocument(bad, 0, background = true) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.inviteToPack(bad, "a@example.com") }
            }
            val items = listOf("..", ".x", "_x", "a/b", "a b", "", "x".repeat(65), "lz-1/../../../teams", "a?b", "a%2fb")
            for (bad in items) {
                assertThrows<IllegalArgumentException>(bad) { rig.client.packItem(pack, bad) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.updateFromOriginal(pack, bad) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.savePackItemToLibrary(pack, bad) }
            }
            assertTrue(rig.requests.isEmpty(), "nothing was sent")
        }
    }

    @Test
    fun `an id at the edge of what the server makes still goes`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("item: get") }
            val item = "a" + "b.c:d-e_".repeat(7) + "f".repeat(7)                       // 64 characters, every one the server allows
            assertEquals(64, item.length)
            rig.client.packItem("A1-b2", item)
            assertEquals("/api/packs/A1-b2/items/$item", rig.requests.single().requestUrl!!.encodedPath)
        }
    }

    // -- Whose call it is ---------------------------------------------------------------------------

    @Test
    fun `a call made for one account is refused under another's session, before anything is sent`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("pack: get") }
            val other = me + 1
            val refused = assertThrows<OtherAccountException> { rig.client.packDocument(pack, asUser = other) }
            assertEquals("other_account", refused.code)
            assertEquals(0, refused.status)
            assertThrows<OtherAccountException> { rig.client.packEventsDocument(pack, 0, background = false, asUser = other) }
            assertThrows<OtherAccountException> { rig.client.sendPackOps(pack, JsonObject(emptyMap()), asUser = other) }
            assertThrows<OtherAccountException> { rig.client.acceptInviteLink("the-link-token", asUser = other) }   // a link is joined for no one else
            assertTrue(rig.requests.isEmpty())

            rig.client.packDocument(pack, asUser = me)                                     // the account it was made for goes as ever
            assertEquals(1, rig.requests.size)
        }
    }

    @Test
    fun `a call refused while someone else signed in is not sent again under their session`() = runBlocking<Unit> {
        val someoneElse = session(access = "access-B", refresh = "refresh-B")
            .copy(user = Recorded.user().copy(id = me + 1, email = "someone.else@example.com"))
        Rig().use { rig ->
            rig.serve {
                // While this call is out, the person signs out and someone else signs in; the old token is then refused.
                runBlocking { rig.store.write(someoneElse) }
                Rig.json(401, """{"msg": "Token has been revoked"}""")
            }
            assertThrows<OtherAccountException> { rig.client.sendPackOps(pack, JsonObject(emptyMap()), asUser = me) }
            assertEquals(listOf("access-1"), rig.requests.map { Rig.bearer(it) })          // never once with the other session's token
            assertEquals(someoneElse, rig.store.current)                                     // and their session is left alone
        }
    }

    @Test
    fun `a call without an account to answer to sends under whoever is signed in, as every other call does`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("pack: get") }
            rig.client.packDocument(pack)
            assertEquals("access-1", Rig.bearer(rig.requests.single()))
        }
    }

    // -- Refusals ----------------------------------------------------------------------------------

    @Test
    fun `a refusal carries the rest of the server's answer`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("ops: a finished pack") }
            val finished = assertThrows<ApiException> { rig.client.sendPackOps(pack, JsonObject(emptyMap()), asUser = me) }
            assertEquals(423, finished.status)
            assertEquals("pack_finished", finished.code)
            val by = finished.body!!.getValue("finished_by").jsonObject
            assertEquals("Sam", by.getValue("name").jsonPrimitive.content)
            assertEquals(7L, by.getValue("id").jsonPrimitive.long)
            assertEquals("<timestamp>", finished.body.getValue("finished_at").jsonPrimitive.content)

            rig.serve { Recorded.mock("ops: a malformed operation") }
            val malformed = assertThrows<ApiException> { rig.client.sendPackOps(pack, JsonObject(emptyMap())) }
            assertEquals("invalid_op", malformed.code)
            val which = malformed.body!!
            assertEquals(1L, which.getValue("index").jsonPrimitive.long)
            assertEquals("bad_value", which.getValue("reason").jsonPrimitive.content)

            rig.serve { Recorded.mock("ops: an item that would pass 5 MB") }
            val large = assertThrows<ApiException> { rig.client.sendPackOps(pack, JsonObject(emptyMap())) }
            assertEquals(413, large.status)
            assertEquals("lz-1", large.body!!["item"]!!.jsonPrimitive.content)

            rig.serve { Recorded.mock("ops: by a viewer") }
            assertEquals("pack_read_only", assertThrows<ApiException> { rig.client.sendPackOps(pack, JsonObject(emptyMap())) }.code)

            rig.serve { Recorded.mock("pack: rename a finished pack") }
            assertEquals("pack_finished", assertThrows<ApiException> { rig.client.updatePack(pack, name = "OP X") }.code)
        }
    }

    @Test
    fun `an account without mission packs is told so, and it is not the affiliation gate`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("packs: an account without mission packs") }
            val refused = assertThrows<ApiException> { rig.client.listPacks() }
            assertFalse(refused is AffiliationRequiredException)
            assertEquals(403, refused.status)
            assertEquals("feature_disabled", refused.code)
            assertEquals("feature_disabled", refused.body!!["code"]!!.jsonPrimitive.content)
            assertEquals(Recorded.user(), rig.store.current!!.user)                          // the session is untouched
        }
    }

    @Test
    fun `a refusal with no JSON in it has no body, and too many invitations says so in its own type`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>") }
            val gateway = assertThrows<ApiException> { rig.client.packDocument(pack) }
            assertEquals(502, gateway.status)
            assertNull(gateway.body)

            rig.serve { Recorded.mock("pack invite: too many") }
            assertInstanceOf(RateLimitedException::class.java, assertThrows<ApiException> { rig.client.inviteToPack(pack, "a@example.com") })
        }
    }

    // -- Priority ----------------------------------------------------------------------------------

    @Test
    fun `a catch-up in the background waits while an analysis runs, and one someone is waiting on does not`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("events: the rest") }
            val analysis = rig.priority.begin()
            val background = async(Dispatchers.Default) { rig.client.packEventsDocument(pack, 3, background = true) }
            withTimeout(10_000) { withContext(Dispatchers.Default) { rig.client.packEventsDocument(pack, 3, background = false) } }
            delay(300)
            assertEquals(1, rig.requests.size, "only the one someone is waiting on went")
            assertFalse(background.isCompleted)

            analysis.close()
            withTimeout(10_000) { background.await() }
            assertEquals(2, rig.requests.size)
        }
    }
}
