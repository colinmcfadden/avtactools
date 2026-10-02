package app.ezpztac.sync

import app.ezpztac.network.ApiClient
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.InMemorySessionStore
import app.ezpztac.network.LiveServer
import app.ezpztac.network.SyncChange
import app.ezpztac.network.changes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The scenarios against the real server (`backend/tests/live_server.py`): one account per scenario, a device per client. */
internal class LiveEnv(private val server: LiveServer) : Env {
    private val email = "sync-${UUID.randomUUID()}@example.com".also { server.makeAccount(it) }

    private fun client(interceptor: okhttp3.Interceptor? = null): ApiClient {
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .apply { if (interceptor != null) addInterceptor(interceptor) }.build()
        val client = ApiClient(server.baseUrl, http, ClientInfo.android("1.4.0", 212), InMemorySessionStore())
        server.clearRateLimits()
        runBlocking { client.login(email, LiveServer.PASSWORD) }
        return client
    }

    override fun device(label: String): Device = Device(label, ApiSyncApi(client()), IdSource.Random)

    /** A device whose connection drops the answer to some of its requests *after* the server has handled them. */
    fun flakyDevice(label: String, loses: (method: String, path: String) -> Boolean, times: Int = 1): Pair<Device, () -> Int> {
        val lost = java.util.concurrent.atomic.AtomicInteger()
        val interceptor = okhttp3.Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (loses(chain.request().method, chain.request().url.encodedPath) && lost.get() < times) {
                lost.incrementAndGet()
                response.close()
                throw java.net.SocketException("Connection reset")
            }
            response
        }
        return Device(label, ApiSyncApi(client(interceptor)), IdSource.Random) to { lost.get() }
    }

    override suspend fun serverView(): List<SyncChange> {
        val reader = client()
        val out = mutableListOf<SyncChange>()
        var cursor = 0
        while (true) {
            val feed = reader.changes(cursor)
            out += feed.changes
            cursor = feed.cursor
            if (!feed.hasMore) return out
        }
    }

    override fun close() {}
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class LiveSyncScenarios : ScenarioSuite() {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 3600)
    }

    @AfterAll
    fun stop() { server?.close() }

    override fun env(): Env = LiveEnv(server ?: error("no live server"))
}

/** What only the real server can say: its idempotency keys really do make a repeated write the same write. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class LiveSyncFailures {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 3600)
    }

    @AfterAll
    fun stop() { server?.close() }

    private fun env() = LiveEnv(server ?: error("no live server"))
    private val kind = RecordKind.LZ

    @org.junit.jupiter.api.Test
    fun `a create whose answer was lost does not make a second record on the real server`() = runBlocking<Unit> {
        val env = env()
        val (a, lost) = env.flakyDevice("A", { method, path -> method == "POST" && path == "/api/lz" })
        val made = a.repository.create(kind, "HAWK", content(kind, "one"))
        org.junit.jupiter.api.Assertions.assertEquals(StopReason.Offline, a.sync().stopped)
        org.junit.jupiter.api.Assertions.assertEquals(1, lost())
        org.junit.jupiter.api.Assertions.assertEquals(1, env.live(kind).size)                    // it did reach the server

        org.junit.jupiter.api.Assertions.assertNull(a.sync().stopped)
        org.junit.jupiter.api.Assertions.assertEquals(listOf("HAWK"), env.live(kind).map { it.name })
        org.junit.jupiter.api.Assertions.assertEquals(1, env.live(kind).single().revision)        // the repeat was the same write
        org.junit.jupiter.api.Assertions.assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)
    }

    @org.junit.jupiter.api.Test
    fun `an update whose answer was lost, then edited again, settles without a conflict on the real server`() = runBlocking<Unit> {
        val env = env()
        val (a, lost) = env.flakyDevice("A", { method, path -> method == "PUT" && path.startsWith("/api/lz/") })
        val made = a.repository.create(kind, "HAWK", content(kind, "one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "v2")
        org.junit.jupiter.api.Assertions.assertEquals(StopReason.Offline, a.sync().stopped)
        a.repository.edit(kind, made.uuid, name = "v3")

        val report = a.sync()
        org.junit.jupiter.api.Assertions.assertEquals(1, lost())
        org.junit.jupiter.api.Assertions.assertTrue(report.conflicts.isEmpty())
        org.junit.jupiter.api.Assertions.assertEquals(listOf("v3"), env.live(kind).map { it.name })
        org.junit.jupiter.api.Assertions.assertEquals(3, env.live(kind).single().revision)
    }

    @org.junit.jupiter.api.Test
    fun `a delete whose answer was lost is repeated on the real server and the record stays deleted`() = runBlocking<Unit> {
        val env = env()
        val (a, _) = env.flakyDevice("A", { method, path -> method == "DELETE" && path.startsWith("/api/lz/") })
        val made = a.repository.create(kind, "HAWK", content(kind, "one"))
        a.sync()
        a.repository.delete(kind, made.uuid)
        org.junit.jupiter.api.Assertions.assertEquals(StopReason.Offline, a.sync().stopped)
        org.junit.jupiter.api.Assertions.assertNull(a.sync().stopped)
        org.junit.jupiter.api.Assertions.assertTrue(env.live(kind).isEmpty())
        org.junit.jupiter.api.Assertions.assertNull(a.store.transaction { record(kind, made.uuid) })
    }
}
