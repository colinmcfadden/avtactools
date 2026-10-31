package app.ezpztac.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit

/** A session store that remembers what was done to it, in order, from any thread. */
internal class RecordingStore(initial: StoredSession? = null) : SessionStore {
    private val inner = InMemorySessionStore(initial)
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What is stored now, without counting as a read. */
    @Volatile var current: StoredSession? = initial
        private set

    /** Run once, by the next read, before it answers: to stop a read half-way and do something in between. */
    @Volatile var beforeNextRead: (suspend () -> Unit)? = null

    override suspend fun read(): StoredSession? {
        val stored = inner.read()
        beforeNextRead?.let { beforeNextRead = null; it() }
        return stored
    }
    override suspend fun write(session: StoredSession) {
        inner.write(session); current = session
        events += "write ${session.accessToken}/${session.refreshToken}"
    }
    override suspend fun clear() { inner.clear(); current = null; events += "clear" }
    override suspend fun end(ended: EndedSession) { inner.end(ended); current = null; events += "end ${ended.code}" }
    override suspend fun ended(): EndedSession? = inner.ended()
}

internal fun session(access: String = "access-1", refresh: String? = "refresh-1") = StoredSession(
    accessToken = access,
    refreshToken = refresh,
    refreshExpiresAtEpochSeconds = 4_000_000_000,
    user = Recorded.user(),
)

/** A mock server, a client talking to it, and the pieces a test wants to look at. */
internal class Rig(
    initial: StoredSession? = session(),
    private val time: TimeSource = TimeSource.System,
    interceptor: Interceptor? = null,
    readTimeoutSeconds: Long = 5,
    refreshReadTimeoutSeconds: Long = ApiClient.REFRESH_READ_TIMEOUT_SECONDS,
) : AutoCloseable {
    val server = MockWebServer()
    val store = RecordingStore(initial)
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    val priority = PriorityGate()

    val client: ApiClient
    private val http: OkHttpClient

    init {
        server.start()
        http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .apply { if (interceptor != null) addInterceptor(interceptor) }
            .build()
        client = ApiClient(server.url("/").toString(), http, ClientInfo.android("1.4.0", 212), store, priority, time, refreshReadTimeoutSeconds)
    }

    /** Answers each request with [handler], keeping a copy of it. */
    fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.requestUrl?.encodedPath == path }

    /** A new client over the same store and server: what the next process to start would have. */
    fun nextProcess(): ApiClient = ApiClient(server.url("/").toString(), http, ClientInfo.android("1.4.0", 212), store, PriorityGate(), time)

    override fun close() { server.shutdown() }

    companion object {
        fun json(status: Int, body: String): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

        fun refreshed(access: String, refresh: String): MockResponse = Recorded.mock("refresh", mapOf("access-token" to access, "refresh-token" to refresh))

        fun bearer(request: RecordedRequest): String? = request.getHeader("Authorization")?.removePrefix("Bearer ")
    }
}

/** Runs the request, then throws as if the connection dropped before the answer got back: the server saw it, the app did not. */
internal class LoseAnswers(
    private val matches: (String) -> Boolean,
    private val times: Int = Int.MAX_VALUE,
    /** Runs after the server has answered and before the answer is thrown away, to do something in between. */
    private val onLost: () -> Unit = {},
) : Interceptor {
    @Volatile var lost = 0
        private set

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        if (matches(chain.request().url.encodedPath) && lost < times) {
            lost++
            response.close()
            onLost()
            throw SocketException("Connection reset")
        }
        return response
    }
}

/** Throws before anything is sent, as a refused connection would. */
internal class RefuseConnection(private val matches: (String) -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        if (matches(chain.request().url.encodedPath)) throw java.net.ConnectException("Connection refused")
        return chain.proceed(chain.request())
    }
}
