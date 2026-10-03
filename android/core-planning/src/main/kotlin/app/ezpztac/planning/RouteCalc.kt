package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.PlanLeg
import app.ezpztac.model.PlanPoint
import app.ezpztac.model.PlanResult
import app.ezpztac.model.PlanTotals
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.Wind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Route planning math: leg distances and courses along the sketched geometry,
 * IAS to TAS conversion, wind-triangle ground speeds, TOT-anchored clock times
 * and fuel estimates. Pure and synchronous. Ground elevations (for AGL altitudes
 * and density altitude) are fetched separately and passed in.
 *
 * A port of `frontend/src/feature/msnxImport/routeCalc.js`, held to
 * `contracts/fixtures/planning/route.json`. The plan that comes out is exported to
 * AMPS, so the numbers must be the web's numbers: where the web does something
 * subtle the comment says so.
 */
public object RouteCalc {
    /** JavaScript's `Math.round`: halves go up (`kotlin.math.round` goes to even). */
    public fun jsRound(x: Double): Double = floor(x + 0.5)

    private fun toRad(deg: Double) = deg * Math.PI / 180
    private fun toDeg(rad: Double) = rad * 180 / Math.PI

    /** ISA temperature (deg C) at a pressure altitude in feet. */
    private fun isaTempC(altFt: Double) = 15 - 1.98 * (altFt / 1000)

    /**
     * IAS to TAS with the standard planning approximation: TAS grows about 2% per
     * 1,000 ft of density altitude. DA from pressure altitude and OAT.
     */
    public fun iasToTas(iasKts: Double, pressAltFt: Double, oatC: Double): Double {
        val da = pressAltFt + 118.8 * (oatC - isaTempC(pressAltFt))
        return iasKts * (1 + 0.02 * max(0.0, da) / 1000)
    }

    public data class WindTriangle(val gsKts: Double, val windCorrectionDeg: Double, val headwindKts: Double)

    /**
     * Wind-triangle solve. Wind direction is where the wind blows FROM (degrees
     * true), the aviation convention. Null when the wind is too strong for this
     * TAS to hold the course.
     */
    public fun solveWindTriangle(tasKts: Double, courseDeg: Double, windFromDeg: Double, windKts: Double): WindTriangle? {
        if (windKts <= 0) return WindTriangle(tasKts, 0.0, 0.0)
        val relRad = toRad(windFromDeg - courseDeg)
        val headwind = windKts * cos(relRad)
        val crosswind = windKts * sin(relRad)
        if (abs(crosswind) > tasKts) return null
        val wcaRad = asin(crosswind / tasKts)
        val gs = tasKts * cos(wcaRad) - headwind
        if (gs <= 0) return null
        return WindTriangle(gs, toDeg(wcaRad), headwind)
    }

    /**
     * AMPS-point list for planning: shaping points only shape leg geometry.
     *
     * A point with no AMPS id is excluded as well. Per-point plan overrides are
     * keyed by point id, so an id-less point cannot hold or export one: every such
     * row would read and write the same entry and move together.
     */
    public fun planPoints(points: List<RoutePoint>): List<RoutePoint> =
        points.filter { it.kind != RoutePoint.KIND_SHAPING && !it.id.isNullOrEmpty() }

    private val CLOCK = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /**
     * A `HH:MM` or `HH:MM:SS` clock on a `YYYY-MM-DD` date. An empty date means
     * [today]. Hours or minutes past their range roll over, as JavaScript's
     * `setHours` does. Null for text that is not a clock or a date that is not one.
     */
    internal fun parseClock(date: String, time: String?, today: LocalDate): LocalDateTime? {
        if (time.isNullOrEmpty()) return null
        val match = CLOCK.matchEntire(time.trim()) ?: return null
        val (hh, mm, ss) = match.destructured
        val day = if (date.isEmpty()) today else runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        return day.atStartOfDay()
            .plusHours(hh.toLong())
            .plusMinutes(mm.toLong())
            .plusSeconds(if (ss.isEmpty()) 0 else ss.toLong())
    }

    private data class PointAltitude(
        val ref: String,
        val value: Double,
        val mslFt: Double?,
        val aglFt: Double?,
        val groundFt: Double?,
    )

