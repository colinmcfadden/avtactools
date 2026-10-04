package app.ezpztac.auth

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.LabelledDivider
import app.ezpztac.designsystem.PasswordField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.ScreenHeader
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import kotlinx.coroutines.launch

/** Everything a screen can do, so a screen is a function of its state and these. */
class AuthActions(
    val signIn: (email: String, password: String) -> Unit = { _, _ -> },
    val register: (name: String, email: String) -> Unit = { _, _ -> },
    val verify: (password: String, confirmation: String) -> Unit = { _, _ -> },
    val resend: (email: String) -> Unit = {},
    val forgot: (email: String) -> Unit = {},
    val reset: (password: String, confirmation: String) -> Unit = { _, _ -> },
    val open: (AuthRoute) -> Unit = {},
    val google: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

/**
 * The sign-in screens: which one is showing follows the view model's route, and back goes to the sign-in. [googleEnabled] shows the
 * Google button, which only an app configured with a Google client ID can offer. [initialRoute] is a link from an email.
 */
@Composable
fun AuthHost(
    modifier: Modifier = Modifier,
    google: GoogleSignInProvider? = null,
    initialRoute: AuthRoute? = null,
    notice: String? = null,
    onRouteChanged: (AuthRoute) -> Unit = {},
    diagnostics: String? = null,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(initialRoute) { initialRoute?.let(viewModel::open) }
    LaunchedEffect(notice) { if (notice != null) viewModel.notify(notice) }
    LaunchedEffect(state.route) { onRouteChanged(state.route) }
    BackHandler(enabled = state.route != AuthRoute.SignIn) { viewModel.back() }
    AuthContent(
        state = state,
        googleEnabled = google != null,
        actions = AuthActions(
            signIn = viewModel::signIn, register = viewModel::register, verify = viewModel::verify, resend = viewModel::resend,
            forgot = viewModel::forgot, reset = viewModel::reset, open = viewModel::open, dismissError = viewModel::dismissError,
            google = {
                if (google != null) scope.launch {
                    when (val result = google.requestToken()) {
                        is GoogleResult.Token -> viewModel.signInWithGoogle(result.idToken)
                        GoogleResult.Cancelled -> viewModel.googleFailed(null)
                        is GoogleResult.Failed -> viewModel.googleFailed(result.message)
                    }
                }
            },
        ),
        modifier = modifier,
        diagnostics = diagnostics,
    )
}

@Composable
fun AuthContent(state: AuthUiState, googleEnabled: Boolean, actions: AuthActions, modifier: Modifier = Modifier, diagnostics: String? = null) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)                                   // a tablet gets a column, not a stretched form
                    .verticalScroll(rememberScrollState())
                    .padding(Tokens.Spacing.xl.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
            ) {
                when (val route = state.route) {
                    AuthRoute.SignIn -> SignIn(state, googleEnabled, actions)
                    AuthRoute.Register -> Register(state, googleEnabled, actions)
                    is AuthRoute.CheckEmail -> CheckEmail(state, route, actions)
                    is AuthRoute.Verify -> Verify(state, route, actions)
                    is AuthRoute.Resend -> Resend(state, route, actions)
                    AuthRoute.Forgot -> Forgot(state, actions)
                    is AuthRoute.Reset -> Reset(state, route, actions)
                }
                if (diagnostics != null) Diagnostics(diagnostics, state.detail)
            }
        }
    }
}

// -- Shared pieces -------------------------------------------------------------------------------------------------

/**
 * For debug builds, which pass [server] (a release build passes nothing, and this draws nothing): which server the build talks to, and what the last failure was. A sign-in that
 * "doesn't work" is most often a build pointed somewhere other than where the account is, and the server's sentence for that is the same as for a wrong password.
 */
