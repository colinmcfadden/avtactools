package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Radar
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What a brief is built from, in the shape the brief's compiler reads (the web's briefPlan.js). */
class BriefPlansTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val hawk = json("""{"id": "lz-1", "name": "LZ HAWK", "target": {"lat": 34.7, "lng": -84.0}}""")
    private val routes = json("""{"version": 1, "routes": [{"id": "r-1", "name": "ALDERAAN", "points": []}]}""")

    @Test
    fun `a pack's plan is its LZ-PZs and route sets, by item uuid, and nothing else in it`() {
        val plan = BriefPlans.fromPack(
            "OP DK",
            listOf(
                PackItemView("lz-1", "lz", "LZ HAWK", hawk),
                PackItemView("rt-1", "route", "ROUTES 1", routes),
                PackItemView("ps-1", "pointset", "POINTS", json("""{"points": []}""")),
                PackItemView("br-1", "brief", "OP DK BRIEF", json("""{"version": 1}""")),
                PackItemView("lz-2", "lz", "BEING MADE", JsonNull),
            ),
        )
        assertEquals(JsonPrimitive("OP DK"), plan["name"])
        val lzs = plan["lzs"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("lz-1"), lzs.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("LZ HAWK", lzs.single()["name"]!!.jsonPrimitive.content)
        assertSame(hawk, lzs.single()["diagram"])                                  // the pack's own document, not a copy
        val sets = plan["routeSets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("rt-1"), sets.map { it["id"]!!.jsonPrimitive.content })
        assertSame(routes, sets.single()["data"])
    }

    @Test
    fun `the session's plan is its open LZ-PZs and its route sets with routes, as version 1 documents`() {
        val plan = BriefPlans.fromSession(
            lzs = listOf(BriefPlans.SessionItem("lz-1", "LZ HAWK", hawk)),
            routeSets = listOf(
                BriefPlans.SessionItem("set-1", "SKETCHED ROUTES", json("""{"version": 3, "routes": [{"id": "r-1"}], "extra": true}""")),
                BriefPlans.SessionItem("set-2", "EMPTY", json("""{"version": 1, "routes": []}""")),
                BriefPlans.SessionItem("set-3", "NOT A SET", json("""{"version": 1}""")),
            ),
        )
        assertEquals(JsonPrimitive(""), plan["name"])
        assertEquals(listOf("lz-1"), plan["lzs"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val sets = plan["routeSets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("set-1"), sets.map { it["id"]!!.jsonPrimitive.content })
        // The web's `{version: 1, routes: set.routes}`: the set's routes, whatever else its document holds.
        assertEquals(json("""{"version": 1, "routes": [{"id": "r-1"}]}"""), sets.single()["data"])
    }

    @Test
    fun `the threats go in as their name and place, the aircraft as the API gives them, and the mission aircraft by slug`() {
        val sam = ThreatEntry("threat-1", Threat("SA-8", "SNGPUSAG", 34.5, -84.2, "info", "source", radars = emptyList<Radar>()))
        val profile = AircraftProfile(id = 3, slug = "uh60m", name = "UH-60M Black Hawk", designation = "UH-60M")
        val plan = BriefPlans.withExtras(BriefPlans.fromSession(emptyList(), emptyList()), listOf(sam), listOf(profile), "uh60m")
        assertEquals(json("""{"name": "SA-8", "lat": 34.5, "lon": -84.2}"""), plan["threats"]!!.jsonArray.single())
        val sent = plan["aircraftProfiles"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(3), sent["id"])
        assertEquals(JsonPrimitive("uh60m"), sent["slug"])
        assertEquals(JsonPrimitive("UH-60M"), sent["designation"])
        assertEquals(JsonPrimitive("uh60m"), plan["missionAircraft"])
        assertEquals(JsonNull, BriefPlans.withExtras(plan, emptyList(), emptyList(), null)["missionAircraft"])
        assertEquals(JsonArray(emptyList()), BriefPlans.withExtras(plan, emptyList(), emptyList(), null)["threats"])
    }

    @Test
    fun `what a brief is built from is said as the menu says it`() {
        val one = BriefPlans.fromSession(listOf(BriefPlans.SessionItem("lz-1", "A", hawk)), listOf(BriefPlans.SessionItem("s", "S", routes)))
        assertEquals("1 LZ/PZ, 1 route set", BriefPlans.summary(one))
        assertEquals("1 LZ/PZ, 1 route set, 1 threat", BriefPlans.summary(one, threatCount = 1))
        assertEquals("0 LZ/PZs, 0 route sets, 3 threats", BriefPlans.summary(BriefPlans.fromSession(emptyList(), emptyList()), threatCount = 3))
    }
}
