package app.ezpztac.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AffiliationStep { Email, Code }

data class AffiliationUiState(
    val step: AffiliationStep = AffiliationStep.Email,
    /** The `.mil` address a code was sent to. */
    val email: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * The `.mil` gate: any `.mil` address clears it, by a code emailed to it (AGENTS.md §2). Clearing it changes the stored user, which is
 * what the app's shell reacts to; nothing here navigates.
 */
@HiltViewModel
class AffiliationViewModel @Inject constructor(private val api: AuthApi) : ViewModel() {
    private val _state = MutableStateFlow(AffiliationUiState())
    val state: StateFlow<AffiliationUiState> = _state.asStateFlow()

    /** An address the account already has pending goes straight to the code step, as the web does. */
    fun startWith(pendingMilEmail: String?) {
        if (!pendingMilEmail.isNullOrBlank() && _state.value.step == AffiliationStep.Email && _state.value.email.isEmpty()) {
            _state.update { it.copy(step = AffiliationStep.Code, email = pendingMilEmail) }
        }
    }

    fun sendCode(email: String) {
        AuthValidation.milEmailError(email)?.let { return fail(it) }
        val address = email.trim()
        run("Couldn't send a code. Check the address and try again.") {
            api.requestMilCode(address)
            // Where it went, and for how long, is the code field's own hint; the notice only says it is on its way.
            _state.update { it.copy(step = AffiliationStep.Code, email = address, notice = "A code was sent. Check your .mil inbox.") }
        }
    }

    fun resend() {
        val address = _state.value.email
        if (address.isBlank()) return
        run("Couldn't resend the code. Try again shortly.") {
            api.requestMilCode(address)
            _state.update { it.copy(notice = "A new code was sent to $address.") }
        }
    }

    fun verify(code: String) {
        AuthValidation.codeError(code)?.let { return fail(it) }
        run("That code is invalid or has expired.") { api.verifyMilCode(code.trim().uppercase()) }
    }

    fun useDifferentAddress() = _state.update { it.copy(step = AffiliationStep.Email, error = null, notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun fail(message: String) = _state.update { it.copy(error = message, notice = null) }

    private fun run(fallback: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e, fallback)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
