package app.ezpztac.workspace

/**
 * What a set of sketched routes is called before the person names it, as [LzNames] is for an LZ/PZ (docs/ANDROID_SCREENS_PLAN.md, *Save, with autosave*): the
 * web's own title for the routes sketched in a session, `SKETCHED ROUTES`, and `SKETCHED ROUTES 2` and on when one is taken. A set still called that offers
 * *Save*, which names it; a named one (and an imported mission, named for its file) says it is saved. Names are in capitals, as the web writes them.
 */
object RouteSetNames {
    private const val BASE = "SKETCHED ROUTES"
    private val MADE_UP = Regex("$BASE(?: \\d+)?", RegexOption.IGNORE_CASE)

    /** Whether [name] is still a made-up one. */
    fun isMadeUp(name: String): Boolean = MADE_UP.matches(name.trim())

    /** Why a made-up name cannot be what a set is saved or renamed as: it is what says the set has not been named yet. */
    const val MADE_UP_REFUSED = "Give it a name of its own: SKETCHED ROUTES is what a set is called until it is saved."

    /** The first of `SKETCHED ROUTES`, `SKETCHED ROUTES 2` … that no set in [taken] is called. */
    fun next(taken: Collection<String>): String {
        val used = taken.map { it.trim().lowercase() }.toSet()
        if (BASE.lowercase() !in used) return BASE
        var n = 2
        while ("${BASE.lowercase()} $n" in used) n++
        return "$BASE $n"
    }

    /** [name] as a set is called: trimmed and in capitals, as the web writes it. */
    fun clean(name: String): String = name.trim().uppercase()

    /** [name], or the first free `NAME 2`, `NAME 3` … when another set in [taken] is already called that (the web's *Keep both*). */
    fun free(name: String, taken: Collection<String>): String = LzNames.free(name, taken)
}
