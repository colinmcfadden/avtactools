package app.ezpztac.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The threat form: what a person may type, what each refusal says, and that a threat opened and applied comes back as it was. */
class ThreatDraftTest {
    private val here = LatLon(34.783817, -84.08219)

    private fun valid(draft: ThreatDraft, base: Threat? = null) = (draft.check(here, base) as ThreatDraft.Checked.Valid).threat

    private fun refused(draft: ThreatDraft) = (draft.check(here) as ThreatDraft.Checked.Refused).message

    private fun withRadar(index: Int, change: (RadarDraft) -> RadarDraft): ThreatDraft {
        val draft = ThreatDraft()
        return draft.copy(radars = draft.radars.mapIndexed { i, r -> if (i == index) change(r) else r })
    }

    // -- A threat with the web's defaults ------------------------------------------------------------------------------

    @Test
    fun `the default form makes the threat the web makes, a detection and an engagement radar at the place given`() {
        val t = valid(ThreatDraft())
        assertEquals("Threat", t.name)
        assertEquals(Radars.DEFAULT_MILSTD_ID, t.milstdId)
        assertEquals(here.lat, t.lat, 0.0)
        assertEquals(here.lon, t.lon, 0.0)
        assertEquals("SOF", t.source)
        assertEquals("", t.information)
        assertTrue(t.showThreat)
        assertEquals(Radars.defaultPair(), t.radars)
    }

    @Test
    fun `a new threat is named for how many there are`() {
        assertEquals("Threat 1", ThreatDraft.forNew(0).name)
        assertEquals("Threat 4", ThreatDraft.forNew(3).name)
    }

    @Test
    fun `a threat opened to be changed and applied unchanged comes back as it was, colours and all`() {
        val odd = Threat(
            "SA-6", "SHGPEWRR------", 34.5, -84.5, "Moved at night", "HUMINT", showThreat = false,
            radars = listOf(Radars.default(Radars.ENGAGEMENT).copy(rangeNmi = 13.5, antennaHeightFt = 40.0, aglNotMsl = false, showMask = false, showRangeRings = false)),
        )
        assertEquals(odd, valid(ThreatDraft.of(odd), base = odd).copy(lat = 34.5, lon = -84.5))
        assertFalse(valid(ThreatDraft.of(odd), base = odd).showThreat)                          // visibility on the map is the picture's; showThreat is the file's, and stays
    }

