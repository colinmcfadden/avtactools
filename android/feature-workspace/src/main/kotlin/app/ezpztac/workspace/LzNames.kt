package app.ezpztac.workspace

import app.ezpztac.model.DiagramStatus
import app.ezpztac.sync.MAX_NAME
import app.ezpztac.sync.cutToCharacters
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
     * Whether an LZ/PZ is saved as the web's `savedId` says one is: named (not its made-up name) and analysed (none is saved before), and not the copy
     * kept beside a conflict. *Save* shows *Saved* then; a save seen happening asks for its point cloud ([app.ezpztac.data.BuildOnSave]), and the 3D
     * view keeps a saved one's build going when it closes.
     */
    fun isSaved(name: String, status: DiagramStatus, conflictOf: String?): Boolean =
        conflictOf == null && status == DiagramStatus.ANALYZED && !isMadeUp(name)

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
        // Within the server's 100 characters (a longer name is refused with a 500, which holds up every upload queued behind it): the name is cut, never the number.
        val whole = cutToCharacters(name, MAX_NAME).trimEnd()
        if (whole.lowercase() !in used) return whole
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = cutToCharacters(name, MAX_NAME - suffix.length).trimEnd() + suffix
            if (candidate.lowercase() !in used) return candidate
            n++
        }
    }

    /**
     * An ISO time as an instant: with an offset as it says, and **without one as UTC**, which is what the server writes (Python's `isoformat()` of its
     * `utcnow()`: `2026-10-12T09:00:00.123456`). The web's `new Date()` reads that as the browser's local time; here it is not. Null for anything else.
     */
    fun instantOf(iso: String?): Instant? {
        val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * When an LZ/PZ was last changed, as the design writes it: `Aug 25 · 17:34`, in [zone]. Written here rather than by the platform's formatter, which
     * can use another locale's digits and month names. Null for a time that cannot be read.
     */
    fun updated(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = instantOf(iso)?.atZone(zone) ?: return null
        return "${MONTHS[at.monthValue - 1]} ${at.dayOfMonth} · ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    }
}
