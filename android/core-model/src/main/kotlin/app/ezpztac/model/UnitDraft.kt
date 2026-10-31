package app.ezpztac.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the unit builder holds while a unit is being made or changed (the web's `UnitBuilder.jsx`): the affiliation, the function, the echelon and
 * the two labels. Nothing is saved until the person adds or applies it; [sidc] is the symbol code these parts make.
 *
 * As on the web, the builder knows only these five parts: a unit made elsewhere with another dimension (an air unit) or status (anticipated) is
 * shown as ground and present once it is opened here and applied, because [sidc] is built the web's way (`buildSidc` with those two left out).
 */
public data class UnitDraft(
    val affiliation: String = "F",
    val functionId: String = SymbolPresets.unitFunctions.first().functionId,
    val echelon: String = "-",
    val uniqueDesignation: String = "",
    val higherFormation: String = "",
) {
    /** The symbol code for these parts. */
    public val sidc: String get() = Sidc.build(affiliation = affiliation, functionId = functionId, echelon = echelon)

    /** What a unit of this draft is made from: the symbol and the labels. */
    public val config: UnitConfig get() = UnitConfig(sidc = sidc, uniqueDesignation = uniqueDesignation, higherFormation = higherFormation)

    /** What applying this draft to a unit changes: its symbol and labels, and nothing else (a unit's position is its own). */
    public val patch: JsonObject
        get() = JsonObject(
            mapOf("sidc" to JsonPrimitive(sidc), "uniqueDesignation" to JsonPrimitive(uniqueDesignation), "higherFormation" to JsonPrimitive(higherFormation)),
        )

    /**
     * What applying this draft changes on a unit, the draft having been made from [before] (the unit as the builder showed it): only what the person
     * changed, its symbol code when one of the symbol's three parts differs, and each label that does. A part they did not change stays as the unit has it,
     * whatever changed it meanwhile (someone else, in a mission pack), and so does what the builder cannot hold of it: a symbol of another dimension keeps
     * its code when only a label changes. A unit with no symbol code ([hasCode] false, an older image) takes this draft's. **Not like the web**, whose
     * builder writes all three.
     */
    public fun patchOver(before: UnitDraft, hasCode: Boolean = true): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        if (!hasCode || affiliation != before.affiliation || functionId != before.functionId || echelon != before.echelon) fields["sidc"] = JsonPrimitive(sidc)
        if (uniqueDesignation != before.uniqueDesignation) fields["uniqueDesignation"] = JsonPrimitive(uniqueDesignation)
        if (higherFormation != before.higherFormation) fields["higherFormation"] = JsonPrimitive(higherFormation)
        return JsonObject(fields)
    }

    /**
     * This draft, being made by a person from the unit as it was when they began ([start]), with each part they have not changed taking the unit's as it is
     * [now]: a part changed under the builder (someone else's change, an undo) is shown, and what the person made of a part is kept.
     */
    public fun following(start: UnitDraft, now: UnitDraft): UnitDraft = UnitDraft(
        affiliation = if (affiliation == start.affiliation) now.affiliation else affiliation,
        functionId = if (functionId == start.functionId) now.functionId else functionId,
        echelon = if (echelon == start.echelon) now.echelon else echelon,
        uniqueDesignation = if (uniqueDesignation == start.uniqueDesignation) now.uniqueDesignation else uniqueDesignation,
        higherFormation = if (higherFormation == start.higherFormation) now.higherFormation else higherFormation,
    )

    /** The affiliation's name (`Friendly`), or null if it is not one of the four. */
    public val affiliationLabel: String? get() = SymbolPresets.affiliations.firstOrNull { it.id == affiliation }?.label

    /** The function's name (`Infantry`), or null for a function the builder has no name for. */
    public val functionLabel: String? get() = SymbolPresets.unitFunctions.firstOrNull { it.functionId == functionId }?.label

    /** The echelon's name (`Platoon`), or null for none or one the builder does not know. */
    public val echelonLabel: String? get() = SymbolPresets.echelons.firstOrNull { it.code == echelon && it.code != "-" }?.label

    public companion object {
        /**
         * The draft for a saved unit (`UnitBuilder`'s `init`): its symbol code split into the parts, each falling back to the builder's default
         * when the code is missing, too short, or has an empty part; the labels as saved. No unit gives the defaults.
         */
        public fun of(unit: JsonObject?): UnitDraft {
            // A code of eleven characters or more has every part, and none is ever empty; anything shorter has none.
            val parts = Sidc.parse(text(unit?.get("sidc")))
            val defaults = UnitDraft()
            return UnitDraft(
                affiliation = parts?.affiliation ?: defaults.affiliation,
                functionId = parts?.functionId ?: defaults.functionId,
                echelon = parts?.echelon ?: defaults.echelon,
                uniqueDesignation = text(unit?.get("uniqueDesignation")),
                higherFormation = text(unit?.get("higherFormation")),
            )
        }

        /** Whether [unit] has a symbol code the builder can read its parts from; an older unit that is an image has none. */
        public fun hasCode(unit: JsonObject?): Boolean = Sidc.parse(text(unit?.get("sidc"))) != null

        /** A label as the web reads it: what is written, as text (a designation of 5 is "5"), and nothing for null or absent. */
        private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
    }
}
