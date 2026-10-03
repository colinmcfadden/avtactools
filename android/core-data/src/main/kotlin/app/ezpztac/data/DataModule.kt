package app.ezpztac.data

import android.content.Context
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.SyncRepository
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

/** The one database, and what is built on it: the sync store and the repository the screens edit records through. */
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

    /** The open diagram outlives any screen, so its delayed save runs in a scope of its own that is never cancelled with one. */
    @Provides
    @Singleton
    fun diagramSession(repository: DiagramRepository): DiagramSession =
        DiagramSession(repository, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** The open set of routes outlives any screen too, for the same reason. */
    @Provides
    @Singleton
    fun routeSession(repository: RouteRepository): RouteSession =
        RouteSession(repository, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** An analysis outlives the screen that asked for it (the person may leave while the server works), and applies its result on the main thread, where edits are made. */
    @Provides
    @Singleton
    fun analysisService(api: TerrainApi, session: DiagramSession, repository: DiagramRepository): AnalysisService =
        AnalysisService(api, session, repository, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.Main.immediate)
}
