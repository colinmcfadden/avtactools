package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The setup's edits to the brief document, as the web's setup makes them (FlowStep.jsx, DetailsStep.jsx, ReviewStep.jsx, BriefSetup.jsx). */
class BriefEditsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val opdk = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray[0].jsonObject
    private val model = opdk["expected"]!!.jsonObject
    private val document = opdk["document"]!!.jsonObject
    private val amb = BriefFormats.format("amb")!!
    private val catalog = amb.constants
    private val blank = json("""{"version": 1, "template": {"id": "amb", "version": 1}, "answers": {}}""")

    private fun JsonObject.keysInOrder() = keys.toList()

    @Test
    fun `a format chosen keeps the answers, and the same or an unknown format changes nothing`() {
        val hasty = BriefEdits.chooseFormat("hasty")(document)
        assertEquals(json("""{"id": "hasty", "version": ${BriefFormats.format("hasty")!!.template.version}}"""), hasty["template"])
        assertEquals(document["answers"], hasty["answers"])
        assertSame(blank, BriefEdits.chooseFormat("amb")(blank))
        assertSame(blank, BriefEdits.chooseFormat("aopord")(blank))
    }

    @Test
    fun `the flow is kept as references, a stop with its role, and moved, re-roled and reset as the web does`() {
        val flow = BriefEdits.flowOf(model)
        assertEquals(json("""{"ref": "route:set-opdk/r-alderaan"}"""), flow[0])
        assertEquals(json("""{"ref": "lz:lz-bluebird", "role": "LZ"}"""), flow[1])
        fun refs(doc: JsonObject) = doc["flow"]!!.jsonArray.map { it.jsonObject["ref"]!!.jsonPrimitive.content }
        val moved = refs(BriefEdits.move(model, "lz:lz-bluebird", 1)(blank))
        assertEquals(listOf("route:set-opdk/r-alderaan", "lz:lz-hawk", "lz:lz-bluebird"), moved.take(3))
        assertSame(blank, BriefEdits.move(model, "route:set-opdk/r-alderaan", -1)(blank))
        assertSame(blank, BriefEdits.move(model, refs(BriefEdits.move(model, "lz:lz-bluebird", 0)(blank)).last(), 1)(blank))
        assertSame(blank, BriefEdits.move(model, "lz:not-in-the-flow", 1)(blank))
        val farp = BriefEdits.setRole(model, "lz:lz-bluebird", "FARP")(blank)["flow"]!!.jsonArray[1]
        assertEquals(json("""{"ref": "lz:lz-bluebird", "role": "FARP"}"""), farp)
        // Made before the brief is compiled again (the model still the one before): each starts from the document's flow, so neither undoes the other.
        val twice = BriefEdits.move(model, "lz:lz-bluebird", 1)(BriefEdits.move(model, "lz:lz-bluebird", 1)(blank))
        assertEquals(listOf("route:set-opdk/r-alderaan", "lz:lz-hawk", "route:set-opdk/r-bespin", "lz:lz-bluebird"), refs(twice).take(4))
        val roles = BriefEdits.setRole(model, "lz:lz-hawk", "LAAGER")(BriefEdits.setRole(model, "lz:lz-bluebird", "FARP")(blank))["flow"]!!.jsonArray
        assertEquals(json("""{"ref": "lz:lz-bluebird", "role": "FARP"}"""), roles[1])
        assertEquals(json("""{"ref": "lz:lz-hawk", "role": "LAAGER"}"""), roles[2])
        val gone = BriefEdits.removeGone(model, "lz:lz-hawk")(blank)["flow"]!!.jsonArray.map { it.jsonObject["ref"]!!.jsonPrimitive.content }
        assertFalse("lz:lz-hawk" in gone)
        assertEquals(flow.size - 1, gone.size)
        // Back to the routes' order: the flow goes, everything else stays where it was.
        val withFlow = BriefEdits.setRole(model, "lz:lz-bluebird", "FARP")(document)
        assertFalse(BriefEdits.followsRoutes(withFlow))
        val reset = BriefEdits.resetFlow(withFlow)
        assertTrue(BriefEdits.followsRoutes(reset))
        assertEquals(document.keysInOrder(), reset.keysInOrder())
    }

    @Test
    fun `the constants start from those offered, and take values, removals, the catalog's others and the person's own`() {
        val offered = catalog.filter { it.offered }.map { it.id }
        assertEquals(offered, BriefEdits.constantsOf(blank, catalog).map { it["id"]!!.jsonPrimitive.content })
        val first = offered.first()
        val typed = BriefEdits.setConstant(catalog, first, "100 KIAS")(blank)
        assertEquals(json("""{"id": "$first", "value": "100 KIAS"}"""), typed["constants"]!!.jsonArray.first())
        val removed = BriefEdits.removeConstant(catalog, first)(typed)
        assertFalse(removed["constants"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == first })
        val other = BriefEdits.suggestions(removed, catalog).map { it.id }
        assertTrue(first in other)
        val added = BriefEdits.addConstant(catalog, first)(removed)
        assertEquals(json("""{"id": "$first"}"""), added["constants"]!!.jsonArray.last())
        assertSame(added, BriefEdits.addConstant(catalog, first)(added))                         // already there
        val own = BriefEdits.addCustomConstant(catalog, "  Formation  ", " Staggered left ")(blank)
        assertEquals(json("""{"id": "custom-1", "label": "Formation", "value": "Staggered left"}"""), own["constants"]!!.jsonArray.last())
        val second = BriefEdits.addCustomConstant(catalog, "Chalks", "4")(own)
        assertEquals("custom-2", second["constants"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.content)
        assertSame(own, BriefEdits.addCustomConstant(catalog, "   ", "x")(own))
        // A long label is cut to 40 characters, never through an emoji.
        val long = "A".repeat(39) + "🚁" + "TAIL"
        val cut = BriefEdits.addCustomConstant(catalog, long, "")(blank)["constants"]!!.jsonArray.last().jsonObject["label"]!!.jsonPrimitive.content
        assertEquals("A".repeat(39) + "🚁", cut)
    }

    @Test
    fun `the threats are briefed unless turned off`() {
        assertTrue(BriefEdits.threatsOn(blank))
        val off = BriefEdits.setThreats(false)(blank)
        assertFalse(BriefEdits.threatsOn(off))
        assertEquals(json("""{"threats": false}"""), off["include"])
        assertTrue(BriefEdits.threatsOn(BriefEdits.setThreats(true)(off)))
    }

    @Test
    fun `an answer typed blank is taken away, and what is typed is kept as typed`() {
        val typed = BriefEdits.setText("2.b", " 500-1 day ")(blank)
        assertEquals(json("""{"value": " 500-1 day "}"""), BriefEdits.answerOf(typed, "2.b"))
        assertNull(BriefEdits.answerOf(BriefEdits.setText("2.b", "   ")(typed), "2.b"))
        assertEquals(json("""{"value": "Heavy"}"""), BriefEdits.answerOf(BriefEdits.setChoice("1.b", "Heavy")(blank), "1.b"))
        assertNull(BriefEdits.answerOf(BriefEdits.setChoice("1.b", null)(typed), "1.b"))
        val list = BriefEdits.setList("6.a", " CH 1 \n\n  CH 2\n")(blank)
        assertEquals(json("""{"value": ["CH 1", "CH 2"]}"""), BriefEdits.answerOf(list, "6.a"))
        assertNull(BriefEdits.answerOf(BriefEdits.setList("6.a", " \n ")(list), "6.a"))
    }

    @Test
    fun `a procedure is standard, custom or blank, and blank keeps what was typed for the others`() {
        val standard = BriefEdits.setMode("5.a.ii", "standard")(blank)
        assertEquals(json("""{"mode": "standard"}"""), BriefEdits.answerOf(standard, "5.a.ii"))
        val custom = BriefEdits.setProcedureText("5.a.ii", "Our own words")(BriefEdits.setMode("5.a.ii", "custom")(standard))
        assertEquals(json("""{"mode": "custom", "value": "Our own words"}"""), BriefEdits.answerOf(custom, "5.a.ii"))
        assertEquals(json("""{"mode": "blank", "value": "Our own words"}"""), BriefEdits.answerOf(BriefEdits.setMode("5.a.ii", "blank")(custom), "5.a.ii"))
        assertNull(BriefEdits.answerOf(BriefEdits.setMode("5.a.ii", "blank")(standard), "5.a.ii"))           // nothing typed: no answer at all
        val field = BriefEdits.setProcedureField("5.a.v", "baseAltitude", "3,000 ft")(BriefEdits.setMode("5.a.v", "standard")(blank))
        assertEquals(json("""{"mode": "standard", "fields": {"baseAltitude": "3,000 ft"}}"""), BriefEdits.answerOf(field, "5.a.v"))
    }

    @Test
    fun `use standard for all sets every procedure of the paragraph's steps, keeping what was typed`() {
        val five = amb.template.paragraphs.first { it.number == "5" }
        val ids = five.sections.map { it.id }.toSet()
        val steps = model["steps"]!!.jsonArray.map { it.jsonObject }.filter { s -> s["sections"]!!.jsonArray.any { it.jsonPrimitive.content in ids } }
        val procedures = five.sections.flatMap { s -> s.items.filter { it.kind == "procedure" && it.standard != null }.map { s.id to it.id } }
        val all = BriefEdits.useStandardForAll(steps, procedures)(document)
        procedures.forEach { (section, item) -> assertEquals(JsonPrimitive("standard"), BriefEdits.answerOf(all, "$section.$item")!!["mode"], "$section.$item") }
        assertEquals(JsonPrimitive("Lead calls it; old lead takes trail."), BriefEdits.answerOf(all, "5.a.i")!!["value"])
        assertEquals(BriefEdits.answerOf(document, "2.b"), BriefEdits.answerOf(all, "2.b"))
    }

    @Test
    fun `a route point is made a rally point, changed, and made one no longer`() {
        val inbound = BriefEdits.setRally("route:set-opdk/r-alderaan", "p3", "inbound")(blank)
        assertEquals("inbound", BriefEdits.rallyLabel(inbound, "route:set-opdk/r-alderaan", "p3"))
        val outbound = BriefEdits.setRally("route:set-opdk/r-alderaan", "p3", "outbound")(inbound)
        assertEquals(1, outbound["rallyPoints"]!!.jsonArray.size)
        assertEquals("outbound", BriefEdits.rallyLabel(outbound, "route:set-opdk/r-alderaan", "p3"))
        val none = BriefEdits.setRally("route:set-opdk/r-alderaan", "p3", "")(outbound)
        assertEquals(JsonArray(emptyList()), none["rallyPoints"])
        assertEquals("", BriefEdits.rallyLabel(none, "route:set-opdk/r-alderaan", "p3"))
    }

    @Test
    fun `a script rewritten is kept until reset, and a blank one goes back to the fields' script`() {
        val edited = BriefEdits.setScript("2", "Our words.")(blank)
        assertTrue(BriefEdits.scriptEdited(edited, "2"))
        assertFalse(BriefEdits.scriptEdited(BriefEdits.setScript("2", null)(edited), "2"))
        assertFalse(BriefEdits.scriptEdited(BriefEdits.setScript("2", "  ")(edited), "2"))
    }

    @Test
    fun `an option is set among the others`() {
        val one = BriefEdits.setOption("strips", JsonPrimitive(false))(blank)
        val two = BriefEdits.setOption("stripScale", JsonPrimitive(250000))(one)
        assertEquals(json("""{"strips": false, "stripScale": 250000}"""), two["options"])
        assertEquals(JsonPrimitive(250000), BriefEdits.option(two, "stripScale"))
        assertNull(BriefEdits.option(blank, "paper"))
    }

    @Test
    fun `the foot counts what is filled and what prints as write-in lines, and each paragraph says how it stands`() {
        assertEquals(33 to 42, BriefEdits.counts(model))
        val steps = model["steps"]!!.jsonArray.map { it.jsonObject }
        val status = amb.template.paragraphs.associate { it.number to BriefEdits.statusOf(it, steps) }
        assertEquals(BriefEdits.Status.AllSet, status["1"])
        assertEquals(BriefEdits.Status.ToAdd(1), status["2"])
        assertEquals(BriefEdits.Status.ToAdd(21), status["4"])
        assertEquals(BriefEdits.Status.NothingToFill, status["8"])
    }

    @Test
    fun `a standard procedure's summary has its values and the person's fields in place, a blank one a write-in line`() {
        val iimc = amb.standards.procedures.getValue("iimc")
        val text = BriefEdits.summaryOf(iimc, amb.standards, json("""{"baseAltitude": "3,000 ft MSL", "baseHeading": "  "}"""))
        assertTrue(text.startsWith("Base 3,000 ft MSL, heading ${CompileBrief.WRITE_IN}, airspeed ${CompileBrief.WRITE_IN};"), text)
        assertFalse("{" in text)
        val visual = BriefEdits.summaryOf(amb.standards.procedures.getValue("lostVisual"), amb.standards, null)
        assertTrue(visual.endsWith("Rally points: …."), visual)                                                  // what the web's summary says for a binding
    }
}
