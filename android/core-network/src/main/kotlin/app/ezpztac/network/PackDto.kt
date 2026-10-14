package app.ezpztac.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Mission packs, teams, invitations and finding people (docs/MISSION_PACKS.md): what the routes under
 * `/api/packs`, `/api/teams`, `/api/invites` and `/api/users/search` answer, one type per body in
 * `contracts/openapi.yaml`, held strictly to the real responses as the rest of Dto.kt is.
 *
 * A pack's place in its log (`seq`, `head_seq`, `seen_seq`, a cursor) is a Long everywhere, so it has one
 * width in the client, the session and the database. What an item holds and what an operation does are the
 * web's JSON, carried as they came: a newer web release may add a field to either, and a pack is shared, so
 * one person's app must not drop what another's wrote.
 */

/** Who did something. [id] is null once the account is deleted (the name stays as it was written) and for the server's own changes. */
@Serializable
public data class PackPerson(val id: Int? = null, val name: String)

/** The team a pack is shared with, and what its members may do there (`editor` or `viewer`). */
@Serializable
public data class PackTeam(
    val id: Int,
    val name: String,
    @SerialName("member_count") val memberCount: Int,
    val role: String? = null,
)

/** Live items by kind. */
@Serializable
public data class PackItemCounts(val lz: Int, val route: Int, val pointset: Int)

