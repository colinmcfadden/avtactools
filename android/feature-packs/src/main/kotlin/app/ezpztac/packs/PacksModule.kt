package app.ezpztac.packs

import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.PackWorkspace
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.RouteRepository
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackState
import app.ezpztac.network.ApiClient
import app.ezpztac.network.OtherAccountException
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.PackEventPage
import app.ezpztac.network.PackSummary
import app.ezpztac.network.Team
import app.ezpztac.network.TeamShare
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.UserBrief
import app.ezpztac.network.acceptInvite
import app.ezpztac.network.addPackMember
import app.ezpztac.network.changeTeamMember
import app.ezpztac.network.createPack
import app.ezpztac.network.createTeam
import app.ezpztac.network.deleteTeam
import app.ezpztac.network.declineInvite
import app.ezpztac.network.duplicatePack
import app.ezpztac.network.finishPack
import app.ezpztac.network.inviteToPack
import app.ezpztac.network.inviteToTeam
import app.ezpztac.network.listPacks
import app.ezpztac.network.listTeams
import app.ezpztac.network.pack
import app.ezpztac.network.packInvites
import app.ezpztac.network.changePackMember
import app.ezpztac.network.removePackMember
import app.ezpztac.network.removeTeamMember
import app.ezpztac.network.renameTeam
import app.ezpztac.network.resendPackInvite
import app.ezpztac.network.revokePackInvite
import app.ezpztac.network.revokeTeamInvite
import app.ezpztac.network.markPackSeen
import app.ezpztac.network.myInvites
import app.ezpztac.network.packEvents
import app.ezpztac.network.reopenPack
import app.ezpztac.network.searchPeople
import app.ezpztac.network.team
import app.ezpztac.network.teamInvites
import app.ezpztac.network.updatePack
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.KeptVersion
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.network.PackItemSource
import app.ezpztac.network.packItem
import app.ezpztac.network.updateFromOriginal
import app.ezpztac.sync.SyncScheduler
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The pack calls over the API client. */
class ApiPacksApi @Inject constructor(private val api: ApiClient) : PacksApi {
    override suspend fun packs(): List<PackSummary> = api.listPacks()

    override suspend fun invites(): List<Invite> = api.myInvites()

    override suspend fun teams(): List<TeamSummary> = api.listTeams()

    override suspend fun accept(inviteId: Int): InviteAccepted = api.acceptInvite(inviteId)

    override suspend fun decline(inviteId: Int) {
        api.declineInvite(inviteId)
    }

    override suspend fun finish(uuid: String, user: Int) {
        api.finishPack(uuid, asUser = user)
    }

    override suspend fun reopen(uuid: String, user: Int) {
        api.reopenPack(uuid, asUser = user)
    }

    override suspend fun duplicate(uuid: String, name: String, user: Int): PackMade = api.duplicatePack(uuid, name, asUser = user).let { PackMade(it.uuid, it.name) }
}

/** The calls about people over the API client, each for the account asked for. */
class ApiPeopleApi @Inject constructor(private val api: ApiClient) : PeopleApi {
    override suspend fun teams(user: Int): List<TeamSummary> = api.listTeams(asUser = user)

    override suspend fun searchPeople(query: String, user: Int): List<UserBrief> = api.searchPeople(query, asUser = user)

    override suspend fun createPack(name: String, description: String, team: TeamPick?, uuid: String, user: Int): PackCreated {
        val share = team?.let { TeamShare.With(it.teamId, it.role) } ?: TeamShare.Unchanged
        val saved = api.createPack(name, description, share, uuid, asUser = user)
        val pack = saved.value
        return PackCreated(pack.uuid, pack.name, pack.description, pack.team?.let { TeamPick(it.id, it.role ?: "editor") }, saved.created)
    }

    override suspend fun updatePack(uuid: String, change: PackChange, user: Int) {
        val share = when {
            !change.setTeam -> TeamShare.Unchanged
            change.team == null -> TeamShare.None                                                  // sent as no team: leaving it out keeps the one there
            else -> TeamShare.With(change.team.teamId, change.team.role)
        }
        api.updatePack(uuid, change.name, change.description, share, asUser = user)
    }

