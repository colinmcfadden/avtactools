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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Teams' calls over the real API client: what each sends, how its answer is read, and that each is made for one account only. */
class ApiTeamsApiTest {
    private val me = 6
    private val server = MockWebServer().also { it.start() }
    private val user = ApiUser(id = me, email = "colin@example.com", name = "Colin", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = true)
    private val store = InMemorySessionStore(StoredSession("access", "refresh", 4_000_000_000, user))
    private val client = ApiClient(server.url("/").toString(), OkHttpClient(), ClientInfo.android("1.4.0", 212), store).also { runBlocking { it.restore() } }
    private val api = ApiTeamsApi(client)

    @After
    fun after() = server.shutdown()

    private fun answer(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun sent(): JsonElement = Json.parseToJsonElement(server.takeRequest().body.readUtf8())

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun team(role: String) = """{"id": 3, "name": "B Co", "role": "$role", "member_count": 1, "created_at": "x",
        "members": [{"user_id": 6, "name": "Colin", "email": "colin@example.com", "role": "$role", "joined_at": "x"}]}"""

    private fun invite(id: Int, status: String) = """{"created_at": "x", "email": "a$id@b.co", "expires_at": "x", "id": $id, "invited_by": {"id": 6, "name": "Colin"},
        "role": "member", "status": "$status", "team": {"id": 3, "name": "B Co"}}"""

    @Test
    fun `a team is read with, for its owner or an admin, the invitations not yet answered`() = runBlocking {
        answer(200, team("admin"))
        answer(200, """{"invites": [${invite(5, "pending")}, ${invite(6, "revoked")}]}""")
        val page = api.team(3, me)
        assertEquals(listOf(6), page.team.members.map { it.userId })
        assertEquals(listOf(5), page.invites!!.map { it.id })
        assertEquals("/api/teams/3", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/teams/3/invites", server.takeRequest().requestUrl!!.encodedPath)
        answer(200, team("member"))
        assertTrue(api.team(3, me).invites!!.isEmpty())
        assertEquals(3, server.requestCount)                                                        // a member's invitations are not asked for
    }

    @Test
    fun `invitations that could not be had leave the team, and another account between the two calls is no answer`() = runBlocking {
        answer(200, team("owner"))
        answer(500, """{"status": "error"}""")
        val page = api.team(3, me)
        assertEquals("B Co", page.team.name)
        assertNull(page.invites)
        server.takeRequest()
        server.takeRequest()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { store.write(StoredSession("access-b", "refresh-b", 4_000_000_000, user.copy(id = me + 1, email = "b@b.co"))) }
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(team("owner"))
            }
        }
        try {
            api.team(3, me)
            fail("answered for another account")
        } catch (_: OtherAccountException) {
        }
        assertEquals(3, server.requestCount)                                                        // the team only
    }

    @Test
    fun `an invitation says whether its email went, and what each change sends`() = runBlocking {
        answer(201, """{"email_sent": false, "invite": ${invite(5, "pending")}}""")
        assertFalse(api.invite(3, "a@b.co", "admin", me))
        assertEquals(json("""{"role": "admin", "email": "a@b.co"}"""), sent())
        answer(201, """{"invite": ${invite(5, "pending")}}""")
        assertTrue(api.invite(3, "a@b.co", "member", me))
        sent()
        answer(201, team("owner"))
        assertEquals("B Co", api.createTeam("B Co", me).name)
        assertEquals(json("""{"name": "B Co"}"""), sent())
        answer(200, team("owner"))
        api.renameTeam(3, "B CO", me)
        assertEquals(json("""{"name": "B CO"}"""), sent())
        answer(200, team("owner"))
        api.changeMember(3, 7, "admin", me)
        assertEquals(json("""{"role": "admin"}"""), sent())
    }

    @Test
    fun `nothing is asked for another account`() = runBlocking {
        val other = me + 1
        val calls: List<suspend () -> Unit> = listOf(
            { api.teams(other) }, { api.team(3, other) }, { api.createTeam("X", other) }, { api.renameTeam(3, "X", other) }, { api.deleteTeam(3, other) },
            { api.changeMember(3, 7, "admin", other) }, { api.removeMember(3, 7, other) }, { api.invite(3, "a@b.co", "member", other) }, { api.revokeInvite(3, 5, other) },
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
