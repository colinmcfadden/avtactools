/// The four affiliations the unit builder offers, with the colour its button is drawn in.
public struct AffiliationOption: Hashable, Sendable {
    public var id: String
    public var label: String
    public var color: String

    public init(_ id: String, _ label: String, _ color: String) {
        self.id = id
        self.label = label
        self.color = color
    }
}

/// A kind of unit the builder offers, by the six-character function part of its SIDC (positions 5 to 10).
public struct UnitFunction: Hashable, Sendable {
    public var id: String
    public var label: String
    public var functionId: String

    public init(_ id: String, _ label: String, _ functionId: String) {
        self.id = id
        self.label = label
        self.functionId = functionId
    }
}

/// An echelon (position 11 of a SIDC): a team, a platoon, a battalion.
public struct EchelonOption: Hashable, Sendable {
    public var id: String
    public var label: String
    public var code: String

    public init(_ id: String, _ label: String, _ code: String) {
        self.id = id
        self.label = label
        self.code = code
    }
}

/// A one-tap symbol: a unit type or a threat, by its whole SIDC.
public struct SymbolPreset: Hashable, Sendable {
    public var id: String
    public var label: String
    public var sidc: String

    public init(_ id: String, _ label: String, _ sidc: String) {
        self.id = id
        self.label = label
        self.sidc = sidc
    }
}

/// The building blocks of the unit builder and the one-tap presets, as the web has them (`symbols/presets.js` and
/// `unit/UnitIcons.js`, held to `contracts/fixtures/symbols/sidc.json`). The labels are a starting point: what a symbol looks like is
/// the renderer's word, not theirs.
public enum SymbolPresets {
    public static let affiliations: [AffiliationOption] = [
        AffiliationOption("F", "Friendly", "#3b82f6"),
        AffiliationOption("H", "Hostile", "#ef4444"),
        AffiliationOption("N", "Neutral", "#22c55e"),
        AffiliationOption("U", "Unknown", "#eab308"),
    ]

    /// Common army unit functions, ground dimension.
    public static let unitFunctions: [UnitFunction] = [
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
    ]

    public static let echelons: [EchelonOption] = [
        EchelonOption("none", "—", "-"),
        EchelonOption("team", "Team / Crew", "A"),
        EchelonOption("squad", "Squad", "B"),
        EchelonOption("section", "Section", "C"),
        EchelonOption("platoon", "Platoon", "D"),
        EchelonOption("company", "Company", "E"),
        EchelonOption("battalion", "Battalion", "F"),
        EchelonOption("regiment", "Regiment", "G"),
        EchelonOption("brigade", "Brigade", "H"),
    ]

    /// Common threat starters: hostile ground equipment.
    public static let threats: [SymbolPreset] = [
        SymbolPreset("ew_radar", "EW / Acquisition Radar", "SHGPEWMAI-----"),
        SymbolPreset("air_srch_radar", "Air Search Radar", "SHGPEWRH------"),
        SymbolPreset("radar", "Radar", "SHGPEWRL------"),
        SymbolPreset("sam_launcher", "SAM Launcher", "SHGPEWRR------"),
        SymbolPreset("msl_launcher", "Missile Launcher", "SHGPEWMA------"),
        SymbolPreset("aaa_gun", "AAA (AD Gun)", "SHGPEWA-------"),
        SymbolPreset("adgun_self", "Self-Propelled AD Gun", "SHGPEWAH------"),
        SymbolPreset("manpad", "MANPADS", "SHGPEWMS------"),
    ]

    /// The unit presets, friendly; `Sidc.withAffiliation` gives the other three.
    public static let unitTypes: [SymbolPreset] = [
        SymbolPreset("infantry", "Infantry", "SFGPUCI--------"),
        SymbolPreset("light_infantry", "Light Infantry", "SFGPUCIL-------"),
        SymbolPreset("air_assault", "Air Assault Infantry", "SFGPUCIS-------"),
        SymbolPreset("airborne", "Airborne Infantry", "SFGPUCIA-------"),
        SymbolPreset("mountain", "Mountain Infantry", "SFGPUCIO-------"),
    ]
}
