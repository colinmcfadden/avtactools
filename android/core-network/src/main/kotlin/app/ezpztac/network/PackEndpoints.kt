package app.ezpztac.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Mission packs, teams, invitations and finding people (docs/MISSION_PACKS.md §4, `contracts/openapi.yaml`): every route an
 * app calls, as functions. Each needs the `mission_packs` entitlement; without it the server answers 403 `feature_disabled`.
 *
 * None of these routes reads an `Idempotency-Key`. A write that may be repeated names what it makes instead: a pack's `uuid`,
 * a copied item's `item`, an operation's `client_op_id`. Given again, the server answers with the first rather than making a
 * second.
 *
 * The screens' calls are typed. The pack engine's three (the pack whole, the log, a batch of operations) are not: what an item
 * holds and what an operation does are the web's JSON, a `null` in them means something, and a field a newer server adds must
 * reach the engine, so their answers are handed over as the server wrote them.
 */

/** Who a pack is shared with: left as it is, with no team, or with a team whose members then have [role] (`editor` or `viewer`). */
public sealed interface TeamShare {
    public data object Unchanged : TeamShare
    public data object None : TeamShare
    public data class With(val teamId: Int, val role: String = "editor") : TeamShare
}

/** A record in the caller's library to copy into a pack ([kind] `lz`, `route` or `pointset`), by its server id or by the uuid its device gave it. */
public sealed interface PackSource {
    public val kind: String

    public data class Id(override val kind: String, val id: Int) : PackSource
    public data class ClientUuid(override val kind: String, val uuid: String) : PackSource
}

/**
 * The paths with an id in them, checked before anything is sent. The transport splits a path on `/` and OkHttp resolves `..`,
 * so an id that is not one could reach a different route: it is refused here with [IllegalArgumentException].
 */
internal object PackPaths {
    // As the server makes them, and the length of a UUID at most.
    private val PACK = Regex("[A-Za-z0-9-]{1,36}")
    // The server's own rule for an item (pack_ops.py, _ITEM_ID).
    private val ITEM = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")

    fun pack(uuid: String): String {
        require(PACK.matches(uuid)) { "Not a pack id: \"$uuid\"" }
        return "/api/packs/$uuid"
    }

    fun item(uuid: String, item: String): String {
        require(ITEM.matches(item)) { "Not a pack item id: \"$item\"" }
        return "${pack(uuid)}/items/$item"
    }
}

private const val DEFAULT_PAGE = 500

private fun call(
    method: String,
    path: String,
    body: JsonObject? = null,
    query: Map<String, String> = emptyMap(),
    priority: CallPriority = CallPriority.NORMAL,
    asUser: Int? = null,
) = ApiClient.Call(method, path, query = query, body = body, callPriority = priority, asUser = asUser)

// -- Packs --------------------------------------------------------------------------

/** The packs the caller is in, directly or through a team, most recently changed first. */
public suspend fun ApiClient.listPacks(): List<PackSummary> =
    decode<PacksBody>(execute(call("GET", "/api/packs"))).packs

/**
 * Makes a pack the caller owns. Pass the [uuid] the device chose and a retry after a lost response returns the first pack
 * ([Saved.created] false) instead of making a second. A pack is shared with a team only by [TeamShare.With].
 */
public suspend fun ApiClient.createPack(
    name: String,
    description: String? = null,
    share: TeamShare = TeamShare.Unchanged,
    uuid: String? = null,
): Saved<Pack> {
    val response = execute(
        call(
            "POST", "/api/packs",
            body = buildJsonObject {
                put("name", name)
                if (description != null) put("description", description)
                if (share is TeamShare.With) { put("team_id", share.teamId); put("team_role", share.role) }
                if (uuid != null) put("uuid", uuid)
            },
        ),
    )
    return Saved(decode(response), created = response.status == 201)
}

/** The pack with its members and every item, exactly as after its `head_seq`. A pack the caller is not in is a 404 (`pack_not_found`). */
public suspend fun ApiClient.pack(uuid: String): Pack = decode(execute(call("GET", PackPaths.pack(uuid))))

/**
 * Renames or describes the pack (an editor may), or shares it with a team or stops sharing it (only the owner). Only what is given
 * is sent. A finished pack refuses a new name or description with 423 `pack_finished`.
 */