/** A pack as a list shows it. [role] is the caller's; a `finished` pack is read-only for everyone until its owner reopens it. */
@Serializable
public data class PackSummary(
    val uuid: String,
    val name: String,
    val description: String,
    val status: String,
    val role: String,
    val owner: PackPerson,
    val team: PackTeam? = null,
    /** The number of the pack's latest event. */
    @SerialName("head_seq") val headSeq: Long,
    /** The newest event the caller has seen, on any of their devices; 0 if never. */
    @SerialName("seen_seq") val seenSeq: Long,
    /** Members in their own right, not counting a shared team. */
    @SerialName("member_count") val memberCount: Int,
    /** Everyone who can open the pack, each once: its members and its team's. */
    @SerialName("audience_count") val audienceCount: Int,
    @SerialName("item_count") val itemCount: Int,
    @SerialName("item_counts") val itemCounts: PackItemCounts,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("finished_by") val finishedBy: PackPerson? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** A pack whole: its members and every live item, exactly as after event [headSeq]. Replaying the log from there keeps it current. */
@Serializable
public data class Pack(
    val uuid: String,
    val name: String,
    val description: String,
    val status: String,
    val role: String,
    val owner: PackPerson,
    val team: PackTeam? = null,
    @SerialName("head_seq") val headSeq: Long,
    @SerialName("seen_seq") val seenSeq: Long,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("audience_count") val audienceCount: Int,
    @SerialName("item_count") val itemCount: Int,
    @SerialName("item_counts") val itemCounts: PackItemCounts,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("finished_by") val finishedBy: PackPerson? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val members: List<PackMember>,
    val items: List<PackItemDto>,
    /** Where to open the live stream (`ws://` or `wss://`); null where the deployment has none, and the client polls the log instead. */
    @SerialName("live_url") val liveUrl: String? = null,
)

/** Someone in a pack in their own right. [email] is their sign-in address; [seenAt] is when they last looked, null if never. */
@Serializable
public data class PackMember(
    @SerialName("user_id") val userId: Int,
    val name: String,
    val email: String,
    val role: String,
    @SerialName("added_at") val addedAt: String,
    @SerialName("seen_at") val seenAt: String? = null,
)

/** One item in a pack: an LZ/PZ, a set of sketched routes or a set of points. */
@Serializable
public data class PackItemDto(
    val uuid: String,
    /** `lz`, `route` or `pointset`. */
    val kind: String,
    val name: String,
    /** Bumped by every operation applied to it. */
    val revision: Int,
    /** The event that last changed it. */
    val seq: Long,
    @SerialName("created_by") val createdBy: PackPerson? = null,
    @SerialName("updated_by") val updatedBy: PackPerson? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** Where a copy from someone's library came from; null for an item made in the pack. */
    val source: PackItemSource? = null,
    /** The library's JSON (a diagram, `{version, routes}`, or a list of points), present wherever the item is returned whole. */
    val data: JsonElement? = null,
)

/**
 * Where a copied item came from. How far the original and the copy have moved apart ([original], [originalUpdatedAt],
 * [packChanges], [lastPackChange]) is told only to whoever copied it in, since the original is their private record.
 */
@Serializable
public data class PackItemSource(
    val kind: String,
    /** The original's `client_uuid`; null only for a copy made before copies named their original. */
    val uuid: String? = null,
    val revision: Int,
    /** `same`, `changed` or `deleted`. */
    val original: String? = null,
    @SerialName("original_updated_at") val originalUpdatedAt: String? = null,
    /** While the original is `changed`: the edits made in the pack that updating from it would replace. */
    @SerialName("pack_changes") val packChanges: Int? = null,
    @SerialName("last_pack_change") val lastPackChange: PackChange? = null,
)

@Serializable
public data class PackChange(
    val actor: PackPerson,
    val summary: String,
    @SerialName("created_at") val createdAt: String,
)

/**
 * One line of a pack's log. An item operation (`item.create`, `set`, `insert` ...) is applied with the web's rules; a pack
 * event (`pack.finish`, `member.add` ...) is read for the pack's state; a type this version does not know is skipped.
 */
@Serializable
public data class PackEvent(
    val seq: Long,
    val type: String,
    val item: String? = null,
    val actor: PackPerson,
    /** The History panel's sentence. */
    val summary: String,
    /** `applied` or `skipped`. */
    val status: String,
    /** Why it was skipped (contracts/fixtures/packs/ops.json). */
    val reason: String? = null,
    @SerialName("client_op_id") val clientOpId: String? = null,
    /** The operation as applied. A `null` in it means something (`value`, `after`), so it is kept as sent. */
    val op: JsonObject,
    @SerialName("created_at") val createdAt: String,
)

/** How one operation sent went: applied, or skipped and why. An operation the pack had already seen is answered from its log. */
@Serializable
public data class PackOpResult(
    @SerialName("client_op_id") val clientOpId: String,
    val seq: Long,
    val status: String,
    val reason: String? = null,
)

/** The answer to a batch of operations: one result per operation, in order, and the events after `base_seq` when one was sent. */
@Serializable
public data class PackOpsResult(
    @SerialName("head_seq") val headSeq: Long,
    val results: List<PackOpResult>,
    val events: List<PackEvent>,
    @SerialName("has_more") val hasMore: Boolean,
)

/** A page of the log, oldest first. Send [cursor] back as `since` for the next. */
@Serializable
public data class PackEventPage(
    val events: List<PackEvent>,
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("head_seq") val headSeq: Long,
)

/** How far the caller has looked in the pack. It never goes back. */
@Serializable
public data class PackSeenDto(
    @SerialName("seen_seq") val seenSeq: Long,
    @SerialName("seen_at") val seenAt: String,
)

/** An item after copying it in or updating it from its original, with the event that did it ([event] is null for a copy asked for again). */
@Serializable
public data class PackItemReply(
    val item: PackItemDto,
    val event: PackEvent? = null,
    @SerialName("head_seq") val headSeq: Long,
)

/** A pack item saved to the caller's own library: a new record, which syncs like any other. */
@Serializable
public data class LibraryCopy(
    val kind: String,
    val id: Int,
    @SerialName("client_uuid") val clientUuid: String,
    val name: String,
    val revision: Int,
)

/** An invitation to a pack (role `editor` or `viewer`) or to a team (`member` or `admin`). [email] is null for a team's single-use link. */
@Serializable
public data class Invite(
    val id: Int,
    val email: String? = null,
    val role: String,
    /** `pending`, `accepted`, `declined`, `revoked` or `expired`. */
    val status: String,
    @SerialName("invited_by") val invitedBy: PackPerson? = null,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("created_at") val createdAt: String,
    val pack: InvitePack? = null,
    val team: InviteTeam? = null,
)

@Serializable
public data class InvitePack(val uuid: String, val name: String)

@Serializable
public data class InviteTeam(val id: Int, val name: String)

/** An invitation made or sent again. [emailSent] is there for an emailed one; [token] is a team link's, shown this once and never again. */
@Serializable
public data class InviteReply(
    val invite: Invite,
    @SerialName("email_sent") val emailSent: Boolean? = null,
    val token: String? = null,
)

/** What accepting an invitation joined: a pack or a team. */
@Serializable
public data class InviteAccepted(
    val invite: Invite,
    val pack: PackSummary? = null,
    val team: TeamSummary? = null,
)

/** A team the caller is in. [role] is theirs: `owner`, `admin` or `member`. */
@Serializable
public data class TeamSummary(
    val id: Int,
    val name: String,
    val role: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
public data class Team(
    val id: Int,
    val name: String,
    val role: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("created_at") val createdAt: String,
    val members: List<TeamMember>,
)

@Serializable
public data class TeamMember(
    @SerialName("user_id") val userId: Int,
    val name: String,
    val email: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String,
)

/** A teammate found by name or sign-in address. There is no open directory: nobody else is ever found. */
@Serializable
public data class UserBrief(val id: Int, val name: String, val email: String)

/** A deletion or removal that was done (`deleted`, `removed`). */
@Serializable
public data class StatusReply(val status: String)

/** What the live service asks before it opens a socket: the caller's place in the pack. An app has no need of it; the type keeps its recording honest. */
@Serializable
internal data class PackAccess(
    val role: String,
    val status: String,
    @SerialName("head_seq") val headSeq: Long,
    val user: PackPerson,
)

@Serializable
internal data class PacksBody(val packs: List<PackSummary>)

@Serializable
internal data class TeamsBody(val teams: List<TeamSummary>)

@Serializable
internal data class InvitesBody(val invites: List<Invite>)

@Serializable
internal data class UsersBody(val users: List<UserBrief>)

@Serializable
internal data class MemberBody(val member: PackMember)

@Serializable
internal data class InviteBody(val invite: Invite)

@Serializable
internal data class ItemBody(val item: PackItemDto, @SerialName("head_seq") val headSeq: Long)
