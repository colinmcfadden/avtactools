package app.ezpztac.auth

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException

/**
 * What to tell the person about a failed call. The server's own words are used where it wrote them for people (its `message`); a
 * missing connection and a rate limit get plain explanations; anything else falls back to [fallback]. Never a stack trace, never a code.
 */
fun describe(error: Throwable, fallback: String): String = when (error) {
    is NetworkException -> "There is no connection to the server. Check your signal and try again."
    is RateLimitedException -> "Too many attempts. " + waitText(error.retryAfterSeconds)
    is SessionEndedException -> "Your session has ended. Sign in again."
    is ApiException -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    else -> fallback
}

private fun waitText(seconds: Long?): String = when {
    seconds == null || seconds <= 0 -> "Try again later."
    seconds < 90 -> "Try again in a minute."
    seconds < 3600 -> "Try again in about ${(seconds + 59) / 60} minutes."
    else -> "Try again in about ${(seconds + 1799) / 3600} hours."
}