public suspend fun ApiClient.updatePack(
    uuid: String,
    name: String? = null,
    description: String? = null,
    share: TeamShare = TeamShare.Unchanged,
): Pack = decode(
    execute(
        call(
            "PUT", PackPaths.pack(uuid),
            body = buildJsonObject {
                if (name != null) put("name", name)
                if (description != null) put("description", description)
                when (share) {
                    TeamShare.Unchanged -> Unit
                    TeamShare.None -> put("team_id", JsonNull)                  // null is "no team", which leaving it out is not
                    is TeamShare.With -> { put("team_id", share.teamId); put("team_role", share.role) }
                }
            },
        ),
    ),
)

/** Deletes the pack and everything in it (the owner only). */
public suspend fun ApiClient.deletePack(uuid: String): StatusReply = decode(execute(call("DELETE", PackPaths.pack(uuid))))

/** Finishes the pack (the owner only): every edit is then refused with 423, the owner's included. Finishing it again changes nothing. */
public suspend fun ApiClient.finishPack(uuid: String): Pack = decode(execute(call("POST", "${PackPaths.pack(uuid)}/finish")))

/** Reopens a finished pack (the owner only). */
public suspend fun ApiClient.reopenPack(uuid: String): Pack = decode(execute(call("POST", "${PackPaths.pack(uuid)}/reopen")))

/** A new pack the caller owns, with a copy of every item; a finished pack can be duplicated. The server names it "<name> (copy)" unless [name] is given. */
public suspend fun ApiClient.duplicatePack(uuid: String, name: String? = null): Pack = decode(
    execute(call("POST", "${PackPaths.pack(uuid)}/duplicate", body = buildJsonObject { if (name != null) put("name", name) })),
)

/** A page of the pack's log after [since], oldest first, for the History panel. Send [PackEventPage.cursor] back as [since] for the next. */
public suspend fun ApiClient.packEvents(uuid: String, since: Long, limit: Int = DEFAULT_PAGE): PackEventPage =
    decode(execute(call("GET", "${PackPaths.pack(uuid)}/events", query = eventsQuery(since, limit))))

/** How far the caller has looked in the pack, on every device they use. It never goes back. */
public suspend fun ApiClient.markPackSeen(uuid: String, seq: Long): PackSeenDto =
    decode(execute(call("PUT", "${PackPaths.pack(uuid)}/seen", body = buildJsonObject { put("seq", seq) })))

private fun eventsQuery(since: Long, limit: Int) = mapOf("since" to since.toString(), "limit" to limit.toString())

// -- Items --------------------------------------------------------------------------

/**
 * Copies a record from the caller's library into the pack as [item], which remembers where it came from. Naming the [item] is what
 * makes a retry after a lost response safe: it returns the first copy ([Saved.created] false). An imported AMPS mission is refused
 * (`mission_not_supported`).
 */
public suspend fun ApiClient.copyIntoPack(
    uuid: String,
    source: PackSource,
    item: String,
    name: String? = null,
    summary: String? = null,
): Saved<PackItemReply> {
    val response = execute(
        call(
            "POST", "${PackPaths.pack(uuid)}/items",
            body = buildJsonObject {
                put(
                    "source",
                    buildJsonObject {
                        put("kind", source.kind)
                        when (source) {
                            is PackSource.Id -> put("id", source.id)
                            is PackSource.ClientUuid -> put("client_uuid", source.uuid)
                        }
                    },
                )
                put("item", item)
                if (name != null) put("name", name)
                if (summary != null) put("summary", summary)
            },
        ),
    )
    return Saved(decode(response), created = response.status == 201)
}

/** One item, whole. */
public suspend fun ApiClient.packItem(uuid: String, item: String): PackItemDto =
    decode<ItemBody>(execute(call("GET", PackPaths.item(uuid, item)))).item

/** Replaces a copied item's content with its library original as it is now. Only whoever copied it in may (`not_your_original`). */
public suspend fun ApiClient.updateFromOriginal(uuid: String, item: String, summary: String? = null): PackItemReply = decode(
    execute(
        call("POST", "${PackPaths.item(uuid, item)}/update-from-original", body = buildJsonObject { if (summary != null) put("summary", summary) }),
    ),
)

