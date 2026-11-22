import CoreJS

/// What the unit builder holds while a unit is being made or changed (the web's `UnitBuilder.jsx`): the affiliation, the function,
/// the echelon and the two labels. Nothing is saved until the person adds or applies it; `sidc` is the symbol code these parts make.
///
/// As on the web, the builder knows only these five parts: a unit made elsewhere with another dimension (an air unit) or status
/// (anticipated) is shown as ground and present once it is opened here and applied, because `sidc` is built the web's way.
public struct UnitDraft: Hashable, Sendable {
    public var affiliation: String
    public var functionId: String
    public var echelon: String
    public var uniqueDesignation: String
    public var higherFormation: String

    public init(
        affiliation: String = "F",
        functionId: String = SymbolPresets.unitFunctions[0].functionId,
        echelon: String = "-",
        uniqueDesignation: String = "",
        higherFormation: String = ""
    ) {
        self.affiliation = affiliation
        self.functionId = functionId
        self.echelon = echelon
        self.uniqueDesignation = uniqueDesignation
        self.higherFormation = higherFormation
    }

    /// The symbol code for these parts.
    public var sidc: String { Sidc.build(affiliation: affiliation, functionId: functionId, echelon: echelon) }

    /// What a unit of this draft is made from: the symbol and the labels.
    public var config: UnitConfig {
        UnitConfig(sidc: sidc, uniqueDesignation: uniqueDesignation, higherFormation: higherFormation)
    }

    /// What applying this draft to a unit changes: its symbol and labels, and nothing else (a unit's position is its own).
    public var patch: JSONObject {
        JSONObject([
            ("sidc", .string(sidc)), ("uniqueDesignation", .string(uniqueDesignation)), ("higherFormation", .string(higherFormation)),
        ])
    }

    /// What applying this draft changes on a unit, the draft having been made from `before` (the unit as the builder showed it): only
    /// what the person changed, its symbol code when one of the symbol's three parts differs, and each label that does. A part they
    /// did not change stays as the unit has it, whatever changed it meanwhile, and so does what the builder cannot hold of it. A unit
    /// with no symbol code (`hasCode` false, an older image) takes this draft's. **Not like the web**, whose builder writes all three.
    public func patchOver(_ before: UnitDraft, hasCode: Bool = true) -> JSONObject {
        var fields: [(String, JSONTree)] = []
        let symbolChanged =
            !Kotlin.same(affiliation, before.affiliation) || !Kotlin.same(functionId, before.functionId)
            || !Kotlin.same(echelon, before.echelon)
        if !hasCode || symbolChanged { fields.append(("sidc", .string(sidc))) }
        if !Kotlin.same(uniqueDesignation, before.uniqueDesignation) { fields.append(("uniqueDesignation", .string(uniqueDesignation))) }
        if !Kotlin.same(higherFormation, before.higherFormation) { fields.append(("higherFormation", .string(higherFormation))) }
        return JSONObject(fields)
    }

    /// This draft, being made by a person from the unit as it was when they began (`start`), with each part they have not changed
    /// taking the unit's as it is `now`: a part changed under the builder (someone else's change, an undo) is shown, and what the
    /// person made of a part is kept.
    public func following(_ start: UnitDraft, _ now: UnitDraft) -> UnitDraft {
        func pick(_ mine: String, _ began: String, _ theirs: String) -> String { Kotlin.same(mine, began) ? theirs : mine }
        return UnitDraft(
            affiliation: pick(affiliation, start.affiliation, now.affiliation),
            functionId: pick(functionId, start.functionId, now.functionId),
            echelon: pick(echelon, start.echelon, now.echelon),
            uniqueDesignation: pick(uniqueDesignation, start.uniqueDesignation, now.uniqueDesignation),
            higherFormation: pick(higherFormation, start.higherFormation, now.higherFormation)
        )
    }

    /// The affiliation's name (`Friendly`), or nil if it is not one of the four.
    public var affiliationLabel: String? { SymbolPresets.affiliations.first { Kotlin.same($0.id, affiliation) }?.label }

    /// The function's name (`Infantry`), or nil for a function the builder has no name for.
    public var functionLabel: String? { SymbolPresets.unitFunctions.first { Kotlin.same($0.functionId, functionId) }?.label }

    /// The echelon's name (`Platoon`), or nil for none or one the builder does not know.
    public var echelonLabel: String? {
        SymbolPresets.echelons.first { Kotlin.same($0.code, echelon) && $0.code != "-" }?.label
    }

    /// The draft for a saved unit (`UnitBuilder`'s `init`): its symbol code split into the parts, each falling back to the builder's
    /// default when the code is missing or too short; the labels as saved. No unit gives the defaults.
    public static func of(_ unit: JSONObject?) -> UnitDraft {
        // A code of eleven characters or more has every part, and none is ever empty; anything shorter has none.
        let parts = Sidc.parse(text(unit?["sidc"]))
        let defaults = UnitDraft()
        return UnitDraft(
            affiliation: parts?.affiliation ?? defaults.affiliation,
            functionId: parts?.functionId ?? defaults.functionId,
            echelon: parts?.echelon ?? defaults.echelon,
            uniqueDesignation: text(unit?["uniqueDesignation"]),
            higherFormation: text(unit?["higherFormation"])
        )
    }

    /// Whether `unit` has a symbol code the builder can read its parts from; an older unit that is an image has none.
    public static func hasCode(_ unit: JSONObject?) -> Bool { Sidc.parse(text(unit?["sidc"])) != nil }

    /// A label as the web reads it: what is written, as text (a designation of 5 is "5", `String(value)`), and nothing for null,
    /// absent, a list or an object.
    private static func text(_ value: JSONTree?) -> String { JsValue.string(value) ?? "" }
}
