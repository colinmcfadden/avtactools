package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Held to contracts/fixtures/symbols/sidc.json: the unit builder's SIDC logic, its presets, and how a new unit is made, as the web does them. */
class SidcFixtureTest {
    private val fixture = Fixtures.load("symbols/sidc.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test
    fun `a SIDC is built from its parts, fitted to fifteen characters, as the web builds it`() {
        val all = cases("build")
        assertEquals(true, all.size >= 15)
        for (c in all) {
            val args = c.getValue("args").let { if (it is JsonNull) JsonObject(emptyMap()) else it.jsonObject }
            val built = Sidc.build(
                affiliation = text(args["affiliation"]) ?: "F", dimension = text(args["dimension"]) ?: "G", status = text(args["status"]) ?: "P",
                functionId = text(args["functionId"]) ?: "------", echelon = text(args["echelon"]) ?: "-",
            )
            assertEquals(c.getValue("expected").jsonPrimitive.content, built, "build $args")
        }
    }

    @Test
    fun `the affiliation is changed at the second position, and a SIDC too short for one is left alone`() {
        for (c in cases("withAffiliation")) {
            assertEquals(text(c["expected"]), Sidc.withAffiliation(text(c["sidc"]), c.getValue("affiliation").jsonPrimitive.content), "${c["sidc"]} ${c["affiliation"]}")
        }
    }

    @Test
    fun `the affiliation is read from the second position, and unknown when there is none`() {
        for (c in cases("affiliation")) {
            assertEquals(c.getValue("expected").jsonPrimitive.content, Sidc.affiliation(text(c["sidc"])), "${c["sidc"]}")
        }
    }

    @Test
    fun `a SIDC is split into the parts the builder edits, and a short one has none`() {
        for (c in cases("parse")) {
            val expected = c.getValue("expected").jsonObject
            val parts = Sidc.parse(text(c["sidc"]))
            if (expected.isEmpty()) {
                assertNull(parts, "${c["sidc"]}")
            } else {
                assertEquals(
                    Sidc.Parts(
                        expected.getValue("affiliation").jsonPrimitive.content, expected.getValue("dimension").jsonPrimitive.content,
                        expected.getValue("status").jsonPrimitive.content, expected.getValue("functionId").jsonPrimitive.content,
                        expected.getValue("echelon").jsonPrimitive.content,
                    ),
                    parts, "${c["sidc"]}",
                )
            }
        }
    }

    private fun rows(name: String): List<JsonObject> = fixture.getValue("presets").jsonObject.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `the presets are the web's, in the web's order`() {
        assertEquals(rows("affiliations").map { listOf(it.s("id"), it.s("label"), it.s("color")) }, SymbolPresets.affiliations.map { listOf(it.id, it.label, it.color) })
        assertEquals(rows("unitFunctions").map { listOf(it.s("id"), it.s("label"), it.s("functionId")) }, SymbolPresets.unitFunctions.map { listOf(it.id, it.label, it.functionId) })
        assertEquals(rows("echelons").map { listOf(it.s("id"), it.s("label"), it.s("code")) }, SymbolPresets.echelons.map { listOf(it.id, it.label, it.code) })
        assertEquals(rows("threats").map { listOf(it.s("id"), it.s("label"), it.s("sidc")) }, SymbolPresets.threats.map { listOf(it.id, it.label, it.sidc) })
        assertEquals(rows("unitTypes").map { listOf(it.s("id"), it.s("label"), it.s("sidc")) }, SymbolPresets.unitTypes.map { listOf(it.id, it.label, it.sidc) })
    }

    @Test
    fun `every preset is a whole SIDC`() {
        for (preset in SymbolPresets.unitTypes) assertEquals(15, preset.sidc.length, preset.id)
        // The web's threat presets are 14 characters, one short of a whole 2525C SIDC (milsymbol draws them all the same). Kept as they are: a
        // threat's SIDC is what an AMPS .ths file carries, so it is not ours to lengthen.
        for (preset in SymbolPresets.threats) assertEquals(14, preset.sidc.length, preset.id)
        for (function in SymbolPresets.unitFunctions) assertEquals(6, function.functionId.length, function.id)
        // The builder's own SIDC for a preset's parts is the preset (so the preset list and the builder agree).
        val infantry = SymbolPresets.unitTypes.first { it.id == "infantry" }
        assertEquals(infantry.sidc, Sidc.build(functionId = SymbolPresets.unitFunctions.first { it.id == "infantry" }.functionId))
    }

    @Test
    fun `a new unit is made as the web makes it`() {
        for (c in cases("createUnit")) {
            val config = c.getValue("config").jsonObject
            val center = c.getValue("center").jsonArray.map { it.jsonPrimitive.double }
            val made = UnitMarkers.create(
                UnitConfig(text(config["id"]), text(config["path"]), text(config["sidc"]), text(config["uniqueDesignation"]), text(config["higherFormation"])),
                LatLon(center[0], center[1]), c.getValue("offset").jsonPrimitive.double, c.getValue("id").jsonPrimitive.content,
            )
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("expected"), made).take(5), c.getValue("name").jsonPrimitive.content)
            assertEquals(c.getValue("expected").jsonObject.keys, made.keys, "${c["name"]}: the same keys, none left undefined")
        }
    }
}
