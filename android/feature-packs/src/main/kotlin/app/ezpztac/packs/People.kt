package app.ezpztac.packs

import app.ezpztac.network.Invite
import app.ezpztac.network.PackMember
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.UserBrief

/**
 * The calls about people that the pack screens make (New pack; Members and Teams to come): the person's teams, their teammates found by name, a pack made and
 * shared, and people brought in. Every one is made for one account ([user]) and never sent under another's session.
 */
interface PeopleApi {
    suspend fun teams(user: Int): List<TeamSummary>

    /** People on one of [user]'s teams whose name or address has [query] in it (2 to 100 characters); nobody else is ever found. */
    suspend fun searchPeople(query: String, user: Int): List<UserBrief>

    /**
     * Makes a pack [user] owns under [uuid], the device's choice, so a try whose answer was lost and is made again is the same pack ([PackCreated.created]
     * false), never a second. Shared with [team] when given.
     */
    suspend fun createPack(name: String, description: String, team: TeamPick?, uuid: String, user: Int): PackCreated

    /** Changes what [change] names of pack [uuid], and nothing else (every field sent is in the pack's history). */
    suspend fun updatePack(uuid: String, change: PackChange, user: Int)

    /** Adds a teammate ([userId]) to pack [uuid] as [role]: they are in it at once, and told by email. */
    suspend fun addMember(uuid: String, userId: Int, role: String, user: Int)

    /** Invites [email] to pack [uuid] as [role]; false when the invitation was made but its email did not go. */
    suspend fun invite(uuid: String, email: String, role: String, user: Int): Boolean

    /** Pack [uuid]'s people as the server has them now: its members, the team it is shared with, and for its owner the invitations waiting. */
    suspend fun people(uuid: String, user: Int): PackPeople

    /** Makes member [userId] of pack [uuid] an `editor`, a `viewer`, or the `owner` (the pack is handed over: its owner becomes an editor). */
    suspend fun changeMember(uuid: String, userId: Int, role: String, user: Int)

    /** Takes member [userId] off pack [uuid]. */
    suspend fun removeMember(uuid: String, userId: Int, user: Int)

    /** Sends invitation [inviteId] to pack [uuid] again, with a new link; false when its email did not go. */
    suspend fun resendInvite(uuid: String, inviteId: Int, user: Int): Boolean

    /** Withdraws invitation [inviteId] to pack [uuid]: its link stops working. */
    suspend fun revokeInvite(uuid: String, inviteId: Int, user: Int)
}

/**
 * What the server said of a pack's people: its name, this person's role in it, its members, the invitations waiting (the owner's only; null when they could not be
 * had, which leaves the members shown), the team it is shared with.
 */
data class PackPeople(val pack: String, val role: String, val members: List<PackMember>, val pending: List<Invite>?, val shared: SharedTeam?) {
    val owner: Boolean get() = role == "owner"
}

/** The team a pack is shared with: who, and what they can do there (`editor` or `viewer`). */
data class SharedTeam(val id: Int, val name: String, val role: String)

/** A team a pack is shared with, and what its members can do in it (`editor` or `viewer`). */
data class TeamPick(val teamId: Int, val role: String)

/** A pack made: as the server has it now, and whether this try made it ([created]) or an earlier one whose answer was lost did. */
data class PackCreated(val uuid: String, val name: String, val description: String, val team: TeamPick?, val created: Boolean)

/** What to change of a pack: a new [name] or [description] (null: as it is), and when [setTeam], who shares it ([team], or null for nobody). */
data class PackChange(val name: String? = null, val description: String? = null, val setTeam: Boolean = false, val team: TeamPick? = null) {
    companion object {
        /** What of [made] differs from what was asked ([name], [description], [team]), or null when nothing does. */
        fun between(made: PackCreated, name: String, description: String, team: TeamPick?): PackChange? {
            val setTeam = team != made.team
            val change = PackChange(
                name = name.takeIf { it != made.name }, description = description.takeIf { it != made.description },
                setTeam = setTeam, team = team.takeIf { setTeam },
            )
            return change.takeIf { it.name != null || it.description != null || it.setTeam }
        }
    }
}
