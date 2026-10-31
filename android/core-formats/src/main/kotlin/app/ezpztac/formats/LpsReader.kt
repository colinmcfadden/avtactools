package app.ezpztac.formats

import app.ezpztac.model.LocalPoint
import app.ezpztac.model.LocalPointSet
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads an AMPS `.LPS` file (local points) into a [LocalPointSet].
 *
 * An `.LPS` is a SpatiaLite database: a `Points` table whose `Coordinate` column holds a
 * POINT geometry blob. A port of `parseLps.js`, held to `contracts/fixtures/localpoints`.
 *
 * Rows the web skips are skipped here: a coordinate that is missing, short, not a POINT, or
 * not in the SpatiaLite layout. One thing is stricter: a point whose coordinates are not
 * numbers at all is skipped too, where the web would pass NaN to its map.
 */
public object LpsReader {
    private const val NOT_AN_LPS = "This doesn't look like an .LPS local points file."
    private const val NO_TABLE = "No Points table found — is this an .LPS local points file?"
    private const val NO_POINTS = "This .LPS file contains no readable points."

    private val EXTENSION = Regex("""\.lps$""", RegexOption.IGNORE_CASE)

    /** [fileName] names the set (without its `.lps`). */
    public fun read(bytes: ByteArray, fileName: String): LocalPointSet {
        val reader = try {
            SqliteReader(bytes)
        } catch (e: SqliteException) {
            throw FormatException(NOT_AN_LPS, e)
        }
        val table = reader.readTable("Points") ?: throw FormatException(NO_TABLE)

        val points = ArrayList<LocalPoint>()
        for (row in table.rows) {
            val coordinate = decodeSpatialitePoint(table.value(row, "Coordinate") as? ByteArray) ?: continue
            val elevation = table.value(row, "Elevation")
            points.add(
                LocalPoint(
                    name = JsText.trim(text(table.value(row, "ID"), "")),
                    description = JsText.trim(text(table.value(row, "Description"), "")),
                    // An empty group name is "Default"; a blank-but-present one stays blank, as on the web.
                    group = JsText.trim(text(table.value(row, "GroupName"), "Default")),
                    icon = JsText.trim(text(table.value(row, "IconName"), "")),
                    // .LPS Elevation values are feet. Anything that is not a number is no elevation.
                    elevationFt = when (elevation) {
                        is Long -> elevation.toDouble()
                        is Double -> elevation
                        else -> null
                    },
                    lat = coordinate.lat,
                    lon = coordinate.lon,
                ),
            )
        }
        if (points.isEmpty()) throw FormatException(NO_POINTS)
        return LocalPointSet(name = fileName.replace(EXTENSION, ""), points = points)
    }

    /** `value || fallback`, for text: an empty or missing value takes the fallback. */
    private fun text(value: Any?, fallback: String): String = JsText.text(value)?.takeIf { it.isNotEmpty() } ?: fallback

    private class Coordinate(val lat: Double, val lon: Double)

    /**
     * A SpatiaLite POINT blob: `[0]` 0x00, `[1]` byte order, `[2..5]` SRID, `[6..37]` the bounding box
     * (four doubles), `[38]` 0x7C, `[39..42]` the geometry class (1 is a POINT), `[43..50]` x,
     * `[51..58]` y, `[59]` 0xFE.
     */
    private fun decodeSpatialitePoint(blob: ByteArray?): Coordinate? {
        if (blob == null || blob.size < 59 || blob[0] != 0.toByte()) return null
        val order = if (blob[1] == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(blob).order(order)
        if (buffer.getInt(39) != 1) return null                    // only POINT geometries appear in .LPS files
        val x = buffer.getDouble(43)
        val y = buffer.getDouble(51)
        if (!x.isFinite() || !y.isFinite()) return null
        return Coordinate(lat = y, lon = x)
    }
}