@Composable
private fun Diagnostics(server: String, lastFailure: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Text("DEBUG BUILD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(server, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        lastFailure?.let {
            Text("Last failure: $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Feedback(state: AuthUiState, actions: AuthActions) {
    state.notice?.let { Banner(it, BannerKind.Info) }
    state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
}

@Composable
private fun GoogleOption(enabled: Boolean, actions: AuthActions, busy: Boolean) {
    if (!enabled) return
    SecondaryButton("Continue with Google", onClick = { if (!busy) actions.google() })
    LabelledDivider("or")
}

@Composable
private fun SwitchLine(question: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(question, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextAction(action, onClick)
    }
}

@Composable
private fun StatusMark(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Lead(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// -- Sign in -----------------------------------------------------------------------------------------------------

@Composable
private fun SignIn(state: AuthUiState, googleEnabled: Boolean, actions: AuthActions) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    val submit = { actions.signIn(email, password) }

    ScreenHeader("Sign in", subtitle = "Continue to the mission planning workspace.", eyebrow = "ACCOUNT ACCESS")
    Feedback(state, actions)
    GoogleOption(googleEnabled, actions, state.busy)
    EzpzTextField(
        value = email, onValueChange = { email = it }, label = "Email address", keyboardType = KeyboardType.Email,
        contentType = ContentType.EmailAddress, hint = null, enabled = !state.busy,
    )
    PasswordField(
        value = password, onValueChange = { password = it }, label = "Password", imeAction = ImeAction.Done, onImeAction = submit,
        enabled = !state.busy,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextAction("Resend verification email", onClick = { actions.open(AuthRoute.Resend(email.trim())) })
        TextAction("Forgot password?", onClick = { actions.open(AuthRoute.Forgot) })
    }
    PrimaryButton("Sign in", onClick = submit, busy = state.busy, busyText = "Signing in…")
    SwitchLine("Need an account?", "Register with email") { actions.open(AuthRoute.Register) }
}

// -- Register ------------------------------------------------------------------------------------------------------

@Composable
private fun Register(state: AuthUiState, googleEnabled: Boolean, actions: AuthActions) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf(state.email) }
    val submit = { actions.register(name, email) }

    ScreenHeader("Create account", subtitle = "Start with your email, then set a password after verification.", eyebrow = "NEW USER")
    Feedback(state, actions)
    GoogleOption(googleEnabled, actions, state.busy)
    EzpzTextField(
        value = name, onValueChange = { name = it }, label = "Full name", capitalization = KeyboardCapitalization.Words,
        contentType = ContentType.PersonFullName, enabled = !state.busy,
    )
    EzpzTextField(
        value = email, onValueChange = { email = it }, label = "Email address", keyboardType = KeyboardType.Email, imeAction = ImeAction.Done,
        onImeAction = submit, contentType = ContentType.EmailAddress, enabled = !state.busy,
    )
    Banner("Data-handling notice. Use this system only for authorized purposes. Do not upload or process classified information.", BannerKind.Info)
    PrimaryButton("Create account", onClick = submit, busy = state.busy, busyText = "Creating account…")
    SwitchLine("Already registered?", "Sign in") { actions.open(AuthRoute.SignIn) }
}

// -- Check your inbox, resend ------------------------------------------------------------------------------------------

@Composable
private fun CheckEmail(state: AuthUiState, route: AuthRoute.CheckEmail, actions: AuthActions) {
    StatusMark("✉")
    ScreenHeader("Check your inbox", eyebrow = "EMAIL VERIFICATION")
    Lead("We sent an account verification link to ${AuthValidation.maskEmail(route.email)}. Follow that link before signing in.")
    Banner("Verification links expire for your security. Check spam or junk mail if it does not arrive shortly.", BannerKind.Info)
    Feedback(state, actions)
    ResendForm(state, initialEmail = route.email, actions = actions)
    TextAction("Return to sign in", onClick = { actions.open(AuthRoute.SignIn) })
}

@Composable
private fun Resend(state: AuthUiState, route: AuthRoute.Resend, actions: AuthActions) {
    ScreenHeader("Resend activation link", subtitle = "Request a new time-limited verification email.", eyebrow = "EMAIL VERIFICATION")
    Lead("Enter the email used to register. For privacy, the response is the same whether or not an eligible account exists.")
    Feedback(state, actions)
    ResendForm(state, initialEmail = route.email, actions = actions)
    TextAction("Return to sign in", onClick = { actions.open(AuthRoute.SignIn) })
}

@Composable
private fun ResendForm(state: AuthUiState, initialEmail: String, actions: AuthActions) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    EzpzTextField(
        value = email, onValueChange = { email = it }, label = "Email address", keyboardType = KeyboardType.Email, imeAction = ImeAction.Done,
        onImeAction = { actions.resend(email) }, contentType = ContentType.EmailAddress, enabled = !state.busy,
    )
    val waiting = state.resendCooldownSeconds > 0
    SecondaryButton(
        if (waiting) "Resend available in ${state.resendCooldownSeconds}s" else "Resend verification email",
        onClick = { actions.resend(email) },
        enabled = !state.busy && !waiting,
    )
}

// -- Verify (the link from the email) ----------------------------------------------------------------------------------------

