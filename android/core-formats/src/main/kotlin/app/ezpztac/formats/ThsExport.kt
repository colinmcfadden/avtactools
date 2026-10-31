package app.ezpztac.formats

import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The rows of an AMPS `.ths` for a set of threats. A port of `build_ths_bytes` in
 * `backend/ths_export.py`, held to what that function writes (`contracts/fixtures/threats/export.json`).
 *
 * A `.ths` is a SQLite database, and writing one is the platform's job: copy the bundled
 * `threat_template.ths` (a real file with its data removed, so the schema AMPS expects
 * survives exactly), empty its `THREATS`, `THREATRADAR`, `SYSTEM` and `LINKS` tables, and
 * insert these rows. This holds everything that is a decision rather than plumbing: what
 * goes in each column, what is cut to fit and what a missing value becomes.
 *
 * Rows are keyed by column name. A column a row does not name takes its default (NULL, in
 * the template). Values are `Long`, `Double`, `String` or `null`.
 */
public object ThsExport {
    public class Rows(
        public val threats: List<Map<String, Any?>>,
        public val radars: List<Map<String, Any?>>,
        public val systems: List<Map<String, Any?>>,
    )

    // AMPS's column widths: text is cut to them. Characters, not UTF-16 units, so a symbol outside the
    // Basic Multilingual Plane (an emoji) is never split in two.
    private const val NAME_MAX = 50
    private const val MILSTD_MAX = 15
    private const val INFORMATION_MAX = 255
    private const val SOURCE_MAX = 32

    /** What the backend writes into each threat's `CORRELATION_CODE`: this plus the threat's number. */
    private const val CORRELATION_BASE = 2_100_000_000L

    /** The colour numbers a band takes when the radar gave fewer than three. */
    private val DEFAULT_COLORS = listOf(1L, 3L, 5L)

    /** The `DATE_TIME` AMPS expects: `DDHHMMSSMMYYYY`, in UTC. */
    public fun dtg(at: Instant): String = DTG.format(at.atZone(ZoneOffset.UTC))

    private val DTG: DateTimeFormatter = DateTimeFormatter.ofPattern("ddHHmmssMMyyyy")

    public fun rows(threats: List<Threat>, dtg: String): Rows {
        val threatRows = ArrayList<Map<String, Any?>>()
        val radarRows = ArrayList<Map<String, Any?>>()
        val systemRows = ArrayList<Map<String, Any?>>()

        threats.forEachIndexed { index, threat ->
            val id = (index + 1).toLong()
            val name = cut(threat.name.ifEmpty { "Threat $id" }, NAME_MAX)

            threatRows.add(
                linkedMapOf(
                    "ID" to id,
                    "CORRELATION_CODE" to CORRELATION_BASE + id,
                    "MILSTD_ID" to cut(threat.milstdId.ifEmpty { Radars.DEFAULT_MILSTD_ID }, MILSTD_MAX),
                    "LATITUDE_DEG" to threat.lat,
                    "LONGITUDE_DEG" to threat.lon,
                    "DATE_TIME" to dtg,
                    "OFFICIAL_NAME" to name,
                    "APPROVED_NICKNAME" to "",
                    "ELLIPSE_ANGLE_DEG" to 0.0,
                    "ELLIPSE_SMAJ_NMI" to 0.0,
                    "ELLIPSE_SMIN_NMI" to 0.0,
                    "INFORMATION" to cut(threat.information, INFORMATION_MAX),
                    "SHOW_THREAT" to flag(threat.showThreat),
                    "SHOW_ELLIPSES" to 0L,
                    "ENABLE_EDIT" to 1L,
                    "SOURCE" to cut(threat.source.ifEmpty { Radars.DEFAULT_SOURCE }, SOURCE_MAX),
                    "OB_TYPE" to 0L,
                    "LABEL_TEXT_LEFT" to "",
                    "LABEL_TEXT_RIGHT" to "",
                    "geom" to null,
                ),
            )

            for (radar in threat.radars) {
                val bands = radar.bands
                // The three bands' altitudes, colours and visibility. A radar with fewer than three bands is
                // padded, and the colours are padded with all three defaults *after* the colours it did have
                // (`zip(bands, (1, 3, 5)) + [1, 3, 5]` in the backend), so with two bands the third colour is
                // 1 rather than 5. That is what the backend writes today, and so what these rows say.
                val altitudes = bands.map { it.altFt.toLong() } + listOf(0L, 0L, 0L)
                val colors = bands.take(DEFAULT_COLORS.size).map { it.colorIndex.toLong() } + DEFAULT_COLORS
                val viewable = bands.map { flag(it.viewable) } + listOf(1L, 1L, 1L)

                radarRows.add(
                    linkedMapOf(
                        "ID" to id,
                        "RADAR_TYPE" to radar.type.toLong(),
                        "RADAR_LATITUDE_DEG" to 0.0,
                        "RADAR_LONGITUDE_DEG" to 0.0,
                        "SHOW_MASK" to flag(radar.showMask),
                        "SHOW_RANGE_RINGS" to flag(radar.showRangeRings),
                        "RANGE_NMI" to radar.rangeNmi,
                        "RANGE_LIMITED" to 0L,
                        "CUSTOM_RANGE_NMI" to 0.0,
                        "RADAR_ELEVATION" to 0L,
                        "ANTENNAE_HEIGHT_FT" to radar.antennaHeightFt,
                        "AGL_NOT_MSL" to flag(radar.aglNotMsl),
                        "ELEVATION1" to altitudes[0],
                        "ELEVATION2" to altitudes[1],
                        "ELEVATION3" to altitudes[2],
                        "DRAW_STYLE" to 2L,
                        "BRUSH_STYLE" to 0L,
                        "COLOR1" to colors[0],
                        "COLOR2" to colors[1],
                        "COLOR3" to colors[2],
                        "MASK1_VIEWABLE" to viewable[0],
                        "MASK2_VIEWABLE" to viewable[1],
                        "MASK3_VIEWABLE" to viewable[2],
                        "geom" to null,
                    ),
                )
            }

            systemRows.add(
                linkedMapOf(
                    "SYSTEM_CODE" to id,
                    "SYSTEM_GROUP" to 2L,
                    "SYSTEM_NAME" to name,
                    "EQUIPMENT_FKEY" to id,
                    "USE_ENGAGEMENT" to flag(threat.radars.any { it.type == Radars.ENGAGEMENT }),
                    "USE_DETECTION" to flag(threat.radars.any { it.type == Radars.DETECTION }),
                ),
            )
        }
        return Rows(threatRows, radarRows, systemRows)
    }

    private fun flag(value: Boolean): Long = if (value) 1L else 0L

    /** The first [max] characters of [text]. */
    private fun cut(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }
}
