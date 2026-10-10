package app.ezpztac.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * A call started from a screen is started on the main thread, and a call resumes on the thread that made it. Android forbids reading a response on the main thread
 * (`NetworkOnMainThreadException`), which refused every threat terrain mask on a device while every JVM test passed. The whole exchange is on OkHttp's own threads, whoever asks.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ApiClientThreadTest {
    /** Records which thread reads the bytes of each response body. */
    private class RecordReadThreads : Interceptor {
        val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val original = response.body ?: return response
            val watched = object : ResponseBody() {
                override fun contentType(): MediaType? = original.contentType()
                override fun contentLength(): Long = original.contentLength()
                override fun source(): BufferedSource = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        threads += Thread.currentThread().name
                        return super.read(sink, byteCount)
                    }
                }.buffer()
            }
            return response.newBuilder().body(watched).build()
        }
    }

    @Test
    fun `a response is read off the thread that asked, as a screen's main thread must not be`() = runBlocking<Unit> {
        val recorder = RecordReadThreads()
        Rig(interceptor = recorder).use { rig ->
            rig.serve { Rig.json(200, """{"cursor": 5, "has_more": false, "changes": []}""") }
            val main = newSingleThreadContext("pretend-main")
            try {
                val feed = withContext(main) { rig.client.changes(0) }
                assertEquals(5, feed.cursor)
            } finally {
                main.close()
            }
        }
        assertTrue(recorder.threads.isNotEmpty(), "the body was never read")
        assertFalse(recorder.threads.any { it.startsWith("pretend-main") }, "the body was read on the caller's thread: ${recorder.threads}")
    }

    @Test
    fun `a file is read off the thread that asked too`() = runBlocking<Unit> {
        val recorder = RecordReadThreads()
        Rig(interceptor = recorder).use { rig ->
            rig.serve { okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("zip bytes") }
            val main = newSingleThreadContext("pretend-main")
            try {
                val bytes = withContext(main) { rig.client.routeFile(1) }
                assertEquals("zip bytes", String(bytes))
            } finally {
                main.close()
            }
        }
        assertFalse(recorder.threads.any { it.startsWith("pretend-main") }, "the file was read on the caller's thread: ${recorder.threads}")
    }
}
