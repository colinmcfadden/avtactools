package app.ezpztac.missionpacks

import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.TeamSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Accepting the invitation link the app was opened with: the web's useInviteLink cases (inviteLink.test.js), one for one, and what
 * keeping the link across a caller that goes away needs here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteAcceptanceTest {
    private companion object {
        const val TOKEN = "Xq3v_8yQm2LZk9-WbT4sPa7Rr1Nd5Cf6Hg0Jj2Kk3Ll"
        const val WAITING = "Mission Packs are not turned on for your account yet, so the invitation is waiting."

        fun invite() = Invite(id = 1, role = "editor", status = "accepted", expiresAt = "2026-10-15T00:00:00Z", createdAt = "2026-10-08T00:00:00Z")

        fun pack(role: String) = PackSummary(
            uuid = "p-1", name = "OP DK", description = "", status = "active", role = role, owner = PackPerson(1, "Sam B."), headSeq = 0,
            seenSeq = 0, memberCount = 2, audienceCount = 2, itemCount = 0, itemCounts = PackItemCounts(0, 0, 0),
            createdAt = "2026-10-08T00:00:00Z", updatedAt = "2026-10-08T00:00:00Z",
        )

        fun joinedPack(role: String) = InviteAccepted(invite(), pack = pack(role))

        fun refusal(status: Int, code: String) =
            ApiException(status, code, "Refused.", body = Json.parseToJsonElement("""{"code": "$code", "error": "x"}""").jsonObject)
    }

    @Test
    fun `accepts once the person can open packs, says what they joined, and the link is done with`() = runTest {
        val asked = mutableListOf<String>()
        val acceptance = InviteAcceptance({ asked += it; joinedPack("editor") }, this)
        assertTrue(acceptance.run(TOKEN, enabled = true))
        assertEquals(listOf(TOKEN), asked)
        val joined = acceptance.state.value as InviteState.Joined
        assertEquals("You joined OP DK as an editor.", joined.message)
        assertEquals("p-1", joined.answer.pack?.uuid)
    }

    @Test
    fun `asks once however many times it is run meanwhile`() = runTest {
        val answer = CompletableDeferred<InviteAccepted>()
        var asked = 0
        val acceptance = InviteAcceptance({ asked++; answer.await() }, this)
        val first = async { acceptance.run(TOKEN, enabled = true) }
        val second = async { acceptance.run(TOKEN, enabled = true) }
        runCurrent()
        assertEquals(1, asked)
        assertEquals(InviteState.Accepting, acceptance.state.value)
        val team = TeamSummary(id = 2, name = "B Co", role = "member", memberCount = 4, createdAt = "2026-10-08T00:00:00Z")
        answer.complete(InviteAccepted(invite(), team = team))
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals("You joined the team B Co.", (acceptance.state.value as InviteState.Joined).message)
        assertEquals(1, asked)
    }

    @Test
    fun `keeps the link while packs are not on for the account`() = runTest {
        val acceptance = InviteAcceptance({ error("never asked while packs are off") }, this)
        assertFalse(acceptance.run(TOKEN, enabled = false))
        assertEquals(InviteState.Waiting(WAITING), acceptance.state.value)
    }

    @Test
    fun `a link the server refused is done with, in the app's words`() = runTest {
        val acceptance = InviteAcceptance({ throw refusal(410, "invite_expired") }, this)
        assertTrue(acceptance.run(TOKEN, enabled = true))
        assertEquals(InviteState.Failed("That invitation has expired. Ask whoever sent it for a new one.", retryable = false), acceptance.state.value)

        // Whatever else the server refuses for good: used or withdrawn, not a link at all, not this person's to take.
        listOf(refusal(409, "invite_gone"), refusal(404, "invite_not_found"), refusal(403, "not_in_team"), refusal(400, "bad_request")).forEach { refused ->
            val again = InviteAcceptance({ throw refused }, this)
            assertTrue(again.run(TOKEN, enabled = true), "${refused.status} ${refused.code}")
            assertFalse((again.state.value as InviteState.Failed).retryable)
        }
    }

    @Test
    fun `a link that could not be sent, or met a busy or broken server, is kept, and tried again when asked`() = runTest {
        val failures = ArrayDeque<Throwable>(
            listOf(
                NetworkException("Network Error", requestMayHaveBeenSent = false),
                RateLimitedException("Too many requests.", 30),
                refusal(503, "unavailable"),
            ),
        )
        var asked = 0
        val acceptance = InviteAcceptance({ asked++; failures.removeFirstOrNull()?.let { throw it } ?: joinedPack("viewer") }, this)
        assertFalse(acceptance.run(TOKEN, enabled = true))
        assertEquals(
            InviteState.Failed("There is no connection to the server. Try again when you are back online.", retryable = true),
            acceptance.state.value,
        )
        assertFalse(acceptance.run(TOKEN, enabled = true))
        assertEquals(InviteState.Failed("Too many invitations for now. Try again later.", retryable = true), acceptance.state.value)
        assertFalse(acceptance.run(TOKEN, enabled = true))
        assertEquals(InviteState.Failed("Something went wrong on the server. Try again.", retryable = true), acceptance.state.value)
        assertTrue(acceptance.run(TOKEN, enabled = true))
        assertEquals("You joined OP DK as a viewer.", (acceptance.state.value as InviteState.Joined).message)
        assertEquals(4, asked)
    }

    @Test
    fun `does nothing without a link`() = runTest {
        val acceptance = InviteAcceptance({ error("never asked without a link") }, this)
        assertFalse(acceptance.run(null, enabled = true))
        assertEquals(InviteState.None, acceptance.state.value)
    }

    // The web's effect returns at once with no link, so its last answer stands until it is put away. Here the caller forgets the link
    // once a run says it is done with, and runs again whenever the feature is turned on or off: that run must not take the answer away
    // before the person has seen it.
    @Test
    fun `a run without a link leaves the last answer, which only putting it away takes away`() = runTest {
        val joined = InviteAcceptance({ joinedPack("editor") }, this)
        assertTrue(joined.run(TOKEN, enabled = true))
        assertFalse(joined.run(null, enabled = true))
        assertFalse(joined.run(null, enabled = false))
        assertEquals("You joined OP DK as an editor.", (joined.state.value as InviteState.Joined).message)
        joined.dismiss()
        assertEquals(InviteState.None, joined.state.value)

        val refused = InviteAcceptance({ throw refusal(410, "invite_expired") }, this)
        assertTrue(refused.run(TOKEN, enabled = true))
        assertFalse(refused.run(null, enabled = true))
        assertFalse(refused.run(null, enabled = false))
        assertEquals(InviteState.Failed("That invitation has expired. Ask whoever sent it for a new one.", retryable = false), refused.state.value)
    }

    @Test
    fun `words what was joined`() {
        assertEquals("You joined OP DK as the owner.", PackMessages.joinedMessage(joinedPack("owner")))
        assertEquals("You accepted the invitation.", PackMessages.joinedMessage(InviteAccepted(invite())))
    }

    // Android's own: the request is not the caller's, so it goes on if the caller goes away (the screen that asked was closed), and
    // the next run is told what it came to rather than asking again, which the server would refuse as a used link.
    @Test
    fun `a caller that goes away does not end the request, and the next run is told what it came to`() = runTest {
        val answer = CompletableDeferred<InviteAccepted>()
        var asked = 0
        val acceptance = InviteAcceptance({ asked++; answer.await() }, this)
        val gone = launch { acceptance.run(TOKEN, enabled = true) }
        runCurrent()
        gone.cancel()
        answer.complete(joinedPack("editor"))
        runCurrent()
        assertTrue(acceptance.run(TOKEN, enabled = true))
        assertEquals("You joined OP DK as an editor.", (acceptance.state.value as InviteState.Joined).message)
        assertEquals(1, asked)
    }

    @Test
    fun `the answer can be put away`() = runTest {
        val acceptance = InviteAcceptance({ joinedPack("editor") }, this)
        acceptance.run(TOKEN, enabled = true)
        acceptance.dismiss()
        assertEquals(InviteState.None, acceptance.state.value)
    }
}