@Composable
private fun Verify(state: AuthUiState, route: AuthRoute.Verify, actions: AuthActions) {
    when {
        route.token.isBlank() -> {
            ScreenHeader("Activate account", eyebrow = "EMAIL VERIFICATION")
            Banner("This verification link is incomplete.", BannerKind.Error)
            PrimaryButton("Request a new link", onClick = { actions.open(AuthRoute.Resend("")) })
        }
        state.completed -> {
            StatusMark("✓")
            ScreenHeader("Email verified", eyebrow = "EMAIL VERIFICATION")
            Lead("Your account is active. Sign in to continue to the planner.")
            PrimaryButton("Continue to sign in", onClick = { actions.open(AuthRoute.SignIn) })
        }
        else -> {
            var password by remember { mutableStateOf("") }
            var confirmation by remember { mutableStateOf("") }
            val submit = { actions.verify(password, confirmation) }
            ScreenHeader("Activate account", subtitle = "Complete registration with a secure passphrase.", eyebrow = "EMAIL VERIFICATION")
            Feedback(state, actions)
            PasswordField(
                value = password, onValueChange = { password = it }, label = "Password", newPassword = true, imeAction = ImeAction.Next,
                hint = "Use 15 or more characters. A passphrase works well.", enabled = !state.busy,
            )
            PasswordField(
                value = confirmation, onValueChange = { confirmation = it }, label = "Confirm password", newPassword = true,
                imeAction = ImeAction.Done, onImeAction = submit, enabled = !state.busy,
            )
            PrimaryButton("Activate account", onClick = submit, busy = state.busy, busyText = "Activating…")
            if (state.error != null) TextAction("Request a new link", onClick = { actions.open(AuthRoute.Resend("")) })
        }
    }
}

// -- Forgot and reset -------------------------------------------------------------------------------------------------------------

@Composable
private fun Forgot(state: AuthUiState, actions: AuthActions) {
    if (state.resetRequested) {
        StatusMark("✉")
        ScreenHeader("Check your inbox", eyebrow = "ACCOUNT RECOVERY")
        Lead("If an account matches ${AuthValidation.maskEmail(state.email)}, a password-reset link has been sent.")
        PrimaryButton("Return to sign in", onClick = { actions.open(AuthRoute.SignIn) })
        return
    }
    var email by rememberSaveable { mutableStateOf(state.email) }
    val submit = { actions.forgot(email) }
    ScreenHeader("Reset access", subtitle = "Recover access using your verified email address.", eyebrow = "ACCOUNT RECOVERY")
    Lead("Enter your account email and we will send a time-limited reset link.")
    Feedback(state, actions)
    EzpzTextField(
        value = email, onValueChange = { email = it }, label = "Email address", keyboardType = KeyboardType.Email, imeAction = ImeAction.Done,
        onImeAction = submit, contentType = ContentType.EmailAddress, enabled = !state.busy,
    )
    PrimaryButton("Send reset link", onClick = submit, busy = state.busy, busyText = "Sending reset link…")
    TextAction("Return to sign in", onClick = { actions.open(AuthRoute.SignIn) })
}

@Composable
private fun Reset(state: AuthUiState, route: AuthRoute.Reset, actions: AuthActions) {
    when {
        route.token.isBlank() -> {
            ScreenHeader("Set new password", eyebrow = "ACCOUNT RECOVERY")
            Banner("This password-reset link is incomplete.", BannerKind.Error)
            PrimaryButton("Request a new link", onClick = { actions.open(AuthRoute.Forgot) })
        }
        state.completed -> {
            StatusMark("✓")
            ScreenHeader("Password updated", eyebrow = "ACCOUNT RECOVERY")
            Lead("Your new password is active. Sign in to continue to the planner.")
            PrimaryButton("Continue to sign in", onClick = { actions.open(AuthRoute.SignIn) })
        }
        else -> {
            var password by remember { mutableStateOf("") }
            var confirmation by remember { mutableStateOf("") }
            val submit = { actions.reset(password, confirmation) }
            ScreenHeader("Set new password", subtitle = "Secure your account with a new passphrase.", eyebrow = "ACCOUNT RECOVERY")
            Feedback(state, actions)
            PasswordField(
                value = password, onValueChange = { password = it }, label = "New password", newPassword = true, imeAction = ImeAction.Next,
                hint = "Use 15 or more characters. A passphrase works well.", enabled = !state.busy,
            )
            PasswordField(
                value = confirmation, onValueChange = { confirmation = it }, label = "Confirm new password", newPassword = true,
                imeAction = ImeAction.Done, onImeAction = submit, enabled = !state.busy,
            )
            PrimaryButton("Update password", onClick = submit, busy = state.busy, busyText = "Updating password…")
            if (state.error != null) TextAction("Request a new link", onClick = { actions.open(AuthRoute.Forgot) })
        }
    }
}