    /**
     * Computes the full plan for a sketched route.
     *
     * @param points every point of the route in flight order, shaping points included
     * @param elevationsFt optional ground elevation per point id, for AGL altitudes
     *   and density-altitude TAS conversion
     * @param today the day clock times fall on when the plan names none
     */
    public fun computeRoutePlan(
        points: List<RoutePoint>,
        plan: RoutePlan,
        elevationsFt: Map<String, Double> = emptyMap(),
        today: LocalDate = LocalDate.now(),
    ): PlanResult {
        val warnings = mutableListOf<String>()
        // Index into [points] for each AMPS point: leg distance follows the shaping
        // points between two of them, and the web finds those by identity.
        val ampsIndex = points.indices.filter { points[it].kind != RoutePoint.KIND_SHAPING && !points[it].id.isNullOrEmpty() }
        val amps = ampsIndex.map { points[it] }
        if (amps.size < 2) {
            return PlanResult(emptyList(), emptyList(), null, listOf("Route needs at least two route points."))
        }

        // Point altitudes (per-point override, else route default). A charted
        // elevation (from a snapped local point) is authoritative over the DEM.
        val pointAlts = amps.map { p ->
            val alt: AltitudeSetting = plan.perPoint[p.id]?.altitude ?: plan.altitude
            val groundFt = p.chartElevationFt ?: elevationsFt[p.id]
            var mslFt: Double? = null
            var aglFt: Double? = null
            if (alt.ref == AltitudeSetting.REF_MSL) {
                mslFt = alt.value
                if (groundFt != null) aglFt = alt.value - groundFt
            } else {
                aglFt = alt.value
                if (groundFt != null) mslFt = groundFt + alt.value
            }
            PointAltitude(alt.ref, alt.value, mslFt, aglFt, groundFt)
        }

        // Legs between consecutive AMPS points; distance follows the shaping
        // geometry the aircraft actually flies, course is point-to-point. Airspeed
        // and wind are "to" values: they come from the point being flown TO (the
        // leg's arrival point).
        val legs = mutableListOf<PlanLeg>()
        for (i in 0 until amps.size - 1) {
            val from = amps[i]
            val to = amps[i + 1]

            var dist = 0.0
            for (j in ampsIndex[i] until ampsIndex[i + 1]) {
                val a = points[j]
                val b = points[j + 1]
                dist += GreatCircle.distanceNm(a.lat, a.lon, b.lat, b.lon)
            }
            val course = GreatCircle.trueCourseDeg(from.lat, from.lon, to.lat, to.lon)

            val arriving = plan.perPoint[to.id]
            val airspeed: Airspeed = arriving?.airspeed ?: plan.airspeed
            val wind: Wind = arriving?.wind ?: plan.wind ?: Wind(0.0, 0.0)
            var gsKts: Double?
            var tasKts: Double? = null
            var windCorrectionDeg = 0.0

            if (airspeed.type == Airspeed.TYPE_GROUND) {
                gsKts = airspeed.value
            } else {
                val tas = if (airspeed.type == Airspeed.TYPE_INDICATED) {
                    // Density altitude from the leg's average planned MSL altitude when
                    // known; otherwise convert at the raw altitude value (best effort).
                    val altFt = ((pointAlts[i].mslFt ?: pointAlts[i].value) +
                        (pointAlts[i + 1].mslFt ?: pointAlts[i + 1].value)) / 2
                    iasToTas(airspeed.value, altFt, plan.tempC ?: 15.0)
                } else {
                    airspeed.value
                }
                tasKts = tas
                val solved = solveWindTriangle(tas, course, wind.dirTrue, wind.speedKts)
                if (solved == null) {
                    // The web labels a leg's end by name, or by position when it has none.
                    val fromLabel = from.name?.takeIf { it.isNotEmpty() } ?: (i + 1).toString()
                    val toLabel = to.name?.takeIf { it.isNotEmpty() } ?: (i + 2).toString()
                    warnings += "Leg $fromLabel → $toLabel: wind exceeds what ${jsRound(tas).toLong()} KTAS can correct for."
                    gsKts = null
                } else {
                    gsKts = solved.gsKts
                    windCorrectionDeg = solved.windCorrectionDeg
                }
            }

            val timeSec = if (gsKts != null && gsKts > 0) dist / gsKts * 3600 else null
            val fuelLb = timeSec?.let { it / 3600 * (plan.fuelFlowLbHr ?: 0.0) }

            legs += PlanLeg(
                fromId = from.id,
                toId = to.id,
                fromName = from.name?.takeIf { it.isNotEmpty() } ?: "PT${i + 1}",
                toName = to.name?.takeIf { it.isNotEmpty() } ?: "PT${i + 2}",
                distNm = dist,
                courseTrueDeg = course,
                airspeed = airspeed,
                wind = wind,
                tasKts = tasKts,
                gsKts = gsKts,
                windCorrectionDeg = windCorrectionDeg,
                timeSec = timeSec,
                fuelLb = fuelLb,
            )
        }

        // Rolling ("stopwatch") elapsed time from the first point. Once a leg has no
        // ground speed, nothing after it has a time: unknown, never guessed.
        val cumSec = mutableListOf<Double?>(0.0)
        var timingBroken = false
        for (leg in legs) {
            if (leg.timeSec == null) timingBroken = true
            cumSec += if (timingBroken) null else cumSec.last()!! + leg.timeSec!!
        }

        // Clock times, anchored at whichever point carries a clock (the TOT).
        var anchorIdx = amps.indexOfFirst { !plan.perPoint[it.id]?.clock.isNullOrEmpty() }
        val anchorTime = if (anchorIdx >= 0) parseClock(plan.date, plan.perPoint[amps[anchorIdx].id]?.clock, today) else null
        if (anchorTime == null) anchorIdx = -1

        val planned = amps.mapIndexed { i, p ->
            var clock: String? = null
            if (anchorTime != null && cumSec[i] != null && cumSec[anchorIdx] != null) {
                // JavaScript Dates hold whole milliseconds, and floor the sum.
                val millis = floor((cumSec[i]!! - cumSec[anchorIdx]!!) * 1000).toLong()
                clock = formatClock(anchorTime.plusNanos(millis * 1_000_000))
            }
            // The leg arriving at this point supplies its "to" speed/wind (the first point has none).
            val legTo = if (i > 0) legs[i - 1] else null
            val alt = pointAlts[i]
            PlanPoint(
                id = p.id,
                uiId = p.uiId ?: p.id ?: "route-plan-point-$i",
                name = p.name,
                ptType = p.ptType,
                lat = p.lat,
                lon = p.lon,
                ref = alt.ref,
                value = alt.value,
                mslFt = alt.mslFt,
                aglFt = alt.aglFt,
                groundFt = alt.groundFt,
                airspeed = legTo?.airspeed,
                wind = legTo?.wind,
                legDistNm = legTo?.distNm,
                legCourseTrueDeg = legTo?.courseTrueDeg,
                legGsKts = legTo?.gsKts,
                legTimeSec = legTo?.timeSec,
                legFuelLb = legTo?.fuelLb,
                elapsedSec = cumSec[i],
                clock = clock,
                hasClock = !plan.perPoint[p.id]?.clock.isNullOrEmpty(),
                isTotAnchor = i == anchorIdx,
            )
        }

        val totalTimeSec = if (timingBroken) null else cumSec.last()
        val totals = PlanTotals(
            distNm = legs.fold(0.0) { sum, leg -> sum + leg.distNm },
            timeSec = totalTimeSec,
            fuelLb = totalTimeSec?.let { it / 3600 * (plan.fuelFlowLbHr ?: 0.0) },
        )

        return PlanResult(planned, legs, totals, warnings)
    }

    /** `HH:MM:SS` for a time of day, or `--:--:--` for none. */
    public fun formatClock(time: LocalDateTime?): String =
        if (time == null) "--:--:--"
        else "${two(time.hour)}:${two(time.minute)}:${two(time.second)}"

    // Not String.format: on a device set to an Arabic or Persian locale that would write the digits in that script.
    private fun two(n: Int): String = n.toString().padStart(2, '0')

    /** `H:MM:SS` (or `M:SS` under an hour) for a duration in seconds, or `--` for none. */
    public fun formatDuration(sec: Double?): String {
        if (sec == null) return "--"
        val s = jsRound(sec).toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${r.toString().padStart(2, '0')}"
        else "$m:${r.toString().padStart(2, '0')}"
    }

}
