package app.ezpztac.android.packs

import app.ezpztac.network.ApiClient
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.acceptInviteLink
import javax.inject.Inject

/** Accepting a mission-pack or team invitation from its link's token, for whichever account is signed in. A seam, so the shell is tried without a server. */
interface PackInvites {
    suspend fun accept(token: String): InviteAccepted
}

/** `POST /api/invites/accept`. */
class ApiPackInvites @Inject constructor(private val client: ApiClient) : PackInvites {
    override suspend fun accept(token: String): InviteAccepted = client.acceptInviteLink(token)
}
