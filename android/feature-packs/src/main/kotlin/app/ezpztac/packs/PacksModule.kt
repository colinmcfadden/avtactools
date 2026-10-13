package app.ezpztac.packs

import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.PackWorkspace
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.RouteRepository
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.missionpacks.PackState
import app.ezpztac.network.ApiClient
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.PackEventPage
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.acceptInvite
import app.ezpztac.network.declineInvite
import app.ezpztac.network.duplicatePack
import app.ezpztac.network.finishPack
import app.ezpztac.network.listPacks
import app.ezpztac.network.listTeams
import app.ezpztac.network.markPackSeen
import app.ezpztac.network.myInvites
import app.ezpztac.network.packEvents
import app.ezpztac.network.reopenPack
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
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
}

/** The Pack panel's calls: the pack's history and seen marker over the API client, its items through the editors' join to the pack. */
class EnginePackPanelDoor @Inject constructor(private val api: ApiClient, private val workspace: PackWorkspace) : PackPanelDoor {
    override val openHere: Flow<Set<String>> = workspace.openItems

    override val notices: Flow<PackNotice> = workspace.notices

    override suspend fun events(pack: String, since: Long, limit: Int): PackEventPage = api.packEvents(pack, since, limit)

    override suspend fun markSeen(pack: String, seq: Long): Long = api.markPackSeen(pack, seq).seenSeq

    override suspend fun openItem(item: String): Boolean = workspace.openItem(item)

    override suspend fun closeItem(item: String) = workspace.closeItem(item)

    override suspend fun renameItem(item: String, name: String): String? = workspace.renameItem(item, name)

    override suspend fun removeItem(item: String): String? = workspace.removeItem(item)
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
}
