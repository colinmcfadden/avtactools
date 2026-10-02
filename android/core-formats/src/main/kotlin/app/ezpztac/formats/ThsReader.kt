package app.ezpztac.formats

import app.ezpztac.model.Radar
import app.ezpztac.model.RadarBand
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat

/**
 * Reads an AMPS `.ths` threat file into [Threat]s.
 *
 * A `.ths` is a SQLite database: a `THREATS` table for each threat's identity and position,
 * and a `THREATRADAR` table of detection and engagement radars, tied to a threat by `ID`.
 * A port of `parseThs.js`, held to `contracts/fixtures/threats`.
 *
 * What a file does not say is filled from the web's defaults for a radar of that type (its
 * range, antenna height and band altitudes), and the overlay colours always come from
 * those defaults: a `.ths` stores AMPS's colour *number*, not a colour.
 *
 * Two places are stricter than the web, which has no good answer for either. A threat with no
 * position (a NULL, or text that is not a number) is skipped rather than placed at 0°, 0°, and
 * a radar value that is not a number takes the radar type's default rather than becoming NaN.
 */
public object ThsReader {
    private const val NOT_A_THS = "This doesn't look like an AMPS .ths threat file."
    private const val NO_TABLE = "No THREATS table found — is this an AMPS .ths file?"
    private const val NO_THREATS = "This .ths file contains no readable threats."

    public fun read(bytes: ByteArray): List<Threat> {
        val reader = try {
            SqliteReader(bytes)
        } catch (e: SqliteException) {
            throw FormatException(NOT_A_THS, e)
        }
        val threats = reader.readTable("THREATS") ?: throw FormatException(NO_TABLE)
        val radars = reader.readTable("THREATRADAR")

        val radarsByThreat = HashMap<Any, MutableList<List<Any?>>>()
        if (radars != null) {
            for (row in radars.rows) {
                val id = key(radars.value(row, "ID")) ?: continue
                radarsByThreat.getOrPut(id) { ArrayList() }.add(row)
            }
        }

        val out = ArrayList<Threat>()
        for (row in threats.rows) {
            val lat = coordinate(threats.value(row, "LATITUDE_DEG")) ?: continue
            val lon = coordinate(threats.value(row, "LONGITUDE_DEG")) ?: continue

            val radarRows = key(threats.value(row, "ID"))?.let { radarsByThreat[it] }.orEmpty()
            val parsed = radarRows.map { radar(radars!!, it) }

            out.add(
                Threat(
                    name = JsText.trim(text(threats.value(row, "OFFICIAL_NAME"), Radars.DEFAULT_NAME)),
                    milstdId = JsText.trim(text(threats.value(row, "MILSTD_ID"), Radars.DEFAULT_MILSTD_ID)),
                    lat = lat,
                    lon = lon,
                    information = JsText.trim(text(threats.value(row, "INFORMATION"), "")),
                    source = JsText.trim(text(threats.value(row, "SOURCE"), Radars.DEFAULT_SOURCE)),
                    showThreat = true,
                    radars = parsed.ifEmpty { Radars.defaultPair() },
                ),
            )
        }
        if (out.isEmpty()) throw FormatException(NO_THREATS)
        return out
    }

    private fun radar(table: SqlTable, row: List<Any?>): Radar {
        val type = JsText.number(table.value(row, "RADAR_TYPE")).let { if (it.isFinite() && it != 0.0) it.toInt() else 0 }
        val template = Radars.default(type)
        val bands = template.bands.mapIndexed { i, band ->
            RadarBand(
                altFt = number(table.value(row, "ELEVATION${i + 1}"), band.altFt),
                color = band.color,
                alpha = band.alpha,
                colorIndex = number(table.value(row, "COLOR${i + 1}"), band.colorIndex.toDouble()).toInt(),
                viewable = flag(table.value(row, "MASK${i + 1}_VIEWABLE"), default = true),
            )
        }
        return template.copy(
            rangeNmi = number(table.value(row, "RANGE_NMI"), template.rangeNmi),
            antennaHeightFt = number(table.value(row, "ANTENNAE_HEIGHT_FT"), template.antennaHeightFt),
            aglNotMsl = flag(table.value(row, "AGL_NOT_MSL"), default = false),
            showMask = flag(table.value(row, "SHOW_MASK"), default = true),
            showRangeRings = flag(table.value(row, "SHOW_RANGE_RINGS"), default = true),
            bands = bands,
        )
    }

    /** A threat's `ID` as a map key: numbers match whether SQLite stored them as integers or reals. */
    private fun key(id: Any?): Any? = when (id) {
        is Long -> id.toDouble()
        is Double -> id
        is String -> id
        else -> null
    }

    /** A latitude or longitude: a number in the file, or null. */
    private fun coordinate(value: Any?): Double? =
        if (value == null) null else JsText.number(value).takeIf { it.isFinite() }

    /** `Number(value ?? default)`, with a value that is not a number taking the default as well. */
    private fun number(value: Any?, default: Double): Double =
        if (value == null) default else JsText.number(value).takeIf { it.isFinite() } ?: default

    /** `Number(value ?? default) !== 0`: NULL is the default, and anything that is not zero is true. */
    private fun flag(value: Any?, default: Boolean): Boolean =
        if (value == null) default else JsText.number(value) != 0.0

    private fun text(value: Any?, fallback: String): String = JsText.text(value)?.takeIf { it.isNotEmpty() } ?: fallback
}
