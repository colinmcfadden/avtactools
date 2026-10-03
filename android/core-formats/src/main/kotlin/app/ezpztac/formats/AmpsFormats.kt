package app.ezpztac.formats

import app.ezpztac.model.Airspeed
import app.ezpztac.model.JsNumber
import java.time.LocalDateTime

/**
 * The attribute value formats AMPS / Mission X keep in the mission's XML parts (the web's `ampsFormats.js`), written with JavaScript's number text
 * ([JsNumber.toText]) because the web builds them by putting a computed number into a template literal and the files must agree digit for digit:
 *
 * | | |
 * |---|---|
 * | `AirspeedValue` | `raw 40 Ground Knot` |
 * | `CruiseWind` | `0 T/0 m/s` |
 * | `PlanAltitudeValue` | `raw15.24 m Foot AGL` |
 * | `CmdAlt` | `940.0032000000001 MM` (metres MSL) |
 * | `Elevation` | `924.7632000000001 m User` |
 * | `CmdClockTime` | `1/7/2026 6:24:09.0000 PM` |
 */
public object AmpsFormats {
    public const val FT_TO_M: Double = 0.3048
    public const val KTS_TO_MPS: Double = 0.514444

    private fun text(value: Double) = JsNumber.toText(value)

    /** An airspeed type this does not know is written as ground speed, as the web does. */
    public fun airspeed(airspeed: Airspeed): String = "raw ${text(airspeed.value)} ${AIRSPEED_NAMES[airspeed.type] ?: "Ground"} Knot"

    public fun wind(dirTrue: Double, speedKts: Double): String = "${text(dirTrue)} T/${text(speedKts * KTS_TO_MPS)} m/s"

    /** Anything but `msl` is above ground level. */
    public fun planAltitude(valueFt: Double, ref: String): String = "raw${text(valueFt * FT_TO_M)} m Foot ${if (ref == "msl") "MSL" else "AGL"}"

    public fun cmdAlt(mslFt: Double): String = "${text(mslFt * FT_TO_M)} MM"

    public fun elevation(groundFt: Double): String = "${text(groundFt * FT_TO_M)} m User"

    /** `M/D/YYYY h:mm:ss.0000 AM`: no padding on the month, day or hour, a twelve-hour clock, and no fraction of a second. */
    public fun clockTime(time: LocalDateTime): String {
        var hours12 = time.hour % 12
        if (hours12 == 0) hours12 = 12
        val meridiem = if (time.hour < 12) "AM" else "PM"
        return "${time.monthValue}/${time.dayOfMonth}/${time.year} $hours12:${two(time.minute)}:${two(time.second)}.0000 $meridiem"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private val AIRSPEED_NAMES = mapOf("ground" to "Ground", "indicated" to "Indicated", "true" to "True")
}
