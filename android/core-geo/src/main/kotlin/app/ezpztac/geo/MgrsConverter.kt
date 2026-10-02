package app.ezpztac.geo

import app.ezpztac.model.LatLon
import app.ezpztac.model.Mgrs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Latitude/longitude to MGRS and back, on the device.
 *
 * Forward is `frontend/src/utils/mgrs.js` line for line: transverse Mercator by
 * Krueger's series (nanometre accuracy across a UTM zone, so 1 m digits match
 * the server's PyGeodesy exactly). Inverse is the same series run backwards,
 * resolved the way PyGeodesy's `parseMGRS(...).toLatLon()` resolves it, since
 * that is what `/api/convert-grid` answers and what this replaces.
 *
 * Covers UTM, 80 S to 84 N. The polar caps use UPS, which no landing zone needs;
 * they have no answer.
 */
public object MgrsConverter {
    private const val A = 6378137.0                 // WGS84 semi-major axis, metres
    private const val F = 1 / 298.257223563         // WGS84 flattening
    private const val K0 = 0.9996                   // UTM central scale factor
    private const val FALSE_EASTING = 500000.0
    private const val FALSE_NORTHING_SOUTH = 10000000.0
    private const val SQUARE_M = 100000.0
    private const val ROW_CYCLE_M = 2000000.0

    private val N = F / (2 - F)
    private val E = sqrt(F * (2 - F))               // eccentricity

    /** Rectifying radius, and Krueger's series to sixth order in n. */
    private val RECTIFYING = (A / (1 + N)) * (1 + N.pow(2) / 4 + N.pow(4) / 64 + N.pow(6) / 256)

    /** Forward (lat/lon to UTM) coefficients. */
    private val ALPHA = doubleArrayOf(
        N / 2 - (2 * N.pow(2)) / 3 + (5 * N.pow(3)) / 16 + (41 * N.pow(4)) / 180 -
            (127 * N.pow(5)) / 288 + (7891 * N.pow(6)) / 37800,
        (13 * N.pow(2)) / 48 - (3 * N.pow(3)) / 5 + (557 * N.pow(4)) / 1440 +
            (281 * N.pow(5)) / 630 - (1983433 * N.pow(6)) / 1935360,
        (61 * N.pow(3)) / 240 - (103 * N.pow(4)) / 140 + (15061 * N.pow(5)) / 26880 +
            (167603 * N.pow(6)) / 181440,
        (49561 * N.pow(4)) / 161280 - (179 * N.pow(5)) / 168 + (6601661 * N.pow(6)) / 7257600,
        (34729 * N.pow(5)) / 80640 - (3418889 * N.pow(6)) / 1995840,
        (212378941 * N.pow(6)) / 319334400,
    )

    /** Inverse (UTM to lat/lon) coefficients, Karney 2011. */
    private val BETA = doubleArrayOf(
        N / 2 - (2 * N.pow(2)) / 3 + (37 * N.pow(3)) / 96 - N.pow(4) / 360 -
            (81 * N.pow(5)) / 512 + (96199 * N.pow(6)) / 604800,
        N.pow(2) / 48 + N.pow(3) / 15 - (437 * N.pow(4)) / 1440 + (46 * N.pow(5)) / 105 -
            (1118711 * N.pow(6)) / 3870720,
        (17 * N.pow(3)) / 480 - (37 * N.pow(4)) / 840 - (209 * N.pow(5)) / 4480 +
            (5569 * N.pow(6)) / 90720,
        (4397 * N.pow(4)) / 161280 - (11 * N.pow(5)) / 504 - (830251 * N.pow(6)) / 7257600,
        (4583 * N.pow(5)) / 161280 - (108847 * N.pow(6)) / 3991680,
        (20648693 * N.pow(6)) / 638668800,
    )

