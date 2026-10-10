package app.ezpztac.auth

/**
 * A mission-pack or team invitation link: `https://<site>/?invite=<token>`, from an email or a team's shared link (`backend/email_service.py`).
 * This reads one into its token, which the shell keeps across sign-in and accepts once the person can open packs (the web's `inviteLink.js`).
 * It is never for the sign-in screens, which [AuthLinks] reads; an address that carried both (the server never sends one) would be read for
 * both, as the web takes `?invite=` apart from the sign-in's parameters.
 *
 * As for [AuthLinks], only an `https` link to the site's own hosts counts, since any other app can start the activity with any address; and
 * only a token of the shape the server makes ([TOKEN]): anything else is dropped, never sent.
 */
object InviteLinks {
    const val PARAM: String = "invite"

    /** What the server issues (`secrets.token_urlsafe(32)`: 43 characters) and accepts (at most 200), as the web checks it. */
    val TOKEN: Regex = Regex("^[A-Za-z0-9_-]{16,200}$")

    /** The invitation's token, or null when [link] is not an invitation link to [hosts]. The first `invite` is read, as the web's `searchParams.get` reads it. */
    fun parse(link: String?, hosts: Set<String> = AuthLinks.SITE_HOSTS): String? {
        val token = AuthLinks.siteQuery(link, hosts)?.firstOrNull { it.first == PARAM }?.second ?: return null
        return token.takeIf { TOKEN.matches(it) }
    }
}
