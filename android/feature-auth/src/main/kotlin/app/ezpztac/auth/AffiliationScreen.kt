package app.ezpztac.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.ScreenHeader
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens

/**
 * The `.mil` gate. [signedInAs] is the account's address, shown so the person knows which account is waiting; [pendingMilEmail] is an
 * address already sent a code. Signing out is offered because someone who cannot clear the gate (an admin can approve them instead)
 * must be able to leave.
 */
@Composable
fun AffiliationHost(
    signedInAs: String,
    pendingMilEmail: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AffiliationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMilEmail) { viewModel.startWith(pendingMilEmail) }
    AffiliationContent(
        state = state, signedInAs = signedInAs, onSendCode = viewModel::sendCode, onVerify = viewModel::verify, onResend = viewModel::resend,
        onDifferentAddress = viewModel::useDifferentAddress, onDismissError = viewModel::dismissError, onSignOut = onSignOut, modifier = modifier,
    )
}

@Composable
fun AffiliationContent(
    state: AffiliationUiState,
    signedInAs: String,
    onSendCode: (String) -> Unit,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onDifferentAddress: () -> Unit,
    onDismissError: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(Tokens.Spacing.xl.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
            ) {
                ScreenHeader("Verify .mil email", subtitle = "Signed in as $signedInAs.", eyebrow = "MILITARY AFFILIATION")
                Text(
                    "Access is limited to Army/DoD personnel. Verify control of a .mil email address to continue. If you can't receive mail " +
                        "at your .mil account, an administrator can approve you instead.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = onDismissError) }
                state.notice?.let { Banner(it, BannerKind.Info) }
                when (state.step) {
                    AffiliationStep.Email -> {
                        var email by rememberSaveable { mutableStateOf(state.email) }
                        EzpzTextField(
                            value = email, onValueChange = { email = it }, label = ".mil email address", keyboardType = KeyboardType.Email,
                            hint = "Any .mil address works (army.mil, mail.mil, us.army.mil, …).", imeAction = ImeAction.Done,
                            onImeAction = { onSendCode(email) }, contentType = ContentType.EmailAddress, enabled = !state.busy,
                        )
                        PrimaryButton("Send verification code", onClick = { onSendCode(email) }, busy = state.busy, busyText = "Sending…")
                    }
                    AffiliationStep.Code -> {
                        var code by rememberSaveable { mutableStateOf("") }
                        EzpzTextField(
                            value = code, onValueChange = { code = it.uppercase() }, label = "Verification code",
                            hint = "Sent to ${state.email}. Expires in 30 minutes.", capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done, onImeAction = { onVerify(code) }, contentType = ContentType.SmsOtpCode, enabled = !state.busy,
                        )
                        PrimaryButton("Verify affiliation", onClick = { onVerify(code) }, busy = state.busy, busyText = "Verifying…")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextAction("Resend code", onClick = onResend, enabled = !state.busy)
                            TextAction("Use a different address", onClick = onDifferentAddress)
                        }
                    }
                }
                TextAction("Sign out", onClick = onSignOut)
            }
        }
    }
}
