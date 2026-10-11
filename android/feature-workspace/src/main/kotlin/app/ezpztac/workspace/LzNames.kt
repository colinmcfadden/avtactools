package app.ezpztac.workspace

import java.time.Instant
import java.time.ZoneId

/**
 * What an LZ/PZ is called before the person names it, and how a name is kept apart from one already in the Library (docs/ANDROID_SCREENS_PLAN.md,
 * *Save, with autosave*). Every LZ/PZ is saved on the device and synced as soon as it exists, so *Save* here names one: an LZ/PZ still called by its
 * made-up name (`LZ/PZ 3`, the web's own title for one not yet saved) offers it, a named one says it is saved.
 */
object LzNames {
    private val MADE_UP = Regex("LZ/PZ \\d+")

    /** Whether [name] is still the made-up one. */
    fun isMadeUp(name: String): Boolean = MADE_UP.matches(name.trim())

    /**
     * Why a made-up name cannot be what an LZ/PZ is saved or renamed as: it is what says the LZ/PZ has not been named yet, so one saved as `LZ/PZ 1`
     * would go on offering *Save* (the web, which keeps that apart in its Library, takes it).
     */
    const val MADE_UP_REFUSED = "Give it a name of its own: LZ/PZ and a number is what an LZ/PZ is called until it is saved."

    /** The first `LZ/PZ n` no LZ/PZ in [taken] is called. */
    fun next(taken: Collection<String>): String {
        val used = taken.map { it.trim().lowercase() }.toSet()
        var n = 1
        while ("lz/pz $n" in used) n++
        return "LZ/PZ $n"
    }

    /**
     * [name], or, when an LZ/PZ in [taken] is already called that, the first of `NAME 2`, `NAME 3` … that is free: the web's *Keep both* (the Library's
     * own choice between keeping both and replacing comes with it, S7). Names are compared as the person reads them, without regard to case.
     */
    fun free(name: String, taken: Collection<String>): String {
        val used = taken.map { it.trim().lowercase() }.toSet()
        if (name.lowercase() !in used) return name
        var n = 2
        while ("${name.lowercase()} $n" in used) n++
        return "$name $n"
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * When an LZ/PZ was last changed, as the design writes it: `Aug 25 · 17:34`, in [zone]. Written here rather than by the platform's formatter, which
     * can use another locale's digits and month names. Null for a time that cannot be read.
     */
    fun updated(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = try {
            Instant.parse(iso ?: return null).atZone(zone)
        } catch (_: Exception) {
            return null
        }
        return "${MONTHS[at.monthValue - 1]} ${at.dayOfMonth} · ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    }
}
