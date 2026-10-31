package app.ezpztac.auth

/** What Google's own account sheet came back with. */
sealed interface GoogleResult {
    /** An ID token for the server to verify (its audience is the app's Google client ID). */
    data class Token(val idToken: String) : GoogleResult

    /** The person closed the sheet. Not an error. */
    data object Cancelled : GoogleResult

    data class Failed(val message: String) : GoogleResult
}

/**
 * Shows Google's account sheet and returns the result. Provided by the app (it needs an activity and a Google client ID): without one
 * the screens do not offer Google at all.
 */
fun interface GoogleSignInProvider {
    suspend fun requestToken(): GoogleResult
}
