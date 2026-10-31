package app.ezpztac.auth

import java.net.URI

/** Where in the sign-up and recovery flow the person is. The same modes the web's auth screen has (`AUTH_MODES` in AuthScreen.jsx). */
sealed interface AuthRoute {
    data object SignIn : AuthRoute
    data object Register : AuthRoute

    /** Registered: the verification link has been emailed to [email]. */
    data class CheckEmail(val email: String) : AuthRoute

    /** The verification link was followed: [token] is its one-time token. */
    data class Verify(val token: String) : AuthRoute

    data class Resend(val email: String) : AuthRoute
    data object Forgot : AuthRoute
    data class Reset(val token: String) : AuthRoute
}

/**
 * The links in the server's emails open `https://<site>/?auth=verify&token=…` or `?auth=reset&token=…`
 * (`backend/email_service.py`). This reads one into a route. Anything else is not for the sign-in screens.
 *
 * Only `https` links to the site's own hosts count. The activity can be started by any other app with any address, and a link
 * that says "reset" from somewhere else must not put the person on a screen that takes a token an attacker chose.
 */
object AuthLinks {
    /** The hosts the server's emails link to (its `FRONTEND_URL`). */
    val SITE_HOSTS: Set<String> = setOf("ezpztac.app", "www.ezpztac.app")

    fun parse(link: String?, hosts: Set<String> = SITE_HOSTS): AuthRoute? {
        // The first of each name, as the web's `searchParams.get` reads it.
        val params = siteQuery(link, hosts)?.distinctBy { it.first }?.toMap() ?: return null
        val token = params["token"]?.trim().orEmpty()
        return when (params["auth"]) {
            "verify" -> AuthRoute.Verify(token)
            "reset" -> AuthRoute.Reset(token)
            else -> null
        }
    }

    /**
     * The decoded query parameters of [link], in order, when it is an `https` link to one of [hosts]; null for anything else. Every link
     * the app takes from outside goes through this ([InviteLinks] too), so only the site's own links are read. That does not say who sent
     * one: any app can start the activity with such an address, as any page can open one on the web.
     */
    internal fun siteQuery(link: String?, hosts: Set<String>): List<Pair<String, String>>? {
        val uri = try {
            URI(link ?: return null)
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host?.lowercase() !in hosts) return null
        val query = uri.rawQuery ?: return null
        // As the web's URLSearchParams reads a query: a name with no "=" has an empty value, and an empty piece is nothing.
        return query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            when {
                pair.isEmpty() -> null
                i < 0 -> decode(pair) to ""
                else -> decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
            }
        }
    }

    private fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }
}
