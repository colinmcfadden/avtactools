package app.ezpztac.model

/**
 * MIL-STD-2525C symbol identification codes (SIDCs), as the web's unit builder makes and reads them
 * (`frontend/src/feature/symbols/sidc.js`, held to `contracts/fixtures/symbols/sidc.json`).
 *
 * A 2525C SIDC is 15 characters: `S`, the affiliation (F friend, H hostile, N neutral, U unknown), the dimension (G ground, A air, S sea
 * surface), the status (P present, A anticipated), six characters of function, the echelon, and four more. These are written to be forgiving
 * the way the web's are: a part that is too short is padded and one that is too long is cut, and the whole is cut or padded to 15.
 */
public object Sidc {
    /** The parts the unit builder edits, as read from a SIDC. */
    public data class Parts(
        val affiliation: String,
        val dimension: String,
        val status: String,
        val functionId: String,
        val echelon: String,
    )

    /** A SIDC from its parts (`buildSidc`). Each default is what the web's is; a part is not checked, only fitted. */
    public fun build(
        affiliation: String = "F",
        dimension: String = "G",
        status: String = "P",
        functionId: String = "------",
        echelon: String = "-",
    ): String {
        val function = "$functionId------".take(6)
        return "S$affiliation$dimension$status$function$echelon----".take(15).padEnd(15, '-')
    }

    /** [sidc] with another affiliation at its second position; a SIDC of fewer than two characters (or none) is returned as it is. */
    public fun withAffiliation(sidc: String?, affiliation: String): String? =
        if (sidc != null && sidc.length >= 2) sidc.substring(0, 1) + affiliation + sidc.substring(2) else sidc

    /** The affiliation character of [sidc], `U` (unknown) if it has none. */
    public fun affiliation(sidc: String?): String = if (sidc != null && sidc.length >= 2) sidc.substring(1, 2) else "U"

    /** The parts of a SIDC, or null when it is too short (under 11 characters) to have them. */
    public fun parse(sidc: String?): Parts? {
        if (sidc == null || sidc.length < 11) return null
        return Parts(
            affiliation = sidc.substring(1, 2), dimension = sidc.substring(2, 3), status = sidc.substring(3, 4),
            functionId = sidc.substring(4, 10), echelon = sidc.substring(10, 11),
        )
    }
}
