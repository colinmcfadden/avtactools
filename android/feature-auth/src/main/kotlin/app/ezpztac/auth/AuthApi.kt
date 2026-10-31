package app.ezpztac.auth

import app.ezpztac.network.ApiClient
import app.ezpztac.network.forgotPassword
import app.ezpztac.network.register
import app.ezpztac.network.requestMilCode
import app.ezpztac.network.resendVerification
import app.ezpztac.network.resetPassword
import app.ezpztac.network.verifyEmail
import app.ezpztac.network.verifyMilCode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the sign-in screens ask of the server. Each call either works or throws a typed [app.ezpztac.network.ApiException]; the screens
 * use fixed words for success (the server's answer to sign-up, resend and reset is deliberately the same for every address) and
 * [describe] for failure. A seam, so the screens' logic is tried without a network.
 */
interface AuthApi {
    suspend fun login(email: String, password: String)
    suspend fun signInWithGoogle(idToken: String)
    suspend fun register(name: String, email: String)
    suspend fun verifyEmail(token: String, password: String)
    suspend fun resendVerification(email: String)
    suspend fun forgotPassword(email: String)
    suspend fun resetPassword(token: String, password: String)
    suspend fun requestMilCode(email: String)
    suspend fun verifyMilCode(code: String)
}

@Singleton
class ApiClientAuthApi @Inject constructor(private val client: ApiClient) : AuthApi {
    override suspend fun login(email: String, password: String) { client.login(email, password) }
    override suspend fun signInWithGoogle(idToken: String) { client.signInWithGoogle(idToken) }
    override suspend fun register(name: String, email: String) { client.register(name, email) }
    override suspend fun verifyEmail(token: String, password: String) { client.verifyEmail(token, password) }
    override suspend fun resendVerification(email: String) { client.resendVerification(email) }
    override suspend fun forgotPassword(email: String) { client.forgotPassword(email) }
    override suspend fun resetPassword(token: String, password: String) { client.resetPassword(token, password) }
    override suspend fun requestMilCode(email: String) { client.requestMilCode(email) }
    override suspend fun verifyMilCode(code: String) { client.verifyMilCode(code) }
}

@Module
@InstallIn(SingletonComponent::class)
interface AuthBindings {
    @Binds
    fun authApi(impl: ApiClientAuthApi): AuthApi
}
