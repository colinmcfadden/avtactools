package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import app.ezpztac.model.WeatherObservation
import app.ezpztac.model.WeatherSnapshot
import app.ezpztac.network.WeatherReportDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Reading what `/api/weather` says. The services behind it are other people's and pass on what they are given, so every field can be a number, text or
 * missing: a number that is not one is none (never zero), and an answer that is not understood is "nothing known", not a guess.
 */
internal object WeatherMapping {
    /** The `station_id` the server uses when no station answered. */
    private const val NO_STATION = "TIMEOUT"

    /** What the server says when the FAA search found nothing. */
    private const val CLEAR_KEY = "Clear"

    fun snapshotOf(report: WeatherReportDto, at: LatLon, nowMillis: Long): WeatherSnapshot =
        WeatherSnapshot(at, nowMillis, observationOf(report), notamsOf(report.notams))

    private fun observationOf(r: WeatherReportDto): WeatherObservation? {
        if (r.stationId == NO_STATION) return null
        val dir = r.windDir
        val variable = dir is JsonPrimitive && dir.isString && dir.content.equals("VRB", ignoreCase = true)
        return WeatherObservation(
            stationId = r.stationId.ifBlank { "UNKNOWN" },
            stationName = r.name.ifBlank { r.stationId },
            distanceMiles = number(r.distanceMiles) ?: 0.0,
            windFromDegrees = number(dir)?.let { Math.round(it).toInt() },
            windVariable = variable,
            windSpeedKt = number(r.windSpeedKts),
            windGustKt = number(r.windGustKts),
            tempC = number(r.tempC),
            dewpointC = number(r.dewpC),
            altimeterInHg = number(r.pressure),                              // `--` when there is none: text, so none
            visibility = visibilityOf(r.visSm),
            flightCategory = r.flightCategory?.takeIf { it.isNotBlank() },
            rawReport = r.rawMetar?.takeIf { it.isNotBlank() },
        )
    }

    private fun visibilityOf(v: JsonElement?): String? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString) return p.content.trim().takeIf { it.isNotEmpty() }
        val n = p.doubleOrNull ?: return null
        return if (n == Math.floor(n) && Math.abs(n) < 1e9) n.toLong().toString() else n.toString()
    }

    /**
     * The NOTAMs: an object of lists of text by what they are about; `{"Clear": [...]}` when there are none; or a sentence when the search could not be
     * done. A search that could not be done is [Notams.Unavailable], which is not "there are none": the crew must be told which.
     */
    private fun notamsOf(value: JsonElement?): Notams {
        val obj = value as? JsonObject ?: return Notams.Unavailable
        val groups = obj.entries.mapNotNull { (title, texts) ->
            if (title == CLEAR_KEY) return@mapNotNull null
            val list = (texts as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }.orEmpty()
            if (list.isEmpty()) null else NotamGroup(title, list)
        }
        return if (groups.isEmpty()) Notams.Clear else Notams.Listed(groups)
    }

    private fun number(v: JsonElement?): Double? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}
