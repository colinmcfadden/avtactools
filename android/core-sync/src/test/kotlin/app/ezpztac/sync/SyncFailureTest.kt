package app.ezpztac.sync

import app.ezpztac.network.AffiliationRequiredException
import app.ezpztac.network.ApiException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What the real server will not do on demand: lose an answer after applying the write, refuse one record for good, end the
 * session in the middle of a queue, land an edit while a push is in flight.
 */
internal class SyncFailureTest {
    private val kind = RecordKind.LZ
    private fun lz(tag: String) = content(kind, tag)

    private class Rig {
        val server = FakeServer()
        val a = Device("A", server)
        val b = Device("B", server)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    // -- No signal ---------------------------------------------------------------------------

    @Test
    fun `no signal keeps the queue and says so, and the next sync sends it`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        server.offlineWrites = 1
        val report = a.sync()
        assertEquals(StopReason.Offline, report.stopped)
        assertTrue(server.log.none { it.startsWith("changes") }, "no signal: nothing to learn, so no pull is tried")
        assertEquals(1, a.outbox().size)
        assertEquals(0, a.outbox().single().attempts)                          // it never left, so it is not counted as tried
        assertEquals(SyncStatus.PENDING, a.record(kind, made.uuid)!!.status)

        assertNull(a.sync().stopped)
        assertTrue(a.outbox().isEmpty())
        assertEquals(listOf("HAWK"), server.live(kind).map { it.name })
    }