/** Saves a copy of the item to the caller's own library, where it syncs like any record. Needs the `cloud_save` entitlement too. */
public suspend fun ApiClient.savePackItemToLibrary(uuid: String, item: String, name: String? = null): LibraryCopy = decode(
    execute(call("POST", "${PackPaths.item(uuid, item)}/library", body = buildJsonObject { if (name != null) put("name", name) })),
)

// -- Members ------------------------------------------------------------------------

/** Adds someone who shares a team with the owner (the owner only). Anyone else is invited by email ([inviteToPack]). */
public suspend fun ApiClient.addPackMember(uuid: String, userId: Int, role: String = "editor"): PackMember = decode<MemberBody>(
    execute(call("POST", "${PackPaths.pack(uuid)}/members", body = buildJsonObject { put("user_id", userId); put("role", role) })),
).member

/** Changes a member's role (the owner only). `owner` hands the pack over, and the old owner stays as an editor. */
public suspend fun ApiClient.changePackMember(uuid: String, userId: Int, role: String): PackMember = decode<MemberBody>(
    execute(call("PUT", "${PackPaths.pack(uuid)}/members/$userId", body = buildJsonObject { put("role", role) })),
).member

/** Removes a member (the owner only), or leaves the pack (the caller's own id). The owner hands the pack over first. */
public suspend fun ApiClient.removePackMember(uuid: String, userId: Int): StatusReply =
    decode(execute(call("DELETE", "${PackPaths.pack(uuid)}/members/$userId")))

// -- Invitations to a pack ------------------------------------------------------------

/** The pack's invitations not yet answered (the owner only). */
public suspend fun ApiClient.packInvites(uuid: String): List<Invite> =
    decode<InvitesBody>(execute(call("GET", "${PackPaths.pack(uuid)}/invites"))).invites

/**
 * Invites anyone by email (the owner only). Inviting the same address again sends a new link in place of the old one
 * ([Saved.created] false). 30 an hour per person ([RateLimitedException]).
 */
public suspend fun ApiClient.inviteToPack(uuid: String, email: String, role: String = "editor"): Saved<InviteReply> {
    val response = execute(call("POST", "${PackPaths.pack(uuid)}/invites", body = buildJsonObject { put("email", email); put("role", role) }))
    return Saved(decode(response), created = response.status == 201)
}

/** Sends a new link, good for another 14 days; the old one stops working. */
public suspend fun ApiClient.resendPackInvite(uuid: String, inviteId: Int): InviteReply =
    decode(execute(call("POST", "${PackPaths.pack(uuid)}/invites/$inviteId/resend")))

/** Withdraws an invitation (the owner only). */
public suspend fun ApiClient.revokePackInvite(uuid: String, inviteId: Int): Invite =
    decode<InviteBody>(execute(call("DELETE", "${PackPaths.pack(uuid)}/invites/$inviteId"))).invite

// -- The caller's own invitations -------------------------------------------------------

/** Invitations waiting for the caller, to a pack or a team: sent to their sign-in address or their verified `.mil` one. */
public suspend fun ApiClient.myInvites(): List<Invite> = decode<InvitesBody>(execute(call("GET", "/api/invites"))).invites

/** Accepts an invitation addressed to the caller. One already answered or withdrawn is 410 (`invite_gone`, `invite_expired`). */
public suspend fun ApiClient.acceptInvite(id: Int): InviteAccepted = decode(execute(call("POST", "/api/invites/$id/accept")))

public suspend fun ApiClient.declineInvite(id: Int): Invite =
    decode<InviteBody>(execute(call("POST", "/api/invites/$id/decline"))).invite

/**
 * Accepts from an emailed or shared link's token, whichever address the caller signed in with. With [asUser], only for that account: under
 * anyone else's session it is refused before anything is sent ([OtherAccountException]), so a link is never joined for the wrong person.
 */
public suspend fun ApiClient.acceptInviteLink(token: String, asUser: Int? = null): InviteAccepted =
    decode(execute(call("POST", "/api/invites/accept", body = buildJsonObject { put("token", token) }, asUser = asUser)))

// -- Teams ----------------------------------------------------------------------------

/** The caller's teams, by name. */
public suspend fun ApiClient.listTeams(): List<TeamSummary> = decode<TeamsBody>(execute(call("GET", "/api/teams"))).teams

