package app.ezpztac.packs

import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.KeptVersion
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiUser
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.InMemorySessionStore
import app.ezpztac.network.OtherAccountException
import app.ezpztac.network.StoredSession
import app.ezpztac.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The calls updating a copy and saving a pack's item make: what each sends, for one account only, and a copy kept on the device as a record of its own. */
class ApiPackItemCallsTest {
    private val me = 6
    private val server = MockWebServer().also { it.start() }
    private val user = ApiUser(id = me, email = "colin@example.com", name = "Colin", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = true)
    private val store = InMemorySessionStore(StoredSession("access", "refresh", 4_000_000_000, user))
    private val client = ApiClient(server.url("/").toString(), OkHttpClient(), ClientInfo.android("1.4.0", 212), store).also { runBlocking { it.restore() } }

    private class Keeper : PackKeeper {
        val kept = mutableListOf<KeptVersion>()
        var answer: (KeptVersion) -> KeptOutcome = { KeptOutcome.Saved(it.key, it.kind, "lib-${it.key}", it.name) }
        override suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome> = versions.map { kept += it; answer(it) }
    }

    private class Scheduler : SyncScheduler {
        var asked = 0
        override fun requestSync() {
            asked++
        }
        override fun schedulePeriodic() = Unit
        override fun cancelAll() = Unit
    }

    private val keeper = Keeper()
    private val scheduler = Scheduler()
    private val calls = ApiPackItemCalls(client, keeper, scheduler)

    @After
    fun after() = server.shutdown()

    private fun answer(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    // As the server answers (contracts/fixtures/network/responses.json, "item: get"), the original changed.
    private val itemBody = """{"head_seq": 12, "item": {"created_at": "x", "created_by": {"id": 6, "name": "Colin"}, "data": {"name": "LZ FALCON"}, "kind": "lz",
        "name": "LZ FALCON", "revision": 1, "seq": 12, "source": {"kind": "lz", "last_pack_change": null, "original": "changed", "original_updated_at": "x",
        "pack_changes": 2, "revision": 1, "uuid": "0c4f8e2b-7a19-4c3d-a6e5-9b1d2f3e4a57"}, "updated_at": "x", "updated_by": {"id": 6, "name": "Colin"}, "uuid": "lz-2"}}"""

    @Test
    fun `what the original says is read from the item, and the update goes to its own route`() = runBlocking {
        answer(200, itemBody)
        val source = calls.source("dk", "lz-2", me)!!
        assertEquals("changed", source.original)
        assertEquals(2, source.packChanges)
        assertEquals("/api/packs/dk/items/lz-2", server.takeRequest().requestUrl!!.encodedPath)
        answer(200, """{"event": null, "head_seq": 14, "item": ${itemBody.substringAfter("\"item\": ").dropLast(1)}}""")
        calls.updateFromOriginal("dk", "lz-2", me)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/packs/dk/items/lz-2/update-from-original", sent.requestUrl!!.encodedPath)
    }

    @Test
    fun `neither call is made under another account's session`() = runBlocking {
        for (call in listOf<suspend () -> Unit>({ calls.source("dk", "lz-2", me + 1) }, { calls.updateFromOriginal("dk", "lz-2", me + 1) })) {
            try {
                call()
                fail("made for another account")
            } catch (_: OtherAccountException) {
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `every copy saved is a record of its own, kept as it is, and a sync is asked for only when one was made`() = runBlocking {
        val set = buildJsonObject { put("version", JsonPrimitive(2)); put("routes", kotlinx.serialization.json.JsonArray(emptyList())); put("note", JsonPrimitive("kept")) }
        calls.saveToLibrary("dk", "route", "INGRESS copy", set)
        calls.saveToLibrary("dk", "route", "INGRESS copy", set)
        assertNotEquals(keeper.kept[0].key, keeper.kept[1].key)
        assertTrue(keeper.kept.all { it.asIs && it.key.startsWith("copy:dk:") && it.data == set && it.name == "INGRESS copy" })
        assertEquals(2, scheduler.asked)
        keeper.answer = { KeptOutcome.NothingToKeep(it.key, "empty_point_set") }
        assertTrue(calls.saveToLibrary("dk", "pointset", "POINTS copy", kotlinx.serialization.json.JsonArray(emptyList())) is KeptOutcome.NothingToKeep)
        assertEquals(2, scheduler.asked)
        assertEquals(0, server.requestCount)                                                         // on the device: nothing sent
    }
}
