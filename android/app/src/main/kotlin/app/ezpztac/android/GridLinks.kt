package app.ezpztac.android

import app.ezpztac.auth.AuthLinks
import app.ezpztac.geo.GridShare
import java.net.URI
import java.net.URISyntaxException

/**
 * A grid link: `https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK` (docs/GRID_SHARING.md, option 3), the web's `#grid=` read as
 * [GridShare.readGridLink] reads it. As for [AuthLinks], only an `https` link to the site's own hosts counts, since any app can start the
 * activity with any address; and only a grid that reads as one place: anything else is dropped. A link opens the app only once the site
 * serves its verified-link file (the owner's step); until then the browser takes it, and the web opens at the grid.
 */
object GridLinks {
    fun parse(link: String?, hosts: Set<String> = AuthLinks.SITE_HOSTS): GridShare.Link? = fragmentOf(link, hosts)?.let(GridShare::readGridLink)

    /**
     * The text after the first `#` of [link] when it is a site link with a `grid=` in it, else null. Split by hand: java.net.URI refuses a
     * fragment with a raw space or a lone `%` (`name=50%off`), which a browser takes and the web reads.
     */
    fun fragmentOf(link: String?, hosts: Set<String> = AuthLinks.SITE_HOSTS): String? {
        val at = link?.indexOf('#') ?: return null
        if (at < 0) return null
        val uri = try {
            URI(link.substring(0, at))
        } catch (_: URISyntaxException) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host?.lowercase() !in hosts) return null
        val fragment = link.substring(at + 1)
        return fragment.takeIf { Regex("(^|&)grid=").containsMatchIn(it) }
    }
}