/** Makes a team the caller owns. */
public suspend fun ApiClient.createTeam(name: String): Team =
    decode(execute(call("POST", "/api/teams", body = buildJsonObject { put("name", name) })))

/** A team and its members. A team the caller is not in is a 404 (`team_not_found`). */
public suspend fun ApiClient.team(id: Int): Team = decode(execute(call("GET", "/api/teams/$id")))

/** Renames a team (its owner or an admin). */
public suspend fun ApiClient.renameTeam(id: Int, name: String): Team =
    decode(execute(call("PUT", "/api/teams/$id", body = buildJsonObject { put("name", name) })))

/** Deletes a team (its owner only). Packs shared with it stop being shared, each saying so in its log. */
public suspend fun ApiClient.deleteTeam(id: Int): StatusReply = decode(execute(call("DELETE", "/api/teams/$id")))

/** Changes a member's role (the owner only): `owner` hands the team over and the old owner becomes an admin; else `admin` or `member`. */
public suspend fun ApiClient.changeTeamMember(id: Int, userId: Int, role: String): Team =
    decode(execute(call("PUT", "/api/teams/$id/members/$userId", body = buildJsonObject { put("role", role) })))

/** Leaves the team (the caller's own id), or removes someone (the owner; an admin removes members only). */
public suspend fun ApiClient.removeTeamMember(id: Int, userId: Int): StatusReply =
    decode(execute(call("DELETE", "/api/teams/$id/members/$userId")))

/** The team's invitations not yet answered (its owner or an admin). */
public suspend fun ApiClient.teamInvites(id: Int): List<Invite> =
    decode<InvitesBody>(execute(call("GET", "/api/teams/$id/invites"))).invites

/**
 * Invites someone to the team by [email], or with none makes a single-use link whose [InviteReply.token] is in this answer and
 * never again. Only the owner can invite an `admin`.
 */
public suspend fun ApiClient.inviteToTeam(id: Int, email: String? = null, role: String = "member"): InviteReply = decode(
    execute(call("POST", "/api/teams/$id/invites", body = buildJsonObject { put("role", role); if (email != null) put("email", email) })),
)

public suspend fun ApiClient.revokeTeamInvite(id: Int, inviteId: Int): Invite =
    decode<InviteBody>(execute(call("DELETE", "/api/teams/$id/invites/$inviteId"))).invite

// -- People -----------------------------------------------------------------------------

/** People who share a team with the caller, by name or sign-in address ([q]: 2 to 100 characters). Nobody else is ever found. */
public suspend fun ApiClient.searchPeople(q: String): List<UserBrief> =
    decode<UsersBody>(execute(call("GET", "/api/users/search", query = mapOf("q" to q)))).users

// -- The pack engine's calls --------------------------------------------------------------
//
// Answered as the server wrote them (see the top of this file). [asUser] is the account the engine is working for: under anyone
// else's session the call is refused before it is sent ([OtherAccountException]), so queued edits never go up as someone else.

/** The pack whole, as [pack] returns it, but untyped. */
public suspend fun ApiClient.packDocument(uuid: String, asUser: Int? = null): JsonObject =
    decode(execute(call("GET", PackPaths.pack(uuid), asUser = asUser)))

/**
 * A page of the log after [since], untyped. A catch-up nobody is waiting on (a poll, a drain in the background) passes
 * [background], so it waits while the server is busy with an analysis or a viewshed.
 */
public suspend fun ApiClient.packEventsDocument(
    uuid: String,
    since: Long,
    limit: Int = DEFAULT_PAGE,
    background: Boolean,
    asUser: Int? = null,
): JsonObject = decode(
    execute(
        call(
            "GET", "${PackPaths.pack(uuid)}/events", query = eventsQuery(since, limit),
            priority = if (background) CallPriority.BACKGROUND else CallPriority.NORMAL, asUser = asUser,
        ),
    ),
)

/**
 * Sends a batch of operations, `{ops, base_seq}`, as it is: a `value: null` or an `after: null` in an operation means something
 * and goes as JSON null. A refusal comes back as an [ApiException] whose [ApiException.body] carries the rest of the server's answer.
 */
public suspend fun ApiClient.sendPackOps(uuid: String, batch: JsonObject, asUser: Int? = null): JsonObject =
    decode(execute(call("POST", "${PackPaths.pack(uuid)}/ops", body = batch, asUser = asUser)))
