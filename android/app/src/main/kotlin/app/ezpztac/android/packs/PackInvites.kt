package app.ezpztac.android.packs

import app.ezpztac.network.ApiClient
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.acceptInviteLink
import javax.inject.Inject

/**
 * Accepting a mission-pack or team invitation from its link's token, for the account [asUser] and nobody else: under anyone else's session
 * it is refused before anything is sent, so a link is never joined for the wrong person. A seam, so the shell is tried without a server.
 */
interface PackInvites {
    suspend fun accept(token: String, asUser: Int): InviteAccepted
}

/** `POST /api/invites/accept`. */
class ApiPackInvites @Inject constructor(private val client: ApiClient) : PackInvites {
    override suspend fun accept(token: String, asUser: Int): InviteAccepted = client.acceptInviteLink(token, asUser)
}
