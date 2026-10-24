package app.ezpztac.brief

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.JsNumber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The brief's words and numbers, formatted by hand as the web's `compile.js` formats them and never by the locale (a device's can
 * write other digits): JavaScript's `Math.round` (halves up), `toFixed` (the exact binary value), and `String(n)`.
 *
 * Times. A route's clock times are wall-clock times on the plan's date, as the route planner keeps them; the brief prints them as they
 * are and treats them as local at the brief's offset. A wall-clock time is carried as the milliseconds of its fields read as UTC ("wall"),
 * and only made an instant with the offset.
 */
internal object BriefText {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val COMPASS = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    private val THOUSANDS = Regex("""\B(?=([0-9]{3})+(?![0-9]))""")

    fun pad2(n: Double): String = Js.text(n).padStart(2, '0')

    /** `withCommas`: the number rounded, its thousands grouped, its sign kept. */
    fun withCommas(n: Double): String {
        val rounded = Js.round(n)
        val whole = Js.text(Math.abs(rounded))
        val grouped = THOUSANDS.replace(whole, ",")
        return if (rounded < 0) "-$grouped" else grouped
    }

    fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

    /** One leading dot of an AMPS name is not said (".RP" is RP). */
    fun pointName(name: String?, fallback: String): String {
        val text = if (name != null) Js.trim(name) else ""
        return if (text.isNotEmpty()) (if (text.startsWith(".")) text.substring(1) else text) else fallback
    }

    fun headingText(deg: Double): String = "${Js.text(((Js.round(deg) % 360) + 360) % 360).padStart(3, '0')}°"
    fun feetText(ft: Double): String = "${withCommas(ft)} ft"
    fun nmText(nm: Double): String = "${JsNumber.toFixed(nm, 1)} nm"
    fun fuelText(lb: Double): String = "${withCommas(lb)} lb"
    fun percentText(fraction: Double): String = "${Js.text(Js.round(fraction * 100))}%"

    fun airspeedText(airspeed: Airspeed?): String? {
        val value = airspeed?.value?.takeIf { it.isFinite() } ?: return null
        return when (airspeed.type) {
            Airspeed.TYPE_INDICATED -> "${Js.text(value)} KIAS"
            Airspeed.TYPE_TRUE -> "${Js.text(value)} KTAS"
            else -> "${Js.text(value)} kt GS"
        }
    }

    fun altitudeText(ref: String, value: Double): String? {
        if (!value.isFinite()) return null
        return "${withCommas(value)} ft ${if (ref == AltitudeSetting.REF_MSL) "MSL" else "AGL"}"
    }

    /** Seconds as h+mm+ss, as AMPS writes a time enroute. */
    fun durationText(sec: Double?): String? {
        if (sec == null) return null
        val s = Js.round(sec)
        return "${Js.text(Math.floor(s / 3600))}+${pad2(Math.floor((s % 3600) / 60))}+${pad2(s % 60)}"
    }

    /** A route planner clock as wall-clock milliseconds: its fields, to the second, read as UTC (`Date.UTC`, which puts a year below 100 in the 1900s). */
    fun wallOf(time: LocalDateTime?): Long? = time?.withNano(0)?.let { if (it.year in 0..99) it.plusYears(1900) else it }?.toInstant(ZoneOffset.UTC)?.toEpochMilli()

    private fun utc(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)

    private fun two(n: Int) = n.toString().padStart(2, '0')

    fun clockText(wall: Long?): String? = wall?.let { utc(it).let { d -> "${two(d.hour)}:${two(d.minute)}:${two(d.second)}" } }

    /** Hours and minutes of a wall clock, the seconds dropped (a time worked out from another, not planned). */
    fun minuteText(wall: Long?): String? = wall?.let { utc(it).let { d -> "${two(d.hour)}:${two(d.minute)}" } }

    /** An instant as local hours and minutes at the offset, to the nearest minute. */
    fun instantMinuteText(ms: Long?, offsetMinutes: Double): String? {
        if (ms == null) return null
        val local = Js.round((ms + offsetMinutes * 60000) / 60000) * 60000
        return minuteText(local.toLong())
    }

    private val ISO_DATE = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")

    fun dateText(isoDate: String?): String? {
        val m = ISO_DATE.matchEntire(isoDate ?: "") ?: return null
        val (y, mo, d) = m.destructured
        return "${d.toInt()} ${MONTHS.getOrNull(mo.toInt() - 1) ?: "undefined"} $y"
    }

    fun compassOf(deg: Double): String = COMPASS[(Js.round((((deg % 360) + 360) % 360) / 45) % 8).toInt()]

    /** `new Date(ms).toISOString()`: always milliseconds and Z; a year past 9999 or before 0 in six digits with its sign. */
    fun isoString(ms: Double): String? {
        if (!ms.isFinite()) return null
        val d = Instant.ofEpochMilli(ms.toLong()).atZone(ZoneOffset.UTC)                  // Date's TimeClip: towards zero
        val year = d.year
        val yearText = when {
            year in 0..9999 -> year.toString().padStart(4, '0')
            year < 0 -> "-" + (-year).toString().padStart(6, '0')
            else -> "+" + year.toString().padStart(6, '0')
        }
        val millis = (d.nano / 1_000_000).toString().padStart(3, '0')
        return "$yearText-${two(d.monthValue)}-${two(d.dayOfMonth)}T${two(d.hour)}:${two(d.minute)}:${two(d.second)}.${millis}Z"
    }

    private val ISO_FORMAT = Regex(
        """^([+-][0-9]{6}|[0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2}))?)?(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]+))?)?(Z|[+-][0-9]{2}:[0-9]{2})?)?$""",
    )

    /**
     * `Date.parse(text)` for the ECMAScript date-time format, the one the app writes (`2026-10-16T18:02:00Z`): a date alone is UTC, a date and
     * time with no offset the device's local time (as JavaScript reads it), else at its offset. Null where JavaScript says NaN, and for the
     * engines' own looser forms, which nothing here writes.
     */
    fun parseDate(text: String?): Double? {
        val m = ISO_FORMAT.matchEntire(text ?: return null) ?: return null
        val (yearText, monthText, dayText, hourText, minuteText, secondText, fractionText, zoneText) = m.destructured
        if (yearText == "-000000") return null
        val year = yearText.toInt()
        val month = monthText.ifEmpty { "01" }.toInt()
        val day = dayText.ifEmpty { "01" }.toInt()
        val hour = hourText.ifEmpty { "00" }.toInt()
        val minute = minuteText.ifEmpty { "00" }.toInt()
        val second = secondText.ifEmpty { "00" }.toInt()
        val millis = fractionText.take(3).padEnd(3, '0').toInt()
        if (month !in 1..12 || day < 1 || day > java.time.YearMonth.of(year, month).lengthOfMonth()) return null
        if (minute > 59 || second > 59 || hour > 24 || (hour == 24 && (minute > 0 || second > 0 || millis > 0))) return null
        val local = LocalDateTime.of(year, month, day, 0, 0).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .plusSeconds(second.toLong()).plusNanos(millis * 1_000_000L)
        val instant = when {
            zoneText == "Z" -> local.toInstant(ZoneOffset.UTC)
            zoneText.isNotEmpty() -> local.toInstant(ZoneOffset.of(zoneText))
            hourText.isEmpty() -> local.toInstant(ZoneOffset.UTC)
            else -> local.atZone(ZoneId.systemDefault()).toInstant()
        }
        return instant.toEpochMilli().toDouble()
    }
}
