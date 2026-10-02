package app.ezpztac.android

import app.ezpztac.network.SignedOutReason

/** What to say on the sign-in screen about why the person is there. Nothing, when they simply have not signed in yet. */
fun signedOutNotice(reason: SignedOutReason, code: String?): String? = when {
    reason == SignedOutReason.NOT_SIGNED_IN -> null
    code == "offline_too_long" ->
        "This device has not been online for more than 14 days, so you need to sign in again. Your plans on this device are kept."
    else -> "Your session expired. Sign in again to continue."
}
