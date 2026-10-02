package app.ezpztac.android

import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.SignedOutReason
import app.ezpztac.network.updateRequired

/** What the app shows in place of its own screens, if anything stands in the way. */
sealed interface Gate {
    /** The session has not been read yet. */
    data object Starting : Gate

    /** The server no longer supports this version. Nothing else works until the app is updated. */
    data class UpdateRequired(val minimum: String?) : Gate

    data class SignedOut(val reason: SignedOutReason, val code: String?) : Gate

    /** Signed in, but not through the `.mil` / approval gate. */
    data class NeedsAffiliation(val user: ApiUser) : Gate

    /**
     * Signed in as someone other than the person whose plans are on this device. They are not shown, and not uploaded under this account;
     * the person chooses between clearing them (losing [unsyncedChanges] changes that never reached the server) and signing out.
     */
    data class DataBelongsToSomeoneElse(val user: ApiUser, val unsyncedChanges: Int) : Gate

    /** Everything is in order. [maintenance] is the server's notice, if it is down for work: a banner, never a block, since planning is local. */
    data class Ready(val user: ApiUser, val maintenance: String?) : Gate
}

/**
 * Decides [Gate] from what is known. A missing [config] (no signal at launch) blocks nothing: an unreachable server must never lock a
 * crew out of planning, and an unreadable minimum version means supported (see [updateRequired]).
 *
 * Order matters: an update comes first, because an app the server no longer supports must not be trusted to talk to it at all, even to
 * sign in; then who is signed in; then the gate; then whose plans are on the device.
 */
fun gateFor(config: AppConfig?, auth: AuthState, ownership: Ownership?, version: String): Gate {
    if (config != null && config.updateRequired("android", version)) {
        return Gate.UpdateRequired(config.minAppVersion.android)
    }
    return when (auth) {
        AuthState.Unknown -> Gate.Starting
        is AuthState.SignedOut -> Gate.SignedOut(auth.reason, auth.code)
        is AuthState.SignedIn -> when {
            !auth.user.accessOk -> Gate.NeedsAffiliation(auth.user)
            ownership == null -> Gate.Starting
            ownership is Ownership.SomeoneElses -> Gate.DataBelongsToSomeoneElse(auth.user, ownership.unsyncedChanges)
            else -> Gate.Ready(auth.user, maintenanceNotice(config))
        }
    }
}

private const val DEFAULT_MAINTENANCE = "The server is down for maintenance. Planning on this device still works."

/** The server's notice while it is in maintenance (its own words if it gave any), else none. */
private fun maintenanceNotice(config: AppConfig?): String? {
    val maintenance = config?.maintenance?.takeIf { it.active } ?: return null
    return maintenance.message?.takeIf { it.isNotBlank() } ?: DEFAULT_MAINTENANCE
}
