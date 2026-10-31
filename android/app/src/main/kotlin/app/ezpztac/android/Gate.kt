package app.ezpztac.android

import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiStanding
import app.ezpztac.network.ApiSupport
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.SignedOutReason
import app.ezpztac.network.isBelowMinimum
import app.ezpztac.network.servedApiMajors
import app.ezpztac.network.updateRequired

/** The API majors this build talks (`ezpz.apiMajors` in `android/gradle.properties`). */
val BUILT_API: ApiSupport = ApiSupport.parse(BuildConfig.SUPPORTED_API_MAJORS)

/**
 * Whether this app must send nothing now: it is below the server's minimum version as last heard (the owner's decision, 2026-10-10), or the
 * server serves none of the API majors this build talks, whichever of the two is behind. What waits stays on the device and goes once they agree.
 */
fun holdsBack(version: String, minimum: String?, served: List<Int>?, api: ApiSupport = BUILT_API): Boolean =
    isBelowMinimum(version, minimum) || api.standingWith(served) != ApiStanding.SUPPORTED

/** What the app shows in place of its own screens, if anything stands in the way. */
sealed interface Gate {
    /** The session has not been read yet. */
    data object Starting : Gate

    /**
     * The server no longer supports this version: it is below the server's minimum, or the server has moved past every API major this build
     * talks. Nothing else works until the app is updated.
     */
    data class UpdateRequired(val minimum: String?) : Gate

    data class SignedOut(val reason: SignedOutReason, val code: String?) : Gate

    /** Signed in, but not through the `.mil` / approval gate. */
    data class NeedsAffiliation(val user: ApiUser) : Gate

    /**
     * Signed in as someone other than the person whose plans are on this device. They are not shown, and not uploaded under this account;
     * the person chooses between clearing them (losing [unsyncedChanges] changes that never reached the server) and signing out.
     */
    data class DataBelongsToSomeoneElse(val user: ApiUser, val unsyncedChanges: Int) : Gate

    /**
     * Everything is in order. [maintenance] is the server's notice, if it is down for work: a banner, never a block, since planning is local.
     * [tooOld] is that this version is below the server's minimum as the device last heard it, or past the API it last served, with no config
     * here to say so (no signal): planning still opens, nothing is sent (the owner's decision), and a banner says why. [serverBehind] is that
     * the server serves only API majors older than any this build talks: the same, but the update is the server's, so the banner says so.
     */
    data class Ready(val user: ApiUser, val maintenance: String?, val tooOld: Boolean = false, val serverBehind: Boolean = false) : Gate
}

/**
 * Decides [Gate] from what is known. A missing [config] (no signal at launch) blocks nothing: an unreachable server must never lock a
 * crew out of planning, and an unreadable minimum version means supported (see [updateRequired]).
 *
 * Order matters: an update comes first, because an app the server no longer supports must not be trusted to talk to it at all, even to
 * sign in; then who is signed in; then the gate; then whose plans are on the device. The minimum [remembered] from an earlier look blocks
 * nothing on the screen, as no config does not: it only marks [Gate.Ready.tooOld] for the banner. So do the API majors: a config whose server
 * has moved past this build's ([api]) is an update required; one behind it, or majors [rememberedApi] from an earlier look, only a banner.
 */
fun gateFor(
    config: AppConfig?, auth: AuthState, ownership: Ownership?, version: String, remembered: String? = null,
    rememberedApi: List<Int>? = null, api: ApiSupport = BUILT_API,
): Gate {
    val standing = api.standingWith(config?.servedApiMajors ?: rememberedApi)
    if (config != null && (config.updateRequired("android", version) || standing == ApiStanding.APP_TOO_OLD)) {
        return Gate.UpdateRequired(config.minAppVersion.android)
    }
    return when (auth) {
        AuthState.Unknown -> Gate.Starting
        is AuthState.SignedOut -> Gate.SignedOut(auth.reason, auth.code)
        is AuthState.SignedIn -> when {
            !auth.user.accessOk -> Gate.NeedsAffiliation(auth.user)
            ownership == null -> Gate.Starting
            ownership is Ownership.SomeoneElses -> Gate.DataBelongsToSomeoneElse(auth.user, ownership.unsyncedChanges)
            else -> Gate.Ready(
                auth.user, maintenanceNotice(config),
                tooOld = isBelowMinimum(version, remembered) || standing == ApiStanding.APP_TOO_OLD,
                serverBehind = standing == ApiStanding.SERVER_TOO_OLD,
            )
        }
    }
}

private const val DEFAULT_MAINTENANCE = "The server is down for maintenance. Planning on this device still works."

/** The server's notice while it is in maintenance (its own words if it gave any), else none. */
private fun maintenanceNotice(config: AppConfig?): String? {
    val maintenance = config?.maintenance?.takeIf { it.active } ?: return null
    return maintenance.message?.takeIf { it.isNotBlank() } ?: DEFAULT_MAINTENANCE
}
