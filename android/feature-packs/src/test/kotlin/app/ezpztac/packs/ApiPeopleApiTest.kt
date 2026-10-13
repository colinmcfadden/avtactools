package app.ezpztac.packs

import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiUser
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.InMemorySessionStore
import app.ezpztac.network.OtherAccountException
import app.ezpztac.network.StoredSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The calls about people over the real API client: what each sends, how its answer is read, and that each is made for one account only. */
class ApiPeopleApiTest {
    private val me = 6
    private val server = MockWebServer().also { it.start() }
    private val user = ApiUser(id = me, email = "colin@example.com", name = "Colin", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = true)
    private val client = ApiClient(
        server.url("/").toString(), OkHttpClient(), ClientInfo.android("1.4.0", 212), InMemorySessionStore(StoredSession("access", "refresh", 4_000_000_000, user)),
    ).also { runBlocking { it.restore() } }
    private val api = ApiPeopleApi(client)

    @After
    fun after() = server.shutdown()

    private fun answer(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun sent(): JsonElement = Json.parseToJsonElement(server.takeRequest().body.readUtf8())

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun pack(team: String = "null") = """{"audience_count": 1, "created_at": "2026-10-12T10:00:00", "description": "", "finished_at": null, "finished_by": null, "head_seq": 1,
        "item_count": 0, "item_counts": {"lz": 0, "pointset": 0, "route": 0}, "items": [], "live_url": null, "member_count": 1, "members": [], "name": "OP DK",
        "owner": {"id": 6, "name": "Colin"}, "role": "owner", "seen_seq": 0, "status": "active", "team": $team, "updated_at": "2026-10-12T10:00:00", "uuid": "u-1"}"""

    @Test
    fun `a pack made says whether this try made it, and who shares it as what`() = runBlocking {
        answer(201, pack(team = """{"id": 3, "member_count": 18, "name": "B Co", "role": "viewer"}"""))
        assertEquals(PackCreated("u-1", "OP DK", "", TeamPick(3, "viewer"), created = true), api.createPack("OP DK", "", TeamPick(3, "viewer"), "u-1", me))
        assertEquals(json("""{"name": "OP DK", "description": "", "team_id": 3, "team_role": "viewer", "uuid": "u-1"}"""), sent())
        answer(200, pack())
        assertEquals(PackCreated("u-1", "OP DK", "", null, created = false), api.createPack("OP DK", "", null, "u-1", me))
        assertEquals(json("""{"name": "OP DK", "description": "", "uuid": "u-1"}"""), sent())       // no team: none named
    }

    @Test
    fun `a change sends only what it names, and no team is sent as none, which stops the sharing`() = runBlocking {
        answer(200, pack())
        api.updatePack("u-1", PackChange(setTeam = true, team = null), me)
        assertEquals(json("""{"team_id": null}"""), sent())
        answer(200, pack())
        api.updatePack("u-1", PackChange(description = "Night"), me)
        assertEquals(json("""{"description": "Night"}"""), sent())
        answer(200, pack())
        api.updatePack("u-1", PackChange(name = "OP DUKE", setTeam = true, team = TeamPick(3, "editor")), me)
        assertEquals(json("""{"name": "OP DUKE", "team_id": 3, "team_role": "editor"}"""), sent())
    }

    @Test
    fun `an invitation whose email did not go says so, and one that does not say is taken as sent`() = runBlocking {
        val invite = """"invite": {"created_at": "x", "email": "a@b.co", "expires_at": "x", "id": 5, "invited_by": {"id": 6, "name": "Colin"}, "role": "viewer", "status": "pending", "team": null}"""
        answer(201, """{"email_sent": false, $invite}""")
        assertFalse(api.invite("u-1", "a@b.co", "viewer", me))
        assertEquals(json("""{"email": "a@b.co", "role": "viewer"}"""), sent())
        answer(201, """{$invite}""")
        assertTrue(api.invite("u-1", "a@b.co", "viewer", me))
        sent()
        answer(201, """{"member": {"added_at": "x", "email": "sam@example.com", "name": "Sam", "role": "editor", "seen_at": null, "user_id": 7}}""")
        api.addMember("u-1", 7, "editor", me)
        assertEquals(json("""{"user_id": 7, "role": "editor"}"""), sent())
    }

    @Test
    fun `nothing is asked for another account`() = runBlocking {
        val other = me + 1
        val calls: List<suspend () -> Unit> = listOf(
            { api.teams(other) }, { api.searchPeople("sam", other) }, { api.createPack("OP DK", "", null, "u-1", other) },
            { api.updatePack("u-1", PackChange(name = "X"), other) }, { api.addMember("u-1", 7, "editor", other) }, { api.invite("u-1", "a@b.co", "editor", other) },
        )
        calls.forEachIndexed { i, call ->
            try {
                call()
                fail("call $i was made for another account")
            } catch (_: OtherAccountException) {
            }
        }
        assertEquals(0, server.requestCount)
    }
}
