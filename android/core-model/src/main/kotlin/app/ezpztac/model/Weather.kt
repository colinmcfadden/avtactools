package app.ezpztac.model

/**
 * What the nearest station reported, as the mission summary shows it. Every number can be missing, because a station reports what its sensors have and
 * the server passes on what it is given; a missing number is null, never zero, so a tile says `--` instead of a calm wind or a freezing day.
 */
public data class WeatherObservation(
    /** The ICAO id (`KRYY`), or `UNKNOWN` when the report has none. */
    val stationId: String,
    val stationName: String,
    /** How far the station is from the position asked about, in statute miles. */
    val distanceMiles: Double,
    /** Degrees true the wind is from. Null when it is variable (see [windVariable]) or not reported. */
    val windFromDegrees: Int?,
    val windVariable: Boolean,
    val windSpeedKt: Double?,
    val windGustKt: Double?,
    val tempC: Double?,
    val dewpointC: Double?,
    /** Inches of mercury. */
    val altimeterInHg: Double?,
    /** Statute miles, as the station wrote it (`10`, `10+`, `1 1/2`). */
    val visibility: String?,
    /** VFR, MVFR, IFR or LIFR. */
    val flightCategory: String?,
    /** The METAR as it was issued. */
    val rawReport: String?,
)

/** The NOTAMs about one kind of thing (the FAA's `featureName`: `Obstruction`, `Airspace`…), each as the text it was issued in. */
public data class NotamGroup(val title: String, val texts: List<String>)

/** What is known of the NOTAMs around a position. */
public sealed interface Notams {
    /** The search was done and there are none. */
    public data object Clear : Notams

    /** The search was done and these are active. */
    public data class Listed(val groups: List<NotamGroup>) : Notams {
        /** How many NOTAMs there are in all. */
        val count: Int get() = groups.sumOf { it.texts.size }
    }

    /** The search could not be done, so nothing is known: it is *not* the same as [Clear]. */
    public data object Unavailable : Notams
}

/**
 * The weather at a position, with the time it was fetched. Kept so the last answer can be shown with no signal, marked with its age: a report from the
 * morning is not today's weather, and the crew must be able to see that.
 */
public data class WeatherSnapshot(
    val at: LatLon,
    /** When it was fetched, in milliseconds since the epoch. */
    val fetchedAtMillis: Long,
    /** Null when no station answered. */
    val observation: WeatherObservation?,
    val notams: Notams,
) {
    /** How old this is at [nowMillis], in words. */
    public fun age(nowMillis: Long): String = WeatherAge.words(nowMillis - fetchedAtMillis)

    /** Whether this is old enough that it should be shown as stale: a METAR is an hour old when it is issued. */
    public fun isStale(nowMillis: Long): Boolean = nowMillis - fetchedAtMillis > WeatherAge.STALE_AFTER_MS
}

/** How old a fetch is, in words a person reads at a glance. */
public object WeatherAge {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** Past this a report is shown as stale. */
    public const val STALE_AFTER_MS: Long = 90 * MINUTE

    /** `just now`, `12 min ago`, `3 h ago`, `2 days ago`. A clock that went backwards (a negative age) is `just now`, not a negative number. */
    public fun words(ageMillis: Long): String = when {
        ageMillis < MINUTE -> "just now"
        ageMillis < HOUR -> "${ageMillis / MINUTE} min ago"
        ageMillis < 2 * DAY -> "${ageMillis / HOUR} h ago"
        else -> "${ageMillis / DAY} days ago"
    }
}
