package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.LidarBuildDto
import app.ezpztac.network.LidarFrameDto
import app.ezpztac.network.LidarResolveDto
import app.ezpztac.network.LidarTargetDto
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding or building a point cloud, held to the web's `useLidarTileset` (contracts/fixtures/viewer3d/lidarFlow.json, and asked with `stream`,
 * lidarFlowStream.json, whose cases also hold what is shown of a streamed survey: `streamed` and `frame`): every scenario replayed against
 * a stand-in server that answers each request as the web was answered, checking each request (what was asked, with what, how long after the last
 * answer), what the view shows after each answer, and what would be asked next. What is said of a failure is the app's own ([LidarProblem]), so a
 * failure is held to the request that failed rather than to the web's words.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LidarBuildsFixtureTest {
    private val fixture = Fixtures.load("viewer3d/lidarFlow.json")
    private val streamFixture = Fixtures.load("viewer3d/lidarFlowStream.json")
    private val watcher = "android-fixture-watcher"
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** A request the run is waiting on, and how to answer it. */
    private class Pending(val request: JsonObject, val answer: CompletableDeferred<Any>)

    private class Server : LidarApi {
        val pending = ArrayDeque<Pending>()
        val released = mutableListOf<JsonObject>()

        private suspend fun ask(request: JsonObject): Any {
            val deferred = CompletableDeferred<Any>()
            pending.addLast(Pending(request, deferred))
            return deferred.await()
        }

        override suspend fun resolve(at: LatLon, radiusM: Double?, stream: Boolean): LidarResolveDto =
            ask(request("POST", "/lidar/resolve", body = place(at, radiusM, stream))) as LidarResolveDto

        override suspend fun build(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double?): LidarBuildDto =
            ask(
                request(
                    "POST", "/lidar/build",
                    body = buildJsonObject {
                        put("lat", at.lat); put("lon", at.lon)
                        if (radiusM != null) put("radius_m", radiusM)
                        put("keep", keep)
                        if (watcher != null) put("watcher", "<watcher>")
                    },
                ),
            ) as LidarBuildDto

        override suspend fun status(key: String, watcher: String?, keep: Boolean): LidarBuildDto =
            ask(
                request(
                    "GET", "/lidar/build/$key",
                    params = buildJsonObject {
                        if (watcher != null) put("watcher", "<watcher>")
                        if (keep) put("keep", "1")
                    },
                ),
            ) as LidarBuildDto

        override suspend fun release(key: String, watcher: String?) {
            released += request("DELETE", "/lidar/build/$key", params = buildJsonObject { if (watcher != null) put("watcher", "<watcher>") })
        }

        private fun place(at: LatLon, radiusM: Double?, stream: Boolean) = buildJsonObject {
            put("lat", at.lat); put("lon", at.lon)
            if (radiusM != null) put("radius_m", radiusM)
            if (stream) put("stream", true)
        }

        private fun request(method: String, path: String, body: JsonObject? = null, params: JsonObject? = null) = buildJsonObject {
            put("method", method)
            put("path", path)
            if (body != null) put("body", body)
            if (params != null && params.isNotEmpty()) put("params", params)
        }
    }

    /** The server's answer as what the call returns or throws. */
    private fun outcome(path: String, answer: JsonObject): Any {
        if (answer["network"]?.jsonPrimitive?.booleanOrNull == true) return NetworkException("unreachable", null, requestMayHaveBeenSent = false)
        val status = answer.getValue("status").jsonPrimitive.intOrNull!!
        val data = answer["data"]
        if (status >= 400) {
            val body = data as? JsonObject
            val message = (body?.get("error") as? JsonPrimitive)?.contentOrNull ?: "The server answered $status."
            if (status == 429) return RateLimitedException(message, null)
            return ApiException(status, (body?.get("code") as? JsonPrimitive)?.contentOrNull, message, body = body)
        }
        return if (path == "/lidar/resolve") json.decodeFromJsonElement<LidarResolveDto>(data!!)
        else json.decodeFromJsonElement<LidarBuildDto>(data!!)
    }

    private fun answerWith(pending: Pending, answer: JsonObject) {
        when (val result = outcome(pending.request.getValue("path").jsonPrimitive.content, answer)) {
            is Throwable -> pending.answer.completeExceptionally(result)
            else -> pending.answer.complete(result)
        }
    }

    /** What the view shows, in the fixture's terms (the web's error words aside); with [stream], what it shows of a streamed survey too. */
    private fun shown(lookup: LidarLookup, stream: Boolean): Map<String, Any?> = mapOf(
        "state" to lookup.phase.name.lowercase(),
        "key" to lookup.key,
        "url" to lookup.url,
        "contextUrl" to lookup.contextUrl,
        "target" to lookup.target,
        "stage" to lookup.stage,
        "position" to lookup.position,
        "elapsedS" to lookup.elapsedS,
        "buildFailed" to lookup.buildFailed,
    ) + if (stream) mapOf("streamed" to lookup.streamed, "frame" to lookup.frame) else emptyMap()

    private fun expected(then: JsonObject, stream: Boolean): Map<String, Any?> = mapOf(
        "state" to then.text("state"),
        "key" to then.text("key"),
        "url" to then.text("url"),
        "contextUrl" to then.text("contextUrl"),
        "target" to (then["target"] as? JsonObject)?.let { json.decodeFromJsonElement<LidarTargetDto>(it) },
        "stage" to then.text("stage"),
        "position" to (then["position"] as? JsonPrimitive)?.intOrNull,
        "elapsedS" to (then["elapsedS"] as? JsonPrimitive)?.intOrNull,
        "buildFailed" to then.getValue("buildFailed").jsonPrimitive.booleanOrNull,
    ) + if (stream) {
        mapOf(
            // The web's `streamed` is false until a streamed answer; null only before the state has the field.
            "streamed" to ((then["streamed"] as? JsonPrimitive)?.booleanOrNull ?: false),
            "frame" to (then["frame"] as? JsonObject)?.let { json.decodeFromJsonElement<LidarFrameDto>(it) },
        )
    } else {
        emptyMap()
    }

    private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** Which of the app's words a failure is said in: from the request that failed, and how. */
    private fun problemFor(request: JsonObject, answer: JsonObject): LidarProblem {
        val path = request.getValue("path").jsonPrimitive.content
        val network = answer["network"]?.jsonPrimitive?.booleanOrNull == true
        val status = (answer["status"] as? JsonPrimitive)?.intOrNull
        return when {
            path == "/lidar/resolve" -> if (network) LidarProblem.LOOKUP_UNREACHABLE else LidarProblem.LOOKUP_FAILED
            path == "/lidar/build" -> when {
                network -> LidarProblem.BUILD_UNREACHABLE
                status == 429 -> LidarProblem.QUEUE_FULL
                else -> LidarProblem.BUILD_REFUSED
            }
            (answer["data"] as? JsonObject)?.text("state") == "failed" -> LidarProblem.BUILD_FAILED
            else -> LidarProblem.LOST_CONTACT
        }
    }

    private fun TestScope.nextRequest(server: Server, limitMs: Long): Pair<Pending, Long>? {
        var waited = 0L
        runCurrent()
        while (server.pending.isEmpty() && waited < limitMs) {
            advanceTimeBy(500)
            runCurrent()
            waited += 500
        }
        return server.pending.removeFirstOrNull()?.let { it to waited }
    }

    @Test
    fun `it asks after a build as often as the web does`() {
        assertEquals(fixture.getValue("constants").jsonObject.getValue("pollMs").jsonPrimitive.content.toLong(), LidarBuilds.POLL_MS)
    }

    @Test
    fun `every scenario asks what the web asks, when it asks it, and shows what the web shows`() {
        val cases = fixture.getValue("cases").jsonArray
        assertTrue(cases.size >= 20)
        for (element in cases) scenario(element.jsonObject)
    }

    @Test
    fun `asked with stream, every scenario asks and shows what the web's does, a streamed survey's frame included`() {
        assertEquals(fixture.getValue("constants"), streamFixture.getValue("constants"))
        val cases = streamFixture.getValue("cases").jsonArray
        assertTrue(cases.size >= 5)
        assertTrue(cases.all { it.jsonObject["stream"]?.jsonPrimitive?.booleanOrNull == true })
        assertTrue("a streamed answer is held", cases.any { case ->
            case.jsonObject.getValue("steps").jsonArray.any { (it.jsonObject["then"] as? JsonObject)?.get("frame") is JsonObject }
        })
        for (element in cases) scenario(element.jsonObject)
    }

    private fun scenario(case: JsonObject) = runTest {
        val name = case.getValue("name").jsonPrimitive.content
        val server = Server()
        // Under the test's background scope: a poll loop left running ends with the test, and a failed assertion is reported rather than run into the ground.
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        val builds = LidarBuilds(server, InMemoryLidarWaiting(watcher), scope)
        val target = case.getValue("target").jsonObject
        val at = LatLon(target.getValue("lat").jsonPrimitive.doubleOrNull ?: Double.NaN, target.getValue("lon").jsonPrimitive.doubleOrNull ?: Double.NaN)
        val keep = case.getValue("keep").jsonPrimitive.booleanOrNull == true
        val stream = case["stream"]?.jsonPrimitive?.booleanOrNull == true
        val run = builds.start(scope, at, keep, stream = stream)
        var closed = false

        for ((index, step) in case.getValue("steps").jsonArray.map { it.jsonObject }.withIndex()) {
            val where = "$name, step $index"
            when {
                "mounted" in step -> {
                    runCurrent()
                    assertEquals(where, expected(step.getValue("then").jsonObject, stream), shown(run.state.value, stream))
                }
                "saved" in step -> {
                    run.setKeep(step.getValue("saved").jsonPrimitive.booleanOrNull == true)
                    runCurrent()
                    assertEquals(where, expected(step.getValue("then").jsonObject, stream), shown(run.state.value, stream))
                }
                "retried" in step -> {
                    run.retry()
                    runCurrent()
                    assertEquals(where, expected(step.getValue("then").jsonObject, stream), shown(run.state.value, stream))
                }
                "closed" in step -> {
                    val before = server.released.size
                    run.close()
                    closed = true
                    runCurrent()
                    assertEquals(where, step.getValue("released").jsonArray.map { it.jsonObject }, server.released.drop(before))
                }
                else -> {
                    val (pending, afterMs) = nextRequest(server, 30_000) ?: error("$where: no request came")
                    assertEquals("$where: the request", step.getValue("request").jsonObject, pending.request)
                    assertEquals("$where: when it was asked", step.getValue("afterMs").jsonPrimitive.content.toLong(), afterMs)
                    val answer = step.getValue("answer").jsonObject
                    answerWith(pending, answer)
                    runCurrent()
                    val then = step.getValue("then").jsonObject
                    assertEquals("$where: what it shows", expected(then, stream), shown(run.state.value, stream))
                    if (then.text("state") == "error") {
                        assertEquals("$where: why", problemFor(pending.request, answer), run.state.value.problem)
                    } else {
                        assertEquals("$where: no problem", null, run.state.value.problem)
                    }
                }
            }
        }
        val next = case["next"] as? JsonObject
        val coming = if (closed) null else nextRequest(server, 10_000)
        if (next == null) {
            assertEquals("$name: nothing more is asked", null, coming?.first?.request)
        } else {
            assertNotNull("$name: something more is asked", coming)
            assertEquals("$name: what is asked next", next.getValue("request").jsonObject, coming!!.first.request)
            assertEquals("$name: when it is asked next", next.getValue("afterMs").jsonPrimitive.content.toLong(), coming.second)
        }
        run.close()
        scope.cancelScope()
    }

    private fun CoroutineScope.cancelScope() = coroutineContext[kotlinx.coroutines.Job]?.cancel()

    @Suppress("unused")
    private fun JsonElement?.isNull() = this == null || this is JsonNull
}