    override suspend fun addMember(uuid: String, userId: Int, role: String, user: Int) {
        api.addPackMember(uuid, userId, role, asUser = user)
    }

    override suspend fun invite(uuid: String, email: String, role: String, user: Int): Boolean = api.inviteToPack(uuid, email, role, asUser = user).value.emailSent != false

    override suspend fun people(uuid: String, user: Int): PackPeople {
        val pack = api.pack(uuid, asUser = user)
        val owner = pack.role == "owner"
        // Only the owner may see the invitations; theirs are those not yet answered. Ones that could not be had leave the members shown (not another account's: that
        // is no answer at all).
        val pending = if (!owner) {
            emptyList()
        } else {
            try {
                api.packInvites(uuid, asUser = user).filter { it.status == "pending" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OtherAccountException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        return PackPeople(pack.name, pack.role, pack.members, pending, pack.team?.let { SharedTeam(it.id, it.name, it.role ?: "editor") })
    }

    override suspend fun changeMember(uuid: String, userId: Int, role: String, user: Int) {
        api.changePackMember(uuid, userId, role, asUser = user)
    }

    override suspend fun removeMember(uuid: String, userId: Int, user: Int) {
        api.removePackMember(uuid, userId, asUser = user)
    }

    override suspend fun resendInvite(uuid: String, inviteId: Int, user: Int): Boolean = api.resendPackInvite(uuid, inviteId, asUser = user).emailSent != false

    override suspend fun revokeInvite(uuid: String, inviteId: Int, user: Int) {
        api.revokePackInvite(uuid, inviteId, asUser = user)
    }
}

/**
 * The calls updating a copy and saving a pack's item make: the first two over the API client, for the account asked for; a copy to the Library on the device
 * (the engine's keeper, which keeps a pack's refused edits the same way), then a sync asked for so it goes up.
 */
class ApiPackItemCalls @Inject constructor(
    private val api: ApiClient,
    private val keeper: PackKeeper,
    private val scheduler: SyncScheduler,
) : PackItemCalls {
    override suspend fun source(pack: String, item: String, user: Int): PackItemSource? = api.packItem(pack, item, asUser = user).source

    override suspend fun updateFromOriginal(pack: String, item: String, user: Int) {
        api.updateFromOriginal(pack, item, asUser = user)
    }

    override suspend fun saveToLibrary(pack: String, kind: String, name: String, data: JsonElement): KeptOutcome {
        // A key of its own each time: every copy saved is a record of its own (the keeper names a record by its key). As it is, as the server's own
        // save to the library keeps it: a route set's own fields and version too.
        val kept = keeper.keep(pack, listOf(KeptVersion("copy:$pack:${UUID.randomUUID()}", kind, name, data, asIs = true))).single()
        if (kept is KeptOutcome.Saved) scheduler.requestSync()
        return kept
    }
}

/** Teams' calls over the API client, each for the account asked for. */
class ApiTeamsApi @Inject constructor(private val api: ApiClient) : TeamsApi {
    override suspend fun teams(user: Int): List<TeamSummary> = api.listTeams(asUser = user)

    override suspend fun team(id: Int, user: Int): TeamPage {
        val team = api.team(id, asUser = user)
        // Only its owner or an admin may see its invitations; theirs are those not yet answered. Ones that could not be had leave the members shown.
        val invites = if (team.role != "owner" && team.role != "admin") {
            emptyList()
        } else {
            try {
                api.teamInvites(id, asUser = user).filter { it.status == "pending" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OtherAccountException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        return TeamPage(team, invites)
    }

    override suspend fun createTeam(name: String, user: Int): Team = api.createTeam(name, asUser = user)

    override suspend fun renameTeam(id: Int, name: String, user: Int) {
        api.renameTeam(id, name, asUser = user)
    }

    override suspend fun deleteTeam(id: Int, user: Int) {
        api.deleteTeam(id, asUser = user)
    }

    override suspend fun changeMember(id: Int, userId: Int, role: String, user: Int) {
        api.changeTeamMember(id, userId, role, asUser = user)
    }

    override suspend fun removeMember(id: Int, userId: Int, user: Int) {
        api.removeTeamMember(id, userId, asUser = user)
    }

    override suspend fun invite(id: Int, email: String, role: String, user: Int): Boolean =
        api.inviteToTeam(id, email, role, asUser = user).emailSent != false

    override suspend fun revokeInvite(id: Int, inviteId: Int, user: Int) {
        api.revokeTeamInvite(id, inviteId, asUser = user)
    }
}

/** The packs the device runs: the engine's open pack, opened and closed through the editors' join to it. */
@Singleton
class EnginePackDoor @Inject constructor(private val engine: PackEngine, private val workspace: PackWorkspace) : PackDoor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val user: StateFlow<Int?> = engine.me.map { it?.id }.stateIn(scope, SharingStarted.Eagerly, engine.me.value?.id)

    override val open: StateFlow<PackState?> = engine.open

    override suspend fun openPack(uuid: String) = workspace.openPack(uuid)

    override suspend fun closePack() = workspace.closePack()

    override fun refresh() {
        engine.wake()                                                                            // a client waiting to load again tries now
        engine.refresh()
    }

    override suspend fun flushOpen() = workspace.flushOpen()

    override fun point(lat: Double?, lon: Double?) = engine.setPointer(lat, lon)

    override suspend fun seenData(item: String): JsonElement? = workspace.seenData(item)
}

/** The Pack panel's calls: the pack's history and seen marker over the API client, its items through the editors' join to the pack. */
class EnginePackPanelDoor @Inject constructor(private val api: ApiClient, private val workspace: PackWorkspace, private val engine: PackEngine) : PackPanelDoor {
    override val openHere: Flow<Set<String>> = workspace.openItems

    override val notices: Flow<PackNotice> = workspace.notices

    override suspend fun events(pack: String, since: Long, limit: Int): PackEventPage = api.packEvents(pack, since, limit)

    override suspend fun markSeen(pack: String, seq: Long): Long = api.markPackSeen(pack, seq).seenSeq

    override suspend fun openItem(item: String): Boolean = workspace.openItem(item)

    override suspend fun closeItem(item: String) = workspace.closeItem(item)

    override suspend fun renameItem(item: String, name: String): String? = workspace.renameItem(item, name)

    override suspend fun removeItem(item: String): String? = workspace.removeItem(item)

    override suspend fun keepDropped(pack: String): List<PackNotice>? = engine.keepDropped(pack)

    override suspend fun discardDropped(pack: String, ids: Set<String>): Int = engine.discardDropped(pack, ids)
}

/** The Library's numbers, from its repositories: every LZ/PZ, set of routes (a mission included) and set of points saved. */
class RepositoryLibraryTally @Inject constructor(diagrams: DiagramRepository, routes: RouteRepository, points: PointSetRepository) : LibraryTally {
    override val counts: Flow<LibraryCounts> =
        combine(diagrams.observe(), routes.observe(), points.observe()) { lz, sets, pointSets -> LibraryCounts(lz.size, sets.size, pointSets.size) }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PacksModule {
    @Binds
    abstract fun packsApi(impl: ApiPacksApi): PacksApi

    @Binds
    abstract fun packDoor(impl: EnginePackDoor): PackDoor

    @Binds
    abstract fun libraryTally(impl: RepositoryLibraryTally): LibraryTally

    @Binds
    abstract fun packPanelDoor(impl: EnginePackPanelDoor): PackPanelDoor

    @Binds
    abstract fun peopleApi(impl: ApiPeopleApi): PeopleApi

    @Binds
    abstract fun teamsApi(impl: ApiTeamsApi): TeamsApi

    @Binds
    abstract fun packItemCalls(impl: ApiPackItemCalls): PackItemCalls
}
