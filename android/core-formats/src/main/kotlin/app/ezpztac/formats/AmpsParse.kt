package app.ezpztac.formats

import app.ezpztac.model.Airspeed
import app.ezpztac.model.Wind
import kotlin.math.floor

/**
 * Readers for the AMPS / Mission X attribute value formats, the inverse of what AMPS
 * writes into a mission's `points.xml` and `legs.xml`. A port of `ampsParse.js`.
 *
 *     AirspeedValue  "raw 80 Ground Knot"
 *     CruiseWind     "0 T/0 m/s"           (direction in degrees true / speed in m/s)
 *     CmdAlt         "341.0712 MM"         (metres MSL)
 *     Elevation      "325.8312 m DAFIF"    (metres of ground, source-tagged)
 *     CmdClockTime   "9/17/2025 12:00:00.0000 AM"
 *
 * These formats are reverse-engineered, so the readers are as forgiving as the web's:
 * a value that does not fit comes back null rather than throwing.
 */
internal object AmpsParse {
    /** The factor `ampsParse.js` uses, which is not the one aircraft geometry uses. */
    const val FT_PER_M: Double = 3.28084
    private const val MPS_TO_KTS: Double = 1 / 0.514444

    /** JavaScript's `Math.round`: halves go up. */
    fun jsRound(x: Double): Double = floor(x + 0.5)

    private val AIRSPEED = Regex("""([\d.]+)\s+(Ground|Indicated|True)\s+Knot""", RegexOption.IGNORE_CASE)
    private val WIND = Regex("""(-?[\d.]+)\s*T\s*/\s*([\d.]+)\s*m/s""", RegexOption.IGNORE_CASE)
    private val METERS = Regex("""-?[\d.]+""")
    private val CLOCK = Regex(
        """(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})(?:[.\d]*)?\s*(AM|PM)""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_NUMBER = Regex("""^[ \t\n\r\u000B\u000C ﻿]*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /**
     * JavaScript's `parseFloat`: the number a string *starts with*, NaN if it starts with
     * none. So `"12.5px"` is 12.5 and `""` is NaN.
     */
    fun jsParseFloat(text: String?): Double {
        val match = LEADING_NUMBER.find(text ?: return Double.NaN) ?: return Double.NaN
        return match.value.trim().toDoubleOrNull() ?: Double.NaN
    }

    /** `"raw 80 Ground Knot"` to 80 ground. */
    fun airspeed(raw: String?): Airspeed? {
        val m = AIRSPEED.find(raw ?: return null) ?: return null
        return Airspeed(jsRound(jsParseFloat(m.groupValues[1])), m.groupValues[2].lowercase())
    }

    /** `"270 T/10.5 m/s"` to 270 degrees at 20 knots. */
    fun wind(raw: String?): Wind? {
        val m = WIND.find(raw ?: return null) ?: return null
        return Wind(
            dirTrue = jsRound(jsParseFloat(m.groupValues[1])),
            speedKts = jsRound(jsParseFloat(m.groupValues[2]) * MPS_TO_KTS),
        )
    }

    /** Metres to whole feet, as the plan stores ground elevations and planned altitudes. */
    fun feetFromMeters(meters: Double): Double = jsRound(meters * FT_PER_M)

    /** The leading number of a metres-tagged field (`"341.07 MM"`, `"325 m DAFIF"`), in metres. */
    fun meters(raw: String?): Double? {
        val m = METERS.find(raw ?: return null) ?: return null
        return jsParseFloat(m.value)
    }

    /** A clock as AMPS writes it: its date (`2025-09-17`) and time of day (`13:30:00`). */
    data class Clock(val date: String, val time: String)

    fun clock(raw: String?): Clock? {
        val m = CLOCK.find(raw ?: return null) ?: return null
        val (month, day, year, hourText, minute, second, meridiem) = m.destructured
        var hour = hourText.toInt() % 12
        if (meridiem.uppercase() == "PM") hour += 12
        return Clock(
            date = "$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}",
            time = "${hour.toString().padStart(2, '0')}:$minute:$second",
        )
    }
}
