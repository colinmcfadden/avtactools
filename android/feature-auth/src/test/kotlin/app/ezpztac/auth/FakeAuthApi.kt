package app.ezpztac.auth

import kotlinx.coroutines.CompletableDeferred

/** An [AuthApi] that records what it was asked and fails on request, so the screens' logic is tried without a network. */
class FakeAuthApi : AuthApi {
    val calls = mutableListOf<String>()

    /** Every call fails with this, if set. */
    var failure: Exception? = null

    /** Calls wait on this until it is completed: a request that is out and has not been answered. */
    var gate: CompletableDeferred<Unit>? = null

    private suspend fun call(text: String) {
        calls += text
        gate?.await()
        failure?.let { throw it }
    }

    override suspend fun login(email: String, password: String) = call("login $email $password")
    override suspend fun signInWithGoogle(idToken: String) = call("google $idToken")
    override suspend fun register(name: String, email: String) = call("register $name $email")
    override suspend fun verifyEmail(token: String, password: String) = call("verify $token $password")
    override suspend fun resendVerification(email: String) = call("resend $email")
    override suspend fun forgotPassword(email: String) = call("forgot $email")
    override suspend fun resetPassword(token: String, password: String) = call("reset $token $password")
    override suspend fun requestMilCode(email: String) = call("milRequest $email")
    override suspend fun verifyMilCode(code: String) = call("milVerify $code")
}
