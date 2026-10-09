package app.ezpztac.missionpacks

/** The pack and the item a local id names. */
public data class PackItemRef(val pack: String, val item: String)

/**
 * The id a pack item has in an editor here (a diagram, a set of routes, a set of points): it names the pack and the item,
 * so what is a pack's can be told from what is this person's own, and from another pack's. The web's `packRef.js`.
 */
public object PackRef {
    // The web's /^pack:([^:]+):(.+)$/. JavaScript's `.` stops at \n, \r, U+2028 and U+2029; Java's also stops at U+0085,
    // so the class is spelt out. Matched whole, so a final line break is never let through as Java's `$` would.
    private val LOCAL_ID = Regex("pack:([^:]+):([^\n\r\u2028\u2029]+)")

    public fun localId(pack: String, item: String): String = "pack:$pack:$item"

    /** The pack and item of an id [localId] made, else null (a library record's id, or none). */
    public fun parse(id: String?): PackItemRef? {
        val match = LOCAL_ID.matchEntire(id ?: return null) ?: return null
        return PackItemRef(match.groupValues[1], match.groupValues[2])
    }
}
