package app.ezpztac.android

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.auth.AffiliationHost
import app.ezpztac.auth.AuthHost
import app.ezpztac.auth.AuthRoute
import app.ezpztac.auth.GoogleSignInProvider
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.ScreenHeader
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens

/** The app: whichever screen the [Gate] says the person is at. A link from an email outranks everything but a needed update. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val link by viewModel.pendingLink.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Nothing runs while the app is out of sight, so what has expired meanwhile (a threat picture left for 48 hours) is dealt with as it comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.appStarted() }
    val google = remember(context) {
        BuildConfig.GOOGLE_SERVER_CLIENT_ID.takeIf { it.isNotBlank() }?.let<String, GoogleSignInProvider> { CredentialManagerGoogleSignIn(context, it) }
    }

    val current = gate
    when {
        current is Gate.UpdateRequired -> UpdateRequiredScreen(current.minimum)
        link != null -> {
            // Leave the link's screens once the person is back at the sign-in (after the step is done, or backing out of it).
            var opened by remember(link) { mutableStateOf(false) }
            AuthHost(
                google = google,
                initialRoute = link,
                onRouteChanged = { route ->
                    if (route == link) opened = true else if (opened && route == AuthRoute.SignIn) viewModel.linkHandled()
                },
            )
        }
        else -> when (current) {
            Gate.Starting -> StartingScreen()
            is Gate.SignedOut -> AuthHost(google = google, notice = signedOutNotice(current.reason, current.code))
            is Gate.NeedsAffiliation -> AffiliationHost(
                signedInAs = current.user.email, pendingMilEmail = current.user.milEmail, onSignOut = viewModel::signOut,
            )
            is Gate.DataBelongsToSomeoneElse -> DataConflictScreen(
                email = current.user.email, unsyncedChanges = current.unsyncedChanges,
                onClear = viewModel::clearOtherAccountsPlans, onSignOut = viewModel::signOut,
            )
            is Gate.Ready -> MapHome(
                version = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE,
                maintenance = current.maintenance, onSignOut = viewModel::signOut,
                // An administrator can switch own aircraft off for an account; the server would refuse what it made.
                canMakeAircraft = current.user.hasFeature("aircraft_profiles"),
            )
            is Gate.UpdateRequired -> UpdateRequiredScreen(current.minimum)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(Tokens.Spacing.xl.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
            ) { content() }
        }
    }
}

/** While the stored session is read: a moment, with nothing to tap. */
@Composable
fun StartingScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("EZ/PZ", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The server no longer supports this version. The way out is an update, so that is the one thing offered. */
@Composable
fun UpdateRequiredScreen(minimum: String?) {
    val context = LocalContext.current
    Centered {
        ScreenHeader("Update required", eyebrow = "EZ/PZ", subtitle = "This version of the app is no longer supported by the server.")
        Text(
            "Update to ${minimum?.let { "version $it" } ?: "the latest version"} or newer to keep using it. Your saved plans are safe on this device and on the server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Open the store", onClick = {
            val store = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(store) }.onFailure {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        })
    }
}

/** A different person has signed in on a device that holds someone else's plans. They choose; nothing is decided for them. */
@Composable
fun DataConflictScreen(email: String, unsyncedChanges: Int, onClear: () -> Unit, onSignOut: () -> Unit) {
    Centered {
        ScreenHeader("Plans from another account", eyebrow = "THIS DEVICE", subtitle = "Signed in as $email.")
        Text(
            "This device holds saved plans that belong to a different account. They are not shown to you and are not uploaded to yours.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unsyncedChanges > 0) {
            Banner(
                if (unsyncedChanges == 1) "1 change on this device has not reached the server. Clearing the plans loses it."
                else "$unsyncedChanges changes on this device have not reached the server. Clearing the plans loses them.",
                BannerKind.Warning,
            )
        }
        // The safe choice is the prominent one: clearing loses work, so it is a deliberate second step and never the default.
        PrimaryButton("Sign out", onClick = onSignOut)
        SecondaryButton("Clear them and continue", onClick = onClear)
    }
}