    /** Conformal to geodetic latitude coefficients. */
    private val DELTA = doubleArrayOf(
        2 * N - (2 * N.pow(2)) / 3 - 2 * N.pow(3) + (116 * N.pow(4)) / 45 +
            (26 * N.pow(5)) / 45 - (2854 * N.pow(6)) / 675,
        (7 * N.pow(2)) / 3 - (8 * N.pow(3)) / 5 - (227 * N.pow(4)) / 45 +
            (2704 * N.pow(5)) / 315 + (2323 * N.pow(6)) / 945,
        (56 * N.pow(3)) / 15 - (136 * N.pow(4)) / 35 - (1262 * N.pow(5)) / 105 +
            (73814 * N.pow(6)) / 2835,
        (4279 * N.pow(4)) / 630 - (332 * N.pow(5)) / 35 - (399572 * N.pow(6)) / 14175,
        (4174 * N.pow(5)) / 315 - (144838 * N.pow(6)) / 6237,
        (601676 * N.pow(6)) / 22275,
    )

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"               // 8 degrees each from 80 S; X is 12
    private val COLUMN_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"          // I and O are never used
    /** Even zones start their rows at F, five letters along. */
    private const val EVEN_ZONE_ROWS = "FGHJKLMNPQRSTUVABCDE"

    private fun toRad(deg: Double) = deg * Math.PI / 180
    private fun toDeg(rad: Double) = rad * 180 / Math.PI

    /** A UTM position. [northing] includes the 10,000 km false northing in the southern hemisphere. */
    public data class Utm(val zone: Int, val band: Char, val easting: Double, val northing: Double)

    /** The UTM zone, including the Norway and Svalbard exceptions. */
    private fun zoneFor(lat: Double, lon: Double): Int {
        var zone = floor((lon + 180) / 6).toInt() + 1
        if (zone > 60) zone = 60                                  // lon == 180
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32
        if (lat >= 72 && lat < 84) {
            if (lon >= 0 && lon < 9) zone = 31
            else if (lon >= 9 && lon < 21) zone = 33
            else if (lon >= 21 && lon < 33) zone = 35
            else if (lon >= 33 && lon < 42) zone = 37
        }
        return zone
    }

