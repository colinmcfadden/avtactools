package app.ezpztac.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The unit builder's draft: what it starts from, what a saved unit gives it, and what it makes and changes. */
class UnitDraftTest {
    private fun unit(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to (if (v == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(v.toString())) })

    @Test
    fun `a new unit starts as friendly infantry with no echelon and no labels`() {
        val draft = UnitDraft()
        assertEquals("SFGPUCI--------", draft.sidc)
        assertEquals(draft, UnitDraft.of(null))
        assertEquals(UnitConfig(sidc = "SFGPUCI--------", uniqueDesignation = "", higherFormation = ""), draft.config)
    }

    @Test
    fun `a saved unit gives its parts back as the builder shows them`() {
        val draft = UnitDraft.of(unit("sidc" to "SHGPUCIL--D----", "uniqueDesignation" to "A/1-171", "higherFormation" to "2-101"))
        assertEquals(UnitDraft("H", "UCIL--", "D", "A/1-171", "2-101"), draft)
        assertEquals("SHGPUCIL--D----", draft.sidc)
        assertEquals("Hostile", draft.affiliationLabel)
        assertEquals("Infantry (Light)", draft.functionLabel)
        assertEquals("Platoon", draft.echelonLabel)
    }

    @Test
    fun `a missing, short or empty code falls back to the defaults, and the labels are kept`() {
        assertEquals(UnitDraft(uniqueDesignation = "TANK"), UnitDraft.of(unit("path" to "/units/tank.svg", "uniqueDesignation" to "TANK")))
        assertEquals(UnitDraft(), UnitDraft.of(unit("sidc" to "SHGPUC")))                      // fewer than eleven characters has no parts
        assertEquals(UnitDraft(), UnitDraft.of(unit("sidc" to "")))
        assertEquals(UnitDraft(), UnitDraft.of(unit("sidc" to null)))
    }

    @Test
    fun `a label that is not text is read as the text it is, and null as nothing`() {
        assertEquals("5", UnitDraft.of(JsonObject(mapOf("uniqueDesignation" to JsonPrimitive(5)))).uniqueDesignation)
        assertEquals("true", UnitDraft.of(JsonObject(mapOf("higherFormation" to JsonPrimitive(true)))).higherFormation)
        assertEquals("", UnitDraft.of(JsonObject(mapOf("uniqueDesignation" to kotlinx.serialization.json.JsonNull))).uniqueDesignation)
        assertEquals("", UnitDraft.of(JsonObject(emptyMap())).higherFormation)
    }

    @Test
    fun `a unit of another dimension or status is rebuilt as ground and present, as the web's builder does`() {
        val air = UnitDraft.of(unit("sidc" to "SFAPMFF--------"))
        assertEquals("F", air.affiliation)
        assertEquals("SFGPMFF--------", air.sidc)                                              // the A for air is not one of the builder's parts
        assertEquals("SFGPUCI--------", UnitDraft.of(unit("sidc" to "SFGAUCI--------")).sidc)  // nor is the A for anticipated
    }

    @Test
    fun `a function the builder has no name for is kept, and has no label`() {
        val draft = UnitDraft.of(unit("sidc" to "SFGPUCV--------"))
        assertEquals("UCV---", draft.functionId)
        assertEquals("Aviation", draft.functionLabel)
        val custom = UnitDraft.of(unit("sidc" to "SFGPUXXXXX-------"))
        assertNull(custom.functionLabel)
        assertEquals("UXXXXX", custom.functionId)
    }

    @Test
    fun `the echelon dash has no name`() {
        assertNull(UnitDraft().echelonLabel)
        assertEquals("Battalion", UnitDraft(echelon = "F").echelonLabel)
        assertNull(UnitDraft(echelon = "Z").echelonLabel)
    }

    @Test
    fun `applying a draft to a unit changes its symbol and labels and nothing else`() {
        val patch = UnitDraft("N", "UCA---", "E", "B/2", "1-22").patch
        assertEquals(setOf("sidc", "uniqueDesignation", "higherFormation"), patch.keys)
        assertEquals(JsonPrimitive("SNGPUCA---E----"), patch["sidc"])
        assertEquals(JsonPrimitive("B/2"), patch["uniqueDesignation"])
    }

    @Test
    fun `every function and echelon the builder offers makes a whole symbol code`() {
        for (function in SymbolPresets.unitFunctions) assertEquals(15, UnitDraft(functionId = function.functionId).sidc.length, function.id)
        for (echelon in SymbolPresets.echelons) assertEquals(15, UnitDraft(echelon = echelon.code).sidc.length, echelon.id)
        for (affiliation in SymbolPresets.affiliations) assertEquals(affiliation.id, Sidc.affiliation(UnitDraft(affiliation = affiliation.id).sidc))
    }
}
