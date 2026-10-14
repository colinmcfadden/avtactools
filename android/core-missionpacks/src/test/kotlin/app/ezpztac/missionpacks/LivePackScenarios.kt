package app.ezpztac.missionpacks

import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.InMemorySessionStore
import app.ezpztac.network.LiveServer
import app.ezpztac.network.acceptInviteLink
import app.ezpztac.network.changePackMember
import app.ezpztac.network.createPack
import app.ezpztac.network.finishPack
import app.ezpztac.network.inviteToPack
import app.ezpztac.network.packEventsDocument
import app.ezpztac.network.packItem
import app.ezpztac.network.removePackMember
import app.ezpztac.network.sendPackOps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.net.ConnectException
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

/**
 * The pack scenarios' world on the real server (`backend/tests/live_server.py`): every person a new account with Mission Packs ticked,
 * every device its own signed-in client, whose connection the scenario controls with an interceptor (no signal, an answer lost or held
 * after the server took the batch). The owner adds people as the screens will, by an emailed invitation whose link is accepted. There
 * is no live service here, so devices poll, as the Fly backend has them do.
 */
internal class LivePackEnv(private val server: LiveServer, scope: CoroutineScope, private val timing: PackTiming = PackScenarioBook.TIMING) : PackEnv {
    private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    private val devices = CoroutineScope(SupervisorJob() + dispatcher)

    // Each person's own client, for what they do outside the devices a scenario follows.
    private val accounts = HashMap<Int, ApiClient>()

    override suspend fun person(name: String): PackPerson {
        val email = "${name.lowercase().filter { it.isLetter() }}-${UUID.randomUUID()}@example.com"
        val id = server.makeAccount(email, features = mapOf("mission_packs" to true), name = name)
        accounts[id] = signedIn(email, null)
        return PackPerson(PackUser(id, name), email)
    }

    override suspend fun device(label: String, person: PackPerson): PackDevice {
        val network = Network()
        return PackDevice(label, person, ApiPackApi(signedIn(person.email, network)), network, InMemoryPackStore(), devices, dispatcher, timing)
    }

    override suspend fun createPack(owner: PackPerson, name: String): String = api(owner).createPack(name).value.uuid

    override suspend fun addMember(pack: String, owner: PackPerson, member: PackPerson, role: String) {
        server.clearRateLimits()
        api(owner).inviteToPack(pack, member.email, role)
        api(member).acceptInviteLink(server.emailed("pack_invite", member.email))
    }

    override suspend fun setRole(pack: String, owner: PackPerson, member: PackPerson, role: String) {
        api(owner).changePackMember(pack, member.user.id, role)
    }

    override suspend fun removeMember(pack: String, owner: PackPerson, member: PackPerson) {
        api(owner).removePackMember(pack, member.user.id)
    }

    override suspend fun finish(pack: String, owner: PackPerson) {
        api(owner).finishPack(pack)
    }

    override suspend fun send(pack: String, by: PackPerson, vararg ops: JsonObject) {
        val named = ops.map { JsonObject(it + ("client_op_id" to JsonPrimitive("env-${UUID.randomUUID()}"))) }
        api(by).sendPackOps(pack, JsonObject(mapOf("ops" to JsonArray(named))), asUser = by.user.id)
    }

    override suspend fun log(pack: String, reader: PackPerson): List<JsonObject> {
        val events = ArrayList<JsonObject>()
        var since = 0L
        while (true) {
            val page = api(reader).packEventsDocument(pack, since, 500, background = false, asUser = reader.user.id)
            val got = page.getValue("events").jsonArray.map { it.jsonObject }
            events += got
            if (got.isEmpty() || !page.getValue("has_more").jsonPrimitive.boolean) return events
            since = got.last().getValue("seq").jsonPrimitive.long
        }
    }

    override suspend fun data(pack: String, reader: PackPerson, item: String): JsonElement? = try {
        api(reader).packItem(pack, item).data
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    override fun pageSize(size: Int): Unit = throw UnsupportedOperationException("The real server gives up to 500 events a page")

    override fun limitItemSize(bytes: Int): Unit = throw UnsupportedOperationException("The real server's limit is 5 MB")

    override fun close() {
        devices.cancel()
    }

    private fun api(person: PackPerson): ApiClient = accounts.getValue(person.user.id)

    private suspend fun signedIn(email: String, network: Network?): ApiClient {
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .apply { if (network != null) addInterceptor(conditions(network)) }
            .build()
        val client = ApiClient(server.baseUrl, http, ClientInfo.android("1.4.0", 212), InMemorySessionStore())
        server.clearRateLimits()
        client.login(email, LiveServer.PASSWORD)
        return client
    }

    // The device's connection: nothing leaves it while offline, and the answer to a batch the server took can be lost, or held first.
    private fun conditions(network: Network) = Interceptor { chain ->
        if (network.offline) throw ConnectException("No connection.")
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method == "POST" && request.url.encodedPath.endsWith("/ops")) {
            val hold = network.takeHold()
            if (hold != null) {
                runBlocking { withTimeoutOrNull(30_000) { hold.await() } }
                response.close()
                throw SocketException("The answer was held, then lost.")
            }
            if (network.losesAnswer()) {
                response.close()
                throw SocketException("Connection reset")
            }
        }
        response
    }
}

/**
 * Every pack scenario ([PackScenarioBook]) against the real server, but those that need what only the fake can be told. Skipped without
 * `EZPZ_LIVE_PYTHON`; CI sets it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LivePackScenarios {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 3600)
    }

    @AfterAll
    fun stop() {
        server?.close()
    }

    @TestFactory
    fun scenarios(): List<DynamicTest> = PackScenarioBook.all.filterNot { it.fakeOnly }.map { scenario ->
        dynamicTest(scenario.name) {
            val live = server ?: error("no live server")
            runBlocking<Unit> { LivePackEnv(live, this).use { scenario.run(it) } }
        }
    }
}
