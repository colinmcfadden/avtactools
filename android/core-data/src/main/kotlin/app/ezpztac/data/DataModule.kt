package app.ezpztac.data

import android.content.Context
import app.ezpztac.missionpacks.LzPackKind
import app.ezpztac.missionpacks.PackApi
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackKeeper
import app.ezpztac.missionpacks.PackStore
import app.ezpztac.missionpacks.RoutePackKind
import app.ezpztac.model.Diagram
import app.ezpztac.model.RouteSet
import app.ezpztac.network.ApiClient
import app.ezpztac.network.AuthState
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The one database, and what is built on it: the sync store and the repository the screens edit records through, and the mission packs'
 * store and engine.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    /** The open Mission Pack, as the LZ/PZ and Routes panels see it: the editors' join to it. */
    @Provides
    fun openPack(workspace: PackWorkspace): OpenPack = workspace

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EzpzDatabase = EzpzDatabase.open(context)

    @Provides
    @Singleton
    fun roomStore(database: EzpzDatabase): RoomSyncStore = RoomSyncStore(database)

    @Provides
    @Singleton
    fun syncStore(store: RoomSyncStore): SyncStore = store

    @Provides
    @Singleton
    fun recordFeed(store: RoomSyncStore): RecordFeed = store

    @Provides
    @Singleton
    fun accountScope(database: EzpzDatabase): AccountScope = RoomAccountScope(database)

    @Provides
    @Singleton
    fun repository(store: SyncStore): SyncRepository = SyncRepository(store)

    /**
     * The open diagram outlives any screen, so its delayed save runs in a scope of its own that is never cancelled with one. A mission pack's
     * LZ/PZ opens in the same session ([lzItems]), so it is edited, undone and saved like one of the person's own.
     */
    @Provides
    @Singleton
    fun diagramSession(repository: DiagramRepository, lzItems: PackItemStore<Diagram>): DiagramSession =
        DiagramSession(repository, CoroutineScope(SupervisorJob() + Dispatchers.Default), packs = lzItems)

    /** The open set of routes outlives any screen too, for the same reason, and a pack's route set opens in it the same way ([routeItems]). */
    @Provides
    @Singleton
    fun routeSession(repository: RouteRepository, routeItems: PackItemStore<RouteSet>): RouteSession =
        RouteSession(repository, CoroutineScope(SupervisorJob() + Dispatchers.Default), packs = routeItems)

    /**
     * Where the diagram session reads and writes a mission pack's LZ/PZs: a save is sent to the pack as operations, never to the library. Its
     * baselines are kept on the main thread, where edits are made.
     */
    @Provides
    @Singleton
    fun lzItems(engine: PackEngine, store: PackStore): PackItemStore<Diagram> =
        PackItemStore(engine, LzPackKind(), store, { it.id }, Dispatchers.Main.immediate)

    /** The same for the route session and a mission pack's sets of routes. */
    @Provides
    @Singleton
    fun routeItems(engine: PackEngine, store: PackStore): PackItemStore<RouteSet> =
        PackItemStore(engine, RoutePackKind, store, { it.id }, Dispatchers.Main.immediate)

    /** A threat's mask is asked for by a press and answered later, and is held in memory with the threats: it is not tied to a screen. */
    @Provides
    @Singleton
    fun threatMasks(api: ThreatMaskApi, store: ThreatStore): ThreatMasks =
        ThreatMasks(api, store, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Every mission pack held on the device, with the edits to them not yet taken or kept (docs/MISSION_PACKS.md). */
    @Provides
    @Singleton
    fun packStore(database: EzpzDatabase): PackStore = RoomPackStore(database)

    /** What a pack would not take of a person's edits is kept in their library, as `NAME (my edits)`; a brief only for an account with mission briefs. */
    @Provides
    @Singleton
    fun packKeeper(store: SyncStore, api: ApiClient): PackKeeper =
        LibraryPackKeeper(store, keepsBriefs = { (api.state.value as? AuthState.SignedIn)?.user?.hasFeature("mission_brief") == true })

    /**
     * Mission packs outlive any screen (a pack closed with edits still going out sends them), so the engine runs in a scope of its own.
     * It asks for a sync when the background has work: edits a pack refused, kept in the library, or a drain that could not finish.
     */
    @Provides
    @Singleton
    fun packEngine(api: PackApi, store: PackStore, keeper: PackKeeper, scheduler: SyncScheduler): PackEngine =
        PackEngine(api, store, keeper, CoroutineScope(SupervisorJob() + Dispatchers.Default), requestBackgroundDrain = scheduler::requestSync)

    /**
     * One of the person's own Library records copied into a mission pack: what is open written, and what is waiting uploaded, first ([LibraryToPack]), the copy
     * asked for as the account it is for.
     */
    @Provides
    @Singleton
    fun libraryCopier(repository: SyncRepository, diagrams: DiagramSession, routes: RouteSession, upload: LibraryUpload, api: ApiClient): LibraryCopier =
        LibraryToPack(repository, SessionWrites(diagrams, routes), upload, copyCall(api))

    /**
     * The brief being set up (docs/MISSION_BRIEF.md §3) outlives the setup: a change still waiting is written once the person has been still, and a pack's edit is sent,
     * whatever closes. Kept in the Library only for an account that may keep records there (`cloud_save`).
     */
    @Provides
    @Singleton
    fun briefKeeper(repository: SyncRepository, scheduler: SyncScheduler, engine: PackEngine, last: LastBrief, api: ApiClient): BriefKeeper =
        BriefKeeper(repository, scheduler, engine, last, CoroutineScope(SupervisorJob() + Dispatchers.Default), canKeep = { signedInHas(api, "cloud_save") })

    /**
     * What a brief is built from: the open pack, or the LZ/PZs and route sets open in this session (each LZ/PZ with the slope measured over it), with the
     * threats (for an account with them) and the aircraft.
     */
    @Provides
    @Singleton
    fun briefSources(
        engine: PackEngine,
        openDiagrams: OpenDiagrams,
        openSets: OpenRouteSets,
        diagrams: DiagramSession,
        routes: RouteSession,
        feed: RecordFeed,
        routeRepository: RouteRepository,
        threats: ThreatStore,
        aircraft: AircraftProfiles,
        analysis: AnalysisService,
        api: ApiClient,
    ): BriefSources = BriefSources(
        engine.open, openDiagrams, openSets, diagrams, routes, feed, routeRepository::read,
        threats = threats.entries,
        threatsAllowed = api.state.map { signedInHas(it, "threats") }.distinctUntilChanged(),
        profiles = aircraft.profiles,
        missionAircraft = aircraft.active.map { it.slug }.distinctUntilChanged(),
        slopes = analysis.slopes,
        boundaryKey = analysis::boundaryKey,
    )

    /** What of the brief waits, written when the app goes to the back and before a sign-out stops packs. */
    @Provides
    fun briefFlush(keeper: BriefKeeper): BriefFlush = BriefFlush { keeper.flush() }

    /** The brief compiled, for an account with Mission briefs: compiled off the main thread, its weather fetched in a scope of its own. */
    @Provides
    @Singleton
    fun briefSession(keeper: BriefKeeper, sources: BriefSources, briefApi: BriefApi, api: ApiClient): BriefSession = BriefSession(
        keeper, sources.inputs, briefApi, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Default,
        account = api.state.map { state -> (state as? AuthState.SignedIn)?.user?.takeIf { it.hasFeature("mission_brief") }?.id }.distinctUntilChanged(),
    )

    private fun signedInHas(api: ApiClient, feature: String): Boolean = signedInHas(api.state.value, feature)

    private fun signedInHas(state: AuthState, feature: String): Boolean = (state as? AuthState.SignedIn)?.user?.hasFeature(feature) == true

    /** An analysis outlives the screen that asked for it (the person may leave while the server works), and applies its result on the main thread, where edits are made. */
    @Provides
    @Singleton
    fun analysisService(api: TerrainApi, session: DiagramSession): AnalysisService =
        AnalysisService(api, session, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Main.immediate)
}
