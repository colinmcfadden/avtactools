package app.ezpztac.model

/** The four affiliations the unit builder offers, with the colour its button is drawn in. */
public data class AffiliationOption(val id: String, val label: String, val color: String)

/** A kind of unit the builder offers, by the six-character function part of its SIDC (positions 5 to 10). */
public data class UnitFunction(val id: String, val label: String, val functionId: String)

/** An echelon (position 11 of a SIDC): a team, a platoon, a battalion. */
public data class EchelonOption(val id: String, val label: String, val code: String)

/** A one-tap symbol: a unit type or a threat, by its whole SIDC. */
public data class SymbolPreset(val id: String, val label: String, val sidc: String)

/**
 * The building blocks of the unit builder and the one-tap presets, as the web has them (`symbols/presets.js` and `unit/UnitIcons.js`, held
 * to `contracts/fixtures/symbols/sidc.json`). The labels are a starting point: what a symbol looks like is the renderer's word, not theirs.
 */
public object SymbolPresets {
    public val affiliations: List<AffiliationOption> = listOf(
        AffiliationOption("F", "Friendly", "#3b82f6"),
        AffiliationOption("H", "Hostile", "#ef4444"),
        AffiliationOption("N", "Neutral", "#22c55e"),
        AffiliationOption("U", "Unknown", "#eab308"),
    )

    /** Common army unit functions, ground dimension. */
    public val unitFunctions: List<UnitFunction> = listOf(
        UnitFunction("infantry", "Infantry", "UCI---"),
        UnitFunction("light_inf", "Infantry (Light)", "UCIL--"),
        UnitFunction("air_assault", "Infantry (Air Assault)", "UCIS--"),
        UnitFunction("airborne", "Infantry (Airborne)", "UCIA--"),
        UnitFunction("mountain", "Infantry (Mountain)", "UCIO--"),
        UnitFunction("mech_inf", "Infantry (Mech)", "UCIZ--"),
        UnitFunction("armor", "Armor", "UCA---"),
        UnitFunction("cavalry", "Cavalry / Recon", "UCR---"),
        UnitFunction("field_arty", "Field Artillery", "UCF---"),
        UnitFunction("mortar", "Mortar", "UCFHE-"),
        UnitFunction("air_defense", "Air Defense", "UCD---"),
        UnitFunction("atgm", "Anti-Armor (ATGM)", "UCAT--"),
        UnitFunction("engineer", "Engineer", "UCE---"),
        UnitFunction("aviation", "Aviation", "UCV---"),
        UnitFunction("signal", "Signal", "UUS---"),
        UnitFunction("medical", "Medical", "UUMS--"),
        UnitFunction("maintenance", "Maintenance", "USM---"),
        UnitFunction("supply", "Supply", "USS---"),
        UnitFunction("hq", "Headquarters", "UH----"),
    )

    public val echelons: List<EchelonOption> = listOf(
        EchelonOption("none", "—", "-"),
        EchelonOption("team", "Team / Crew", "A"),
        EchelonOption("squad", "Squad", "B"),
        EchelonOption("section", "Section", "C"),
        EchelonOption("platoon", "Platoon", "D"),
        EchelonOption("company", "Company", "E"),
        EchelonOption("battalion", "Battalion", "F"),
        EchelonOption("regiment", "Regiment", "G"),
        EchelonOption("brigade", "Brigade", "H"),
    )

    /** Common threat starters: hostile ground equipment. */
    public val threats: List<SymbolPreset> = listOf(
        SymbolPreset("ew_radar", "EW / Acquisition Radar", "SHGPEWMAI-----"),
        SymbolPreset("air_srch_radar", "Air Search Radar", "SHGPEWRH------"),
        SymbolPreset("radar", "Radar", "SHGPEWRL------"),
        SymbolPreset("sam_launcher", "SAM Launcher", "SHGPEWRR------"),
        SymbolPreset("msl_launcher", "Missile Launcher", "SHGPEWMA------"),
        SymbolPreset("aaa_gun", "AAA (AD Gun)", "SHGPEWA-------"),
        SymbolPreset("adgun_self", "Self-Propelled AD Gun", "SHGPEWAH------"),
        SymbolPreset("manpad", "MANPADS", "SHGPEWMS------"),
    )

    /** The unit presets, friendly; [Sidc.withAffiliation] gives the other three. */
    public val unitTypes: List<SymbolPreset> = listOf(
        SymbolPreset("infantry", "Infantry", "SFGPUCI--------"),
        SymbolPreset("light_infantry", "Light Infantry", "SFGPUCIL-------"),
        SymbolPreset("air_assault", "Air Assault Infantry", "SFGPUCIS-------"),
        SymbolPreset("airborne", "Airborne Infantry", "SFGPUCIA-------"),
        SymbolPreset("mountain", "Mountain Infantry", "SFGPUCIO-------"),
    )
}
