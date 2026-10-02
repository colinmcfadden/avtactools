package app.ezpztac.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The real Flask server (`backend/tests/live_server.py`) in a child process, for tests that want the actual
 * routes and token rotation behind a real socket. Needs a Python with the server's packages: set
 * `EZPZ_LIVE_PYTHON` to it. Without that, [startOrNull] returns null and the tests that use it are skipped.
 */
internal class LiveServer private constructor(private val process: Process, val port: Int) : AutoCloseable {
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private fun post(path: String, body: String): String {
        val request = Request.Builder().url("$baseUrl$path").post(body.toRequestBody(json)).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "POST $path answered ${response.code}: ${response.body?.string()}" }
            return response.body!!.string()
        }
    }

    /** A verified account. [approved] false leaves it outside the `.mil` / approval gate. */
    fun makeAccount(email: String, password: String = PASSWORD, approved: Boolean = true) {
        post("/__test__/account", """{"email":"$email","password":"$password","approved":$approved}""")
    }

    /** Moves every spent refresh token this many seconds into the past: time passing, for the 30 s grace period. */
    fun ageSpentRefreshTokens(seconds: Int) {
        post("/__test__/age-refresh", """{"seconds":$seconds}""")
    }

    override fun close() {
        runCatching { post("/__test__/stop", "{}") }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        const val PASSWORD = "a secure flight password"

        /** True when the live tests can run here. */
        val available: Boolean get() = !System.getenv("EZPZ_LIVE_PYTHON").isNullOrBlank()

        fun startOrNull(accessTokenSeconds: Int = 2): LiveServer? {
            val python = System.getenv("EZPZ_LIVE_PYTHON")?.takeIf { it.isNotBlank() } ?: return null
            val contracts = File(System.getProperty("ezpz.contracts") ?: error("ezpz.contracts is not set"))
            val backend = File(contracts.parentFile.parentFile, "backend")
            val script = File(backend, "tests/live_server.py")
            check(script.isFile) { "no live server at $script" }

            val process = ProcessBuilder(python, "-B", script.path)
                .directory(backend)
                .redirectErrorStream(true)
                .apply {
                    environment()["EZPZ_ACCESS_TOKEN_SECONDS"] = accessTokenSeconds.toString()
                    environment()["PYTHONUNBUFFERED"] = "1"
                    environment()["PYTHONDONTWRITEBYTECODE"] = "1"
                }
                .start()

            val port = AtomicReference<Int?>()
            val ready = CountDownLatch(1)
            val output = StringBuffer()
            thread(isDaemon = true, name = "live-server-output") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    output.append(line).append('\n')
                    if (line.startsWith("READY ")) { port.set(line.removePrefix("READY ").trim().toInt()); ready.countDown() }
                }
                ready.countDown()                                                    // the process ended
            }
            if (!ready.await(90, TimeUnit.SECONDS) || port.get() == null) {
                process.destroyForcibly()
                error("The live server did not start:\n$output")
            }
            return LiveServer(process, port.get()!!)
        }
    }
}