    @Test
    fun `a record made and deleted with no signal at all is thrown away without a trace`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        server.offlineWrites = 3
        a.sync(); a.sync()
        a.repository.delete(kind, made.uuid)
        assertTrue(a.outbox().isEmpty())
        a.sync()
        assertTrue(server.records.isEmpty())
    }

    // -- An answer that never arrived ----------------------------------------------------------

    @Test
    fun `a create whose answer was lost is repeated and does not make a second record`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        server.loseAnswers = 1
        assertEquals(StopReason.Offline, a.sync().stopped)
        assertEquals(1, server.records.size)                                      // it reached the server
        assertEquals(1, a.outbox().single().attempts)                             // and this device knows it may have

        assertNull(a.sync().stopped)
        assertEquals(1, server.live(kind).size)
        assertNotNull(a.record(kind, made.uuid)!!.serverId)
        assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)
        // Both sends carried the same key: this was the same write, not a second.
        val creates = server.log.filter { it.startsWith("create") }
        assertEquals(2, creates.size)
        assertEquals(creates[0], creates[1])
    }

    @Test
    fun `after a lost create answer the content goes up again, since it may have changed in between`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("first"))
        server.loseAnswers = 1
        a.sync()
        a.repository.edit(kind, made.uuid, data = lz("second"))                  // edited before the retry
        a.sync()
        assertEquals("second", (server.live(kind).single().data["note"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(a.outbox().isEmpty())
    }

    @Test
    fun `an update whose answer was lost is repeated under the same key and applied once`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "HAWK 2")
        server.loseAnswers = 1
        assertEquals(StopReason.Offline, a.sync().stopped)
        assertNull(a.sync().stopped)

        assertEquals(2, server.live(kind).single().revision)                       // one bump
        val updates = server.log.filter { it.startsWith("update") }
        assertEquals(2, updates.size)
        assertEquals(updates[0], updates[1])                                       // the same key
        assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)
    }

    @Test
    fun `a delete whose answer was lost is repeated, and the record does not come back`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.delete(kind, made.uuid)
        server.loseAnswers = 1
        a.sync()
        a.sync()
        assertTrue(server.live(kind).isEmpty())
        assertNull(a.store.transaction { record(kind, made.uuid) })
        b.sync()
        assertTrue(b.names(kind).isEmpty())
    }

    @Test
    fun `a record deleted while its create may already be on the server does not come back`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        server.loseAnswers = 1
        a.sync()                                                                   // reached the server; this device does not know
        a.repository.delete(kind, made.uuid)                                       // so it cannot simply forget the record
        assertEquals(listOf(Operation.CREATE, Operation.DELETE), a.outbox().map { it.operation })
        assertNull(a.record(kind, made.uuid))                                      // hidden at once

        a.sync()
        assertTrue(server.live(kind).isEmpty())
        assertNull(a.store.transaction { record(kind, made.uuid) })
        b.sync()
        assertTrue(b.names(kind).isEmpty())
    }

    @Test
    fun `after a lost update answer the record changes again, the first write is settled before the second goes`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "v2")
        server.loseAnswers = 1
        a.sync()                                                                   // sent and applied; the answer was lost
        a.repository.edit(kind, made.uuid, name = "v3")                            // and then changed again

        val report = a.sync()
        assertTrue(report.conflicts.isEmpty(), "this device must not conflict with its own earlier write")
        assertEquals(listOf("v3"), server.live(kind).map { it.name })
        assertEquals(3, server.live(kind).single().revision)

        // The first write was repeated exactly (same key, same base, same content), then the new one went on top of it.
        val updates = server.log.filter { it.startsWith("update") }
        assertEquals(3, updates.size)
        assertEquals(updates[0], updates[1])
        assertEquals("base=1", updates[1].split(' ')[3])
        assertNotEquals(updates[1].substringAfterLast(' '), updates[2].substringAfterLast(' '), "different content must not reuse a key the server would read as a repeat")
        assertEquals("base=2", updates[2].split(' ')[3])
        assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)
    }

    @Test
    fun `a delete after a lost update answer settles the update first, and is not mistaken for a conflict`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "v2")
        server.loseAnswers = 1
        a.sync()
        a.repository.delete(kind, made.uuid)
        assertEquals(listOf(Operation.UPDATE, Operation.DELETE), a.outbox().map { it.operation })

        val report = a.sync()
        assertTrue(report.restored.isEmpty())
        assertTrue(server.live(kind).isEmpty())
        assertNull(a.store.transaction { record(kind, made.uuid) })
    }

    @Test
    fun `a create repeated after a lost answer sends what it first sent, then the newer content`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("first"))
        server.loseAnswers = 1
        a.sync()
        a.repository.edit(kind, made.uuid, data = lz("second"))
        a.sync()
        val writes = server.log.filter { it.startsWith("create") || it.startsWith("update") }
        assertEquals(3, writes.size)
        assertEquals(writes[0], writes[1])                                         // the repeat is the same create
        assertTrue(writes[2].startsWith("update"))
        assertEquals("second", (server.live(kind).single().data["note"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a create that finds the record already edited on the server leaves the conflict to be kept, not overwritten`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("mine"))
        server.loseAnswers = 1
        a.sync()                                                                   // applied, answer lost
        server.editElsewhere(kind, made.uuid, name = "WEB EDIT", data = lz("web"))   // then somebody else changed it
        val report = a.sync()
        assertEquals(1, report.conflicts.size)
        assertEquals("WEB EDIT", a.record(kind, made.uuid)!!.name)
        assertEquals(2, server.live(kind).size)                                    // the web's version, and mine kept beside it
    }

    // -- Edits that land while a push is in flight ------------------------------------------------

    @Test
    fun `an edit made while a push is in flight is not lost`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        server.onWrite = { op -> if (op == "create") { a.repository.edit(kind, made.uuid, data = lz("while sending")); server.onWrite = null } }
        a.sync()

        // The create went with what the record was when it was sent; what it became while sending went straight after it.
        assertEquals("while sending", tagOf(kind, a.record(kind, made.uuid)!!))
        assertEquals("while sending", (server.live(kind).single().data["note"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(2, server.live(kind).single().revision)
        assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)
        assertTrue(a.outbox().isEmpty())
    }

    @Test
    fun `an update that finishes after another edit leaves the newer edit queued`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "v2")
        server.onWrite = { op -> if (op == "update") { a.repository.edit(kind, made.uuid, name = "v3"); server.onWrite = null } }
        a.sync()
        assertEquals("v3", a.record(kind, made.uuid)!!.name)
        assertEquals(SyncStatus.SYNCED, a.record(kind, made.uuid)!!.status)       // the loop sent v3 as well
        assertEquals("v3", server.live(kind).single().name)
        assertEquals(3, server.live(kind).single().revision)
    }

    @Test
    fun `a record deleted while its update is in flight stays deleted`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "v2")
        server.onWrite = { op -> if (op == "update") { a.repository.delete(kind, made.uuid); server.onWrite = null } }
        a.sync()
        assertNull(a.record(kind, made.uuid))
        assertTrue(server.live(kind).isEmpty())
    }

    // -- Refusals and failures ------------------------------------------------------------------

    @Test
    fun `a record the server refuses for good waits for the user, and the rest of the queue goes on`() = rig {
        a.repository.create(kind, "BAD", lz("x"))
        a.repository.create(kind, "GOOD", lz("y"))
        a.repository.create(kind, "ALSO GOOD", lz("z"))
        server.failName = "BAD" to ApiException(400, "invalid", "That is not allowed.")
        val report = a.sync()

        assertNull(report.stopped)
        assertEquals(2, report.pushed)
        assertEquals(setOf("GOOD", "ALSO GOOD"), server.live(kind).map { it.name }.toSet())
        val blocked = report.blocked.single()
        assertEquals("That is not allowed.", blocked.lastError)
        assertTrue(blocked.blocked)

        // It is not sent again until the user (or a fix) releases it.
        val creates = server.log.count { it.startsWith("create") }
        a.sync()
        assertEquals(creates, server.log.count { it.startsWith("create") })
        assertEquals(1, a.outbox().size)
        server.failName = null
        assertEquals(1, a.engine.retryBlocked())
        a.sync()
        assertEquals(3, server.live(kind).size)
        assertTrue(a.outbox().isEmpty())
    }

    @Test
    fun `a blocked edit holds back later edits to the same record, not to others`() = rig {
        val bad = a.repository.create(kind, "BAD", lz("x"))
        val good = a.repository.create(kind, "GOOD", lz("y"))
        a.sync()
        a.repository.edit(kind, bad.uuid, name = "BAD 2")
        a.repository.edit(kind, good.uuid, name = "GOOD 2")
        server.failName = "BAD 2" to ApiException(400, "invalid", "no")
        a.sync()
        assertEquals("GOOD 2", server.byUuid(kind, good.uuid).name)
        assertEquals("BAD", server.byUuid(kind, bad.uuid).name)
    }

    @Test
    fun `an ended session stops the sync with nothing lost`() = rig {
        a.repository.create(kind, "HAWK", lz("one"))
        server.failAll = SessionEndedException(SignedOutReason.SESSION_ENDED, "refresh_reuse_detected", "This session has expired.")
        val report = a.sync()
        assertEquals(StopReason.SessionEnded("refresh_reuse_detected"), report.stopped)
        assertTrue(server.log.none { it.startsWith("changes") })
        assertEquals(1, a.outbox().size)
        assertEquals(0, a.outbox().single().attempts)
        server.failAll = null
        assertNull(a.sync().stopped)                                                // after signing in again
        assertEquals(1, server.live(kind).size)
    }

    @Test
    fun `too many requests and an uncleared gate stop the sync and say why`() = rig {
        a.repository.create(kind, "HAWK", lz("one"))
        server.failAll = RateLimitedException("Slow down.", 30)
        assertEquals(StopReason.RateLimited(30), a.sync().stopped)
        server.failAll = AffiliationRequiredException("Military affiliation verification is required to use this feature.")
        assertEquals(StopReason.AffiliationRequired, a.sync().stopped)
        assertEquals(1, a.outbox().size)
        assertEquals(0, a.outbox().single().attempts)                          // refused before being looked at: not an attempt
    }

    @Test
    fun `a server error stops the push but the pull still runs`() = rig {
        server.createElsewhere(kind, "web-1", "FROM THE WEB", lz("w"))
        a.repository.create(kind, "HAWK", lz("one"))
        server.failName = "HAWK" to ApiException(503, null, "Service Unavailable")
        val report = a.sync()
        assertEquals(StopReason.ServerError(503), report.stopped)
        assertEquals(1, a.outbox().size)
        assertEquals(1, report.pulled)
        assertTrue("FROM THE WEB" in a.names(kind))
    }

    @Test
    fun `a create that never left is sent as the record is now, not as it was`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("first"))
        server.offlineWrites = 1
        a.sync()                                                                   // never reached the server
        a.repository.edit(kind, made.uuid, name = "HAWK 2", data = lz("second"))
        a.sync()

        val only = server.live(kind).single()
        assertEquals("HAWK 2", only.name)
        assertEquals(1, only.revision)                                              // one write, with the newer content
    }

    @Test
    fun `editing a record the server has not seen leaves one create, not an update`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("first"))
        a.repository.edit(kind, made.uuid, name = "HAWK 2")
        a.repository.edit(kind, made.uuid, name = "HAWK 3")
        assertEquals(listOf(Operation.CREATE), a.outbox().map { it.operation })
        a.sync()
        assertEquals("HAWK 3", server.live(kind).single().name)
        assertEquals(1, server.live(kind).single().revision)
    }

    @Test
    fun `a record the server refused to create can simply be deleted`() = rig {
        val made = a.repository.create(kind, "BAD", lz("x"))
        server.failName = "BAD" to ApiException(400, "invalid", "That is not allowed.")
        assertEquals(1, a.sync().blocked.size)
        a.repository.delete(kind, made.uuid)
        assertTrue(a.outbox().isEmpty())
        assertNull(a.store.transaction { record(kind, made.uuid) })
        server.failName = null
        a.sync()
        assertTrue(server.records.isEmpty())
    }

    @Test
    fun `a feed entry older than what this device has does not roll the record back`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "HAWK 2")
        a.sync()
        assertEquals(2, a.record(kind, made.uuid)!!.baseRevision)
        server.editElsewhere(kind, made.uuid)                                     // anything, so there is a change to pull
        server.feedTransform = { page -> page.map { it.copy(revision = 1, name = "OLD NAME") } }
        a.sync()
        assertEquals("HAWK 2", a.record(kind, made.uuid)!!.name)
        assertEquals(2, a.record(kind, made.uuid)!!.baseRevision)
    }

    @Test
    fun `edited here and deleted there is found by a pull too, and the work is kept`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("start"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "mine", data = lz("mine"))
        server.deleteElsewhere(kind, made.uuid)
        server.failName = "mine" to ApiException(503, null, "down")                // the push cannot go, so the pull finds out first
        val report = a.sync()

        val fresh = report.recreated.getValue(made.uuid)
        assertNull(a.record(kind, made.uuid))
        assertEquals("mine", a.record(kind, fresh)!!.name)
        assertEquals(1, a.outbox().size)
        server.failName = null
        a.sync()
        assertEquals(listOf("mine"), server.live(kind).map { it.name })
    }

    // -- Pulling -------------------------------------------------------------------------------

    @Test
    fun `a pull never overwrites a record that has changes of its own`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("start"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "mine", data = lz("mine"))
        server.editElsewhere(kind, made.uuid, name = "theirs", data = lz("theirs"))
        server.failName = "mine" to ApiException(503, null, "down")             // so the push cannot go first
        a.sync()

        val kept = a.record(kind, made.uuid)!!
        assertEquals("mine", kept.name)
        assertEquals("mine", tagOf(kind, kept))
        assertEquals(1, kept.baseRevision)                                          // still based on what it was edited from

        server.failName = null                                                      // the server comes back: the conflict is found and kept
        val report = a.sync()
        assertEquals(1, report.conflicts.size)
        assertEquals("theirs", a.record(kind, made.uuid)!!.name)
    }

    @Test
    fun `a long feed is read page by page, and the cursor moves with each page`() = rig {
        server.pageSize = 2
        repeat(5) { server.createElsewhere(kind, "web-$it", "LZ $it", lz("$it")) }
        val report = b.sync()
        assertEquals(5, report.pulled)
        assertEquals(5, b.names(kind).size)
        assertEquals(listOf("changes 0", "changes 2", "changes 4"), server.log.filter { it.startsWith("changes") })
        assertEquals(0, b.sync().pulled)
    }

    @Test
    fun `a change of a kind this engine does not handle is passed over, and the cursor still moves`() = rig {
        server.addForeignChange("route", kind = "mission")                           // a mission the web imported: it carries a file this engine does not fetch
        server.addForeignChange("a-collection-from-a-newer-server")                  // not ours to read
        server.createElsewhere(kind, "web-1", "LZ", lz("x"))
        val report = b.sync()
        assertEquals(listOf("LZ"), b.names(kind))
        assertEquals(emptyList<String>(), b.names(RecordKind.ROUTE))
        assertEquals(3, report.pulled)
        assertEquals(0, b.sync().pulled)
    }

    @Test
    fun `a set of sketched routes made on the web is applied like any record`() = rig {
        server.createElsewhere(RecordKind.ROUTE, "web-r1", "ROUTES", JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to JsonArray(emptyList()))))
        b.sync()
        assertEquals(listOf("ROUTES"), b.names(RecordKind.ROUTE))
        assertEquals(JsonPrimitive(1), b.record(RecordKind.ROUTE, "web-r1")!!.data["version"])
    }

    @Test
    fun `a point set made on the web arrives as a bare list of points and is held as a document`() = rig {
        val points = JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("lps-0"), "name" to JsonPrimitive("BLUE 1"), "lat" to JsonPrimitive(34.5), "lon" to JsonPrimitive(-84.2),
            "aFieldFromANewerRelease" to JsonPrimitive(true)))))
        server.createElsewhere(RecordKind.POINT_SET, "web-p1", "NORTH GA", JsonObject(mapOf("points" to points)))
        b.sync()
        assertEquals(listOf("NORTH GA"), b.names(RecordKind.POINT_SET))
        assertEquals(points, b.record(RecordKind.POINT_SET, "web-p1")!!.data["points"])               // what the web wrote is kept as it was
    }

    @Test
    fun `an edited point set goes up as its list of points, and what a newer release added to a point goes with it`() = rig {
        val points = JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("p1"), "name" to JsonPrimitive("A"), "extra" to JsonPrimitive(1)))))
        val made = a.repository.create(RecordKind.POINT_SET, "SET", JsonObject(mapOf("points" to points)))
        a.sync()
        assertEquals(points, server.live(RecordKind.POINT_SET).single().data["points"])
        a.repository.edit(RecordKind.POINT_SET, made.uuid, name = "SET 2")
        a.sync()
        assertEquals("SET 2", server.live(RecordKind.POINT_SET).single().name)
        assertEquals(points, server.live(RecordKind.POINT_SET).single().data["points"])
    }

    @Test
    fun `an own change coming back in the feed is not applied twice`() = rig {
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.edit(kind, made.uuid, name = "HAWK 2")
        a.sync()
        val record = a.record(kind, made.uuid)!!
        assertEquals(2, record.baseRevision)
        assertEquals(SyncStatus.SYNCED, record.status)
        assertEquals(0, a.sync().pushed)
    }

    @Test
    fun `a deletion of a record never seen here is nothing`() = rig {
        server.createElsewhere(kind, "web-1", "LZ", lz("x"))
        server.deleteElsewhere(kind, "web-1")
        val report = a.sync()
        assertEquals(1, report.pulled)
        assertTrue(a.names(kind).isEmpty())
    }

    // -- Running -------------------------------------------------------------------------------

    @Test
    fun `only one sync runs at a time`() = rig {
        a.repository.create(kind, "HAWK", lz("one"))
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.onWrite = { inFlight.complete(Unit); release.await() }
        coroutineScope {
            val first = async { a.sync() }
            inFlight.await()
            val second = a.sync()
            assertTrue(second.skipped)
            assertEquals(0, second.pushed)
            release.complete(Unit)
            assertEquals(1, first.await().pushed)
        }
        assertFalse(a.sync().skipped)
    }

    @Test
    fun `changes are pushed in the order they were made`() = rig {
        a.repository.create(kind, "ONE", lz("1"))
        a.repository.create(kind, "TWO", lz("2"))
        a.repository.create(kind, "THREE", lz("3"))
        a.sync()
        assertEquals(listOf("ONE", "TWO", "THREE"), server.records.sortedBy { it.serverId }.map { it.name })
    }

    // -- The repository -------------------------------------------------------------------------

    @Test
    fun `a record that is not there cannot be edited, and deleting nothing is nothing`() = rig {
        assertThrows<IllegalArgumentException> { a.repository.edit(kind, "nope", name = "x") }
        a.repository.delete(kind, "nope")
        val made = a.repository.create(kind, "HAWK", lz("one"))
        a.sync()
        a.repository.delete(kind, made.uuid)
        assertThrows<IllegalArgumentException> { a.repository.edit(kind, made.uuid, name = "x") }
    }

    @Test
    fun `a record keeps the fields of its document that this app does not know`() = rig {
        val future = JsonObject(lz("x") + mapOf("fromTheFuture" to kotlinx.serialization.json.JsonPrimitive(7)))
        val made = a.repository.create(kind, "HAWK", future)
        a.repository.edit(kind, made.uuid, name = "renamed")
        assertEquals(future, a.record(kind, made.uuid)!!.data)
    }

    @Test
    fun `the store undoes a transaction that fails`() = runBlocking<Unit> {
        val store = InMemorySyncStore()
        store.transaction { put(LocalRecord(RecordKind.LZ, "one", null, null, "one", JsonObject(emptyMap()))); setCursor(5) }
        assertThrows<IllegalStateException> {
            store.transaction {
                remove(RecordKind.LZ, "one")
                put(LocalRecord(RecordKind.LZ, "two", null, null, "two", JsonObject(emptyMap())))
                enqueue(OutboxEntry(0, RecordKind.LZ, "two", Operation.CREATE, null, -1))
                setCursor(9)
                error("half way")
            }
        }
        store.transaction {
            assertEquals(listOf("one"), records(RecordKind.LZ).map { it.uuid })
            assertTrue(outbox().isEmpty())
            assertEquals(5, cursor())
        }
    }

    // -- Backoff --------------------------------------------------------------------------------

    @Test
    fun `retries back off from five seconds to fifteen minutes, with a little jitter`() {
        assertEquals(5_000L, RetryPolicy.delayMillis(0))
        assertEquals(10_000L, RetryPolicy.delayMillis(1))
        assertEquals(20_000L, RetryPolicy.delayMillis(2))
        assertEquals(15 * 60 * 1000L, RetryPolicy.delayMillis(20))
        assertEquals(15 * 60 * 1000L, RetryPolicy.delayMillis(1_000))
        assertEquals(6_000L, RetryPolicy.delayMillis(0, jitter = 1.0))
        assertEquals(5_000L, RetryPolicy.delayMillis(0, jitter = -3.0))                  // out of range is clamped
        assertThrows<IllegalArgumentException> { RetryPolicy.delayMillis(-1) }
    }
}
