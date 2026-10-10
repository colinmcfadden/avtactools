package app.ezpztac.missionpacks

import app.ezpztac.network.AffiliationRequiredException
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.OtherAccountException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a pack call's failure says to the session: the web's failureOf, from the exceptions core-network raises. */
class PackFailureTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `no answer is status 0, to be asked again unchanged`() {
        listOf(
            NetworkException("timeout", requestMayHaveBeenSent = true),
            NetworkException("no route", requestMayHaveBeenSent = false),
            IllegalStateException("anything that is not the server's answer"),
        ).forEach { error ->
            val failure = PackFailure.of(error)
            assertEquals(PackFailure(0), failure, error.toString())
            assertTrue(failure.retryable)
            assertFalse(failure.pauses)
        }
    }

    @Test
    fun `an answer that could not be read was an answer, so the batch goes again and the server answers from its log`() {
        val failure = PackFailure.of(ApiException(200, "unreadable_response", "The server's answer could not be read."))
        assertEquals(0, failure.status)
        assertTrue(failure.retryable)
        // Not on a refusal: that is the server's refusal whatever its body.
        assertEquals(502, PackFailure.of(ApiException(502, "unreadable_response", "bad gateway")).status)
    }

    @Test
    fun `a refusal carries what its body says - who finished the pack, null too, when, and what of the batch the pack took`() {
        val body = json(
            """{"error": "This pack is finished.", "code": "pack_finished", "finished_by": null, "finished_at": "2026-10-05T13:00:08",
                "taken": [{"client_op_id": "op-1", "seq": 7, "status": "applied", "reason": null}]}""",
        )
        val failure = PackFailure.of(ApiException(423, "pack_finished", "This pack is finished.", body = body))

        assertEquals(423, failure.status)
        assertEquals("pack_finished", failure.code)
        assertEquals(JsonNull, failure.finishedBy)                               // said, and null
        assertEquals(JsonPrimitive("2026-10-05T13:00:08"), failure.finishedAt)
        assertEquals(body["taken"], failure.taken)
        assertFalse(failure.retryable)
        assertFalse(failure.pauses)
    }

    @Test
    fun `a key the body did not have stays unsaid`() {
        val failure = PackFailure.of(ApiException(403, "pack_read_only", "View only.", body = json("""{"code": "pack_read_only"}""")))
        assertNull(failure.finishedBy)
        assertNull(failure.finishedAt)
        assertNull(failure.taken)
        assertNull(failure.reason)
        // And with no body at all.
        assertEquals(PackFailure(404, "pack_not_found"), PackFailure.of(ApiException(404, "pack_not_found", "Gone.")))
    }

    @Test
    fun `a malformed batch is named by the reason the server gave, and one too large by its item`() {
        val malformed = PackFailure.of(
            ApiException(400, "invalid_op", "Operation 0 is malformed (bad_path).", body = json("""{"code": "invalid_op", "index": 0, "reason": "bad_path"}""")),
        )
        assertEquals("bad_path", malformed.reason)
        val large = PackFailure.of(
            ApiException(413, "item_too_large", "Too large.", body = json("""{"code": "item_too_large", "item": "lz-1", "taken": []}""")),
        )
        assertEquals("lz-1", large.item)
        assertEquals(json("""{"taken": []}""")["taken"], large.taken)
    }

    @Test
    fun `too many requests is asked again, after what the server asked for`() {
        val failure = PackFailure.of(RateLimitedException("Slow down.", retryAfterSeconds = 30))
        assertEquals(PackFailure(429, "rate_limited", retryAfterSeconds = 30), failure)
        assertTrue(failure.retryable)
        assertTrue(PackFailure.of(ApiException(503, null, "busy")).retryable)
    }

    @Test
    fun `what is about the account pauses the edits rather than deciding anything about the pack`() {
        val other = PackFailure.of(OtherAccountException("someone else is signed in"))
        assertEquals(PackFailure(0, "other_account"), other)
        val ended = PackFailure.of(SessionEndedException(SignedOutReason.SESSION_ENDED, "session_revoked", "Sign in again."))
        assertEquals(401, ended.status)
        val gate = PackFailure.of(AffiliationRequiredException("Not cleared yet."))
        assertEquals(PackFailure(403, "affiliation_required"), gate)
        val off = PackFailure.of(ApiException(403, "feature_disabled", "Not on.", body = json("""{"code": "feature_disabled"}""")))
        assertEquals(PackFailure(403, "feature_disabled"), off)

        listOf(other, ended, gate, off, PackFailure(401)).forEach { assertTrue(it.pauses, it.toString()) }
        listOf(PackFailure(0), PackFailure(403), PackFailure(403, "pack_read_only"), PackFailure(404), PackFailure(423, "pack_finished"))
            .forEach { assertFalse(it.pauses, it.toString()) }
    }

    @Test
    fun `a failure written as JSON reads as the fixtures write it, a status not given being 0`() {
        assertEquals(PackFailure(0), PackFailure.fromJson(json("{}")))
        assertEquals(PackFailure(401), PackFailure.fromJson(json("""{"status": 401}""")))
        assertEquals(
            PackFailure(423, "pack_finished", finishedBy = JsonNull, finishedAt = JsonPrimitive("2026-10-05T13:00:05")),
            PackFailure.fromJson(json("""{"status": 423, "code": "pack_finished", "finished_by": null, "finished_at": "2026-10-05T13:00:05"}""")),
        )
    }

    @Test
    fun `a 423 leaves what the session knew of who finished the pack and when, for each the body does not say`() {
        val pack = json(
            """{"uuid": "p-1", "status": "active", "role": "editor", "head_seq": 1, "finished_at": "2026-10-05T12:00:00", "finished_by": {"id": 1, "name": "Colin"},
                "members": [], "items": [{"uuid": "lz-1", "kind": "lz", "name": "LZ", "data": {"a": 1}}]}""",
        )
        val edited = PackSessions.edit(PackSessions.open(pack, 2), listOf(json("""{"type": "set", "item": "lz-1", "path": ["a"], "value": 2}""")), { "op-1" })
        val out = requireNotNull(PackSessions.nextBatch(edited.session)).session

        val unsaid = PackSessions.batchFailed(out, PackFailure(423, "pack_finished", finishedAt = JsonPrimitive("2026-10-05T13:00:05")))
        assertEquals(json("""{"id": 1, "name": "Colin"}"""), unsaid.pack["finished_by"])
        assertEquals(JsonPrimitive("2026-10-05T13:00:05"), unsaid.pack["finished_at"])
        val saidNull = PackSessions.batchFailed(out, PackFailure(423, "pack_finished", finishedBy = JsonNull))
        assertEquals(JsonNull, saidNull.pack["finished_by"])
        assertEquals(JsonPrimitive("2026-10-05T12:00:00"), saidNull.pack["finished_at"])
    }
}
