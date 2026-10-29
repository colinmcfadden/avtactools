package app.ezpztac.android

import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.config
import app.ezpztac.network.refreshUser
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the app's shell needs from the server and the session. A seam, so the shell's logic is tried without a network. */
interface AuthBackend {
    val state: StateFlow<AuthState>

    /** Reads the stored session, once per process; after that it is the state as it is now. Nothing is asked of the server. */
    suspend fun restore(): AuthState

    /** Asks who this is now, and keeps the answer. Throws if there is no signal. */
    suspend fun refreshUser(): ApiUser

    /** The server's public config. Throws if there is no signal. */
    suspend fun config(): AppConfig

    suspend fun logout(): Boolean
}

@Singleton
class ApiClientBackend @Inject constructor(private val api: ApiClient) : AuthBackend {
    override val state: StateFlow<AuthState> get() = api.state
    override suspend fun restore() = api.restore()
    override suspend fun refreshUser() = api.refreshUser()
    override suspend fun config() = api.config()
    override suspend fun logout() = api.logout()
}
