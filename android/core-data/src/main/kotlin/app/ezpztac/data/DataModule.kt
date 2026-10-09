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

/**
 * The one database, and what is built on it: the sync store and the repository the screens edit records through, and the mission packs'
 * store and engine.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
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

    /** What a pack would not take of a person's edits is kept in their library, as `NAME (my edits)`. */
    @Provides
    @Singleton
    fun packKeeper(store: SyncStore): PackKeeper = LibraryPackKeeper(store)

    /**
     * Mission packs outlive any screen (a pack closed with edits still going out sends them), so the engine runs in a scope of its own.
     * It asks for a sync when the background has work: edits a pack refused, kept in the library, or a drain that could not finish.
     */
    @Provides
    @Singleton
    fun packEngine(api: PackApi, store: PackStore, keeper: PackKeeper, scheduler: SyncScheduler): PackEngine =
        PackEngine(api, store, keeper, CoroutineScope(SupervisorJob() + Dispatchers.Default), requestBackgroundDrain = scheduler::requestSync)

    /** An analysis outlives the screen that asked for it (the person may leave while the server works), and applies its result on the main thread, where edits are made. */
    @Provides
    @Singleton
    fun analysisService(api: TerrainApi, session: DiagramSession): AnalysisService =
        AnalysisService(api, session, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Main.immediate)
}
