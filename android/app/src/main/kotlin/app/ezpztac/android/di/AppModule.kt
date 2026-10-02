package app.ezpztac.android.di

import android.content.Context
import android.os.Build
import app.ezpztac.android.ApiClientBackend
import app.ezpztac.android.AuthBackend
import app.ezpztac.android.BuildConfig
import app.ezpztac.android.sync.EngineSyncRunner
import app.ezpztac.android.sync.SyncRunner
import app.ezpztac.android.sync.SyncScheduler
import app.ezpztac.android.sync.WorkManagerSyncScheduler
import app.ezpztac.data.session.EncryptedSessionStore
import app.ezpztac.data.session.KeystoreSecretBox
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ClientInfo
import app.ezpztac.network.SessionStore
import app.ezpztac.sync.ApiSyncApi
import app.ezpztac.sync.SyncApi
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun syncApi(client: ApiClient): SyncApi = ApiSyncApi(client)

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
    fun syncRunner(impl: EngineSyncRunner): SyncRunner
}
