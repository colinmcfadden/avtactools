package app.ezpztac.briefs

import app.ezpztac.brief.SandtableSteps
import app.ezpztac.brief.SandtableSteps.Colours
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the map shows for a step of the sandtable (the web's `attachSandtable`, SandtableLayer.jsx), as plain data, from OP DK's compiled brief: the lit route over
 * the dimmed ones, the LZ/PZ in focus close, the rally points on their step, threats always; and the GeoJSON MapLibre is handed.
 */
class SandtableSceneTest {
    private val model = BriefFixtures.model
    private val geometry = model["geometry"]!!.jsonObject
    private val steps = SandtableSteps.listed(model)

    private fun sceneOf(id: String): SandtableScene = SandtableScene.of(SandtableSteps.look(model, steps.indexOfFirst { it.id == id }), geometry)

    private fun routePoints(ref: String) = geometry["routes"]!!.jsonObject[ref]!!.jsonObject["points"]!!.jsonArray

    private val alderaan = "route:set-opdk/r-alderaan"
    private val bespin = "route:set-opdk/r-bespin"

    @Test
    fun `a route step lights its route, drawn last over the others dimmed`() {
        val scene = sceneOf("4.c@$alderaan")
        // Each route's casing then its line: BESPIN first (dimmed, its own colour), ALDERAAN last (lit, the focus colour, heavier).
        assertEquals(
            listOf(
                Triple(Colours.CASING, 8.0, 0.6 * SandtableScene.DIMMED), Triple("#0A84FF", 4.0, SandtableScene.DIMMED),
                Triple(Colours.CASING, 10.0, 0.6), Triple(Colours.FOCUS, 6.0, 1.0),
            ),
            scene.lines.map { Triple(it.colour, it.width, it.opacity) },
        )
        assertEquals(listOf(0, 1, 2, 3), scene.lines.map { it.order })
        // A dot at each named point, in its route's look.
        val bespinDots = scene.dots.take(routePoints(bespin).size)
        assertTrue(bespinDots.all { it.colour == "#0A84FF" && it.radius == 5.0 && it.opacity == SandtableScene.DIMMED && it.fillOpacity == SandtableScene.DIMMED })
        val alderaanDots = scene.dots.drop(routePoints(bespin).size).take(routePoints(alderaan).size)
        assertTrue(alderaanDots.all { it.colour == Colours.FOCUS && it.radius == 6.0 && it.opacity == 1.0 && it.fill == Colours.CASING })
        // Only the lit route's points are named, then every LZ/PZ and threat.
        val pointNames = routePoints(alderaan).map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(pointNames, scene.labels.filter { it.size == LabelSize.SMALL && it.colour == SandtableScene.WHITE }.map { it.text })
        assertEquals(
            listOf("LZ BLUEBIRD", "LZ HAWK", "PZ IBIS", "FARP BUCEES", "LZ DECOY"),
            scene.labels.filter { it.size == LabelSize.NORMAL }.map { it.text },
        )
        assertTrue("no LZ/PZ is close on a route step", scene.areas.isEmpty() && scene.marks.all { it is FigureMark.Square && it.size == 14.0 })
    }

    @Test
    fun `an LZ-PZ step draws it close, with its boundary, its aircraft and its landing heading`() {
        val scene = sceneOf("4.e@lz:lz-hawk")
        val hawk = geometry["stops"]!!.jsonObject["lz:lz-hawk"]!!.jsonObject
        val boundary = hawk["boundary"]!!.jsonArray.map { p -> p.jsonArray.let { LatLon(it[0].jsonPrimitive.content.toDouble(), it[1].jsonPrimitive.content.toDouble()) } }
        assertEquals(listOf(FigureArea(boundary, Colours.ROLES["LZ"]!!, 3.0, 0.16)), scene.areas)
        val at = LatLon(34.5501, -84.1102)
        val aircraft = scene.dots.filter { it.colour == Colours.AIRCRAFT }
        assertEquals(2, aircraft.size)
        assertTrue(aircraft.all { it.at == at && it.radius == 7.0 && it.stroke == 2.5 && it.fill == Colours.CASING && it.fillOpacity == 1.0 })
        val arrows = scene.marks.filterIsInstance<FigureMark.Arrow>()
        assertEquals(
            listOf(
                FigureMark.Arrow(at, 0.0, 30.0, Colours.AIRCRAFT, fromTip = false, rank = RANK_AIRCRAFT),
                FigureMark.Arrow(at, 0.0, 30.0, Colours.AIRCRAFT, fromTip = false, rank = RANK_AIRCRAFT),
                FigureMark.Arrow(at, 203.0, 96.0, SandtableScene.WHITE, fromTip = true, rank = RANK_LANDING),
            ),
            arrows,
        )
        // HAWK's square larger, its name larger; the other LZ/PZs as on any step.
        val squares = scene.marks.filterIsInstance<FigureMark.Square>()
        assertEquals(listOf(14.0, 18.0, 14.0, 14.0, 14.0), squares.map { it.size })
        assertEquals(LabelSize.LARGE, scene.labels.single { it.text == "LZ HAWK" }.size)
        // A step about an LZ/PZ lights no route and names no route point.
        assertTrue(scene.lines.none { it.colour == Colours.FOCUS })
        assertTrue(scene.labels.none { it.size == LabelSize.SMALL && it.colour == SandtableScene.WHITE })
    }

    @Test
    fun `a PZ and a FARP are drawn in their roles' colours, and an LZ-PZ with no landing heading has no arrow`() {
        val ibis = sceneOf("4.d@lz:pz-ibis")
        assertEquals(Colours.ROLES["PZ"], ibis.areas.single().colour)
        assertEquals(Colours.ROLES["PZ"], ibis.marks.filterIsInstance<FigureMark.Square>().single { it.size == 18.0 }.colour)
        val decoy = sceneOf("4.e@lz:lz-decoy")
        assertTrue(decoy.marks.none { it is FigureMark.Arrow })
        assertEquals(Colours.ROLES["FARP"], sceneOf("4.f@lz:farp-bucees").areas.single().colour)
    }

    @Test
    fun `the rally points are drawn on the rally step only, each with its side`() {
        val rallyStep = steps.indices.first { SandtableSteps.look(model, it)!!.rally }
        val scene = SandtableScene.of(SandtableSteps.look(model, rallyStep), geometry)
        assertEquals(2, scene.marks.filterIsInstance<FigureMark.Rally>().size)
        assertEquals(listOf("ACP2 (inbound)", "RP5 (outbound)"), scene.labels.filter { it.colour == Colours.RALLY }.map { it.text })
        steps.indices.filter { it != rallyStep }.forEach { i ->
            assertTrue(SandtableScene.of(SandtableSteps.look(model, i), geometry).marks.none { it is FigureMark.Rally })
        }
    }

    @Test
    fun `threats are drawn on every step, red, with their names`() {
        steps.indices.forEach { i ->
            val scene = SandtableScene.of(SandtableSteps.look(model, i), geometry)
            val threat = scene.dots.single { it.colour == Colours.THREAT }
            assertEquals(FigureDot(LatLon(34.4012, -84.0011), 9.0, Colours.THREAT, 3.0, 1.0, Colours.THREAT, 0.35, threat.order), threat)
            assertEquals(FigureLabel(LatLon(34.4012, -84.0011), "ZSU-23-4", Colours.THREAT_LABEL, LabelSize.SMALL), scene.labels.single { it.colour == Colours.THREAT_LABEL })
        }
    }

    @Test
    fun `a step with no figure shows the whole plan, unlit, with every route's points named on the overview`() {
        val none = steps.indices.first { SandtableSteps.look(model, it)!!.kind == null }
        val scene = SandtableScene.of(SandtableSteps.look(model, none), geometry)
        assertTrue(scene.lines.none { it.colour == Colours.FOCUS || it.opacity < 0.6 })
        assertEquals(listOf("#0A84FF", "#FF453A").sorted(), scene.lines.filter { it.colour != Colours.CASING }.map { it.colour }.sorted())
        assertTrue(scene.labels.none { it.size == LabelSize.SMALL && it.colour == SandtableScene.WHITE })
        val overview = SandtableScene.of(SandtableSteps.look(model, steps.indexOfFirst { it.id == "1" }), geometry)
        assertEquals(routePoints(alderaan).size + routePoints(bespin).size, overview.labels.count { it.size == LabelSize.SMALL && it.colour == SandtableScene.WHITE })
    }

    @Test
    fun `nothing is drawn for no step, nor from a brief with no geometry`() {
        assertEquals(SandtableScene.EMPTY, SandtableScene.of(null, geometry))
        assertTrue(SandtableScene.of(SandtableSteps.look(model, 0), null).isEmpty)
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `what the brief gives that cannot be drawn is left out, and a colour is only ever #RRGGBB`() {
        val odd = json(
            """{"routes":{"r":{"color":"red; stroke: url(x)","line":[[1,2],[1,"3"],null,[2,3]],"points":[{"name":"A","lat":1,"lon":2},{"name":"B","lat":"1","lon":2},{"name":"","lat":2,"lon":3}]},
                          "s":{"color":"#00ff00","line":[[1,2]],"points":[]}},
                "stops":{"lz":{"name":0,"role":"nope","lat":5,"lon":6,"boundary":[[5,6],[5,7]],"aircraft":[{"lat":5,"lon":6,"heading":"90"},{"lat":5}],"landingHeading":"203"}},
                "threats":[{"lat":1,"lon":1},{"name":"SA-8","lat":null,"lon":1}],"rally":[]}""",
        )
        val view = SandtableSteps.View(
            stepId = "x", kind = "stop", focus = "lz", bounds = null,
            routes = listOf(SandtableSteps.RouteLook("r", lit = false, dimmed = false, labelled = true), SandtableSteps.RouteLook("s", lit = false, dimmed = false, labelled = true)),
            stops = listOf(SandtableSteps.StopLook("lz", close = true)), rally = false,
        )
        val scene = SandtableScene.of(view, odd)
        // Route r: a line through its two good points in the route colour (its own is not a colour), a dot and a name for each named point with a place. Route s:
        // one point, no line.
        assertEquals(listOf(listOf(LatLon(1.0, 2.0), LatLon(2.0, 3.0))), scene.lines.filter { it.colour != Colours.CASING }.map { it.points })
        assertEquals(Colours.ROUTE, scene.lines[1].colour)
        assertEquals(listOf("A"), scene.labels.filter { it.size == LabelSize.SMALL && it.colour == SandtableScene.WHITE }.map { it.text })
        // The LZ: two corners are no boundary; an aircraft without a place is none, and one whose heading is text points north (the web's `finite` takes no text);
        // a landing heading given as text is none; a name of 0 is no name; a role the figures do not know is an LZ's colour.
        assertTrue(scene.areas.isEmpty())
        assertEquals(listOf(0.0), scene.marks.filterIsInstance<FigureMark.Arrow>().map { it.heading })
        assertEquals(Colours.ROLES["LZ"], scene.marks.filterIsInstance<FigureMark.Square>().single().colour)
        assertTrue(scene.labels.none { it.size == LabelSize.LARGE })
        // A threat with no name is "Threat"; one with no place is not drawn.
        assertEquals(listOf("Threat"), scene.labels.filter { it.colour == Colours.THREAT_LABEL }.map { it.text })
    }

    @Test
    fun `the GeoJSON carries each shape's own style, a ring closed, a dot's fill half a ring inside`() {
        val scene = sceneOf("4.e@lz:lz-hawk")
        val features = Json.parseToJsonElement(scene.geoJson()).jsonObject["features"]!!.jsonArray.map { it.jsonObject }
        val roles = features.groupBy { it["properties"]!!.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(scene.lines.size, roles["line"]!!.size)
        val area = roles["area"]!!.single()
        val ring = area["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(ring.first(), ring.last())
        assertEquals(scene.areas.single().ring.size + 1, ring.size)
        assertEquals(listOf(-84.11120000000001, 34.5491), ring[0].jsonArray.map { it.jsonPrimitive.content.toDouble() })
        val dot = roles["dot"]!!.first { it["properties"]!!.jsonObject["colour"]!!.jsonPrimitive.content == Colours.AIRCRAFT }["properties"]!!.jsonObject
        assertEquals(7.0, dot["radius"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(5.75, dot["fillRadius"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val line = roles["line"]!!.first()["properties"]!!.jsonObject
        assertEquals(setOf("role", "colour", "width", "opacity", "order"), line.keys)
        assertEquals(scene.dots.size, roles["dot"]!!.size)
    }

    @Test
    fun `longitudes past 180 are handed on as the brief gives them`() {
        val across = json("""{"routes":{"r":{"line":[[51.9,179.9],[51.9,180.1]],"points":[]}},"stops":{}}""") as JsonObject
        val view = SandtableSteps.View("x", "route", "r", null, listOf(SandtableSteps.RouteLook("r", lit = true, dimmed = false, labelled = true)), emptyList(), rally = false)
        val coordinates = Json.parseToJsonElement(SandtableScene.of(view, across).geoJson()).jsonObject["features"]!!.jsonArray[0]
            .jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(listOf(179.9, 180.1), coordinates.map { (it as JsonArray)[0].jsonPrimitive.content.toDouble() })
    }
}
