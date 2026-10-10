package app.ezpztac.missionpacks

import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

/** The app's own words for a failed pack call and an accepted invitation, as the web's packApi.test.js and inviteLink.test.js hold them. */
class PackMessagesTest {
    @Test
    fun `a refusal is told in the app's words, never the server's`() {
        assertEquals("This pack is finished. It is read-only until its owner reopens it.", PackMessages.words(PackFailure(423, "pack_finished")))
        // A server fault's text (a Python exception) is never shown.
        val fault = ApiException(500, null, "KeyError: 'name'", body = Json.parseToJsonElement("""{"error": "KeyError: 'name'"}""").jsonObject)
        assertEquals("Something went wrong on the server. Try again.", PackMessages.words(PackFailure.of(fault)))
        assertEquals(
            "There is no connection to the server. Try again when you are back online.",
            PackMessages.words(PackFailure.of(NetworkException("offline", IOException("down"), requestMayHaveBeenSent = false))),
        )
        assertEquals("That invitation has expired. Ask whoever sent it for a new one.", PackMessages.words(PackFailure(410, "invite_expired")))
        assertEquals("That pack is gone, or you are no longer in it.", PackMessages.words(PackFailure(404)))
        assertEquals("You do not have permission to do that.", PackMessages.words(PackFailure(403)))
        assertEquals("Too many requests. Wait a moment and try again.", PackMessages.words(PackFailure(429)))
        assertEquals("Mission Packs are not turned on for your account yet.", PackMessages.words(PackFailure(403, "feature_disabled")))
        // A code the app has no words for is told by its status.
        assertEquals("You do not have permission to do that.", PackMessages.words(PackFailure(403, "something_new")))
    }

    private fun invite() = Invite(id = 1, role = "editor", status = "accepted", expiresAt = "2026-10-15T00:00:00Z", createdAt = "2026-10-08T00:00:00Z")

    private fun pack(role: String) = PackSummary(
        uuid = "p-1", name = "OP DK", description = "", status = "active", role = role, owner = PackPerson(1, "Sam B."), headSeq = 0, seenSeq = 0,
        memberCount = 2, audienceCount = 2, itemCount = 0, itemCounts = PackItemCounts(0, 0, 0),
        createdAt = "2026-10-08T00:00:00Z", updatedAt = "2026-10-08T00:00:00Z",
    )

    @Test
    fun `joining is told as the role the person now has`() {
        assertEquals("You joined OP DK as the owner.", PackMessages.joinedMessage(InviteAccepted(invite(), pack = pack("owner"))))
        assertEquals("You joined OP DK as a viewer.", PackMessages.joinedMessage(InviteAccepted(invite(), pack = pack("viewer"))))
        assertEquals("You joined OP DK as an editor.", PackMessages.joinedMessage(InviteAccepted(invite(), pack = pack("editor"))))
        val team = TeamSummary(id = 3, name = "2-10 AVN", role = "member", memberCount = 4, createdAt = "2026-10-08T00:00:00Z")
        assertEquals("You joined the team 2-10 AVN.", PackMessages.joinedMessage(InviteAccepted(invite(), team = team)))
        assertEquals("You accepted the invitation.", PackMessages.joinedMessage(InviteAccepted(invite())))
        assertEquals("You accepted the invitation.", PackMessages.joinedMessage(null))
    }
}
