package app.ezpztac.packs

import app.ezpztac.data.PackFacts
import app.ezpztac.network.PackChange
import app.ezpztac.network.PackItemSource
import java.time.Instant
import java.time.ZoneId

/** What the update from the original says (the web's UpdateFromOriginalDialog, the design's PackDialogs B), in its words. */
object UpdateWords {
    fun title(name: String) = "Update “$name” from Library?"

    /** When the Library version changed, if the server said: "Your Library version changed Oct 3 at 09:12. Updating replaces the pack copy with it." */
    fun text(source: PackItemSource?, zone: ZoneId, now: Instant): String {
        val at = PackFacts.instant(source?.originalUpdatedAt) ?: return "Updating replaces the pack copy with your Library version."
        return "Your Library version changed ${PackFacts.dateAtTime(at, zone, now)}. Updating replaces the pack copy with it."
    }

    /**
     * What the update replaces, as the callout says it: the count in bold ([Replaced.bold]) and the rest after it; none while the item is still asked for
     * ([loading]) or when nothing made in the pack is replaced; the plain warning when the count did not come back.
     */
    fun replaced(source: PackItemSource?, loading: Boolean, me: Int?): Replaced? {
        val count = source?.packChanges
        if (count == null) return if (loading) null else Replaced("", "Changes made to it in the pack will be replaced. The history keeps what was there.")
        if (count <= 0) return null
        return Replaced("$count ${if (count == 1) "change" else "changes"} made in the pack will be replaced", "${lastChange(count, source.lastPackChange, me)} The history keeps what was there.")
    }

    /** The callout: [bold] first (may be empty), then [rest]. */
    data class Replaced(val bold: String, val rest: String)

    /**
     * The last edit an update would replace, after the count (the web's `lastChangeWords`): ": SENTENCE." for one, else ", including Jess R.’s last, “set the
     * landing heading of LZ HAWK to 270°”." The sentence was written with its editor's name in front, which is taken off.
     */
    internal fun lastChange(count: Int, last: PackChange?, me: Int?): String {
        val summary = jsTrimmed(last?.summary ?: "").removeSuffix(".")
        if (count == 1) return if (summary.isNotEmpty()) ": $summary." else "."
        val actor = last?.actor ?: return "."
        val name = actor.name
        val whose = if (me != null && actor.id == me) "your" else "${PackFacts.shortName(name)}’s"
        val what = if (name.isNotEmpty() && summary.startsWith("$name ")) summary.substring(name.length + 1) else summary
        return if (what.isNotEmpty()) ", including $whose last, “$what”." else ", including $whose last."
    }

    private val EDGES = Regex("^[\\s\\p{Z}\\x{FEFF}]+|[\\s\\p{Z}\\x{FEFF}]+$")

    private fun jsTrimmed(text: String) = text.replace(EDGES, "")
}
