package app.ezpztac.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/** The typed routes and how errors and conflicts come back, with the server's recorded answers. */
class ApiClientCallsTest {
    private val uuid = "7f1f0a52-6c4e-4b2a-9d3e-0a1b2c3d4e5f"

    // -- Errors -----------------------------------------------------------------------

    @Test
    fun `the gate that is not cleared is its own error`() = runBlocking<Unit> {
        Rig().use { rig ->
            // Exactly what the server's affiliation gate writes (backend/app.py, enforce_affiliation_gate).
            rig.serve { Rig.json(403, """{"error": "Military affiliation verification is required to use this feature.", "code": "affiliation_required"}""") }
            val e = assertThrows<AffiliationRequiredException> { rig.client.listLzs() }
            assertEquals(403, e.status)
            assertEquals("Military affiliation verification is required to use this feature.", e.message)
        }
    }

    @Test
    fun `a conflict carries the server's copy`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lz: update on a stale revision") }
            val e = assertThrows<RevisionConflictException> { rig.client.updateLz(1, baseRevision = 1, name = "mine") }
            assertEquals("The record changed on the server. Nothing was overwritten.", e.message)
            val server = ApiClient.JSON.decodeFromJsonElement(LzFull.serializer(), e.server)
            assertEquals("LZ HAWK 2", server.name)
            assertEquals(2, server.revision)
        }
    }

    @Test
    fun `too many requests carries how long to wait`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Rig.json(429, """{"status":"error","code":"rate_limited","error":"Too many attempts.","message":"Too many attempts."}""").setHeader("Retry-After", "42") }
            assertEquals(42L, assertThrows<RateLimitedException> { rig.client.listLzs() }.retryAfterSeconds)
            rig.serve { Rig.json(429, """{"error":"Slow down"}""") }
            assertNull(assertThrows<RateLimitedException> { rig.client.listLzs() }.retryAfterSeconds)
        }
    }

    @Test
    fun `the server's own words come through, whichever way it words an error`() = runBlocking<Unit> {
        Rig().use { rig ->
            val cases = listOf(
                "lz: not found" to Triple(404, null, "Not found"),
                "lz: malformed If-Match" to Triple(400, "invalid_if_match", "If-Match must be the record's revision, e.g. \"3\"."),
                "aircraft: refused" to Triple(400, null, "rotor_diameter_m must be between 1.0 and 60.0."),
                "sync: bad cursor" to Triple(400, "invalid_cursor", "since and limit must be integers"),
                "login: wrong password" to Triple(401, "invalid_credentials", "Invalid email or password."),
            )
            for ((name, expected) in cases) {
                rig.serve { Recorded.mock(name) }
                val e = assertThrows<ApiException>(name) { rig.client.config() }
                assertEquals(expected, Triple(e.status, e.code, e.message), name)
            }
        }
    }

    @Test
    fun `an error page from a proxy is still an error with its status`() = runBlocking<Unit> {
        Rig().use { rig ->
            for (response in listOf(
                MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html><h1>Bad gateway</h1></html>"),
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(503).setBody("[1, 2]"),
            )) {
                rig.serve { response }
                val e = assertThrows<ApiException> { rig.client.config() }
                assertEquals(response.status.split(" ")[1].toInt(), e.status)
                assertEquals("The server answered ${e.status}.", e.message)
                assertNull(e.code)
            }
        }
    }

    @Test
    fun `an answer that is not what was asked for is an error, not a crash`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Rig.json(200, "<html>captive portal</html>") }
            val e = assertThrows<ApiException> { rig.client.config() }
            assertEquals("unreadable_response", e.code)
            rig.serve { Rig.json(200, """{"configVersion": "one"}""") }
            assertEquals("unreadable_response", assertThrows<ApiException> { rig.client.config() }.code)
        }
    }

    @Test
    fun `a field a newer server adds does not break the call`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Rig.json(200, Recorded.text("config").replaceFirst("{", """{"someNewThing": {"a": [1]}, """)) }
            assertEquals(1, rig.client.config().configVersion)
        }
    }

    // -- Saved LZs --------------------------------------------------------------------

    private val diagram = buildJsonObject { put("schema", 2); put("note", "x") }

    @Test
    fun `saving an LZ sends its identity and an idempotency key, and says whether it was new`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lz: create") }
            val first = rig.client.createLz("LZ HAWK", diagram, uuid, idempotencyKey = "key-1")
            assertTrue(first.created)
            assertEquals(uuid, first.value.clientUuid)
            val sent = rig.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("key-1", sent.getHeader("Idempotency-Key"))
            assertEquals("""{"name":"LZ HAWK","lz_data":{"schema":2,"note":"x"},"client_uuid":"$uuid"}""", sent.body.readUtf8())

            rig.serve { Recorded.mock("lz: create again with the same identity") }
            assertFalse(rig.client.createLz("LZ HAWK", diagram, uuid).created)             // a repeat of a lost response: 200
        }
    }

    @Test
    fun `an edit says which revision it was made on, and sends only what changed`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lz: update") }
            val updated = rig.client.updateLz(1, baseRevision = 1, name = "LZ HAWK 2", idempotencyKey = "key-2")
            assertEquals(2, updated.revision)
            val sent = rig.requests.single()
            assertEquals("PUT", sent.method)
            assertEquals("/api/lz/1", sent.requestUrl!!.encodedPath)
            assertEquals("\"1\"", sent.getHeader("If-Match"))
            assertEquals("key-2", sent.getHeader("Idempotency-Key"))
            assertEquals("""{"name":"LZ HAWK 2"}""", sent.body.readUtf8())
        }
    }

    @Test
    fun `an edit with no revision is last writer wins, as on the web`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lz: update") }
            rig.client.updateLz(1, baseRevision = null, lzData = diagram)
            val sent = rig.requests.single()
            assertNull(sent.getHeader("If-Match"))
            assertEquals("""{"lz_data":{"schema":2,"note":"x"}}""", sent.body.readUtf8())
        }
    }

    @Test
    fun `listing, reading and deleting`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lz: list") }
            assertEquals("LZ HAWK", rig.client.listLzs().single().name)
            rig.serve { Recorded.mock("lz: get") }
            assertEquals(34.5, ((rig.client.getLz(1).lzData["target"] as kotlinx.serialization.json.JsonArray)[0] as JsonPrimitive).content.toDouble())
            rig.serve { Recorded.mock("lz: delete") }
            rig.client.deleteLz(1, baseRevision = 2, idempotencyKey = "key-3")
            val sent = rig.requests.last()
            assertEquals("DELETE", sent.method)
            assertEquals("\"2\"", sent.getHeader("If-Match"))
            assertEquals("key-3", sent.getHeader("Idempotency-Key"))
        }
    }

    // -- Saved routes -----------------------------------------------------------------

    private val routeData = buildJsonObject { put("version", 1); put("routes", kotlinx.serialization.json.JsonArray(emptyList())) }

    /** A multipart body's text fields by name: the part's name is in its Content-Disposition, and its text follows the blank line. */
    private fun fields(body: String): Map<String, String> =
        Regex("""name="([^"]+)"\r\n(?:Content-[^\r]*\r\n)*\r\n(.*?)\r\n--""", RegexOption.DOT_MATCHES_ALL).findAll(body).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `saving a set of routes is a multipart form with its identity, and says whether it was new`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route: create") }
            val first = rig.client.createRoute("ROUTES", routeData, uuid, idempotencyKey = "key-r1")
            assertTrue(first.created)
            assertEquals("sketch", first.value.kind)
            val sent = rig.requests.single()
            assertEquals("POST", sent.method)
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("multipart/form-data"), sent.getHeader("Content-Type"))
            assertEquals("key-r1", sent.getHeader("Idempotency-Key"))
            assertEquals(
                mapOf("name" to "ROUTES", "kind" to "sketch", "route_data" to """{"version":1,"routes":[]}""", "client_uuid" to uuid),
                fields(sent.body.readUtf8()),
            )

            rig.serve { Recorded.mock("route: create again with the same identity") }
            assertFalse(rig.client.createRoute("ROUTES", routeData, uuid).created)           // a repeat of a lost response: 200
        }
    }

    @Test
    fun `an edit of a set says which revision it was made on, and sends only what changed`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route: update") }
            val updated = rig.client.updateRoute(1, baseRevision = 1, name = "ROUTES 2", idempotencyKey = "key-r2")
            assertEquals(2, updated.revision)
            val sent = rig.requests.single()
            assertEquals("PUT", sent.method)
            assertEquals("/api/routes/1", sent.requestUrl!!.encodedPath)
            assertEquals("\"1\"", sent.getHeader("If-Match"))
            assertEquals(mapOf("name" to "ROUTES 2"), fields(sent.body.readUtf8()))

            rig.serve { Recorded.mock("route: update") }
            rig.client.updateRoute(1, baseRevision = null, routeData = routeData)
            val second = rig.requests.last()
            assertNull(second.getHeader("If-Match"))
            assertEquals(mapOf("route_data" to """{"version":1,"routes":[]}"""), fields(second.body.readUtf8()))
        }
    }

    @Test
    fun `a conflict on a set carries the server's copy`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route: update on a stale revision") }
            val e = assertThrows<RevisionConflictException> { rig.client.updateRoute(1, baseRevision = 1, name = "mine") }
            val server = ApiClient.JSON.decodeFromJsonElement(RouteFull.serializer(), e.server)
            assertEquals("ROUTES 2", server.name)
            assertEquals(2, server.revision)
            assertEquals("sketch", server.kind)
        }
    }

    @Test
    fun `listing, reading and deleting a set`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route: list") }
            assertEquals("ROUTES", rig.client.listRoutes().single().name)
            rig.serve { Recorded.mock("route: get") }
            val full = rig.client.getRoute(1)
            assertEquals(1, (full.routeData["routes"] as kotlinx.serialization.json.JsonArray).size)
            rig.serve { Recorded.mock("route: delete") }
            rig.client.deleteRoute(1, baseRevision = 2, idempotencyKey = "key-r3")
            val sent = rig.requests.last()
            assertEquals("DELETE", sent.method)
            assertEquals("\"2\"", sent.getHeader("If-Match"))
            assertEquals("key-r3", sent.getHeader("Idempotency-Key"))
        }
    }

    // -- Aircraft profiles ------------------------------------------------------------

    @Test
    fun `a user's own aircraft profile is created, edited and deleted with the same rules`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("aircraft: create") }
            val made = rig.client.createAircraftProfile(AircraftProfileInput(clientUuid = uuid, name = "My Hawk", designation = "MH-60", rotorDiameterM = 16.357), "k1")
            assertTrue(made.created)
            assertEquals("""{"client_uuid":"$uuid","name":"My Hawk","designation":"MH-60","rotor_diameter_m":16.357}""", rig.requests.single().body.readUtf8())

            rig.serve { Recorded.mock("aircraft: update") }
            val renamed = rig.client.updateAircraftProfile(1, 1, AircraftProfileInput(name = "Renamed"), "k2")
            assertEquals(2, renamed.revision)
            val put = rig.requests.last()
            assertEquals("\"1\"", put.getHeader("If-Match"))
            assertEquals("""{"name":"Renamed"}""", put.body.readUtf8())

            rig.serve { Recorded.mock("aircraft: update on a stale revision") }
            val e = assertThrows<RevisionConflictException> { rig.client.updateAircraftProfile(1, 1, AircraftProfileInput(name = "x")) }
            assertEquals("Renamed", (e.server["name"] as JsonPrimitive).content)

            rig.serve { Recorded.mock("aircraft: delete") }
            rig.client.deleteAircraftProfile(1, baseRevision = 2)
            assertEquals("DELETE", rig.requests.last().method)
        }
    }

    @Test
    fun `the aircraft list is read in the order the server gives it`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("aircraft: list") }
            val profiles = rig.client.aircraftProfiles()
            assertEquals(listOf("mh-60"), profiles.map { it.slug })
            assertEquals(uuidPlaceholder, profiles.single().clientUuid)
        }
    }

    private val uuidPlaceholder = "<uuid>"

    // -- Sync -------------------------------------------------------------------------

    @Test
    fun `the change feed is asked for after a cursor, and returns tombstones and profiles as well as LZs`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("sync: changes") }
            val feed = rig.client.changes(since = 0, limit = 50)
            val sent = rig.requests.single()
            assertEquals("0", sent.requestUrl!!.queryParameter("since"))
            assertEquals("50", sent.requestUrl!!.queryParameter("limit"))
            assertEquals(listOf("lz", "route", "aircraft", "lz"), feed.changes.map { it.type })
            assertEquals(listOf(false, false, false, true), feed.changes.map { it.deleted })
            assertEquals(8, feed.cursor)
            assertFalse(feed.hasMore)
            assertEquals(JsonObject(emptyMap()), feed.changes.last().data)
            // A route set arrives with its kind and its routes, so a device can read it without asking again.
            val route = feed.changes.single { it.type == "route" }
            assertEquals("sketch", route.kind)
            assertEquals(false, route.hasFile)
            assertEquals(1, (route.data as kotlinx.serialization.json.JsonObject).getValue("routes").let { (it as kotlinx.serialization.json.JsonArray).size })

            rig.serve { Recorded.mock("sync: nothing new") }
            assertEquals(999, rig.client.changes(since = 999).cursor)
            assertNull(rig.requests.last().requestUrl!!.queryParameter("limit"))
        }
    }

    // -- Account, sessions, config ----------------------------------------------------

    @Test
    fun `the account, its devices and the config`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("me") }
            assertEquals("pilot@example.com", rig.client.me().email)

            rig.serve { Recorded.mock("sessions") }
            val devices = rig.client.deviceSessions()
            assertEquals("android/1.4.0 (212)", devices.single().client)
            assertTrue(devices.single().current)

            rig.serve { Recorded.mock("logout") }
            rig.client.revokeSession("abc123")
            assertEquals("/api/auth/sessions/abc123", rig.requests.last().requestUrl!!.encodedPath)
            assertEquals("DELETE", rig.requests.last().method)

            rig.serve { Recorded.mock("sessions: revoke one that is not mine") }
            assertEquals("session_not_found", assertThrows<ApiException> { rig.client.revokeSession("0".repeat(32)) }.code)

            rig.serve { Recorded.mock("config") }
            val config = rig.client.config()
            assertEquals(1, config.configVersion)
            assertFalse(config.maintenance.active)
        }
    }

    // -- Request priority --------------------------------------------------------------

    private fun heavy() = ApiClient.Call("POST", "/api/analyze-field", body = buildJsonObject { put("x", 1) }, readTimeoutSeconds = 10)

    @Test
    fun `background work waits while heavy work is in flight, and normal work does not`() = runBlocking<Unit> {
        Rig().use { rig ->
            val arrivals = java.util.concurrent.ConcurrentHashMap<String, Long>()
            rig.serve { request ->
                arrivals[request.requestUrl!!.encodedPath] = System.nanoTime()
                if (request.path == "/api/analyze-field") Rig.json(200, "{}").setBodyDelay(600, TimeUnit.MILLISECONDS)
                else if (request.path!!.startsWith("/api/sync/changes")) Recorded.mock("sync: nothing new")
                else Recorded.mock("lz: list")
            }
            val analysis = async(Dispatchers.Default) { rig.client.execute(heavy()); System.nanoTime() }
            kotlinx.coroutines.withTimeout(5_000) { while (!rig.priority.isBusy) kotlinx.coroutines.delay(5) }

            val normal = async(Dispatchers.Default) { rig.client.listLzs() }
            val background = async(Dispatchers.Default) { rig.client.changes(0) }
            kotlinx.coroutines.withTimeout(10_000) {
                normal.await()                                                               // not held up
            }
            val analysisDone = kotlinx.coroutines.withTimeout(10_000) { analysis.await() }
            kotlinx.coroutines.withTimeout(10_000) { background.await() }

            assertTrue(arrivals.getValue("/api/lz") < analysisDone, "a normal call went ahead of the analysis finishing")
            assertTrue(arrivals.getValue("/api/sync/changes") >= analysisDone, "a background call waited for the analysis")
            assertFalse(rig.priority.isBusy)
        }
    }

    @Test
    fun `heavy work that fails or is cancelled lets background work go`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.path == "/api/analyze-field") Rig.json(500, """{"error":"SAM failed"}""") else Recorded.mock("sync: nothing new")
            }
            assertThrows<ApiException> { rig.client.execute(heavy()) }
            assertFalse(rig.priority.isBusy)
            assertEquals(999, kotlinx.coroutines.withTimeout(10_000) { withContext(Dispatchers.Default) { rig.client.changes(999).cursor } })

            rig.serve { Rig.json(200, "{}").setBodyDelay(5, TimeUnit.SECONDS) }
            val slow = async(Dispatchers.Default) { runCatching { rig.client.execute(heavy()) } }
            kotlinx.coroutines.withTimeout(5_000) { while (!rig.priority.isBusy) kotlinx.coroutines.delay(5) }
            slow.cancel()
            slow.join()
            assertFalse(rig.priority.isBusy, "a cancelled analysis must not hold background work back for ever")
        }
    }

    @Test
    fun `an analysis is allowed the long time the server takes over it, other calls are not`() = runBlocking<Unit> {
        Rig(readTimeoutSeconds = 1).use { rig ->
            rig.serve { Rig.json(200, "{}").setBodyDelay(2, TimeUnit.SECONDS) }
            rig.client.execute(heavy().let { ApiClient.Call(it.method, it.path, body = it.body) })     // two seconds is fine for an analysis
            val e = assertThrows<NetworkException> { rig.client.execute(ApiClient.Call("GET", "/api/anything")) }
            assertTrue(e.requestMayHaveBeenSent)                                                      // ... and a timeout after sending is "may have"
        }
    }

    @Test
    fun `a refresh gives up on a silent server quickly, so its repeat is inside the grace period`() = runBlocking<Unit> {
        Rig(readTimeoutSeconds = 60, refreshReadTimeoutSeconds = 1).use { rig ->
            val refreshes = java.util.concurrent.atomic.AtomicInteger()
            rig.serve { request ->
                when {
                    request.path == "/api/auth/refresh" ->
                        if (refreshes.incrementAndGet() == 1) Rig.refreshed("access-2", "refresh-2").setBodyDelay(3, TimeUnit.SECONDS) else Rig.refreshed("access-2", "refresh-2")
                    Rig.bearer(request) == "access-1" -> Rig.json(401, """{"msg": "Token has expired"}""")
                    else -> Recorded.mock("lz: list")
                }
            }
            val started = System.nanoTime()
            assertEquals(1, rig.client.listLzs().size)
            val seconds = (System.nanoTime() - started) / 1e9
            assertEquals(2, refreshes.get())
            assertTrue(seconds < 2.9, "took $seconds s: waited for the slow answer instead of giving up on it")
        }
    }
}
