package app.ezpztac.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the map's taps are for while the person is drawing. A boundary and a route are both drawn by tapping the map, so only one may be drawn at a time:
 * the taps would go to one of them, and the toolbars of both would sit on the same strip of the map. Whoever starts drawing [begin]s, and says
 * [end] when it finishes or is abandoned.
 */
@Singleton
class DrawingMode @Inject constructor() {
    enum class Kind(val noun: String) { BOUNDARY("boundary"), ROUTE("route") }

    private var held: Kind? = null

    /** Null when [kind] may draw (it holds the mode now, and holding it again is fine); else why not, in words. */
    @Synchronized
    fun begin(kind: Kind): String? {
        val other = held
        if (other != null && other != kind) return "Finish or cancel the ${other.noun} first."
        held = kind
        return null
    }

    /** [kind] has stopped drawing. Ending what is not held changes nothing. */
    @Synchronized
    fun end(kind: Kind) {
        if (held == kind) held = null
    }
}
