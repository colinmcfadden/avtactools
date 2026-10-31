package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import app.ezpztac.model.WeatherObservation
import app.ezpztac.model.WeatherSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The last weather for each diagram, as the JSON a device keeps between launches. Written by hand, not derived, so that what is on disk is a format of
 * its own that a later release can read, and so that a file that is damaged or from some other release costs the entries it cannot read and nothing more.
 */
public object WeatherCodec {
    private const val VERSION = 1

    public fun encode(snapshots: Map<String, WeatherSnapshot>): String = JsonObject(
        mapOf(
            "version" to JsonPrimitive(VERSION),
            "entries" to JsonObject(snapshots.mapValues { (_, s) -> snapshot(s) }),
        ),
    ).toString()

    /** The snapshots in [text]; empty for text that is not this format. An entry that cannot be read is left out, the others are kept. */
    public fun decode(text: String): Map<String, WeatherSnapshot> {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        if (number(root["version"])?.toInt() != VERSION) return emptyMap()
        val entries = root["entries"] as? JsonObject ?: return emptyMap()
        return entries.mapNotNull { (id, e) -> readSnapshot(e as? JsonObject)?.let { id to it } }.toMap()
    }

    private fun snapshot(s: WeatherSnapshot): JsonObject = JsonObject(
        mapOf(
            "lat" to JsonPrimitive(s.at.lat), "lon" to JsonPrimitive(s.at.lon), "fetchedAt" to JsonPrimitive(s.fetchedAtMillis),
            "observation" to (s.observation?.let(::observation) ?: JsonNull),
            "notams" to notams(s.notams),
        ),
    )

    private fun observation(o: WeatherObservation): JsonObject = JsonObject(
        mapOf(
            "stationId" to JsonPrimitive(o.stationId), "stationName" to JsonPrimitive(o.stationName), "distanceMiles" to JsonPrimitive(o.distanceMiles),
            "windFrom" to (o.windFromDegrees?.let { JsonPrimitive(it) } ?: JsonNull), "windVariable" to JsonPrimitive(o.windVariable),
            "windSpeedKt" to num(o.windSpeedKt), "windGustKt" to num(o.windGustKt), "tempC" to num(o.tempC), "dewpointC" to num(o.dewpointC),
            "altimeterInHg" to num(o.altimeterInHg), "visibility" to text(o.visibility), "flightCategory" to text(o.flightCategory), "raw" to text(o.rawReport),
        ),
    )

    private fun notams(n: Notams): JsonElement = when (n) {
        Notams.Clear -> JsonObject(mapOf("kind" to JsonPrimitive("clear")))
        Notams.Unavailable -> JsonObject(mapOf("kind" to JsonPrimitive("unavailable")))
        is Notams.Listed -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("listed"),
                "groups" to JsonArray(n.groups.map { g -> JsonObject(mapOf("title" to JsonPrimitive(g.title), "texts" to JsonArray(g.texts.map(::JsonPrimitive)))) }),
            ),
        )
    }

    private fun readSnapshot(o: JsonObject?): WeatherSnapshot? {
        o ?: return null
        val lat = number(o["lat"]) ?: return null
        val lon = number(o["lon"]) ?: return null
        val fetched = (o["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: return null
        val observationElement = o["observation"]
        val observation = if (observationElement is JsonObject) readObservation(observationElement) ?: return null else null
        return WeatherSnapshot(LatLon(lat, lon), fetched, observation, readNotams(o["notams"] as? JsonObject) ?: return null)
    }

    private fun readObservation(o: JsonObject): WeatherObservation? = WeatherObservation(
        stationId = string(o["stationId"]) ?: return null,
        stationName = string(o["stationName"]) ?: return null,
        distanceMiles = number(o["distanceMiles"]) ?: 0.0,
        windFromDegrees = (o["windFrom"] as? JsonPrimitive)?.intOrNull,
        windVariable = (o["windVariable"] as? JsonPrimitive)?.booleanOrNull ?: false,
        windSpeedKt = number(o["windSpeedKt"]), windGustKt = number(o["windGustKt"]), tempC = number(o["tempC"]), dewpointC = number(o["dewpointC"]),
        altimeterInHg = number(o["altimeterInHg"]), visibility = string(o["visibility"]), flightCategory = string(o["flightCategory"]), rawReport = string(o["raw"]),
    )

    private fun readNotams(o: JsonObject?): Notams? = when (string(o?.get("kind"))) {
        "clear" -> Notams.Clear
        "unavailable" -> Notams.Unavailable
        "listed" -> Notams.Listed(
            (o?.get("groups") as? JsonArray).orEmpty().mapNotNull { g ->
                val group = g as? JsonObject ?: return@mapNotNull null
                val title = string(group["title"]) ?: return@mapNotNull null
                val texts = (group["texts"] as? JsonArray).orEmpty().mapNotNull { string(it) }
                if (texts.isEmpty()) null else NotamGroup(title, texts)
            },
        ).takeIf { it.groups.isNotEmpty() }
        else -> null
    }

    private fun num(v: Double?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull

    private fun text(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull

    private fun number(v: JsonElement?): Double? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun string(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
