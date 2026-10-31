package app.ezpztac.data

import app.ezpztac.formats.ThsReader
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** Threats in and out of `.ths` files: what a file may hold on the way in, and what the one file out is called and holds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThreatTransferTest {
    private val templateBytes = File("../../backend/threat_template.ths").also { check(it.isFile) }.readBytes()
    private val transfer = ThreatTransfer(ThsWriter { templateBytes })
    private val now = Instant.parse("2026-10-02T12:34:56Z")
    private val errors = Fixtures.load("threats/parse.json").getValue("errors").jsonObject

    private fun message(key: String) = (errors.getValue(key) as JsonPrimitive).content

    private fun threat(name: String) = Threat(name, "SHGPEWRR------", 34.5, -84.5, "", "SOF", radars = Radars.defaultPair())

    private fun entries(vararg names: String) = names.mapIndexed { i, n -> ThreatEntry("t$i", threat(n)) }

    // -- In ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a threat file the backend wrote is read as the threats in it`() {
        val bytes = Fixtures.bytes("sqlite/threats.ths")
        assertEquals(ThreatTransfer.Imported.Threats(ThsReader.read(bytes)), transfer.import(bytes))
    }

    @Test
    fun `what is not a threat file is refused in the web's words`() {
        assertEquals(ThreatTransfer.Imported.Refused(message("not SQLite")), transfer.import("not a database".toByteArray()))
        assertEquals(ThreatTransfer.Imported.Refused(message("no THREATS table")), transfer.import(Fixtures.bytes("sqlite/deep-tree.db")))
        assertEquals(ThreatTransfer.Imported.Refused(message("no readable threats")), transfer.import(Fixtures.bytes("sqlite/threats-empty.ths")))
    }

    @Test
    fun `a file of more threats than a picture can hold is refused, and one at the limit is not`() {
        val atLimit = transfer.export(entries(*Array(ThreatTransfer.MAX_IMPORT) { "T$it" }), null, now) as ExportResult.Ready
        assertEquals(ThreatTransfer.MAX_IMPORT, (transfer.import(atLimit.bytes) as ThreatTransfer.Imported.Threats).threats.size)
        val over = transfer.export(entries(*Array(ThreatTransfer.MAX_IMPORT + 1) { "T$it" }), null, now) as ExportResult.Ready
        assertEquals(ThreatTransfer.Imported.Refused("This file holds 1,001 threats; the most it can import is 1,000."), transfer.import(over.bytes))
    }

    // -- Out --------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing to export is said so`() {
        assertEquals(ExportResult.Refused("There are no threats to export."), transfer.export(emptyList(), "MISSION 1", now))
    }

    @Test
    fun `the file holds every threat, hidden ones too, and reads back as they were`() {
        val list = listOf(ThreatEntry("a", threat("SHOWN")), ThreatEntry("b", threat("HIDDEN"), visible = false))
        val ready = transfer.export(list, null, now) as ExportResult.Ready
        assertEquals(listOf("SHOWN", "HIDDEN"), ThsReader.read(ready.bytes).map { it.name })
        assertEquals(null, ready.warning)
        // The same threats at the same time are the same file's rows (the writer is held to the backend's own file in ThsWriterTest).
        assertArrayEquals(ready.bytes.copyOf(), (transfer.export(list, null, now) as ExportResult.Ready).bytes.copyOf())
    }

    @Test
    fun `the file is named for the mission it travels with, or threats`() {
        assertEquals("threats.ths", ThreatTransfer.fileName(null))
        assertEquals("threats.ths", ThreatTransfer.fileName(""))
        assertEquals("threats.ths", ThreatTransfer.fileName("   "))
        assertEquals("MISSION 1.ths", ThreatTransfer.fileName("MISSION 1"))
        assertEquals("MISSION 1.ths", ThreatTransfer.fileName("MISSION 1.msnx"))
        assertEquals("MISSION 1.ths", ThreatTransfer.fileName("MISSION 1.THS"))
        assertEquals("A_B.ths", ThreatTransfer.fileName("A/B"))
        assertEquals("A_B.ths", ThreatTransfer.fileName("A?B"))
        assertEquals("threats.ths", ThreatTransfer.fileName("../.."))
        assertTrue(ThreatTransfer.fileName("x".repeat(500)).length <= 84)
        assertEquals("MISSION 1.ths", (transfer.export(entries("A"), "MISSION 1.msnx", now) as ExportResult.Ready).fileName)
    }

    @Test
    fun `route exports are named as they were before the shared naming was extracted`() {
        assertEquals("ROUTE 1_ROUTE 2.msnx", RouteExport.fileName(listOf(route("ROUTE 1"), route("ROUTE 2"))))
        assertEquals("ROUTES.msnx", RouteExport.fileName(listOf(route("???"))))
    }

    private fun route(name: String) = app.ezpztac.model.SketchRoute(id = "sketch-1", name = name, color = "#ff0000", points = emptyList())
}