    /** Latitude/longitude to UTM. Null outside 80 S to 84 N or for a non-finite input. */
    public fun toUtm(lat: Double, lon: Double): Utm? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat < -80 || lat >= 84) return null
        val wrapped = ((((lon + 180) % 360) + 360) % 360) - 180

        val zone = zoneFor(lat, wrapped)
        val centralMeridian = ((zone - 1) * 6 - 180 + 3).toDouble()
        val phi = toRad(lat)
        val lambda = toRad(wrapped - centralMeridian)

        // Conformal latitude, then the transverse Mercator series.
        val t = sinh(atanh(sin(phi)) - E * atanh(E * sin(phi)))
        val xiPrime = atan2(t, cos(lambda))
        val etaPrime = atanh(sin(lambda) / sqrt(1 + t * t))
        var xi = xiPrime
        var eta = etaPrime
        for (i in ALPHA.indices) {
            val j = 2 * (i + 1)
            xi += ALPHA[i] * sin(j * xiPrime) * cosh(j * etaPrime)
            eta += ALPHA[i] * cos(j * xiPrime) * sinh(j * etaPrime)
        }

        val easting = FALSE_EASTING + K0 * RECTIFYING * eta
        var northing = K0 * RECTIFYING * xi
        if (lat < 0) northing += FALSE_NORTHING_SOUTH

        val band = BANDS[minOf(floor((lat + 80) / 8).toInt(), BANDS.length - 1)]
        return Utm(zone, band, easting, northing)
    }

    /**
     * Latitude/longitude to a grid such as `16S GD 66993 52949`.
     *
     * [digits] per coordinate: 5 is 1 m, 4 is 10 m. Digits are truncated, not
     * rounded, as MGRS specifies: a grid names the square the point is in.
     */
    public fun toMgrs(lat: Double, lon: Double, digits: Int = 5): Mgrs? {
        require(digits in 1..5) { "digits must be 1..5 (10 km to 1 m)" }
        val utm = toUtm(lat, lon) ?: return null

        // The 100 km square: column letters cycle every three zones, rows every
        // two million metres, with even zones' rows offset by five letters.
        val set = (utm.zone - 1) % 3
        val columnIndex = floor(utm.easting / SQUARE_M).toInt() - 1
        val column = COLUMN_SETS[set].getOrNull(columnIndex) ?: return null
        val rowIndex = (floor(utm.northing / SQUARE_M).toInt() + (if (utm.zone % 2 == 0) 5 else 0)) % 20
        val square = "$column${ROW_LETTERS[rowIndex]}"

        val scale = 10.0.pow(5 - digits)
        fun within(metres: Double): String =
            floor((metres % SQUARE_M) / scale).toLong().toString().padStart(digits, '0')

        return Mgrs(utm.zone, utm.band, square, within(utm.easting), within(utm.northing))
    }

    private val GRID = Regex("""(\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-Z])(\d*)""")

    /**
     * A grid, as typed, to the point at the centre of the square it names, or
     * null when it is not a usable grid. Spaces and case are ignored and the
     * digits split evenly into easting and northing, so any even count of two or
     * more works; each pair past ten refines the square tenfold below a metre.
     */
    public fun parse(text: String): Mgrs? {
        val clean = text.filterNot { it.isWhitespace() }.uppercase()
        val match = GRID.matchEntire(clean) ?: return null
        val (zoneText, bandText, columnText, rowText, digits) = match.destructured
        val zone = zoneText.toInt()
        if (zone !in 1..60) return null
        if (digits.length < 2 || digits.length % 2 != 0) return null
        val half = digits.length / 2
        return Mgrs(zone, bandText[0], "$columnText$rowText", digits.substring(0, half), digits.substring(half))
    }

    /** The centre of the square a grid names, or null for a grid [parse] refuses or the squares a zone does not have. */
    public fun toLatLon(text: String): LatLon? = parse(text)?.let { toLatLon(it) }

    public fun toLatLon(grid: Mgrs): LatLon? {
        val zone = grid.zone
        val columnIndex = COLUMN_SETS[(zone - 1) % 3].indexOf(grid.square[0])
        val rows = if (zone % 2 == 0) EVEN_ZONE_ROWS else ROW_LETTERS
        val rowIndex = rows.indexOf(grid.square[1])
        if (columnIndex < 0 || rowIndex < 0) return null
        val bandIndex = BANDS.indexOf(grid.band)
        if (bandIndex < 0) return null

        // Position within the square: the digits name its lower-left corner at
        // 10^(5 - digits) metres, and the answer is its centre.
        val resolution = 10.0.pow(5 - grid.digits)
        val cornerEasting = (columnIndex + 1) * SQUARE_M + grid.easting.toDouble() * resolution
        var cornerNorthing = rowIndex * SQUARE_M + grid.northing.toDouble() * resolution

        // The row letters repeat every 2,000 km north; add blocks until the
        // northing reaches the band's floor. PyGeodesy takes the floor from the
        // band's southern edge on the 0 degree meridian, truncated to 100 km,
        // and works from the square's corner, centring afterwards.
        val bandFloor = toUtm((bandIndex * 8 - 80).toDouble(), 0.0)!!.northing
        val northingBottom = floor(bandFloor / SQUARE_M) * SQUARE_M
        val blocks = (northingBottom - cornerNorthing) / ROW_CYCLE_M
        if (blocks > 0) {
            cornerNorthing += minOf(blocks.toInt() + 1, if (grid.band == 'W') 3 else 4) * ROW_CYCLE_M
        }

        return fromUtm(
            zone,
            southern = grid.band < 'N',
            easting = cornerEasting + resolution / 2,
            northing = cornerNorthing + resolution / 2,
        )
    }

    private fun fromUtm(zone: Int, southern: Boolean, easting: Double, northing: Double): LatLon {
        val xi0 = (northing - if (southern) FALSE_NORTHING_SOUTH else 0.0) / (K0 * RECTIFYING)
        val eta0 = (easting - FALSE_EASTING) / (K0 * RECTIFYING)
        var xi = xi0
        var eta = eta0
        for (i in BETA.indices) {
            val j = 2 * (i + 1)
            xi -= BETA[i] * sin(j * xi0) * cosh(j * eta0)
            eta -= BETA[i] * cos(j * xi0) * sinh(j * eta0)
        }
        val chi = asin(sin(xi) / cosh(eta))
        var phi = chi
        for (i in DELTA.indices) phi += DELTA[i] * sin(2 * (i + 1) * chi)
        val lambda = atan2(sinh(eta), cos(xi))
        val centralMeridian = ((zone - 1) * 6 - 180 + 3).toDouble()
        return LatLon(toDeg(phi), wrap180(toDeg(lambda) + centralMeridian))
    }

    /**
     * Longitude folded into +-180 the way PyGeodesy's `wrap180` does: a value
     * already in range, +-180 included, is left alone. A square on the far side
     * of its zone's edge (zone 60's east, zone 1's west) otherwise comes back
     * as 180.0003 where the server says -179.9997.
     */
    private fun wrap180(lon: Double): Double =
        if (lon in -180.0..180.0) lon else ((lon + 180) % 360 + 360) % 360 - 180
}
