package app.ezpztac.planning

/**
 * The colours a sketched route is drawn in, in the web's order (`ROUTE_COLORS`). The web hands them out from a counter that runs for the length of
 * the session; a phone's app is opened and closed all day, so a counter would give two routes the same colour as often as not. A new route here
 * takes the first colour no route of its set is using, and only once all eight are used does it start over.
 */
public object RouteColors {
    public val PALETTE: List<String> = listOf("#FF453A", "#0A84FF", "#32D74B", "#FFD60A", "#BF5AF2", "#FF9F0A", "#64D2FF", "#FF375F")

    /** The colour for a new route among routes already drawn in [taken]. Colours are compared without regard to case. */
    public fun next(taken: Collection<String>): String {
        val used = taken.map { it.uppercase() }.toSet()
        return PALETTE.firstOrNull { it !in used } ?: PALETTE[taken.size % PALETTE.size]
    }
}