    // -- Identity ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a threat needs a name`() {
        assertEquals("A threat needs a name.", refused(ThreatDraft(name = "   ")))
        assertEquals("A threat needs a name.", refused(ThreatDraft(name = "")))
    }

    @Test
    fun `a name is trimmed and cut to what AMPS holds, by characters`() {
        assertEquals("SA-6", valid(ThreatDraft(name = "  SA-6  ")).name)
        val long = "A".repeat(49) + "🚀🚀"                                    // 49 letters and two emoji: 51 characters, 53 UTF-16 units
        assertEquals("A".repeat(49) + "🚀", valid(ThreatDraft(name = long)).name)       // the emoji at the end is kept whole, the next is dropped
    }

    @Test
    fun `information and source are cut, and a blank source is the web's default`() {
        assertEquals(255, valid(ThreatDraft(information = "x".repeat(300))).information.length)
        assertEquals("SOF", valid(ThreatDraft(source = "  ")).source)
        assertEquals("S".repeat(32), valid(ThreatDraft(source = "S".repeat(40))).source)
        assertEquals("note", valid(ThreatDraft(information = "  note ")).information)
    }

    @Test
    fun `the symbol code is upper case, 1 to 15 of letters digits hyphens and asterisks`() {
        assertEquals("SHGPEWRR------", valid(ThreatDraft(milstdId = " shgpewrr------ ")).milstdId)
        assertEquals("S*G*-", valid(ThreatDraft(milstdId = "S*G*-")).milstdId)
        val message = "The symbol code must be 1 to 15 letters, digits, hyphens or asterisks."
        assertEquals(message, refused(ThreatDraft(milstdId = "")))
        assertEquals(message, refused(ThreatDraft(milstdId = "S".repeat(16))))
        assertEquals(message, refused(ThreatDraft(milstdId = "SHG PEW")))
        assertEquals(message, refused(ThreatDraft(milstdId = "SHG;DROP")))
        assertEquals(15, valid(ThreatDraft(milstdId = "S".repeat(15))).milstdId.length)
    }

    // -- A radar's numbers ---------------------------------------------------------------------------------------------------

    @Test
    fun `a range is between a tenth of a mile and five hundred, and the words name the radar`() {
        assertEquals(0.1, valid(withRadar(0) { it.copy(rangeNmi = "0.1") }).radars[0].rangeNmi, 0.0)
        assertEquals(500.0, valid(withRadar(1) { it.copy(rangeNmi = "500") }).radars[1].rangeNmi, 0.0)
        assertEquals("Detection range must be between 0.1 and 500.", refused(withRadar(0) { it.copy(rangeNmi = "0.09") }))
        assertEquals("Engagement range must be between 0.1 and 500.", refused(withRadar(1) { it.copy(rangeNmi = "500.1") }))
        assertEquals("Detection range must be between 0.1 and 500.", refused(withRadar(0) { it.copy(rangeNmi = "0") }))
        assertEquals("Detection range must be between 0.1 and 500.", refused(withRadar(0) { it.copy(rangeNmi = "-5") }))
    }

    @Test
    fun `a blank or unreadable number is refused with the field named, never turned into zero`() {
        assertEquals("Detection range needs a number.", refused(withRadar(0) { it.copy(rangeNmi = "") }))
        assertEquals("Detection range is not a number.", refused(withRadar(0) { it.copy(rangeNmi = "12 nmi") }))
        assertEquals("Engagement antenna height is not a number.", refused(withRadar(1) { it.copy(antennaHeightFt = "1,5") }))
        assertEquals("Engagement antenna height needs a number.", refused(withRadar(1) { it.copy(antennaHeightFt = "  ") }))
        assertEquals("Detection range is not a number.", refused(withRadar(0) { it.copy(rangeNmi = "1e2") }))
        assertEquals("Detection range is not a number.", refused(withRadar(0) { it.copy(rangeNmi = "NaN") }))
    }

    @Test
    fun `an antenna is between nothing and ten thousand feet`() {
        assertEquals(0.0, valid(withRadar(0) { it.copy(antennaHeightFt = "0") }).radars[0].antennaHeightFt, 0.0)
        assertEquals(10_000.0, valid(withRadar(0) { it.copy(antennaHeightFt = "10000") }).radars[0].antennaHeightFt, 0.0)
        assertEquals("Detection antenna height must be between 0 and 10000.", refused(withRadar(0) { it.copy(antennaHeightFt = "10000.1") }))
        assertEquals("Detection antenna height must be between 0 and 10000.", refused(withRadar(0) { it.copy(antennaHeightFt = "-1") }))
    }

    @Test
    fun `band altitudes are whole feet from the ground to fifty thousand`() {
        val set = { text: String -> withRadar(1) { r -> r.copy(bandAltitudesFt = listOf("50", text, "500")) } }
        assertEquals(0.0, valid(set("0")).radars[1].bands[1].altFt, 0.0)
        assertEquals(50_000.0, valid(set("50000")).radars[1].bands[1].altFt, 0.0)
        assertEquals("Engagement band 2 altitude must be between 0 and 50000.", refused(set("50001")))
        assertEquals("Engagement band 2 altitude must be between 0 and 50000.", refused(set("-1")))
        assertEquals("Engagement band 2 altitude must be a whole number of feet.", refused(set("250.5")))     // a .ths holds integers: it would be cut without a word
        assertEquals("Engagement band 2 altitude needs a number.", refused(set("")))
        assertEquals(250.0, valid(set("250.0")).radars[1].bands[1].altFt, 0.0)                                  // a whole number written with a point is whole
    }

    @Test
    fun `the first thing wrong is what is said, detection before engagement and the range before the bands`() {
        val draft = ThreatDraft().let { d ->
            d.copy(
                radars = listOf(
                    d.radars[0].copy(bandAltitudesFt = listOf("x", "250", "500")),
                    d.radars[1].copy(rangeNmi = "x"),
                ),
            )
        }
        assertEquals("Detection band 1 altitude is not a number.", refused(draft))
        assertEquals("Detection range needs a number.", refused(draft.copy(radars = listOf(draft.radars[0].copy(rangeNmi = ""), draft.radars[1]))))
    }

    @Test
    fun `the toggles and the bands' visibility are carried, and the bands keep their colours`() {
        val t = valid(
            withRadar(0) { it.copy(aglNotMsl = false, showMask = false, showRangeRings = false, bandsViewable = listOf(true, false, true)) },
        )
        val r = t.radars[0]
        assertFalse(r.aglNotMsl)
        assertFalse(r.showMask)
        assertFalse(r.showRangeRings)
        assertEquals(listOf(true, false, true), r.bands.map { it.viewable })
        assertEquals(Radars.default(Radars.DETECTION).bands.map { it.color to it.colorIndex }, r.bands.map { it.color to it.colorIndex })
    }

    @Test
    fun `a radar with fewer than three bands is kept as it is, not padded`() {
        val one = Radars.default(Radars.DETECTION).copy(bands = Radars.default(Radars.DETECTION).bands.take(1))
        val t = Threat("X", "SHGPEWRR------", 1.0, 2.0, "", "SOF", radars = listOf(one))
        assertEquals(1, valid(ThreatDraft.of(t)).radars.single().bands.size)
    }

    @Test
    fun `numbers are put back in a field as a person would type them`() {
        val d = RadarDraft.of(Radars.default(Radars.DETECTION).copy(rangeNmi = 12.5, antennaHeightFt = 20.0))
        assertEquals("12.5", d.rangeNmi)
        assertEquals("20", d.antennaHeightFt)
        assertEquals(listOf("50", "250", "500"), d.bandAltitudesFt)
    }

    // -- Telling threats apart ----------------------------------------------------------------------------------------------

    @Test
    fun `an id is nudged past any taken, so two threats made in one millisecond are two`() {
        assertEquals("threat-100-0", ThreatIds.next(emptyList(), 100))
        assertEquals("threat-100-1", ThreatIds.next(listOf("threat-100-0"), 100))
        assertEquals("threat-100-2", ThreatIds.next(listOf("threat-100-0", "threat-100-1"), 100))
        assertEquals("threat-200-0", ThreatIds.next(listOf("threat-100-0"), 200))
    }
}
