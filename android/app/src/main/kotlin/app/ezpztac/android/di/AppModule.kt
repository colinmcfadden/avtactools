package app.ezpztac.android.di

import android.content.Context
import android.os.Build
import app.ezpztac.android.ApiClientBackend
import app.ezpztac.android.AuthBackend
import app.ezpztac.android.BuildConfig
import app.ezpztac.android.MapPreferences
import app.ezpztac.android.MapTokenSink
import app.ezpztac.android.packs.ApiPackInvites
import app.ezpztac.android.packs.EnginePackRuntime
import app.ezpztac.android.packs.PackInvites
import app.ezpztac.android.packs.PackRuntime
import app.ezpztac.android.sync.EngineSyncRunner
import app.ezpztac.android.sync.SyncRunner
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.android.sync.WorkManagerSyncScheduler
import app.ezpztac.data.session.EncryptedSessionStore
import app.ezpztac.data.session.KeystoreSecretBox
import app.ezpztac.map.CameraMemory
import app.ezpztac.map.LocationSource
import app.ezpztac.map.MapTokenSource
import app.ezpztac.map.PlatformLocationSource
import app.ezpztac.android.export.AssetMissionTemplate
import app.ezpztac.android.export.AssetThsTemplate
import app.ezpztac.android.export.ExportCleaner
import app.ezpztac.android.export.ShareExport
import app.ezpztac.data.ApiClientPlanningApi
import app.ezpztac.data.ApiClientThreatMaskApi
import app.ezpztac.data.ApiClientWeatherApi
import app.ezpztac.data.WeatherApi
import app.ezpztac.data.WeatherCache
import app.ezpztac.data.WeatherService
import app.ezpztac.data.EncryptedThreatVault
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatVault
import app.ezpztac.android.WeatherFileCache
import app.ezpztac.data.MissionTemplate
import app.ezpztac.data.ThsTemplate
import app.ezpztac.data.ApiClientTerrainApi
import app.ezpztac.data.PlanningApi
import app.ezpztac.data.ThreatMaskApi
import app.ezpztac.data.TerrainApi
import app.ezpztac.missionpacks.ApiPackApi
import app.ezpztac.missionpacks.PackApi
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.SessionStore
import app.ezpztac.sync.ApiSyncApi
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.SyncApi
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStore
import dagger.Binds
import dagger.Module
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.ApiClientMasterProfileSource
import app.ezpztac.data.FileMasterProfileStore
import app.ezpztac.android.AircraftChoicePreferences
import app.ezpztac.android.LastDiagram
import app.ezpztac.android.LastDiagramPreferences
import app.ezpztac.android.LastRouteSet
import app.ezpztac.android.LastRouteSetPreferences
import app.ezpztac.android.PointSetViewPreferences
import app.ezpztac.data.PointSetViewStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.symbols.DefaultSymbolRenderer
import app.ezpztac.symbols.JavaScriptSymbolSource
import app.ezpztac.symbols.PresetSymbols
import app.ezpztac.symbols.SymbolRenderer
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Named("appVersion")
    fun appVersion(): String = BuildConfig.VERSION_NAME

    @Provides
    @Singleton
    fun clientInfo(): ClientInfo = ClientInfo.android(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    /**
     * The session, sealed by a Keystore key and kept where backups do not reach. Losing it signs the user out, and nothing else.
     */
    @Provides
    @Singleton
    fun sessionStore(@ApplicationContext context: Context): SessionStore =
        EncryptedSessionStore(File(context.noBackupFilesDir, "session.bin"), KeystoreSecretBox())

    /**
     * Plain OkHttp, with no logging interceptor: a body here can hold a password or a token, and nothing is ever logged. Heavy calls
     * ask for a longer read timeout on their own; the platform's certificate checks are the whole of the transport security (no pinning).
     */
    @Provides
    @Singleton
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun apiClient(http: OkHttpClient, info: ClientInfo, sessions: SessionStore): ApiClient =
        ApiClient(BuildConfig.API_URL, http, info, sessions)

    @Provides
    @Singleton
    fun locationSource(@ApplicationContext context: Context): LocationSource = PlatformLocationSource(context)

    @Provides
    @Singleton
    fun syncApi(client: ApiClient): SyncApi = ApiSyncApi(client)

    /** What the mission-pack engine asks the server: a pack, its log, and a batch of edits. */
    @Provides
    @Singleton
    fun packApi(client: ApiClient): PackApi = ApiPackApi(client)

    @Provides
    @Singleton
    fun terrainApi(client: ApiClient): TerrainApi = ApiClientTerrainApi(client)

    @Provides
    @Singleton
    fun planningApi(client: ApiClient): PlanningApi = ApiClientPlanningApi(client)

    @Provides
    @Singleton
    fun threatMaskApi(client: ApiClient): ThreatMaskApi = ApiClientThreatMaskApi(client)

    @Provides
    @Singleton
    fun weatherApi(client: ApiClient): WeatherApi = ApiClientWeatherApi(client)

    /** The weather outlives any screen (a fetch finishes with the sheet closed), so it runs in a scope of its own that is never cancelled with one. */
    @Provides
    @Singleton
    fun weatherService(api: WeatherApi, cache: WeatherCache): WeatherService =
        WeatherService(api, cache, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))

    /**
     * The threat picture, sealed by its own Keystore key and kept where backups do not reach. Threats are sensitive and never persisted in the
     * usual sense (`AGENTS.md` §2): this is the one short-lived file the owner approved, wiped at sign-out and 48 hours after the last change.
     */
    @Provides
    @Singleton
    fun threatVault(@ApplicationContext context: Context): ThreatVault =
        EncryptedThreatVault(File(context.noBackupFilesDir, "threats.bin"), KeystoreSecretBox(alias = "ezpz.threats", purpose = "ezpz-threats-v1"))

    /** Threats outlive any screen (a write finishes with the sheet closed), so they run in a scope of their own that is never cancelled with one. */
    @Provides
    @Singleton
    fun threatStore(vault: ThreatVault): ThreatStore =
        ThreatStore(vault, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))

    @Provides
    @Singleton
    fun missionTemplate(@ApplicationContext context: Context): MissionTemplate = AssetMissionTemplate(context)

    /** Sign-out clears what was exported: a shared `.ths` holds a crew's threats in the clear. */
    @Provides
    @Singleton
    fun exportCleaner(@ApplicationContext context: Context): ExportCleaner = ExportCleaner { ShareExport.clear(context) }

    @Provides
    @Singleton
    fun thsTemplate(@ApplicationContext context: Context): ThsTemplate = AssetThsTemplate(context)

    /** Symbols: the pre-rendered presets first, then milsymbol in the system JavaScript sandbox, which starts when a symbol that needs it is first asked for. */
    @Provides
    @Singleton
    fun symbolRenderer(@ApplicationContext context: Context): SymbolRenderer =
        DefaultSymbolRenderer(listOf(PresetSymbols(context.assets), JavaScriptSymbolSource(context)))

    /** The admin's airframes (refreshed from the server, kept in a file so they are there offline), the user's own (synced), and the one chosen. */
    @Provides
    @Singleton
    fun aircraftProfiles(
        @ApplicationContext context: Context, feed: RecordFeed, client: ApiClient, choice: AircraftChoicePreferences, sync: SyncRepository, scheduler: SyncScheduler,
    ): AircraftProfiles =
        AircraftProfiles(
            feed, FileMasterProfileStore(File(context.noBackupFilesDir, "aircraft-master.json")), ApiClientMasterProfileSource(client), choice,
            CoroutineScope(SupervisorJob() + Dispatchers.Default), sync, scheduler,
        )

    @Provides
    @Singleton
    fun syncEngine(api: SyncApi, store: SyncStore): SyncEngine =
        SyncEngine(api, store, deviceLabel = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "this device" })
}

@Module
@InstallIn(SingletonComponent::class)
interface AppBindings {
    @Binds
    fun authBackend(impl: ApiClientBackend): AuthBackend

    @Binds
    fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    fun lastDiagram(impl: LastDiagramPreferences): LastDiagram

    @Binds
    fun lastRouteSet(impl: LastRouteSetPreferences): LastRouteSet

    @Binds
    fun pointSetViews(impl: PointSetViewPreferences): PointSetViewStore

    @Binds
    fun weatherCache(impl: WeatherFileCache): WeatherCache

    @Binds
    fun syncRunner(impl: EngineSyncRunner): SyncRunner

    @Binds
    fun packRuntime(impl: EnginePackRuntime): PackRuntime

    @Binds
    fun packInvites(impl: ApiPackInvites): PackInvites

    @Binds
    fun conflictResolver(impl: SyncEngine): ConflictResolver

    @Binds
    fun mapTokens(impl: MapPreferences): MapTokenSource

    @Binds
    fun mapTokenSink(impl: MapPreferences): MapTokenSink

    @Binds
    fun cameraMemory(impl: MapPreferences): CameraMemory
}
