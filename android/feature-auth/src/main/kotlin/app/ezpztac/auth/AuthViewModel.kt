package app.ezpztac.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val route: AuthRoute = AuthRoute.SignIn,
    /** A request is in flight: the button shows progress and takes no second tap. */
    val busy: Boolean = false,
    /** What went wrong, in words for a person. Cleared by the next action and by [AuthViewModel.dismissError]. */
    val error: String? = null,
    /** What went wrong in a developer's words ([detail]); shown only by debug builds. Cleared with the error. */
    val detail: String? = null,
    val notice: String? = null,
    /** The address the person last gave, carried between screens (sign-up → check your inbox → resend). */
    val email: String = "",
    /** Seconds until another verification email may be asked for. */
    val resendCooldownSeconds: Int = 0,
    /** The step the route was for is done: the address is verified, or the password is changed. */
    val completed: Boolean = false,
    /** A password-reset link was asked for. */
    val resetRequested: Boolean = false,
)

/**
 * The sign-in screens' state and what each action does. The form's text lives in the screens (a password is not kept here); this is the
 * flow: which screen, whether a request is out, what it said. Signing in does not navigate: the session it creates is what the app's
 * shell reacts to.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(private val api: AuthApi) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var cooldown: Job? = null

    // -- Moving around ------------------------------------------------------------------------------

    fun open(route: AuthRoute) {
        _state.update {
            it.copy(
                route = route,
                error = null,
                detail = null,
                notice = null,
                completed = false,
                resetRequested = false,
                email = when (route) {
                    is AuthRoute.CheckEmail -> route.email
                    is AuthRoute.Resend -> route.email.ifBlank { it.email }
                    else -> it.email
                },
            )
        }
    }

    /** Back from any screen goes to the sign-in. */
    fun back(): Boolean {
        if (_state.value.route == AuthRoute.SignIn) return false
        open(AuthRoute.SignIn)
        return true
    }

    fun dismissError() = _state.update { it.copy(error = null, detail = null) }

    /** A message from outside for the sign-in screen: "Your session expired." */
    fun notify(text: String?) = _state.update { it.copy(notice = text) }

    // -- Actions ----------------------------------------------------------------------------------------

    fun signIn(email: String, password: String) {
        val problem = AuthValidation.emailError(email) ?: if (password.isEmpty()) "Enter your password." else null
        if (problem != null) return fail(problem)
        run("Unable to sign in. Check your credentials.", email = email.trim()) { api.login(email.trim(), password) }
    }

    fun signInWithGoogle(idToken: String) =
        run("Google sign-in could not be completed.") { api.signInWithGoogle(idToken) }

    /** Google's own sheet failed or was dismissed on the device. A dismissal is not an error. */
    fun googleFailed(message: String?) {
        if (message != null) fail(message)
    }

    fun register(name: String, email: String) {
        val problem = AuthValidation.nameError(name) ?: AuthValidation.emailError(email)
        if (problem != null) return fail(problem)
        run("We could not create the account.", email = email.trim()) {
            api.register(name.trim(), email.trim())
            _state.update { it.copy(route = AuthRoute.CheckEmail(email.trim())) }
            startCooldown(RESEND_SECONDS)
        }
    }

    fun verify(password: String, confirmation: String) {
        val route = _state.value.route as? AuthRoute.Verify ?: return
        val problem = AuthValidation.newPasswordError(password) ?: AuthValidation.confirmationError(password, confirmation)
        if (problem != null) return fail(problem)
        run("This verification link is invalid or has expired.") {
            api.verifyEmail(route.token, password)
            _state.update { it.copy(completed = true) }
        }
    }

    fun resend(email: String) {
        AuthValidation.emailError(email)?.let { return fail(it) }
        if (_state.value.resendCooldownSeconds > 0) return
        run("A new verification link could not be requested.", email = email.trim()) {
            api.resendVerification(email.trim())
            _state.update { it.copy(notice = "If that account is eligible, a new verification link is on its way.") }
            startCooldown(RESEND_SECONDS)
        }
    }

    fun forgot(email: String) {
        AuthValidation.emailError(email)?.let { return fail(it) }
        run("The reset request could not be completed.", email = email.trim()) {
            api.forgotPassword(email.trim())
            _state.update { it.copy(resetRequested = true) }
        }
    }

    fun reset(password: String, confirmation: String) {
        val route = _state.value.route as? AuthRoute.Reset ?: return
        val problem = AuthValidation.newPasswordError(password) ?: AuthValidation.confirmationError(password, confirmation)
        if (problem != null) return fail(problem)
        run("This reset link is invalid or has expired.") {
            api.resetPassword(route.token, password)
            _state.update { it.copy(completed = true) }
        }
    }

    // -- Plumbing -----------------------------------------------------------------------------------------

    private fun fail(message: String) = _state.update { it.copy(error = message, detail = null, notice = null) }

    private fun run(fallback: String, email: String? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return                                            // a second tap while one is out does nothing
        _state.update { it.copy(busy = true, error = null, detail = null, notice = null, email = email ?: it.email) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e, fallback), detail = detail(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun startCooldown(seconds: Int) {
        cooldown?.cancel()
        _state.update { it.copy(resendCooldownSeconds = seconds) }
        cooldown = viewModelScope.launch {
            while (_state.value.resendCooldownSeconds > 0) {
                delay(1000)
                _state.update { it.copy(resendCooldownSeconds = maxOf(0, it.resendCooldownSeconds - 1)) }
            }
        }
    }

    companion object {
        /** The server sends one verification link a minute per address; asking sooner would be answered with silence. */
        const val RESEND_SECONDS = 60
    }
}
