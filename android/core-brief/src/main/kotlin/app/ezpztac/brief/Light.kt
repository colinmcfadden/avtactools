package app.ezpztac.brief

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Light data for a brief, worked out on the device: BMNT, sunrise, sunset, EENT, moonrise, moonset and the moon's illumination. No
 * service, so it works offline and says nothing about where the mission is to anyone. A port of the web's `feature/brief/light.js`,
 * held to Skyfield's answers in `contracts/fixtures/brief/light.json` (sun events to a minute, the moon's to three, illumination to one
 * percent).
 *
 * The sun is the Astronomical Almanac's low-precision position (about 0.01°), the moon its low-precision series (about 0.3° in
 * longitude). Rise, set and twilight are found by stepping through the local day and bisecting each crossing, so a body that does not
 * rise or set that day (polar day, or the moon's daily lag) has no time: never one made up.
 *
 * Instants are milliseconds since 1970 (UTC). A "local day" is the 24 hours from local midnight at the offset given, the offset the
 * brief's clock times are in. The trigonometry is [StrictMath]'s (fdlibm), the closest the JVM has to the JavaScript engines': an
 * independent check of 3,000 random places, dates and offsets against the web found every event at the same instant, and illumination
 * differing at most in its last bit, far below the percent a brief says it to.
 */
public object Light {
    /** What [of] answers: each event's instant (null when it does not happen that local day) and the illuminated fraction (0..1). */
    public data class Data(
        val bmnt: Long?,
        val sunrise: Long?,
        val sunset: Long?,
        val eent: Long?,
        val moonrise: Long?,
        val moonset: Long?,
        val illumination: Double,
    )

    private const val RAD = Math.PI / 180
    private const val DAY_MS = 86_400_000L
    private const val J2000_MS = 946_728_000_000L                                    // Date.UTC(2000, 0, 1, 12, 0, 0): JD 2451545.0

    /** The sun's centre at this altitude is sunrise or sunset: refraction (34') and its semidiameter (16'). */
    public const val SUNRISE_ALTITUDE: Double = -0.8333

    /** Nautical twilight: the sun's centre 12° below the horizon. */
    public const val NAUTICAL_ALTITUDE: Double = -12.0

    private const val STEP_MS = 10L * 60 * 1000
    private const val PRECISION_MS = 1000L

    private fun sin(deg: Double) = StrictMath.sin(deg * RAD)
    private fun cos(deg: Double) = StrictMath.cos(deg * RAD)
    private fun norm360(deg: Double) = ((deg % 360) + 360) % 360

    /** Days since J2000.0 (TT and UT are not told apart). */
    private fun daysSinceJ2000(ms: Double) = (ms - J2000_MS) / DAY_MS

    private fun obliquity(d: Double) = 23.439 - 0.0000004 * d

    /** The sun's ecliptic longitude (°). */
    private fun sunLongitude(d: Double): Double {
        val g = norm360(357.529 + 0.98560028 * d)
        val q = norm360(280.459 + 0.98564736 * d)
        return norm360(q + 1.915 * sin(g) + 0.02 * sin(2 * g))
    }

    private class Equatorial(val ra: Double, val dec: Double)

    private fun equatorial(lambda: Double, beta: Double, d: Double): Equatorial {
        val e = obliquity(d)
        val ra = StrictMath.atan2(sin(lambda) * cos(e) - StrictMath.tan(beta * RAD) * sin(e), cos(lambda)) / RAD
        val dec = StrictMath.asin(sin(beta) * cos(e) + cos(beta) * sin(e) * sin(lambda)) / RAD
        return Equatorial(norm360(ra), dec)
    }

    private class Moon(val lambda: Double, val beta: Double, val parallax: Double)

    /** The moon's geocentric ecliptic longitude, latitude and horizontal parallax (°). The sums are the web's, term for term, in its order. */
    private fun moonPosition(d: Double): Moon {
        val t = d / 36525
        val lambda =
            218.32 + 481267.881 * t +
                6.29 * sin(135.0 + 477198.87 * t) - 1.27 * sin(259.3 - 413335.36 * t) +
                0.66 * sin(235.7 + 890534.22 * t) + 0.21 * sin(269.9 + 954397.74 * t) -
                0.19 * sin(357.5 + 35999.05 * t) - 0.11 * sin(186.5 + 966404.03 * t)
        val beta =
            5.13 * sin(93.3 + 483202.02 * t) + 0.28 * sin(228.2 + 960400.89 * t) -
                0.28 * sin(318.3 + 6003.15 * t) - 0.17 * sin(217.6 - 407332.21 * t)
        val parallax =
            0.9508 + 0.0518 * cos(135.0 + 477198.87 * t) + 0.0095 * cos(259.3 - 413335.36 * t) +
                0.0078 * cos(235.7 + 890534.22 * t) + 0.0028 * cos(269.9 + 954397.74 * t)
        return Moon(norm360(lambda), beta, parallax)
    }

    /** Greenwich mean sidereal time (°). */
    private fun siderealTime(d: Double) = norm360(280.46061837 + 360.98564736629 * d)

    private fun altitude(body: Equatorial, d: Double, lat: Double, lon: Double): Double {
        val h = siderealTime(d) + lon - body.ra
        return StrictMath.asin(sin(lat) * sin(body.dec) + cos(lat) * cos(body.dec) * cos(h)) / RAD
    }

    /** The sun's altitude less the altitude that counts, at an instant: zero at the event. */
    private fun sunAbove(threshold: Double, lat: Double, lon: Double): (Long) -> Double = { ms ->
        val d = daysSinceJ2000(ms.toDouble())
        altitude(equatorial(sunLongitude(d), 0.0, d), d, lat, lon) - threshold
    }

    /**
     * The moon's geocentric altitude less the altitude of its rising: its upper limb on the horizon seen from the ground, refraction
     * included (Meeus, Astronomical Algorithms, ch. 15: 0.7275 π − 0°34′).
     */
    private fun moonAbove(lat: Double, lon: Double): (Long) -> Double = { ms ->
        val d = daysSinceJ2000(ms.toDouble())
        val m = moonPosition(d)
        altitude(equatorial(m.lambda, m.beta, d), d, lat, lon) - (0.7275 * m.parallax - 0.5667)
    }

    /** Bisects a sign change of [f] between [a] and [b] to a second; the instant is a whole second, as the web's `Math.round` makes it. */
    private fun bisect(f: (Long) -> Double, a: Long, b: Long): Long {
        var lo = a
        var hi = b
        var fLo = f(lo)
        while (hi - lo > PRECISION_MS) {
            val mid = lo + (hi - lo) / 2                                               // Math.floor of a positive half
            val fMid = f(mid)
            if ((fMid < 0) == (fLo < 0)) {
                lo = mid
                fLo = fMid
            } else {
                hi = mid
            }
        }
        return Js.round((lo + hi).toDouble() / 2 / 1000).toLong() * 1000
    }

    private class Crossings(val rises: List<Long>, val sets: List<Long>)

    /** Every crossing of zero by [f] in [start, end): rising where it goes from below to above, setting the other way. */
    private fun crossings(f: (Long) -> Double, start: Long, end: Long): Crossings {
        val rises = ArrayList<Long>()
        val sets = ArrayList<Long>()
        var t0 = start
        var f0 = f(t0)
        while (t0 < end) {
            val t1 = minOf(t0 + STEP_MS, end)
            val f1 = f(t1)
            if (f0 < 0 && f1 >= 0) rises += bisect(f, t0, t1)
            else if (f0 >= 0 && f1 < 0) sets += bisect(f, t0, t1)
            t0 = t1
            f0 = f1
        }
        return Crossings(rises, sets)
    }

    private val DATE = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")

    /**
     * Local midnight of [date] (`YYYY-MM-DD`) at an offset east of UTC in minutes, as an instant; null for text that is not such a date.
     * Read as JavaScript's `Date.UTC` reads the parts, since the web does: the month and day roll over (`2026-02-30` is 2 March) and a
     * year below 100 is in the 1900s.
     */
    public fun localMidnight(date: String?, utcOffsetMinutes: Double): Long? {
        val m = DATE.matchEntire(date ?: return null) ?: return null
        val (y, mo, d) = m.destructured
        val year = y.toInt().let { if (it in 0..99) it + 1900 else it }
        val utc = LocalDate.of(year, 1, 1).plusMonths(mo.toLong() - 1).plusDays(d.toLong() - 1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return Js.round(utc - utcOffsetMinutes * 60000).toLong()
    }

    /** The moon's illuminated fraction (0..1) at an instant: from its elongation from the sun and the two distances (Meeus, ch. 48). */
    public fun moonIllumination(ms: Double): Double {
        val d = daysSinceJ2000(ms)
        val m = moonPosition(d)
        val sunLambda = sunLongitude(d)
        val cosPsi = cos(m.beta) * cos(m.lambda - sunLambda)
        val psi = StrictMath.acos(maxOf(-1.0, minOf(1.0, cosPsi)))
        val moonKm = 6378.14 / StrictMath.sin(m.parallax * RAD)
        val sunKm = 149598000.0
        val phase = StrictMath.atan2(sunKm * StrictMath.sin(psi), moonKm - sunKm * StrictMath.cos(psi))
        return (1 + StrictMath.cos(phase)) / 2
    }

    /**
     * Light data for a place and a local day, or null if the date or place cannot be read.
     *
     * @param lat degrees, north positive
     * @param lon degrees, east positive
     * @param date `YYYY-MM-DD`, the local date
     * @param utcOffsetMinutes the local offset east of UTC on that date (−240 for EDT)
     * @param at the instant illumination is for (the takeoff); local noon when null or not finite
     */
    public fun of(lat: Double, lon: Double, date: String?, utcOffsetMinutes: Double = 0.0, at: Double? = null): Data? {
        if (!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat) > 90) return null
        if (!utcOffsetMinutes.isFinite()) return null
        val start = localMidnight(date, utcOffsetMinutes) ?: return null
        val end = start + DAY_MS

        val sun = crossings(sunAbove(SUNRISE_ALTITUDE, lat, lon), start, end)
        val nautical = crossings(sunAbove(NAUTICAL_ALTITUDE, lat, lon), start, end)
        val moon = crossings(moonAbove(lat, lon), start, end)
        val whenMs = if (at != null && at.isFinite()) at else (start + DAY_MS / 2).toDouble()

        return Data(
            bmnt = nautical.rises.firstOrNull(),
            sunrise = sun.rises.firstOrNull(),
            sunset = sun.sets.firstOrNull(),
            eent = nautical.sets.firstOrNull(),
            moonrise = moon.rises.firstOrNull(),
            moonset = moon.sets.firstOrNull(),
            illumination = moonIllumination(whenMs),
        )
    }
}
