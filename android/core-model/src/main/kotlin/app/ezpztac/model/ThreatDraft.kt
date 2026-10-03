package app.ezpztac.model

/**
 * What the threat form holds while a threat is being made or changed (the web's `ThreatDialog`): the words and numbers as typed, and nothing
 * saved until [check] says they are good. Numbers stay text because that is what a person is in the middle of typing ("1." is not yet a number).
 *
 * **Not like the web:** the web's `num` turns a blank or unreadable number into 0 silently (a radar with no range is then refused by the server,
 * later, with "Radar range must be positive"). Here each is refused at the field, by name. The ranges are the planner's, not the web's: they keep
 * what is saved inside what an AMPS `.ths` can hold and what a viewshed can be asked for. Band altitudes are whole feet because a `.ths` stores
 * them as integers: a fraction would be cut off in the file without a word.
 *
 * A threat's position is not in the form: a new one is put where the crosshair is, and a held one is moved by the sheet's position controls.
 */
public data class ThreatDraft(
    val name: String = Radars.DEFAULT_NAME,
    val milstdId: String = Radars.DEFAULT_MILSTD_ID,
    val information: String = "",
    val source: String = Radars.DEFAULT_SOURCE,
    val radars: List<RadarDraft> = Radars.defaultPair().map(RadarDraft::of),
) {
    /** What [check] found: the threat to keep, or a refusal in words (for the form itself, not the sheet's banner, which can be scrolled out of sight). */
    public sealed interface Checked {
        public data class Valid(val threat: Threat) : Checked

        public data class Refused(val message: String) : Checked
    }

    /** Whether this draft can be kept, and as what. [at] is where the threat is; [base] the threat being changed, whose visibility and bands' colours stay as they are. */
    public fun check(at: LatLon, base: Threat? = null): Checked {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return Checked.Refused("A threat needs a name.")
        val sidc = milstdId.trim().uppercase()
        if (!SIDC.matches(sidc)) return Checked.Refused("The symbol code must be 1 to 15 letters, digits, hyphens or asterisks.")
        val checked = ArrayList<Radar>(radars.size)
        for (radar in radars) {
            when (val made = radar.check()) {
                is RadarDraft.Made -> checked.add(made.radar)
                is RadarDraft.Not -> return Checked.Refused(made.message)
            }
        }
        return Checked.Valid(
            Threat(
                name = TypedNumber.cut(trimmedName, NAME_MAX),
                milstdId = sidc,
                lat = at.lat,
                lon = at.lon,
                information = TypedNumber.cut(information.trim(), INFORMATION_MAX),
                source = TypedNumber.cut(source.trim().ifEmpty { Radars.DEFAULT_SOURCE }, SOURCE_MAX),
                showThreat = base?.showThreat ?: true,
                radars = checked,
            ),
        )
    }

    public companion object {
        public const val NAME_MAX: Int = 50
        public const val INFORMATION_MAX: Int = 255
        public const val SOURCE_MAX: Int = 32

        private val SIDC = Regex("""[A-Z0-9*-]{1,15}""")

        /** A new threat's form: the web's defaults, and the name `Threat n` (n counting the threats there already, +1). */
        public fun forNew(existingCount: Int): ThreatDraft = ThreatDraft(name = "${Radars.DEFAULT_NAME} ${existingCount + 1}")

        /** The form for a held threat, to change it. */
        public fun of(threat: Threat): ThreatDraft = ThreatDraft(
            name = threat.name, milstdId = threat.milstdId, information = threat.information, source = threat.source,
            radars = threat.radars.map(RadarDraft::of),
        )
    }
}

/** One radar's part of the form: its range and antenna as typed, and the three bands' altitudes. The bands' colours stay as the radar had them. */
public data class RadarDraft(
    val base: Radar,
    val rangeNmi: String,
    val antennaHeightFt: String,
    val aglNotMsl: Boolean,
    val showMask: Boolean,
    val showRangeRings: Boolean,
    val bandAltitudesFt: List<String>,
    val bandsViewable: List<Boolean>,
) {
    /** `Detection` or `Engagement`: what the form and its refusals call this radar. */
    public val label: String get() = if (base.type == Radars.ENGAGEMENT) "Engagement" else "Detection"

    internal sealed interface Result

    internal data class Made(val radar: Radar) : Result

    internal data class Not(val message: String) : Result

    /** The radar these fields make, or why they do not. */
    internal fun check(): Result {
        val range = number("$label range", rangeNmi, MIN_RANGE_NMI, MAX_RANGE_NMI).let { it.first ?: return Not(it.second!!) }
        val antenna = number("$label antenna height", antennaHeightFt, 0.0, MAX_ANTENNA_FT).let { it.first ?: return Not(it.second!!) }
        val bands = base.bands.mapIndexed { i, band ->
            val typed = bandAltitudesFt.getOrNull(i) ?: TypedNumber.plain(band.altFt)
            val name = "$label band ${i + 1} altitude"
            val altitude = number(name, typed, 0.0, MAX_BAND_FT).let { it.first ?: return Not(it.second!!) }
            if (altitude != Math.floor(altitude)) return Not("$name must be a whole number of feet.")
            band.copy(altFt = altitude, viewable = bandsViewable.getOrNull(i) ?: band.viewable)
        }
        return Made(
            base.copy(rangeNmi = range, antennaHeightFt = antenna, aglNotMsl = aglNotMsl, showMask = showMask, showRangeRings = showRangeRings, bands = bands),
        )
    }

    private fun number(name: String, typed: String, min: Double, max: Double): Pair<Double?, String?> {
        val value = TypedNumber.parse(typed) ?: return null to if (typed.isBlank()) "$name needs a number." else "$name is not a number."
        if (value < min || value > max) return null to "$name must be between ${TypedNumber.plain(min)} and ${TypedNumber.plain(max)}."
        return value to null
    }

    public companion object {
        public const val MIN_RANGE_NMI: Double = 0.1
        public const val MAX_RANGE_NMI: Double = 500.0
        public const val MAX_ANTENNA_FT: Double = 10_000.0
        public const val MAX_BAND_FT: Double = 50_000.0

        public fun of(radar: Radar): RadarDraft = RadarDraft(
            base = radar,
            rangeNmi = TypedNumber.plain(radar.rangeNmi),
            antennaHeightFt = TypedNumber.plain(radar.antennaHeightFt),
            aglNotMsl = radar.aglNotMsl, showMask = radar.showMask, showRangeRings = radar.showRangeRings,
            bandAltitudesFt = radar.bands.map { TypedNumber.plain(it.altFt) },
            bandsViewable = radar.bands.map { it.viewable },
        )
    }
}
