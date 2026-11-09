package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the map says of the FAA's charts: every case of `contracts/fixtures/charts/words.json`, over the same recorded `coverage.json` the web's are held to. */
class ChartWordsFixtureTest {
    private val fixture = Fixtures.load("charts/words.json")
    private val coverage = requireNotNull(ChartCoverage.of(Fixtures.load("charts/coverage.json")))
    private val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }

    private val live = fixture.getValue("live").jsonObject.let { recorded ->
        VfrLive(
            build = recorded.getValue("build").jsonPrimitive.content, tiles = "", coverage = "", tileSize = 512, minZoom = 5, maxZoom = 14,
            effective = recorded.getValue("effective").jsonPrimitive.content, expires = recorded.getValue("expires").jsonPrimitive.content,
            bounds = requireNotNull(ChartBox.of(recorded.getValue("bounds").jsonArray.map { it.jsonPrimitive.double })),
            fallback = recorded.getValue("fallback").jsonArray.map { box -> requireNotNull(ChartBox.of(box.jsonArray.map { it.jsonPrimitive.double })) },
        )
    }

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double

    private fun said(case: JsonObject): ChartLines {
        val view = case.getValue("view").jsonObject
        val center = case.getValue("center").jsonObject
        return ChartWords.of(
            live = if (case.getValue("live").jsonPrimitive.boolean) live else null,
            coverage = if (case.getValue("coverage").jsonPrimitive.boolean) coverage else null,
            view = ChartBox(view.number("west"), view.number("south"), view.number("east"), view.number("north")),
            center = LatLon(center.number("lat"), center.number("lon")),
            zoom = case.number("zoom"),
            now = requireNotNull(chartInstant(case.getValue("now").jsonPrimitive.content)),
            finer = case["finer"]?.jsonPrimitive?.int ?: 0,
        )
    }

    private fun expected(case: JsonObject): ChartLines {
        val words = case.getValue("expected").jsonObject
        return ChartLines(
            lines = words.getValue("lines").jsonArray.map { it.jsonObject }.map { ChartLine(it.getValue("text").jsonPrimitive.content, it.getValue("expired").jsonPrimitive.boolean) },
            enlarged = words.getValue("enlarged").takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `the words are the web's in every case`() {
        assertTrue(cases.size >= 29)
        for (case in cases) assertEquals(case.getValue("name").jsonPrimitive.content, expected(case), said(case))
    }

    @Test
    fun `every chart of the recorded coverage is read`() {
        assertEquals(Fixtures.load("charts/coverage.json").getValue("charts").jsonArray.size, coverage.charts.size)
        assertNotEquals(0, coverage.charts.size)
    }

    @Test
    fun `the next expiry is the soonest still to come, and none once all have passed`() {
        val first = coverage.charts.minOf { it.edition.expiresAt }
        val last = maxOf(coverage.charts.maxOf { it.edition.expiresAt }, requireNotNull(chartInstant(live.expires)))
        assertEquals(first, ChartWords.nextExpiry(live, coverage, first - 1))
        assertEquals(null, ChartWords.nextExpiry(live, coverage, last))
        assertEquals(null, ChartWords.nextExpiry(null, null, 0))
    }
}
