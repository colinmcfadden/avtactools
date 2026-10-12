package app.ezpztac.workspace

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The route plan's date as the design's field shows it (`mm/dd/yyyy`) and as the plan keeps it (`yyyy-mm-dd`, the web's `<input type="date">` value; blank is
 * today). Written by hand, not by the platform's formatter, which can use another locale's digits.
 */
internal object PlanDates {
    private val US = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""")
    private val ISO = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

    /** What the field shows for the plan's [iso] date: `10/12/2026`, or blank for none (today). A date the plan holds that cannot be read is shown as it is. */
    fun display(iso: String): String {
        val day = parseIso(iso.trim()) ?: return iso.trim()
        return "${two(day.monthValue)}/${two(day.dayOfMonth)}/${day.year}"
    }

    /**
     * What the plan keeps for [typed]: `2026-10-12` for `10/12/2026` (or for `2026-10-12` typed as it is kept), blank for blank (today), and null for anything that is
     * not yet a whole date that exists (`10/1`, `02/30/2026`).
     */
    fun toPlan(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty()) return ""
        val day = US.matchEntire(text)?.let { m -> date(m.groupValues[3], m.groupValues[1], m.groupValues[2]) }
            ?: ISO.matchEntire(text)?.let { m -> date(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        return day?.toString()
    }

    /** The plan's [iso] date as the calendar's selection (midnight UTC, as Material's date picker counts), or null for none. */
    fun millisOf(iso: String): Long? = parseIso(iso.trim())?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    /** The calendar's selection as the plan keeps it. */
    fun fromMillis(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun parseIso(text: String): LocalDate? = ISO.matchEntire(text)?.let { m -> date(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }

    private fun date(year: String, month: String, day: String): LocalDate? = try {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    } catch (_: DateTimeException) {
        null
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
}
