package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Notifications: the feed with its tag, nothing new, what is seen, and the settings. What is sent, and what the recorded answers say. */
class NotificationCallsTest {
    private val me = Recorded.user().id

    private fun body(request: okhttp3.mockwebserver.RecordedRequest): JsonObject = ApiClient.JSON.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun `the feed comes with its tag, and the tag goes back so nothing new is a 304`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request ->
                if (request.getHeader("If-None-Match") == "\"abc\"") MockResponse().setResponseCode(304)
                else Recorded.mock("notifications: an invitation to a pack").setHeader("ETag", "\"abc\"")
            }
            val first = rig.client.notifications(null, me) as NotificationsAnswer.Changed
            assertEquals("\"abc\"", first.etag)
            val note = first.feed.notifications.single()
            assertEquals(listOf("pack_invite", "invitations"), listOf(note.type, note.group))
            assertEquals("OP EAGLE", note.pack?.name)
            assertEquals("Colin", note.actor?.name)
            assertEquals(NotificationsAnswer.NotModified, rig.client.notifications(first.etag, me))
            val asked = rig.requestsTo("/api/notifications")
            assertNull(asked[0].getHeader("If-None-Match"))
            assertEquals("\"abc\"", asked[1].getHeader("If-None-Match"))
            assertEquals("Bearer access-1", asked[1].getHeader("Authorization"))
        }
    }

    @Test
    fun `a tag that is not one is neither kept nor sent back`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("notifications: a pack finished").setHeader("ETag", "abc") }
            assertNull((rig.client.notifications("\"x\"\r\nX-Evil: 1", me) as NotificationsAnswer.Changed).etag)
            assertNull(rig.requestsTo("/api/notifications").single().getHeader("If-None-Match"))
            assertTrue(isEtag("W/\"5f2b\""))
            assertFalse(isEtag("\"a\"b\""))
        }
    }

    @Test
    fun `under another account's session nothing is asked`() = runBlocking<Unit> {
        Rig().use { rig ->
            assertThrows<OtherAccountException> { rig.client.notifications(null, me + 1) }
            assertThrows<OtherAccountException> { rig.client.markNotificationsSeen(listOf(1L), me + 1) }
            assertTrue(rig.requestsTo("/api/notifications").isEmpty())
        }
    }

    @Test
    fun `seen sends the ids once each and answers the callers own`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("notifications: seen") }
            assertEquals(listOf(3L), rig.client.markNotificationsSeen(listOf(3L, 3L, 9L), me))
            val sent = body(rig.requestsTo("/api/notifications/seen").single())["ids"]!!.jsonArray
            assertEquals(listOf(JsonPrimitive(3L), JsonPrimitive(9L)), sent.toList())
            assertEquals(emptyList<Long>(), rig.client.markNotificationsSeen(emptyList(), me))
            assertEquals(1, rig.requestsTo("/api/notifications/seen").size)                    // nothing to say: nothing sent
        }
    }

    @Test
    fun `settings send only what changes and answer what holds`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("notifications: settings") }
            val settings = rig.client.changeNotificationSettings(me, packFinished = false)
            assertEquals(NotificationSettings(invitations = true, packFinished = false, packOwner = true), settings)
            assertEquals(JsonObject(mapOf("pack_finished" to JsonPrimitive(false))), body(rig.requestsTo("/api/notifications/settings").single()))
        }
    }

    @Test
    fun `a refusal is the servers words`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("notifications: seen, not ids") }
            val refused = assertThrows<ApiException> { rig.client.markNotificationsSeen(listOf(1L), me) }
            assertEquals(400 to "invalid_ids", refused.status to refused.code)
        }
    }
}
